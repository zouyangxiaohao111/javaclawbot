package agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆压缩工具类
 *
 * 对齐 OpenClaw 的 compaction.ts 核心功能：
 * - 分块压缩：按 token 数分块，避免超出模型限制
 * - 安全边距：补偿 token 估算误差
 * - 渐进式回退：压缩失败时跳过超大消息
 * - 标识符保留：保留 UUID、API key 等关键标识符
 */
public class MemoryCompaction {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompaction.class);

    /** 安全边距：补偿 token 估算误差（OpenClaw 使用 1.2） */
    public static final double SAFETY_MARGIN = 1.2;

    /** 压缩开销预留 token 数 */
    public static final int SUMMARIZATION_OVERHEAD_TOKENS = 4096;

    /** 基础分块比例 */
    public static final double BASE_CHUNK_RATIO = 0.4;

    /** 最小分块比例 */
    public static final double MIN_CHUNK_RATIO = 0.15;

    /** 标识符保留指令 */
    public static final String IDENTIFIER_PRESERVATION_INSTRUCTIONS =
            "Preserve all opaque identifiers exactly as written (no shortening or reconstruction), " +
            "including UUIDs, hashes, IDs, tokens, API keys, hostnames, IPs, ports, URLs, and file names.";

    /**
     * 估算消息的 token 数
     * 使用简单的字符数/4 估算（与 OpenClaw 的 estimateTokens 类似）
     */
    public static int estimateTokens(Map<String, Object> message) {
        if (message == null) return 0;

        int total = 0;

        // 内容
        Object content = message.get("content");
        if (content instanceof String s) {
            total += s.length() / 4;
        } else if (content instanceof List<?> list) {
            // 多部分内容
            for (Object part : list) {
                if (part instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text instanceof String t) {
                        total += t.length() / 4;
                    }
                }
            }
        }

        // 工具调用
        Object toolCalls = message.get("tool_calls");
        if (toolCalls instanceof List<?> list) {
            for (Object tc : list) {
                if (tc instanceof Map<?, ?> map) {
                    Object args = map.get("arguments");
                    if (args instanceof String s) {
                        total += s.length() / 4;
                    } else if (args instanceof Map<?, ?> m) {
                        total += m.toString().length() / 4;
                    }
                }
            }
        }

        // 工具结果
        Object toolResult = message.get("content");
        if (message.get("role") != null && "tool".equals(message.get("role"))) {
            if (toolResult instanceof String s) {
                total += s.length() / 4;
            }
        }

        return Math.max(1, total);
    }

    /**
     * 估算消息列表的总 token 数
     */
    public static int estimateMessagesTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        return messages.stream().mapToInt(MemoryCompaction::estimateTokens).sum();
    }

    /**
     * 按 token 数分块
     *
     * 对齐 OpenClaw 的 chunkMessagesByMaxTokens
     *
     * @param messages  消息列表
     * @param maxTokens 每块最大 token 数
     * @return 分块后的消息列表
     */
    public static List<List<Map<String, Object>>> chunkMessagesByMaxTokens(
            List<Map<String, Object>> messages,
            int maxTokens
    ) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        // 应用安全边距
        int effectiveMax = Math.max(1, (int) Math.floor(maxTokens / SAFETY_MARGIN));

        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        List<Map<String, Object>> currentChunk = new ArrayList<>();
        int currentTokens = 0;

        for (Map<String, Object> message : messages) {
            int messageTokens = estimateTokens(message);

            // 当前块不为空且加入后超出限制
            if (!currentChunk.isEmpty() && currentTokens + messageTokens > effectiveMax) {
                chunks.add(new ArrayList<>(currentChunk));
                currentChunk = new ArrayList<>();
                currentTokens = 0;
            }

            currentChunk.add(message);
            currentTokens += messageTokens;

            // 单条消息就超出限制
            if (messageTokens > effectiveMax) {
                chunks.add(new ArrayList<>(currentChunk));
                currentChunk = new ArrayList<>();
                currentTokens = 0;
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }

        return chunks;
    }

    /**
     * 按比例分割消息
     *
     * 对齐 OpenClaw 的 splitMessagesByTokenShare
     *
     * @param messages 消息列表
     * @param parts    分割份数
     * @return 分割后的消息列表
     */
    public static List<List<Map<String, Object>>> splitMessagesByTokenShare(
            List<Map<String, Object>> messages,
            int parts
    ) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        int normalizedParts = Math.max(1, Math.min(parts, messages.size()));
        if (normalizedParts <= 1) {
            return List.of(new ArrayList<>(messages));
        }

        int totalTokens = estimateMessagesTokens(messages);
        int targetTokens = totalTokens / normalizedParts;

        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int currentTokens = 0;

        for (Map<String, Object> message : messages) {
            int messageTokens = estimateTokens(message);

            if (chunks.size() < normalizedParts - 1 && !current.isEmpty() && currentTokens + messageTokens > targetTokens) {
                chunks.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }

            current.add(message);
            currentTokens += messageTokens;
        }

        if (!current.isEmpty()) {
            chunks.add(current);
        }

        return chunks;
    }

    /**
     * 计算自适应分块比例
     *
     * 对齐 OpenClaw 的 computeAdaptiveChunkRatio
     * 当消息较大时，使用较小的分块比例
     */
    public static double computeAdaptiveChunkRatio(List<Map<String, Object>> messages, int contextWindow) {
        if (messages == null || messages.isEmpty()) {
            return BASE_CHUNK_RATIO;
        }

        int totalTokens = estimateMessagesTokens(messages);
        double avgTokens = (double) totalTokens / messages.size();

        // 应用安全边距
        double safeAvgTokens = avgTokens * SAFETY_MARGIN;
        double avgRatio = safeAvgTokens / contextWindow;

        // 如果平均消息 > 10% 上下文，减少分块比例
        if (avgRatio > 0.1) {
            double reduction = Math.min(avgRatio * 2, BASE_CHUNK_RATIO - MIN_CHUNK_RATIO);
            return Math.max(MIN_CHUNK_RATIO, BASE_CHUNK_RATIO - reduction);
        }

        return BASE_CHUNK_RATIO;
    }

    /**
     * 检查单条消息是否过大无法压缩
     *
     * 对齐 OpenClaw 的 isOversizedForSummary
     */
    public static boolean isOversizedForSummary(Map<String, Object> message, int contextWindow) {
        int tokens = (int) (estimateTokens(message) * SAFETY_MARGIN);
        return tokens > contextWindow * 0.5;
    }

    /**
     * 修剪历史以适应上下文共享
     *
     * 对齐 OpenClaw 的 pruneHistoryForContextShare
     *
     * @param messages        消息列表
     * @param maxContextTokens 最大上下文 token 数
     * @param maxHistoryShare 历史最大占比（默认 0.5）
     * @return 修剪结果
     */
    public static PruneResult pruneHistoryForContextShare(
            List<Map<String, Object>> messages,
            int maxContextTokens,
            double maxHistoryShare
    ) {
        double share = Math.max(0.1, Math.min(1.0, maxHistoryShare));
        int budgetTokens = Math.max(1, (int) Math.floor(maxContextTokens * share));

        List<Map<String, Object>> keptMessages = new ArrayList<>(messages);
        List<Map<String, Object>> droppedMessages = new ArrayList<>();
        int droppedChunks = 0;
        int droppedTokens = 0;

        int parts = 2; // 默认分成 2 部分

        while (!keptMessages.isEmpty() && estimateMessagesTokens(keptMessages) > budgetTokens) {
            List<List<Map<String, Object>>> chunks = splitMessagesByTokenShare(keptMessages, parts);
            if (chunks.size() <= 1) {
                break;
            }

            // 丢弃第一块
            List<Map<String, Object>> dropped = chunks.get(0);
            List<Map<String, Object>> rest = new ArrayList<>();
            for (int i = 1; i < chunks.size(); i++) {
                rest.addAll(chunks.get(i));
            }

            // 修复工具调用配对
            PruneRepairResult repair = repairToolUseResultPairing(rest);

            droppedChunks++;
            droppedTokens += estimateMessagesTokens(dropped);
            droppedMessages.addAll(dropped);
            // 注意：孤立的 tool_result 也被丢弃了，但没有加入 droppedMessages

            keptMessages = repair.messages;
        }

        return new PruneResult(
                keptMessages,
                droppedMessages,
                droppedChunks,
                droppedMessages.size(),
                droppedTokens,
                estimateMessagesTokens(keptMessages),
                budgetTokens
        );
    }

    /**
     * 修复工具调用配对
     *
     * 对齐 OpenClaw 的 repairToolUseResultPairing
     * 移除孤立的 tool_result（其对应的 tool_use 已被丢弃）
     */
    public static PruneRepairResult repairToolUseResultPairing(List<Map<String, Object>> messages) {
        // 收集所有 tool_use 的 id
        java.util.Set<String> toolUseIds = new java.util.HashSet<>();
        for (Map<String, Object> msg : messages) {
            Object toolCalls = msg.get("tool_calls");
            if (toolCalls instanceof List<?> list) {
                for (Object tc : list) {
                    if (tc instanceof Map<?, ?> map) {
                        Object id = map.get("id");
                        if (id != null) {
                            toolUseIds.add(String.valueOf(id));
                        }
                    }
                }
            }
        }

        // 过滤掉孤立的 tool_result
        List<Map<String, Object>> repaired = new ArrayList<>();
        int droppedOrphanCount = 0;

        for (Map<String, Object> msg : messages) {
            Object role = msg.get("role");
            if ("tool".equals(role)) {
                Object toolCallId = msg.get("tool_call_id");
                if (toolCallId != null && !toolUseIds.contains(String.valueOf(toolCallId))) {
                    // 孤立的 tool_result，跳过
                    droppedOrphanCount++;
                    continue;
                }
            }
            repaired.add(msg);
        }

        return new PruneRepairResult(repaired, droppedOrphanCount);
    }

    /**
     * 构建压缩提示词指令
     *
     * 对齐 OpenClaw 的 buildCompactionSummarizationInstructions
     */
    public static String buildCompactionInstructions(String customInstructions, boolean preserveIdentifiers) {
        StringBuilder sb = new StringBuilder();

        if (preserveIdentifiers) {
            sb.append(IDENTIFIER_PRESERVATION_INSTRUCTIONS);
        }

        if (customInstructions != null && !customInstructions.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\nAdditional focus:\n");
            }
            sb.append(customInstructions);
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    // ==========================
    // 结果类
    // ==========================

    public static class PruneResult {
        public final List<Map<String, Object>> messages;
        public final List<Map<String, Object>> droppedMessagesList;
        public final int droppedChunks;
        public final int droppedMessages;
        public final int droppedTokens;
        public final int keptTokens;
        public final int budgetTokens;

        public PruneResult(
                List<Map<String, Object>> messages,
                List<Map<String, Object>> droppedMessagesList,
                int droppedChunks,
                int droppedMessages,
                int droppedTokens,
                int keptTokens,
                int budgetTokens
        ) {
            this.messages = messages;
            this.droppedMessagesList = droppedMessagesList;
            this.droppedChunks = droppedChunks;
            this.droppedMessages = droppedMessages;
            this.droppedTokens = droppedTokens;
            this.keptTokens = keptTokens;
            this.budgetTokens = budgetTokens;
        }
    }

    public static class PruneRepairResult {
        public final List<Map<String, Object>> messages;
        public final int droppedOrphanCount;

        public PruneRepairResult(List<Map<String, Object>> messages, int droppedOrphanCount) {
            this.messages = messages;
            this.droppedOrphanCount = droppedOrphanCount;
        }
    }
}