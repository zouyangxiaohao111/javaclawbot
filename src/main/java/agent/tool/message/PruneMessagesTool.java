package agent.tool.message;

import agent.tool.Tool;
import config.Config;
import config.ConfigIO;
import context.ContextPruner;
import context.ContextPruningSettings;
import lombok.extern.slf4j.Slf4j;
import session.Session;
import session.SessionManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 消息修剪工具
 *
 * 用于 /context-compress 命令，分析对话消息并删除冗余内容
 */
@Slf4j
public class PruneMessagesTool extends Tool {

    private final SessionManager sessionManager;
    private final String sessionKey;

    public PruneMessagesTool(SessionManager sessionManager, String sessionKey) {
        this.sessionManager = sessionManager;
        this.sessionKey = sessionKey;
    }

    @Override
    public String name() {
        return "prune_messages";
    }

    @Override
    public String description() {
        return "分析对话消息，决定哪些可以从 对话上下文 中删除。" +
                "memory/YYYY-MM-DD.md 已保存完整原始对话，Session 只需保留活跃窗口内的消息。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        // 模式1：删除消息（原有功能）
        Map<String, Object> droppedIndices = new LinkedHashMap<>();
        droppedIndices.put("type", "array");
        droppedIndices.put("items", Map.of("type", "integer"));
        droppedIndices.put("description", "可以删除的消息索引列表");
        props.put("dropped_indices", droppedIndices);

        Map<String, Object> importantIndices = new LinkedHashMap<>();
        importantIndices.put("type", "array");
        importantIndices.put("items", Map.of("type", "integer"));
        importantIndices.put("description", "特别重要的消息索引");
        props.put("important_indices", importantIndices);

        Map<String, Object> reasoning = new LinkedHashMap<>();
        reasoning.put("type", "string");
        reasoning.put("description", "删除理由");
        props.put("reasoning", reasoning);

        // 模式2：裁剪消息（新增功能）
        Map<String, Object> pruneMessages = new LinkedHashMap<>();
        pruneMessages.put("type", "array");
        Map<String, Object> pruneItem = new LinkedHashMap<>();
        pruneItem.put("type", "object");

        Map<String, Object> itemProps = new LinkedHashMap<>();
        Map<String, Object> sub_indices = new LinkedHashMap<>();
        sub_indices.put("type", "array");
        sub_indices.put("items", Map.of("type", "integer"));
        sub_indices.put("description", "要裁剪的消息索引列表");
        itemProps.put("sub_indices", sub_indices);

        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("type", "string");
        replacement.put("description", "替换被裁剪消息的内容");
        itemProps.put("replacement", replacement);

        Map<String, Object> pruneReasoning = new LinkedHashMap<>();
        pruneReasoning.put("type", "string");
        pruneReasoning.put("description", "裁剪理由");
        itemProps.put("reasoning", pruneReasoning);

        pruneItem.put("properties", itemProps);
        pruneItem.put("required", List.of("sub_indices", "replacement"));
        pruneMessages.put("items", pruneItem);
        pruneMessages.put("description", "裁剪消息列表，每个条目指定要裁剪的索引、替换内容和理由");
        props.put("prune_messages", pruneMessages);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", props);
        out.put("required", List.of("reasoning"));
        // 注意：dropped_indices 或 prune_messages 至少需要一组

        return out;
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        Session session = sessionManager.getOrCreate(sessionKey);
        if (session == null) {
            return CompletableFuture.completedFuture("Error: session not found: " + sessionKey);
        }

        List<Map<String, Object>> messages = session.getMessages();
        int totalMessages = messages.size();

        String reasoning = args.get("reasoning") instanceof String s ? s : "";

        // 解析 prune_messages 参数（新模式）
        List<Map<String, Object>> pruneMessagesList = parsePruneMessagesList(args.get("prune_messages"));

        // 如果提供了 prune_messages，使用裁剪模式
        if (pruneMessagesList != null && !pruneMessagesList.isEmpty()) {
            return executePruneMode(messages, pruneMessagesList, reasoning, session, totalMessages);
        }

        // 否则使用原有的 dropped_indices 删除模式
        return executeDropMode(args, messages, reasoning, session, totalMessages);
    }

