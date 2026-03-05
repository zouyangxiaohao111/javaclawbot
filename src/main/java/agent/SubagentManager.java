package agent;

import agent.tool.ExecTool;
import agent.tool.FileSystemTools;
import agent.tool.ToolRegistry;
import agent.tool.WebFetchTool;
import agent.tool.WebSearchTool;
import bus.InboundMessage;
import bus.MessageBus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.ConfigSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import skills.SkillsLoader;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 子代理管理器：用于后台执行任务（Subagent）
 *
 * 职责：
 * 1) spawn：启动后台子任务
 * 2) _run_subagent：构建工具集 + 运行有限轮次的 agent loop
 * 3) _announce_result：把结果通过 MessageBus 注入为 system 入站消息，触发主代理总结
 *
 * 说明：
 * - 子代理不直接与用户对话，只把结果汇报给主代理
 * - 子代理拥有精简工具集：文件、shell、web（没有 message、spawn 等工具）
 */
public class SubagentManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMProvider provider;
    private final Path workspace;
    private final MessageBus bus;

    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String reasoningEffort; // 可选：部分模型支持
    private final String braveApiKey;
    private final ConfigSchema.ExecToolConfig execConfig;
    private final boolean restrictToWorkspace;

    /** taskId -> Future（后台任务） */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();
    /** sessionKey -> {taskId...} */
    private final ConcurrentHashMap<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    private final Executor executor;

    public SubagentManager(
            LLMProvider provider,
            Path workspace,
            MessageBus bus,
            String model,
            Double temperature,
            Integer maxTokens,
            String reasoningEffort,
            String braveApiKey,
            ConfigSchema.ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            Executor executor
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.bus = Objects.requireNonNull(bus, "bus");

        this.model = (model == null || model.isBlank()) ? provider.getDefaultModel() : model;
        this.temperature = (temperature == null) ? 0.7 : temperature;
        this.maxTokens = (maxTokens == null) ? 4096 : maxTokens;
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
        this.braveApiKey = braveApiKey;
        this.execConfig = (execConfig == null) ? new ConfigSchema.ExecToolConfig() : execConfig;
        this.restrictToWorkspace = restrictToWorkspace;

        // 默认使用通用线程池（也可外部注入）
        this.executor = (executor != null) ? executor : ForkJoinPool.commonPool();
    }

    /**
     * 启动一个子代理后台任务
     *
     * @param task          要执行的任务（自然语言）
     * @param label         可选：展示用标签；为空时从 task 截断生成
     * @param originChannel 任务来源渠道（默认 cli）
     * @param originChatId  任务来源 chat_id（默认 direct）
     * @param sessionKey    会话键，用于后续批量取消（可为空）
     */
    public CompletionStage<String> spawn(
            String task,
            String label,
            String originChannel,
            String originChatId,
            String sessionKey
    ) {
        final String taskId = uuid8();

        // 展示标签：label 优先，否则从 task 取前 30 字并按需加 ...
        final String displayLabel = (label != null && !label.isBlank())
                ? label
                : shortLabel(task, 30);

        final Origin origin = new Origin(
                (originChannel == null || originChannel.isBlank()) ? "cli" : originChannel,
                (originChatId == null || originChatId.isBlank()) ? "direct" : originChatId
        );

        // 后台执行：执行完成后自动清理 runningTasks / sessionTasks
        CompletableFuture<Void> bg = CompletableFuture.runAsync(() -> {
            try {
                runSubagent(taskId, task, displayLabel, origin).toCompletableFuture().join();
            } catch (CancellationException ce) {
                log.info("Subagent [{}] cancelled", taskId);
            } catch (Throwable t) {
                // runSubagent 内部已尝试 announce error；这里仅兜底日志
                log.error("Subagent [{}] crashed", taskId, t);
            }
        }, executor);

        runningTasks.put(taskId, bg);

        if (sessionKey != null && !sessionKey.isBlank()) {
            sessionTasks.compute(sessionKey, (k, set) -> {
                if (set == null) set = ConcurrentHashMap.newKeySet();
                set.add(taskId);
                return set;
            });
        }

        bg.whenComplete((v, ex) -> {
            runningTasks.remove(taskId);
            if (sessionKey != null && !sessionKey.isBlank()) {
                sessionTasks.computeIfPresent(sessionKey, (k, set) -> {
                    set.remove(taskId);
                    return set.isEmpty() ? null : set;
                });
            }
        });

        log.info("Spawned subagent [{}]: {}", taskId, displayLabel);

        return CompletableFuture.completedFuture(
                "Subagent [" + displayLabel + "] started (id: " + taskId + "). I'll notify you when it completes."
        );
    }

    /**
     * 执行子代理任务（有限轮次 agent loop）
     *
     * 行为：
     * - 构建工具集（文件/exec/web）
     * - messages = [system(subagent prompt), user(task)]
     * - 最多迭代 15 次：
     *   - 若模型返回 tool_calls：按顺序执行，并把 tool 结果追加到 messages
     *   - 否则：视为最终文本输出，结束循环
     * - 最终通过 bus 注入 system 入站消息，让主代理自然总结（1-2 句）
     */
    private CompletionStage<Void> runSubagent(
            String taskId,
            String task,
            String label,
            Origin origin
    ) {
        log.info("Subagent [{}] starting task: {}", taskId, label);

        // ========== 1) 工具集 ==========
        ToolRegistry tools = new ToolRegistry();
        Path allowedDir = restrictToWorkspace ? workspace : null;

        // 文件工具：受 allowedDir 限制时仅允许在工作区内访问
        tools.register(new FileSystemTools.ReadFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.WriteFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.EditFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ListDirTool(workspace, allowedDir));

        // shell 工具：默认 working_dir=workspace，并支持 restrict_to_workspace
        tools.register(new ExecTool(
                execConfig.getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                execConfig.getPathAppend()
        ));

        // web 工具：search/fetch
        tools.register(new WebSearchTool(braveApiKey, null));
        tools.register(new WebFetchTool(null));

        // ========== 2) 初始消息 ==========
        String systemPrompt = buildSubagentPrompt();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        messages.add(msg("user", task == null ? "" : task));

        // ========== 3) 运行有限轮次 ==========
        final int maxIterations = 15;
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        final Holder<String> finalResult = new Holder<>(null);

        for (int i = 0; i < maxIterations; i++) {
            chain = chain.thenComposeAsync(ignored -> {
                // 如果已经得到最终结果，则直接短路（后续迭代不再发请求）
                if (finalResult.value != null) {
                    return CompletableFuture.completedFuture(null);
                }

                // 允许外部 cancel 时尽快终止
                if (Thread.currentThread().isInterrupted()) {
                    return CompletableFuture.failedFuture(new CancellationException("Interrupted"));
                }

                return chatCompat(provider, messages, tools.getDefinitions(), model, maxTokens, temperature, reasoningEffort)
                        .thenCompose(resp -> {
                            if (resp == null) {
                                finalResult.value = "Task completed but no final response was generated.";
                                return CompletableFuture.completedFuture(null);
                            }

                            if (resp.hasToolCalls()) {
                                // 追加 assistant（包含 tool_calls）
                                Map<String, Object> assistant = new LinkedHashMap<>();
                                assistant.put("role", "assistant");
                                assistant.put("content", resp.getContent() == null ? "" : resp.getContent());

                                List<Map<String, Object>> toolCallDicts = resp.getToolCalls().stream()
                                        .map(tc -> {
                                            Map<String, Object> fn = new LinkedHashMap<>();
                                            fn.put("name", tc.getName());
                                            // arguments 必须是字符串 JSON（与 Python 侧 json.dumps 一致）
                                            fn.put("arguments", toJson(tc.getArguments()));

                                            Map<String, Object> call = new LinkedHashMap<>();
                                            call.put("id", tc.getId());
                                            call.put("type", "function");
                                            call.put("function", fn);
                                            return call;
                                        })
                                        .collect(Collectors.toList());

                                assistant.put("tool_calls", toolCallDicts);
                                messages.add(assistant);

                                // 逐个执行工具（顺序执行，保持行为稳定）
                                CompletableFuture<Void> toolSeq = CompletableFuture.completedFuture(null);
                                for (var tc : resp.getToolCalls()) {
                                    toolSeq = toolSeq.thenCompose(v -> {
                                        String argsStr = toJson(tc.getArguments());
                                        log.debug("Subagent [{}] executing: {} with arguments: {}", taskId, tc.getName(), argsStr);

                                        return tools.execute(tc.getName(), tc.getArguments())
                                                .thenApply(result -> {
                                                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                                                    toolMsg.put("role", "tool");
                                                    toolMsg.put("tool_call_id", tc.getId());
                                                    toolMsg.put("name", tc.getName());
                                                    toolMsg.put("content", result);
                                                    messages.add(toolMsg);
                                                    return (Void) null;
                                                }).toCompletableFuture();
                                    });
                                }
                                return toolSeq;
                            } else {
                                // 没有工具调用：认为模型给出了最终答案
                                finalResult.value = resp.getContent();
                                return CompletableFuture.completedFuture(null);
                            }
                        }).toCompletableFuture();
            }, executor);
        }

        // ========== 4) 汇报结果 ==========
        return chain.handle((v, ex) -> {
            String result;
            String status;

            if (ex == null) {
                result = (finalResult.value != null)
                        ? finalResult.value
                        : "Task completed but no final response was generated.";
                status = "ok";
                log.info("Subagent [{}] completed successfully", taskId);
            } else {
                result = "Error: " + rootMessage(ex);
                status = "error";
                log.error("Subagent [{}] failed: {}", taskId, rootMessage(ex));
            }

            // 把结果注入主代理（system 入站消息）
            return announceResult(taskId, label, task, result, origin, status)
                    .toCompletableFuture()
                    .join();
        }).toCompletableFuture();
    }

    /**
     * 将子代理结果注入主代理（通过 MessageBus）
     *
     * 注入内容要求主代理：
     * - 自然总结给用户（1-2 句）
     * - 不提 subagent、task id 等技术细节
     */
    private CompletionStage<Void> announceResult(
            String taskId,
            String label,
            String task,
            String result,
            Origin origin,
            String status
    ) {
        String statusText = "ok".equals(status) ? "completed successfully" : "failed";

        String announceContent =
                "[Subagent '" + label + "' " + statusText + "]\n\n" +
                        "Task: " + (task == null ? "" : task) + "\n\n" +
                        "Result:\n" + (result == null ? "" : result) + "\n\n" +
                        "Summarize this naturally for the user. Keep it brief (1-2 sentences). " +
                        "Do not mention technical details like \"subagent\" or task IDs.";

        InboundMessage msg = new InboundMessage(
                "system",
                "subagent",
                origin.channel + ":" + origin.chatId,
                announceContent,
                null,
                null
        );

        // 发布到主代理入站队列
        return bus.publishInbound(msg);
    }

    /**
     * 构建子代理系统提示词（聚焦任务、注入时间元信息、附带技能索引摘要）
     *
     * 结构：
     * - "# Subagent"
     * - Runtime metadata（不可信元信息块）
     * - 任务行为约束 + Workspace
     * - Skills 摘要（存在才追加）
     */
    private String buildSubagentPrompt() {
        // 复用主 ContextBuilder 的运行时元信息块（仅元数据、非指令）
        String timeCtx = ContextBuilder.buildRuntimeContext(null, null);

        List<String> parts = new ArrayList<>();
        parts.add(
                "# Subagent\n\n" +
                        timeCtx + "\n\n" +
                        "You are a subagent spawned by the main agent to complete a specific task.\n" +
                        "Stay focused on the assigned task. Your final response will be reported back to the main agent.\n\n" +
                        "## Workspace\n" +
                        workspace
        );

        // 技能摘要：提示子代理需要时用 read_file 读取 SKILL.md
        String skillsSummary = new SkillsLoader(workspace).buildSkillsSummary();
        if (skillsSummary != null && !skillsSummary.isBlank()) {
            parts.add("## Skills\n\nRead SKILL.md with read_file to use a skill.\n\n" + skillsSummary);
        }

        return String.join("\n\n", parts);
    }

    /**
     * 取消某个 session 下的所有子代理（返回取消数量）
     *
     * 行为：
     * - 仅取消仍在运行中的任务
     * - 等待这些任务结束（join），避免泄漏
     */
    public CompletionStage<Integer> cancelBySession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }

        Set<String> ids = sessionTasks.getOrDefault(sessionKey, Set.of());
        if (ids.isEmpty()) return CompletableFuture.completedFuture(0);

        List<CompletableFuture<Void>> toCancel = new ArrayList<>();
        for (String tid : ids) {
            CompletableFuture<Void> f = runningTasks.get(tid);
            if (f != null && !f.isDone()) {
                f.cancel(true);
                toCancel.add(f);
            }
        }

        if (toCancel.isEmpty()) return CompletableFuture.completedFuture(0);

        return CompletableFuture
                .allOf(toCancel.toArray(new CompletableFuture[0]))
                .handle((v, ex) -> toCancel.size());
    }

    /**
     * 当前运行中的子代理数量
     */
    public int getRunningCount() {
        return runningTasks.size();
    }

    // ==========================
    // Provider chat 调用兼容
    // ==========================

    /**
     * 对不同 LLMProvider.chat 签名做兼容：
     * - 若存在 6 参版本（含 reasoning_effort），优先调用
     * - 否则回退到 5 参版本
     */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<LLMResponse> chatCompat(
            LLMProvider provider,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        try {
            // 尝试：chat(messages, tools, model, maxTokens, temperature, reasoningEffort)
            Method m = provider.getClass().getMethod(
                    "chat",
                    List.class, List.class, String.class, int.class, double.class, String.class
            );
            Object r = m.invoke(provider, messages, tools, model, maxTokens, temperature, reasoningEffort);
            if (r instanceof CompletableFuture<?> f) {
                return (CompletableFuture<LLMResponse>) f;
            }
        } catch (NoSuchMethodException ignored) {
            // 没有 6 参版本则走回退
        } catch (Exception e) {
            // 反射调用失败也回退到 5 参版本
            log.debug("chat 6参调用失败，回退到 5参：{}", e.toString());
        }

        // 回退：chat(messages, tools, model, maxTokens, temperature)
        return provider.chat(messages, tools, model, maxTokens, temperature);
    }

    // ==========================
    // 工具方法
    // ==========================

    /**
     * 生成 8 位短 UUID（用于展示）
     */
    private static String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 生成展示用 label：取前 max 字符，超出则加 ...
     */
    private static String shortLabel(String task, int max) {
        if (task == null) return "";
        String t = task;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }

    /**
     * 构建 OpenAI 兼容消息结构：{"role": "...", "content": "..."}
     */
    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * 将对象转为 JSON 字符串（用于 tool_calls.function.arguments）
     */
    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    /**
     * 获取异常链最底层 message（便于输出）
     */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }

    /**
     * 任务来源信息（用于回传到 bus 的 chat_id）
     */
    private static final class Origin {
        final String channel;
        final String chatId;

        Origin(String channel, String chatId) {
            this.channel = channel;
            this.chatId = chatId;
        }
    }

    /**
     * 简单可变 holder（用于 lambda 闭包写入最终结果）
     */
    private static final class Holder<T> {
        T value;
        Holder(T v) { this.value = v; }
    }
}