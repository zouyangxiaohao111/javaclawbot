package agent.subagent.task;

import agent.subagent.task.local.LocalAgentTaskState;
import agent.subagent.task.local.LocalBashTaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Task control service.
 * 对应 Open-ClaudeCode:
 * - src/tasks/stopTask.ts - stopTask()
 * - src/tasks/LocalAgentTask.tsx - killAllRunningAgentTasks(), killAsyncAgent()
 */
public class TaskControlService {

    private static final Logger log = LoggerFactory.getLogger(TaskControlService.class);

    /**
     * PANEL_GRACE_MS - 面板保留时间（毫秒）
     * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask.tsx 中的 PANEL_GRACE_MS
     */
    private static final long PANEL_GRACE_MS = 5000;

    private final TaskRegistry taskRegistry;

    public TaskControlService(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    /**
     * Stop a task by ID.
     * 对应 Open-ClaudeCode: src/tasks/stopTask.ts - stopTask()
     *
     * export async function stopTask(
     *   taskId: string,
     *   context: StopTaskContext,
     * ): Promise<StopTaskResult> {
     *   const { getAppState, setAppState } = context
     *   const appState = getAppState()
     *   const task = appState.tasks?.[taskId] as TaskStateBase | undefined
     *
     *   if (!task) {
     *     throw new StopTaskError(`No task found with ID: ${taskId}`, 'not_found')
     *   }
     *
     *   if (task.status !== 'running') {
     *     throw new StopTaskError(
     *       `Task ${taskId} is not running (status: ${task.status})`,
     *       'not_running',
     *     )
     *   }
     *
     *   const taskImpl = getTaskByType(task.type)
     *   if (!taskImpl) {
     *     throw new StopTaskError(
     *       `Unsupported task type: ${task.type}`,
     *       'unsupported_type',
     *     )
     *   }
     *
     *   await taskImpl.kill(taskId, setAppState)
     *
     *   // Bash: suppress the "exit code 137" notification (noise)...
     *   if (isLocalShellTask(task)) {
     *     // ... suppress notification logic
     *   }
     *
     *   const command = isLocalShellTask(task) ? task.command : task.description
     *
     *   return { taskId, taskType: task.type, command }
     * }
     */
    public StopResult stopTask(String taskId, AppState.Getter getAppState, AppState.Setter setAppState) {
        AppState appState = getAppState.get();
        Map<String, TaskState> tasks = appState.getTasks();

        TaskState taskState = tasks != null ? tasks.get(taskId) : null;

        if (taskState == null) {
            throw StopTaskError.notFound(taskId);
        }

        if (taskState.getStatus() != TaskStatus.RUNNING) {
            throw StopTaskError.notRunning(taskId);
        }

        Task taskImpl = taskRegistry.getTaskByType(taskState.getType());
        if (taskImpl == null) {
            throw StopTaskError.unsupportedType(taskState.getType().name());
        }

        // Kill the task
        taskImpl.kill(taskId, setAppState).join();

        // Bash: suppress the "exit code 137" notification (noise)
        if (taskState instanceof LocalBashTaskState) {
            LocalBashTaskState bashState = (LocalBashTaskState) taskState;
            bashState.setNotified(true);
        }

        // Return result
        String command = (taskState instanceof LocalBashTaskState)
                ? ((LocalBashTaskState) taskState).getCommand()
                : taskState.getDescription();

        return new StopResult(taskId, taskState.getType().name(), command);
    }

    /**
     * Kill a single async agent task.
     * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask.tsx - killAsyncAgent()
     *
     * export function killAsyncAgent(taskId: string, setAppState: SetAppState): void {
     *   let killed = false;
     *   updateTaskState<LocalAgentTaskState>(taskId, setAppState, task => {
     *     if (task.status !== 'running') {
     *       return task;
     *     }
     *     killed = true;
     *     task.abortController?.abort();
     *     task.unregisterCleanup?.();
     *     return {
     *       ...task,
     *       status: 'killed',
     *       endTime: Date.now(),
     *       evictAfter: task.retain ? undefined : Date.now() + PANEL_GRACE_MS,
     *       abortController: undefined,
     *       unregisterCleanup: undefined,
     *       selectedAgent: undefined
     *     };
     *   });
     *   if (killed) {
     *     void evictTaskOutput(taskId);
     *   }
     * }
     */
    public void killAsyncAgent(String taskId, AppState.Setter setAppState) {
        final boolean[] killed = {false};

        TaskStateUpdater.updateTaskState(taskId, setAppState, task -> {
            if (!(task instanceof LocalAgentTaskState)) {
                return task;
            }

            LocalAgentTaskState agentState = (LocalAgentTaskState) task;

            if (agentState.getStatus() != TaskStatus.RUNNING) {
                return task;
            }

            killed[0] = true;

            // Abort the controller
            if (agentState.getAbortController() != null) {
                agentState.getAbortController().set(true);
            }

            // Run unregister cleanup
            if (agentState.getUnregisterCleanup() != null) {
                agentState.getUnregisterCleanup().run();
            }

            // Create updated state
            LocalAgentTaskState updated = (LocalAgentTaskState) task.copy();
            updated.setStatus(TaskStatus.KILLED);
            updated.setEndTime(Instant.now());
            updated.setEvictAfter(agentState.isRetain() ? null : System.currentTimeMillis() + PANEL_GRACE_MS);
            updated.setAbortController(null);
            updated.setUnregisterCleanup(null);
            updated.setSelectedAgent(null);

            return updated;
        });

        if (killed[0]) {
            evictTaskOutput(taskId);
        }
    }

    /**
     * Kill all running agent tasks.
     * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask.tsx - killAllRunningAgentTasks()
     *
     * export function killAllRunningAgentTasks(tasks: Record<string, TaskState>, setAppState: SetAppState): void {
     *   for (const [taskId, task] of Object.entries(tasks)) {
     *     if (task.type === 'local_agent' && task.status === 'running') {
     *       killAsyncAgent(taskId, setAppState);
     *     }
     *   }
     * }
     *
     * @return the number of tasks that were killed
     */
    public int killAllAgentTasks(AppState state, AppState.Setter setAppState) {
        Map<String, TaskState> tasks = state.getTasks();
        if (tasks == null) return 0;

        int killedCount = 0;
        for (Map.Entry<String, TaskState> entry : tasks.entrySet()) {
            String taskId = entry.getKey();
            TaskState task = entry.getValue();

            if (task.getType() == TaskType.LOCAL_AGENT && task.getStatus() == TaskStatus.RUNNING) {
                killAsyncAgent(taskId, setAppState);
                killedCount++;
            }
        }
        return killedCount;
    }

    /**
     * Evict task output (cleanup).
     * 对应 Open-ClaudeCode: evictTaskOutput()
     */
    private void evictTaskOutput(String taskId) {
        log.debug("Evicting task output for: {}", taskId);
        // 清理任务输出缓存 - 仅记录日志，实际清理由 TaskRegistry 内部管理
        log.debug("Task output evicted for: {}", taskId);
    }
}
