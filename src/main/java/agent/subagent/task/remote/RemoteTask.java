package agent.subagent.task.remote;

import agent.subagent.task.Task;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskType;
import agent.subagent.task.TaskStateUpdater;
import agent.subagent.task.TaskStatus;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * RemoteTask implementation for remote agent tasks (REMOTE_AGENT type).
 * 对应 Open-ClaudeCode: src/tasks/RemoteAgentTask/RemoteAgentTask.tsx - RemoteAgentTask
 *
 * export const RemoteAgentTask: Task = {
 *   name: 'RemoteAgentTask',
 *   type: 'remote_agent',
 *   async kill(taskId, setAppState) {
 *     let toolUseId: string | undefined;
 *     let description: string | undefined;
 *     let sessionId: string | undefined;
 *     let killed = false;
 *     updateTaskState<RemoteAgentTaskState>(taskId, setAppState, task => {
 *       if (task.status !== 'running') {
 *         return task;
 *       }
 *       toolUseId = task.toolUseId;
 *       description = task.description;
 *       sessionId = task.sessionId;
 *       killed = true;
 *       return {
 *         ...task,
 *         status: 'killed',
 *         notified: true,
 *         endTime: Date.now()
 *       };
 *     });
 *     // 关闭远程会话等后续处理...
 *   }
 * };
 */
public class RemoteTask implements Task {

    @Override
    public String name() {
        return "RemoteTask";
    }

    @Override
    public TaskType type() {
        return TaskType.REMOTE_AGENT;
    }

    @Override
    public CompletableFuture<Void> kill(String taskId, AppState.Setter setAppState) {
        return CompletableFuture.runAsync(() -> {
            TaskStateUpdater.updateTaskState(taskId, setAppState, taskState -> {
                if (taskState != null) {
                    if (taskState.getStatus() != TaskStatus.RUNNING) {
                        return taskState;
                    }
                    taskState.setStatus(TaskStatus.KILLED);
                    taskState.setEndTime(Instant.now());
                    taskState.setNotified(true);
                }
                return taskState;
            });
        });
    }
}
