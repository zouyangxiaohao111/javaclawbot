package context;

import config.Config;
import config.ConfigIO;
import config.ConfigSchema;
import config.provider.ProviderConfig;
import config.provider.model.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    public static final int DEFAULT_CONTEXT_WINDOW = 64000;

    /** 模型上下文窗口缓存 */
    private static final Map<String, Integer> MODEL_CACHE = new ConcurrentHashMap<>();




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
            Integer fallback, Config currentConfig
    ) {
        // 优先使用覆盖值
        if (contextTokensOverride != null && contextTokensOverride > 0) {
            return contextTokensOverride;
        }

        // 根据模型配置去找
        int contextWindow = currentConfig.obtainContextWindow(model);
        if (contextWindow > 0) {
            return contextWindow;
        }

        return fallback != null ? fallback : DEFAULT_CONTEXT_WINDOW;
    }


}