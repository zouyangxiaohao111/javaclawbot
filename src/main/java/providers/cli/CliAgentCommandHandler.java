package providers.cli;

import bus.InboundMessage;
import bus.OutboundMessage;
import lombok.extern.slf4j.Slf4j;
import providers.cli.model.FileAttachment;
import providers.cli.model.ImageAttachment;
import providers.cli.permission.PermissionEngine;

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
 * - /bind p1=/path/to/project
 * - /unbind p1
 * - /projects
 * - /cc <project> <prompt>
 * - /opencode <project> <prompt>
 * - /status [project]
 * - /stop <project>
 * - /history <project> [limit]
 */
@Slf4j
public class CliAgentCommandHandler {

    private final ProjectRegistry projectRegistry;
    private final CliAgentPool agentPool;
    private final CliAgentOutputHandler outputHandler;
    private final ExecutorService executor;

    // 发送消息到渠道的回调
    private BiConsumer<String, String> sendToChannel;

    // 项目 -> sessionKey 映射（用于路由回复到正确的渠道）
    private final Map<String, String> projectSessionKeys = new java.util.concurrent.ConcurrentHashMap<>();

    public CliAgentCommandHandler(Path workspacePath) {
        Path registryPath = workspacePath.resolve("cli-projects.json");
        this.projectRegistry = new ProjectRegistry(registryPath);
        this.projectRegistry.load();

        this.outputHandler = new CliAgentOutputHandler();
        PermissionEngine permissionEngine = PermissionEngine.createDefault();

        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cli-agent-handler");
            t.setDaemon(true);
            return t;
        });

        this.agentPool = new CliAgentPool(
                projectRegistry,
                permissionEngine,
                outputHandler
        );

        // 设置输出回调：根据 project 查找对应的 sessionKey 路由回复
        outputHandler.setSendToChatCallback((formattedMessage, project) -> {
            if (sendToChannel != null) {
                String sessionKey = project != null ? projectSessionKeys.get(project) : null;
                sendToChannel.accept(formattedMessage, sessionKey);
            }
        });
    }

    /**
     * 设置发送消息回调
     */
    public void setSendToChannel(BiConsumer<String, String> callback) {
        this.sendToChannel = callback;
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
                case "/status" -> {
                    handleStatus(msg, parts);
                    return true;
                }
                case "/stop" -> {
                    handleStop(msg, parts);
                    return true;
                }
                case "/history" -> {
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
     */
    private void handleBind(InboundMessage msg, String[] parts) {
        if (parts.length < 2) {
            reply(msg, "用法: /bind <名称>=<路径>\n示例: /bind p1=/home/user/project");
            return;
        }

        String arg = parts[1];
        int eqIdx = arg.indexOf('=');
        if (eqIdx < 0) {
            // 只有路径，使用默认名称
            String path = arg;
            String name = generateProjectName(path);
            if (projectRegistry.bind(name, path)) {
                reply(msg, "✅ 项目已绑定: " + name + " → " + path);
            } else {
                reply(msg, "❌ 绑定失败");
            }
        } else {
            String name = arg.substring(0, eqIdx).trim();
            String path = arg.substring(eqIdx + 1).trim();
            if (projectRegistry.bind(name, path)) {
                reply(msg, "✅ 项目已绑定: " + name + " → " + path);
            } else {
                reply(msg, "❌ 绑定失败");
            }
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
        if (projectRegistry.unbind(name)) {
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
        reply(msg, projectRegistry.formatList());
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
        String workDir = projectRegistry.getPath(project);
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
            String workDir = projectRegistry.getPath(project);
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
     */
    private void handleStop(InboundMessage msg, String[] parts) {
        if (parts.length < 2) {
            reply(msg, "用法: /stop <项目>");
            return;
        }

        String project = parts[1];
        agentPool.stopAllForProject(project)
                .thenRun(() -> reply(msg, "✅ 已停止 " + project + " 的所有 Agent"))
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
            reply(msg, "用法: /history <项目> [数量]");
            return;
        }

        String project = parts[1];
        String workDir = projectRegistry.getPath(project);
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
        while (projectRegistry.exists(name)) {
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
        if (sendToChannel != null) {
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
        return projectRegistry;
    }

    public CliAgentPool getAgentPool() {
        return agentPool;
    }
}
