package providers;

import config.Config;
import config.ConfigSchema;
import config.agent.AgentDefaults;
import config.provider.FallbackConfig;
import config.provider.FallbackTarget;
import config.provider.ProviderConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import providers.startegy.FallbackStrategies;
import providers.startegy.FallbackStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
public final class ModelFallbackManager {

    public static final class FallbackChain {
        private final String primaryProviderName;
        private final String primaryModel;
        private final LLMProvider primary;
        private final List<NamedProvider> fallbacks;
        private final FallbackStrategy strategy;
        private final int maxAttempts;
        private final Config config;  // 保存 config 引用，用于获取每个模型的 maxTokens 和 temperature

        public FallbackChain(
                String primaryProviderName,
                String primaryModel,
                LLMProvider primary,
                List<NamedProvider> fallbacks,
                FallbackStrategy strategy,
                int maxAttempts,
                Config config
        ) {
            this.primaryProviderName = primaryProviderName;
            this.primaryModel = primaryModel;
            this.primary = Objects.requireNonNull(primary, "primary");
            this.fallbacks = fallbacks != null ? List.copyOf(fallbacks) : List.of();
            this.strategy = Objects.requireNonNull(strategy, "strategy");
            this.maxAttempts = Math.max(1, maxAttempts);
            this.config = config;
        }

        public String getPrimaryProviderName() { return primaryProviderName; }
        public String getPrimaryModel() { return primaryModel; }
        public LLMProvider getPrimary() { return primary; }
        public List<NamedProvider> getFallbacks() { return fallbacks; }
        public FallbackStrategy getStrategy() { return strategy; }
        public int getMaxAttempts() { return maxAttempts; }
        public Config getConfig() { return config; }

        public List<NamedProvider> fullChain() {
            List<NamedProvider> chain = new ArrayList<>();
            chain.add(new NamedProvider(primaryProviderName, primaryModel, primary));
            chain.addAll(fallbacks);
            return chain;
        }

        public int chainLength() {
            return 1 + fallbacks.size();
        }
    }

    @Slf4j
    public static final class NamedProvider {
        private final String name;
        private final String model;
        private final LLMProvider provider;

        public NamedProvider(String name, String model, LLMProvider provider) {
            this.name = name;
            this.model = model;
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        public String getName() { return name; }
        public String getModel() { return model; }
        public LLMProvider getProvider() { return provider; }

        @Override
        public String toString() {
            return name + " / " + model;
        }
    }

    @Data
    public static final class ChatParams {
        private final List<java.util.Map<String, Object>> messages;
        private final List<java.util.Map<String, Object>> tools;
        private final String model;
        private final int maxTokens;
        private final double temperature;
        private final String reasoningEffort;
        private final Map<String, Object> think;
        private final Map<String, Object> extraBody;
        private final CancelChecker cancelChecker;

        public ChatParams(
                List<java.util.Map<String, Object>> messages,
                List<java.util.Map<String, Object>> tools,
                String model,
                int maxTokens,
                double temperature,
                String reasoningEffort,
                Map<String, Object> think,
                Map<String, Object> extraBody,
                CancelChecker cancelChecker
        ) {
            this.messages = messages;
            this.tools = tools;
            this.model = model;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.reasoningEffort = reasoningEffort;
            this.think = think;
            this.extraBody = extraBody;
            this.cancelChecker = cancelChecker;
        }


    }



    public FallbackChain buildFallbackChain(Config config) {
        Objects.requireNonNull(config, "config");

        AgentDefaults defaults = config.getAgents().getDefaults();
        String providerName = defaults.getProvider();
        String model = defaults.getModel();

        LLMProvider primaryProvider = ProviderFactory.createProvider(config, providerName, model);
        String primaryProviderName = ProviderFactory.resolveProviderName(config, providerName, model);

        FallbackConfig fallbackConfig = defaults.getFallback();
        FallbackStrategy strategy = FallbackStrategies.byMode(
                fallbackConfig != null ? fallbackConfig.getMode() : "on_error"
        );
        int maxAttempts = fallbackConfig != null ? fallbackConfig.getMaxAttempts() : 3;

        List<NamedProvider> fallbacks = buildFallbackNodes(config, fallbackConfig);

        return new FallbackChain(
                primaryProviderName,
                model,
                primaryProvider,
                fallbacks,
                strategy,
                maxAttempts,
                config  // 传入 config 引用
        );
    }

    public CompletableFuture<LLMResponse> executeWithFallback(FallbackChain chain, ChatParams params) {
        List<NamedProvider> fullChain = chain.fullChain();
        int maxAttempts = Math.min(chain.getMaxAttempts(), fullChain.size());
        if (maxAttempts <= 0) maxAttempts = 1;

        CompletableFuture<LLMResponse> result = new CompletableFuture<>();
        invokeAt(chain, fullChain, 0, maxAttempts, params, result);
        return result;
    }

