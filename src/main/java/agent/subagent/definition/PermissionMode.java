package agent.subagent.definition;

/**
 * 权限模式枚举
 */
public enum PermissionMode {
    /** 绕过权限检查 */
    BYPASS_PERMISSIONS("bypassPermissions"),

    /** 接受编辑模式 */
    ACCEPT_EDITS("acceptEdits"),

    /** 需要计划审批 */
    PLAN("plan"),

    /** 权限提示冒泡到父终端 */
    BUBBLE("bubble");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static PermissionMode fromValue(String value) {
        for (PermissionMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        return BYPASS_PERMISSIONS;
    }
}
