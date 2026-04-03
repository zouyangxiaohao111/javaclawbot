package agent.tool.shell;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Atomic replication of Claude Code shell/prefix.ts.
 *
 * Original source: src/utils/shell/prefix.ts
 *
 * Shared command prefix extraction using Haiku LLM.
 * Provides a factory for creating command prefix extractors
 * that can be used by different shell tools. The core logic
 * (Haiku query, response validation) is shared, while tool-specific
 * aspects (examples, pre-checks) are configurable.
 */
public final class Prefix {

    private Prefix() {}

    // ========================================================================
    // DANGEROUS_SHELL_PREFIXES — Original source: prefix.ts lines 28-44
    // ========================================================================

    /**
     * Shell executables that must never be accepted as bare prefixes.
     * Allowing e.g. "bash:*" would let any command through, defeating
     * the permission system. Includes Unix shells and Windows equivalents.
     *
     * Original source: src/utils/shell/prefix.ts → DANGEROUS_SHELL_PREFIXES
     */
    public static final Set<String> DANGEROUS_SHELL_PREFIXES = Set.of(
            "sh", "bash", "zsh", "fish", "csh", "tcsh", "ksh", "dash",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe",
            "bash.exe"
    );

    // ========================================================================
    // CommandPrefixResult — Original source: prefix.ts lines 49-52
    // ========================================================================

    /**
     * Result of command prefix extraction.
     *
     * Original source: src/utils/shell/prefix.ts → CommandPrefixResult
     *
     * @param commandPrefix The detected command prefix, or null if no prefix could be determined
     */
    public record CommandPrefixResult(String commandPrefix) {
        public CommandPrefixResult {
            commandPrefix = commandPrefix; // nullable
        }
    }

    // ========================================================================
    // CommandSubcommandPrefixResult — Original source: prefix.ts lines 57-59
    // ========================================================================

    /**
     * Result including subcommand prefixes for compound commands.
     *
     * Original source: src/utils/shell/prefix.ts → CommandSubcommandPrefixResult
     */
    public record CommandSubcommandPrefixResult(
            String commandPrefix,
            Map<String, CommandPrefixResult> subcommandPrefixes
    ) {}

    // ========================================================================
    // PrefixExtractorConfig — Original source: prefix.ts lines 64-78
    // ========================================================================

    /**
     * Configuration for creating a command prefix extractor.
     *
     * Original source: src/utils/shell/prefix.ts → PrefixExtractorConfig
     *
     * @param toolName    Tool name for logging and warning messages
     * @param policySpec  The policy spec containing examples for the LLM
     * @param eventName   Analytics event name for logging
     * @param querySource Query source identifier for the API call
     * @param preCheck    Optional pre-check function that can short-circuit the LLM call
     */
    public record PrefixExtractorConfig(
            String toolName,
            String policySpec,
            String eventName,
            String querySource,
            Function<String, CommandPrefixResult> preCheck
    ) {}

    // ========================================================================
    // createCommandPrefixExtractor — Original source: prefix.ts lines 92-126
    // ========================================================================

    /**
     * Creates a memoized command prefix extractor function.
     *
     * Original source: src/utils/shell/prefix.ts → createCommandPrefixExtractor()
     *
     * Uses two-layer memoization: the outer memoized function creates the promise
     * and attaches a .catch handler that evicts the cache entry on rejection.
     * This prevents aborted or failed LLM calls from poisoning future lookups.
     *
     * Bounded to 200 entries via LRU to prevent unbounded growth in heavy sessions.
     *
     * @param config Configuration for the extractor
     * @return A memoized function that extracts command prefixes
     */
    public static Function<String, CommandPrefixResult> createCommandPrefixExtractor(PrefixExtractorConfig config) {
        // Original: prefix.ts lines 93-126
        // In the full Claude Code, this uses memoizeWithLRU(200) and calls queryHaiku.
        // In Java, we stub the LLM call with a simple heuristic.

        return (command) -> {
            // Pre-check if provided
            if (config.preCheck() != null) {
                CommandPrefixResult preCheckResult = config.preCheck().apply(command);
                if (preCheckResult != null) {
                    return preCheckResult;
                }
            }

            // Stub: In Claude Code, this calls queryHaiku() to determine the prefix.
            // Original: prefix.ts lines 220-243
            // The full implementation sends the command to Claude Haiku with the policy spec
            // and validates the response (prefix must be actual prefix of command,
            // not a dangerous shell prefix, not "none", etc.)
            //
            // Java stub: return null (no prefix extraction without LLM)
            // TODO: Integrate with LLM API for full prefix extraction
            return null;
        };
    }

