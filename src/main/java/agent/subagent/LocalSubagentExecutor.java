package agent.subagent;

import agent.ProgressCallback;
import agent.tool.ExecTool;
import agent.tool.FileSystemTools;
import agent.tool.ToolRegistry;
import agent.tool.WebFetchTool;
import agent.tool.WebSearchTool;
import bus.MessageBus;
import config.ConfigSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import providers.ToolCallRequest;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static utils.Helpers.stripThink;
import static utils.Helpers.toolHint;

/**
 * 本地子Agent执行器
 *
 * 在当前进程内执行子Agent任务，使用独立的工具集和消息历史。
 *
 * 关键特性：
 * - 支持多层级嵌套（子Agent可以spawn子子Agent）
 * - 根据深度动态注册spawn/subagents工具
 * - 支持终止信号和超时控制
 *
 * 对应 OpenClaw: src/agents/SubagentManager.ts (部分功能)
 */
public class LocalSubagentExecutor implements SubagentExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalSubagentExecutor.class);

    private final LLMProvider provider;
    private final Path workspace;
    private final ConfigSchema.ExecToolConfig execConfig;
    private final String braveApiKey;
    private final boolean restrictToWorkspace;
    private final ExecutorService executor;

    // ========== 新增：多Agent控制依赖 ==========
    private final SubagentRegistry registry;
    private final MessageBus messageBus;
    private SubagentManager subagentManager;

    /** 最大迭代次数 */
    private static final int MAX_ITERATIONS = 30;

    /** 运行中的任务 */
    private final ConcurrentHashMap<String, CompletableFuture<SubagentExecutionResult>> runningTasks = new ConcurrentHashMap<>();

    /** 终止信号 */
    private final ConcurrentHashMap<String, AtomicBoolean> terminateSignals = new ConcurrentHashMap<>();

    /**
     * 完整构造函数（支持多层级嵌套）
     */
    public LocalSubagentExecutor(
            LLMProvider provider,
            Path workspace,
            ConfigSchema.ExecToolConfig execConfig,
            String braveApiKey,
            boolean restrictToWorkspace,
            SubagentRegistry registry,
            MessageBus messageBus
    ) {
        this.provider = Objects.requireNonNull(provider);
        this.workspace = Objects.requireNonNull(workspace);
        this.execConfig = execConfig != null ? execConfig : new ConfigSchema.ExecToolConfig();
        this.braveApiKey = braveApiKey;
        this.restrictToWorkspace = restrictToWorkspace;
        this.registry = registry != null ? registry : SubagentRegistry.getInstance();
        this.messageBus = messageBus;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("subagent-executor-" + t.getId());
            return t;
        });
    }

    /**
     * 兼容旧构造函数
     */
    public LocalSubagentExecutor(
            LLMProvider provider,
            Path workspace,
            ConfigSchema.ExecToolConfig execConfig,
            String braveApiKey,
            boolean restrictToWorkspace
    ) {
        this(provider, workspace, execConfig, braveApiKey, restrictToWorkspace, null, null);
    }

    /**
     * 设置 SubagentManager 引用（由 SubagentManager 构造时调用）
     */
    public void setSubagentManager(SubagentManager manager) {
        this.subagentManager = manager;
    }

    @Override
    public CompletionStage<SubagentExecutionResult> execute(SubagentRunRecord record, String systemPrompt) {
        String runId = record.getRunId();

        ProgressCallback onProgress = (content, toolHit) -> {
            String msg = "  ↳ " + "子代理 [" + runId+ " ]: " + (content == null ? "" : content);
            // 发布通知
            messageBus.publishOutbound(new bus.OutboundMessage(record.getOriginChannel(), record.getOriginChatId(), msg, List.of(), Map.of()));
        } ;

        // 创建终止信号
        AtomicBoolean terminateSignal = new AtomicBoolean(false);
        terminateSignals.put(runId, terminateSignal);

        // 创建执行任务
        CompletableFuture<SubagentExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                // 标记开始
                record.setStartedAt(java.time.LocalDateTime.now());

                // ========== 关键修改：根据深度构建工具集 ==========
                ToolRegistry tools = buildToolRegistry(record);

                // 构建初始消息
                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(msg("system", systemPrompt));
                messages.add(msg("user", record.getTask()));

                // 运行Agent循环
                String finalResult = null;
                SubagentOutcome outcome = null;

                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    // 检查终止信号
                    if (terminateSignal.get()) {
                        outcome = SubagentOutcome.error("terminated");
                        break;
                    }

                    // 检查超时
                    if (record.getRunTimeoutSeconds() != null) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        if (elapsed > record.getRunTimeoutSeconds()) {
                            outcome = SubagentOutcome.timeout();
                            break;
                        }
                    }

                    // 调用LLM
                    log.info("Subagent [{}] calling LLM, iteration {}", runId, i + 1);
                    LLMResponse response = chatWithRetry(
                            provider, messages, tools.getDefinitions(),
                            record.getModel() != null ? record.getModel() : provider.getDefaultModel(),
                            8192, 0.5, null
                    );

                    if (response.hasToolCalls()) {
                        String clean = stripThink(response.getContent());
                        if (clean != null) {
                            onProgress.onProgress(clean, false);
                        }
                        // 工具调用发布通知
                        onProgress.onProgress(toolHint(response.getToolCalls()), true);

                        // 追加assistant消息
                        messages.add(buildAssistantMessage(response));

                        // 执行工具调用
                        for (ToolCallRequest tc : response.getToolCalls()) {
                            if (terminateSignal.get()) break;

                            String result = tools.execute(tc.getName(), tc.getArguments())
                                    .toCompletableFuture()
                                    .join();

                            messages.add(buildToolResultMessage(tc.getId(), tc.getName(), result));
                        }

                        continue;
                    }

                    // 没有工具调用，获取最终结果
                    finalResult = response.getContent();

                    // 输出至用户channel
                    String clean = stripThink(response.getContent());
                    if (clean != null) {
                        onProgress.onProgress(clean, false);
                    }

                    outcome = SubagentOutcome.ok();
                    break;
                }

                // 如果循环结束但没有结果
                if (outcome == null) {
                    outcome = SubagentOutcome.error("max iterations reached");
                }

                return new SubagentExecutionResult(outcome, finalResult);

            } catch (CancellationException e) {
                log.error("Subagent [{}] cancelled", runId);
                return new SubagentExecutionResult(SubagentOutcome.error("cancelled"), null);
            } catch (Exception e) {
                log.error("Subagent execution error: {}", runId, e);
                return new SubagentExecutionResult(SubagentOutcome.error(e.getMessage()), null);
            } finally {
                terminateSignals.remove(runId);
            }
        }, executor)
                .whenComplete((v, ex) -> runningTasks.remove(runId));

        runningTasks.put(runId, future);

        return future;
    }

    @Override
    public boolean isRunning(String runId) {
        CompletableFuture<SubagentExecutionResult> future = runningTasks.get(runId);
        return future != null && !future.isDone();
    }

    @Override
    public CompletionStage<Boolean> terminate(String runId) {
        AtomicBoolean signal = terminateSignals.get(runId);
        if (signal != null) {
            signal.set(true);
        }

        CompletableFuture<SubagentExecutionResult> future = runningTasks.get(runId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        return CompletableFuture.completedFuture(true);
    }

    // ==========================
    // 关键修改：根据深度构建工具集
    // ==========================

    /**
     * 构建工具集
     *
     * 关键逻辑：
     * - 所有子Agent都有基础工具（文件、shell、web）
     * - 如果当前深度 < 最大深度，则注册spawn/subagents工具
     * - 这样子Agent就可以继续spawn子子Agent
     *
     * @param record 运行记录（包含深度信息）
     * @return 工具注册表
     */
    private ToolRegistry buildToolRegistry(SubagentRunRecord record) {
        ToolRegistry tools = new ToolRegistry();
        Path allowedDir = restrictToWorkspace ? workspace : null;

        // ========== 基础工具（所有子Agent都有） ==========
        
        // 文件工具
        tools.register(new FileSystemTools.ReadFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.WriteFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.EditFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ListDirTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadPptTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadPptStructuredTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadWordTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadWordStructuredTool(workspace, allowedDir));

        // Shell工具
        tools.register(new ExecTool(
                execConfig.getTimeout(),
                workspace.toString(),
                null, null,
                restrictToWorkspace,
                execConfig.getPathAppend()
        ));

        // Web工具
        tools.register(new WebSearchTool(braveApiKey, null));
        tools.register(new WebFetchTool(null));

        // ========== 关键：根据深度判断是否注册spawn/subagents工具 ==========
        int currentDepth = record.getDepth();
        int maxDepth = registry.getMaxSpawnDepth();
        boolean canSpawn = currentDepth < maxDepth;

        if (canSpawn && messageBus != null && subagentManager != null) {
            log.info("Subagent [{}] at depth {} can spawn children (max: {})", 
                    record.getRunId(), currentDepth, maxDepth);

            // 通过 SubagentManager 统一入口注册 spawn/control 工具
            SessionsSpawnTool spawnTool = new SessionsSpawnTool(subagentManager);
            spawnTool.setContext(record.getChildSessionKey(), 
                    extractChannel(record.getChildSessionKey()), 
                    extractChatId(record.getChildSessionKey()));
            tools.register(spawnTool);

            SubagentsControlTool subagentsControlTool = new SubagentsControlTool(subagentManager);
            subagentsControlTool.setAgentSessionKey(record.getChildSessionKey());
            tools.register(subagentsControlTool);

            log.info("Registered spawn/subagents tools for subagent [{}] at depth {}", 
                    record.getRunId(), currentDepth);
        } else if (!canSpawn) {
            log.info("Subagent [{}] at depth {} is a leaf worker (max: {}), no spawn tools", 
                    record.getRunId(), currentDepth, maxDepth);
        }

        return tools;
    }

    /**
     * 从会话Key中提取渠道
     */
    private String extractChannel(String sessionKey) {
        if (sessionKey == null || !sessionKey.contains(":")) {
            return "cli";
        }
        return sessionKey.split(":")[0];
    }

    /**
     * 从会话Key中提取chatId
     */
    private String extractChatId(String sessionKey) {
        if (sessionKey == null || !sessionKey.contains(":")) {
            return "direct";
        }
        String[] parts = sessionKey.split(":");
        return parts.length > 1 ? parts[1] : "direct";
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content != null ? content : "");
        return m;
    }

    private Map<String, Object> buildAssistantMessage(LLMResponse response) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", response.getContent() != null ? response.getContent() : "");

        if (response.hasToolCalls()) {
            List<Map<String, Object>> toolCalls = response.getToolCalls().stream()
                    .map(tc -> {
                        Map<String, Object> fn = new LinkedHashMap<>();
                        fn.put("name", tc.getName());
                        fn.put("arguments", toJson(tc.getArguments()));

                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("id", tc.getId());
                        call.put("type", "function");
                        call.put("function", fn);
                        return call;
                    })
                    .collect(Collectors.toList());
            msg.put("tool_calls", toolCalls);
        }

        return msg;
    }

    private Map<String, Object> buildToolResultMessage(String toolCallId, String toolName, String result) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("name", toolName);
        msg.put("content", result != null ? result : "");
        return msg;
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    @SuppressWarnings("unchecked")
    private static LLMResponse chatWithRetry(
            LLMProvider provider,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
            // 尝试6参数版本
            return provider.chatWithRetry(messages, tools, model, maxTokens, temperature, reasoningEffort).toCompletableFuture().join();
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}