package config.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AwaySummary Configuration
 *
 * 配置参数：
 * - enabled: 总开关
 * - idleMinutes: 离开多久后生成摘要（默认 10 分钟）
 * - requireSessionMemory: 是否强依赖 Session Memory（默认 false）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AwaySummaryConfig {

    /**
     * 是否启用 AwaySummary
     */
    private boolean enabled = false;

    /**
     * 离开多久后生成摘要（分钟）
     * 对齐 Open-ClaudeCode: BLUR_DELAY_MS = 5 * 60_000，但用户需求是 10 分钟
     */
    private int idleMinutes = 10;

    /**
     * 是否强依赖 Session Memory
     * false: 可选，有则摘要更丰富
     * true: 必须开启 Session Memory 才能使用
     */
    private boolean requireSessionMemory = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes;
    }

    public boolean isRequireSessionMemory() {
        return requireSessionMemory;
    }

    public void setRequireSessionMemory(boolean requireSessionMemory) {
        this.requireSessionMemory = requireSessionMemory;
    }

    /**
     * 检查是否可通过环境变量强制开启
     */
    public static boolean isForcedEnabled() {
        String enable = System.getenv("ENABLE_AWAY_SUMMARY");
        return "1".equals(enable) || "true".equalsIgnoreCase(enable);
    }

    /**
     * 检查是否可通过环境变量强制关闭
     */
    public static boolean isForcedDisabled() {
        String disable = System.getenv("DISABLE_AWAY_SUMMARY");
        return "1".equals(disable) || "true".equalsIgnoreCase(disable);
    }

    /**
     * 获取空闲分钟数（支持环境变量覆盖）
     */
    public static int getIdleMinutesFromEnv() {
        String env = System.getenv("AWAY_SUMMARY_IDLE_MINUTES");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 10; // 默认值
    }

    /**
     * 获取最终启用状态（环境变量优先）
     */
    public boolean isEffectivelyEnabled() {
        if (isForcedEnabled()) return true;
        if (isForcedDisabled()) return false;
        return enabled;
    }

    /**
     * 获取最终空闲分钟数
     */
    public int getEffectiveIdleMinutes() {
        return getIdleMinutesFromEnv();
    }
}
