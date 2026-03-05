package providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;


/**
 * 提供者注册表：LLM 提供者元数据的唯一来源
 *
 * 作用：
 * - 定义所有提供者的识别信息、关键字、默认网关地址等
 * - 提供按模型名匹配、按网关特征匹配、按名称查找等能力
 *
 * 顺序很重要：决定匹配优先级与兜底顺序（网关在前）
 */
public final class ProviderRegistry {

    private ProviderRegistry() {}

    /**
     * 额外环境变量键值对
     * - value 支持占位符：{api_key}、{api_base}
     */
    public record EnvKV(String key, String value) {}

    /**
     * 按模型覆盖参数（保留结构；具体参数内容用 Map 表示）
     */
    public record ModelOverride(String modelName, Map<String, Object> params) {}

    /**
     * 单个提供者的元数据
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ProviderSpec {

        // 身份信息
        private final String name;                 // 配置字段名，如 "dashscope"
        private final List<String> keywords;       // 模型关键字（小写）
        private final String envKey;               // 环境变量名（如 "DASHSCOPE_API_KEY"）
        private final String displayName;          // 展示名

        // 模型前缀
        private final String litellmPrefix;        // 前缀，如 "dashscope" => "dashscope/{model}"
        private final List<String> skipPrefixes;   // 如果模型已包含这些前缀，则不重复添加

        // 额外环境变量
        private final List<EnvKV> envExtras;

        // 网关 / 本地检测
        private final boolean gateway;             // 是否网关（可路由任意模型）
        private final boolean local;               // 是否本地部署
        private final String detectByKeyPrefix;    // 通过 api_key 前缀识别
        private final String detectByBaseKeyword;  // 通过 api_base 包含关键字识别
        private final String defaultApiBase;       // 默认 api_base

        // 网关行为
        private final boolean stripModelPrefix;    // 是否剥离 "provider/" 再重新加前缀

        // 按模型覆盖参数
        private final List<ModelOverride> modelOverrides;

        // OAuth / 直连
        private final boolean oauth;               // OAuth 提供者（不使用 api_key）
        private final boolean direct;              // 直连提供者（绕过 LiteLLM）

        // 是否支持提示缓存
        private final boolean supportsPromptCaching;

        private ProviderSpec(Builder b) {
            this.name = b.name;
            this.keywords = List.copyOf(b.keywords);
            this.envKey = b.envKey;
            this.displayName = b.displayName;

            this.litellmPrefix = b.litellmPrefix;
            this.skipPrefixes = List.copyOf(b.skipPrefixes);
            this.envExtras = List.copyOf(b.envExtras);

            this.gateway = b.gateway;
            this.local = b.local;
            this.detectByKeyPrefix = b.detectByKeyPrefix;
            this.detectByBaseKeyword = b.detectByBaseKeyword;
            this.defaultApiBase = b.defaultApiBase;

            this.stripModelPrefix = b.stripModelPrefix;

            this.modelOverrides = List.copyOf(b.modelOverrides);

            this.oauth = b.oauth;
            this.direct = b.direct;

            this.supportsPromptCaching = b.supportsPromptCaching;
        }

        public String getName() { return name; }
        public List<String> getKeywords() { return keywords; }
        public String getEnvKey() { return envKey; }
        public String getDisplayName() { return displayName; }

        public String getLitellmPrefix() { return litellmPrefix; }
        public List<String> getSkipPrefixes() { return skipPrefixes; }
        public List<EnvKV> getEnvExtras() { return envExtras; }

        public boolean isGateway() { return gateway; }
        public boolean isLocal() { return local; }
        public String getDetectByKeyPrefix() { return detectByKeyPrefix; }
        public String getDetectByBaseKeyword() { return detectByBaseKeyword; }
        public String getDefaultApiBase() { return defaultApiBase; }

        public boolean isStripModelPrefix() { return stripModelPrefix; }

        public List<ModelOverride> getModelOverrides() { return modelOverrides; }

        public boolean isOauth() { return oauth; }
        public boolean isDirect() { return direct; }

        public boolean isSupportsPromptCaching() { return supportsPromptCaching; }

        /** 展示标签：优先 displayName，否则 name 首字母大写 */
        public String getLabel() {
            if (displayName != null && !displayName.isBlank()) return displayName;
            return title(name);
        }

