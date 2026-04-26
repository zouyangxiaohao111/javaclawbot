package agent.subagent.task;

/**
 * Task status enum.
 * 对应 Open-ClaudeCode: src/Task.ts - TaskStatus (行 11-16)
 *
 * export type TaskStatus =
 *   | 'pending'
 *   | 'running'
 *   | 'completed'
 *   | 'failed'
 *   | 'killed'
 */
public enum TaskStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    KILLED("killed");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 对应 Open-ClaudeCode: src/Task.ts - isTerminalTaskStatus() (行 19-22)
     *
     * export function isTerminalTaskStatus(status: TaskStatus): boolean {
     *   return status === 'completed' || status === 'failed' || status === 'killed'
     * }
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
