package context.auto;

import java.util.List;
import java.util.Map;

/**
 * Auto-Compact Service aligned with Open-ClaudeCode.
 *
 * Main orchestration for automatic context compaction.
 *
 * Key differences from old implementation:
 * - Token-based threshold: tokenCount >= (effectiveContextWindow - 13,000)
 * - Circuit breaker: stops after 3 consecutive failures
 * - Session memory fast path before legacy compact
 * - Proper boundary markers and post-compact cleanup
 */
public class AutoCompactService {

    private AutoCompactService() {}

    /**
     * Tracking state for auto-compact.
     */
    public static class AutoCompactTrackingState {
        /** Whether compaction has occurred */
        public boolean compacted = false;
        /** Turn counter since last compaction */
        public int turnCounter = 0;
        /** Unique ID per turn */
        public String turnId;
        /** Consecutive compaction failures (circuit breaker) */
        public int consecutiveFailures = 0;

        public AutoCompactTrackingState() {
            this.turnId = java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /**
     * Query sources that should skip auto-compact.
     */
    private static final String SOURCE_SESSION_MEMORY = "session_memory";
    private static final String SOURCE_COMPACT = "compact";

    /**
     * Check if auto-compact should run.
     *
     * @param messages Current message list
     * @param tokenUsage Current token usage
     * @param effectiveContextWindow Effective context window (after maxOutput reserve)
     * @param autoCompactEnabled Whether auto-compact is enabled
     * @param querySource Source of the query (can be null)
     * @param tracking Current tracking state
     * @param snipTokensFreed Tokens that will be freed by snipping before compaction
     * @return true if auto-compact should be triggered
     */
    public static boolean shouldAutoCompact(
            List<Map<String, Object>> messages,
            long tokenUsage,
            int effectiveContextWindow,
            boolean autoCompactEnabled,
            String querySource,
            AutoCompactTrackingState tracking,
            long snipTokensFreed) {

        // Skip for forked agents that would deadlock
        if (SOURCE_SESSION_MEMORY.equals(querySource) || SOURCE_COMPACT.equals(querySource)) {
            return false;
        }

        // TODO [Feature Gates]: marble_origami query source exclusion
        // Open-ClaudeCode: autoCompact.ts:174-183
        // if (querySource === 'marble_origami') return false;
        // javaclawbot: N/A - no marble_origami ctx-agent concept

        // TODO [Feature Gates]: CONTEXT_COLLAPSE feature gate
        // Open-ClaudeCode: autoCompact.ts:215-223
        // if (feature('CONTEXT_COLLAPSE')) {
        //     if (isContextCollapseEnabled()) return false;
        // }
        // javaclawbot: N/A - no CONTEXT_COLLAPSE system implemented

        // TODO [Feature Gates]: REACTIVE_COMPACT feature gate
        // Open-ClaudeCode: autoCompact.ts:189-199
        // if (feature('REACTIVE_COMPACT')) {
        //     if (getFeatureValue_CACHED_MAY_BE_STALE('tengu_cobalt_raccoon', false)) return false;
        // }
        // javaclawbot: N/A - no REACTIVE_COMPACT passive compaction mode

        // TODO [Feature Gates]: GrowthBook feature flags
        // Open-ClaudeCode: autoCompact.ts (various)
        // - tengu_compact_cache_prefix: controls forked agent path for prompt cache sharing
        // - tengu_cobalt_raccoon: REACTIVE_COMPACT mode switch
        // - PROMPT_CACHE_BREAK_DETECTION: controls cache break notification
        // javaclawbot: N/A - no GrowthBook A/B testing framework

        if (!autoCompactEnabled) {
            return false;
        }

        int threshold = AutoCompactThreshold.getAutoCompactThreshold(effectiveContextWindow);

        // Adjust threshold if snipping will free tokens
        // Only trigger if usage AFTER snipping would still exceed threshold
        // Note: Circuit breaker is checked in checkAndExecuteContextCompress() (AgentLoop.java),
        // not here - this method only determines if compaction SHOULD run
        long effectiveUsage = tokenUsage - snipTokensFreed;
        return effectiveUsage >= threshold;
    }

    /**
     * Calculate the effective context window.
     *
     * @param contextWindow Model's context window
     * @param maxOutputTokens Model's max output tokens
     * @return Effective context window for input
     */
    public static int calculateEffectiveContextWindow(int contextWindow, int maxOutputTokens) {
        return AutoCompactThreshold.calculateEffectiveContextWindow(contextWindow, maxOutputTokens);
    }

    /**
     * Get the auto-compact threshold.
     */
    public static int getAutoCompactThreshold(int effectiveContextWindow) {
        return AutoCompactThreshold.getAutoCompactThreshold(effectiveContextWindow);
    }

    /**
     * Get current token usage from messages.
     *
     * @param messages Current message list
     * @return Estimated token count
     */
    public static long getTokenUsageFromMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;

        long total = 0;
        for (Map<String, Object> msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    private static long estimateMessageTokens(Map<String, Object> message) {
        if (message == null) return 0;

        Object role = message.get("role");

        if ("user".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return roughTokenCountEstimation(s);
            }
            if (content instanceof List<?> list) {
                long sum = 0;
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        String type = map.get("type") instanceof String t ? t : null;
                        if ("text".equals(type) && map.get("text") instanceof String t) {
                            sum += roughTokenCountEstimation(t);
                        } else if ("image".equals(type) || "document".equals(type)) {
                            sum += 2000;
                        }
                    }
                }
                return sum;
            }
        }

