package agent.subagent.task;

/**
 * Result of stopping a task.
 */
public class StopResult {

    private final String taskId;
    private final String taskType;
    private final String command;

    /**
     * Creates a new StopResult.
     *
     * @param taskId   the ID of the stopped task
     * @param taskType the type of the stopped task
     * @param command  the command that was stopped (may be null)
     */
    public StopResult(String taskId, String taskType, String command) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.command = command;
    }

    /**
     * Returns the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Returns the task type.
     */
    public String getTaskType() {
        return taskType;
    }

    /**
     * Returns the command that was stopped.
     *
     * @return the command, or null if not applicable
     */
    public String getCommand() {
        return command;
    }

    @Override
    public String toString() {
        return "StopResult{" +
                "taskId='" + taskId + '\'' +
                ", taskType='" + taskType + '\'' +
                ", command='" + command + '\'' +
                '}';
    }
}
