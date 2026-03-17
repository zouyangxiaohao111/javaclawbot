package providers;

import config.ConfigSchema;
import providers.startegy.FallbackStrategies;
import providers.startegy.FallbackStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 模型回退管理器
 *
 * 职责：
 * - 构建 fallback 链（从配置解析）
 * - 执行带回退的 LLM 调用
 * - 管理回退策略
 *
 * 设计模式：
 * - Strategy：回退策略由 FallbackStrategy 决定
 * - Chain of Responsibility：按链执行 provider/model
 */
public final class ModelFallbackManager {

    /**
     * 回退链
     *
     * 不可变数据类，包含完整的 provider/model 链和回退策略
     */
    public static final class FallbackChain {
        private final String primaryProviderName;
        private final String primaryModel;
        private final LLMProvider primary;
        private final List<NamedProvider> fallbacks;
        private final FallbackStrategy strategy;
        private final int maxAttempts;

        public FallbackChain(
                String primaryProviderName,
                String primaryModel,
                LLMProvider primary,
                List<NamedProvider> fallbacks,
                FallbackStrategy strategy,
                int maxAttempts
        ) {
            this.primaryProviderName = primaryProviderName;
            this.primaryModel = primaryModel;
            this.primary = Objects.requireNonNull(primary, "primary");
            this.fallbacks = fallbacks != null ? List.copyOf(fallbacks) : List.of();
            this.strategy = Objects.requireNonNull(strategy, "strategy");
            this.maxAttempts = Math.max(1, maxAttempts);
        }

        public String getPrimaryProviderName() {
            return primaryProviderName;
        }

        public String getPrimaryModel() {
            return primaryModel;
        }

        public LLMProvider getPrimary() {
            return primary;
        }

        public List<NamedProvider> getFallbacks() {
            return fallbacks;
        }

        public FallbackStrategy getStrategy() {
            return strategy;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        /**
         * 返回完整 provider/model 链
         */
        public List<NamedProvider> fullChain() {
            List<NamedProvider> chain = new ArrayList<>();
            chain.add(new NamedProvider(primaryProviderName, primaryModel, primary));
            chain.addAll(fallbacks);
            return chain;
        }

        /**
         * 链的总长度
         */
        public int chainLength() {
            return 1 + fallbacks.size();
        }
    }

    /**
     * 带名称的 Provider 节点
     */
    public static final class NamedProvider {
        private final String name;
        private final String model;
        private final LLMProvider provider;

        public NamedProvider(String name, String model, LLMProvider provider) {
            this.name = name;
            this.model = model;
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        public String getName() {
            return name;
        }

        public String getModel() {
            return model;
        }

        public LLMProvider getProvider() {
            return provider;
        }

        @Override
        public String toString() {
            return name + " / " + model;
        }
    }

    /**
     * LLM 调用参数
     */
    public static final class ChatParams {
        private final List<java.util.Map<String, Object>> messages;
        private final List<java.util.Map<String, Object>> tools;
        private final int maxTokens;
        private final double temperature;
        private final String reasoningEffort;

        public ChatParams(
                List<java.util.Map<String, Object>> messages,
                List<java.util.Map<String, Object>> tools,
                int maxTokens,
                double temperature,
                String reasoningEffort
        ) {
            this.messages = messages;
            this.tools = tools;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.reasoningEffort = reasoningEffort;
        }

        public List<java.util.Map<String, Object>> getMessages() {
            return messages;
        }

        public List<java.util.Map<String, Object>> getTools() {
            return tools;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }
    }

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelFallbackManager.class);

    /**
     * 从配置构建回退链
     *
     * @param config 配置对象
     * @return FallbackChain
     */
    public FallbackChain buildFallbackChain(ConfigSchema.Config config) {
        Objects.requireNonNull(config, "config");

        ConfigSchema.AgentDefaults defaults = config.getAgents().getDefaults();
        String providerName = defaults.getProvider();
        String model = defaults.getModel();

        // 创建 primary provider
        LLMProvider primaryProvider = ProviderFactory.createProvider(config, providerName, model);
        String primaryProviderName = ProviderFactory.resolveProviderName(config, providerName, model);

        // 获取 fallback 配置
        ConfigSchema.FallbackConfig fallbackConfig = defaults.getFallback();
        FallbackStrategy strategy = FallbackStrategies.byMode(
                fallbackConfig != null ? fallbackConfig.getMode() : "on_error"
        );
        int maxAttempts = fallbackConfig != null ? fallbackConfig.getMaxAttempts() : 3;

        // 构建 fallback 节点
        List<NamedProvider> fallbacks = buildFallbackNodes(config, fallbackConfig);

        return new FallbackChain(
                primaryProviderName,
                model,
                primaryProvider,
                fallbacks,
                strategy,
                maxAttempts
        );
    }

    /**
     * 执行带回退的 LLM 调用
     *
     * @param chain  回退链
     * @param params 调用参数
     * @return CompletableFuture<LLMResponse>
     */
    public CompletableFuture<LLMResponse> executeWithFallback(FallbackChain chain, ChatParams params) {
        List<NamedProvider> fullChain = chain.fullChain();
        int maxAttempts = Math.min(chain.getMaxAttempts(), fullChain.size());
        if (maxAttempts <= 0) maxAttempts = 1;

        CompletableFuture<LLMResponse> result = new CompletableFuture<>();
        invokeAt(chain, fullChain, 0, maxAttempts, params, result);
        return result;
    }

