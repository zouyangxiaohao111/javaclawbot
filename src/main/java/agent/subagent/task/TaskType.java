package agent.subagent.task;

/**
 * Task type enum.
 * 对应 Open-ClaudeCode: src/Task.ts - TaskType (行 3-10)
 *
 * export type TaskType =
 *   | 'local_bash'
 *   | 'local_agent'
 *   | 'remote_agent'
 *   | 'in_process_teammate'
 *   | 'local_workflow'
 *   | 'monitor_mcp'
 *   | 'dream'
 */
public enum TaskType {
    LOCAL_BASH("b"),
    LOCAL_AGENT("a"),
    REMOTE_AGENT("r"),
    IN_PROCESS_TEAMMATE("t"),
    LOCAL_WORKFLOW("w"),
    MONITOR_MCP("m"),
    DREAM("d");

    private final String prefix;

    TaskType(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Returns the prefix used for task ID generation.
     * 对应 Open-ClaudeCode: src/Task.ts - getTaskIdPrefix()
     */
    public String getPrefix() {
        return prefix;
    }
}
