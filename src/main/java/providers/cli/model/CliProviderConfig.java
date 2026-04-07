package providers.cli.model;

import java.util.List;
import java.util.Map;

/**
 * Provider 配置
 *
 * 复刻 cc-connect core/interfaces.go ProviderConfig
 */
public record CliProviderConfig(
    /**
     * 名称
     */
    String name,

    /**
     * API Key
     */
    String apiKey,

    /**
     * Base URL
     */
    String baseUrl,

    /**
     * 模型
     */
    String model,

    /**
     * 预配置的可用模型列表
     */
    List<ModelOption> models,

    /**
     * 覆盖 thinking 类型 ("disabled", "enabled", 或 "" 表示不重写)
     */
    String thinking,

    /**
     * 额外环境变量 (如 CLAUDE_CODE_USE_BEDROCK=1)
     */
    Map<String, String> env
) {
    public CliProviderConfig {
        // 允许 null
    }
}
