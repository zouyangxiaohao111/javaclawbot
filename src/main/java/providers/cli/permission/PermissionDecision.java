package providers.cli.permission;

import java.util.Map;

/**
 * 权限决策
 *
 * @param behavior     行为: "allow", "deny", "ask_user"
 * @param message      消息 (拒绝原因或询问说明)
 * @param updatedInput 更新后的输入 (允许时可选回传)
 */
public record PermissionDecision(
        String behavior,
        String message,
        Map<String, Object> updatedInput
) {
    /**
     * 创建允许决策
     */
    public static PermissionDecision allow() {
        return new PermissionDecision("allow", null, null);
    }

    /**
     * 创建允许决策 (带更新输入)
     */
    public static PermissionDecision allow(Map<String, Object> updatedInput) {
        return new PermissionDecision("allow", null, updatedInput);
    }

    /**
     * 创建拒绝决策
     */
    public static PermissionDecision deny() {
        return new PermissionDecision("deny", "Permission denied", null);
    }

    /**
     * 创建拒绝决策 (带消息)
     */
    public static PermissionDecision deny(String message) {
        return new PermissionDecision("deny",
                message != null ? message : "Permission denied", null);
    }

    /**
     * 创建询问用户决策
     */
    public static PermissionDecision askUser() {
        return new PermissionDecision("ask_user", "Please confirm this action", null);
    }

    /**
     * 创建询问用户决策 (带消息)
     */
    public static PermissionDecision askUser(String message) {
        return new PermissionDecision("ask_user",
                message != null ? message : "Please confirm this action", null);
    }

    /**
     * 是否允许
     */
    public boolean isAllowed() {
        return "allow".equals(behavior);
    }

    /**
     * 是否拒绝
     */
    public boolean isDenied() {
        return "deny".equals(behavior);
    }

    /**
     * 是否需要询问用户
     */
    public boolean needsUserConfirmation() {
        return "ask_user".equals(behavior);
    }
}
