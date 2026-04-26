package agent.tool.task;

import agent.subagent.task.AppState;
import agent.subagent.task.StopResult;
import agent.subagent.task.StopTaskError;
import agent.subagent.task.TaskControlService;
import agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Task stop tool - allows agents to stop running tasks.
 *
 * This tool provides the ability to stop a running agent or task by its ID.
 * It delegates to TaskControlService for the actual stop operation.
 */
public class TaskStopTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(TaskStopTool.class);

    private final TaskControlService taskControlService;
    private final AppState.Getter getAppState;
    private final AppState.Setter setAppState;

    /**
     * Creates a new TaskStopTool.
     *
     * @param taskControlService the task control service
     * @param getAppState       the app state getter
     * @param setAppState       the app state setter
     */
    public TaskStopTool(
            TaskControlService taskControlService,
            AppState.Getter getAppState,
            AppState.Setter setAppState
    ) {
        this.taskControlService = taskControlService;
        this.getAppState = getAppState;
        this.setAppState = setAppState;
    }

    @Override
    public String name() {
        return "TaskStopTool";
    }

    @Override
    public String description() {
        return "Stop a running agent or task. Only one of `taskId` may be specified. Cannot be undone.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of(
                                "type", "string",
                                "description", "The ID of the task to stop"
                        )
                ),
                "required", List.of("taskId")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        if (args == null) args = Map.of();

        String taskId = getString(args, "taskId", null);
        if (taskId == null || taskId.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"status\":\"error\",\"error\":\"taskId is required\"}"
            );
        }

        log.info("TaskStopTool: stopping task {}", taskId);

        try {
            StopResult result = taskControlService.stopTask(taskId, getAppState, setAppState);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "ok");
            output.put("taskId", result.getTaskId());
            output.put("taskType", result.getTaskType());
            if (result.getCommand() != null) {
                output.put("command", result.getCommand());
            }
            output.put("text", "Task stopped: " + taskId);

            return CompletableFuture.completedFuture(toJson(output));

        } catch (StopTaskError e) {
            log.warn("Failed to stop task {}: {}", taskId, e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("error", e.getMessage());
            error.put("code", e.getCode().name());
            return CompletableFuture.completedFuture(toJson(error));

        } catch (Exception e) {
            log.error("Error stopping task {}", taskId, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("error", "Failed to stop task: " + e.getMessage());
            return CompletableFuture.completedFuture(toJson(error));
        }
    }

    private String getString(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
