package providers.cli.model;

import java.util.Map;

/**
 * 权限结果
 */
public record PermissionResult(
    String behavior,
    Map<String, Object> updatedInput,
    String message
) {
    public static PermissionResult allow() {
        return new PermissionResult("allow", null, null);
    }

    public static PermissionResult allow(Map<String, Object> updatedInput) {
        return new PermissionResult("allow", updatedInput, null);
    }

    public static PermissionResult deny() {
        return new PermissionResult("deny", null, "The user denied this tool use. Stop and wait for the user's instructions.");
    }

    public static PermissionResult deny(String message) {
        return new PermissionResult("deny", null,
            message != null ? message : "The user denied this tool use. Stop and wait for the user's instructions.");
    }
}
