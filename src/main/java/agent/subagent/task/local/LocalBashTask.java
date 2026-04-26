package agent.subagent.task.local;

import agent.subagent.task.Task;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskType;
import agent.subagent.task.TaskStateUpdater;
import agent.subagent.task.TaskStatus;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * LocalBashTask implementation for local bash command tasks (LOCAL_BASH type).
 * 对应 Open-ClaudeCode: src/tasks/LocalShellTask/LocalShellTask.tsx - LocalShellTask
 *
 * export const LocalShellTask: Task = {
 *   name: 'LocalShellTask',
 *   type: 'local_bash',
 *   async kill(taskId, setAppState) {
 *     killTask(taskId, setAppState);
 *   }
 * };
 */
public class LocalBashTask implements Task {

    @Override
    public String name() {
        return "LocalBashTask";
    }

    @Override
    public TaskType type() {
        return TaskType.LOCAL_BASH;
    }

    @Override
    public CompletableFuture<Void> kill(String taskId, AppState.Setter setAppState) {
        return CompletableFuture.runAsync(() -> {
            TaskStateUpdater.updateTaskState(taskId, setAppState, taskState -> {
                if (taskState instanceof LocalBashTaskState) {
                    LocalBashTaskState bashState = (LocalBashTaskState) taskState;

                    // Destroy the process forcibly
                    bashState.destroyProcess();

                    // Mark as killed
                    bashState.setStatus(TaskStatus.KILLED);
                    bashState.setEndTime(Instant.now());
                    bashState.setDestroyed(true);

                    // Set abort controller if present
                    if (bashState.getAbortController() != null) {
                        bashState.getAbortController().set(true);
                    }
                }
                return taskState;
            });
        });
    }
}
