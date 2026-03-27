package config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FallbackTarget {
    /**
     * 是否启用该 fallback 节点
     */
    private boolean enabled = true;

    /**
     * provider 名
     * 例如：openrouter / deepseek / custom / siliconflow
     */
    private String provider = "";

    /**
     * 该 provider 下的多个候选模型
     * 例如：
     * ["gpt-4.1", "claude-3.7-sonnet", "gemini-2.5-pro"]
     */
    private List<String> models = new ArrayList<>();

    /**
     * 可选：覆盖 apiBase
     * 适合 custom 或同 provider 多网关场景
     */
    private String apiBase = null;

    /**
     * 可选：覆盖 apiKey
     * 一般不建议写在 fallback 节点里，但保留能力
     */
    private String apiKey = null;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = (models != null) ? models : new ArrayList<>();
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}