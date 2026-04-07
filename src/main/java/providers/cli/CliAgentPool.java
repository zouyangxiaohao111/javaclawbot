package providers.cli;

import lombok.extern.slf4j.Slf4j;
import providers.cli.claudecode.ClaudeCodeAgent;
import providers.cli.opencode.OpenCodeAgent;
import providers.cli.model.PermissionResult;
import providers.cli.permission.PermissionDecision;
import providers.cli.permission.PermissionEngine;

import java.util.*;
import java.util.concurrent.*;

/**
 * CLI Agent 实例池 - 管理所有运行中的 Agent 实例
 *
 * key 格式: "project:agentType" 如 "p1:claude", "p2:opencode"
 */
@Slf4j
public class CliAgentPool {

    private final Map<String, CliAgentSession> sessions = new ConcurrentHashMap<>();
    private final ProjectRegistry projectRegistry;
    private final PermissionEngine permissionEngine;
    private final CliAgentOutputHandler outputHandler;

    // Agent 工厂
    private final Map<String, CliAgent> agents = new ConcurrentHashMap<>();

    public CliAgentPool(ProjectRegistry projectRegistry,
                        PermissionEngine permissionEngine,
                        CliAgentOutputHandler outputHandler) {
        this.projectRegistry = projectRegistry;
        this.permissionEngine = permissionEngine;
        this.outputHandler = outputHandler;

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
        String workDir = projectRegistry.getPath(project);
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

        // 权限请求特殊处理
        if (event.type() == CliEventType.PERMISSION_REQUEST) {
            handlePermissionRequest(project, agentType, session, event);
            return;
        }

        // 其他事件交给 OutputHandler
        outputHandler.handleEvent(project, agentType, event);
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
                session.respondPermission(event.requestId(), PermissionResult.allow(decision.updatedInput()));
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
