package providers.cli;

import lombok.extern.slf4j.Slf4j;
import providers.cli.claudecode.ClaudeCodeAgent;
import providers.cli.opencode.OpenCodeAgent;
import providers.cli.model.PermissionResult;
import providers.cli.permission.PermissionDecision;
import providers.cli.permission.PermissionEngine;
import session.CliAgentSessionManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * CLI Agent 实例池 - 管理所有运行中的 Agent 实例
 *
 * key 格式: "project:agentType" 如 "p1:claude", "p2:opencode"
 *
 * 改进:
 * - CLI Agent 会话事件独立存储，不污染主代理上下文
 * - 存放路径: {workspace}/sessions/cliagent/{channel}_{chatId}_{project}_{agentType}_{sessionId}.jsonl
 * - 执行完毕后自动通知主代理，实现自主感知
 */
@Slf4j
public class CliAgentPool {

    private final Map<String, CliAgentSession> sessions = new ConcurrentHashMap<>();
    private final Supplier<ProjectRegistry> projectRegistrySupplier;
    private final PermissionEngine permissionEngine;
    private final CliAgentOutputHandler outputHandler;
    private final CliAgentSessionManager sessionManager;

    /** session key -> current sessionId 映射 */
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();

    /** project:agentType -> sessionKey (通道标识) 映射 */
    private final Map<String, String> channelSessionKeys = new ConcurrentHashMap<>();

    /** project:agentType -> 起始时间 映射 */
    private final Map<String, Long> sessionStartTimes = new ConcurrentHashMap<>();

    /** CLI Agent 完成回调：发送消息给主代理 */
    private Consumer<CliAgentCompletionEvent> completionCallback;

    // Agent 工厂
    private final Map<String, CliAgent> agents = new ConcurrentHashMap<>();

    /**
     * CLI Agent 完成事件
     */
    public record CliAgentCompletionEvent(
            String project,
            String agentType,
            String sessionId,
            String sessionKey,
            String sessionFile,
            int inputTokens,
            int outputTokens,
            long durationMs,
            boolean success,
            String errorMessage
    ) {}

    public CliAgentPool(Supplier<ProjectRegistry> projectRegistrySupplier,
                        PermissionEngine permissionEngine,
                        CliAgentOutputHandler outputHandler,
                        CliAgentSessionManager sessionManager) {
        this.projectRegistrySupplier = projectRegistrySupplier;
        this.permissionEngine = permissionEngine;
        this.outputHandler = outputHandler;
        this.sessionManager = sessionManager;

        // 注册默认 Agent
        registerAgent("claude", new ClaudeCodeAgent());
        registerAgent("claudecode", new ClaudeCodeAgent());
        registerAgent("cc", new ClaudeCodeAgent());

        registerAgent("opencode", new OpenCodeAgent());
        registerAgent("oc", new OpenCodeAgent());
    }

    /**
     * 注册 Agent
     */
    public void registerAgent(String name, CliAgent agent) {
        agents.put(name.toLowerCase(), agent);
        log.debug("Registered CLI agent: {}", name);
    }

    /**
     * 设置通道 sessionKey（由 CliAgentCommandHandler 调用）
     *
     * @param project    项目名
     * @param agentType  Agent 类型
     * @param sessionKey 通道 sessionKey (格式: channel:chatId)
     */
    public void setChannelSessionKey(String project, String agentType, String sessionKey) {
        String key = buildKey(project, agentType);
        channelSessionKeys.put(key, sessionKey);
        log.debug("Set channel session key: {} -> {}", key, sessionKey);
    }

    /**
     * 获取通道 sessionKey
     */
    public String getChannelSessionKey(String project, String agentType) {
        return channelSessionKeys.get(buildKey(project, agentType));
    }

    /**
     * 设置 CLI Agent 完成回调
     * 当 CLI Agent 执行完毕时，会调用此回调通知主代理
     *
     * @param callback 回调函数，接收 CliAgentCompletionEvent
     */
    public void setCompletionCallback(Consumer<CliAgentCompletionEvent> callback) {
        this.completionCallback = callback;
    }

    /**
     * 记录会话开始时间
     */
    private void recordSessionStart(String key) {
        sessionStartTimes.put(key, System.currentTimeMillis());
    }

