package context.auto;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compact Warning State aligned with Open-ClaudeCode.
 *
 * Tracks whether the "context left until autocompact" warning should be suppressed.
 * We suppress immediately after successful compaction since we don't have accurate
 * token counts until the next API response.
 */
public class CompactWarningState {

    private CompactWarningState() {}

    /**
     * Tracks whether the compact warning is suppressed.
     * Initially false (warnings enabled).
     */
    private static final AtomicBoolean warningSuppressed = new AtomicBoolean(false);

    /**
     * Suppress the compact warning.
     * Call after successful compaction.
     */
    public static void suppressCompactWarning() {
        warningSuppressed.set(true);
    }

    /**
     * Clear the compact warning suppression.
     * Called at start of new compact attempt.
     */
    public static void clearCompactWarningSuppression() {
        warningSuppressed.set(false);
    }

    /**
     * Check if the compact warning is currently suppressed.
     */
    public static boolean isWarningSuppressed() {
        return warningSuppressed.get();
    }

    /**
     * Get the compact warning state.
     */
    public static CompactWarningStateSnapshot getSnapshot() {
        return new CompactWarningStateSnapshot(warningSuppressed.get());
    }

    /**
     * Snapshot of warning state at a point in time.
     */
    public record CompactWarningStateSnapshot(
            boolean suppressed
    ) {}
}
