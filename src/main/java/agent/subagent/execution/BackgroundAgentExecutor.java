package agent.subagent.execution;

import agent.subagent.task.AppState;
import agent.subagent.task.TaskStatus;
import agent.subagent.task.local.LocalAgentTaskState;
import agent.tool.Tool;
import agent.tool.ToolView;
import agent.tool.ToolUseContext;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * Background agent executor for built-in agent types (Explore, Plan, GeneralPurpose).
 *
 * Provides true asynchronous execution when background=true is specified.
 * Implements fire-and-forget pattern: immediately returns task ID, executes in background.
 *
 * Corresponds to Open-ClaudeCode: src/tools/AgentTool/agentToolUtils.ts - runAsyncAgentLifecycle()
 */
public class BackgroundAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAgentExecutor.class);

    /** Background executor service (daemon threads) */
    private final ExecutorService executor;

    /** AppState for task registration */
    private final AppState appState;

    /** AppState setter for updates */
    private final AppState.Setter setAppState;

    /** MessageBus for notifications */
    private final MessageBus messageBus;

    /** ToolView for fallback context creation (injected via constructor) */
    private final ToolView toolView;

    /** Running background tasks for potential cancellation */
    private final ConcurrentHashMap<String, CompletableFuture<String>> runningTasks = new ConcurrentHashMap<>();

    /** Task metadata for notifications */
    private final ConcurrentHashMap<String, TaskMetadata> taskMetadata = new ConcurrentHashMap<>();

    /** Completion notification callback: (taskId, result) -> CompletionStage<Void> */
    private BiFunction<String, String, CompletionStage<Void>> completionCallback;

    /**
     * Task metadata for notification
     */
    private static class TaskMetadata {
        final String agentType;
        final String prompt;
        final String sessionKey;
        final String channel;
        final String chatId;

        TaskMetadata(String agentType, String prompt, String sessionKey, String channel, String chatId) {
            this.agentType = agentType;
            this.prompt = prompt;
            this.sessionKey = sessionKey;
            this.channel = channel;
            this.chatId = chatId;
        }
    }

    public BackgroundAgentExecutor(AppState appState, AppState.Setter setAppState, MessageBus messageBus, ToolView toolView) {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("background-agent-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        this.appState = appState;
        this.setAppState = setAppState;
        this.messageBus = messageBus;
        this.toolView = toolView;
    }

    /**
     * Set completion notification callback.
     * Called when a background task completes with (taskId, result).
     * Should send notification to user or inject result into agent.
     */
    public void setCompletionCallback(BiFunction<String, String, CompletionStage<Void>> callback) {
        this.completionCallback = callback;
    }

    /**
     * Execute agent in background, immediately returning task ID.
     *
     * @param agentType  Agent type (Explore, Plan, general-purpose)
     * @param prompt     User prompt
     * @param systemPrompt System prompt for the agent
     * @param parentContext Parent tool use context (can be null)
     * @param sessionKey Session key for notification routing
     * @param channel Channel for notification
     * @param chatId Chat ID for notification
     * @return JSON string with status "async_launched" and task ID
     */
    public String executeAsync(String agentType, String prompt, String systemPrompt,
                               ToolUseContext parentContext,
                               String sessionKey, String channel, String chatId) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        log.info("BackgroundAgentExecutor: starting async agent taskId={}, type={}, prompt={}",
                taskId, agentType, prompt);

        // Create task state
        LocalAgentTaskState taskState = LocalAgentTaskState.create(
                taskId,
                prompt,
                null,  // toolUseId
                prompt,
                agentType
        );
        taskState.setBackgrounded(true);
        taskState.setAgentId(taskId);
        taskState.setStartTime(Instant.now());
        taskState.setStatus(TaskStatus.PENDING);

        // Register task
        AppState.registerTask(taskState, setAppState);

        // Store metadata for notification
        taskMetadata.put(taskId, new TaskMetadata(agentType, prompt, sessionKey, channel, chatId));

        // Create CompletableFuture for result tracking
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        runningTasks.put(taskId, resultFuture);

        // Execute asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Create isolated context for this background task
                ToolUseContext isolatedContext = createIsolatedContext(parentContext, taskId, agentType);

                // Set context for this thread
                // ToolUseContextHolder.setCurrent(isolatedContext);

                try {
                    // Mark task as running
                    AppState.updateTaskState(taskId, setAppState, (LocalAgentTaskState t) -> {
                        t.setStatus(TaskStatus.RUNNING);
                        t.setStartTime(Instant.now());
                        return t;
                    });

                    // Execute query loop
                    String result = RunAgent.executeQueryLoopAsync(
                            systemPrompt,
                            prompt,
                            agentType,
                            null,  // model
                            null,  // provider
                            isolatedContext
                    );

                    // Mark task as completed
                    final String finalResult = result;
                    AppState.updateTaskState(taskId, setAppState, (LocalAgentTaskState t) -> {
                        t.setStatus(TaskStatus.COMPLETED);
                        t.setEndTime(Instant.now());
                        t.setResult(finalResult);
                        return t;
                    });

                    log.info("BackgroundAgentExecutor: task {} completed, result length={}",
                            taskId, result != null ? result.length() : 0);
                    resultFuture.complete(result);

                    // Send completion notification
                    notifyCompletion(taskId, result);

                } finally {

                }

            } catch (CancellationException e) {
                handleCancellation(taskId, resultFuture);
            } catch (Exception e) {
                handleError(taskId, resultFuture, e);
            } finally {
                runningTasks.remove(taskId);
                taskMetadata.remove(taskId);
            }
        }, executor);

        // Return immediately with async_launched status
        return String.format(
                "{\"status\":\"async_launched\",\"agentId\":\"%s\",\"prompt\":\"%s\"}",
                taskId, escapeJson(prompt));
    }

    /**
     * Send completion notification via MessageBus.publishInbound.
     * The result is sent as a user-role message to the main agent.
     */
    private void notifyCompletion(String taskId, String result) {
        TaskMetadata metadata = taskMetadata.get(taskId);
        if (metadata == null) {
            log.warn("BackgroundAgentExecutor: no metadata for task {}", taskId);
            return;
        }

        // Format notification message for the main agent
        String notification;
        if (result != null && result.startsWith("Task failed:")) {
            notification = String.format("[Background task %s (%s) failed]\n\n%s",
                    taskId, metadata.agentType, result.substring(13));
        } else if ("Task cancelled".equals(result)) {
            notification = String.format("[Background task %s (%s) was cancelled]",
                    taskId, metadata.agentType);
        } else {
            notification = String.format("[Background task %s (%s) completed]\n\n%s",
                    taskId, metadata.agentType, truncateResult(result));
        }

        // Send via MessageBus.publishInbound so the main agent receives this as a user message
        if (messageBus != null && metadata.channel != null) {
            // Create metadata for the inbound message
            Map<String, Object> msgMetadata = new java.util.HashMap<>();
            msgMetadata.put("_backgroundTask", true);
            msgMetadata.put("taskId", taskId);
            msgMetadata.put("agentType", metadata.agentType);
            msgMetadata.put("agentId", taskId);  // The taskId serves as the agentId
            msgMetadata.put("prompt", metadata.prompt);

            InboundMessage inboundMsg = new InboundMessage(
                    metadata.channel,
                    "background-agent",  // senderId indicates it's from a background agent
                    metadata.chatId,
                    notification,
                    List.of(),
                    msgMetadata
            );

            messageBus.publishInbound(inboundMsg);
            log.info("BackgroundAgentExecutor: notification sent via inbound for task {} to channel {}",
                    taskId, metadata.channel);
        }

        // Also call completion callback if set
        if (completionCallback != null) {
            completionCallback.apply(taskId, result)
                    .whenComplete((v, ex) -> {
                        if (ex != null) {
                            log.error("BackgroundAgentExecutor: callback failed for task {}", taskId, ex);
                        }
                    });
        }
    }

    private String truncateResult(String result) {
        if (result == null) return "";
        if (result.length() <= 500) return result;
        return result.substring(0, 500) + "...\n\n(truncated)";
    }

    /**
     * Terminate a running background task.
     */
    public boolean terminate(String taskId) {
        CompletableFuture<String> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);

            AppState.updateTaskState(taskId, setAppState, (LocalAgentTaskState t) -> {
                t.setStatus(TaskStatus.KILLED);
                t.setEndTime(Instant.now());
                return t;
            });

            log.info("BackgroundAgentExecutor: task {} terminated", taskId);

            // Send cancellation notification via inbound
            notifyCompletion(taskId, "Task cancelled");

            return true;
        }
        return false;
    }

    /**
     * Check if a task is still running.
     */
    public boolean isRunning(String taskId) {
        CompletableFuture<String> future = runningTasks.get(taskId);
        return future != null && !future.isDone();
    }

    /**
     * Get the result of a completed task.
     */
    public String getResult(String taskId) {
        CompletableFuture<String> future = runningTasks.get(taskId);
        if (future != null && future.isDone()) {
            try {
                return future.get();
            } catch (Exception e) {
                return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
            }
        }
        return null;
    }

    /**
     * Create isolated subagent context from parent context.
     * 永远不返回 null：如果没有 parentContext，从 ThreadLocal 获取；
     * 如果都没有，创建基于 sharedTools 的 fallback 上下文。
     */
    private ToolUseContext createIsolatedContext(ToolUseContext parentContext, String agentId, String agentType) {
        // 第一选择：从 ThreadLocal 获取当前上下文
        // 第二选择：创建基于 sharedTools 的 fallback 上下文（永远不返回 null）
        if (parentContext == null) {
            log.warn("No parent context found, creating fallback context with shared tools");
            return createFallbackContext(agentId, agentType);
        }

        // 调试日志：记录 parentContext 状态
        log.info("[createIsolatedContext] parentContext has tools count={}, thread={}",
                parentContext.getTools() != null ? parentContext.getTools().size() : -1,
                Thread.currentThread().getName());

        // Create abort controller linked to parent
        AtomicBoolean childAbortController = new AtomicBoolean(false);
        AtomicBoolean parentAbort = parentContext.getAbortController();
        if (parentAbort != null) {
            Thread observer = new Thread(() -> {
                try {
                    while (!parentAbort.get() && !childAbortController.get()) {
                        Thread.sleep(100);
                    }
                    if (!childAbortController.get()) {
                        childAbortController.set(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            observer.setDaemon(true);
            observer.setName("bg-agent-abort-" + agentId);
            observer.start();
        }

        // Get or create query tracking
        ToolUseContext.QueryTracking parentQueryTracking = parentContext.getQueryTracking();
        ToolUseContext.QueryTracking childQueryTracking =
                parentQueryTracking != null ? parentQueryTracking.child() : new ToolUseContext.QueryTracking();

        // Build isolated context - copy tools from parent context
        // This is critical: subagents need the same tool definitions as the parent
        // to pass to the LLM and to be able to execute tools via ToolRegistry
        ToolUseContext.Builder builder = ToolUseContext.builder()
                .agentId(agentId)
                .agentType(agentType)
                .abortController(childAbortController)
                .workspace(parentContext.getWorkspace())
                .restrictToWorkspace(parentContext.isRestrictToWorkspace())
                .mainLoopModel(parentContext.getMainLoopModel())
                .messages(null)
                .sessionId(parentContext.getSessionId())
                .mcpClients(parentContext.getMcpClients() != null ? new java.util.ArrayList<>(parentContext.getMcpClients()) : null)
                .tools(parentContext.getTools())  // Copy tools from parent context
                .nestedMemoryAttachmentTriggers(new java.util.HashSet<>())
                .loadedNestedMemoryPaths(new java.util.HashSet<>())
                .dynamicSkillDirTriggers(new java.util.HashSet<>())
                .discoveredSkillNames(new java.util.HashSet<>())
                .toolDecisions(null)
                .queryTracking(childQueryTracking)
                .fileReadingLimits(parentContext.getFileReadingLimits())
                .userModified(parentContext.getUserModified());

        return builder.build();
    }

    /**
     * 创建基于注入 ToolView 的 fallback 上下文。
     * 当没有 parentContext 时使用，确保子代理总有工具可用。
     */
    private ToolUseContext createFallbackContext(String agentId, String agentType) {
        return ToolUseContext.builder()
                .agentId(agentId)
                .agentType(agentType)
                .tools(toolView.getDefinitions())
                .toolView(toolView)
                .nestedMemoryAttachmentTriggers(new java.util.HashSet<>())
                .loadedNestedMemoryPaths(new java.util.HashSet<>())
                .dynamicSkillDirTriggers(new java.util.HashSet<>())
                .discoveredSkillNames(new java.util.HashSet<>())
                .queryTracking(new ToolUseContext.QueryTracking())
                .build();
    }

    private void handleCancellation(String taskId, CompletableFuture<String> future) {
        AppState.updateTaskState(taskId, setAppState, (LocalAgentTaskState t) -> {
            t.setStatus(TaskStatus.KILLED);
            t.setEndTime(Instant.now());
            return t;
        });
        log.info("BackgroundAgentExecutor: task {} cancelled", taskId);
        future.cancel(true);
        notifyCompletion(taskId, "Task cancelled");
    }

    private void handleError(String taskId, CompletableFuture<String> future, Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
        AppState.updateTaskState(taskId, setAppState, (LocalAgentTaskState t) -> {
            t.setStatus(TaskStatus.FAILED);
            t.setEndTime(Instant.now());
            t.setError(errorMsg);
            return t;
        });
        log.error("BackgroundAgentExecutor: task {} failed: {}", taskId, errorMsg, e);
        future.completeExceptionally(e);
        notifyCompletion(taskId, "Task failed: " + errorMsg);
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        runningTasks.forEach((taskId, future) -> {
            if (!future.isDone()) {
                terminate(taskId);
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
}
