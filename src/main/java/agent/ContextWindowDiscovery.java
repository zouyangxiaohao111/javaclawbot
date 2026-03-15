package agent;

import config.ConfigIO;
import config.ConfigSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文窗口发现
 * 
 * 对齐 OpenClaw 的 context.ts
 * 
 * 功能：
 * - 从配置文件中读取模型的上下文窗口配置
 * - 缓存模型上下文窗口信息
 * - 提供查询接口 lookupContextTokens(modelId)
 * - 提供解析接口 resolveContextTokensForModel(provider, model)
 */
public class ContextWindowDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowDiscovery.class);

    /** Anthropic 1M 上下文模型前缀 */
    private static final List<String> ANTHROPIC_1M_MODEL_PREFIXES = List.of(
            "claude-opus-4", "claude-sonnet-4"
    );

    /** Anthropic 1M 上下文 token 数 */
    public static final int ANTHROPIC_CONTEXT_1M_TOKENS = 1_048_576;

    /** 默认上下文窗口（4K） */
    public static final int DEFAULT_CONTEXT_WINDOW = 4096;

    /** 模型上下文窗口缓存 */
    private static final Map<String, Integer> MODEL_CACHE = new ConcurrentHashMap<>();

    /** 是否已初始化 */
    private static volatile boolean initialized = false;

    /**
     * 初始化上下文窗口缓存
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            ConfigSchema.Config config = ConfigIO.loadConfig(null);
            if (config != null) {
                applyConfiguredContextWindows(config);
            }
            initialized = true;
            log.debug("Context window cache initialized with {} entries", MODEL_CACHE.size());
        } catch (Exception e) {
            log.warn("Failed to initialize context window cache: {}", e.getMessage());
        }
    }

    /**
     * 查找模型的上下文窗口大小
     *
     * @param modelId 模型 ID
     * @return 上下文窗口大小，如果未找到返回 null
     */
    public static Integer lookupContextTokens(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }

        // 确保已初始化
        if (!initialized) {
            initialize();
        }

        return MODEL_CACHE.get(modelId);
    }

    /**
     * 解析模型的上下文 token 数
     *
     * @param provider 提供商
     * @param model     模型 ID
     * @return 上下文窗口大小
     */
    public static int resolveContextTokensForModel(String provider, String model) {
        return resolveContextTokensForModel(provider, model, null, DEFAULT_CONTEXT_WINDOW);
    }

    /**
     * 解析模型的上下文 token 数
     *
     * @param provider              提供商
     * @param model                 模型 ID
     * @param contextTokensOverride 覆盖值
     * @param fallback              默认值
     * @return 上下文窗口大小
     */
    public static int resolveContextTokensForModel(
            String provider,
            String model,
            Integer contextTokensOverride,
            Integer fallback
    ) {
        // 优先使用覆盖值
        if (contextTokensOverride != null && contextTokensOverride > 0) {
            return contextTokensOverride;
        }

        // 确保已初始化
        if (!initialized) {
            initialize();
        }

        // 解析提供商/模型引用
        ProviderModelRef ref = resolveProviderModelRef(provider, model);
        if (ref != null) {
            // 检查 Anthropic 1M 模型
            if (isAnthropic1MModel(ref.provider, ref.model)) {
                return ANTHROPIC_CONTEXT_1M_TOKENS;
            }

            // 尝试从配置直接查找
            if (provider != null && !provider.isBlank()) {
                Integer configured = resolveConfiguredProviderContextWindow(ref.provider, ref.model);
                if (configured != null) {
                    return configured;
                }
            }

            // 尝试提供商限定的缓存键
            if (provider != null && !provider.isBlank() && !ref.model.contains("/")) {
                String qualifiedKey = normalizeProviderId(ref.provider) + "/" + ref.model;
                Integer qualifiedResult = MODEL_CACHE.get(qualifiedKey);
                if (qualifiedResult != null) {
                    return qualifiedResult;
                }
            }
        }

        // 尝试裸键查找
        Integer bareResult = MODEL_CACHE.get(model);
        if (bareResult != null) {
            return bareResult;
        }

        // 尝试提供商限定的键（提供商隐式时）
        if (ref != null && !ref.model.contains("/")) {
            String qualifiedKey = normalizeProviderId(ref.provider) + "/" + ref.model;
            Integer qualifiedResult = MODEL_CACHE.get(qualifiedKey);
            if (qualifiedResult != null) {
                return qualifiedResult;
            }
        }

        return fallback != null ? fallback : DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * 应用配置的上下文窗口
     */
    private static void applyConfiguredContextWindows(ConfigSchema.Config config) {
        // 从配置中读取模型上下文窗口
        // 这里需要根据实际的配置结构来解析
        // 暂时使用硬编码的常见模型上下文窗口

        // OpenAI 模型
        MODEL_CACHE.put("gpt-4", 8192);
        MODEL_CACHE.put("gpt-4-turbo", 128000);
        MODEL_CACHE.put("gpt-4-turbo-preview", 128000);
        MODEL_CACHE.put("gpt-4o", 128000);
        MODEL_CACHE.put("gpt-4o-mini", 128000);
        MODEL_CACHE.put("gpt-3.5-turbo", 16385);
        MODEL_CACHE.put("gpt-3.5-turbo-16k", 16385);

        // Anthropic 模型
        MODEL_CACHE.put("claude-3-opus", 200000);
        MODEL_CACHE.put("claude-3-sonnet", 200000);
        MODEL_CACHE.put("claude-3-haiku", 200000);
        MODEL_CACHE.put("claude-3-5-sonnet", 200000);
        MODEL_CACHE.put("claude-3-5-haiku", 200000);
        MODEL_CACHE.put("claude-opus-4", ANTHROPIC_CONTEXT_1M_TOKENS);
        MODEL_CACHE.put("claude-sonnet-4", ANTHROPIC_CONTEXT_1M_TOKENS);

        // Google 模型
        MODEL_CACHE.put("gemini-pro", 32760);
        MODEL_CACHE.put("gemini-1.5-pro", 1048576);
        MODEL_CACHE.put("gemini-1.5-flash", 1048576);
        MODEL_CACHE.put("gemini-2.0-flash", 1048576);

        // DeepSeek 模型
        MODEL_CACHE.put("deepseek-chat", 64000);
        MODEL_CACHE.put("deepseek-coder", 16384);

        // Qwen 模型
        MODEL_CACHE.put("qwen-turbo", 8192);
        MODEL_CACHE.put("qwen-plus", 32768);
        MODEL_CACHE.put("qwen-max", 32768);
        MODEL_CACHE.put("qwen-2.5-72b-instruct", 131072);

        // 本地模型（Ollama 等）
        MODEL_CACHE.put("llama3", 8192);
        MODEL_CACHE.put("llama3:8b", 8192);
        MODEL_CACHE.put("llama3:70b", 8192);
        MODEL_CACHE.put("mistral", 32768);
        MODEL_CACHE.put("codellama", 16384);

        log.debug("Applied {} default context windows", MODEL_CACHE.size());
    }

    /**
     * 从配置中解析提供商的模型上下文窗口
     */
    private static Integer resolveConfiguredProviderContextWindow(String provider, String model) {
        // 这里需要根据实际的配置结构来解析
        // 暂时返回 null，使用缓存中的值
        return null;
    }

    /**
     * 解析提供商/模型引用
     */
    private static ProviderModelRef resolveProviderModelRef(String provider, String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        String providerRaw = provider != null ? provider.trim() : null;
        String modelRaw = model.trim();

        if (providerRaw != null && !providerRaw.isBlank()) {
            return new ProviderModelRef(normalizeProviderId(providerRaw), modelRaw);
        }

        // 尝试从模型 ID 中解析提供商
        int slash = modelRaw.indexOf('/');
        if (slash > 0) {
            String parsedProvider = normalizeProviderId(modelRaw.substring(0, slash));
            String parsedModel = modelRaw.substring(slash + 1).trim();
            if (!parsedProvider.isBlank() && !parsedModel.isBlank()) {
                return new ProviderModelRef(parsedProvider, parsedModel);
            }
        }

        return null;
    }

    /**
     * 规范化提供商 ID
     */
    private static String normalizeProviderId(String provider) {
        if (provider == null || provider.isBlank()) {
            return "";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 检查是否为 Anthropic 1M 上下文模型
     */
    private static boolean isAnthropic1MModel(String provider, String model) {
        if (!"anthropic".equals(normalizeProviderId(provider))) {
            return false;
        }

        String normalized = model.trim().toLowerCase(Locale.ROOT);
        String modelId = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf("/") + 1)
                : normalized;

        for (String prefix : ANTHROPIC_1M_MODEL_PREFIXES) {
            if (modelId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加模型上下文窗口到缓存
     */
    public static void addModelContextWindow(String modelId, int contextWindow) {
        if (modelId == null || modelId.isBlank() || contextWindow <= 0) {
            return;
        }

        Integer existing = MODEL_CACHE.get(modelId);
        // 保留较小的窗口，避免延迟压缩导致溢出
        if (existing == null || contextWindow < existing) {
            MODEL_CACHE.put(modelId, contextWindow);
        }
    }

    /**
     * 批量添加模型上下文窗口
     */
    public static void addModelContextWindows(Map<String, Integer> models) {
        if (models == null) return;
        models.forEach(ContextWindowDiscovery::addModelContextWindow);
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        MODEL_CACHE.clear();
        initialized = false;
    }

    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return MODEL_CACHE.size();
    }

    /**
     * 提供商/模型引用
     */
    private record ProviderModelRef(String provider, String model) {}
}