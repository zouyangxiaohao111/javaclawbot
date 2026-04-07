package providers.cli;

/**
 * CLI Agent 事件类型枚举
 *
 * 复刻 cc-connect core/message.go EventType
 */
public enum CliEventType {
    /**
     * 文本内容
     */
    TEXT("text"),

    /**
     * 思考过程 (thinking)
     */
    THINKING("thinking"),

    /**
     * 工具调用
     */
    TOOL_USE("tool_use"),

    /**
     * 工具结果
     */
    TOOL_RESULT("tool_result"),

    /**
     * 最终结果
     */
    RESULT("result"),

    /**
     * 错误
     */
    ERROR("error"),

    /**
     * 权限请求
     */
    PERMISSION_REQUEST("permission_request"),

    /**
     * 会话 ID
     */
    SESSION_ID("session_id");

    private final String value;

    CliEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CliEventType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (CliEventType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
