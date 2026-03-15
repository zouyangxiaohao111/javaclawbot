package agent;

import agent.subagent.*;
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
 * 子代理管理器（重构版）
 *
 * 职责：
 * 1) spawn：启动后台子任务（集成SubagentRegistry）
 * 2) _run_subagent：构建工具集 + 运行有限轮次的 agent loop
 * 3) _announce_result：把结果通过 MessageBus 注入为 system 入站消息，触发主代理总结
 *
 * 新增功能：
 * - 多层级Agent嵌套支持
 * - 深度控制
 * - 完成公告机制
 * - 子Agent控制（kill/steer）
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
    private final String reasoningEffort;
    private final String braveApiKey;
    private final ConfigSchema.ExecToolConfig execConfig;
    private final boolean restrictToWorkspace;

    // ========== 新增：多Agent控制组件 ==========
    private final SubagentRegistry registry;
    private final SubagentSystemPromptBuilder promptBuilder;
    private final SubagentAnnounceService announceService;
    private final SubagentController controller;
    private final LocalSubagentExecutor localExecutor;

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

        // 默认使用通用线程池
        this.executor = (executor != null) ? executor : ForkJoinPool.commonPool();

        // ========== 初始化多Agent控制组件 ==========
        this.registry = SubagentRegistry.getInstance();
        this.promptBuilder = new SubagentSystemPromptBuilder(workspace);
        this.announceService = new SubagentAnnounceService(bus, promptBuilder);
        this.controller = new SubagentController(registry, bus);
        // 关键：传入registry和bus，支持子Agent继续spawn子子Agent
        this.localExecutor = new LocalSubagentExecutor(
                provider, workspace, execConfig, braveApiKey, restrictToWorkspace,
                registry, bus
        );
    }

    /**
     * 启动一个子代理后台任务（增强版）
     *
     * @param task          要执行的任务（自然语言）
     * @param label         可选：展示用标签
     * @param originChannel 任务来源渠道
     * @param originChatId  任务来源 chat_id
     * @param sessionKey    会话键
     */
    public CompletionStage<String> spawn(
            String task,
            String label,
            String originChannel,
            String originChatId,
            String sessionKey
    ) {
        // 解析请求者会话Key
        String requesterKey = (sessionKey != null && !sessionKey.isBlank())
                ? sessionKey
                : ((originChannel != null ? originChannel : "cli") + ":" + (originChatId != null ? originChatId : "direct"));

        // 计算子Agent深度
        int childDepth = registry.computeChildDepth(requesterKey);
        int maxDepth = registry.getMaxSpawnDepth();

        // 检查深度限制
        if (childDepth > maxDepth) {
            return CompletableFuture.completedFuture(
                    String.format("{\"status\":\"forbidden\",\"error\":\"Maximum spawn depth (%d) exceeded\"}", maxDepth)
            );
        }

        // 生成运行ID和子会话Key
        final String runId = uuid8();
        final String childSessionKey = requesterKey + ":" + runId;

        // 展示标签
        final String displayLabel = (label != null && !label.isBlank())
                ? label
                : shortLabel(task, 30);

        // 创建运行记录
        SubagentRunRecord record = new SubagentRunRecord(
                runId,
                childSessionKey,
                requesterKey,
                displayLabel,
                task,
                SubagentRunRecord.CleanupPolicy.KEEP,
                SubagentRunRecord.SpawnMode.RUN,
                childDepth
        );
        record.setModel(this.model);
        record.setWorkspaceDir(workspace.toString());

        // 注册到Registry
        registry.register(record);

        // 构建子Agent系统提示词
        SubagentSystemPromptBuilder.Params promptParams = new SubagentSystemPromptBuilder.Params()
                .task(task)
                .label(displayLabel)
                .requesterSessionKey(requesterKey)
                .requesterChannel(originChannel)
                .childSessionKey(childSessionKey)
                .childDepth(childDepth)
                .maxSpawnDepth(maxDepth);

        String systemPrompt = promptBuilder.build(promptParams);

        // 后台执行
        CompletableFuture<Void> bg = CompletableFuture.runAsync(() -> {
            try {
                // 标记开始
                registry.markStarted(runId);

                // 执行子Agent
                SubagentExecutionResult result = localExecutor.execute(record, systemPrompt)
                        .toCompletableFuture()
                        .join();

                // 更新记录
                registry.markEnded(runId, result.getOutcome());
                registry.updateFrozenResult(runId, result.getResultText());

                // 发送完成公告
                announceService.announceWithRetry(record).toCompletableFuture().join();

                log.info("Subagent [{}] completed: {}", runId, result.getOutcome().getStatusText());

            } catch (CancellationException ce) {
                log.info("Subagent [{}] cancelled", runId);
                registry.markEnded(runId, SubagentOutcome.error("cancelled"));
            } catch (Throwable t) {
                log.error("Subagent [{}] crashed", runId, t);
                registry.markEnded(runId, SubagentOutcome.error(t.getMessage()));
            }
        }, executor);

        runningTasks.put(runId, bg);

        if (sessionKey != null && !sessionKey.isBlank()) {
            sessionTasks.compute(sessionKey, (k, set) -> {
                if (set == null) set = ConcurrentHashMap.newKeySet();
                set.add(runId);
                return set;
            });
        }

        bg.whenComplete((v, ex) -> {
            runningTasks.remove(runId);
            if (sessionKey != null && !sessionKey.isBlank()) {
                sessionTasks.computeIfPresent(sessionKey, (k, set) -> {
                    set.remove(runId);
                    return set.isEmpty() ? null : set;
                });
            }
        });

        log.info("Spawned subagent [{}]: {} (depth: {})", runId, displayLabel, childDepth);

        // 返回JSON结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "accepted");
        result.put("runId", runId);
        result.put("childSessionKey", childSessionKey);
        result.put("depth", childDepth);
        result.put("note", "Auto-announce is push-based. Wait for completion events to arrive as user messages.");
        result.put("text", String.format("Subagent [%s] started (id: %s). I'll notify you when it completes.", displayLabel, runId));

        return CompletableFuture.completedFuture(toJson(result));
    }

    /**
     * 取消某个 session 下的所有子代理
     */
    public CompletionStage<Integer> cancelBySession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }

        Set<String> ids = sessionTasks.getOrDefault(sessionKey, Set.of());
        if (ids.isEmpty()) return CompletableFuture.completedFuture(0);

        int[] count = {0};
        List<CompletableFuture<Void>> toCancel = new ArrayList<>();

        for (String tid : ids) {
            CompletableFuture<Void> f = runningTasks.get(tid);
            if (f != null && !f.isDone()) {
                f.cancel(true);
                toCancel.add(f);
                count[0]++;
            }
            // 终止本地执行器
            localExecutor.terminate(tid);
        }

        if (toCancel.isEmpty()) return CompletableFuture.completedFuture(0);

        return CompletableFuture
                .allOf(toCancel.toArray(new CompletableFuture[0]))
                .handle((v, ex) -> count[0]);
    }

    /**
     * 当前运行中的子代理数量
     */
    public int getRunningCount() {
        return runningTasks.size();
    }

    // ==========================
    // 新增：多Agent控制API
    // ==========================

    /**
     * 获取子Agent注册表
     */
    public SubagentRegistry getRegistry() {
        return registry;
    }

    /**
     * 获取子Agent控制器
     */
    public SubagentController getController() {
        return controller;
    }

    /**
     * 获取子Agent公告服务
     */
    public SubagentAnnounceService getAnnounceService() {
        return announceService;
    }

    /**
     * 获取子Agent提示词构建器
     */
    public SubagentSystemPromptBuilder getPromptBuilder() {
        return promptBuilder;
    }

    /**
     * 列出指定会话的子Agent
     */
    public List<SubagentRunRecord> listSubagents(String sessionKey) {
        return registry.listByRequester(sessionKey);
    }

    /**
     * 终止指定子Agent
     */
    public CompletionStage<Boolean> killSubagent(String runId) {
        CompletableFuture<Void> f = runningTasks.get(runId);
        if (f != null && !f.isDone()) {
            f.cancel(true);
        }
        return localExecutor.terminate(runId);
    }

    /**
     * 向子Agent发送指导消息
     */
    public CompletionStage<Boolean> steerSubagent(String runId, String message) {
        return controller.steer(runId, message);
    }

    // ==========================
    // 工具方法
    // ==========================

    private static String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String shortLabel(String task, int max) {
        if (task == null) return "";
        String t = task;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    /**
     * 关闭管理器
     */
    public void shutdown() {
        localExecutor.shutdown();
    }
}