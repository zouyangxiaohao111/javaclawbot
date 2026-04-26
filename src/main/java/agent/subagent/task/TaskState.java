package agent.subagent.task;

import java.time.Instant;

/**
 * Task state base class.
 * 对应 Open-ClaudeCode: src/Task.ts - TaskStateBase (行 38-50)
 *
 * export type TaskStateBase = {
 *   id: string
 *   type: TaskType
 *   status: TaskStatus
 *   description: string
 *   toolUseId?: string
 *   startTime: number
 *   endTime?: number
 *   totalPausedMs?: number
 *   outputFile: string
 *   outputOffset: number
 *   notified: boolean
 * }
 */
public abstract class TaskState {
    protected String id;
    protected TaskType type;
    protected TaskStatus status;
    protected String description;
    protected String toolUseId;
    protected Instant startTime;
    protected Instant endTime;
    protected Long totalPausedMs;
    protected String outputFile;
    protected int outputOffset;
    protected boolean notified;

    protected TaskState() {
    }

    // =====================
    // Getters
    // =====================

    public String getId() {
        return id;
    }

    public TaskType getType() {
        return type;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Long getTotalPausedMs() {
        return totalPausedMs;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public int getOutputOffset() {
        return outputOffset;
    }

    public boolean isNotified() {
        return notified;
    }

    // =====================
    // Setters
    // =====================

    public void setId(String id) {
        this.id = id;
    }

    public void setType(TaskType type) {
        this.type = type;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public void setTotalPausedMs(Long totalPausedMs) {
        this.totalPausedMs = totalPausedMs;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
    }

    public void setOutputOffset(int outputOffset) {
        this.outputOffset = outputOffset;
    }

    public void setNotified(boolean notified) {
        this.notified = notified;
    }

    // =====================
    // Computed methods
    // =====================

    /**
     * 对应 Open-ClaudeCode: src/Task.ts - isTerminalTaskStatus()
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * 计算运行时长（毫秒）
     */
    public long getRuntimeMs() {
        if (startTime == null) return 0;
        Instant end = endTime != null ? endTime : Instant.now();
        return java.time.Duration.between(startTime, end).toMillis();
    }

    /**
     * 创建状态副本（用于不可变更新）
     * 子类必须实现此方法
     */
    public abstract TaskState copy();
}