    // ========================================================================
    // createSubcommandPrefixExtractor — Original source: prefix.ts lines 138-170
    // ========================================================================

    /**
     * Creates a memoized function to get prefixes for compound commands with subcommands.
     *
     * Original source: src/utils/shell/prefix.ts → createSubcommandPrefixExtractor()
     *
     * @param getPrefix    The single-command prefix extractor
     * @param splitCommand Function to split a compound command into subcommands
     * @return A memoized function that extracts prefixes for the main command and all subcommands
     */
    public static Function<String, CommandSubcommandPrefixResult> createSubcommandPrefixExtractor(
            Function<String, CommandPrefixResult> getPrefix,
            Function<String, String[]> splitCommand
    ) {
        // Original: prefix.ts lines 142-170
        return (command) -> {
            // Stub: In Claude Code, this splits the command and queries for each subcommand prefix
            // Java stub: return null
            // TODO: Integrate with LLM API for full subcommand prefix extraction
            return null;
        };
    }

    // ========================================================================
    // getCommandPrefixImpl — Original source: prefix.ts lines 172-330
    // ========================================================================

    /**
     * Implementation of command prefix extraction.
     *
     * Original source: src/utils/shell/prefix.ts → getCommandPrefixImpl()
     *
     * In Claude Code, this:
     * 1. Sends the command to Haiku LLM with the policy spec
     * 2. Validates the response (prefix must be actual prefix of command)
     * 3. Checks for dangerous shell prefixes
     * 4. Logs analytics events
     *
     * In Java, this is a stub that returns null (no LLM available).
     * Can be replaced with a real implementation when an LLM API is available.
     */
    static CommandPrefixResult getCommandPrefixImpl(
            String command,
            String toolName,
            String policySpec,
            String eventName,
            String querySource,
            Function<String, CommandPrefixResult> preCheck
    ) {
        // Original: prefix.ts lines 172-330
        // Full implementation sends command to queryHaiku() API

        // Pre-check
        if (preCheck != null) {
            CommandPrefixResult preCheckResult = preCheck.apply(command);
            if (preCheckResult != null) {
                return preCheckResult;
            }
        }

        // Stub: no LLM in Java
        // The full implementation would:
        // 1. Set a 10s warning timeout
        // 2. Call queryHaiku with system prompt + policy spec + command
        // 3. Validate response: check for API errors, command_injection_detected,
        //    dangerous_shell_prefix, "none", and that prefix is actual prefix of command
        // 4. Log analytics event with success/failure and duration
        return null;
    }

    // ========================================================================
    // isHelpCommand — helper for Bash tool pre-check
    // ========================================================================

    /**
     * Check if a command is a help/version query.
     *
     * Original source: src/utils/shell/prefix.ts → isHelpCommand (referenced in BashTool)
     *
     * Help commands don't need prefix extraction — they're always safe.
     *
     * @param command The command to check
     * @return CommandPrefixResult with "help" prefix if it's a help command, null otherwise
     */
    public static CommandPrefixResult isHelpCommand(String command) {
        String trimmed = command.trim().toLowerCase();
        if (trimmed.endsWith("--help") || trimmed.endsWith("-h") ||
                trimmed.endsWith("--version") || trimmed.endsWith("-v") ||
                trimmed.endsWith("-V")) {
            return new CommandPrefixResult(null);
        }
        return null;
    }
}
