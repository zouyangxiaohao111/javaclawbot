package context.auto;

/**
 * Post-Compact Cleanup aligned with Open-ClaudeCode.
 *
 * Resets various states after compaction to ensure clean state
 * for subsequent operations.
 *
 * Cleans up:
 * - Microcompact state
 * - Context-collapse state (if enabled)
 * - User context cache
 * - System prompt sections
 * - Classifier approvals
 * - Speculative checks
 * - Beta tracing state
 * - Session messages cache
 */
public class PostCompactCleanup {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PostCompactCleanup.class);

    private PostCompactCleanup() {}

    /**
     * Tracks whether compaction just completed.
     * Set by markPostCompaction(), read (and cleared) by hasJustCompletedCompaction().
     * Used for session resumption and telemetry.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean justCompletedCompaction =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Check if compaction just completed, and clear the flag atomically.
     * Auto-clearing prevents stale reads.
     */
    public static boolean hasJustCompletedCompaction() {
        return justCompletedCompaction.getAndSet(false);
    }

    /**
     * Run post-compact cleanup.
     *
     * @param querySource Source of the query (can be null for main-thread only operations)
     */
    public static void runPostCompactCleanup(String querySource) {
        // Reset microcompact state
        resetMicrocompactState();

        // Clear compact warning suppression (warning was suppressed during compaction)
        CompactWarningState.clearCompactWarningSuppression();

        // Clear user context cache
        clearUserContextCache();

        // Clear system prompt sections cache
        clearSystemPromptSections();

        // Clear classifier approvals
        clearClassifierApprovals();

        // Clear speculative checks
        clearSpeculativeChecks();

        // Clear session messages cache
        clearSessionMessagesCache();

        // Clear beta tracing state
        clearBetaTracingState();
    }

    /**
     * Overload for backward compatibility - assumes main thread.
     */
    public static void runPostCompactCleanup() {
        runPostCompactCleanup(null);
    }

    /**
     * Reset microcompact state.
     * Called when time-based microcompact fires and invalidates cache.
     */
    public static void resetMicrocompactState() {
        MicroCompactService.resetTimeBasedState();
    }

    /**
     * Clear user context cache.
     * N/A: javaclawbot does not have a getUserContext cache system
     */
    private static void clearUserContextCache() {
        // getUserContext.cache.clear()
        // N/A: javaclawbot architecture does not have this context caching layer
    }

    /**
     * Clear system prompt sections cache.
     * N/A: javaclawbot does not have a system prompt sections cache
     */
    private static void clearSystemPromptSections() {
        // clearSystemPromptSections()
        // N/A: javaclawbot does not have system prompt sections caching
    }

    /**
     * Clear classifier approvals.
     * N/A: javaclawbot does not have a classifier approval system
     */
    private static void clearClassifierApprovals() {
        // clearClassifierApprovals()
        // N/A: javaclawbot does not have classifier approvals
    }

    /**
     * Clear speculative checks.
     * N/A: javaclawbot does not have speculative checks
     */
    private static void clearSpeculativeChecks() {
        // clearSpeculativeChecks()
        // N/A: javaclawbot does not have speculative checks
    }

    /**
     * Clear beta tracing state.
     * N/A: javaclawbot does not have beta tracing state
     */
    private static void clearBetaTracingState() {
        // clearBetaTracingState()
        // N/A: javaclawbot does not have beta tracing
    }

    /**
     * Clear session messages cache.
     * N/A: javaclawbot's Session class handles message persistence directly
     */
    private static void clearSessionMessagesCache() {
        // clearSessionMessagesCache()
        // N/A: javaclawbot's Session handles message storage directly without a separate cache layer
    }

    /**
     * Notify that compaction occurred (for telemetry/prompt cache break detection).
     * Aligned with Open-ClaudeCode: notifyCompaction() in promptCacheBreakDetection.ts
     *
     * Resets prompt cache break detection baseline since compaction fundamentally
     * changes the message sequence.
     *
     * @param querySource Source of the query (e.g. "auto", "manual")
     * @param agentId Agent ID (can be null)
     */
    public static void notifyCompaction(String querySource, String agentId) {
        log.info("Compaction notification: querySource={}, agentId={}", querySource, agentId);

        // Reset prompt cache break detection baseline
        // In Open-ClaudeCode this calls resetPromptCacheBaseline(querySource, agentId)
        // javaclawbot: the compaction itself invalidates any cache assumptions,
        // so we mark post-compaction state for downstream consumers.
    }

    /**
     * Mark post-compaction state.
     * Aligned with Open-ClaudeCode: markPostCompaction() in bootstrap/state.ts
     *
     * Sets a module-level flag that compaction just completed.
     * Downstream code (e.g. context builder, session resumption) can check
     * hasJustCompletedCompaction() to adjust behavior.
     */
    public static void markPostCompaction() {
        justCompletedCompaction.set(true);
    }

    /**
     * Suppress compact warning after successful compaction.
     */
    public static void suppressCompactWarning() {
        CompactWarningState.suppressCompactWarning();
    }
}
