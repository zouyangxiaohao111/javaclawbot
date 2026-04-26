package agent.subagent.task.local;

import agent.subagent.task.Task;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskType;
import agent.subagent.task.TaskControlService;
import agent.subagent.task.TaskRegistry;

import java.util.concurrent.CompletableFuture;

/**
 * ForkTask implementation for local agent tasks (LOCAL_AGENT type).
 * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - LocalAgentTask
 *
 * export const LocalAgentTask: Task = {
 *   name: 'LocalAgentTask',
 *   type: 'local_agent',
 *   async kill(taskId, setAppState) {
 *     killAsyncAgent(taskId, setAppState);
 *   }
 * };
 */
public class ForkTask implements Task {

    private final TaskControlService taskControlService;

    public ForkTask() {
        this.taskControlService = new TaskControlService(TaskRegistry.getInstance());
    }

    public ForkTask(TaskControlService taskControlService) {
        this.taskControlService = taskControlService;
    }

    @Override
    public String name() {
        return "ForkTask";
    }

    @Override
    public TaskType type() {
        return TaskType.LOCAL_AGENT;
    }

    @Override
    public CompletableFuture<Void> kill(String taskId, AppState.Setter setAppState) {
        return CompletableFuture.runAsync(() -> {
            taskControlService.killAsyncAgent(taskId, setAppState);
        });
    }
}
