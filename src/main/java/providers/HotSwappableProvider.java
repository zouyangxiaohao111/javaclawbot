package providers;

import config.Config;
import config.ConfigReloader;
import config.ConfigSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

public final class HotSwappableProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(HotSwappableProvider.class);

    private final ConfigReloader reloader;
    private final ModelFallbackManager fallbackManager;
    private final ReentrantLock rebuildLock = new ReentrantLock();

    private volatile ProviderRuntimeSnapshot activeSnapshot;

    public HotSwappableProvider(ConfigReloader reloader) {
        super("hot-swap", "hot-swap");
        this.reloader = Objects.requireNonNull(reloader, "reloader");
        this.fallbackManager = new ModelFallbackManager();

        Config cfg = reloader.getCurrentConfig();
        long version = reloader.getVersion();
        this.activeSnapshot = buildSnapshot(cfg, version);
    }

    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            CancelChecker cancelChecker
    ) {
        ProviderRuntimeSnapshot snapshot = ensureLatestSnapshot();
        ModelFallbackManager.FallbackChain chain = snapshot.getFallbackChain();
        return fallbackManager.executeWithFallback(
                chain, messages, tools, maxTokens, temperature, reasoningEffort, cancelChecker
        );
    }

    @Override
    public String getDefaultModel() {
        ProviderRuntimeSnapshot s = activeSnapshot;
        return s != null ? s.getModel() : "default";
    }

    private ProviderRuntimeSnapshot ensureLatestSnapshot() {
        boolean changed = false;
        try {
            changed = reloader.refreshIfChanged();
        } catch (Exception e) {
            log.warn("Config refresh check failed, keep previous provider snapshot: {}", e.toString());
        }

        if (!changed) {
            return activeSnapshot;
        }

        rebuildLock.lock();
        try {
            long version = reloader.getVersion();
            ProviderRuntimeSnapshot current = activeSnapshot;
            if (current != null && current.getVersion() == version) {
                return current;
            }

            try {
                Config cfg = reloader.getCurrentConfig();
                ProviderRuntimeSnapshot next = buildSnapshot(cfg, version);
                activeSnapshot = next;

                ModelFallbackManager.FallbackChain chain = next.getFallbackChain();
                log.info("Provider 快照重建成功。版本={}, 主提供商={} / {}, 回退模式={}",
                        next.getVersion(),
                        next.getPrimaryProviderName(),
                        next.getModel(),
                        chain.getStrategy().name());

                return next;
            } catch (Exception e) {
                log.warn("重建 Provider 快照失败，保留之前的快照。错误={}", e.toString());
                return activeSnapshot;
            }
        } finally {
            rebuildLock.unlock();
        }
    }

    private ProviderRuntimeSnapshot buildSnapshot(Config config, long version) {
        ModelFallbackManager.FallbackChain chain = fallbackManager.buildFallbackChain(config);
        return new ProviderRuntimeSnapshot(version, chain);
    }
}