package context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.agent.AgentDefaults;
import lombok.Getter;
import lombok.Setter;

/**
 * Bootstrap 上下文配置
 * 对齐 OpenClaw 的 bootstrap-files.ts
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BootstrapConfig {

    /**
     * 上下文模式
     * - full: 加载所有 bootstrap 文件
     * - lightweight: 根据 runKind 决定加载哪些文件
     */
    public enum ContextMode {
        FULL,
        LIGHTWEIGHT
    }

    /**
     * 运行类型
     * - default: 普通会话
     * - heartbeat: 心跳任务
     * - cron: 定时任务
     */
    public enum RunKind {
        DEFAULT,
        HEARTBEAT,
        CRON
    }

    // 默认值（对齐 OpenClaw）
    public static final int DEFAULT_BOOTSTRAP_MAX_CHARS = 20_000;
    public static final int DEFAULT_BOOTSTRAP_TOTAL_MAX_CHARS = 150_000;
    public static final int MIN_BOOTSTRAP_FILE_BUDGET_CHARS = 64;
    public static final double BOOTSTRAP_HEAD_RATIO = 0.7;
    public static final double BOOTSTRAP_TAIL_RATIO = 0.2;

    private int maxChars = DEFAULT_BOOTSTRAP_MAX_CHARS;
    private int totalMaxChars = DEFAULT_BOOTSTRAP_TOTAL_MAX_CHARS;
    private ContextMode contextMode = ContextMode.FULL;
    private RunKind runKind = RunKind.DEFAULT;

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = Math.max(1, maxChars);
    }

    public int getTotalMaxChars() {
        return totalMaxChars;
    }

    public void setTotalMaxChars(int totalMaxChars) {
        this.totalMaxChars = Math.max(1, totalMaxChars);
    }

    public ContextMode getContextMode() {
        return contextMode;
    }

    public void setContextMode(ContextMode contextMode) {
        this.contextMode = contextMode != null ? contextMode : ContextMode.FULL;
    }

    public RunKind getRunKind() {
        return runKind;
    }

    public void setRunKind(RunKind runKind) {
        this.runKind = runKind != null ? runKind : RunKind.DEFAULT;
    }

    /**
     * 从配置对象解析 BootstrapConfig
     */
    public static BootstrapConfig fromAgentDefaults(AgentDefaults defaults) {
        BootstrapConfig config = new BootstrapConfig();
        if (defaults == null) {
            return config;
        }
        // 可以从 defaults 中读取配置（如果添加了相应字段）
        return defaults.getBootstrapConfig();
    }
}