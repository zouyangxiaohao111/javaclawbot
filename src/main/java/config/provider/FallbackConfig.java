package config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FallbackConfig {
    /**
     * 是否启用 fallback
     */
    private boolean enabled = true;

    /**
     * 模式：
     * - off
     * - on_error
     * - on_empty
     * - on_invalid
     * - always_try_next
     */
    private String mode = "on_error";

    /**
     * 旧版兼容：仅指定 provider 顺序
     * 若配置了 targets，则优先使用 targets
     */
    /* private List<String> providers = new ArrayList<>();*/

    /**
     * 新版 fallback 目标：
     * 每个 target 可以指定 provider + 多个 models + apiBase/apiKey 覆盖
     */
    private List<FallbackTarget> targets = new ArrayList<>();

    /**
     * 最大尝试次数（包含 primary）
     */
    private int maxAttempts = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /* public List<String> getProviders() { return providers; }
     public void setProviders(List<String> providers) { this.providers = (providers != null) ? providers : new ArrayList<>(); }*/
    public List<FallbackTarget> getTargets() {
        return targets;
    }

    public void setTargets(List<FallbackTarget> targets) {
        this.targets = (targets != null) ? targets : new ArrayList<>();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
