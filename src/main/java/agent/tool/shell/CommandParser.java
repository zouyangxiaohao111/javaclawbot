package agent.tool.shell;

import java.util.*;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Atomic replication of Claude Code bash/commands.ts + parser.ts.
 *
 * Original sources:
 * - src/utils/bash/commands.ts (splitCommand, splitCommandWithOperators, extractOutputRedirections)
 * - src/utils/bash/parser.ts (parseCommand, extractEnvVars, extractCommandArguments)
 *
 * Command parsing utilities for bash security validation.
 * Provides command splitting, argument extraction, and output redirection handling.
 *
 * Key security features:
 * - Safe command splitting with operator awareness
 * - Environment variable extraction
 * - Output redirection extraction for permission checking
 * - Placeholder generation with random salt for injection prevention
 */
@Slf4j
public final class CommandParser {

    private CommandParser() {}

    // ========================================================================
    // Constants - Original source: commands.ts lines 1-40, parser.ts lines 20-35
    // ========================================================================

    /** Maximum command length for parsing */
    private static final int MAX_COMMAND_LENGTH = 10000;

    /** Declaration commands */
    private static final Set<String> DECLARATION_COMMANDS = Set.of(
        "export", "declare", "typeset", "readonly", "local", "unset", "unsetenv"
    );

    /** Argument types in AST */
    private static final Set<String> ARGUMENT_TYPES = Set.of("word", "string", "raw_string", "number");

    /** Command types in AST */
    private static final Set<String> COMMAND_TYPES = Set.of("command", "declaration_command");

    /** File descriptors for standard streams */
    private static final Set<String> ALLOWED_FILE_DESCRIPTORS = Set.of("0", "1", "2");

    /** Environment variable pattern */
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=");

    /** Numeric pattern */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+$");

    // ========================================================================
    // ParsedCommandData - Original source: parser.ts lines 12-17
    // ========================================================================

    /**
     * Data extracted from a parsed command.
     *
     * Original source: src/utils/bash/parser.ts → ParsedCommandData
     */
    public static class ParsedCommandData {
        private final String originalCommand;
        private final List<String> envVars;
        private final List<String> commandArgs;
        private final boolean valid;

        public ParsedCommandData(String originalCommand, List<String> envVars,
                                List<String> commandArgs, boolean valid) {
            this.originalCommand = originalCommand;
            this.envVars = envVars != null ? envVars : Collections.emptyList();
            this.commandArgs = commandArgs != null ? commandArgs : Collections.emptyList();
            this.valid = valid;
        }

        public String getOriginalCommand() { return originalCommand; }
        public List<String> getEnvVars() { return envVars; }
        public List<String> getCommandArgs() { return commandArgs; }
        public boolean isValid() { return valid; }
    }

    // ========================================================================
    // OutputRedirection - Original source: commands.ts lines 42-56
    // ========================================================================

    /**
     * Represents an output redirection in a command.
     *
     * Original source: src/utils/bash/commands.ts → OutputRedirection
     */
    public static class OutputRedirection {
        private final String target;
        private final String operator; // ">" or ">>"

        public OutputRedirection(String target, String operator) {
            this.target = target;
            this.operator = operator;
        }

        public String getTarget() { return target; }
        public String getOperator() { return operator; }
        public boolean isAppend() { return ">>".equals(operator); }

        @Override
        public String toString() {
            return operator + " " + target;
        }
    }

    // ========================================================================
    // OutputRedirectionResult - Original source: commands.ts lines 58-65
    // ========================================================================

    /**
     * Result of extracting output redirections.
     *
     * Original source: src/utils/bash/commands.ts → extractOutputRedirections() result
     */
    public static class OutputRedirectionResult {
        private final String commandWithoutRedirections;
        private final List<OutputRedirection> redirections;

        public OutputRedirectionResult(String commandWithoutRedirections, List<OutputRedirection> redirections) {
            this.commandWithoutRedirections = commandWithoutRedirections;
            this.redirections = redirections != null ? redirections : Collections.emptyList();
        }

        public String getCommandWithoutRedirections() { return commandWithoutRedirections; }
        public List<OutputRedirection> getRedirections() { return redirections; }
        public boolean hasRedirections() { return !redirections.isEmpty(); }
    }