    public CompletableFuture<LLMResponse> executeWithFallback(
            FallbackChain chain,
            List<java.util.Map<String, Object>> messages,
            List<java.util.Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Map<String, Object> think,
            Map<String, Object> extraBody,
            CancelChecker cancelChecker
    ) {
        ChatParams params = new ChatParams(
                messages, tools, model, maxTokens, temperature, reasoningEffort, think, extraBody, cancelChecker
        );
        return executeWithFallback(chain, params);
    }

    private void invokeAt(
            FallbackChain chain,
            List<NamedProvider> fullChain,
            int index,
            int maxAttempts,
            ChatParams params,
            CompletableFuture<LLMResponse> result
    ) {
        if (result.isDone()) {
            return;
        }

        if (params.getCancelChecker() != null && params.getCancelChecker().isCancelled()) {
            result.completeExceptionally(new CancellationException("LLM fallback chain cancelled"));
            return;
        }

        if (index >= fullChain.size() || index >= maxAttempts) {
            result.complete(errorResponse("所有提供商/模型都失败或没有有效的提供商响应。"));
            return;
        }

        NamedProvider current = fullChain.get(index);
        String providerName = current.getName();
        String model = current.getModel();
        LLMProvider provider = current.getProvider();

        // 根据 fallback provider 对应的模型获取实时配置
        Config cfg = chain.getConfig();
        int maxTokens = params.getMaxTokens();
        double temperature = params.getTemperature();

        if (cfg != null) {
            // 使用 model 而不是 params.getModel() 来获取当前 fallback 模型的配置
            Integer modelMaxTokens = cfg.obtainMaxTokens(model);
            Double modelTemperature = cfg.obtainTemperature(model);
            if (modelMaxTokens != null) {
                maxTokens = modelMaxTokens;
            }
            if (modelTemperature != null) {
                temperature = modelTemperature;
            }
        }

        provider.chatWithRetry(
                params.getMessages(),
                params.getTools(),
                model,  // 使用当前 fallback provider 对应的模型
                maxTokens,  // 使用当前模型对应的 maxTokens
                temperature,  // 使用当前模型对应的 temperature
                params.getReasoningEffort(),
                params.getThink(),
                params.getExtraBody(),
                params.getCancelChecker()
        ).whenComplete((resp, ex) -> {
            if (result.isDone()) {
                return;
            }

            if (params.getCancelChecker() != null && params.getCancelChecker().isCancelled()) {
                result.completeExceptionally(new CancellationException("LLM fallback chain cancelled"));
                return;
            }

            Throwable root = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause()
                    : ex;

            if (root instanceof CancellationException) {
                result.completeExceptionally(root);
                return;
            }

            boolean shouldFallback = chain.getStrategy().shouldFallback(resp, ex, index);

            if (!shouldFallback) {
                if (ex != null) {
                    result.complete(errorResponse(ex.toString()));
                } else {
                    result.complete(resp != null ? resp : errorResponse("提供商返回空响应。"));
                }
                return;
            }

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

    private List<NamedProvider> buildFallbackNodes(Config config, FallbackConfig fallbackConfig) {
        List<NamedProvider> fallbacks = new ArrayList<>();

        if (fallbackConfig == null || !fallbackConfig.isEnabled()) {
            return fallbacks;
        }

        List<FallbackTarget> targets = fallbackConfig.getTargets();
        if (targets == null || targets.isEmpty()) {
            return fallbacks;
        }

        for (FallbackTarget target : targets) {
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

            for (String targetModel : models) {
                if (targetModel == null || targetModel.isBlank()) {
                    continue;
                }

                try {
                    String apiKey = target.getApiKey();
                    String apiBase = target.getApiBase();

                    if ((apiKey == null || apiKey.isBlank()) && (apiBase == null || apiBase.isBlank())) {
                        ProviderConfig pc = config.getProviders().getByName(targetProvider);
                        if (pc != null) {
                            if (apiKey == null || apiKey.isBlank()) apiKey = pc.getApiKey();
                            if (apiBase == null || apiBase.isBlank()) apiBase = pc.getApiBase();
                        }
                    }

                    LLMProvider provider = ProviderFactory.createProviderWithConfig(
                            targetProvider, apiKey, apiBase, targetModel,
                            config.getAgents() != null && config.getAgents().getDefaults() != null
                                    ? config.getAgents().getDefaults().getTimeoutSeconds() : 120
                    );

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