package agent.subagent.fork;

import agent.subagent.context.SubagentContext;
import agent.subagent.framework.ProgressTracker;
import agent.subagent.task.local.LocalAgentTaskState;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskStatus;
import agent.tool.ToolView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Fork 代理执行器
 *
 * 负责 Fork 子代理的完整执行生命周期：
 * 1. 构建隔离的上下文
 * 2. 构建 Fork 消息
 * 3. 执行 LLM 对话循环
 * 4. 追踪进度
 * 5. 处理完成和通知
 * 6. 结果持久化
 *
 * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - LocalAgentTask
 */
public class ForkAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ForkAgentExecutor.class);

    /** 默认最大迭代次数 */
    private static final int DEFAULT_MAX_ITERATIONS = 30;

    /** LLM 提供者 */
    private final LLMProvider provider;

    /** 工作目录 */
    private final Path workspace;

    /** Sessions 目录（用于持久化） */
    private final Path sessionsDir;

    /** 最大迭代次数 */
    private final int maxIterations;

    /** Fork 执行回调 */
    private final ForkCompletionCallback completionCallback;

    /** AppState */
    private final AppState appState;

    /** AppState Setter */
    private final AppState.Setter setAppState;

    /** ToolView for tool lookup */
    private final ToolView toolView;

    /** 运行中的任务 */
    private final ConcurrentHashMap<String, CompletableFuture<ForkResult>> runningTasks = new ConcurrentHashMap<>();

    /** 终止信号 */
    private final ConcurrentHashMap<String, AtomicBoolean> terminateSignals = new ConcurrentHashMap<>();

    private final ExecutorService executor;

    public ForkAgentExecutor(
            LLMProvider provider,
            Path workspace,
            Path sessionsDir,
            ForkCompletionCallback completionCallback,
            AppState appState,
            AppState.Setter setAppState,
            ToolView toolView
    ) {
        this(provider, workspace, sessionsDir, DEFAULT_MAX_ITERATIONS, completionCallback, appState, setAppState, toolView);
    }

    public ForkAgentExecutor(
            LLMProvider provider,
            Path workspace,
            Path sessionsDir,
            int maxIterations,
            ForkCompletionCallback completionCallback,
            AppState appState,
            AppState.Setter setAppState,
            ToolView toolView
    ) {
        this.provider = provider;
        this.workspace = workspace;
        this.sessionsDir = sessionsDir;
        this.maxIterations = maxIterations;
        this.completionCallback = completionCallback;
        this.appState = appState;
        this.setAppState = setAppState;
        this.toolView = toolView;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("fork-executor-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行 Fork 子代理
     *
     * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - registerAsyncAgent()
     *
     * @param sessionId 会话 ID（用于持久化）
     * @param forkContext Fork 上下文
     * @param subagentContext 子代理上下文
     * @return CompletableFuture<ForkResult> - 允许调用者追踪结果
     */
    public CompletableFuture<ForkResult> execute(String sessionId, ForkContext forkContext, SubagentContext subagentContext) {
        return execute(sessionId, forkContext, subagentContext, null);
    }

    /**
     * 执行 Fork 子代理（带工具限制）
     *
     * @param sessionId 会话 ID（用于持久化）
     * @param forkContext Fork 上下文
     * @param subagentContext 子代理上下文
     * @param canUseTool 工具使用限制函数，参数为工具名，返回 true 表示允许使用；null 表示不限制
     * @return CompletableFuture<ForkResult> - 允许调用者追踪结果
     *
     * TODO [execute]: 缺少 onMessage 流式回调
     * Open-ClaudeCode: forkedAgent.ts:498 (onMessage callback parameter)
     * onMessage?: (message: Message) => void
     * 用于流式接收 fork agent 产生的消息
     * javaclawbot: 当前不支持流式回调，execute 为同步返回
     */
    public CompletableFuture<ForkResult> execute(
            String sessionId,
            ForkContext forkContext,
            SubagentContext subagentContext,
            java.util.function.Function<String, Boolean> canUseTool) {
        String runId = UUID.randomUUID().toString().substring(0, 8);

        // 创建终止信号
        AtomicBoolean terminateSignal = new AtomicBoolean(false);
        terminateSignals.put(runId, terminateSignal);

        // 创建进度追踪器
        ProgressTracker progressTracker = subagentContext.getProgressTracker();

        // 创建任务状态（对应 createTaskStateBase + LocalAgentTaskState）
        LocalAgentTaskState taskState = LocalAgentTaskState.create(
                runId,
                forkContext.getDirective(),
                null,  // toolUseId
                forkContext.getDirective(),  // prompt
                null   // agentType
        );
        taskState.setBackgrounded(true);
        taskState.setProgressTracker(progressTracker);
        taskState.setAgentId(runId);

        // 注册任务到 AppState（对应 registerTask(taskState, setAppState)）
        AppState.registerTask(taskState, setAppState);

        // 创建 CompletableFuture 用于返回
        CompletableFuture<ForkResult> resultFuture = new CompletableFuture<>();

        // 异步执行
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                // 标记开始（对应 updateTaskState + markStarted）
                AppState.updateTaskState(runId, setAppState, (LocalAgentTaskState t) -> {
                    t.setStatus(TaskStatus.RUNNING);
                    return t;
                });

                // 构建 Fork 消息
                List<Map<String, Object>> messages = ForkAgentDefinition.buildForkedMessages(
                        forkContext.getDirective(),
                        forkContext.getParentMessages()
                );

                // 构建初始消息
                List<Map<String, Object>> initialMessages = new ArrayList<>();

                // 添加系统提示词
                if (forkContext.getParentSystemPrompt() != null) {
                    initialMessages.add(Map.of("role", "system", "content", forkContext.getParentSystemPrompt()));
                }

                // 添加用户上下文
                if (forkContext.getUserContext() != null && !forkContext.getUserContext().isEmpty()) {
                    StringBuilder ucText = new StringBuilder();
                    forkContext.getUserContext().forEach((k, v) -> ucText.append(k).append(": ").append(v).append("\n"));
                    initialMessages.add(Map.of("role", "user", "content", ucText.toString()));
                }

                // 添加 Fork 消息
                initialMessages.addAll(messages);

                // 执行对话循环
                String finalResult = executeLoop(
                        runId,
                        initialMessages,
                        terminateSignal,
                        progressTracker,
                        forkContext,
                        canUseTool
                );

                // 计算工具使用次数
                long toolUseCount = progressTracker.getToolUseCount();

                // 标记完成（对应 markCompleted）
                AppState.updateTaskState(runId, setAppState, (LocalAgentTaskState t) -> {
                    t.setStatus(TaskStatus.COMPLETED);
                    t.setEndTime(Instant.now());
                    t.setResult(finalResult);
                    return t;
                });

                ForkResult result = ForkResult.success(runId, finalResult, toolUseCount);

                // 完成 future
                resultFuture.complete(result);

                // 通知回调
                if (completionCallback != null) {
                    completionCallback.onComplete(runId, forkContext.getDirective(), result);
                }

            } catch (CancellationException e) {
                log.error("Fork [{}] cancelled", runId);
                // 标记终止（对应 markKilled）
                AppState.updateTaskState(runId, setAppState, (LocalAgentTaskState t) -> {
                    t.setStatus(TaskStatus.KILLED);
                    t.setEndTime(Instant.now());
                    t.markKilled();
                    return t;
                });

                resultFuture.complete(ForkResult.killed(runId));

                if (completionCallback != null) {
                    completionCallback.onComplete(runId, forkContext.getDirective(), ForkResult.killed(runId));
                }

            } catch (Exception e) {
                log.error("Fork [{}] execution error", runId, e);
                // 标记失败（对应 markFailed）
                AppState.updateTaskState(runId, setAppState, (LocalAgentTaskState t) -> {
                    t.setStatus(TaskStatus.FAILED);
                    t.setEndTime(Instant.now());
                    t.setError(e.getMessage());
                    return t;
                });

                resultFuture.complete(ForkResult.error(runId, e.getMessage()));

                if (completionCallback != null) {
                    completionCallback.onComplete(runId, forkContext.getDirective(), ForkResult.error(runId, e.getMessage()));
                }

            } finally {
                terminateSignals.remove(runId);
            }
        }, executor)
                .whenComplete((v, ex) -> runningTasks.remove(runId));

        runningTasks.put(runId, resultFuture);

        return resultFuture;
    }

    /**
     * 执行对话循环
     *
     * @param canUseTool 工具使用限制函数，参数为工具名，返回 true 表示允许使用；null 表示不限制
     */
    private String executeLoop(
            String runId,
            List<Map<String, Object>> messages,
            AtomicBoolean terminateSignal,
            ProgressTracker progressTracker,
            ForkContext forkContext,
            java.util.function.Function<String, Boolean> canUseTool
    ) {
        String model = provider.getDefaultModel();

        for (int i = 0; i < maxIterations; i++) {
            // 检查终止信号
            if (terminateSignal.get()) {
                return "Terminated by request";
            }

            log.debug("Fork [{}] iteration {}, calling LLM", runId, i + 1);

            // 获取可用工具列表
            List<Map<String, Object>> tools = getAvailableTools(canUseTool);

            // 调用 LLM
            LLMResponse response = provider.chatWithRetry(
                    messages,
                    tools,
                    model,
                    8192,
                    0.5,
                    null
            ).toCompletableFuture().join();

            if (response.hasToolCalls()) {
                // 记录工具使用
                for (var tc : response.getToolCalls()) {
                    progressTracker.addToolUse(tc.getName(), tc.getArguments());
                }

                // 更新消息
                messages.add(buildAssistantMessage(response));

                // 执行工具调用
                for (var tc : response.getToolCalls()) {
                    if (terminateSignal.get()) break;

                    // 执行工具 - 复用 RunAgent 的工具执行逻辑
                    String result = executeTool(tc.getName(), tc.getArguments(), canUseTool);

                    messages.add(buildToolResultMessage(tc.getId(), tc.getName(), result));
                }

                continue;
            }

            // 没有工具调用，返回结果
            return response.getContent() != null ? response.getContent() : "";
        }

        // TODO [executeLoop]: 缺少 telemetry 记录
        // Open-ClaudeCode: forkedAgent.ts:613-620 (logForkAgentQueryEvent)
        // logForkAgentQueryEvent({ forkLabel, querySource, durationMs, messageCount, totalUsage, queryTracking });
        // javaclawbot: 需要添加 tengu_fork_agent_query 事件记录

        // TODO [executeLoop]: 缺少 transcript 记录
        // Open-ClaudeCode: forkedAgent.ts:530-541, 581-596 (recordSidechainTranscript)
        // 用于在 sidechain transcript 中记录 fork agent 的消息
        // javaclawbot: 不需要 - javaclawbot 没有 transcript 系统

        return "Max iterations reached";
    }

    /**
     * 终止 Fork 任务
     */
    public boolean terminate(String runId) {
        AtomicBoolean signal = terminateSignals.get(runId);
        if (signal != null) {
            signal.set(true);
        }

        CompletableFuture<ForkResult> future = runningTasks.get(runId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            return true;
        }
        return false;
    }

    /**
     * 检查任务是否运行中
     */
    public boolean isRunning(String runId) {
        CompletableFuture<ForkResult> future = runningTasks.get(runId);
        return future != null && !future.isDone();
    }

    /**
     * 获取任务结果（如果已完成）
     */
    public ForkResult getResult(String runId) {
        CompletableFuture<ForkResult> future = runningTasks.get(runId);
        if (future != null && future.isDone()) {
            try {
                return future.get();
            } catch (Exception e) {
                return ForkResult.error(runId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        // 终止所有运行中的任务
        runningTasks.forEach((runId, future) -> {
            if (!future.isDone()) {
                terminate(runId);
            }
        });

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> buildAssistantMessage(LLMResponse response) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", response.getContent() != null ? response.getContent() : "");

        if (response.hasToolCalls()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (var tc : response.getToolCalls()) {
                toolCalls.add(Map.of(
                        "id", tc.getId(),
                        "type", "function",
                        "function", Map.of(
                                "name", tc.getName(),
                                "arguments", toJson(tc.getArguments())
                        )
                ));
            }
            msg.put("tool_calls", toolCalls);
        }

        return msg;
    }

    private Map<String, Object> buildToolResultMessage(String toolCallId, String toolName, String result) {
        return Map.of(
                "role", "tool",
                "tool_call_id", toolCallId,
                "name", toolName,
                "content", result != null ? result : ""
        );
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Fork 执行结果
     *
     * TODO [ForkResult 扩展]: 添加 usage tracking 字段
     * Open-ClaudeCode: forkedAgent.ts:115-120 (ForkedAgentResult)
     * - inputTokens: 输入 token 数
     * - outputTokens: 输出 token 数
     * - cacheReadTokens: 缓存读取 token 数 (cache_read_input_tokens)
     * - cacheCreationTokens: 缓存创建 token 数 (cache_creation_input_tokens)
     * javaclawbot: LLMResponse 已包含 usage 信息，需传递到 ForkResult
     */
    public static class ForkResult {
        public final String runId;
        public final boolean success;
        public final boolean killed;
        public final String result;
        public final String error;
        public final long toolUseCount;

        // TODO [ForkResult]: usage tracking - Open-ClaudeCode: forkedAgent.ts:119
        // public final long inputTokens;
        // public final long outputTokens;
        // public final long cacheReadTokens;
        // public final long cacheCreationTokens;

        private ForkResult(String runId, boolean success, boolean killed, String result, String error, long toolUseCount) {
            this.runId = runId;
            this.success = success;
            this.killed = killed;
            this.result = result;
            this.error = error;
            this.toolUseCount = toolUseCount;
        }

        public static ForkResult success(String runId, String result, long toolUseCount) {
            return new ForkResult(runId, true, false, result, null, toolUseCount);
        }

        public static ForkResult error(String runId, String error) {
            return new ForkResult(runId, false, false, null, error, 0);
        }

        public static ForkResult killed(String runId) {
            return new ForkResult(runId, false, true, null, "Terminated", 0);
        }

        /**
         * 转换为 JSON 格式
         */
        public String toJson() {
            if (success) {
                return String.format(
                        "{\"status\":\"completed\",\"runId\":\"%s\",\"result\":\"%s\",\"toolUseCount\":%d}",
                        escapeJson(runId), escapeJson(result), toolUseCount);
            } else if (killed) {
                return String.format(
                        "{\"status\":\"killed\",\"runId\":\"%s\"}",
                        escapeJson(runId));
            } else {
                return String.format(
                        "{\"status\":\"failed\",\"runId\":\"%s\",\"error\":\"%s\"}",
                        escapeJson(runId), escapeJson(error));
            }
        }

        private String escapeJson(String text) {
            if (text == null) return "";
            return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }
    }

    /**
     * Fork 完成回调接口
     */
    @FunctionalInterface
    public interface ForkCompletionCallback {
        void onComplete(String runId, String directive, ForkResult result);
    }

    /**
     * 获取可用工具列表
     * 对应 Open-ClaudeCode: assembleToolPool() 获取工具定义
     *
     * @param canUseTool 工具使用限制函数，参数为工具名，返回 true 表示允许使用；null 表示不限制
     * @return 工具定义列表（OpenAI schema 格式）
     */
    private List<Map<String, Object>> getAvailableTools(java.util.function.Function<String, Boolean> canUseTool) {
        try {
            if (toolView == null) {
                log.warn("ToolView not initialized, no tools available");
                return null;
            }
            List<Map<String, Object>> allTools = toolView.getDefinitions();

            // 如果没有工具限制，直接返回所有工具
            if (canUseTool == null) {
                log.debug("Found {} available tools for fork agent (no restrictions)", allTools.size());
                return allTools;
            }

            // 根据 canUseTool 过滤工具
            List<Map<String, Object>> filteredTools = new java.util.ArrayList<>();
            for (Map<String, Object> tool : allTools) {
                String toolName = getToolNameFromDefinition(tool);
                if (toolName != null && canUseTool.apply(toolName)) {
                    filteredTools.add(tool);
                }
            }

            log.debug("Fork agent: {} tools available after filtering (canUseTool)", filteredTools.size());
            return filteredTools;
        } catch (Exception e) {
            log.error("Error getting available tools", e);
            return null;
        }
    }

    /**
     * 从工具定义中获取工具名
     */
    private String getToolNameFromDefinition(Map<String, Object> toolDef) {
        if (toolDef == null) return null;
        // OpenAI schema 格式: {"type": "function", "function": {"name": "tool_name", ...}}
        Object functionObj = toolDef.get("function");
        if (functionObj instanceof Map<?, ?> functionMap) {
            Object name = functionMap.get("name");
            return (name instanceof String) ? (String) name : null;
        }
        return null;
    }

    /**
     * 执行工具 - 复用 RunAgent 的工具执行逻辑
     *
     * @param canUseTool 工具使用限制函数，参数为工具名，返回 true 表示允许使用；null 表示不限制
     */
    private String executeTool(String toolName, Map<String, Object> args, java.util.function.Function<String, Boolean> canUseTool) {
        // 检查工具是否被允许使用
        if (canUseTool != null && !canUseTool.apply(toolName)) {
            return "{\"error\": \"Tool not allowed: " + toolName + "\"}";
        }

        try {
            // 从 ToolView 获取工具
            if (toolView == null) {
                return "{\"error\": \"ToolView not initialized\"}";
            }

            Object toolObj = toolView.get(toolName);
            if (!(toolObj instanceof agent.tool.Tool)) {
                return "{\"error\": \"Tool not found: " + toolName + "\"}";
            }
            agent.tool.Tool tool = (agent.tool.Tool) toolObj;

            // 执行工具
            String result = tool.execute(args).toCompletableFuture().join();
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("Tool execution failed: name={}", toolName, e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ==================== Prompt Cache Sharing (TODO) ====================

    /**
     * Run forked agent with prompt cache sharing for compaction.
     *
     * This allows the forked agent to reuse the main conversation's cached prefix
     * (system prompt, tools, context messages), significantly reducing API costs.
     *
     * N/A: javaclawbot does not have prompt cache sharing mechanism
     * The CompactService uses direct LLM API calls instead
     *
     * 参考: Open-ClaudeCode/src/utils/forkedAgent.ts
     *
     * @param promptMessages Summary request messages
     * @param cacheSafeParams Parameters for cache key calculation
     * @param canUseTool Tool use policy
     * @param querySource Query source identifier
     * @param forkLabel Label for the fork
     * @param maxTurns Maximum turns (should be 1 for compaction)
     * @param skipCacheWrite Whether to skip writing to cache
     * @return Fork result with generated summary
     */
    public CompletableFuture<ForkResult> runForkedAgentForCompact(
            List<Map<String, Object>> promptMessages,
            Map<String, Object> cacheSafeParams,
            Object canUseTool,
            String querySource,
            String forkLabel,
            int maxTurns,
            boolean skipCacheWrite) {
        // N/A: javaclawbot does not have prompt cache sharing mechanism
        // The CompactService.streamCompactSummary() uses direct LLM API calls
        // This would require deep integration with the LLM provider's caching API
        throw new UnsupportedOperationException("Prompt cache sharing not implemented - using direct LLM API calls instead");
    }
}
