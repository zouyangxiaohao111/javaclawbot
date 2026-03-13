package providers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Provider 元数据目录
 *
 * 用于 onboard 向导展示：
 * - provider 列表
 * - 默认 api_base
 * - 推荐模型
 * - 是否需要 api_key
 * - 是否允许手动输入模型
 */
public final class ProviderCatalog {

    private ProviderCatalog() {}

    public static final class ProviderMeta {
        private final String name;
        private final String label;
        private final String defaultApiBase;
        private final boolean supportsApiKey;
        private final boolean supportsApiBase;
        private final boolean manualModelOnly;
        private final List<String> recommendedModels;

        public ProviderMeta(
                String name,
                String label,
                String defaultApiBase,
                boolean supportsApiKey,
                boolean supportsApiBase,
                boolean manualModelOnly,
                List<String> recommendedModels
        ) {
            this.name = name;
            this.label = label;
            this.defaultApiBase = defaultApiBase;
            this.supportsApiKey = supportsApiKey;
            this.supportsApiBase = supportsApiBase;
            this.manualModelOnly = manualModelOnly;
            this.recommendedModels = (recommendedModels != null) ? recommendedModels : List.of();
        }

        public String getName() { return name; }
        public String getLabel() { return label; }
        public String getDefaultApiBase() { return defaultApiBase; }
        public boolean isSupportsApiKey() { return supportsApiKey; }
        public boolean isSupportsApiBase() { return supportsApiBase; }
        public boolean isManualModelOnly() { return manualModelOnly; }
        public List<String> getRecommendedModels() { return recommendedModels; }

        @Override
        public String toString() {
            return label + " (" + name + ")";
        }
    }

    public static List<ProviderMeta> supportedProviders() {
        List<ProviderMeta> list = new ArrayList<>();

        list.add(new ProviderMeta(
                "custom", "Custom (OpenAI-compatible)",
                "http://localhost:8000/v1",
                true, true, true,
                List.of()
        ));

        list.add(new ProviderMeta(
                "anthropic", "Anthropic",
                "https://api.anthropic.com",
                true, true, false,
                List.of("claude-sonnet-4-5", "claude-opus-4-5","claude-sonnet-4-6", "claude-opus-4-6")
        ));

        list.add(new ProviderMeta(
                "openai", "OpenAI",
                "https://api.openai.com/v1",
                true, true, false,
                List.of("gpt-5.4", "gpt-5.4-thinking")
        ));

        list.add(new ProviderMeta(
                "openrouter", "OpenRouter",
                "https://openrouter.ai/api/v1",
                true, true, false,
                List.of("openai/gpt-5.4", "anthropic/claude-4.5-sonnet", "anthropic/claude-4.6-sonnet")
        ));

        list.add(new ProviderMeta(
                "deepseek", "DeepSeek",
                "https://api.deepseek.com",
                true, true, false,
                List.of("deepseek-chat", "deepseek-reasoner")
        ));

        list.add(new ProviderMeta(
                "groq", "Groq",
                "https://api.groq.com/openai/v1",
                true, true, false,
                List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768", "gemma2-9b-it")
        ));

        list.add(new ProviderMeta(
                "zhipu", "Zhipu",
                "https://open.bigmodel.cn/api/paas/v4",
                true, true, false,
                List.of("glm-5", "glm-4.7", "glm-4.6")
        ));

        list.add(new ProviderMeta(
                "dashscope", "DashScope (阿里云)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                true, true, false,
                List.of("qwen3-max", "qwen3.5-plus")
        ));

        list.add(new ProviderMeta(
                "vllm", "vLLM / Local OpenAI-compatible",
                "http://localhost:8000/v1",
                false, true, true,
                List.of()
        ));

        list.add(new ProviderMeta(
                "gemini", "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta",
                true, true, false,
                List.of("gemini-2.0-flash-exp", "gemini-1.5-pro", "gemini-1.5-flash", "gemini-1.5-flash-8b")
        ));

        list.add(new ProviderMeta(
                "moonshot", "Moonshot (Kimi)",
                "https://api.moonshot.cn/v1",
                true, true, false,
                List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
        ));

        list.add(new ProviderMeta(
                "minimax", "MiniMax",
                "https://api.minimax.chat/v1",
                true, true, false,
                List.of("abab6.5s-chat", "abab6.5g-chat", "abab5.5-chat")
        ));

        list.add(new ProviderMeta(
                "aihubmix", "AIHubMix",
                "https://api.aihubmix.com/v1",
                true, true, true,
                List.of()
        ));

        list.add(new ProviderMeta(
                "siliconflow", "SiliconFlow",
                "https://api.siliconflow.cn/v1",
                true, true, false,
                List.of("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "meta-llama/Llama-3.3-70B-Instruct", "THUDM/glm-4-9b-chat")
        ));

        list.add(new ProviderMeta(
                "volcengine", "Volcengine Ark",
                "https://ark.cn-beijing.volces.com/api/v3",
                true, true, true,
                List.of()
        ));

        list.sort(Comparator.comparing(x -> x.getLabel().toLowerCase(Locale.ROOT)));
        return list;
    }

    public static ProviderMeta find(String providerName) {
        if (providerName == null || providerName.isBlank()) return null;
        for (ProviderMeta m : supportedProviders()) {
            if (m.getName().equalsIgnoreCase(providerName)) {
                return m;
            }
        }
        return null;
    }
}