        if ("assistant".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return roughTokenCountEstimation(s);
            }
            if (content instanceof List<?> list) {
                long sum = 0;
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        String type = map.get("type") instanceof String t ? t : null;
                        if ("text".equals(type) && map.get("text") instanceof String t) {
                            sum += roughTokenCountEstimation(t);
                        } else if ("tool_use".equals(type)) {
                            Object input = map.get("input");
                            if (input != null) {
                                sum += roughTokenCountEstimation(input.toString());
                            }
                        }
                    }
                }
                return sum;
            }
        }

        return 64;
    }

    private static long roughTokenCountEstimation(String text) {
        if (text == null) return 0;
        return (text.length() / 4) + 1;
    }

    /**
     * Auto-compact result.
     */
    public static class AutoCompactResult {
        /** Whether compaction occurred */
        public final boolean wasCompacted;
        /** Compaction result details */
        public final CompactionResult compactionResult;
        /** Updated consecutive failure count */
        public final int consecutiveFailures;

        public AutoCompactResult(boolean wasCompacted, CompactionResult compactionResult, int consecutiveFailures) {
            this.wasCompacted = wasCompacted;
            this.compactionResult = compactionResult;
            this.consecutiveFailures = consecutiveFailures;
        }
    }

    /**
     * Check if auto-compact is enabled via environment/config.
     * Aligned with Open-ClaudeCode: autoCompact.ts:147-158
     *
     * Checks:
     * 1. DISABLE_COMPACT env var - completely disables all compaction
     * 2. DISABLE_AUTO_COMPACT env var - disables only auto-compact (manual /compact still works)
     * 3. configSetting - user's config preference
     */
    public static boolean isAutoCompactEnabled(boolean configSetting) {
        // Check DISABLE_COMPACT env var
        String disableCompact = System.getenv("DISABLE_COMPACT");
        if (disableCompact != null && !disableCompact.isEmpty()) {
            return false;
        }
        // Check DISABLE_AUTO_COMPACT env var
        String disableAutoCompact = System.getenv("DISABLE_AUTO_COMPACT");
        if (disableAutoCompact != null && !disableAutoCompact.isEmpty()) {
            return false;
        }
        return configSetting;
    }

    /**
     * Get token warning state.
     */
    public static AutoCompactThreshold.TokenWarningState getTokenWarningState(
            long tokenUsage,
            int effectiveContextWindow,
            boolean autoCompactEnabled) {

        return AutoCompactThreshold.calculateTokenWarningState(
                (int) tokenUsage,
                effectiveContextWindow,
                autoCompactEnabled
        );
    }
}
