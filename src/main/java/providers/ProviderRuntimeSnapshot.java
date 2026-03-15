package providers;

import java.util.Objects;

/**
 * 运行时 provider 快照
 *
 * 设计思想：
 * - 每次配置变化时，不修改旧 provider，而是创建一套新的不可变快照
 * - 一次 chat 请求始终绑定同一个 snapshot，保证配置一致性
 * - 委托 ModelFallbackManager.FallbackChain 管理 fallback 逻辑
 */
public final class ProviderRuntimeSnapshot {

    private final long version;
    private final ModelFallbackManager.FallbackChain fallbackChain;

    public ProviderRuntimeSnapshot(long version, ModelFallbackManager.FallbackChain fallbackChain) {
        this.version = version;
        this.fallbackChain = Objects.requireNonNull(fallbackChain, "fallbackChain");
    }

    public long getVersion() {
        return version;
    }

    public String getModel() {
        return fallbackChain.getPrimaryModel();
    }

    public String getPrimaryProviderName() {
        return fallbackChain.getPrimaryProviderName();
    }

    public LLMProvider getPrimary() {
        return fallbackChain.getPrimary();
    }

    public ModelFallbackManager.FallbackChain getFallbackChain() {
        return fallbackChain;
    }

    /**
     * 兼容旧 API
     */
    @Deprecated
    public java.util.List<ModelFallbackManager.NamedProvider> getFallbacks() {
        return fallbackChain.getFallbacks();
    }

    /**
     * 兼容旧 API
     */
    @Deprecated
    public providers.startegy.FallbackStrategy getFallbackStrategy() {
        return fallbackChain.getStrategy();
    }

    /**
     * 兼容旧 API
     */
    @Deprecated
    public int getMaxAttempts() {
        return fallbackChain.getMaxAttempts();
    }

    /**
     * 兼容旧 API：返回完整 provider/model 链
     */
    @Deprecated
    public java.util.List<ModelFallbackManager.NamedProvider> fullChain() {
        return fallbackChain.fullChain();
    }
}