    // ========================================================================
    // parseCommand - Original source: parser.ts lines 56-84
    // ========================================================================

    /**
     * Parse a command string into structured data.
     *
     * Original source: src/utils/bash/parser.ts → parseCommand()
     *
     * Extracts:
     * - Environment variable assignments (VAR=value)
     * - Command name and arguments
     * - Handles declaration commands specially
     *
     * @param command The command to parse
     * @return ParsedCommandData or null if invalid/too long
     */
    public static ParsedCommandData parseCommand(String command) {
        if (command == null || command.isEmpty()) {
            log.debug("解析命令: 命令为空");
            return null;
        }

        if (command.length() > MAX_COMMAND_LENGTH) {
            log.warn("解析命令: 命令超出最大长度 ({} > {})", command.length(), MAX_COMMAND_LENGTH);
            return null;
        }

        try {
            // Use ShellQuote parser for basic tokenization
            ShellQuote.ParseResult parseResult = ShellQuote.tryParseShellCommand(command);
            if (!parseResult.isSuccess() || parseResult.getTokens().isEmpty()) {
                log.debug("解析命令: Shell引号解析失败");
                return null;
            }

            // Extract env vars and command args
            List<String> envVars = extractEnvVars(command);
            List<String> commandArgs = extractCommandArguments(command);

            if (commandArgs.isEmpty()) {
                log.debug("解析命令: 未找到命令参数");
                return null;
            }

            log.debug("解析命令: {} 个环境变量, {} 个参数, 命令={}", envVars.size(), commandArgs.size(), commandArgs.get(0));

            return new ParsedCommandData(command, envVars, commandArgs, true);

        } catch (Exception e) {
            log.warn("解析命令失败: {}", e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // extractEnvVars - Original source: parser.ts lines 175-187
    // ========================================================================

    /**
     * Extract environment variable assignments from a command.
     *
     * Original source: src/utils/bash/parser.ts → extractEnvVars()
     *
     * Environment variables are assignments at the beginning of a command,
     * before the actual command name. E.g., `VAR=value CMD arg1 arg2`.
     *
     * @param command The command string
     * @return List of environment variable assignments (e.g., ["VAR=value"])
     */
    public static List<String> extractEnvVars(String command) {
        if (command == null || command.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> envVars = new ArrayList<>();
        String[] tokens = command.split("\\s+");

        for (String token : tokens) {
            if (ENV_VAR_PATTERN.matcher(token).find()) {
                envVars.add(token);
                log.debug("提取环境变量: 发现环境变量: {}", token);
            } else {
                // Env vars must be at the start, stop at first non-env token
                break;
            }
        }

        return envVars;
    }

    // ========================================================================
    // extractCommandArguments - Original source: parser.ts lines 189-222
    // ========================================================================

    /**
     * Extract command arguments from a command string.
     *
     * Original source: src/utils/bash/parser.ts → extractCommandArguments()
     *
     * Handles:
     * - Declaration commands (export, declare, etc.) - returns just the command name
     * - Regular commands - returns [command_name, arg1, arg2, ...]
     * - Skips environment variable assignments
     * - Strips quotes from arguments
     * - Stops at command substitution $(...)
     *
     * @param command The command string
     * @return List of command arguments (first element is the command name)
     */
    public static List<String> extractCommandArguments(String command) {
        if (command == null || command.isEmpty()) {
            return Collections.emptyList();
        }

        String[] tokens = command.split("\\s+");

        // Skip env vars
        int start = 0;
        for (int i = 0; i < tokens.length; i++) {
            if (ENV_VAR_PATTERN.matcher(tokens[i]).find()) {
                start = i + 1;
            } else {
                break;
            }
        }

        if (start >= tokens.length) {
            return Collections.emptyList();
        }

        List<String> args = new ArrayList<>();
        String firstToken = tokens[start];

        // Check for declaration commands
        if (DECLARATION_COMMANDS.contains(firstToken)) {
            args.add(firstToken);
            return args;
        }

        // Extract regular command arguments
        for (int i = start; i < tokens.length; i++) {
            String token = tokens[i];

            // Skip env var assignments
            if (ENV_VAR_PATTERN.matcher(token).find() && args.isEmpty()) {
                continue;
            }

            // Stop at command substitution
            if (token.contains("$(") || token.contains("`")) {
                log.debug("提取命令参数: 在命令替换处停止: {}", token);
                break;
            }

            // Strip quotes
            args.add(stripQuotes(token));
        }

        return args;
    }

    /**
     * Strip surrounding quotes from a string.
     *
     * Original source: src/utils/bash/parser.ts lines 224-230
     */
    private static String stripQuotes(String text) {
        if (text == null || text.length() < 2) return text;

        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);

        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    // ========================================================================
    // splitCommand - Original source: commands.ts lines 82-155
    // ========================================================================

    /**
     * Split a compound command into subcommands.
     *
     * Original source: src/utils/bash/commands.ts → splitCommand_DEPRECATED()
     *
     * Splits on &&, ||, ; operators while respecting quotes.
     *
     * @param command The compound command to split
     * @return Array of individual subcommands
     */
    public static String[] splitCommand(String command) {
        if (command == null || command.isEmpty()) {
            return new String[0];
        }

        ShellQuote.ParseResult result = ShellQuote.tryParseShellCommand(command);
        if (!result.isSuccess()) {
            log.debug("拆分命令: 解析失败, 返回原始命令");
            return new String[]{command};
        }

        List<ShellQuote.ParseEntry> tokens = result.getTokens();
        List<String> subcommands = new ArrayList<>();
        List<String> currentParts = new ArrayList<>();

        for (ShellQuote.ParseEntry entry : tokens) {
            if (entry.isOperator() && isCompoundOperator(entry.getOp())) {
                if (!currentParts.isEmpty()) {
                    subcommands.add(String.join(" ", currentParts));
                    currentParts.clear();
                }
            } else if (entry.isString()) {
                currentParts.add(entry.getValue());
            } else if (entry.isGlob()) {
                currentParts.add(entry.getPattern());
            } else if (entry.isOperator()) {
                currentParts.add(entry.getOp());
            }
        }

        if (!currentParts.isEmpty()) {
            subcommands.add(String.join(" ", currentParts));
        }

        log.debug("拆分命令: 找到 {} 个子命令", subcommands.size());
        return subcommands.toArray(new String[0]);
    }

    private static boolean isCompoundOperator(String op) {
        return "&&".equals(op) || "||".equals(op) || ";".equals(op);
    }

    // ========================================================================
    // splitCommandWithOperators - Original source: commands.ts lines 160-220
    // ========================================================================

    /**
     * Split a command into parts preserving operators.
     *
     * Original source: src/utils/bash/commands.ts → splitCommandWithOperators()
     *
     * Unlike splitCommand(), this keeps operators as separate elements.
     * E.g., "cmd1 && cmd2 | cmd3" → ["cmd1", "&&", "cmd2", "|", "cmd3"]
     *
     * @param command The command to split
     * @return Array of command parts and operators
     */
    public static String[] splitCommandWithOperators(String command) {
        if (command == null || command.isEmpty()) {
            return new String[0];
        }

        ShellQuote.ParseResult result = ShellQuote.tryParseShellCommand(command);
        if (!result.isSuccess()) {
            return new String[]{command};
        }

        List<ShellQuote.ParseEntry> tokens = result.getTokens();
        List<String> parts = new ArrayList<>();

        for (ShellQuote.ParseEntry entry : tokens) {
            if (entry.isString()) {
                parts.add(entry.getValue());
            } else if (entry.isGlob()) {
                parts.add(entry.getPattern());
            } else if (entry.isOperator()) {
                parts.add(entry.getOp());
            }
        }

        log.debug("拆分命令(保留操作符): {} 个部分", parts.size());
        return parts.toArray(new String[0]);
    }

    // ========================================================================
    // extractOutputRedirections - Original source: commands.ts lines 225-345
    // ========================================================================

    /**
     * Extract output redirections from a command.
     *
     * Original source: src/utils/bash/commands.ts → extractOutputRedirections()
     *
     * Detects:
     * - > file (overwrite redirect)
     * - >> file (append redirect)
     * - 2> file (stderr redirect)
     * - &> file (stdout+stderr redirect)
     * - 2>&1 (fd duplication)
     *
     * Security: Only allows redirections to simple static file paths.
     * Rejects paths with:
     * - Command substitution $(...)
     * - Variable expansion ${...}
     * - Backticks
     * - Semicolons, pipes, &&, ||
     *
     * @param command The command to extract redirections from
     * @return OutputRedirectionResult with cleaned command and redirection list
     */
    public static OutputRedirectionResult extractOutputRedirections(String command) {
        if (command == null || command.isEmpty()) {
            return new OutputRedirectionResult(command, Collections.emptyList());
        }

        if (!command.contains(">")) {
            return new OutputRedirectionResult(command, Collections.emptyList());
        }

        List<OutputRedirection> redirections = new ArrayList<>();
        String remaining = command;

        // Pattern for output redirections: [n]&?>> file
        // Must match: > ..., >> ..., 2> ..., &> ..., &>> ...
        Pattern redirectPattern = Pattern.compile(
            "(?:^|\\s)(\\d?&?>>)\\s*([^\\s;|&$`(){}]+)"
        );

        java.util.regex.Matcher matcher = redirectPattern.matcher(remaining);
        StringBuilder cleaned = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            String operator = matcher.group(1);
            String target = matcher.group(2);

            // Security: validate the target is a simple static file path
            if (!isSimpleStaticPath(target)) {
                log.debug("跳过可疑的重定向目标: {}", target);
                continue;
            }

            // Normalize operator
            String normalizedOp = operator.replace(" ", "");
            if (normalizedOp.contains(">>")) {
                normalizedOp = ">>";
            } else if (normalizedOp.contains(">")) {
                normalizedOp = ">";
            }

            redirections.add(new OutputRedirection(target, normalizedOp));

            // Remove the redirection from the command
            cleaned.append(remaining, lastEnd, matcher.start());
            lastEnd = matcher.end();

            log.debug("提取输出重定向: 找到 {} {}", normalizedOp, target);
        }

        cleaned.append(remaining.substring(lastEnd));

        String commandWithoutRedirections = cleaned.toString().trim().replaceAll("\\s+", " ");
        return new OutputRedirectionResult(commandWithoutRedirections, redirections);
    }

    /**
     * Check if a path is a simple static file path (no expansions/substitutions).
     *
     * Security: Prevents injection via redirect targets containing:
     * - Command substitution $()
     * - Variable expansion ${}
     * - Backticks
     * - Shell metacharacters (;, |, &&, ||)
     */
    private static boolean isSimpleStaticPath(String path) {
        if (path == null || path.isEmpty()) return false;

        // Reject paths with shell expansions/substitutions
        if (path.contains("$(") || path.contains("${") || path.contains("`")) return false;
        if (path.contains(";") || path.contains("|") || path.contains("&&")) return false;

        // Reject paths with suspicious characters
        if (path.contains("..")) return false;

        return true;
    }

    // ========================================================================
    // generatePlaceholders - Original source: commands.ts lines 14-36
    // ========================================================================

    /**
     * Generates placeholder strings with random salt for injection prevention.
     *
     * Original source: src/utils/bash/commands.ts → generatePlaceholders()
     *
     * Security: This is critical for preventing attacks where a command like
     * `sort __SINGLE_QUOTE__ hello --help __SINGLE_QUOTE__` could inject arguments.
     * The random salt makes the placeholders unpredictable.
     *
     * @return Map of placeholder names to generated placeholder strings
     */
    public static Map<String, String> generatePlaceholders() {
        String salt = generateSalt();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("SINGLE_QUOTE", "__SINGLE_QUOTE_" + salt + "__");
        placeholders.put("DOUBLE_QUOTE", "__DOUBLE_QUOTE_" + salt + "__");
        placeholders.put("NEW_LINE", "__NEW_LINE_" + salt + "__");
        placeholders.put("ESCAPED_OPEN_PAREN", "__ESCAPED_OPEN_PAREN_" + salt + "__");
        placeholders.put("ESCAPED_CLOSE_PAREN", "__ESCAPED_CLOSE_PAREN_" + salt + "__");

        log.debug("已生成带随机盐的命令占位符");
        return placeholders;
    }

    private static String generateSalt() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
