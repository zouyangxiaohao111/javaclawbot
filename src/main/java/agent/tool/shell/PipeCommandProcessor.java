package agent.tool.shell;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Atomic replication of Claude Code bash/bashPipeCommand.ts.
 *
 * Original source: src/utils/bash/bashPipeCommand.ts
 *
 * Rearranges a command with pipes to place stdin redirect after the first command.
 * This fixes an issue where eval treats the entire piped command as a single unit,
 * causing the stdin redirect to apply to eval itself rather than the first command.
 *
 * Security features:
 * - Skips commands with backticks (shell-quote doesn't handle them)
 * - Skips commands with $() (shell-quote parses incorrectly)
 * - Skips commands with shell variables ($VAR, ${VAR})
 * - Skips commands with control structures (for/while/until/if/case/select)
 * - Detects malformed tokens and shell-quote single quote bug
 * - Handles file descriptor redirections (2>&1, 2>/dev/null)
 * - Manages environment variable assignments
 * - Handles line continuation
 */
public final class PipeCommandProcessor {

    private static final System.Logger log = System.getLogger(PipeCommandProcessor.class.getName());

    private PipeCommandProcessor() {}

    // ========================================================================
    // rearrangePipeCommand - Original source: bashPipeCommand.ts lines 14-100
    // ========================================================================

    /**
     * Rearranges a command with pipes to place stdin redirect after the first command.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → rearrangePipeCommand()
     *
     * Strategy:
     * 1. Parse the command into tokens
     * 2. Find the first pipe operator
     * 3. Rebuild: first_command < /dev/null | rest_of_pipeline
     * 4. Fall back to eval-wrapped quoting for complex cases
     *
     * @param command The pipe command to rearrange
     * @return The properly quoted pipe command with stdin redirect
     */
    public static String rearrangePipeCommand(String command) {
        if (command == null || command.isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "Empty command, skipping pipe rearrangement");
            return quoteWithEvalStdinRedirect(command);
        }

        // Skip if command has backticks
        if (command.contains("`")) {
            log.log(System.Logger.Level.DEBUG, "Skipping pipe rearrangement: contains backticks");
            return quoteWithEvalStdinRedirect(command);
        }

        // Skip if command has command substitution $()
        if (command.contains("$(")) {
            log.log(System.Logger.Level.DEBUG, "Skipping pipe rearrangement: contains $()");
            return quoteWithEvalStdinRedirect(command);
        }

        // Skip if command references shell variables
        if (Pattern.compile("\\$[A-Za-z_{]").matcher(command).find()) {
            log.log(System.Logger.Level.DEBUG, "Skipping pipe rearrangement: contains shell variables");
            return quoteWithEvalStdinRedirect(command);
        }

        // Skip if command contains bash control structures
        if (containsControlStructure(command)) {
            log.log(System.Logger.Level.DEBUG, "Skipping pipe rearrangement: contains control structure");
            return quoteWithEvalStdinRedirect(command);
        }

        // Join continuation lines before parsing
        String joined = joinContinuationLines(command);

        // Skip if joined command contains newlines (real command separators)
        if (joined.contains("\n")) {
            log.log(System.Logger.Level.DEBUG, "Skipping pipe rearrangement: contains newlines after join");
            return quoteWithEvalStdinRedirect(command);
        }

        // Security: check for shell-quote single quote bug
        if (ShellQuote.hasShellQuoteSingleQuoteBug(joined)) {
            log.log(System.Logger.Level.WARNING, "Shell-quote bug detected, using eval fallback");
            return quoteWithEvalStdinRedirect(command);
        }

        // Parse command
        ShellQuote.ParseResult parseResult = ShellQuote.tryParseShellCommand(joined);
        if (!parseResult.isSuccess()) {
            log.log(System.Logger.Level.DEBUG, "Parse failed, using eval fallback");
            return quoteWithEvalStdinRedirect(command);
        }

        List<ShellQuote.ParseEntry> parsed = parseResult.getTokens();

        // Security: check for malformed tokens
        if (ShellQuote.hasMalformedTokens(joined, parsed)) {
            log.log(System.Logger.Level.WARNING, "Malformed tokens detected, using eval fallback");
            return quoteWithEvalStdinRedirect(command);
        }

        // Find first pipe operator
        int firstPipeIndex = findFirstPipeOperator(parsed);
        if (firstPipeIndex <= 0) {
            log.log(System.Logger.Level.DEBUG, "No pipe found at valid position, using eval fallback");
            return quoteWithEvalStdinRedirect(command);
        }

        // Rebuild: first_command < /dev/null | rest_of_pipeline
        List<String> parts = new ArrayList<>();
        parts.addAll(buildCommandParts(parsed, 0, firstPipeIndex));
        parts.add("< /dev/null");
        parts.addAll(buildCommandParts(parsed, firstPipeIndex, parsed.size()));

        String result = singleQuoteForEval(String.join(" ", parts));
        log.log(System.Logger.Level.INFO, "Pipe command rearranged successfully");
        return result;
    }

    // ========================================================================
    // findFirstPipeOperator - Original source: bashPipeCommand.ts lines 105-113
    // ========================================================================

    /**
     * Finds the index of the first pipe operator in parsed shell command.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → findFirstPipeOperator()
     *
     * @param parsed Parsed tokens
     * @return Index of first pipe, or -1 if none found
     */
    static int findFirstPipeOperator(List<ShellQuote.ParseEntry> parsed) {
        for (int i = 0; i < parsed.size(); i++) {
            ShellQuote.ParseEntry entry = parsed.get(i);
            if (isOperator(entry, "|")) {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // buildCommandParts - Original source: bashPipeCommand.ts lines 119-212
    // ========================================================================

    /**
     * Builds command parts from parsed entries.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → buildCommandParts()
     *
     * Special handling for:
     * - File descriptor redirections (2>&1, 2>/dev/null)
     * - Environment variable assignments (VAR=value)
     * - Glob patterns (preserved as-is)
     * - Command separators (resets env var tracking)
     *
     * @param parsed Parsed tokens
     * @param start Start index (inclusive)
     * @param end End index (exclusive)
     * @return List of command parts
     */
    static List<String> buildCommandParts(List<ShellQuote.ParseEntry> parsed, int start, int end) {
        List<String> parts = new ArrayList<>();
        boolean seenNonEnvVar = false;

        for (int i = start; i < end; i++) {
            ShellQuote.ParseEntry entry = parsed.get(i);

            // Check for file descriptor redirections (e.g., 2>&1, 2>/dev/null)
            if (entry.isString() && isFileDescriptor(entry.getValue())) {
                if (i + 2 < end) {
                    ShellQuote.ParseEntry nextOp = parsed.get(i + 1);
                    ShellQuote.ParseEntry nextTarget = parsed.get(i + 2);

                    // Handle 2>&1 style redirections
                    if (nextOp.isOperator() && ">&".equals(nextOp.getOp())
                        && nextTarget.isString() && isFileDescriptor(nextTarget.getValue())) {
                        parts.add(entry.getValue() + ">&" + nextTarget.getValue());
                        i += 2;
                        continue;
                    }

                    // Handle 2>/dev/null style redirections
                    if (nextOp.isOperator() && ">".equals(nextOp.getOp())
                        && nextTarget.isString() && "/dev/null".equals(nextTarget.getValue())) {
                        parts.add(entry.getValue() + ">/dev/null");
                        i += 2;
                        continue;
                    }

                    // Handle 2> &1 style (space between > and &1)
                    if (nextOp.isOperator() && ">".equals(nextOp.getOp())
                        && nextTarget.isString() && nextTarget.getValue().startsWith("&")) {
                        String fd = nextTarget.getValue().substring(1);
                        if (isFileDescriptor(fd)) {
                            parts.add(entry.getValue() + ">&" + fd);
                            i += 2;
                            continue;
                        }
                    }
                }
            }

            // Handle regular string entries
            if (entry.isString()) {
                boolean isEnvVar = !seenNonEnvVar && isEnvironmentVariableAssignment(entry.getValue());

                if (isEnvVar) {
                    // Preserve env var assignment, quote the value
                    int eqIndex = entry.getValue().indexOf('=');
                    String name = entry.getValue().substring(0, eqIndex);
                    String value = entry.getValue().substring(eqIndex + 1);
                    parts.add(name + "=" + BashProvider.quote(new String[]{value}));
                } else {
                    seenNonEnvVar = true;
                    parts.add(BashProvider.quote(new String[]{entry.getValue()}));
                }
            } else if (entry.isOperator()) {
                // Handle glob operators
                if ("glob".equals(entry.getOp()) && entry.getPattern() != null) {
                    parts.add(entry.getPattern());
                } else {
                    String op = entry.getOp();
                    parts.add(op);
                    // Reset env var tracking after command separators
                    if (isCommandSeparator(op)) {
                        seenNonEnvVar = false;
                    }
                }
            } else if (entry.isGlob()) {
                parts.add(entry.getPattern());
            }
        }

        return parts;
    }

    // ========================================================================
    // containsControlStructure - Original source: bashPipeCommand.ts lines 247-249
    // ========================================================================

    /**
     * Checks if a command contains bash control structures.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → containsControlStructure()
     *
     * Detects: for, while, until, if, case, select
     * Matches keywords followed by whitespace to avoid false positives.
     *
     * @param command The command to check
     * @return true if control structure detected
     */
    public static boolean containsControlStructure(String command) {
        if (command == null) return false;
        boolean found = Pattern.compile("\\b(for|while|until|if|case|select)\\s").matcher(command).find();
        if (found) {
            log.log(System.Logger.Level.DEBUG, "Control structure detected in command");
        }
        return found;
    }

    // ========================================================================
    // quoteWithEvalStdinRedirect - Original source: bashPipeCommand.ts lines 262-264
    // ========================================================================

    /**
     * Quotes a command and adds stdin redirect for eval.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → quoteWithEvalStdinRedirect()
     *
     * Produces: eval 'cmd' < /dev/null
     *
     * @param command The command to quote
     * @return The quoted command with stdin redirect
     */
    public static String quoteWithEvalStdinRedirect(String command) {
        return singleQuoteForEval(command) + " < /dev/null";
    }

    // ========================================================================
    // singleQuoteForEval - Original source: bashPipeCommand.ts lines 273-275
    // ========================================================================

    /**
     * Single-quote a string for use as an eval argument.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → singleQuoteForEval()
     *
     * Escapes embedded single quotes via '"'"' (close-sq, literal-sq-in-dq, reopen-sq).
     * Used instead of shell-quote's quote() which switches to double-quote mode
     * when the input contains single quotes and then escapes ! to \!, corrupting
     * filters like select(.x != .y).
     *
     * @param s The string to quote
     * @return Single-quoted string safe for eval
     */
    public static String singleQuoteForEval(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    // ========================================================================
    // joinContinuationLines - Original source: bashPipeCommand.ts lines 283-294
    // ========================================================================

    /**
     * Joins shell continuation lines (backslash-newline) into a single line.
     *
     * Original source: src/utils/bash/bashPipeCommand.ts → joinContinuationLines()
     *
     * Only joins when there's an odd number of backslashes before the newline
     * (the last one escapes the newline). Even backslashes pair up as escape
     * sequences and the newline remains a separator.
     *
     * @param command The command with potential continuation lines
     * @return The command with continuation lines joined
     */
    public static String joinContinuationLines(String command) {
        if (command == null || !command.contains("\\\n")) {
            return command;
        }

        // Replace \<newline> sequences
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < command.length()) {
            // Look for backslash runs followed by newline
            if (command.charAt(i) == '\\' && i + 1 < command.length() && command.charAt(i + 1) == '\n') {
                // Count consecutive backslashes before this one
                int backslashCount = 1;
                int j = i - 1;
                while (j >= 0 && command.charAt(j) == '\\') {
                    backslashCount++;
                    j--;
                }

                if (backslashCount % 2 == 1) {
                    // Odd: last backslash escapes newline (line continuation)
                    // Keep all but the last backslash
                    for (int k = 0; k < backslashCount - 1; k++) {
                        result.append('\\');
                    }
                    i += 2; // Skip \<newline>
                } else {
                    // Even: all pair up, newline is real separator
                    result.append('\\');
                    result.append('\n');
                    i += 2;
                }
            } else {
                result.append(command.charAt(i));
                i++;
            }
        }

        log.log(System.Logger.Level.DEBUG, "Joined continuation lines");
        return result.toString();
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private static boolean isOperator(ShellQuote.ParseEntry entry, String op) {
        return entry != null && entry.isOperator() && op.equals(entry.getOp());
    }

    private static boolean isFileDescriptor(String s) {
        return "0".equals(s) || "1".equals(s) || "2".equals(s);
    }

    private static boolean isEnvironmentVariableAssignment(String s) {
        return s != null && Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=").matcher(s).find();
    }

    private static boolean isCommandSeparator(String op) {
        return "&&".equals(op) || "||".equals(op) || ";".equals(op);
    }
}
