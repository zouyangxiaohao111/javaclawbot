package config.agent;// =========================
// 智能体 / 提供者 / 工具配置（字段与默认值一比一）
// =========================

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.hearbet.HeartbeatConfig;
import config.provider.FallbackConfig;
import config.tool.QueueConfig;
import context.BootstrapConfig;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class AgentDefaults {
    private String workspace = "~/.javaclawbot/workspace";
    private String model = "qwen/qwen3.6-plus";
    private String provider = "openrouter";
    public static final int MAX_TOKENS = 8192;
    /**
     * 上下文窗口token数量
     */
    public static final int CONTEXT_WINDOW = 32000;
    public static final double TEMPERATURE = 0.1;
    /**
     * 是否开发者
     */
    private boolean development = false;
    private int maxToolIterations = 500;
    private int memoryWindow = 200;
    /**
     * 最大技能装载数量
     */
    private int skillMaxLoad = 5;

    /**
     * 上下文压缩触发阈值（默认 0.95）
     * 当上下文使用率超过此阈值时，阻塞执行 consolidate 压缩
     */
    private double consolidateThreshold = 0.9;

    /**
     * 软裁剪触发阈值（默认 0.7）
     * 当上下文使用率超过此阈值时，执行 ContextPruner 软裁剪
     */
    private double softTrimThreshold = 0.7;

    /**
     * HTTP 请求超时秒数（默认120秒）
     */
    private int timeoutSeconds = 120;

    private String reasoningEffort = null;


    /**
     * 全局最大并发数（对齐 OpenClaw maxConcurrent）
     */
    private int maxConcurrent = 4;

    /**
     * fallback 调用策略配置
     */
    private FallbackConfig fallback = new FallbackConfig();

    /**
     * 心跳配置
     */
    private HeartbeatConfig heartbeat = new HeartbeatConfig();

    /**
     * 队列配置
     */
    private QueueConfig queue = new QueueConfig();

    /**
     * 引导配置
     */
    private BootstrapConfig bootstrapConfig = new BootstrapConfig();

    /**
     * Windows 环境下 Git Bash 的 bash.exe 路径。
     * 仅 Windows 需要，Linux/macOS 使用系统自带 bash/zsh。
     * 示例: "C:\\Program Files\\Git\\bin\\bash.exe"
     * 如果未配置，系统会自动尝试常见路径检测。
     */
    private String windowsBashPath = null;

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setFallback(FallbackConfig fallback) {
        this.fallback = (fallback != null) ? fallback : new FallbackConfig();
    }

    public void setHeartbeat(HeartbeatConfig heartbeat) {
        this.heartbeat = (heartbeat != null) ? heartbeat : new HeartbeatConfig();
    }

    public void setQueue(QueueConfig queue) {
        this.queue = (queue != null) ? queue : new QueueConfig();
    }

    public void setBootstrapConfig(BootstrapConfig bootstrapConfig) {
        this.bootstrapConfig = (bootstrapConfig != null) ? bootstrapConfig : new BootstrapConfig();
    }

    public BootstrapConfig getBootstrapConfig() {
        return bootstrapConfig;
    }
}