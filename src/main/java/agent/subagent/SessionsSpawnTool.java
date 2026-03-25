package agent.subagent;

import agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 子Agent生成工具（LLM Tool 层）
 *
 * 职责：解析 LLM 传入的参数，委托给 SubagentManager.spawn()。
 * 不直接操作 LocalSubagentExecutor、SubagentRegistry 等底层组件。
 *
 * @author zcw
 */
public class SessionsSpawnTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(SessionsSpawnTool.class);

    private final SubagentManager manager;

    private String agentSessionKey;
    private String agentChannel;
    private String agentChatId;

    public SessionsSpawnTool(SubagentManager manager) {
        this.manager = manager;
    }

    public void setContext(String sessionKey, String channel, String chatId) {
        this.agentSessionKey = sessionKey;
        this.agentChannel = channel;
        this.agentChatId = chatId;
    }

    @Override
    public String name() {
        return "sessions_spawn";
    }

    @Override
    public String description() {
        return "Spawn an isolated sub-agent session. mode=\"run\" for one-shot, mode=\"session\" for persistent. Sub-agents inherit parent workspace.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> modeEnum = new LinkedHashMap<>();
        modeEnum.put("type", "string");
        modeEnum.put("enum", List.of("run", "session"));
        modeEnum.put("default", "run");

        Map<String, Object> cleanupEnum = new LinkedHashMap<>();
        cleanupEnum.put("type", "string");
        cleanupEnum.put("enum", List.of("delete", "keep"));
        cleanupEnum.put("default", "keep");

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of(
                                "type", "string",
                                "description", "The task for the sub-agent to complete"
                        ),
                        "label", Map.of(
                                "type", "string",
                                "description", "Optional short label for the task (for display)"
                        ),
                        "mode", modeEnum,
                        "cleanup", cleanupEnum,
                        "model", Map.of(
                                "type", "string",
                                "description", "Optional model override for the subagent"
                        ),
                        "thinking", Map.of(
                                "type", "string",
                                "description", "Thinking level: low, medium, high"
                        ),
                        "runTimeoutSeconds", Map.of(
                                "type", "integer",
                                "description", "Timeout in seconds (default 300)",
                                "minimum", 10
                        )
                ),
                "required", List.of("task")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        if (args == null) args = Map.of();

        String task = getString(args, "task", null);
        if (task == null || task.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"status\":\"error\",\"error\":\"task is required\"}"
            );
        }

        String label = getString(args, "label", null);
        String modeStr = getString(args, "mode", "run");
        String cleanupStr = getString(args, "cleanup", "keep");
        Integer timeoutSeconds = getInt(args, "runTimeoutSeconds", 300);

        SubagentRunRecord.SpawnMode mode = "session".equalsIgnoreCase(modeStr)
                ? SubagentRunRecord.SpawnMode.SESSION
                : SubagentRunRecord.SpawnMode.RUN;

        SubagentRunRecord.CleanupPolicy cleanup = "delete".equalsIgnoreCase(cleanupStr)
                ? SubagentRunRecord.CleanupPolicy.DELETE
                : SubagentRunRecord.CleanupPolicy.KEEP;

        log.info("SessionsSpawnTool: task={}, label={}, mode={}", task, label, mode);

        return manager.spawn(
                task,
                label,
                agentChannel,
                agentChatId,
                agentSessionKey,
                mode,
                cleanup,
                timeoutSeconds
        );
    }

    private String getString(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    private Integer getInt(Map<String, Object> args, String key, Integer defaultValue) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }
}
