package agent.subagent.task;

import agent.subagent.task.local.ForkTask;
import agent.subagent.task.local.LocalBashTask;
import agent.subagent.task.remote.RemoteTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task registry.
 * 对应 Open-ClaudeCode: src/tasks.ts - getAllTasks(), getTaskByType()
 *
 * export function getAllTasks(): Task[] {
 *   const tasks: Task[] = [
 *     LocalShellTask,
 *     LocalAgentTask,
 *     RemoteAgentTask,
 *     DreamTask,
 *   ]
 *   if (LocalWorkflowTask) tasks.push(LocalWorkflowTask)
 *   if (MonitorMcpTask) tasks.push(MonitorMcpTask)
 *   return tasks
 * }
 *
 * export function getTaskByType(type: TaskType): Task | undefined {
 *   return getAllTasks().find(t => t.type === type)
 * }
 */
public class TaskRegistry {
    private static final TaskRegistry INSTANCE = new TaskRegistry();
    private final Map<TaskType, List<Task>> tasksByType = new ConcurrentHashMap<>();

    private TaskRegistry() {
        // Initialize empty lists for each task type
        for (TaskType type : TaskType.values()) {
            tasksByType.put(type, new ArrayList<>());
        }

        // Register built-in task implementations
        register(new ForkTask());
        register(new LocalBashTask());
        register(new RemoteTask());
    }

    /**
     * Returns the singleton instance of TaskRegistry.
     */
    public static TaskRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a task with the registry.
     *
     * @param task the task to register
     */
    public void register(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task cannot be null");
        }
        TaskType type = task.type();
        List<Task> tasks = tasksByType.computeIfAbsent(type, k -> new ArrayList<>());
        synchronized (tasks) {
            tasks.add(task);
        }
    }

    /**
     * Returns all registered tasks.
     * 对应 Open-ClaudeCode: getAllTasks()
     *
     * @return a list of all registered tasks
     */
    public List<Task> getAllTasks() {
        List<Task> all = new ArrayList<>();
        for (List<Task> tasks : tasksByType.values()) {
            all.addAll(tasks);
        }
        return all;
    }

    /**
     * Returns the first task of the specified type.
     * 对应 Open-ClaudeCode: getTaskByType() - returns single Task | undefined
     *
     * @param type the task type to filter by
     * @return the task matching the specified type, or null if not found
     */
    public Task getTaskByType(TaskType type) {
        if (type == null) {
            throw new IllegalArgumentException("TaskType cannot be null");
        }
        List<Task> tasks = tasksByType.get(type);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        return tasks.get(0);
    }

    /**
     * Returns all tasks of the specified type.
     *
     * @param type the task type to filter by
     * @return a list of tasks matching the specified type
     */
    public List<Task> getTasksByType(TaskType type) {
        if (type == null) {
            throw new IllegalArgumentException("TaskType cannot be null");
        }
        List<Task> tasks = tasksByType.get(type);
        return tasks != null ? new ArrayList<>(tasks) : new ArrayList<>();
    }

    /**
     * Clears all registered tasks.
     */
    public void clear() {
        tasksByType.values().forEach(List::clear);
    }
}
