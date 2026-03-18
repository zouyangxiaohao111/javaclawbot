package agent.subagent;

import agent.tool.Tool;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * 子Agent控制工具
 *
 * 对应 OpenClaw: src/agents/tools/subagents-tool.ts
 *
 * 功能：
 * - list: 列出子Agent运行
 * - kill: 终止子Agent运行
 * - steer: 向运行中的子Agent发送指导消息
 */
public class SubagentsControlTool extends Tool {

    private static final int DEFAULT_RECENT_MINUTES = 30;
    private static final int MAX_RECENT_MINUTES = 24 * 60;
    private static final int MAX_STEER_MESSAGE_CHARS = 4_000;

    private final SubagentRegistry registry;
    private final SubagentController controller;
    private String agentSessionKey;

    public SubagentsControlTool(SubagentRegistry registry, SubagentController controller) {
        this.registry = registry;
        this.controller = controller;
    }

    public void setAgentSessionKey(String sessionKey) {
        this.agentSessionKey = sessionKey;
    }

    @Override
    public String name() {
        return "subagents_control";
    }

    @Override
    public String description() {
        return "List, kill, or steer spawned sub-agents for this requester session. Use this for sub-agent orchestration. Control sub-agents.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> actionEnum = new LinkedHashMap<>();
        actionEnum.put("type", "string");
        actionEnum.put("enum", List.of("list", "kill", "steer"));
        actionEnum.put("default", "list");

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", actionEnum,
                        "target", Map.of(
                                "type", "string",
                                "description", "目标子代理 runId 或 'all' 用于 kill 操作"
                        ),
                        "message", Map.of(
                                "type", "string",
                                "description", "发送给子代理的引导消息（最多 4000 字符）"
                        ),
                        "recentMinutes", Map.of(
                                "type", "integer",
                                "description", "查看最近多少分钟内的子代理（默认 30）",
                                "minimum", 1
                        )
                )
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        if (args == null) args = Map.of();

        String action = getString(args, "action", "list");
        String target = getString(args, "target", null);
        String message = getString(args, "message", null);
        int recentMinutes = getInt(args, "recentMinutes", DEFAULT_RECENT_MINUTES);
        recentMinutes = Math.max(1, Math.min(MAX_RECENT_MINUTES, recentMinutes));

        String requesterKey = agentSessionKey != null ? agentSessionKey : "cli:direct";

        switch (action.toLowerCase()) {
            case "list":
                return executeList(requesterKey, recentMinutes);
            case "kill":
                return executeKill(requesterKey, target);
            case "steer":
                return executeSteer(requesterKey, target, message);
            default:
                return CompletableFuture.completedFuture(
                        "{\"status\":\"error\",\"error\":\"不支持的操作: " + action + "\"}"
                );
        }
    }

    /**
     * 列出子Agent
     */
    private CompletionStage<String> executeList(String requesterKey, int recentMinutes) {
        List<SubagentRunRecord> runs = registry.listByRequester(requesterKey);

        // 分离活跃和最近的
        List<SubagentRunRecord> active = runs.stream()
                .filter(SubagentRunRecord::isRunning)
                .collect(Collectors.toList());

        List<SubagentRunRecord> recent = runs.stream()
                .filter(r -> !r.isRunning() && isRecent(r, recentMinutes))
                .collect(Collectors.toList());

        // 构建输出
        List<Map<String, Object>> activeList = active.stream()
                .map(SubagentRunRecord::toSummaryMap)
                .collect(Collectors.toList());

        List<Map<String, Object>> recentList = recent.stream()
                .map(SubagentRunRecord::toSummaryMap)
                .collect(Collectors.toList());

        // 构建文本摘要
        StringBuilder text = new StringBuilder();
        text.append(String.format("Total: %d subagents (%d active, %d recent)\n\n",
                runs.size(), active.size(), recent.size()));

        if (!active.isEmpty()) {
            text.append("## Active\n");
            for (int i = 0; i < active.size(); i++) {
                SubagentRunRecord r = active.get(i);
                text.append(String.format("%d. [%s] %s - %s (running)\n",
                        i + 1, r.getRunId(), r.getLabel() != null ? r.getLabel() : truncate(r.getTask(), 30), r.getRuntimeMs() / 1000 + "s"));
            }
            text.append("\n");
        }

        if (!recent.isEmpty()) {
            text.append("## Recent\n");
            for (int i = 0; i < recent.size(); i++) {
                SubagentRunRecord r = recent.get(i);
                text.append(String.format("%d. [%s] %s - %s\n",
                        i + 1, r.getRunId(), r.getLabel() != null ? r.getLabel() : truncate(r.getTask(), 30),
                        r.getOutcome() != null ? r.getOutcome().getStatusText() : "unknown"));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("action", "list");
        result.put("requesterSessionKey", requesterKey);
        result.put("total", runs.size());
        result.put("active", activeList);
        result.put("recent", recentList);
        result.put("text", text.toString().trim());

        return CompletableFuture.completedFuture(toJson(result));
    }

    /**
     * 终止子Agent
     */
    private CompletionStage<String> executeKill(String requesterKey, String target) {
        if (target == null || target.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"status\":\"error\",\"error\":\"target is required for kill action\"}"
            );
        }

        // kill all
        if ("all".equalsIgnoreCase(target) || "*".equals(target)) {
            return controller.killAll(requesterKey)
                    .thenApply(count -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "ok");
                        result.put("action", "kill");
                        result.put("target", "all");
                        result.put("killed", count);
                        result.put("text", count > 0
                                ? "已终止 " + count + " 个子代理"
                                : "没有运行中的子代理可终止");
                        return toJson(result);
                    });
        }

        // kill specific
        return controller.kill(target)
                .thenApply(success -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (success) {
                        result.put("status", "ok");
                        result.put("action", "kill");
                        result.put("target", target);
                        result.put("text", "已终止子代理: " + target);
                    } else {
                        result.put("status", "error");
                        result.put("action", "kill");
                        result.put("target", target);
                        result.put("error", "子代理未找到或已完成");
                    }
                    return toJson(result);
                });
    }

    /**
     * 向子Agent发送指导消息
     */
    private CompletionStage<String> executeSteer(String requesterKey, String target, String message) {
        if (target == null || target.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"status\":\"error\",\"error\":\"target is required for steer action\"}"
            );
        }

        if (message == null || message.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"status\":\"error\",\"error\":\"message is required for steer action\"}"
            );
        }

        if (message.length() > MAX_STEER_MESSAGE_CHARS) {
            return CompletableFuture.completedFuture(
                    String.format("{\"status\":\"error\",\"error\":\"Message too long (%d chars, max %d)\"}",
                            message.length(), MAX_STEER_MESSAGE_CHARS)
            );
        }

        return controller.steer(target, message)
                .thenApply(success -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (success) {
                        result.put("status", "ok");
                        result.put("action", "steer");
                        result.put("target", target);
                        result.put("text", "已引导子代理: " + target);
                    } else {
                        result.put("status", "error");
                        result.put("action", "steer");
                        result.put("target", target);
                        result.put("error", "子代理未找到或未运行");
                    }
                    return toJson(result);
                });
    }

    // ==========================
    // 辅助方法
    // ==========================

    private boolean isRecent(SubagentRunRecord record, int minutes) {
        if (record.getEndedAt() == null) return false;
        java.time.LocalDateTime threshold = java.time.LocalDateTime.now().minusMinutes(minutes);
        return record.getEndedAt().isAfter(threshold);
    }

    private String getString(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    private int getInt(Map<String, Object> args, String key, int defaultValue) {
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