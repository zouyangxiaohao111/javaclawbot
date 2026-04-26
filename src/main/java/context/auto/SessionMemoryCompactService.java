package context.auto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session Memory Compact Service aligned with Open-ClaudeCode.
 *
 * Fast path compression that uses pre-extracted session memory as the summary
 * instead of calling the API for summarization.
 *
 * Configuration:
 * - minTokens: 10,000 - Minimum tokens to preserve after compaction
 * - minTextBlockMessages: 5 - Minimum messages with text blocks to keep
 * - maxTokens: 40,000 - Maximum tokens to preserve (hard cap)
 *
 * Key features:
 * - Adjusts index to preserve tool_use/tool_result pairs
 * - Uses session memory file as the summary source
 * - Tracks lastSummarizedMessageId to know which messages have been summarized
 */
public class SessionMemoryCompactService {

    private SessionMemoryCompactService() {}

    /**
     * Configuration for session memory compaction
     */
    public static class SessionMemoryCompactConfig {
        /** Minimum tokens to preserve after compaction */
        public int minTokens = 10_000;
        /** Minimum number of messages with text blocks to keep */
        public int minTextBlockMessages = 5;
        /** Maximum tokens to preserve after compaction (hard cap) */
        public int maxTokens = 40_000;
    }

    private static final SessionMemoryCompactConfig DEFAULT_CONFIG = new SessionMemoryCompactConfig();