        private static String title(String s) {
            if (s == null || s.isEmpty()) return s;
            String lower = s.toLowerCase(Locale.ROOT);
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }

        public static Builder builder(String name) {
            return new Builder(name);
        }

        public static final class Builder {
            private final String name;
            private List<String> keywords = new ArrayList<>();
            private String envKey = "";
            private String displayName = "";

            private String litellmPrefix = "";
            private List<String> skipPrefixes = new ArrayList<>();
            private List<EnvKV> envExtras = new ArrayList<>();

            private boolean gateway = false;
            private boolean local = false;
            private String detectByKeyPrefix = "";
            private String detectByBaseKeyword = "";
            private String defaultApiBase = "";

            private boolean stripModelPrefix = false;

            private List<ModelOverride> modelOverrides = new ArrayList<>();

            private boolean oauth = false;
            private boolean direct = false;

            private boolean supportsPromptCaching = false;

            private Builder(String name) {
                this.name = Objects.requireNonNull(name, "name 不能为空");
            }

            public Builder keywords(String... kws) {
                this.keywords = (kws == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(kws));
                return this;
            }

            public Builder envKey(String envKey) {
                this.envKey = (envKey != null) ? envKey : "";
                return this;
            }

            public Builder displayName(String displayName) {
                this.displayName = (displayName != null) ? displayName : "";
                return this;
            }

            public Builder litellmPrefix(String litellmPrefix) {
                this.litellmPrefix = (litellmPrefix != null) ? litellmPrefix : "";
                return this;
            }

            public Builder skipPrefixes(String... prefixes) {
                this.skipPrefixes = (prefixes == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(prefixes));
                return this;
            }

            public Builder envExtras(EnvKV... kvs) {
                this.envExtras = (kvs == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(kvs));
                return this;
            }

            public Builder gateway(boolean gateway) {
                this.gateway = gateway;
                return this;
            }

            public Builder local(boolean local) {
                this.local = local;
                return this;
            }

            public Builder detectByKeyPrefix(String prefix) {
                this.detectByKeyPrefix = (prefix != null) ? prefix : "";
                return this;
            }

            public Builder detectByBaseKeyword(String kw) {
                this.detectByBaseKeyword = (kw != null) ? kw : "";
                return this;
            }

            public Builder defaultApiBase(String base) {
                this.defaultApiBase = (base != null) ? base : "";
                return this;
            }

            public Builder stripModelPrefix(boolean strip) {
                this.stripModelPrefix = strip;
                return this;
            }

            public Builder modelOverrides(ModelOverride... overrides) {
                this.modelOverrides = (overrides == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(overrides));
                return this;
            }

            public Builder oauth(boolean oauth) {
                this.oauth = oauth;
                return this;
            }

            public Builder direct(boolean direct) {
                this.direct = direct;
                return this;
            }

            public Builder supportsPromptCaching(boolean supports) {
                this.supportsPromptCaching = supports;
                return this;
            }

