package agent.tool.shell;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atomic replication of Claude Code bash/ParsedCommand.ts output redirection handling.
 *
 * Original source: src/utils/bash/ParsedCommand.ts
 *
 * Output redirection extraction for bash commands.
 * Provides methods to extract, remove, and validate output redirections
 * for security and permission checking.
 *
 * Key features:
 * - Extract output redirections from parsed commands
 * - Get output redirections without modifying the command
 * - Validate redirect targets for security
 * - Handle file descriptor redirections (0, 1, 2)
 * - Support for >> (append) and > (overwrite) operators
 *
 * Security: Only allows redirections to simple static file paths.
 * Rejects paths with command substitution, variable expansion, or shell metacharacters.
 */
public final class OutputRedirectionExtractor {

    private static final System.Logger log = System.getLogger(OutputRedirectionExtractor.class.getName());

    private OutputRedirectionExtractor() {}

    // ========================================================================
    // OutputRedirection - Parsed output redirection
    // ========================================================================

    /**
     * Represents a parsed output redirection.
     */
    public static class OutputRedirection {
        private final String fd;       // File descriptor: "0", "1", "2", "&", or ""
        private final String operator; // ">" or ">>"
        private final String target;   // Target path or fd (&N)
        private final boolean isFdDup; // Whether this is fd duplication (e.g., 2>&1)

        public OutputRedirection(String fd, String operator, String target, boolean isFdDup) {
            this.fd = fd;
            this.operator = operator;
            this.target = target;
            this.isFdDup = isFdDup;
        }

        public String getFd() { return fd; }
        public String getOperator() { return operator; }
        public String getTarget() { return target; }
        public boolean isFdDup() { return isFdDup; }
        public boolean isAppend() { return ">>".equals(operator); }

        @Override
        public String toString() {
            return (fd.isEmpty() ? "" : fd) + operator + target;
        }
    }

    // ========================================================================
    // RedirectionExtractionResult
    // ========================================================================

    /**
     * Result of redirection extraction.
     */
    public static class RedirectionExtractionResult {
        private final String commandWithoutRedirections;
        private final List<OutputRedirection> redirections;

        public RedirectionExtractionResult(String commandWithoutRedirections, List<OutputRedirection> redirections) {
            this.commandWithoutRedirections = commandWithoutRedirections;
            this.redirections = redirections != null ? redirections : Collections.emptyList();
        }

        public String getCommandWithoutRedirections() { return commandWithoutRedirections; }
        public List<OutputRedirection> getRedirections() { return redirections; }
        public boolean hasRedirections() { return !redirections.isEmpty(); }
    }

    // ========================================================================
    // Allowed file descriptors
    // ========================================================================

    private static final Set<String> ALLOWED_FDS = Set.of("0", "1", "2");

    // ========================================================================
    // Patterns for redirection detection
    // ========================================================================

    /**
     * Pattern for output redirections.
     * Matches: [n]>> file, [n]> file, &>> file, &> file, [n]>>&[n], [n]>&[n]
     *
     * Original source: ParsedCommand.ts → getOutputRedirections() regex patterns
     */
    private static final Pattern REDIRECT_PATTERN = Pattern.compile(
        "(?:^|\\s)" +
        "(\\d|&)?(>{1,2})" +       // fd (optional) + operator (> or >>)
        "(&(\\d))?\\s*" +           // fd dup target (optional, e.g., &1)
        "([^\\s;|&$`(){}'\"\\\\]+)?" // file target (optional)
    );

    /**
     * Simpler pattern for fd duplication: N>&M, N>>&M
     */
    private static final Pattern FD_DUP_PATTERN = Pattern.compile(
        "(\\d|&)?(>{1,2})&(\\d)"
    );

    // ========================================================================
    // getOutputRedirections - Original source: ParsedCommand.ts lines 123-175
    // ========================================================================

