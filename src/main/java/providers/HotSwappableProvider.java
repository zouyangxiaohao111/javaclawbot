package providers;

import config.ConfigReloader;
import config.ConfigSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可热切换 Provider
 *
 * 设计模式：
 * - Proxy：对 AgentLoop 隐藏 provider 热更新与 fallback 逻辑
 * - Strategy：fallback 规则由 FallbackStrategy 决定
 * - Snapshot：每次请求绑定一个一致的 provider 快照
 *
 * 核心行为：
 * 1. 每次 chat 前检查配置文件是否变化
 * 2. 若变化则尝试重建 provider 快照
 * 3. 若新配置有问题，则保留旧快照
 * 4. 委托 ModelFallbackManager 执行 fallback 逻辑
 */
public final class HotSwappableProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(HotSwappableProvider.class);

    private final ConfigReloader reloader;
    private final ModelFallbackManager fallbackManager;
    private final ReentrantLock rebuildLock = new ReentrantLock();

    /**
     * 当前生效的 provider 快照
     */
    private volatile ProviderRuntimeSnapshot activeSnapshot;

    public HotSwappableProvider(ConfigReloader reloader) {
        super("hot-swap", "hot-swap");
        this.reloader = Objects.requireNonNull(reloader, "reloader");
        this.fallbackManager = new ModelFallbackManager();

        ConfigSchema.Config cfg = reloader.getCurrentConfig();
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
            String reasoningEffort
    ) {
        ProviderRuntimeSnapshot snapshot = ensureLatestSnapshot();

        // 委托 ModelFallbackManager 执行
        ModelFallbackManager.FallbackChain chain = snapshot.getFallbackChain();
        return fallbackManager.executeWithFallback(chain, messages, tools, maxTokens, temperature, reasoningEffort);
    }

    @Override
    public String getDefaultModel() {
        ProviderRuntimeSnapshot s = activeSnapshot;
        return s != null ? s.getModel() : "default";
    }

    /**
     * 确保当前快照已刷新到最新配置
     *
     * 若重建失败，则继续使用旧快照
     */
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
                ConfigSchema.Config cfg = reloader.getCurrentConfig();
                ProviderRuntimeSnapshot next = buildSnapshot(cfg, version);
                activeSnapshot = next;

                ModelFallbackManager.FallbackChain chain = next.getFallbackChain();
                log.info("Provider snapshot rebuilt successfully. version={}, primary={} / {}, fallback_mode={}",
                        next.getVersion(),
                        next.getPrimaryProviderName(),
                        next.getModel(),
                        chain.getStrategy().name());

                return next;
            } catch (Exception e) {
                log.warn("Failed to rebuild provider snapshot, keep previous snapshot. error={}", e.toString());
                return activeSnapshot;
            }
        } finally {
            rebuildLock.unlock();
        }
    }

    /**
     * 构建快照
     */
    private ProviderRuntimeSnapshot buildSnapshot(ConfigSchema.Config config, long version) {
        ModelFallbackManager.FallbackChain chain = fallbackManager.buildFallbackChain(config);
        return new ProviderRuntimeSnapshot(version, chain);
    }
}