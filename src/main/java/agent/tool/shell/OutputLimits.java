package agent.tool.shell;

/**
 * Atomic replication of Claude Code shell/outputLimits.ts.
 *
 * Original source: src/utils/shell/outputLimits.ts
 *
 * Manages maximum output length for bash tool results.
 * Can be overridden via BASH_MAX_OUTPUT_LENGTH environment variable.
 *
 * Default: 30,000 chars
 * Upper limit: 150,000 chars
 */
public final class OutputLimits {

    private OutputLimits() {}

    /**
     * Upper limit for bash output length.
     *
     * Original source: src/utils/shell/outputLimits.ts → BASH_MAX_OUTPUT_UPPER_LIMIT
     */
    public static final int BASH_MAX_OUTPUT_UPPER_LIMIT = 150_000;

    /**
     * Default max output length.
     *
     * Original source: src/utils/shell/outputLimits.ts → BASH_MAX_OUTPUT_DEFAULT
     */
    public static final int BASH_MAX_OUTPUT_DEFAULT = 30_000;

    /**
     * Get the effective max output length.
     *
     * Original source: src/utils/shell/outputLimits.ts → getMaxOutputLength()
     *
     * Checks BASH_MAX_OUTPUT_LENGTH env var, clamps to [0, BASH_MAX_OUTPUT_UPPER_LIMIT].
     * Falls back to BASH_MAX_OUTPUT_DEFAULT if env var is not set or invalid.
     *
     * @return The effective max output length in characters
     */
    public static int getMaxOutputLength() {
        String envValue = System.getenv("BASH_MAX_OUTPUT_LENGTH");
        if (envValue == null || envValue.isBlank()) {
            return BASH_MAX_OUTPUT_DEFAULT;
        }

        try {
            int parsed = Integer.parseInt(envValue.trim());
            // Validate bounded range
            if (parsed <= 0) {
                return BASH_MAX_OUTPUT_DEFAULT;
            }
            return Math.min(parsed, BASH_MAX_OUTPUT_UPPER_LIMIT);
        } catch (NumberFormatException e) {
            return BASH_MAX_OUTPUT_DEFAULT;
        }
    }
}