    /**
     * Get all output redirections from a command.
     *
     * Original source: src/utils/bash/ParsedCommand.ts → getOutputRedirections()
     *
     * Detects and extracts:
     * - > file      (stdout to file)
     * - >> file     (stdout append to file)
     * - 2> file     (stderr to file)
     * - 2>> file    (stderr append to file)
     * - &> file     (stdout+stderr to file)
     * - &>> file    (stdout+stderr append to file)
     * - 2>&1        (stderr to stdout)
     * - 1>&2        (stdout to stderr)
     *
     * @param command The command to extract redirections from
     * @return List of OutputRedirection objects
     */
    public static List<OutputRedirection> getOutputRedirections(String command) {
        if (command == null || command.isEmpty() || !command.contains(">")) {
            return Collections.emptyList();
        }

        List<OutputRedirection> redirections = new ArrayList<>();

        // Parse using ShellQuote for accurate token-level detection
        ShellQuote.ParseResult parseResult = ShellQuote.tryParseShellCommand(command);
        if (!parseResult.isSuccess()) {
            log.log(System.Logger.Level.DEBUG, "getOutputRedirections: parse failed");
            return Collections.emptyList();
        }

        List<ShellQuote.ParseEntry> tokens = parseResult.getTokens();

        for (int i = 0; i < tokens.size(); i++) {
            ShellQuote.ParseEntry entry = tokens.get(i);

            // Check for redirect operator
            if (entry.isOperator() && (">".equals(entry.getOp()) || ">>".equals(entry.getOp()))) {
                String fd = "";
                String operator = entry.getOp();
                String target = null;
                boolean isFdDup = false;

                // Check previous token for file descriptor
                if (i > 0 && tokens.get(i - 1).isString()) {
                    String prev = tokens.get(i - 1).getValue();
                    if (ALLOWED_FDS.contains(prev) || "&".equals(prev)) {
                        fd = prev;
                    }
                }

                // Check next token for target
                if (i + 1 < tokens.size() && tokens.get(i + 1).isString()) {
                    String next = tokens.get(i + 1).getValue();

                    // Check for fd duplication (&N)
                    if (next.startsWith("&") && next.length() > 1) {
                        String targetFd = next.substring(1);
                        if (ALLOWED_FDS.contains(targetFd)) {
                            target = next;
                            isFdDup = true;
                            log.log(System.Logger.Level.DEBUG, "FD duplication: {0}{1}{2}",
                                    fd, operator, target);
                        }
                    } else {
                        // File target
                        target = next;

                        // Security: validate the target
                        if (isSimpleStaticPath(target)) {
                            log.log(System.Logger.Level.DEBUG, "File redirect: {0}{1} {2}",
                                    fd, operator, target);
                        } else {
                            log.log(System.Logger.Level.WARNING,
                                "Suspicious redirect target (skipping): {0}", target);
                            continue;
                        }
                    }
                }

                if (target != null) {
                    redirections.add(new OutputRedirection(fd, operator, target, isFdDup));
                }
            }

            // Check for combined redirect operators (&>, &>>)
            if (entry.isOperator() && ("&>".equals(entry.getOp()) || "&>>".equals(entry.getOp()))) {
                String operator = "&>".equals(entry.getOp()) ? ">" : ">>";

                if (i + 1 < tokens.size() && tokens.get(i + 1).isString()) {
                    String target = tokens.get(i + 1).getValue();
                    if (isSimpleStaticPath(target)) {
                        redirections.add(new OutputRedirection("&", operator, target, false));
                        log.log(System.Logger.Level.DEBUG, "Combined redirect: &{0}{1}", operator, target);
                    }
                }
            }
        }

        log.log(System.Logger.Level.DEBUG, "Found {0} output redirection(s)", redirections.size());
        return redirections;
    }

    // ========================================================================
    // extractOutputRedirections - Original source: ParsedCommand.ts lines 180-230
    // ========================================================================

    /**
     * Extract and remove output redirections from a command.
     *
     * Original source: src/utils/bash/ParsedCommand.ts → withoutOutputRedirections()
     *
     * Returns the command with redirections removed and the list of redirections.
     *
     * @param command The command to process
     * @return RedirectionExtractionResult with cleaned command and redirection list
     */
    public static RedirectionExtractionResult extractOutputRedirections(String command) {
        if (command == null || command.isEmpty() || !command.contains(">")) {
            return new RedirectionExtractionResult(command, Collections.emptyList());
        }

        List<OutputRedirection> redirections = getOutputRedirections(command);

        if (redirections.isEmpty()) {
            return new RedirectionExtractionResult(command, Collections.emptyList());
        }

        // Remove redirections from command
        String cleaned = command;

        // Sort by string length descending to avoid partial matches
        List<String> redirectStrings = new ArrayList<>();
        for (OutputRedirection r : redirections) {
            redirectStrings.add(r.toString());
        }
        redirectStrings.sort((a, b) -> Integer.compare(b.length(), a.length()));

        for (String redirectStr : redirectStrings) {
            cleaned = cleaned.replace(redirectStr, "");
        }

        // Clean up extra whitespace
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        log.log(System.Logger.Level.INFO, "Extracted {0} redirection(s), cleaned command length: {1}",
                redirections.size(), cleaned.length());

        return new RedirectionExtractionResult(cleaned, redirections);
    }

    // ========================================================================
    // isSimpleStaticPath - Security validation for redirect targets
    // ========================================================================

    /**
     * Check if a path is a simple static file path.
     *
     * Security: Prevents injection via redirect targets containing:
     * - Command substitution $() or backticks
     * - Variable expansion ${...} or $VAR
     * - Shell metacharacters (;, |, &&, ||, backslash)
     * - Quotes (which could hide malicious content)
     *
     * @param path The path to validate
     * @return true if the path is safe
     */
    static boolean isSimpleStaticPath(String path) {
        if (path == null || path.isEmpty()) return false;

        // Reject paths with shell expansions/substitutions
        if (path.contains("$(") || path.contains("${") || path.contains("`")) return false;
        if (path.contains("$") && path.matches(".*\\$[A-Za-z_].*")) return false;

        // Reject paths with shell metacharacters
        if (path.contains(";") || path.contains("|") || path.contains("&")) return false;
        if (path.contains("&&") || path.contains("||")) return false;
        if (path.contains("\\") && !isWindows()) return false;

        // Reject paths with quotes
        if (path.contains("'") || path.contains("\"")) return false;

        // Reject parent directory traversal
        if (path.contains("..")) return false;

        return true;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