    /**
     * 计算会话持续时间
     */
    private long getSessionDuration(String key) {
        Long startTime = sessionStartTimes.get(key);
        if (startTime == null) return 0;
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 构建 session 文件名
     */
    private String buildSessionFilename(String channelSessionKey, String project, String agentType, String sessionId) {
        String channel = "unknown";
        String chatId = "default";

        if (channelSessionKey != null && !channelSessionKey.isBlank()) {
            String[] parts = channelSessionKey.split(":", 2);
            if (parts.length >= 1) channel = parts[0].replaceAll("[^a-zA-Z0-9_-]", "_");
            if (parts.length >= 2) chatId = parts[1].replaceAll("[^a-zA-Z0-9_-]", "_");
        }

        return channel + "_" + chatId + "_" +
               (project != null ? project.replaceAll("[^a-zA-Z0-9_-]", "_") : "unknown") + "_" +
               (agentType != null ? agentType.replaceAll("[^a-zA-Z0-9_-]", "_") : "unknown") + "_" +
               (sessionId != null ? sessionId.replaceAll("[^a-zA-Z0-9_-]", "_") : "default") + ".jsonl";
    }

    /**
     * 获取或创建 Agent 会话
     */
    public CompletableFuture<CliAgentSession> getOrCreate(String project, String agentType) {
        String key = buildKey(project, agentType);

        CliAgentSession existing = sessions.get(key);
        if (existing != null && existing.isAlive()) {
            return CompletableFuture.completedFuture(existing);
        }

        return createSession(project, agentType);
    }

    /**
     * 创建新会话
     */
    private CompletableFuture<CliAgentSession> createSession(String project, String agentType) {
        String workDir = projectRegistrySupplier.get().getPath(project);
        if (workDir == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("项目 '" + project + "' 未绑定。使用 /bind " + project + "=<路径> 绑定"));
        }

        CliAgent agent = agents.get(agentType.toLowerCase());
        if (agent == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("未知的 Agent 类型: " + agentType));
        }

