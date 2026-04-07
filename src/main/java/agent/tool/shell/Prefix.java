package agent.tool.shell;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Pattern;

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
 *
 * Key features:
 * - Dangerous shell prefix detection (prevents permission bypass)
 * - Command prefix extraction via LLM or heuristic fallback
 * - Subcommand prefix extraction for compound commands
 * - Help/version command detection
 * - Response validation (prefix must be actual prefix, not dangerous)
 * - LRU memoization bounded to 200 entries
 */
public final class Prefix {

    private static final System.Logger log = System.getLogger(Prefix.class.getName());

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
    // MAX_LRU_SIZE — Original source: prefix.ts line 92
    // ========================================================================

    /** Maximum LRU cache size for memoization. */
    private static final int MAX_LRU_SIZE = 200;

    // ========================================================================
    // CommandPrefixResult — Original source: prefix.ts lines 49-52
    // ========================================================================

    /**
     * Result of command prefix extraction.
     *
     * Original source: src/utils/shell/prefix.ts → CommandPrefixResult
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
     * Bounded to 200 entries via LRU to prevent unbounded growth.
     *
     * @param config Configuration for the extractor
     * @return A memoized function that extracts command prefixes
     */
    public static Function<String, CommandPrefixResult> createCommandPrefixExtractor(PrefixExtractorConfig config) {
        // LRU cache for memoization
        LinkedHashMap<String, CommandPrefixResult> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CommandPrefixResult> eldest) {
                return size() > MAX_LRU_SIZE;
            }
        };

        return (command) -> {
            if (command == null || command.isEmpty()) {
                log.log(System.Logger.Level.DEBUG, "[{0}] Empty command, returning null", config.toolName());
                return null;
            }

            // Check cache
            CommandPrefixResult cached = cache.get(command);
            if (cached != null) {
                log.log(System.Logger.Level.DEBUG, "[{0}] Cache hit for command prefix", config.toolName());
                return cached;
            }

            try {
                CommandPrefixResult result = getCommandPrefixImpl(
                    command, config.toolName(), config.policySpec(),
                    config.eventName(), config.querySource(), config.preCheck()
                );

                if (result != null) {
                    cache.put(command, result);
                    log.log(System.Logger.Level.DEBUG, "[{0}] Cached prefix result: {1}",
                            config.toolName(), result.commandPrefix());
                }

                return result;
            } catch (Exception e) {
                // Evict on failure (matches CC's .catch handler)
                cache.remove(command);
                log.log(System.Logger.Level.WARNING, "[{0}] Prefix extraction failed, evicted from cache: {1}",
                        config.toolName(), e.getMessage());
                return null;
            }
        };
    }

    // ========================================================================
    // createSubcommandPrefixExtractor — Original source: prefix.ts lines 138-170
    // ========================================================================

    /**
     * Creates a memoized function to get prefixes for compound commands.
     *
     * Original source: src/utils/shell/prefix.ts → createSubcommandPrefixExtractor()
     *
     * Splits the command on compound operators and queries for each subcommand.
     *
     * @param getPrefix    The single-command prefix extractor
     * @param splitCommand Function to split a compound command into subcommands
     * @return A memoized function that extracts prefixes for all subcommands
     */
    public static Function<String, CommandSubcommandPrefixResult> createSubcommandPrefixExtractor(
            Function<String, CommandPrefixResult> getPrefix,
            Function<String, String[]> splitCommand
    ) {
        // LRU cache for subcommand results
        LinkedHashMap<String, CommandSubcommandPrefixResult> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CommandSubcommandPrefixResult> eldest) {
                return size() > MAX_LRU_SIZE;
            }
        };

        return (command) -> {
            if (command == null || command.isEmpty()) return null;

            CommandSubcommandPrefixResult cached = cache.get(command);
            if (cached != null) return cached;

            try {
                // Get main prefix
                CommandPrefixResult mainPrefix = getPrefix.apply(command);
                String mainPf = mainPrefix != null ? mainPrefix.commandPrefix() : null;

                // Split and get subcommand prefixes
                String[] subcommands = splitCommand != null ? splitCommand.apply(command) : new String[0];
                Map<String, CommandPrefixResult> subPrefixes = new LinkedHashMap<>();

                for (String sub : subcommands) {
                    CommandPrefixResult subResult = getPrefix.apply(sub);
                    if (subResult != null) {
                        subPrefixes.put(sub, subResult);
                    }
                }

                CommandSubcommandPrefixResult result =
                    new CommandSubcommandPrefixResult(mainPf, subPrefixes);
                cache.put(command, result);

                log.log(System.Logger.Level.DEBUG, "Subcommand prefix extraction: main={0}, subs={1}",
                        mainPf, subPrefixes.size());

                return result;
            } catch (Exception e) {
                cache.remove(command);
                log.log(System.Logger.Level.WARNING, "Subcommand prefix extraction failed: {0}", e.getMessage());
                return null;
            }
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
     * Flow:
     * 1. Pre-check for known patterns (help, known commands)
     * 2. Parse command to extract first token (the command name)
     * 3. Validate prefix is not a dangerous shell prefix
     * 4. If LLM API available, query for accurate prefix
     * 5. Otherwise use heuristic extraction
     *
     * @param command     The command to extract prefix from
     * @param toolName    Tool name for logging
     * @param policySpec  Policy spec for LLM query
     * @param eventName   Analytics event name
     * @param querySource Query source identifier
     * @param preCheck    Optional pre-check function
     * @return CommandPrefixResult or null
     */
    static CommandPrefixResult getCommandPrefixImpl(
            String command,
            String toolName,
            String policySpec,
            String eventName,
            String querySource,
            Function<String, CommandPrefixResult> preCheck
    ) {
        long startTime = System.currentTimeMillis();
        log.log(System.Logger.Level.DEBUG, "[{0}] Extracting prefix for command (length={1})",
                toolName, command.length());

        // Step 1: Pre-check
        if (preCheck != null) {
            CommandPrefixResult preCheckResult = preCheck.apply(command);
            if (preCheckResult != null) {
                log.log(System.Logger.Level.DEBUG, "[{0}] Pre-check returned: {1}",
                        toolName, preCheckResult.commandPrefix());
                return preCheckResult;
            }
        }

        // Step 2: Heuristic extraction (original uses queryHaiku)
        // Original: prefix.ts lines 220-243
        // The full implementation sends the command to Claude Haiku with the policy spec
        // and validates the response.
        //
        // Java implementation: use command parsing to extract the command name as prefix
        CommandPrefixResult result = extractPrefixHeuristic(command, toolName);

        // Step 3: Validate the result
        if (result != null && result.commandPrefix() != null) {
            String prefix = result.commandPrefix();

            // Check: prefix must be actual prefix of command
            if (!command.startsWith(prefix) && !command.trim().startsWith(prefix)) {
                log.log(System.Logger.Level.WARNING,
                    "[{0}] Extracted prefix ''{1}'' is not a prefix of command, discarding",
                    toolName, prefix);
                result = null;
            }

            // Check: not a dangerous shell prefix
            if (result != null && DANGEROUS_SHELL_PREFIXES.contains(prefix)) {
                log.log(System.Logger.Level.WARNING,
                    "[{0}] Extracted prefix ''{1}'' is a dangerous shell prefix, discarding",
                    toolName, prefix);
                result = null;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.log(System.Logger.Level.INFO,
            "[{0}] Prefix extraction completed in {1}ms: {2}",
            toolName, duration, result != null ? result.commandPrefix() : "null");

        return result;
    }

    /**
     * Heuristic prefix extraction.
     *
     * Extracts the command name (first token) from the command.
     * Handles:
     * - Environment variable assignments (skips them)
     * - Quoted commands
     * - Path-based commands (extracts basename)
     */
    private static CommandPrefixResult extractPrefixHeuristic(String command, String toolName) {
        // Parse command to extract first meaningful token
        ShellQuote.ParseResult parseResult = ShellQuote.tryParseShellCommand(command);
        if (!parseResult.isSuccess() || parseResult.getTokens().isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "[{0}] Heuristic: parse failed", toolName);
            return null;
        }

        // Skip env vars
        List<ShellQuote.ParseEntry> tokens = parseResult.getTokens();
        int start = 0;
        for (ShellQuote.ParseEntry entry : tokens) {
            if (entry.isString() && entry.getValue().contains("=")
                && Pattern.matches("^[A-Za-z_][A-Za-z0-9_]*=.*", entry.getValue())) {
                start++;
            } else {
                break;
            }
        }

        if (start >= tokens.size()) {
            log.log(System.Logger.Level.DEBUG, "[{0}] Heuristic: only env vars found", toolName);
            return null;
        }

        ShellQuote.ParseEntry firstEntry = tokens.get(start);
        if (!firstEntry.isString()) {
            log.log(System.Logger.Level.DEBUG, "[{0}] Heuristic: first non-env token is not a string", toolName);
            return null;
        }

        String prefix = firstEntry.getValue();

        // Extract basename for path-based commands
        int lastSlash = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < prefix.length() - 1) {
            prefix = prefix.substring(lastSlash + 1);
        }

        // Validate prefix is not empty
        if (prefix.isEmpty()) {
            return null;
        }

        log.log(System.Logger.Level.DEBUG, "[{0}] Heuristic extracted prefix: {1}", toolName, prefix);
        return new CommandPrefixResult(prefix);
    }

    // ========================================================================
    // isHelpCommand — Original source: prefix.ts lines 332-340
    // ========================================================================

    /**
     * Check if a command is a help/version query.
     *
     * Original source: src/utils/shell/prefix.ts → isHelpCommand()
     *
     * Help commands don't need prefix extraction — they're always safe.
     *
     * @param command The command to check
     * @return CommandPrefixResult with null prefix if help command, null otherwise
     */
    public static CommandPrefixResult isHelpCommand(String command) {
        if (command == null) return null;
        String trimmed = command.trim().toLowerCase();
        if (trimmed.endsWith("--help") || trimmed.endsWith("-h") ||
                trimmed.endsWith("--version") || trimmed.endsWith("-v") ||
                trimmed.endsWith("-V")) {
            log.log(System.Logger.Level.DEBUG, "Help command detected: {0}", command);
            return new CommandPrefixResult(null);
        }
        return null;
    }

    // ========================================================================
    // validatePrefix — Original source: prefix.ts lines 248-290
    // ========================================================================

    /**
     * Validate an extracted prefix.
     *
     * Original source: src/utils/shell/prefix.ts → validation in getCommandPrefixImpl()
     *
     * Checks:
     * 1. Prefix is not empty
     * 2. Prefix is an actual prefix of the command
     * 3. Prefix is not a dangerous shell prefix
     * 4. Prefix is not "none"
     * 5. Prefix does not contain injection patterns
     *
     * @param prefix  The extracted prefix
     * @param command The original command
     * @return true if the prefix is valid
     */
    public static boolean validatePrefix(String prefix, String command) {
        if (prefix == null || prefix.isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "Prefix validation failed: empty prefix");
            return false;
        }

        // Check "none" sentinel value
        if ("none".equalsIgnoreCase(prefix)) {
            log.log(System.Logger.Level.DEBUG, "Prefix validation failed: 'none' sentinel");
            return false;
        }

        // Check dangerous shell prefixes
        if (DANGEROUS_SHELL_PREFIXES.contains(prefix)) {
            log.log(System.Logger.Level.WARNING, "Prefix validation failed: dangerous shell prefix ''{0}''", prefix);
            return false;
        }

        // Check prefix is actual prefix of command
        String trimmedCommand = command.trim();
        if (!trimmedCommand.startsWith(prefix)) {
            log.log(System.Logger.Level.DEBUG,
                "Prefix validation failed: ''{0}'' is not a prefix of command", prefix);
            return false;
        }

        // Check for injection patterns in prefix
        if (prefix.contains(";") || prefix.contains("|") || prefix.contains("&")
            || prefix.contains("$") || prefix.contains("`") || prefix.contains("(")) {
            log.log(System.Logger.Level.WARNING,
                "Prefix validation failed: injection pattern in ''{0}''", prefix);
            return false;
        }

        return true;
    }
}
