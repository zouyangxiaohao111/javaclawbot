package context.auto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Microcompact service aligned with Open-ClaudeCode.
 *
 * Clears old tool results when server cache expires (time-based trigger).
 * Also supports count-based clearing of tool results.
 *
 * Key concept: TIME_BASED_MC_CLEARED_MESSAGE marker replaces content,
 * allowing the cache to be preserved while freeing context space.
 */
public class MicroCompactService {

    private MicroCompactService() {}

    /**
     * Marker for cleared tool result content.
     */
    public static final String TIME_BASED_MC_CLEARED_MESSAGE = "[Old tool result content cleared]";

    /**
     * Image token size estimate.
     */
    private static final int IMAGE_MAX_TOKEN_SIZE = 2000;

    /**
     * Tools that are eligible for microcompact.
     * Only these tools' results are cleared during microcompact.
     */
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "Read",
            "Bash",
            "Shell",
            "Grep",
            "Glob",
            "WebSearch",
            "WebFetch",
            "Edit",
            "Write"
    );

    /**
     * Time-based microcompact configuration.
     */
    public static class TimeBasedMCConfig {
        /** Whether time-based microcompact is enabled */
        public boolean enabled = true;
        /** Gap threshold in minutes since last assistant message */
        public int gapThresholdMinutes = 30;
        /** Number of recent compactable tool results to keep */
        public int keepRecent = 2;
    }

    private static final TimeBasedMCConfig DEFAULT_TIME_BASED_CONFIG = new TimeBasedMCConfig();

    /**
     * Get the time-based microcompact configuration.
     */
    public static TimeBasedMCConfig getTimeBasedMCConfig() {
        // Could be overridden by GrowthBook/config in a full implementation
        return DEFAULT_TIME_BASED_CONFIG;
    }

    /**
     * Evaluate whether the time-based trigger should fire.
     *
     * @param messages The message list
     * @param lastAssistantTimestampMs Timestamp of last assistant message in milliseconds
     * @param config The time-based config
     * @return Gap in minutes if trigger fires, -1 otherwise
     */
    public static int evaluateTimeBasedTrigger(
            List<Map<String, Object>> messages,
            long lastAssistantTimestampMs,
            TimeBasedMCConfig config) {

        if (!config.enabled) {
            return -1;
        }

        if (messages == null || messages.isEmpty()) {
            return -1;
        }

        long now = System.currentTimeMillis();
        long gapMs = now - lastAssistantTimestampMs;
        if (gapMs < 0) {
            return -1;
        }

        int gapMinutes = (int) (gapMs / 60_000);

        if (gapMinutes < config.gapThresholdMinutes) {
            return -1;
        }

        return gapMinutes;
    }

    /**
     * Get the timestamp of the last assistant message.
     *
     * @param messages The message list
     * @return Timestamp in milliseconds, or -1 if not found
     */
    public static long getLastAssistantTimestamp(List<Map<String, Object>> messages) {
        if (messages == null) return -1;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                Object timestamp = msg.get("timestamp");
                if (timestamp instanceof String ts) {
                    try {
                        return parseTimestamp(ts);
                    } catch (Exception e) {
                        // Fall through
                    }
                } else if (timestamp instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return -1;
    }

    /**
     * Perform time-based microcompact.
     *
     * Clears all but the most recent N compactable tool results.
     *
     * @param messages The message list (will be modified)
     * @param keepRecent Number of recent tool results to keep
     * @return MicrocompactResult with cleared messages and tokens saved
     */
    public static MicrocompactResult microcompactMessages(
            List<Map<String, Object>> messages,
            int keepRecent) {

        if (messages == null || messages.isEmpty()) {
            return new MicrocompactResult(messages, 0);
        }

        // Collect compactable tool IDs in order
        List<String> compactableIds = collectCompactableToolIds(messages);

        if (compactableIds.isEmpty()) {
            return new MicrocompactResult(messages, 0);
        }

        // Determine which to clear
        int effectiveKeep = Math.max(1, keepRecent);
        Set<String> keepSet = compactableIds.size() <= effectiveKeep
                ? Set.of()
                : Set.of(compactableIds.subList(
                        compactableIds.size() - effectiveKeep,
                        compactableIds.size()).toArray(new String[0]));

        List<String> clearIds = new ArrayList<>();
        for (String id : compactableIds) {
            if (!keepSet.contains(id)) {
                clearIds.add(id);
            }
        }

        if (clearIds.isEmpty()) {
            return new MicrocompactResult(messages, 0);
        }

        Set<String> clearSet = Set.copyOf(clearIds);
        int tokensSaved = 0;

        // Modify messages in place
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(msg.get("role"))) continue;

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> content)) continue;

            boolean touched = false;
            List<Object> newContent = new ArrayList<>();

            for (Object block : content) {
                if (!(block instanceof Map<?, ?> map)) {
                    newContent.add(block);
                    continue;
                }

                if ("tool_result".equals(map.get("type"))) {
                    String toolUseId = map.get("tool_use_id") instanceof String id ? id : null;
                    Object content_value = map.get("content");

                    if (toolUseId != null && clearSet.contains(toolUseId)) {
                        String currentContent = content_value instanceof String s ? s : "";
                        if (!TIME_BASED_MC_CLEARED_MESSAGE.equals(currentContent)) {
                            tokensSaved += estimateToolResultTokens(content_value);
                            touched = true;
                            // Replace with cleared marker
                            Map<Object, Object> newBlock = new LinkedHashMap<>(map);
                            newBlock.put("content", TIME_BASED_MC_CLEARED_MESSAGE);
                            newContent.add(newBlock);
                        } else {
                            newContent.add(block);
                        }
                    } else {
                        newContent.add(block);
                    }
                } else {
                    newContent.add(block);
                }
            }

            if (touched) {
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                messages.set(i, newMsg);
            }
        }

        return new MicrocompactResult(messages, tokensSaved);
    }

    /**
     * Collect compactable tool IDs from assistant messages.
     */
    private static List<String> collectCompactableToolIds(List<Map<String, Object>> messages) {
        List<String> ids = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) continue;

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> content)) continue;

            for (Object block : content) {
                if (!(block instanceof Map<?, ?> map)) continue;

                if ("tool_use".equals(map.get("type"))) {
                    String name = map.get("name") instanceof String n ? n : null;
                    String id = map.get("id") instanceof String i ? i : null;

                    if (name != null && id != null && COMPACTABLE_TOOLS.contains(name)) {
                        ids.add(id);
                    }
                }
            }
        }

        return ids;
    }

    /**
     * Estimate token count for a tool result.
     */
    private static int estimateToolResultTokens(Object content) {
        if (content == null) return 0;

        if (content instanceof String s) {
            return roughTokenCountEstimation(s);
        }

        if (content instanceof List<?> list) {
            int total = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String type = map.get("type") instanceof String t ? t : null;
                    if ("text".equals(type) && map.get("text") instanceof String t) {
                        total += roughTokenCountEstimation(t);
                    } else if ("image".equals(type) || "document".equals(type)) {
                        total += IMAGE_MAX_TOKEN_SIZE;
                    }
                }
            }
            return total;
        }

        return 0;
    }

    /**
     * Rough token estimation (~4 chars per token).
     */
    private static int roughTokenCountEstimation(String text) {
        if (text == null) return 0;
        return (text.length() / 4) + 1;
    }

    /**
     * Parse timestamp to milliseconds.
     */
    private static long parseTimestamp(String timestamp) {
        // Try ISO format first
        try {
            return java.time.Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            // Try other formats
        }

        try {
            return Long.parseLong(timestamp);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Strip images from user messages before sending to compaction.
     * Images are not needed for summary and can cause PTL errors.
     *
     * @param messages The message list
     * @return New list with images replaced by [image] markers
     */
    public static List<Map<String, Object>> stripImagesFromMessages(
            List<Map<String, Object>> messages) {

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            if (!"user".equals(msg.get("role"))) {
                result.add(msg);
                continue;
            }

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> content)) {
                result.add(msg);
                continue;
            }

            boolean hasMedia = false;
            List<Object> newContent = new ArrayList<>();

            for (Object block : content) {
                if (!(block instanceof Map<?, ?> map)) {
                    newContent.add(block);
                    continue;
                }

                String type = map.get("type") instanceof String t ? t : null;

                if ("image".equals(type) || "document".equals(type)) {
                    hasMedia = true;
                    newContent.add(Map.of("type", "text", "text", "[" + type + "]"));
                } else if ("tool_result".equals(type) && map.get("content") instanceof List<?> toolContent) {
                    // Check for nested images in tool_result
                    boolean toolHasMedia = false;
                    List<Object> newToolContent = new ArrayList<>();

                    for (Object item : toolContent) {
                        if (!(item instanceof Map<?, ?> itemMap)) {
                            newToolContent.add(item);
                            continue;
                        }

                        String itemType = itemMap.get("type") instanceof String it ? it : null;
                        if ("image".equals(itemType) || "document".equals(itemType)) {
                            toolHasMedia = true;
                            newToolContent.add(Map.of("type", "text", "text", "[" + itemType + "]"));
                        } else {
                            newToolContent.add(item);
                        }
                    }

                    if (toolHasMedia) {
                        hasMedia = true;
                        Map<Object, Object> newBlock = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {
                            newBlock.put(entry.getKey(), entry.getValue());
                        }
                        newBlock.put("content", newToolContent);
                        newContent.add(newBlock);
                    } else {
                        newContent.add(block);
                    }
                } else {
                    newContent.add(block);
                }
            }

            if (!hasMedia) {
                result.add(msg);
            } else {
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                result.add(newMsg);
            }
        }

        return result;
    }

    /**
     * Result of microcompact operation.
     */
    public static class MicrocompactResult {
        /** Modified messages (same reference if not changed) */
        private final List<Map<String, Object>> messages;
        /** Approximate tokens saved */
        private final int tokensSaved;

        public MicrocompactResult(List<Map<String, Object>> messages, int tokensSaved) {
            this.messages = messages;
            this.tokensSaved = tokensSaved;
        }

        public List<Map<String, Object>> getMessages() {
            return messages;
        }

        public int getTokensSaved() {
            return tokensSaved;
        }
    }

    // ==================== State Reset ====================

    /**
     * Reset time-based microcompact state.
     * Called when time-based microcompact fires and invalidates cache,
     * or when post-compact cleanup runs.
     */
    public static void resetTimeBasedState() {
        cachedMicroCompact = null;
    }

    // ==================== CachedMicroCompact Path ====================

    /**
     * Cached microcompact state for fast path reuse.
     * When cache is valid, we skip re-running microcompact.
     */
    private static class CachedMicroCompactState {
        /** Session key this cache belongs to */
        final String sessionKey;
        /** Timestamp when cache was created */
        final long timestampMs;
        /** List of tool IDs that were cleared */
        final List<String> clearedToolIds;
        /** Tokens saved estimate */
        final int tokensSaved;
        /** Messages snapshot this cache applies to */
        final int messageCount;

        CachedMicroCompactState(String sessionKey, List<String> clearedToolIds, int tokensSaved, int messageCount) {
            this.sessionKey = sessionKey;
            this.timestampMs = System.currentTimeMillis();
            this.clearedToolIds = List.copyOf(clearedToolIds);
            this.tokensSaved = tokensSaved;
            this.messageCount = messageCount;
        }

        /**
         * Check if this cache is still valid for the given session and messages.
         * Cache expires after CACHE_EXPIRY_MINUTES or if session changes.
         */
        boolean isValid(String sessionKey, int currentMessageCount) {
            if (!this.sessionKey.equals(sessionKey)) {
                return false;
            }
            // Cache expires after 5 minutes
            long ageMs = System.currentTimeMillis() - timestampMs;
            if (ageMs > CACHE_EXPIRY_MS) {
                return false;
            }
            // Only valid if message count hasn't decreased (messages were added, not removed)
            return currentMessageCount >= this.messageCount;
        }
    }

    /** Cache expiry: 5 minutes */
    private static final long CACHE_EXPIRY_MS = 5 * 60 * 1000;

    /** Current cached state (session-scoped in real impl) */
    private static volatile CachedMicroCompactState cachedMicroCompact = null;

    /**
     * Get cached microcompact result if valid.
     *
     * @param sessionKey Current session key
     * @param currentMessageCount Current number of messages
     * @return Cached result if valid, null otherwise
     */
    public static MicrocompactResult getCachedMicroCompact(String sessionKey, int currentMessageCount) {
        CachedMicroCompactState cache = cachedMicroCompact;
        if (cache == null) {
            return null;
        }
        if (!cache.isValid(sessionKey, currentMessageCount)) {
            // Cache invalid, clear it
            cachedMicroCompact = null;
            return null;
        }
        // Return a result indicating cache hit (no messages modified, just state info)
        return new MicrocompactResult(null, cache.tokensSaved);
    }

    /**
     * Store microcompact result in cache.
     */
    private static void cacheMicroCompactResult(
            String sessionKey,
            List<String> clearedToolIds,
            int tokensSaved,
            int messageCount) {
        cachedMicroCompact = new CachedMicroCompactState(sessionKey, clearedToolIds, tokensSaved, messageCount);
    }

    /**
     * Invalidate the microcompact cache.
     * Called when context changes significantly or after compaction.
     */
    public static void invalidateMicroCompactCache() {
        cachedMicroCompact = null;
    }

    /**
     * Check if cached microcompact is available for given session.
     */
    public static boolean hasCachedMicroCompact(String sessionKey, int currentMessageCount) {
        CachedMicroCompactState cache = cachedMicroCompact;
        return cache != null && cache.isValid(sessionKey, currentMessageCount);
    }

    /**
     * Run time-based microcompact if conditions are met.
     * Uses cached result if available.
     *
     * @param messages The message list (will be modified if microcompact runs)
     * @param sessionKey Current session key
     * @param lastAssistantTimestampMs Timestamp of last assistant message
     * @param config Time-based config
     * @return MicrocompactResult with modified messages and tokens saved
     */
    public static MicrocompactResult runTimeBasedMicroCompactIfNeeded(
            List<Map<String, Object>> messages,
            String sessionKey,
            long lastAssistantTimestampMs,
            TimeBasedMCConfig config) {

        if (messages == null || messages.isEmpty()) {
            return new MicrocompactResult(messages, 0);
        }

        int currentMessageCount = messages.size();

        // Check cache first
        if (hasCachedMicroCompact(sessionKey, currentMessageCount)) {
            // Cache hit - return cached result without modifying messages
            // Messages already modified from previous run, just report same savings
            CachedMicroCompactState cache = cachedMicroCompact;
            return new MicrocompactResult(messages, cache.tokensSaved);
        }

        // Evaluate trigger
        int gapMinutes = evaluateTimeBasedTrigger(messages, lastAssistantTimestampMs, config);
        if (gapMinutes < 0) {
            return new MicrocompactResult(messages, 0);
        }

        // Run microcompact
        int keepRecent = config.keepRecent;
        MicrocompactResult result = microcompactMessages(messages, keepRecent);

        if (result.getTokensSaved() > 0) {
            // Collect cleared IDs for cache
            List<String> clearedIds = collectClearedToolIds(messages, keepRecent);
            // Cache the result
            cacheMicroCompactResult(sessionKey, clearedIds, result.getTokensSaved(), currentMessageCount);
        }

        return result;
    }

    /**
     * Collect tool IDs that were cleared in the last microcompact run.
     */
    private static List<String> collectClearedToolIds(List<Map<String, Object>> messages, int keepRecent) {
        List<String> compactableIds = collectCompactableToolIds(messages);
        if (compactableIds.isEmpty()) {
            return List.of();
        }

        int effectiveKeep = Math.max(1, keepRecent);
        Set<String> keepSet = compactableIds.size() <= effectiveKeep
                ? Set.of()
                : Set.of(compactableIds.subList(
                        compactableIds.size() - effectiveKeep,
                        compactableIds.size()).toArray(new String[0]));

        List<String> cleared = new ArrayList<>();
        for (String id : compactableIds) {
            if (!keepSet.contains(id)) {
                cleared.add(id);
            }
        }
        return cleared;
    }
}
