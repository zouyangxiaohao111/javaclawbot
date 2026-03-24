package agent.subagent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子Agent运行记录
 *
 * 对应 OpenClaw: src/agents/subagent-registry.types.ts - SubagentRunRecord
 *
 * 记录子Agent的完整生命周期信息，包括：
 * - 运行标识（runId, childSessionKey）
 * - 任务信息（task, label）
 * - 时间戳（createdAt, startedAt, endedAt）
 * - 执行结果（outcome）
 * - 清理策略（cleanup）
 * - 深度控制（depth）
 */
@Data
public class SubagentRunRecord {

    /** 运行ID（唯一标识） */
    private final String runId;

    /** 子会话标识 */
    private final String childSessionKey;

    /** 父会话标识（请求者） */
    private final String requesterSessionKey;

    /** 展示用标签 */
    private final String label;

    /** 任务描述 */
    private final String task;

    /** 清理策略：delete（完成后删除）或 keep（保留） */
    private final CleanupPolicy cleanup;

    /** 运行模式：run（一次性）或 session（持久会话） */
    private final SpawnMode spawnMode;

    /** 嵌套深度（1=子Agent, 2=子子Agent...） */
    private final int depth;

    /** 创建时间 */
    private final LocalDateTime createdAt;

    /** 启动时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime endedAt;

    /** 执行结果 */
    private SubagentOutcome outcome;

    /** 冻结的结果文本（用于公告） */
    private String frozenResultText;

    /** 是否期望完成消息 */
    private boolean expectsCompletionMessage;

    /** 工作目录 */
    private String workspaceDir;

    /** 模型 */
    private String model;

    /** 超时秒数 */
    private Integer runTimeoutSeconds;

    /** 附加元数据 */
    private Map<String, Object> metadata;

    public enum CleanupPolicy {
        DELETE,
        KEEP
    }

    public enum SpawnMode {
        RUN,      // 一次性执行
        SESSION   // 持久会话
    }

    public SubagentRunRecord(
            String runId,
            String childSessionKey,
            String requesterSessionKey,
            String label,
            String task,
            CleanupPolicy cleanup,
            SpawnMode spawnMode,
            int depth
    ) {
        this.runId = runId;
        this.childSessionKey = childSessionKey;
        this.requesterSessionKey = requesterSessionKey;
        this.label = label;
        this.task = task;
        this.cleanup = cleanup != null ? cleanup : CleanupPolicy.KEEP;
        this.spawnMode = spawnMode != null ? spawnMode : SpawnMode.RUN;
        this.depth = Math.max(1, depth);
        this.createdAt = LocalDateTime.now();
        this.metadata = new ConcurrentHashMap<>();
    }

    // ==========================
    // 状态判断方法
    // ==========================

    /** 是否正在运行 */
    public boolean isRunning() {
        return startedAt != null && endedAt == null;
    }

    /** 是否已完成 */
    public boolean isCompleted() {
        return endedAt != null;
    }

    /** 是否成功完成 */
    public boolean isSuccess() {
        return outcome != null && outcome.getStatus() == SubagentOutcome.Status.OK;
    }

    /** 是否超时 */
    public boolean isTimeout() {
        return outcome != null && outcome.getStatus() == SubagentOutcome.Status.TIMEOUT;
    }

    /** 是否出错 */
    public boolean isError() {
        return outcome != null && outcome.getStatus() == SubagentOutcome.Status.ERROR;
    }

    /** 是否可以继续spawn子Agent */
    public boolean canSpawn(int maxDepth) {
        return depth < maxDepth;
    }

    /** 运行时长（毫秒） */
    public long getRuntimeMs() {
        if (startedAt == null) return 0;
        LocalDateTime end = endedAt != null ? endedAt : LocalDateTime.now();
        return java.time.Duration.between(startedAt, end).toMillis();
    }

    // ==========================
    // Getters & Setters
    // ==========================



    /**
     * 转换为摘要Map（用于工具输出）
     */
    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("runId", runId);
        map.put("sessionKey", childSessionKey);
        map.put("label", label != null ? label : truncate(task, 30));
        map.put("task", truncate(task, 100));
        map.put("status", getStatusText());
        map.put("depth", depth);
        map.put("runtime", formatRuntime(getRuntimeMs()));
        if (model != null) map.put("model", model);
        return map;
    }

    private String getStatusText() {
        if (endedAt == null) return "running";
        if (outcome == null) return "unknown";
        return outcome.getStatus().name().toLowerCase();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String formatRuntime(long ms) {
        if (ms <= 0) return "n/a";
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return hours + "h" + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m" + (seconds % 60) + "s";
        return seconds + "s";
    }
}