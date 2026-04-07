package providers.cli.permission;

import java.util.Map;

/**
 * 权限请求
 *
 * @param requestId 请求 ID
 * @param toolName  工具名称
 * @param toolInput 工具输入参数
 */
public record PermissionRequest(
        String requestId,
        String toolName,
        Map<String, Object> toolInput
) {
}
