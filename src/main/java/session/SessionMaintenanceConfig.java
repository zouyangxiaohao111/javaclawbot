package session;

/**
 * 会话维护配置
 * 
 * 对齐 OpenClaw 的 session-maintenance 配置
 */
public class SessionMaintenanceConfig {
    
    /** 维护模式：warn（仅警告）或 enforce（执行清理） */
    public enum Mode {
        WARN,
        ENFORCE
    }
    
    /** 默认配置 */
    public static final SessionMaintenanceConfig DEFAULT = new SessionMaintenanceConfig(
            Mode.WARN,
            7 * 24 * 60 * 60 * 1000L,  // 7 天
            100,                        // 最多 100 个会话
            10 * 1024 * 1024L          // 10MB 磁盘预算
    );
    
    private final Mode mode;
    private final long pruneAfterMs;
    private final int maxEntries;
    private final long diskBudgetBytes;
    
    public SessionMaintenanceConfig(Mode mode, long pruneAfterMs, int maxEntries, long diskBudgetBytes) {
        this.mode = mode;
        this.pruneAfterMs = pruneAfterMs;
        this.maxEntries = maxEntries;
        this.diskBudgetBytes = diskBudgetBytes;
    }
    
    public Mode getMode() { return mode; }
    public long getPruneAfterMs() { return pruneAfterMs; }
    public int getMaxEntries() { return maxEntries; }
    public long getDiskBudgetBytes() { return diskBudgetBytes; }
    
    /**
     * 从配置解析维护配置
     */
    public static SessionMaintenanceConfig fromConfig(Object config) {
        // TODO: 从实际配置中解析
        return DEFAULT;
    }
}