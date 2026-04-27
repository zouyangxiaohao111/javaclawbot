package providers.cli;

import bus.InboundMessage;
import bus.OutboundMessage;
import lombok.extern.slf4j.Slf4j;
import providers.cli.model.FileAttachment;
import providers.cli.model.ImageAttachment;
import providers.cli.permission.PermissionEngine;
import session.CliAgentSessionManager;
import utils.Helpers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * CLI Agent 命令处理器
 *
 * 处理以下命令:
 * - /bind <名称>=<路径> [--main]  绑定项目，可选设为主代理项目
 * - /bind --main <路径>           直接设置主代理项目
 * - /unbind <名称>                解绑项目
 * - /projects                     列出所有项目
 * - /cc <project> <prompt>        使用 Claude Code
 * - /oc <project> <prompt>        使用 OpenCode
 * - /status [project]             查看状态
 * - /stop <project> [type]        停止 Agent
 * - /stopall                      停止所有 Agent
 *
 * 改进:
 * - CLI Agent 会话独立存储，不污染主代理上下文
 * - 存放路径: {workspace}/sessions/cliagent/{project}_{agentType}_{sessionId}.jsonl
 */
@Slf4j
public class CliAgentCommandHandler {

    /** 默认 registry（fallback，无 session 上下文时使用） */
    private final ProjectRegistry defaultRegistry;
    /** per-session registries: sessionKey → ProjectRegistry */
    private final Map<String, ProjectRegistry> sessionRegistries = new java.util.concurrent.ConcurrentHashMap<>();
    private final CliAgentPool agentPool;
    private final CliAgentOutputHandler outputHandler;
    private final CliAgentSessionManager sessionManager;
    private final ExecutorService executor;
    private final Path workspacePath;

    // 发送消息到渠道的回调 (message, sessionKey)
    private BiConsumer<String, String> sendToChannel;

    // 发送消息到渠道的回调（带元数据）(message, sessionKey, metadata)
    private TriConsumer<String, String, Map<String, Object>> sendToChannelWithMeta;

    // 项目 -> sessionKey 映射（用于路由回复到正确的渠道）
    private final Map<String, String> projectSessionKeys = new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前请求的 sessionKey（由 AgentLoop 设置，供 Tool 使用） */
    private final ThreadLocal<String> currentSessionKey = new ThreadLocal<>();

    /**
     * 三参数消费者接口
     */
    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    public CliAgentCommandHandler(Path workspacePath) {
        this(workspacePath, null);
    }

