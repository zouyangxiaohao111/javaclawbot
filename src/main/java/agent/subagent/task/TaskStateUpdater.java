package agent.subagent.task;

import java.util.Map;
import java.util.function.Function;

/**
 * Task state updater.
 * 对应 Open-ClaudeCode: src/utils/task/framework.ts - updateTaskState()
 *
 * export function updateTaskState<T extends TaskState>(
 *   taskId: string,
 *   setAppState: SetAppState,
 *   updater: (task: T) => T,
 * ): void {
 *   setAppState(prev => {
 *     const task = prev.tasks?.[taskId] as T | undefined
 *     if (!task) {
 *       return prev
 *     }
 *     const updated = updater(task)
 *     if (updated === task) {
 *       return prev
 *     }
 *     return {
 *       ...prev,
 *       tasks: {
 *         ...prev.tasks,
 *         [taskId]: updated,
 *       },
 *     }
 *   })
 * }
 */
public class TaskStateUpdater {

    /**
     * Updates task state immutably.
     *
     * @param taskId      the ID of the task to update
     * @param setAppState the AppState setter
     * @param updater     the function to apply to the task state
     * @param <T>         the task state type
     */
    @SuppressWarnings("unchecked")
    public static <T extends TaskState> void updateTaskState(
            String taskId,
            AppState.Setter setAppState,
            Function<T, T> updater
    ) {
        setAppState.accept(prev -> {
            Map<String, TaskState> tasks = prev.getTasks();
            if (tasks == null) {
                return prev;
            }

            T task = (T) tasks.get(taskId);
            if (task == null) {
                return prev;
            }

            T updated = updater.apply(task);
            if (updated == task) {
                return prev;
            }

            // Create new tasks map with updated task
            Map<String, TaskState> newTasks = new java.util.HashMap<>(tasks);
            newTasks.put(taskId, updated);

            // Create new AppState
            AppState newState = new AppState();
            newState.setTasks(newTasks);
            newState.setAgentNameRegistry(new java.util.HashMap<>(prev.getAgentNameRegistry()));

            return newState;
        });
    }
}
