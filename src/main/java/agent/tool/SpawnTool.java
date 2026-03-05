package agent.tool;


import agent.SubagentManager;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Spawn tool for creating background subagents.
 * Java port of nanobot/agent/tools/spawn.py
 */
public class SpawnTool extends Tool {

    private final SubagentManager manager;

    private String originChannel = "cli";
    private String originChatId = "direct";
    private String sessionKey = "cli:direct";

    public SpawnTool(SubagentManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager cannot be null");
    }

    /** Set the origin context for subagent announcements. */
    public void setContext(String channel, String chatId) {
        this.originChannel = (channel == null ? "" : channel);
        this.originChatId = (chatId == null ? "" : chatId);
        this.sessionKey = this.originChannel + ":" + this.originChatId;
    }

    @Override
    public String name() {
        return "spawn";
    }

    @Override
    public String description() {
        return "Spawn a subagent to handle a task in the background. "
                + "Use this for complex or time-consuming tasks that can run independently. "
                + "The subagent will complete the task and report back when done.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of(
                                "type", "string",
                                "description", "The task for the subagent to complete"
                        ),
                        "label", Map.of(
                                "type", "string",
                                "description", "Optional short label for the task (for display)"
                        )
                ),
                "required", java.util.List.of("task")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        args = (args == null ? Map.of() : args);

        Object taskObj = args.get("task");
        if (!(taskObj instanceof String) || ((String) taskObj).isBlank()) {
            // ToolRegistry 也会先 validateParams，但这里额外兜底更安全
            return CompletableFuture.completedFuture("Error: task is required");
        }
        String task = (String) taskObj;

        String label = null;
        Object labelObj = args.get("label");
        if (labelObj instanceof String s && !s.isBlank()) {
            label = s;
        }

        return manager.spawn(
                task,
                label,
                originChannel,
                originChatId,
                sessionKey
        );
    }

}