    /**
     * 执行带回退的 LLM 调用（简化版）
     *
     * @param chain           回退链
     * @param messages        消息列表
     * @param tools           工具列表
     * @param maxTokens       最大 token
     * @param temperature     温度
     * @param reasoningEffort 推理努力
     * @return CompletableFuture<LLMResponse>
     */
    public CompletableFuture<LLMResponse> executeWithFallback(
            FallbackChain chain,
            List<java.util.Map<String, Object>> messages,
            List<java.util.Map<String, Object>> tools,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        ChatParams params = new ChatParams(messages, tools, maxTokens, temperature, reasoningEffort);
        return executeWithFallback(chain, params);
    }

    /**
     * 递归执行链中的每个节点
     */
    private void invokeAt(
            FallbackChain chain,
            List<NamedProvider> fullChain,
            int index,
            int maxAttempts,
            ChatParams params,
            CompletableFuture<LLMResponse> result
    ) {
        if (index >= fullChain.size() || index >= maxAttempts) {
            result.complete(errorResponse("所有提供商/模型都失败或没有有效的提供商响应。"));
            return;
        }

        NamedProvider current = fullChain.get(index);
        String providerName = current.getName();
        String model = current.getModel();
        LLMProvider provider = current.getProvider();

        provider.chatWithRetry(params.getMessages(), params.getTools(), model, params.getMaxTokens(), params.getTemperature(), params.getReasoningEffort())
                .whenComplete((resp, ex) -> {
                    boolean shouldFallback = chain.getStrategy().shouldFallback(resp, ex, index);

                    if (!shouldFallback) {
                        if (ex != null) {
                            result.complete(errorResponse(ex.toString()));
                        } else {
                            result.complete(resp != null ? resp : errorResponse("提供商返回空响应。"));
                        }
                        return;
                    }

                    // 已经没有更多 fallback 节点
                    if (index + 1 >= fullChain.size() || index + 1 >= maxAttempts) {
                        if (ex != null) {
                            log.warn("Provider {} / {} failed and no more fallbacks. error={}",
                                    providerName, model, ex.toString());
                            result.complete(errorResponse(ex.toString()));
                        } else {
                            log.warn("Provider {} / {} produced fallback-worthy response and no more fallbacks.",
                                    providerName, model);
                            result.complete(resp != null ? resp : errorResponse("没有更多可用的回退。"));
                        }
                        return;
                    }

                    NamedProvider next = fullChain.get(index + 1);
                    String reason = ex != null ? ex.toString() : summarizeResponse(resp);

                    log.warn("Provider {} / {} failed or invalid, fallback to {} / {}. strategy={}, reason={}",
                            providerName,
                            model,
                            next.getName(),
                            next.getModel(),
                            chain.getStrategy().name(),
                            reason);

                    invokeAt(chain, fullChain, index + 1, maxAttempts, params, result);
                });
    }

    /**
     * 构建 fallback 节点列表
     */
    private List<NamedProvider> buildFallbackNodes(ConfigSchema.Config config, ConfigSchema.FallbackConfig fallbackConfig) {
        List<NamedProvider> fallbacks = new ArrayList<>();

        if (fallbackConfig == null || !fallbackConfig.isEnabled()) {
            return fallbacks;
        }

        List<ConfigSchema.FallbackTarget> targets = fallbackConfig.getTargets();
        if (targets == null || targets.isEmpty()) {
            return fallbacks;
        }

        for (ConfigSchema.FallbackTarget target : targets) {
            if (!target.isEnabled()) {
                continue;
            }

            String targetProvider = target.getProvider();
            if (targetProvider == null || targetProvider.isBlank()) {
                continue;
            }

            List<String> models = target.getModels();
            if (models == null || models.isEmpty()) {
                continue;
            }

            // 为每个 model 创建一个 fallback 节点
            for (String targetModel : models) {
                if (targetModel == null || targetModel.isBlank()) {
                    continue;
                }

                try {
                    // 获取或创建 provider 配置
                    String apiKey = target.getApiKey();
                    String apiBase = target.getApiBase();

                    // 如果没有显式配置，从全局配置获取
                    if ((apiKey == null || apiKey.isBlank()) && (apiBase == null || apiBase.isBlank())) {
                        ConfigSchema.ProviderConfig pc = config.getProviders().getByName(targetProvider);
                        if (pc != null) {
                            if (apiKey == null || apiKey.isBlank()) apiKey = pc.getApiKey();
                            if (apiBase == null || apiBase.isBlank()) apiBase = pc.getApiBase();
                        }
                    }

                    // 创建 provider 实例
                    LLMProvider provider = ProviderFactory.createProviderWithConfig(targetProvider, apiKey, apiBase, targetModel);

                    fallbacks.add(new NamedProvider(targetProvider, targetModel, provider));
                } catch (Exception e) {
                    log.warn("创建回退提供商失败: {} / {}", targetProvider, targetModel, e);
                }
            }
        }

        return fallbacks;
    }

    private static LLMResponse errorResponse(String message) {
        LLMResponse r = new LLMResponse();
        r.setContent("Error: " + message);
        r.setFinishReason("error");
        return r;
    }

    private static String summarizeResponse(LLMResponse resp) {
        if (resp == null) return "null_response";
        String finish = resp.getFinishReason();
        String content = resp.getContent();
        if (content != null && content.length() > 120) {
            content = content.substring(0, 120) + "...";
        }
        return "finish_reason=" + finish + ", content=" + content;
    }
}