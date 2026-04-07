package providers.cli;

import lombok.extern.slf4j.Slf4j;
import providers.cli.model.PermissionResult;
import providers.cli.CliAgentSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * CLI Agent 输出处理器
 *
 * 处理事件输出和格式化
 */
@Slf4j
public class CliAgentOutputHandler {

    // 发送消息到群聊的回调：(格式化消息, 项目名)
    private BiConsumer<String, String> sendToChatCallback;

    // 等待用户权限响应的会话
    private final Map<String, PendingPermission> pendingPermissions = new ConcurrentHashMap<>();

    public CliAgentOutputHandler() {
    }

    /**
     * 设置发送消息回调
     */
    public void setSendToChatCallback(BiConsumer<String, String> callback) {
        this.sendToChatCallback = callback;
    }

    /**
     * 处理事件
     */
    public void handleEvent(String project, String agentType, CliEvent event) {
        String prefix = formatPrefix(agentType, project);

        if (event == null || event.type() == null) return;

        switch (event.type()) {
            case TEXT -> {
                // 文本内容直接输出
                if (event.content() != null && !event.content().isBlank()) {
                    sendToChat(prefix, event.content(), project);
                }
            }

            case THINKING -> {
                // thinking 可以折叠或者简化输出
                if (event.content() != null && !event.content().isBlank()) {
                    String truncated = truncate(event.content(), 200);
                    sendToChat(prefix, "💭 " + truncated + (event.content().length() > 200 ? "..." : ""), project);
                }
            }

            case TOOL_USE -> {
                // 工具调用
                String toolInfo = formatToolUse(event);
                sendToChat(prefix, "▶ " + toolInfo, project);
            }

            case TOOL_RESULT -> {
                // 工具结果
                String resultInfo = formatToolResult(event);
                sendToChat(prefix, "  " + resultInfo, project);
            }

            case RESULT -> {
                // 最终结果（文本内容已通过 TEXT 事件流式输出，这里只显示摘要）
                sendToChat(prefix, "✅ 完成 (tokens: " + event.inputTokens() + "/" + event.outputTokens() + ")", project);
            }

            case ERROR -> {
                // 错误
                String errorMsg = event.error() != null ? event.error().getMessage() : "Unknown error";
                sendToChat(prefix, "❌ " + errorMsg, project);
            }

            case SESSION_ID -> {
                // 会话 ID，记录日志即可
                log.debug("[{}/{}] Session ID: {}", agentType, project, event.sessionId());
            }

            case PERMISSION_REQUEST -> {
                // 由 PermissionEngine 处理，不应该到达这里
                log.warn("Permission request should be handled by PermissionEngine: {}", event);
            }
        }
    }

    /**
     * 通知自动拒绝
     */
    public void notifyAutoDeny(String project, String agentType, String toolName, String reason) {
        String prefix = formatPrefix(agentType, project);
        sendToChat(prefix, "⚠️ 已自动拒绝: " + toolName +
                (reason != null ? " (" + reason + ")" : ""), project);
    }

    /**
     * 询问用户权限
     */
    public void askUserPermission(String project, String agentType,
                                   CliAgentSession session, CliEvent event) {
        String prefix = formatPrefix(agentType, project);
        String key = project + ":" + event.requestId();

        // 构建询问消息
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" ❓ 是否允许执行?\n");
        sb.append("工具: ").append(event.toolName()).append("\n");

        if (event.toolInput() != null && !event.toolInput().isBlank()) {
            sb.append("输入: ").append(truncate(event.toolInput(), 200)).append("\n");
        }

        sb.append("\n回复 `").append(key).append(" y` 允许");
        sb.append(" 或 `").append(key).append(" n` 拒绝");

        sendToChat(prefix, sb.toString(), project);

        // 记录待处理权限
        pendingPermissions.put(key, new PendingPermission(project, agentType, session, event));
    }

    /**
     * 处理用户权限响应
     *
     * @return true 如果匹配到待处理的权限请求
     */
    public boolean handleUserPermissionResponse(String key, boolean allow) {
        PendingPermission pending = pendingPermissions.remove(key);
        if (pending == null) {
            return false;
        }

        PermissionResult result = allow ?
                PermissionResult.allow() :
                PermissionResult.deny("User denied");

        pending.session.respondPermission(pending.event.requestId(), result);

        String prefix = formatPrefix(pending.agentType, pending.project);
        sendToChat(prefix, allow ? "✅ 已允许" : "❌ 已拒绝", pending.project);

        return true;
    }

    /**
     * 检查是否有待处理的权限请求
     */
    public boolean hasPendingPermission(String key) {
        return pendingPermissions.containsKey(key);
    }

    /**
     * 格式化前缀
     */
    private String formatPrefix(String agentType, String project) {
        return switch (agentType.toLowerCase()) {
            case "claude", "claudecode", "cc" -> "[CC/" + project + "]";
            case "opencode", "oc" -> "[OpenCode/" + project + "]";
            default -> "[" + agentType + "/" + project + "]";
        };
    }

    /**
     * 格式化工具调用
     */
    private String formatToolUse(CliEvent event) {
        String toolName = event.toolName();
        String input = event.toolInput();

        if (input == null || input.isBlank()) {
            return toolName;
        }

        return toolName + " " + truncate(input, 100);
    }

    /**
     * 格式化工具结果
     */
    private String formatToolResult(CliEvent event) {
        String status = event.toolSuccess() != null && event.toolSuccess() ? "✓" : "✗";
        String result = event.toolResult();

        if (result == null || result.isBlank()) {
            return status;
        }

        return status + " " + truncate(result, 200);
    }

    /**
     * 发送消息到群聊
     *
     * @param prefix  前缀，如 [CC/p1]
     * @param message 消息内容
     * @param project 项目名，用于路由到正确的渠道
     */
    private void sendToChat(String prefix, String message, String project) {
        if (sendToChatCallback != null) {
            String formatted = prefix + " " + message;
            sendToChatCallback.accept(formatted, project);
        } else {
            log.info("{} {}", prefix, message);
        }
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 待处理权限
     */
    private record PendingPermission(
            String project,
            String agentType,
            CliAgentSession session,
            CliEvent event
    ) {}
}
