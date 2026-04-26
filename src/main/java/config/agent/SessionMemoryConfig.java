package config.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Session Memory Configuration
 * 对齐 Open-ClaudeCode: src/services/SessionMemory/sessionMemoryUtils.ts:32-36
 *
 * 配置参数：
 * - minimumMessageTokensToInit: 初始化阈值（默认 10,000）
 * - minimumTokensBetweenUpdate: 更新间隔 token 数（默认 5,000）
 * - toolCallsBetweenUpdates: 更新间隔工具调用次数（默认 3）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionMemoryConfig {

    /**
     * 是否启用 Session Memory
     */
    private boolean enabled = false;

    /**
     * 初始化最小 token 数
     * 对齐: DEFAULT_SESSION_MEMORY_CONFIG.minimumMessageTokensToInit = 10000
     */
    private int minimumMessageTokensToInit = 10_000;

    /**
     * 更新间隔 token 数（自上次提取后上下文增长量）
     * 对齐: DEFAULT_SESSION_MEMORY_CONFIG.minimumTokensBetweenUpdate = 5000
     */
    private int minimumTokensBetweenUpdate = 5_000;

    /**
     * 更新间隔工具调用次数
     * 对齐: DEFAULT_SESSION_MEMORY_CONFIG.toolCallsBetweenUpdates = 3
     */
    private int toolCallsBetweenUpdates = 3;

    /**
     * 是否启用快速压缩路径（使用已提取的 session memory 作为摘要，无需 LLM 调用）
     * 对齐 Open-ClaudeCode: tengu_sm_compact GrowthBook 标志
     *
     * 注意：需要同时启用 sessionMemory 才能生效
     */
    private boolean smCompactEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinimumMessageTokensToInit() {
        return minimumMessageTokensToInit;
    }

    public void setMinimumMessageTokensToInit(int minimumMessageTokensToInit) {
        this.minimumMessageTokensToInit = minimumMessageTokensToInit;
    }

    public int getMinimumTokensBetweenUpdate() {
        return minimumTokensBetweenUpdate;
    }

    public void setMinimumTokensBetweenUpdate(int minimumTokensBetweenUpdate) {
        this.minimumTokensBetweenUpdate = minimumTokensBetweenUpdate;
    }

    public int getToolCallsBetweenUpdates() {
        return toolCallsBetweenUpdates;
    }

    public void setToolCallsBetweenUpdates(int toolCallsBetweenUpdates) {
        this.toolCallsBetweenUpdates = toolCallsBetweenUpdates;
    }

    public boolean isSmCompactEnabled() {
        return smCompactEnabled;
    }

    public void setSmCompactEnabled(boolean smCompactEnabled) {
        this.smCompactEnabled = smCompactEnabled;
    }

    /**
     * 检查是否可通过环境变量强制开启
     */
    public static boolean isForcedEnabled() {
        String enable = System.getenv("ENABLE_SESSION_MEMORY");
        return "1".equals(enable) || "true".equalsIgnoreCase(enable);
    }

    /**
     * 检查是否可通过环境变量强制关闭
     */
    public static boolean isForcedDisabled() {
        String disable = System.getenv("DISABLE_SESSION_MEMORY");
        return "1".equals(disable) || "true".equalsIgnoreCase(disable);
    }

    /**
     * 获取最终启用状态（环境变量优先）
     */
    public boolean isEffectivelyEnabled() {
        if (isForcedEnabled()) return true;
        if (isForcedDisabled()) return false;
        return enabled;
    }
}
