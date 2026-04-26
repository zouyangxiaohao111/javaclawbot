package agent.subagent.task;

/**
 * Exception thrown when a task cannot be stopped.
 */
public class StopTaskError extends RuntimeException {

    /**
     * Error codes for StopTaskError.
     */
    public enum Code {
        NOT_FOUND,
        NOT_RUNNING,
        UNSUPPORTED_TYPE
    }

    private final Code code;

    /**
     * Creates a new StopTaskError.
     *
     * @param message the error message
     * @param code    the error code
     */
    public StopTaskError(String message, Code code) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the error code.
     */
    public Code getCode() {
        return code;
    }

    /**
     * Creates a NOT_FOUND error.
     *
     * @param taskId the task ID that was not found
     * @return a new StopTaskError with NOT_FOUND code
     */
    public static StopTaskError notFound(String taskId) {
        return new StopTaskError("Task not found: " + taskId, Code.NOT_FOUND);
    }

    /**
     * Creates a NOT_RUNNING error.
     *
     * @param taskId the task ID that is not running
     * @return a new StopTaskError with NOT_RUNNING code
     */
    public static StopTaskError notRunning(String taskId) {
        return new StopTaskError("Task is not running: " + taskId, Code.NOT_RUNNING);
    }

    /**
     * Creates an UNSUPPORTED_TYPE error.
     *
     * @param taskType the task type that is not supported
     * @return a new StopTaskError with UNSUPPORTED_TYPE code
     */
    public static StopTaskError unsupportedType(String taskType) {
        return new StopTaskError("Unsupported task type: " + taskType, Code.UNSUPPORTED_TYPE);
    }
}
