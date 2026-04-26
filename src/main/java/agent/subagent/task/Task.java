package agent.subagent.task;

import java.util.concurrent.CompletableFuture;

/**
 * Task interface for all task implementations.
 */
public interface Task {
    /**
     * Returns the name of this task.
     */
    String name();

    /**
     * Returns the type of this task.
     */
    TaskType type();

    /**
     * Kills the task with the given ID.
     *
     * @param taskId      the ID of the task to kill
     * @param setAppState the AppState setter to update state
     * @return a CompletableFuture that completes when the task is killed
     */
    CompletableFuture<Void> kill(String taskId, AppState.Setter setAppState);
}