        // 检查 CLI 是否可用
        if (!agent.checkCliAvailable()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("CLI 不可用: " + agent.cliBinaryName()));
        }

        // 构建配置
        CliAgentConfig config = CliAgentConfig.builder()
                .agentType(agentType)
                .workDir(workDir)
                .build();

        return agent.createSession(config)
                .thenApply(session -> {
                    String key = buildKey(project, agentType);
                    sessions.put(key, session);
                    log.info("Created CLI session: {} -> {} ({})", key, workDir, agentType);

                    // 记录会话开始时间
                    recordSessionStart(key);

                    // 订阅事件
                    session.events().subscribe(event -> {
                        handleEvent(project, agentType, session, event);
                    });

                    return session;
                });
    }

    /**
     * 处理事件
     */
    private void handleEvent(String project, String agentType, CliAgentSession session, CliEvent event) {
        if (event == null || event.type() == null) return;

        String key = buildKey(project, agentType);

        // 提取并跟踪 sessionId
        if (event.type() == CliEventType.SESSION_ID && event.sessionId() != null) {
            sessionIds.put(key, event.sessionId());
            log.debug("[{}/{}] Session ID tracked: {}", agentType, project, event.sessionId());
        }

        // 获取当前的 sessionId 和 channel sessionKey
        String currentSessionId = sessionIds.getOrDefault(key, session.currentSessionId());
        String channelSessionKey = channelSessionKeys.get(key);

        // 先保存事件到独立 session（如果 sessionManager 存在）
        if (sessionManager != null && currentSessionId != null) {
            sessionManager.saveEvent(project, agentType, currentSessionId, channelSessionKey, event);
        }

        // 检测完成事件（RESULT 或 ERROR），通知主代理
        if (event.type() == CliEventType.RESULT || event.type() == CliEventType.ERROR) {
            handleCompletion(project, agentType, currentSessionId, channelSessionKey, event);
        }

        // 权限请求特殊处理
        if (event.type() == CliEventType.PERMISSION_REQUEST) {
            handlePermissionRequest(project, agentType, session, event);
            return;
        }

        // 其他事件交给 OutputHandler
        outputHandler.handleEvent(project, agentType, currentSessionId, event);
    }

    /**
     * 处理 CLI Agent 完成事件
     * 通知主代理 CLI Agent 已完成，可进行下一步操作
     */
    private void handleCompletion(String project, String agentType, String sessionId,
                                   String channelSessionKey, CliEvent event) {
        String key = buildKey(project, agentType);

        // 构建完成事件
        boolean success = event.type() == CliEventType.RESULT;
        String errorMessage = success ? null :
                (event.error() != null ? event.error().getMessage() : "Unknown error");
        long durationMs = getSessionDuration(key);
        String sessionFile = buildSessionFilename(channelSessionKey, project, agentType, sessionId);

        CliAgentCompletionEvent completionEvent = new CliAgentCompletionEvent(
                project,
                agentType,
                sessionId,
                channelSessionKey,
                sessionFile,
                event.inputTokens(),
                event.outputTokens(),
                durationMs,
                success,
                errorMessage
        );

        log.info("[{}/{}] CLI Agent completed: success={}, duration={}ms, tokens={}/{}",
                agentType, project, success, durationMs, event.inputTokens(), event.outputTokens());

        // 调用完成回调，通知主代理
        if (completionCallback != null) {
            try {
                completionCallback.accept(completionEvent);
                log.debug("Notified main agent of CLI completion: {}", completionEvent);
            } catch (Exception e) {
                log.warn("Error in completion callback: {}", e.getMessage());
            }
        }

        // 清理会话开始时间
        sessionStartTimes.remove(key);
    }

    /**
     * 处理权限请求
     */
    private void handlePermissionRequest(String project, String agentType,
                                          CliAgentSession session, CliEvent event) {
        providers.cli.permission.PermissionRequest request =
                new providers.cli.permission.PermissionRequest(
                event.requestId(),
                event.toolName(),
                event.toolInputRaw()
        );

        PermissionDecision decision = permissionEngine.decide(request);

        log.info("[Permission] {} {}/{}: {} -> {}",
                decision.behavior(), agentType, project, event.toolName(), decision.behavior());

        switch (decision.behavior()) {
            case "allow" -> {
                // 如果 decision.updatedInput() 为 null，使用原始输入
                // Claude Code 要求 updatedInput 必须存在
                Map<String, Object> input = decision.updatedInput() != null
                        ? decision.updatedInput()
                        : event.toolInputRaw();
                session.respondPermission(event.requestId(), PermissionResult.allow(input));
            }
            case "deny" -> {
                session.respondPermission(event.requestId(), PermissionResult.deny(decision.message()));
                outputHandler.notifyAutoDeny(project, agentType, event.toolName(), decision.message());
            }
            case "ask_user" -> {
                outputHandler.askUserPermission(project, agentType, session, event);
            }
        }
    }

    /**
     * 获取现有会话
     */
    public CliAgentSession get(String project, String agentType) {
        return sessions.get(buildKey(project, agentType));
    }

    /**
     * 获取项目的任意活跃会话
     */
    public CliAgentSession getAnyForProject(String project) {
        for (Map.Entry<String, CliAgentSession> entry : sessions.entrySet()) {
            if (entry.getKey().startsWith(project + ":") && entry.getValue().isAlive()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 停止并移除会话
     */
    public CompletableFuture<Void> stop(String project, String agentType) {
        String key = buildKey(project, agentType);
        CliAgentSession session = sessions.remove(key);

        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Stopping CLI session: {}", key);
        return session.close()
                .exceptionally(e -> {
                    log.warn("Error closing session {}: {}", key, e.getMessage());
                    return null;
                });
    }

    /**
     * 停止项目的所有会话
     */
    public CompletableFuture<Void> stopAllForProject(String project) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        Iterator<Map.Entry<String, CliAgentSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CliAgentSession> entry = it.next();
            if (entry.getKey().startsWith(project + ":")) {
                it.remove();
                futures.add(entry.getValue().close());
            }
        }

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * 获取所有活跃会话
     */
    public Map<String, CliAgentSession> getActiveSessions() {
        Map<String, CliAgentSession> active = new LinkedHashMap<>();
        for (Map.Entry<String, CliAgentSession> entry : sessions.entrySet()) {
            if (entry.getValue().isAlive()) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }

    /**
     * 获取所有会话 (包括非活跃)
     */
    public Map<String, CliAgentSession> getAllSessions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
    }

    /**
     * 获取特定会话
     */
    public CliAgentSession getSession(String key) {
        return sessions.get(key);
    }

    /**
     * 移除会话
     */
    public void removeSession(String key) {
        sessions.remove(key);
        log.debug("Removed session: {}", key);
    }

    /**
     * 关闭所有会话
     */
    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, CliAgentSession> entry : sessions.entrySet()) {
            futures.add(entry.getValue().close());
        }

        sessions.clear();

        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .exceptionally(e -> {
                    log.warn("Error closing some sessions", e);
                    return null;
                });
    }

    /**
     * 获取会话状态摘要
     */
    public String getStatusSummary() {
        Map<String, CliAgentSession> active = getActiveSessions();

        if (active.isEmpty()) {
            return "📊 无运行中的 CLI Agent";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 运行中的 CLI Agent (").append(active.size()).append("):\n");

        for (Map.Entry<String, CliAgentSession> entry : active.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            String project = parts[0];
            String agentType = parts.length > 1 ? parts[1] : "unknown";
            CliAgentSession session = entry.getValue();

            sb.append("  • [").append(agentType.toUpperCase()).append("/").append(project).append("]");
            sb.append(" ").append(session.getConfig().workDir());

            if (!session.isAlive()) {
                sb.append(" ⚠️ (已停止)");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String buildKey(String project, String agentType) {
        return project.toLowerCase() + ":" + agentType.toLowerCase();
    }
}
