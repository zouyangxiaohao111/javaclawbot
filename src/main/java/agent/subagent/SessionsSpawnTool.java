package agent.subagent;

import agent.tool.Tool;
import bus.MessageBus;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 增强版子Agent生成工具
 *
 * 对应 OpenClaw: src/agents/tools/sessions-spawn-tool.ts
 *
 * 功能：
 * - 支持多种运行模式（run/session）
 * - 支持深度控制
 * - 支持清理策略
 * - 支持模型选择
 * - 自动注册到SubagentRegistry
 */
public class SessionsSpawnTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(SessionsSpawnTool.class);

    private final SubagentRegistry registry;
    private final SubagentAnnounceService announceService;
    private final SubagentSystemPromptBuilder promptBuilder;
    private final SubagentExecutor executor;
    private final Path workspace;
    private final MessageBus messageBus;

    private String agentSessionKey;
    private String agentChannel;
    private String agentChatId;

    public SessionsSpawnTool(
            SubagentRegistry registry,
            SubagentAnnounceService announceService,
            SubagentSystemPromptBuilder promptBuilder,
            SubagentExecutor executor,
            Path workspace,
            MessageBus messageBus
    ) {
        this.registry = registry;
        this.announceService = announceService;
        this.promptBuilder = promptBuilder;
        this.executor = executor;
        this.workspace = workspace;
        this.messageBus = messageBus;
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
        return "生成一个隔离会话（子代理）。mode=\"run\" 是一次性执行，mode=\"session\" 是持久化/线程绑定模式。" +
                "子代理自动继承父工作区目录。";
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
                                "description", "子代理要完成的任务"
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
                                "description", "思考级别: low, medium, high"
                        ),
                        "runTimeoutSeconds", Map.of(
                                "type", "integer",
                                "description", "超时秒数（默认 300）",
                                "minimum", 10
                        )
                ),
                "required", List.of("task")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        if (args == null) args = Map.of();


        // 解析参数
        String task = getString(args, "task", null);
        log.info("子代理开始启动, 具体参数: {}", new Gson().toJson(args));
        if (task == null || task.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"status\":\"error\",\"error\":\"task is required\"}"
            );
        }

        String label = getString(args, "label", null);
        String modeStr = getString(args, "mode", "run");
        String cleanupStr = getString(args, "cleanup", "keep");
        //String model = getString(args, "model", null);
        String thinking = getString(args, "thinking", null);
        Integer timeoutSeconds = getInt(args, "runTimeoutSeconds", 300);

        SubagentRunRecord.SpawnMode mode = "session".equalsIgnoreCase(modeStr)
                ? SubagentRunRecord.SpawnMode.SESSION
                : SubagentRunRecord.SpawnMode.RUN;

        SubagentRunRecord.CleanupPolicy cleanup = "delete".equalsIgnoreCase(cleanupStr)
                ? SubagentRunRecord.CleanupPolicy.DELETE
                : SubagentRunRecord.CleanupPolicy.KEEP;

        // 检查深度限制
        String requesterKey = agentSessionKey != null ? agentSessionKey : "cli:direct";
        int childDepth = registry.computeChildDepth(requesterKey);
        int maxDepth = registry.getMaxSpawnDepth();

        if (childDepth > maxDepth) {
            return CompletableFuture.completedFuture(
                    String.format("{\"status\":\"forbidden\",\"error\":\"Maximum spawn depth (%d) exceeded\"}", maxDepth)
            );
        }

        // 生成运行ID和会话Key
        String runId = generateRunId();
        String childSessionKey = generateChildSessionKey(runId);

        // 创建运行记录
        SubagentRunRecord record = new SubagentRunRecord(
                runId,
                childSessionKey,
                requesterKey,
                label,
                task,
                cleanup,
                mode,
                childDepth
        );
        record.setModel(mode.name().toLowerCase());
        record.setRunTimeoutSeconds(timeoutSeconds);
        record.setWorkspaceDir(workspace.toString());
        record.setExpectsCompletionMessage(true);

        // 注册到Registry
        registry.register(record);

        log.info("启动子代理: {} (深度: {}, 模式: {})", runId, childDepth, mode);

        // 构建子Agent系统提示词
        SubagentSystemPromptBuilder.Params promptParams = new SubagentSystemPromptBuilder.Params()
                .task(task)
                .label(label)
                .requesterSessionKey(requesterKey)
                .requesterChannel(agentChannel)
                .childSessionKey(childSessionKey)
                .childDepth(childDepth)
                .maxSpawnDepth(maxDepth);

        String systemPrompt = promptBuilder.build(promptParams);

        // 异步执行子Agent
        CompletableFuture<Void> execution = executor.execute(record, systemPrompt)
                .thenAccept(result -> {
                    // 更新记录
                    record.setOutcome(result.getOutcome());
                    record.setFrozenResultText(result.getResultText());

                    // 发送完成公告
                    announceService.announceWithRetry(record);
                })
                .exceptionally(ex -> {
                    log.error("子代理执行失败: {}", runId, ex);
                    record.setOutcome(SubagentOutcome.error(ex.getMessage()));
                    announceService.announceWithRetry(record);
                    return null;
                }).toCompletableFuture();

        // 立即返回接受状态
        String displayLabel = (label != null && !label.isBlank()) ? label : truncate(task, 30);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "accepted");
        result.put("runId", runId);
        result.put("childSessionKey", childSessionKey);
        result.put("mode", mode.name().toLowerCase());
        result.put("depth", childDepth);
        result.put("note", "自动公告是推送模式。启动子代理后，不要调用 sessions_list、sessions_history、exec sleep 或任何轮询工具。等待完成事件作为用户消息到达。");
        result.put("text", String.format("子代理 [%s] 已启动 (id: %s)。完成时会通知您。", displayLabel, runId));

        return CompletableFuture.completedFuture(toJson(result));
    }

    // ==========================
    // 辅助方法
    // ==========================

    private String generateRunId() {
        return "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String generateChildSessionKey(String runId) {
        String base = agentSessionKey != null ? agentSessionKey : "cli:direct";
        return base + ":" + runId;
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

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}