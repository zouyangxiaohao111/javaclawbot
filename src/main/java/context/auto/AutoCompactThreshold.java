package context.auto;

/**
 * Auto-compact threshold constants aligned with Open-ClaudeCode.
 *
 * Key differences from old implementation:
 * - Old: contextRatio = tokens/window > 0.90
 * - New: tokens >= (effectiveContextWindow - 13,000)
 *
 * The 13,000 token buffer ensures:
 * 1. Compression process itself has space to execute
 * 2. Post-compression context still has room to continue
 */
public class AutoCompactThreshold {

    private AutoCompactThreshold() {}

    /**
     * Reserve this many tokens for output during compaction.
     * Based on p99.99 of compact summary output being 17,387 tokens.
     */
    public static final int MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000;

    /**
     * Buffer tokens for auto-compact trigger threshold.
     * Compaction fires when: tokenCount >= (effectiveContextWindow - 13,000)
     */
    public static final int AUTOCOMPACT_BUFFER_TOKENS = 13_000;

    /**
     * Buffer tokens for warning threshold.
     */
    public static final int WARNING_THRESHOLD_BUFFER_TOKENS = 20_000;

    /**
     * Buffer tokens for error threshold.
     */
    public static final int ERROR_THRESHOLD_BUFFER_TOKENS = 20_000;

    /**
     * Buffer tokens for manual compact blocking limit.
     */
    public static final int MANUAL_COMPACT_BUFFER_TOKENS = 3_000;

    /**
     * Stop trying autocompact after this many consecutive failures.
     * Circuit breaker prevents wasting API calls when context is irrecoverably
     * over the limit (e.g., prompt_too_long).
     */
    public static final int MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3;

    /**
     * Calculate the effective context window size for a given context window and max output tokens.
     * effectiveContextWindow = contextWindow - maxOutputTokens - reservedForSummary
     *
     * @param contextWindow The model's context window
     * @param maxOutputTokens The model's max output tokens
     * @return effective context window for input tokens
     */
    public static int calculateEffectiveContextWindow(int contextWindow, int maxOutputTokens) {
        int reservedForSummary = Math.min(maxOutputTokens, MAX_OUTPUT_TOKENS_FOR_SUMMARY);
        return contextWindow - reservedForSummary;
    }

    /**
     * Get the auto-compact threshold.
     * Compaction triggers when: tokenCount >= threshold
     *
     * @param effectiveContextWindow The effective context window size
     * @return The token count threshold for auto-compact
     */
    public static int getAutoCompactThreshold(int effectiveContextWindow) {
        return effectiveContextWindow - AUTOCOMPACT_BUFFER_TOKENS;
    }

    /**
     * Get the warning threshold.
     *
     * @param effectiveContextWindow The effective context window size
     * @return The token count threshold for warning
     */
    public static int getWarningThreshold(int effectiveContextWindow) {
        return effectiveContextWindow - WARNING_THRESHOLD_BUFFER_TOKENS;
    }

    /**
     * Get the error threshold.
     *
     * @param effectiveContextWindow The effective context window size
     * @return The token count threshold for error
     */
    public static int getErrorThreshold(int effectiveContextWindow) {
        return effectiveContextWindow - ERROR_THRESHOLD_BUFFER_TOKENS;
    }

    /**
     * Get the blocking limit for manual compact.
     *
     * @param effectiveContextWindow The effective context window size
     * @return The token count blocking limit
     */
    public static int getBlockingLimit(int effectiveContextWindow) {
        return effectiveContextWindow - MANUAL_COMPACT_BUFFER_TOKENS;
    }

    /**
     * Calculate token warning state.
     *
     * @param tokenUsage Current token usage
     * @param effectiveContextWindow The effective context window size
     * @param autoCompactEnabled Whether auto-compact is enabled
     * @return TokenWarningState with all threshold flags
     */
    public static TokenWarningState calculateTokenWarningState(
            int tokenUsage,
            int effectiveContextWindow,
            boolean autoCompactEnabled) {

        int threshold = autoCompactEnabled
                ? getAutoCompactThreshold(effectiveContextWindow)
                : effectiveContextWindow;

        int percentLeft = Math.max(0, (int) Math.round(((double) (threshold - tokenUsage) / threshold * 100)));

        int warningThreshold = threshold - WARNING_THRESHOLD_BUFFER_TOKENS;
        int errorThreshold = threshold - ERROR_THRESHOLD_BUFFER_TOKENS;

        return new TokenWarningState(
                percentLeft,
                tokenUsage >= warningThreshold,
                tokenUsage >= errorThreshold,
                autoCompactEnabled && tokenUsage >= getAutoCompactThreshold(effectiveContextWindow),
                tokenUsage >= getBlockingLimit(effectiveContextWindow)
        );
    }

    /**
     * Token warning state record
     */
    public record TokenWarningState(
            int percentLeft,
            boolean isAboveWarningThreshold,
            boolean isAboveErrorThreshold,
            boolean isAboveAutoCompactThreshold,
            boolean isAtBlockingLimit
    ) {}
}
