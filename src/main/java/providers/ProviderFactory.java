package providers;

import config.ConfigSchema;

import java.util.Objects;

/**
 * Provider 工厂
 *
 * 职责：
 * - 根据 provider 名称和模型创建 LLMProvider 实例
 * - 解析 provider 配置
 *
 * 注意：fallback 链构建已移至 ModelFallbackManager
 */
public final class ProviderFactory {

    public ProviderFactory() {}

    /**
     * 创建 LLMProvider 实例
     *
     * @param config       配置对象
     * @param providerName provider 名称（可为 null 或 "auto"）
     * @param model        模型名称
     * @return LLMProvider 实例
     */
    public static LLMProvider createProvider(ConfigSchema.Config config, String providerName, String model) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(model, "model");

        // 获取 provider 配置
        ConfigSchema.ProviderConfig providerConfig = resolveProviderConfig(config, providerName, model);
        String apiKey = providerConfig != null ? providerConfig.getApiKey() : null;
        String apiBase = providerConfig != null ? providerConfig.getApiBase() : null;

        // 解析实际的 provider 名称
        String resolvedName = resolveProviderName(config, providerName, model);

        return createProviderWithConfig(resolvedName, apiKey, apiBase, model);
    }

    /**
     * 使用显式配置创建 LLMProvider 实例
     *
     * @param providerName provider 名称
     * @param apiKey       API Key（可为 null）
     * @param apiBase      API Base URL（可为 null）
     * @param model        模型名称
     * @return LLMProvider 实例
     */
    public static LLMProvider createProviderWithConfig(String providerName, String apiKey, String apiBase, String model) {
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(model, "model");

        // custom：强制走 OpenAI-compatible 直连
        if ("custom".equals(providerName)) {
            if (apiBase == null || apiBase.isBlank()) apiBase = "http://localhost:8000/v1";
            if (apiKey == null || apiKey.isBlank()) apiKey = "no-key";
            return new CustomProvider(apiKey, apiBase, model);
        }

        // azure_openai：Azure OpenAI 直连
        if ("azure_openai".equals(providerName) || "azure".equals(providerName)) {
            if (apiBase == null || apiBase.isBlank()) {
                throw new IllegalStateException("Azure OpenAI requires api_base (e.g. https://your-resource.openai.azure.com/)");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Azure OpenAI requires api_key");
            }
            return new AzureOpenAIProvider(apiKey, apiBase, model);
        }

        // 其他 provider：使用 CustomProvider（OpenAI 兼容接口）
        // 从 ProviderRegistry 获取默认 apiBase
        ProviderRegistry.ProviderSpec spec = ProviderRegistry.findByName(providerName);
        if (spec != null && spec.getDefaultApiBase() != null && !spec.getDefaultApiBase().isBlank()) {
            if (apiBase == null || apiBase.isBlank()) {
                apiBase = spec.getDefaultApiBase();
            }
        }

        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.openai.com/v1";
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "no-key";
        }

        return new CustomProvider(apiKey, apiBase, model);
    }

    /**
     * 解析 provider 配置
     */
    public static ConfigSchema.ProviderConfig resolveProviderConfig(ConfigSchema.Config config, String providerName, String model) {
        if (providerName != null && !"auto".equals(providerName)) {
            // 显式指定 provider
            ConfigSchema.ProviderConfig pc = config.getProviders().getByName(providerName);
            if (pc != null) {
                return pc;
            }
        }

        // 根据 model 自动匹配
        return config.getProvider(model);
    }

    /**
     * 解析 provider 名称
     */
    public static String resolveProviderName(ConfigSchema.Config config, String providerName, String model) {
        if (providerName != null && !"auto".equals(providerName)) {
            return providerName;
        }

        // 根据 model 自动匹配
        String name = config.getProviderName(model);
        return name != null ? name : "custom";
    }
}