    /**
     * 模式1：删除消息（原有功能）
     */
    private CompletionStage<String> executeDropMode(
            Map<String, Object> args,
            List<Map<String, Object>> messages,
            String reasoning,
            Session session,
            int totalMessages) {

        List<Integer> droppedIndices = parseIntegerList(args.get("dropped_indices"));
        if (droppedIndices == null || droppedIndices.isEmpty()) {
            return CompletableFuture.completedFuture("没有消息需要删除");
        }

        // 验证索引范围
        droppedIndices = droppedIndices.stream()
                .filter(i -> i >= 0 && i < totalMessages)
                .sorted(Collections.reverseOrder()) // 从后往前删除，避免索引变化
                .toList();

        if (droppedIndices.isEmpty()) {
            return CompletableFuture.completedFuture("所有索引都超出范围，没有消息被删除");
        }

        // 执行删除
        List<Map<String, Object>> newMessages = new ArrayList<>(messages);
        int droppedCount = 0;
        for (int index : droppedIndices) {
            if (index >= 0 && index < newMessages.size()) {
                newMessages.remove(index);
                droppedCount++;
            }
        }

        // 更新 session
        session.setMessages(newMessages);
        sessionManager.save(session);

        String result = String.format(
                "已删除 %d 条消息（共 %d 条）。\n理由：%s",
                droppedCount, totalMessages, reasoning
        );
        logContextUsage(newMessages);

        return CompletableFuture.completedFuture(result);
    }

    /**
     * 模式2：裁剪消息（新增功能）
     * 每个条目指定要裁剪的索引，用摘要替换，head/tail 自动保留
     */
    private CompletionStage<String> executePruneMode(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> pruneMessagesList,
            String reasoning,
            Session session,
            int totalMessages) {

        // 自动保留的 head 和 tail 数量
        int headCount = 3;
        int tailCount = 5;

        // 收集所有要裁剪的索引
        Set<Integer> allPrunedIndices = new HashSet<>();
        Map<Integer, String> indexToReplacement = new HashMap<>();
        List<String> allReasons = new ArrayList<>();

        for (Map<String, Object> pruneItem : pruneMessagesList) {
            List<Integer> sub_indices = parseIntegerList(pruneItem.get("sub_indices"));
            String replacement = pruneItem.get("replacement") instanceof String s ? s : "[上下文已被裁剪]";
            String pruneReason = pruneItem.get("reasoning") instanceof String s ? s : "";
            if (!pruneReason.isEmpty()) {
                allReasons.add(pruneReason);
            }
            if (sub_indices != null) {
                for (int idx : sub_indices) {
                    if (idx >= headCount && idx < totalMessages - tailCount) {
                        allPrunedIndices.add(idx);
                        indexToReplacement.put(idx, replacement);
                    }
                }
            }
        }

        if (allPrunedIndices.isEmpty()) {
            return CompletableFuture.completedFuture(
                    String.format("所有索引都在保留范围内（head=%d, tail=%d），没有消息被裁剪", headCount, tailCount));
        }

        // 构建新消息列表
        List<Map<String, Object>> newMessages = new ArrayList<>();
        int prunedCount = 0;
        String lastReplacement = null;

        for (int i = 0; i < totalMessages; i++) {
            if (allPrunedIndices.contains(i)) {
                // 该索引需要裁剪，用摘要替换（多个连续裁剪只插入一条摘要）
                String replacement = indexToReplacement.get(i);
                if (lastReplacement == null || !lastReplacement.equals(replacement)) {
                    // 新的替换内容，插入摘要消息
                    Map<String, Object> summaryMsg = new LinkedHashMap<>();
                    summaryMsg.put("role", "user");
                    summaryMsg.put("content", replacement);
                    newMessages.add(summaryMsg);
                    lastReplacement = replacement;
                }
                prunedCount++;
            } else {
                // 保留该消息
                newMessages.add(messages.get(i));
                lastReplacement = null; // 重置，下次裁剪时需要新摘要
            }
        }

        // 更新 session
        session.setMessages(newMessages);
        sessionManager.save(session);

        String reasonsText = allReasons.isEmpty() ? reasoning : String.join("; ", allReasons);
        String result = String.format(
                "已裁剪 %d 条消息（共 %d 条），自动保留头部 %d 条 + 尾部 %d 条。\n新消息数：%d\n理由：%s",
                prunedCount, totalMessages, headCount, tailCount, newMessages.size(), reasonsText
        );
        logContextUsage(newMessages);

        return CompletableFuture.completedFuture(result);
    }

    private void logContextUsage(List<Map<String, Object>> messages) {
        int estimatedChars = ContextPruner.estimateContextChars(messages);
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(sessionManager.getWorkspace()));
        int contextWindow = config.obtainContextWindow(config.getAgents().getDefaults().getModel());
        double contextRatio = contextWindow > 0 ? (double) estimatedChars / contextWindowChars(contextWindow) : 0;
        log.info("压缩后上下文使用率: {}%", String.format("%.1f", contextRatio * 100));
    }

    /**
     * 解析 prune_messages 列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parsePruneMessagesList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        return null;
    }

    private double contextWindowChars(int contextWindow) {
        return contextWindow * ContextPruningSettings.CHARS_PER_TOKEN_ESTIMATE;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> parseIntegerList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            List<Integer> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number num) {
                    result.add(num.intValue());
                } else if (item instanceof String s) {
                    try {
                        result.add(Integer.parseInt(s));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return result;
        }
        return null;
    }
}