            public ProviderSpec build() {
                return new ProviderSpec(this);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // PROVIDERS：注册表（顺序 = 优先级）
    // ---------------------------------------------------------------------------

    public static final List<ProviderSpec> PROVIDERS = List.of(

            // Custom（直连 OpenAI 兼容端点，绕过 LiteLLM）
            ProviderSpec.builder("custom")
                    .keywords()
                    .envKey("")
                    .displayName("Custom")
                    .litellmPrefix("")
                    .direct(true)
                    .build(),

            // Gateways（通过 api_key / api_base 识别，不靠模型名）

            // OpenRouter
            ProviderSpec.builder("openrouter")
                    .keywords("openrouter")
                    .envKey("OPENROUTER_API_KEY")
                    .displayName("OpenRouter")
                    .litellmPrefix("openrouter")
                    .gateway(true)
                    .detectByKeyPrefix("sk-or-")
                    .detectByBaseKeyword("openrouter")
                    .defaultApiBase("https://openrouter.ai/api/v1")
                    .supportsPromptCaching(true)
                    .build(),

            // AiHubMix
            ProviderSpec.builder("aihubmix")
                    .keywords("aihubmix")
                    .envKey("OPENAI_API_KEY")
                    .displayName("AiHubMix")
                    .litellmPrefix("openai")
                    .gateway(true)
                    .detectByBaseKeyword("aihubmix")
                    .defaultApiBase("https://aihubmix.com/v1")
                    .stripModelPrefix(true)
                    .build(),

            // SiliconFlow（硅基流动）
            ProviderSpec.builder("siliconflow")
                    .keywords("siliconflow")
                    .envKey("OPENAI_API_KEY")
                    .displayName("SiliconFlow")
                    .litellmPrefix("openai")
                    .gateway(true)
                    .detectByBaseKeyword("siliconflow")
                    .defaultApiBase("https://api.siliconflow.cn/v1")
                    .build(),

            // VolcEngine（火山引擎）
            ProviderSpec.builder("volcengine")
                    .keywords("volcengine", "volces", "ark")
                    .envKey("OPENAI_API_KEY")
                    .displayName("VolcEngine")
                    .litellmPrefix("volcengine")
                    .gateway(true)
                    .detectByBaseKeyword("volces")
                    .defaultApiBase("https://ark.cn-beijing.volces.com/api/v3")
                    .build(),

            // 标准提供者（按模型名关键字匹配）

            // Anthropic
            ProviderSpec.builder("anthropic")
                    .keywords("anthropic", "claude")
                    .envKey("ANTHROPIC_API_KEY")
                    .displayName("Anthropic")
                    .supportsPromptCaching(true)
                    .build(),

            // OpenAI
            ProviderSpec.builder("openai")
                    .keywords("openai", "gpt")
                    .envKey("OPENAI_API_KEY")
                    .displayName("OpenAI")
                    .build(),

            // OpenAI Codex（OAuth）
            ProviderSpec.builder("openai_codex")
                    .keywords("openai-codex", "codex")
                    .envKey("")
                    .displayName("OpenAI Codex")
                    .detectByBaseKeyword("codex")
                    .defaultApiBase("https://chatgpt.com/backend-api")
                    .oauth(true)
                    .build(),

            // Github Copilot（OAuth）
            ProviderSpec.builder("github_copilot")
                    .keywords("github_copilot", "copilot")
                    .envKey("")
                    .displayName("Github Copilot")
                    .litellmPrefix("github_copilot")
                    .skipPrefixes("github_copilot/")
                    .oauth(true)
                    .build(),

            // DeepSeek
            ProviderSpec.builder("deepseek")
                    .keywords("deepseek")
                    .envKey("DEEPSEEK_API_KEY")
                    .displayName("DeepSeek")
                    .litellmPrefix("deepseek")
                    .skipPrefixes("deepseek/")
                    .build(),

            // Gemini
            ProviderSpec.builder("gemini")
                    .keywords("gemini")
                    .envKey("GEMINI_API_KEY")
                    .displayName("Gemini")
                    .litellmPrefix("gemini")
                    .skipPrefixes("gemini/")
                    .build(),

            // Zhipu
            ProviderSpec.builder("zhipu")
                    .keywords("zhipu", "glm", "zai")
                    .envKey("ZAI_API_KEY")
                    .displayName("Zhipu AI")
                    .litellmPrefix("zai")
                    .skipPrefixes("zhipu/", "zai/", "openrouter/", "hosted_vllm/")
                    .envExtras(new EnvKV("ZHIPUAI_API_KEY", "{api_key}"))
                    .build(),

            // DashScope
            ProviderSpec.builder("dashscope")
                    .keywords("qwen", "dashscope")
                    .envKey("DASHSCOPE_API_KEY")
                    .displayName("DashScope")
                    .litellmPrefix("dashscope")
                    .skipPrefixes("dashscope/", "openrouter/")
                    .build(),

            // Moonshot
            ProviderSpec.builder("moonshot")
                    .keywords("moonshot", "kimi")
                    .envKey("MOONSHOT_API_KEY")
                    .displayName("Moonshot")
                    .litellmPrefix("moonshot")
                    .skipPrefixes("moonshot/", "openrouter/")
                    .envExtras(new EnvKV("MOONSHOT_API_BASE", "{api_base}"))
                    .defaultApiBase("https://api.moonshot.ai/v1")
                    .modelOverrides(new ModelOverride("kimi-k2.5", Map.of("temperature", 1.0)))
                    .build(),

            // MiniMax
            ProviderSpec.builder("minimax")
                    .keywords("minimax")
                    .envKey("MINIMAX_API_KEY")
                    .displayName("MiniMax")
                    .litellmPrefix("minimax")
                    .skipPrefixes("minimax/", "openrouter/")
                    .defaultApiBase("https://api.minimax.io/v1")
                    .build(),

            // vLLM（本地部署）
            ProviderSpec.builder("vllm")
                    .keywords("vllm")
                    .envKey("HOSTED_VLLM_API_KEY")
                    .displayName("vLLM/Local")
                    .litellmPrefix("hosted_vllm")
                    .local(true)
                    .build(),

            // Groq（放最后）
            ProviderSpec.builder("groq")
                    .keywords("groq")
                    .envKey("GROQ_API_KEY")
                    .displayName("Groq")
                    .litellmPrefix("groq")
                    .skipPrefixes("groq/")
                    .build()
    );

    // ---------------------------------------------------------------------------
    // 查找工具
    // ---------------------------------------------------------------------------

    /**
     * 按模型名关键字匹配标准提供者（不含网关/本地）
     */
    public static ProviderSpec findByModel(String model) {
        if (model == null) return null;

        String modelLower = model.toLowerCase(Locale.ROOT);
        String modelNormalized = modelLower.replace("-", "_");
        String modelPrefix = "";
        int idx = modelLower.indexOf('/');
        if (idx >= 0) modelPrefix = modelLower.substring(0, idx);
        String normalizedPrefix = modelPrefix.replace("-", "_");

        List<ProviderSpec> stdSpecs = new ArrayList<>();
        for (ProviderSpec s : PROVIDERS) {
            if (!s.isGateway() && !s.isLocal()) stdSpecs.add(s);
        }

        // 优先显式前缀
        for (ProviderSpec spec : stdSpecs) {
            if (!modelPrefix.isBlank() && normalizedPrefix.equals(spec.getName())) {
                return spec;
            }
        }

        // 按关键字匹配
        for (ProviderSpec spec : stdSpecs) {
            for (String kw : spec.getKeywords()) {
                if (kw == null || kw.isBlank()) continue;
                String k = kw.toLowerCase(Locale.ROOT);
                if (modelLower.contains(k) || modelNormalized.contains(k.replace("-", "_"))) {
                    return spec;
                }
            }
        }

        return null;
    }

    /**
     * 识别网关/本地提供者
     */
    public static ProviderSpec findGateway(String providerName, String apiKey, String apiBase) {
        // 1) 直接按配置名
        if (providerName != null && !providerName.isBlank()) {
            ProviderSpec spec = findByName(providerName);
            if (spec != null && (spec.isGateway() || spec.isLocal())) return spec;
        }

        // 2) 自动识别
        for (ProviderSpec spec : PROVIDERS) {
            String keyPrefix = spec.getDetectByKeyPrefix();
            if (keyPrefix != null && !keyPrefix.isBlank() && apiKey != null && apiKey.startsWith(keyPrefix)) {
                return spec;
            }
            String baseKw = spec.getDetectByBaseKeyword();
            if (baseKw != null && !baseKw.isBlank() && apiBase != null && apiBase.contains(baseKw)) {
                return spec;
            }
        }

        return null;
    }

    /**
     * 按名称查找提供者
     */
    public static ProviderSpec findByName(String name) {
        if (name == null) return null;
        for (ProviderSpec spec : PROVIDERS) {
            if (name.equals(spec.getName())) return spec;
        }
        return null;
    }
}