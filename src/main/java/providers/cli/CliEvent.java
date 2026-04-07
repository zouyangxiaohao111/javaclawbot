package providers.cli;

import providers.cli.model.UserQuestion;

import java.util.List;
import java.util.Map;

/**
 * CLI Agent 事件
 */
public record CliEvent(
    /**
     * 事件类型
     */
    CliEventType type,

    /**
     * 文本内容 (TEXT/THINKING/RESULT)
     */
    String content,

    /**
     * 工具名称 (TOOL_USE/TOOL_RESULT/PERMISSION_REQUEST)
     */
    String toolName,

    /**
     * 工具输入摘要 (人类可读)
     */
    String toolInput,

    /**
     * 工具输入原始数据 (用于权限响应)
     */
    Map<String, Object> toolInputRaw,

    /**
     * 工具结果 (TOOL_RESULT)
     */
    String toolResult,

    /**
     * 工具状态 (completed/failed)
     */
    String toolStatus,

    /**
     * 工具退出码
     */
    Integer toolExitCode,

    /**
     * 工具是否成功
     */
    Boolean toolSuccess,

    /**
     * 会话 ID (SESSION_ID/RESULT)
     */
    String sessionId,

    /**
     * 请求 ID (PERMISSION_REQUEST)
     */
    String requestId,

    /**
     * 用户问题列表 (AskUserQuestion)
     */
    List<UserQuestion> questions,

    /**
     * 是否完成
     */
    boolean done,

    /**
     * 输入 token 数
     */
    int inputTokens,

    /**
     * 输出 token 数
     */
    int outputTokens,

    /**
     * 错误
     */
    Throwable error
) {
    /**
     * 创建文本事件
     */
    public static CliEvent text(String content) {
        return new CliEvent(CliEventType.TEXT, content, null, null, null, null, null, null, null,
                null, null, null, false, 0, 0, null);
    }

    /**
     * 创建思考事件
     */
    public static CliEvent thinking(String content) {
        return new CliEvent(CliEventType.THINKING, content, null, null, null, null, null, null, null,
                null, null, null, false, 0, 0, null);
    }

    /**
     * 创建工具调用事件
     */
    public static CliEvent toolUse(String toolName, String toolInput, Map<String, Object> toolInputRaw) {
        return new CliEvent(CliEventType.TOOL_USE, null, toolName, toolInput, toolInputRaw, null, null, null, null,
                null, null, null, false, 0, 0, null);
    }

    /**
     * 创建工具结果事件
     */
    public static CliEvent toolResult(String toolName, String toolResult, String toolStatus,
                                       Integer toolExitCode, Boolean toolSuccess) {
        return new CliEvent(CliEventType.TOOL_RESULT, null, toolName, null, null, toolResult, toolStatus,
                toolExitCode, toolSuccess, null, null, null, false, 0, 0, null);
    }

    /**
     * 创建权限请求事件
     */
    public static CliEvent permissionRequest(String requestId, String toolName, String toolInput,
                                              Map<String, Object> toolInputRaw, List<UserQuestion> questions) {
        return new CliEvent(CliEventType.PERMISSION_REQUEST, null, toolName, toolInput, toolInputRaw, null, null, null, null,
                null, requestId, questions, false, 0, 0, null);
    }

    /**
     * 创建结果事件
     */
    public static CliEvent result(String content, String sessionId, int inputTokens, int outputTokens) {
        return new CliEvent(CliEventType.RESULT, content, null, null, null, null, null, null, null,
                sessionId, null, null, true, inputTokens, outputTokens, null);
    }

    /**
     * 创建会话 ID 事件
     */
    public static CliEvent sessionId(String sessionId) {
        return new CliEvent(CliEventType.SESSION_ID, null, null, null, null, null, null, null, null,
                sessionId, null, null, false, 0, 0, null);
    }

    /**
     * 创建错误事件
     */
    public static CliEvent error(Throwable error) {
        return new CliEvent(CliEventType.ERROR, null, null, null, null, null, null, null, null,
                null, null, null, false, 0, 0, error);
    }

    /**
     * 创建错误事件 (带消息)
     */
    public static CliEvent error(String message) {
        return new CliEvent(CliEventType.ERROR, message, null, null, null, null, null, null, null,
                null, null, null, false, 0, 0, new RuntimeException(message));
    }
}