    /**
     * Check if a message contains text blocks (text content for user/assistant).
     */
    public static boolean hasTextBlocks(Map<String, Object> message) {
        if (message == null) return false;

        String role = message.get("role") instanceof String r ? r : null;

        if ("assistant".equals(role)) {
            Object content = message.get("content");
            if (content instanceof List<?> list) {
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                        return true;
                    }
                }
            }
        }

        if ("user".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return !s.isEmpty();
            }
            if (content instanceof List<?> list) {
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Get tool_result IDs from a user message.
     */
    private static List<String> getToolResultIds(Map<String, Object> message) {
        List<String> ids = new ArrayList<>();

        if (!"user".equals(message.get("role"))) return ids;

        Object content = message.get("content");
        if (!(content instanceof List<?> list)) return ids;

        for (Object block : list) {
            if (block instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                if (map.get("tool_use_id") instanceof String id) {
                    ids.add(id);
                }
            }
        }

        return ids;
    }

    /**
     * Check if an assistant message has tool_use blocks with given IDs.
     */
    private static boolean hasToolUseWithIds(Map<String, Object> message, Set<String> toolUseIds) {
        if (!"assistant".equals(message.get("role"))) return false;

        Object content = message.get("content");
        if (!(content instanceof List<?> list)) return false;

        for (Object block : list) {
            if (block instanceof Map<?, ?> map && "tool_use".equals(map.get("type"))) {
                if (map.get("id") instanceof String id && toolUseIds.contains(id)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Adjust start index to preserve tool_use/tool_result pairs and thinking blocks.
     *
     * If ANY message we're keeping contains tool_result blocks, we need to
     * include the preceding assistant message(s) that contain the matching tool_use blocks.
     *
     * Additionally, if ANY assistant message in the kept range has the same message.id
     * as a preceding assistant message (which may contain thinking blocks), we need to
     * include those messages.
     */
    public static int adjustIndexToPreserveAPIInvariants(
            List<Map<String, Object>> messages,
            int startIndex) {

        if (startIndex <= 0 || startIndex >= messages.size()) {
            return startIndex;
        }

        int adjustedIndex = startIndex;

        // Step 1: Handle tool_use/tool_result pairs
        // Collect tool_result IDs from ALL messages in the kept range
        List<String> allToolResultIds = new ArrayList<>();
        for (int i = startIndex; i < messages.size(); i++) {
            allToolResultIds.addAll(getToolResultIds(messages.get(i)));
        }

        if (!allToolResultIds.isEmpty()) {
            Set<String> toolUseIdsInKeptRange = new java.util.HashSet<>();

            // Collect tool_use IDs already in the kept range
            for (int i = adjustedIndex; i < messages.size(); i++) {
                Map<String, Object> msg = messages.get(i);
                if (!"assistant".equals(msg.get("role"))) continue;

                Object content = msg.get("content");
                if (!(content instanceof List<?> list)) continue;

                for (Object block : list) {
                    if (block instanceof Map<?, ?> map && "tool_use".equals(map.get("type"))) {
                        if (map.get("id") instanceof String id) {
                            toolUseIdsInKeptRange.add(id);
                        }
                    }
                }
            }

            // Find tool_use IDs that are NOT in kept range
            Set<String> neededToolUseIds = new java.util.HashSet<>();
            for (String id : allToolResultIds) {
                if (!toolUseIdsInKeptRange.contains(id)) {
                    neededToolUseIds.add(id);
                }
            }

            // Find assistant messages with matching tool_use blocks
            for (int i = adjustedIndex - 1; i >= 0 && !neededToolUseIds.isEmpty(); i--) {
                Map<String, Object> message = messages.get(i);
                if (hasToolUseWithIds(message, neededToolUseIds)) {
                    adjustedIndex = i;

                    // Remove found tool_use_ids
                    Object content = message.get("content");
                    if (content instanceof List<?> list) {
                        for (Object block : list) {
                            if (block instanceof Map<?, ?> map && "tool_use".equals(map.get("type"))) {
                                if (map.get("id") instanceof String id) {
                                    neededToolUseIds.remove(id);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step 2: Handle thinking blocks that share message.id
        Set<String> messageIdsInKeptRange = new java.util.HashSet<>();
        for (int i = adjustedIndex; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;

            Object msgObj = msg.get("message");
            if (msgObj instanceof Map<?, ?> m && m.get("id") instanceof String id) {
                messageIdsInKeptRange.add(id);
            }
        }

        // Look backwards for assistant messages with same message.id
        for (int i = adjustedIndex - 1; i >= 0; i--) {
            Map<String, Object> message = messages.get(i);
            if (!"assistant".equals(message.get("role"))) continue;

            Object msgObj = message.get("message");
            if (msgObj instanceof Map<?, ?> m && m.get("id") instanceof String id
                    && messageIdsInKeptRange.contains(id)) {
                adjustedIndex = i;
            }
        }

        return adjustedIndex;
    }

    /**
     * Calculate the starting index for messages to keep after compaction.
     *
     * Starts from lastSummarizedIndex, then expands backwards to meet minimums.
     * Also ensures tool_use/tool_result pairs are not split.
     */
    public static int calculateMessagesToKeepIndex(
            List<Map<String, Object>> messages,
            int lastSummarizedIndex) {

        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        SessionMemoryCompactConfig config = DEFAULT_CONFIG;

        // Start from the message after lastSummarizedIndex
        // If lastSummarizedIndex is -1, start with messages.length (no messages kept initially)
        int startIndex = lastSummarizedIndex >= 0 ? lastSummarizedIndex + 1 : messages.size();

        // Calculate current tokens and text-block message count from startIndex to end
        int totalTokens = 0;
        int textBlockMessageCount = 0;

        for (int i = startIndex; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            totalTokens += estimateMessageTokens(msg);
            if (hasTextBlocks(msg)) {
                textBlockMessageCount++;
            }
        }

        // Check if we already hit the max cap
        if (totalTokens >= config.maxTokens) {
            return adjustIndexToPreserveAPIInvariants(messages, startIndex);
        }

        // Check if we already meet both minimums
        if (totalTokens >= config.minTokens && textBlockMessageCount >= config.minTextBlockMessages) {
            return adjustIndexToPreserveAPIInvariants(messages, startIndex);
        }

        // Find the last boundary: floor at the last compact boundary
        int floor = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (CompactBoundary.isCompactBoundaryMessage(messages.get(i))) {
                floor = i + 1;
                break;
            }
        }

        // Expand backwards until we meet both minimums or hit max cap
        for (int i = startIndex - 1; i >= floor; i--) {
            Map<String, Object> msg = messages.get(i);
            int msgTokens = estimateMessageTokens(msg);
            totalTokens += msgTokens;
            if (hasTextBlocks(msg)) {
                textBlockMessageCount++;
            }
            startIndex = i;

            // Stop if we hit the max cap
            if (totalTokens >= config.maxTokens) {
                break;
            }

            // Stop if we meet both minimums
            if (totalTokens >= config.minTokens && textBlockMessageCount >= config.minTextBlockMessages) {
                break;
            }
        }

        return adjustIndexToPreserveAPIInvariants(messages, startIndex);
    }

    /**
     * Estimate message tokens (rough, ~4 chars per token).
     */
    private static int estimateMessageTokens(Map<String, Object> message) {
        if (message == null) return 0;

        Object role = message.get("role");

        if ("user".equals(role) || "assistant".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return roughTokenCountEstimation(s);
            }
            if (content instanceof List<?> list) {
                int total = 0;
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        String type = map.get("type") instanceof String t ? t : null;
                        if ("text".equals(type) && map.get("text") instanceof String t) {
                            total += roughTokenCountEstimation(t);
                        } else if ("tool_result".equals(type)) {
                            Object tc = map.get("content");
                            if (tc instanceof String s) {
                                total += roughTokenCountEstimation(s);
                            } else if (tc instanceof List<?> l) {
                                for (Object item : l) {
                                    if (item instanceof Map<?, ?> m && "text".equals(m.get("type")) && m.get("text") instanceof String txt) {
                                        total += roughTokenCountEstimation(txt);
                                    }
                                }
                            }
                        } else if ("tool_use".equals(type)) {
                            Object input = map.get("input");
                            if (input != null) {
                                total += roughTokenCountEstimation(input.toString());
                            }
                        } else if ("thinking".equals(type) && map.get("thinking") instanceof String t) {
                            total += roughTokenCountEstimation(t);
                        }
                    }
                }
                return total;
            }
        }

        return 256 / 4; // Default rough estimate
    }

    private static int roughTokenCountEstimation(String text) {
        if (text == null) return 0;
        return (text.length() / 4) + 1;
    }

    /**
     * Create summary messages from session memory.
     *
     * @param sessionMemory The session memory content
     * @param transcriptPath Optional transcript path
     * @return List containing the summary user message
     */
    public static List<Map<String, Object>> createSummaryMessagesFromSessionMemory(
            String sessionMemory,
            String transcriptPath) {

        // Truncate if needed (memory could be very large)
        String truncatedContent = sessionMemory;
        boolean wasTruncated = false;

        // Rough estimate: if > 40K tokens, truncate
        if (roughTokenCountEstimation(sessionMemory) > 40_000) {
            truncatedContent = truncateToTokenBudget(sessionMemory, 40_000);
            wasTruncated = true;
        }

        String summaryContent = CompactPrompt.getCompactUserSummaryMessage(
                truncatedContent,
                true,  // suppress follow-up questions
                transcriptPath,
                true   // recent messages preserved
        );

        if (wasTruncated) {
            summaryContent += "\n\nNote: Some session memory content was truncated for length.";
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(createSummaryUserMessage(summaryContent, true));

        return messages;
    }

    /**
     * Truncate content to token budget (roughly 4 chars per token).
     */
    private static String truncateToTokenBudget(String content, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "\n\n[... content truncated ...]";
    }

    /**
     * Create a summary user message.
     */
    private static Map<String, Object> createSummaryUserMessage(
            String content,
            boolean isCompactSummary) {

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        msg.put("isCompactSummary", isCompactSummary);
        msg.put("isVisibleInTranscriptOnly", true);
        msg.put("timestamp", LocalDateTime.now().toString());

        return msg;
    }

    /**
     * Create the compact boundary marker with preserved segment.
     */
    public static Map<String, Object> createBoundaryWithPreservedSegment(
            String trigger,
            long preCompactTokenCount,
            String lastPreCompactUuid,
            String summaryUuid,
            List<Map<String, Object>> messagesToKeep) {

        Map<String, Object> boundary = CompactBoundary.createCompactBoundaryMessage(
                trigger,
                preCompactTokenCount,
                lastPreCompactUuid
        );

        if (messagesToKeep != null && !messagesToKeep.isEmpty()) {
            Map<String, Object> firstMsg = messagesToKeep.get(0);
            Map<String, Object> lastMsg = messagesToKeep.get(messagesToKeep.size() - 1);

            String headUuid = firstMsg.get("uuid") instanceof String u ? u : null;
            String tailUuid = lastMsg.get("uuid") instanceof String u ? u : null;

            if (headUuid != null && tailUuid != null) {
                CompactBoundary.annotateBoundaryWithPreservedSegment(
                        boundary,
                        summaryUuid,  // anchor is the summary message
                        headUuid,
                        tailUuid
                );
            }
        }

        return boundary;
    }

    // ==================== Session Memory Compact Entry Point ====================

    /**
     * 检查是否应该使用 session memory 压缩（快速路径）
     * 对齐 Open-ClaudeCode: shouldUseSessionMemoryCompaction() in sessionMemoryCompact.ts:403-432
     *
     * 需要同时满足：
     * 1. sessionMemory.enabled = true (tengu_session_memory flag)
     * 2. smCompact.enabled = true (tengu_sm_compact flag)
     *
     * 注意：javaclawbot 使用配置开关而非 GrowthBook，
     *      调用者应先检查 sessionMemoryConfig.isEffectivelyEnabled() 和 smConfig.isSmCompactEnabled()
     */
    public static boolean shouldUseSessionMemoryCompaction(
            boolean sessionMemoryEnabled,
            boolean smCompactEnabled) {
        return sessionMemoryEnabled && smCompactEnabled;
    }

    /**
     * 尝试使用 session memory 进行压缩（快速路径）
     * 对齐 Open-ClaudeCode: trySessionMemoryCompaction() in sessionMemoryCompact.ts:514-630
     *
     * 函数签名对齐 Open-ClaudeCode: (messages, agentId?, autoCompactThreshold?) => CompactionResult | null
     *
     * 如果 session memory 存在且有内容，使用它作为摘要（无需 LLM 调用）
     * 如果 session memory 不存在或为空，返回 null，调用者应 fallback 到 LLM 压缩
     *
     * @param messages 当前消息列表
     * @param agentId Agent ID（可选，用于事件追踪）
     * @param autoCompactThreshold 自动压缩阈值（可选）
     * @return 压缩结果，如果不可用则返回 null
     */
    public static CompactionResult trySessionMemoryCompaction(
            session.SessionMemoryService sessionMemoryService,
            String sessionId,
            List<Map<String, Object>> messages,
            Long autoCompactThreshold) {

        if (sessionMemoryService == null || !sessionMemoryService.isEnabled()) {
            return null;
        }

        try {
            // 等待正在进行的 session memory 提取完成
            session.SessionMemoryUtils.waitForExtraction();

            // 获取已提取的 session memory 内容
            String sessionMemory = sessionMemoryService.getContent(sessionId);

            // 没有 session memory 文件
            if (sessionMemory == null || sessionMemory.isBlank()) {
                logEvent("tengu_sm_compact_no_session_memory");
                return null;
            }

            // session memory 是空模板，返回 null
            if (session.SessionMemoryUtils.isSessionMemoryEmpty(sessionMemory)) {
                logEvent("tengu_sm_compact_empty_template");
                return null;
            }

            // 获取 lastSummarizedMessageId
            String lastSummarizedMessageId = session.SessionMemoryUtils.getLastSummarizedMessageId();

            int lastSummarizedIndex;
            if (lastSummarizedMessageId != null && !lastSummarizedMessageId.isBlank()) {
                // Normal case: 查找已摘要消息的索引
                lastSummarizedIndex = -1;
                for (int i = 0; i < messages.size(); i++) {
                    Object uuid = messages.get(i).get("uuid");
                    if (lastSummarizedMessageId.equals(uuid)) {
                        lastSummarizedIndex = i;
                        break;
                    }
                }

                if (lastSummarizedIndex == -1) {
                    // 摘要消息 ID 不存在于当前消息中，可能是消息被修改
                    // Fall back to legacy compact
                    logEvent("tengu_sm_compact_summarized_id_not_found");
                    return null;
                }
            } else {
                // Resumed session case: session memory 有内容但没有边界
                // 设置 lastSummarizedIndex 为最后一条消息，使 startIndex = messages.length（不保留初始消息）
                lastSummarizedIndex = messages.size() - 1;
                logEvent("tengu_sm_compact_resumed_session");
            }

            // 计算要保留的消息起始索引
            int startIndex = calculateMessagesToKeepIndex(messages, lastSummarizedIndex);

            // 过滤掉旧的 compact boundary 消息
            // After REPL pruning, old boundaries re-yielded from messagesToKeep would
            // trigger an unwanted second prune (isCompactBoundaryMessage returns true),
            // discarding the new boundary and summary.
            List<Map<String, Object>> messagesToKeep = new ArrayList<>();
            for (int i = startIndex; i < messages.size(); i++) {
                Map<String, Object> msg = messages.get(i);
                if (!CompactBoundary.isCompactBoundaryMessage(msg)) {
                    messagesToKeep.add(msg);
                }
            }

            // TODO: 对齐 Open-ClaudeCode: processSessionStartHooks('compact', {model: getMainLoopModel()})
            // 源码路径: Open-ClaudeCode/src/services/compact/sessionMemoryCompact.ts:584-586
            // 等价实现: 调用 processSessionStartHooks('compact', {...}) 恢复 CLAUDE.md 和其他上下文
            // 当前 Java 实现缺失此 hook，可能影响压缩后的上下文恢复

            // 估计压缩前的 token 数
            long preCompactTokenCount = estimatePreCompactTokenCount(messages);

            // 获取最后一条消息的 UUID
            String lastUuid = null;
            if (!messages.isEmpty()) {
                Object uuid = messages.get(messages.size() - 1).get("uuid");
                if (uuid instanceof String u) lastUuid = u;
            }

            // 创建边界标记
            Map<String, Object> boundaryMarker = CompactBoundary.createCompactBoundaryMessage(
                    "auto",
                    preCompactTokenCount,
                    lastUuid
            );

            // 创建摘要消息
            List<Map<String, Object>> summaryMessages = createSummaryMessagesFromSessionMemory(
                    sessionMemory,
                    null  // transcriptPath
            );

            // 获取摘要消息的 UUID 作为锚点
            String summaryUuid = null;
            if (!summaryMessages.isEmpty()) {
                Object uuid = summaryMessages.get(summaryMessages.size() - 1).get("uuid");
                if (uuid instanceof String u) summaryUuid = u;
            }

            // 标注边界和保留片段
            if (summaryUuid != null && !messagesToKeep.isEmpty()) {
                // 获取 messagesToKeep 的首尾 UUID
                String headUuid = null;
                String tailUuid = null;

                Map<String, Object> firstMsg = messagesToKeep.get(0);
                Map<String, Object> lastMsg = messagesToKeep.get(messagesToKeep.size() - 1);

                Object headUuidObj = firstMsg.get("uuid");
                Object tailUuidObj = lastMsg.get("uuid");

                if (headUuidObj instanceof String h) headUuid = h;
                if (tailUuidObj instanceof String t) tailUuid = t;

                if (headUuid != null && tailUuid != null) {
                    CompactBoundary.annotateBoundaryWithPreservedSegment(
                            boundaryMarker,
                            summaryUuid,
                            headUuid,
                            tailUuid
                    );
                }
            }

            // 估计压缩后的 token 数
            int postCompactTokenCount = (int) estimatePreCompactTokenCount(summaryMessages);

            // 检查阈值
            if (autoCompactThreshold != null && postCompactTokenCount >= autoCompactThreshold) {
                logEvent("tengu_sm_compact_threshold_exceeded");
                return null;
            }

            // 构建结果
            return new CompactionResult(
                    boundaryMarker,
                    summaryMessages,
                    List.of(),  // attachments
                    List.of(),  // hookResults
                    messagesToKeep,
                    preCompactTokenCount,
                    postCompactTokenCount
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logEvent("tengu_sm_compact_error");
            return null;
        }
    }

    /**
     * 记录事件日志
     * 对齐 Open-ClaudeCode: logEvent() in src/services/analytics/index.ts
     */
    private static void logEvent(String eventName) {
        org.slf4j.LoggerFactory.getLogger(SessionMemoryCompactService.class)
                .debug("Session memory compact event: {}", eventName);
    }

    /**
     * 记录事件日志（带额外数据）
     */
    private static void logEvent(String eventName, Object... data) {
        org.slf4j.LoggerFactory.getLogger(SessionMemoryCompactService.class)
                .debug("Session memory compact event: {} {}", eventName, java.util.Arrays.toString(data));
    }

    /**
     * 估计消息列表的 token 数
     */
    private static long estimatePreCompactTokenCount(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (Map<String, Object> msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    /**
     * 压缩结果
     */
    public static class CompactionResult {
        public final Map<String, Object> boundaryMarker;
        public final List<Map<String, Object>> summaryMessages;
        public final List<Map<String, Object>> attachments;
        public final List<Map<String, Object>> hookResults;
        public final List<Map<String, Object>> messagesToKeep;
        public final long preCompactTokenCount;
        public final int postCompactTokenCount;

        public CompactionResult(
                Map<String, Object> boundaryMarker,
                List<Map<String, Object>> summaryMessages,
                List<Map<String, Object>> attachments,
                List<Map<String, Object>> hookResults,
                List<Map<String, Object>> messagesToKeep,
                long preCompactTokenCount,
                int postCompactTokenCount) {
            this.boundaryMarker = boundaryMarker;
            this.summaryMessages = summaryMessages;
            this.attachments = attachments;
            this.hookResults = hookResults;
            this.messagesToKeep = messagesToKeep;
            this.preCompactTokenCount = preCompactTokenCount;
            this.postCompactTokenCount = postCompactTokenCount;
        }
    }
}