    /** 带外部 ProjectRegistry 的构造器（GUI 使用，按 session 隔离项目绑定） */
    public CliAgentCommandHandler(Path workspacePath, ProjectRegistry projectRegistry) {
        this.workspacePath = workspacePath;

        if (projectRegistry != null) {
            this.defaultRegistry = projectRegistry;
        } else {
            this.defaultRegistry = createRegistry(Helpers.getDataPath()
                    .resolve("projects")
                    .resolve("projects.json"));
        }

        this.outputHandler = new CliAgentOutputHandler();
        PermissionEngine permissionEngine = PermissionEngine.createDefault();

        // 初始化 CLI Agent Session 管理器
        this.sessionManager = new CliAgentSessionManager(workspacePath);

        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cli-agent-handler");
            t.setDaemon(true);
            return t;
        });

        // 传入 Supplier 给 agentPool，支持 per-session 解析
        this.agentPool = new CliAgentPool(
                this::getProjectRegistry,
                permissionEngine,
                outputHandler,
                sessionManager
        );

        // 设置输出回调：根据 project 查找对应的 sessionKey 路由回复
        // 使用新的带元数据的回调
        outputHandler.setSendToChatWithMetaCallback((formattedMessage, project, metadata) -> {
            if (sendToChannel != null || sendToChannelWithMeta != null) {
                String sessionKey = project != null ? projectSessionKeys.get(project) : null;

                // 优先使用带元数据的回调
                if (sendToChannelWithMeta != null) {
                    sendToChannelWithMeta.accept(formattedMessage, sessionKey, metadata);
                } else {
                    sendToChannel.accept(formattedMessage, sessionKey);
                }
            }
        });
    }

    /**
     * 获取 CLI Agent Session 管理器
     */
    public CliAgentSessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * 设置当前请求的 sessionKey（由 AgentLoop 调用）
     * 用于 Tool 执行时获取当前通道信息
     */
    public void setCurrentSessionKey(String sessionKey) {
        currentSessionKey.set(sessionKey);
    }

    /**
     * 获取当前请求的 sessionKey
     * 用于 Tool 执行时获取当前通道信息
     */
    public String getCurrentSessionKey() {
        return currentSessionKey.get();
    }

    /**
     * 清除当前请求的 sessionKey
     */
    public void clearCurrentSessionKey() {
        currentSessionKey.remove();
    }

    /**
     * 设置项目的 sessionKey 映射（由 CliAgentTool 调用）
     * 用于 CLI Agent 输出路由到正确的通道
     *
     * @param project    项目名
     * @param sessionKey 通道 sessionKey (格式: channel:chatId)
     */
    public void setProjectSessionKey(String project, String sessionKey) {
        if (project != null && sessionKey != null) {
            projectSessionKeys.put(project, sessionKey);
            log.debug("Set project session key: {} -> {}", project, sessionKey);
        }
    }

    /**
     * 设置发送消息回调
     */
    public void setSendToChannel(BiConsumer<String, String> callback) {
        this.sendToChannel = callback;
    }

    /**
     * 设置发送消息回调（带元数据）
     * 主代理可以使用此方法来识别 CLI 子代理的输出
     */
    public void setSendToChannelWithMeta(TriConsumer<String, String, Map<String, Object>> callback) {
        this.sendToChannelWithMeta = callback;
    }

    /**
     * 处理命令
     *
     * @return true 如果命令被处理，false 如果不是 CLI Agent 命令
     */
    public boolean handleCommand(InboundMessage msg, String content) {
        if (content == null || !content.startsWith("/")) {
            return false;
        }

        String[] parts = content.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        try {
            switch (cmd) {
                case "/bind" -> {
                    handleBind(msg, parts);
                    return true;
                }
                case "/unbind" -> {
                    handleUnbind(msg, parts);
                    return true;
                }
                case "/projects" -> {
                    handleProjects(msg);
                    return true;
                }
                case "/cc", "/claude", "/claudecode" -> {
                    handleCliAgent(msg, parts, "claude");
                    return true;
                }
                case "/oc", "/opencode" -> {
                    handleCliAgent(msg, parts, "opencode");
                    return true;
                }
                case "/cli-status" -> {
                    handleStatus(msg, parts);
                    return true;
                }
                case "/stop" -> {
                    handleStop(msg, parts);
                    return true;
                }
                case "/cli-stopall" -> {
                    handleStopAll(msg);
                    return true;
                }
                case "/cli-history" -> {
                    handleHistory(msg, parts);
                    return true;
                }
                default -> {
                    // 检查是否是权限响应
                    if (outputHandler.hasPendingPermission(cmd)) {
                        String[] respParts = content.split("\\s+");
                        if (respParts.length >= 2) {
                            boolean allow = respParts[1].equalsIgnoreCase("y") ||
                                    respParts[1].equalsIgnoreCase("yes") ||
                                    respParts[1].equalsIgnoreCase("允许");
                            outputHandler.handleUserPermissionResponse(cmd, allow);
                            return true;
                        }
                    }
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("Error handling CLI agent command: {}", content, e);
            reply(msg, "❌ 命令执行失败: " + e.getMessage());
            return true;
        }
    }

    /**
     * 处理 /bind 命令
     *
     * 格式:
     * - /bind p1=/path/to/project           普通绑定
     * - /bind p1=/path/to/project --main    绑定并设为主代理
     * - /bind /path/to/project --main       自动命名并设为主代理
     * - /bind --main /path/to/project       直接设为主代理（名称为 main）
     */
    private void handleBind(InboundMessage msg, String[] parts) {
        if (parts.length < 2 || msg.getContent().equalsIgnoreCase("/bind -help") || msg.getContent().equalsIgnoreCase("/bind -h")) {
            reply(msg, "用法: /bind <名称>=<路径> [--main]\n" +
                    "示例:\n" +
                    "  /bind p1=/home/user/project           # 普通绑定\n" +
                    "  /bind p1=/home/user/project --main    # 绑定并设为主代理\n" +
                    "  /bind --main /home/user/project       # 直接设为主代理（名称为 main）");
            return;
        }

        // 解析参数
        boolean isMain = false;
        String nameArg = null;
        String pathArg = null;

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if ("--main".equalsIgnoreCase(part)) {
                isMain = true;
            } else if (nameArg == null) {
                nameArg = part;
            } else if (pathArg == null) {
                pathArg = part;
            }
        }

        // 处理 --main 后面直接跟路径的情况: /bind --main /path/to/project
        if (isMain && nameArg != null && !nameArg.contains("=") && pathArg == null) {
            // nameArg 实际上是路径
            pathArg = nameArg;
            nameArg = null;
        }

        if (nameArg == null || nameArg.isBlank()) {
            reply(msg, "❌ 缺少项目名称或路径");
            return;
        }

        // 解析 name=path 格式
        int eqIdx = nameArg.indexOf('=');
        String name;
        String path;

        if (eqIdx >= 0) {
            // name=path 格式
            name = nameArg.substring(0, eqIdx).trim();
            path = nameArg.substring(eqIdx + 1).trim();
        } else if (pathArg != null) {
            // name path 格式（单独的 name 和 path）
            name = nameArg.trim();
            path = pathArg.trim();
        } else {
            // 只有路径，自动生成名称
            path = nameArg.trim();
            name = isMain ? "main" : generateProjectName(path);
        }

        // 验证路径
        if (path == null || path.isBlank()) {
            reply(msg, "❌ 缺少项目路径");
            return;
        }

        // 校验路径是否存在
        Path projectPath = Path.of(path);
        if (!Files.exists(projectPath)) {
            reply(msg, "❌ 绑定失败: 路径不存在\n   " + path);
            return;
        }
        if (!Files.isDirectory(projectPath)) {
            reply(msg, "❌ 绑定失败: 路径不是目录\n   " + path);
            return;
        }

        // 执行绑定
        if (getProjectRegistry().bind(name, path, isMain)) {
            String mainHint = isMain ? " ⭐ [主代理]" : "";
            reply(msg, "✅ 项目已绑定" + mainHint + ": " + name + " → " + path);
        } else {
            reply(msg, "❌ 绑定失败");
        }
    }

    /**
     * 处理 /unbind 命令
     */
    private void handleUnbind(InboundMessage msg, String[] parts) {
        if (parts.length < 2) {
            reply(msg, "用法: /unbind <名称>");
            return;
        }

        String name = parts[1];
        if (getProjectRegistry().unbind(name)) {
            // 停止相关的 Agent
            agentPool.stopAllForProject(name);
            reply(msg, "✅ 项目已解绑: " + name);
        } else {
            reply(msg, "❌ 项目不存在: " + name);
        }
    }

    /**
     * 处理 /projects 命令
     */
    private void handleProjects(InboundMessage msg) {
        reply(msg, getProjectRegistry().formatList());
    }

    /**
     * 处理 CLI Agent 命令
     */
    private void handleCliAgent(InboundMessage msg, String[] parts, String agentType) {
        if (parts.length < 3) {
            reply(msg, "用法: /" + agentType + " <项目> <提示词>\n" +
                    "示例: /" + agentType + " p1 帮我分析代码结构");
            return;
        }

        String project = parts[1];
        String prompt = parts[2];

        // 检查项目是否存在
        String workDir = getProjectRegistry().getPath(project);
        if (workDir == null) {
            reply(msg, "❌ 项目 '" + project + "' 未绑定。\n" +
                    "使用 /bind " + project + "=<路径> 绑定项目");
            return;
        }

        // 检查路径是否存在
        if (!Files.exists(Path.of(workDir))) {
            reply(msg, "⚠️ 项目路径不存在: " + workDir + "\n" +
                    "是否要继续？路径将被自动创建。");
        }

        reply(msg, "🔄 启动 " + agentType.toUpperCase() + " @ " + project + "...");

        // 记录项目 -> sessionKey 映射，用于后续输出路由
        projectSessionKeys.put(project, msg.getSessionKey());

        // 设置通道 sessionKey 到 agentPool（用于 session 文件记录）
        agentPool.setChannelSessionKey(project, agentType, msg.getSessionKey());

        // 获取或创建会话
        agentPool.getOrCreate(project, agentType)
                .thenAccept(session -> {
                    // 提取图片和文件
                    List<ImageAttachment> images = extractImages(msg);
                    List<FileAttachment> files = extractFiles(msg);

                    // 发送消息
                    session.send(prompt, images, files)
                            .exceptionally(e -> {
                                log.error("Failed to send to CLI agent", e);
                                reply(msg, "[CC/" + project + "] ❌ 发送失败: " + e.getMessage());
                                return null;
                            });
                })
                .exceptionally(e -> {
                    log.error("Failed to create CLI session", e);
                    reply(msg, "❌ 创建会话失败: " + e.getMessage());
                    return null;
                });
    }

    /**
     * 处理 /status 命令
     */
    private void handleStatus(InboundMessage msg, String[] parts) {
        if (parts.length >= 2) {
            String project = parts[1];
            String workDir = getProjectRegistry().getPath(project);
            if (workDir == null) {
                reply(msg, "❌ 项目不存在: " + project);
                return;
            }

            // 查找该项目的所有会话
            Map<String, CliAgentSession> sessions = agentPool.getAllSessions();
            boolean found = false;
            for (Map.Entry<String, CliAgentSession> entry : sessions.entrySet()) {
                if (entry.getKey().startsWith(project + ":")) {
                    CliAgentSession session = entry.getValue();
                    String[] keyParts = entry.getKey().split(":", 2);
                    String agentType = keyParts.length > 1 ? keyParts[1] : "unknown";

                    StringBuilder sb = new StringBuilder();
                    sb.append("📊 [").append(agentType.toUpperCase()).append("/").append(project).append("]\n");
                    sb.append("路径: ").append(session.getConfig().workDir()).append("\n");
                    sb.append("状态: ").append(session.isAlive() ? "运行中" : "已停止").append("\n");
                    sb.append("会话: ").append(session.currentSessionId() != null ? session.currentSessionId() : "-");

                    reply(msg, sb.toString());
                    found = true;
                }
            }

            if (!found) {
                reply(msg, "📊 项目 " + project + " 无运行中的 Agent");
            }
        } else {
            reply(msg, agentPool.getStatusSummary());
        }
    }

    /**
     * 处理 /stop 命令
     * 用法: /stop <项目> [类型]
     * 示例: /stop p1          停止 p1 的所有 Agent
     *       /stop p1 claude   只停止 p1 的 Claude Agent
     *       /stop p1 opencode  只停止 p1 的 OpenCode Agent
     */
    private void handleStop(InboundMessage msg, String[] parts) {
        if (parts.length < 2) {
            reply(msg, "用法: /stop <项目> [类型]\n示例:\n  /stop p1         停止 p1 的所有 Agent\n  /stop p1 claude  只停止 Claude Agent\n  /stop p1 opencode 只停止 OpenCode Agent");
            return;
        }

        String project = parts[1];
        String agentType = parts.length > 2 ? parts[2].toLowerCase() : null;

        if (agentType != null) {
            // 停止特定类型的 Agent
            String sessionKey = project + ":" + agentType;
            CliAgentSession session = agentPool.getSession(sessionKey);
            if (session != null) {
                session.close()
                        .thenRun(() -> reply(msg, "✅ 已停止 " + project + " 的 " + agentType.toUpperCase() + " Agent"))
                        .exceptionally(e -> {
                            reply(msg, "❌ 停止失败: " + e.getMessage());
                            return null;
                        });
                agentPool.removeSession(sessionKey);
            } else {
                reply(msg, "⚠️ " + project + " 的 " + agentType + " Agent 未运行");
            }
        } else {
            // 停止该项目的所有 Agent
            agentPool.stopAllForProject(project)
                    .thenRun(() -> reply(msg, "✅ 已停止 " + project + " 的所有 Agent"))
                    .exceptionally(e -> {
                        reply(msg, "❌ 停止失败: " + e.getMessage());
                        return null;
                    });
        }
    }

    /**
     * 处理 /stopall 命令 - 停止所有 CLI Agent
     */
    private void handleStopAll(InboundMessage msg) {
        int count = agentPool.getAllSessions().size();
        if (count == 0) {
            reply(msg, "⚠️ 无运行中的 CLI Agent");
            return;
        }

        agentPool.closeAll()
                .thenRun(() -> reply(msg, "✅ 已停止所有 CLI Agent (共 " + count + " 个)"))
                .exceptionally(e -> {
                    reply(msg, "❌ 停止失败: " + e.getMessage());
                    return null;
                });
    }

    /**
     * 处理 /history 命令
     */
    private void handleHistory(InboundMessage msg, String[] parts) {
        if (parts.length < 2) {
            reply(msg, "用法: /cli-history <项目> [数量]");
            return;
        }

        String project = parts[1];
        String workDir = getProjectRegistry().getPath(project);
        if (workDir == null) {
            reply(msg, "❌ 项目不存在: " + project);
            return;
        }

        // TODO: 实现历史读取
        reply(msg, "📜 历史功能开发中...");
    }

    /**
     * 生成项目名称
     */
    private String generateProjectName(String path) {
        Path p = Path.of(path).normalize();
        String name = p.getFileName().toString();
        if (name == null || name.isBlank()) {
            name = "p" + System.currentTimeMillis() % 10000;
        }
        // 转小写，替换特殊字符
        name = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (name.isBlank()) {
            name = "p" + System.currentTimeMillis() % 10000;
        }
        // 检查冲突
        int i = 1;
        String original = name;
        while (getProjectRegistry().exists(name)) {
            name = original + i++;
        }
        return name;
    }

    /**
     * 提取图片
     */
    private List<ImageAttachment> extractImages(InboundMessage msg) {
        // TODO: 从消息中提取图片
        return List.of();
    }

    /**
     * 提取文件
     */
    private List<FileAttachment> extractFiles(InboundMessage msg) {
        // TODO: 从消息中提取文件
        return List.of();
    }

    /**
     * 回复消息
     */
    private void reply(InboundMessage msg, String content) {
        // 优先使用带元数据的回调
        if (sendToChannelWithMeta != null) {
            sendToChannelWithMeta.accept(content, msg.getSessionKey(), null);
        } else if (sendToChannel != null) {
            sendToChannel.accept(content, msg.getSessionKey());
        }
    }

    /**
     * 关闭
     */
    public CompletableFuture<Void> close() {
        return agentPool.closeAll();
    }

    public ProjectRegistry getProjectRegistry() {
        String sessionKey = currentSessionKey.get();
        if (sessionKey != null && !sessionKey.isBlank()) {
            return sessionRegistries.computeIfAbsent(sessionKey, this::createSessionRegistry);
        }
        return defaultRegistry;
    }

    /**
     * 预注册一个 session 的 ProjectRegistry（GUI 用，避免重复创建）
     */
    public void registerSessionRegistry(String sessionKey, ProjectRegistry registry) {
        sessionRegistries.put(sessionKey, registry);
    }

    /**
     * 为指定 sessionKey 创建 ProjectRegistry
     */
    private ProjectRegistry createSessionRegistry(String sessionKey) {
        Path storePath = Helpers.getDataPath()
                .resolve("projects")
                .resolve(sessionKey.replace(":", "_"))
                .resolve("projects.json");
        return createRegistry(storePath);
    }

    /**
     * 创建并初始化 ProjectRegistry（load + auto-bind user.dir）
     */
    private static ProjectRegistry createRegistry(Path storePath) {
        ProjectRegistry registry = new ProjectRegistry(storePath);
        registry.load();
        String cwd = System.getProperty("user.dir");
        if (cwd != null && !cwd.isBlank() && registry.getMainProject() == null) {
            registry.bind("main", cwd, true);
        }
        return registry;
    }

    public CliAgentPool getAgentPool() {
        return agentPool;
    }
}
