package providers.cli.model;

/**
 * 模型选项
 *
 * 复刻 cc-connect core/interfaces.go ModelOption
 */
public record ModelOption(
    /**
     * 模型标识符
     */
    String name,

    /**
     * 简短描述 (display_name)
     */
    String desc,

    /**
     * 可选的短别名 (如 "codex" -> "gpt-5.3-codex")
     */
    String alias
) {
    public ModelOption {
        // 允许 null
    }

    public ModelOption(String name) {
        this(name, null, null);
    }

    public ModelOption(String name, String desc) {
        this(name, desc, null);
    }
}
