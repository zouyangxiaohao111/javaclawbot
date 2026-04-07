package providers.cli.model;

/**
 * 权限模式信息
 *
 * 复刻 cc-connect core/interfaces.go PermissionModeInfo
 */
public record PermissionModeInfo(
    /**
     * 键
     */
    String key,

    /**
     * 名称
     */
    String name,

    /**
     * 中文名称
     */
    String nameZh,

    /**
     * 描述
     */
    String desc,

    /**
     * 中文描述
     */
    String descZh
) {
    public PermissionModeInfo {
        // 允许 null
    }
}
