package context;

import lombok.Data;

/**
 * 上下文修剪配置
 * 
 * 对齐 OpenClaw 的 context-pruning/settings.ts
 */
public class ContextPruningSettings {

    /** 每个字符估算的 token 数 */
    public static final double CHARS_PER_TOKEN_ESTIMATE = 1.2;

    /** 图片估算字符数 */
    public static final int IMAGE_CHAR_ESTIMATE = 8_000;

    /** 默认配置 */
    public static final ContextPruningSettings DEFAULT = new ContextPruningSettings();

    /** 修剪模式：off 或 cache-ttl */
    private String mode = "cache-ttl";

    /** TTL 毫秒数（默认 5 分钟） */
    private long ttlMs = 5 * 60 * 1000;

    /** 保留最后 N 个助手消息 */
    private int keepLastAssistants = 5;

    /** 软修剪阈值（上下文使用率 > 此值时修剪工具结果） */
    private double softTrimRatio = 0.5;

    /** 硬清除阈值（上下文使用率 > 此值时清除旧工具结果） */
    private double hardClearRatio = 0.95;

    /** 最小可修剪工具字符数 */
    private int minPrunableToolChars = 50_000;

    /** 软修剪配置 */
    private SoftTrimConfig softTrim = new SoftTrimConfig();

    /** 硬清除配置 */
    private HardClearConfig hardClear = new HardClearConfig();

    /** 当前轮次保留最后的工具调用数量（当需要裁剪当前轮次时） */
    private int keepLastToolsInTurn = 10;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    public void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public int getKeepLastAssistants() {
        return keepLastAssistants;
    }

    public void setKeepLastAssistants(int keepLastAssistants) {
        this.keepLastAssistants = Math.max(0, keepLastAssistants);
    }

    public double getSoftTrimRatio() {
        return softTrimRatio;
    }

    public void setSoftTrimRatio(double softTrimRatio) {
        this.softTrimRatio = Math.min(1.0, Math.max(0.0, softTrimRatio));
    }

    public double getHardClearRatio() {
        return hardClearRatio;
    }

    public void setHardClearRatio(double hardClearRatio) {
        this.hardClearRatio = Math.min(1.0, Math.max(0.0, hardClearRatio));
    }

    public int getMinPrunableToolChars() {
        return minPrunableToolChars;
    }

    public int obtainMinPrunableToolCharsByContextWindow(Integer contextWindow) {
        if (contextWindow == null) {
            return minPrunableToolChars;
        }

        // 当字符数大于上下文0.8时,触发修剪
        return (int) Math.floor(hardClearRatio * contextWindow * CHARS_PER_TOKEN_ESTIMATE);
    }

    public void setMinPrunableToolChars(int minPrunableToolChars) {
        this.minPrunableToolChars = Math.max(0, minPrunableToolChars);
    }

    public SoftTrimConfig getSoftTrim() {
        return softTrim;
    }

    public void setSoftTrim(SoftTrimConfig softTrim) {
        this.softTrim = softTrim != null ? softTrim : new SoftTrimConfig();
    }

    public HardClearConfig getHardClear() {
        return hardClear;
    }

    public void setHardClear(HardClearConfig hardClear) {
        this.hardClear = hardClear != null ? hardClear : new HardClearConfig();
    }

    public boolean isEnabled() {
        return "cache-ttl".equals(mode);
    }

    public int getKeepLastToolsInTurn() {
        return keepLastToolsInTurn;
    }

    public void setKeepLastToolsInTurn(int keepLastToolsInTurn) {
        this.keepLastToolsInTurn = Math.max(0, keepLastToolsInTurn);
    }

    /**
     * 软修剪配置
     */
    public static class SoftTrimConfig {
        private int maxChars = 1500;
        private int headChars = 200;
        private int tailChars = 100;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = Math.max(0, maxChars);
        }

        public int getHeadChars() {
            return headChars;
        }

        public void setHeadChars(int headChars) {
            this.headChars = Math.max(0, headChars);
        }

        public int getTailChars() {
            return tailChars;
        }

        public void setTailChars(int tailChars) {
            this.tailChars = Math.max(0, tailChars);
        }
    }

    /**
     * 硬清除配置
     */
    public static class HardClearConfig {
        private boolean enabled = true;
        private String placeholder = "[tool result content cleared]";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder != null && !placeholder.isBlank() 
                    ? placeholder.trim() 
                    : "[tool result content cleared]";
        }
    }
}