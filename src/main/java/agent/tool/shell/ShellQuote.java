package agent.tool.shell;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Atomic replication of Claude Code bash/shellQuote.ts.
 *
 * Original source: src/utils/bash/shellQuote.ts
 *
 * Safe wrappers for shell-quote library functions that handle errors gracefully.
 * Provides security checks for shell command parsing and quoting.
 *
 * Key security features:
 * - tryParseShellCommand: Safe parsing with error recovery
 * - hasMalformedTokens: Detects unbalanced delimiters (injection attacks)
 * - hasShellQuoteSingleQuoteBug: Detects shell-quote library bug exploits
 * - quote: Safe argument quoting with type validation
 */
public final class ShellQuote {

    private static final System.Logger log = System.getLogger(ShellQuote.class.getName());

    private ShellQuote() {}

    // ========================================================================
    // ParseResult - Result of shell command parsing
    // ========================================================================

    /**
     * Result of parsing a shell command.
     *
     * Original source: src/utils/bash/shellQuote.ts → ShellParseResult
     */
    public static class ParseResult {
        private final boolean success;
        private final List<ParseEntry> tokens;
        private final String error;

        private ParseResult(boolean success, List<ParseEntry> tokens, String error) {
            this.success = success;
            this.tokens = tokens != null ? tokens : Collections.emptyList();
            this.error = error;
        }

        public static ParseResult success(List<ParseEntry> tokens) {
            return new ParseResult(true, tokens, null);
        }

        public static ParseResult failure(String error) {
            return new ParseResult(false, null, error);
        }

        public boolean isSuccess() { return success; }
        public List<ParseEntry> getTokens() { return tokens; }
        public String getError() { return error; }
    }

    // ========================================================================
    // ParseEntry - Parsed token from shell command
    // ========================================================================

    /**
     * A parsed entry from a shell command.
     * Can be a string (argument) or an operator (|, &&, ||, ;, etc.)
     *
     * Original source: src/utils/bash/shellQuote.ts → ParseEntry
     */
    public static class ParseEntry {
        private final String value;
        private final String op;
        private final String pattern;
        private final EntryType type;

        public enum EntryType {
            STRING, OPERATOR, GLOB
        }

        private ParseEntry(String value, String op, String pattern, EntryType type) {
            this.value = value;
            this.op = op;
            this.pattern = pattern;
            this.type = type;
        }

        public static ParseEntry string(String value) {
            return new ParseEntry(value, null, null, EntryType.STRING);
        }

        public static ParseEntry operator(String op) {
            return new ParseEntry(null, op, null, EntryType.OPERATOR);
        }

        public static ParseEntry glob(String pattern) {
            return new ParseEntry(null, null, pattern, EntryType.GLOB);
        }

        public boolean isString() { return type == EntryType.STRING; }
        public boolean isOperator() { return type == EntryType.OPERATOR; }
        public boolean isGlob() { return type == EntryType.GLOB; }

        public String getValue() { return value; }
        public String getOp() { return op; }
        public String getPattern() { return pattern; }
        public EntryType getType() { return type; }

        @Override
        public String toString() {
            return switch (type) {
                case STRING -> "String(" + value + ")";
                case OPERATOR -> "Op(" + op + ")";
                case GLOB -> "Glob(" + pattern + ")";
            };
        }
    }

    // ========================================================================
    // QuoteResult - Result of quoting shell arguments
    // ========================================================================

    /**
     * Result of quoting shell arguments.
     *
     * Original source: src/utils/bash/shellQuote.ts → ShellQuoteResult
     */
    public static class QuoteResult {
        private final boolean success;
        private final String quoted;
        private final String error;

        private QuoteResult(boolean success, String quoted, String error) {
            this.success = success;
            this.quoted = quoted;
            this.error = error;
        }

        public static QuoteResult success(String quoted) {
            return new QuoteResult(true, quoted, null);
        }

        public static QuoteResult failure(String error) {
            return new QuoteResult(false, null, error);
        }

        public boolean isSuccess() { return success; }
        public String getQuoted() { return quoted; }
        public String getError() { return error; }
    }

    // ========================================================================
    // Command operators
    // ========================================================================

    private static final Set<String> COMMAND_OPERATORS = Set.of("|", "||", "&&", ";");
    private static final Set<String> REDIRECT_OPERATORS = Set.of("<", ">", ">>", ">&", "<&", "&>", "&>>");

    // ========================================================================
    // tryParseShellCommand - Original source: shellQuote.ts lines 24-45
    // ========================================================================

    /**
     * Safely parse a shell command into tokens.
     *
     * Original source: src/utils/bash/shellQuote.ts → tryParseShellCommand()
     *
     * Handles:
     * - Single-quoted strings (literal)
     * - Double-quoted strings (with escape support)
     * - Operators (|, ||, &&, ;, <, >, >>, etc.)
     * - Glob patterns (*.txt, file?.log)
     *
     * @param command The command string to parse
     * @return ParseResult with tokens or error
     */
    public static ParseResult tryParseShellCommand(String command) {
        if (command == null || command.isEmpty()) {
            return ParseResult.success(Collections.emptyList());
        }

        try {
            List<ParseEntry> tokens = parseCommandInternal(command);
            log.log(System.Logger.Level.DEBUG, "Parsed command: {0} tokens extracted", tokens.size());
            return ParseResult.success(tokens);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to parse shell command: {0}", e.getMessage());
            return ParseResult.failure(e.getMessage());
        }
    }

    /**
     * Internal command parsing implementation.
     * Simulates shell-quote library behavior without external dependency.
     */
    private static List<ParseEntry> parseCommandInternal(String command) {
        List<ParseEntry> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        int i = 0;

        while (i < command.length()) {
            char c = command.charAt(i);

            // Skip whitespace outside quotes
            if (Character.isWhitespace(c)) {
                if (currentToken.length() > 0) {
                    tokens.add(ParseEntry.string(currentToken.toString()));
                    currentToken.setLength(0);
                }
                i++;
                continue;
            }

            // Handle single-quoted strings (literal, no escapes)
            if (c == '\'') {
                int start = i;
                i++; // skip opening quote
                while (i < command.length() && command.charAt(i) != '\'') {
                    currentToken.append(command.charAt(i));
                    i++;
                }
                if (i < command.length()) i++; // skip closing quote
                else {
                    // Unterminated single quote - still add what we have
                    log.log(System.Logger.Level.DEBUG, "Unterminated single quote at position {0}", start);
                }
                continue;
            }

            // Handle double-quoted strings (with escape support)
            if (c == '"') {
                int start = i;
                i++; // skip opening quote
                while (i < command.length() && command.charAt(i) != '"') {
                    if (command.charAt(i) == '\\' && i + 1 < command.length()) {
                        // Handle escapes inside double quotes
                        char next = command.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
                            currentToken.append(next);
                            i += 2;
                            continue;
                        }
                    }
                    currentToken.append(command.charAt(i));
                    i++;
                }
                if (i < command.length()) i++; // skip closing quote
                else {
                    log.log(System.Logger.Level.DEBUG, "Unterminated double quote at position {0}", start);
                }
                continue;
            }

            // Handle operators
            if (isOperatorStart(c)) {
                if (currentToken.length() > 0) {
                    tokens.add(ParseEntry.string(currentToken.toString()));
                    currentToken.setLength(0);
                }

                // Check for two-character operators
                if (i + 1 < command.length()) {
                    String twoChar = command.substring(i, i + 2);
                    if (COMMAND_OPERATORS.contains(twoChar) || REDIRECT_OPERATORS.contains(twoChar)) {
                        tokens.add(ParseEntry.operator(twoChar));
                        i += 2;
                        continue;
                    }
                }

                // Single-character operator
                String op = String.valueOf(c);
                if (COMMAND_OPERATORS.contains(op) || REDIRECT_OPERATORS.contains(op)) {
                    tokens.add(ParseEntry.operator(op));
                } else if (c == '&') {
                    // & might be start of && or &> or just &
                    tokens.add(ParseEntry.operator("&"));
                } else {
                    currentToken.append(c);
                }
                i++;
                continue;
            }

            // Handle backslash escapes outside quotes
            if (c == '\\' && i + 1 < command.length()) {
                currentToken.append(command.charAt(i + 1));
                i += 2;
                continue;
            }

            // Handle glob patterns
            if (c == '*' || c == '?') {
                if (currentToken.length() > 0) {
                    // Check if this looks like a glob pattern
                    String prefix = currentToken.toString();
                    i++;
                    StringBuilder pattern = new StringBuilder(prefix);
                    pattern.append(c);

                    // Collect rest of glob pattern
                    while (i < command.length() && !Character.isWhitespace(command.charAt(i))
                           && !isOperatorStart(command.charAt(i))) {
                        pattern.append(command.charAt(i));
                        i++;
                    }

                    tokens.add(ParseEntry.glob(pattern.toString()));
                    currentToken.setLength(0);
                    continue;
                }
            }

            // Regular character
            currentToken.append(c);
            i++;
        }

        // Add remaining token
        if (currentToken.length() > 0) {
            tokens.add(ParseEntry.string(currentToken.toString()));
        }

        return tokens;
    }

    private static boolean isOperatorStart(char c) {
        return c == '|' || c == '&' || c == ';' || c == '<' || c == '>';
    }

    // ========================================================================
    // tryQuoteShellArgs - Original source: shellQuote.ts lines 47-95
    // ========================================================================

    /**
     * Safely quote shell arguments.
     *
     * Original source: src/utils/bash/shellQuote.ts → tryQuoteShellArgs()
     *
     * Validates argument types and quotes them for shell use.
     * Rejects object, symbol, and function types.
     *
     * @param args Arguments to quote
     * @return QuoteResult with quoted string or error
     */
    public static QuoteResult tryQuoteShellArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return QuoteResult.success("");
        }

        try {
            List<String> validated = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];

                if (arg == null) {
                    validated.add("null");
                    continue;
                }

                if (arg instanceof String s) {
                    validated.add(s);
                    continue;
                }

                if (arg instanceof Number || arg instanceof Boolean) {
                    validated.add(String.valueOf(arg));
                    continue;
                }

                // Reject unsupported types
                String type = arg.getClass().getSimpleName();
                if (arg instanceof Map || arg instanceof List || arg.getClass().isArray()) {
                    throw new IllegalArgumentException(
                        "Cannot quote argument at index " + i + ": object/map/array values are not supported"
                    );
                }

                throw new IllegalArgumentException(
                    "Cannot quote argument at index " + i + ": unsupported type " + type
                );
            }

            String quoted = quoteArgs(validated.toArray(new String[0]));
            log.log(System.Logger.Level.DEBUG, "Quoted {0} arguments successfully", args.length);
            return QuoteResult.success(quoted);

        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to quote shell args: {0}", e.getMessage());
            return QuoteResult.failure(e.getMessage());
        }
    }

    // ========================================================================
    // hasMalformedTokens - Original source: shellQuote.ts lines 117-176
    // ========================================================================

    /**
     * Checks if parsed tokens contain malformed entries.
     *
     * Original source: src/utils/bash/shellQuote.ts → hasMalformedTokens()
     *
     * Detects:
     * - Unterminated quotes in original command
     * - Unbalanced curly braces { }
     * - Unbalanced parentheses ( )
     * - Unbalanced square brackets [ ]
     * - Unbalanced quotes in tokens
     *
     * Security: This prevents command injection via ambiguous input patterns.
     *
     * @param command The original command string
     * @param tokens Parsed tokens to check
     * @return true if malformed tokens detected
     */
    public static boolean hasMalformedTokens(String command, List<ParseEntry> tokens) {
        if (command == null || tokens == null || tokens.isEmpty()) {
            return false;
        }

        // Check for unterminated quotes in original command
        // Original: shellQuote.ts lines 125-143
        int doubleCount = 0;
        int singleCount = 0;
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // Handle escapes outside single quotes
            if (c == '\\' && !inSingle && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '"' && !inSingle) {
                doubleCount++;
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                singleCount++;
                inSingle = !inSingle;
            }
        }

        if (doubleCount % 2 != 0 || singleCount % 2 != 0) {
            log.log(System.Logger.Level.WARNING, "Malformed tokens: unbalanced quotes (double={0}, single={1})",
                    doubleCount, singleCount);
            return true;
        }

        // Check each string token for unbalanced delimiters
        // Original: shellQuote.ts lines 145-175
        for (ParseEntry entry : tokens) {
            if (!entry.isString()) continue;

            String value = entry.getValue();

            // Check unbalanced curly braces
            int openBraces = countChar(value, '{');
            int closeBraces = countChar(value, '}');
            if (openBraces != closeBraces) {
                log.log(System.Logger.Level.WARNING, "Malformed tokens: unbalanced braces in ''{0}''", value);
                return true;
            }

            // Check unbalanced parentheses
            int openParens = countChar(value, '(');
            int closeParens = countChar(value, ')');
            if (openParens != closeParens) {
                log.log(System.Logger.Level.WARNING, "Malformed tokens: unbalanced parens in ''{0}''", value);
                return true;
            }

            // Check unbalanced brackets
            int openBrackets = countChar(value, '[');
            int closeBrackets = countChar(value, ']');
            if (openBrackets != closeBrackets) {
                log.log(System.Logger.Level.WARNING, "Malformed tokens: unbalanced brackets in ''{0}''", value);
                return true;
            }

            // Check unescaped quotes in token
            int unescapedDouble = countUnescaped(value, '"');
            int unescapedSingle = countUnescaped(value, '\'');
            if (unescapedDouble % 2 != 0 || unescapedSingle % 2 != 0) {
                log.log(System.Logger.Level.WARNING, "Malformed tokens: unescaped quotes in ''{0}''", value);
                return true;
            }
        }

        return false;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    private static int countUnescaped(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                // Check if escaped
                int backslashes = 0;
                for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) {
                    backslashes++;
                }
                if (backslashes % 2 == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    // ========================================================================
    // hasShellQuoteSingleQuoteBug - Original source: shellQuote.ts lines 190-265
    // ========================================================================

    /**
     * Detects shell-quote library single quote bug exploits.
     *
     * Original source: src/utils/bash/shellQuote.ts → hasShellQuoteSingleQuoteBug()
     *
     * In bash, single quotes preserve ALL characters literally - backslash has no
     * special meaning. So '\' is just the string \ (the quote opens, contains \,
     * and the next ' closes it). But shell-quote incorrectly treats \ as an escape
     * character inside single quotes.
     *
     * This means the pattern '\' <payload> '\' hides <payload> from security checks.
     *
     * Security: Critical for preventing command injection.
     *
     * @param command The command to check
     * @return true if shell-quote bug pattern detected
     */
    public static boolean hasShellQuoteSingleQuoteBug(String command) {
        if (command == null || !command.contains("'")) {
            return false;
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // Handle backslash escaping outside of single quotes
            if (c == '\\' && !inSingleQuote && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                boolean wasInSingleQuote = inSingleQuote;
                inSingleQuote = !inSingleQuote;

                // When closing a single quote, check for trailing backslashes
                // Original: shellQuote.ts lines 238-259
                if (wasInSingleQuote) {
                    int backslashCount = 0;
                    int j = i - 1;
                    while (j >= 0 && command.charAt(j) == '\\') {
                        backslashCount++;
                        j--;
                    }

                    // Odd trailing backslashes = always a bug
                    if (backslashCount > 0 && backslashCount % 2 == 1) {
                        log.log(System.Logger.Level.WARNING,
                            "Shell-quote bug detected: odd trailing backslashes ({0}) at position {1}",
                            backslashCount, i);
                        return true;
                    }

                    // Even trailing backslashes: bug when a later ' exists
                    if (backslashCount > 0 && backslashCount % 2 == 0) {
                        if (command.indexOf("'", i + 1) != -1) {
                            log.log(System.Logger.Level.WARNING,
                                "Shell-quote bug detected: even trailing backslashes ({0}) with later quote",
                                backslashCount);
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    // ========================================================================
    // quote - Original source: shellQuote.ts lines 267-304
    // ========================================================================

    /**
     * Quote arguments for shell use.
     *
     * Original source: src/utils/bash/shellQuote.ts → quote()
     *
     * First tries strict validation, falls back to lenient mode for
     * complex types (converts via toString/JSON).
     *
     * @param args Arguments to quote
     * @return Quoted string safe for shell use
     */
    public static String quote(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }

        // Try strict validation first
        QuoteResult strictResult = tryQuoteShellArgs(args);
        if (strictResult.isSuccess()) {
            log.log(System.Logger.Level.DEBUG, "Strict quote succeeded for {0} args", args.length);
            return strictResult.getQuoted();
        }

        // Lenient fallback: convert unsupported types to string
        log.log(System.Logger.Level.DEBUG, "Using lenient quote fallback for {0} args", args.length);

        try {
            List<String> stringArgs = new ArrayList<>();
            for (Object arg : args) {
                if (arg == null) {
                    stringArgs.add("null");
                } else if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
                    stringArgs.add(String.valueOf(arg));
                } else {
                    // JSON fallback for complex objects
                    stringArgs.add(jsonStringify(arg));
                }
            }

            return quoteArgs(stringArgs.toArray(new String[0]));

        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "Failed to quote shell arguments safely: {0}", e.getMessage());
            throw new RuntimeException("Failed to quote shell arguments safely", e);
        }
    }

    /**
     * Quote an array of strings for shell use (single-quote each).
     */
    private static String quoteArgs(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append("'");
            if (args[i] != null) {
                sb.append(args[i].replace("'", "'\\''"));
            }
            sb.append("'");
        }
        return sb.toString();
    }

    /**
     * Simple JSON stringify for fallback.
     */
    private static String jsonStringify(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (obj instanceof Map) return obj.toString();
        if (obj instanceof List) return obj.toString();
        if (obj.getClass().isArray()) return Arrays.deepToString((Object[]) obj);
        return obj.toString();
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Check if an entry is a command operator (|, ||, &&, ;).
     */
    public static boolean isCommandOperator(ParseEntry entry) {
        return entry != null && entry.isOperator() && COMMAND_OPERATORS.contains(entry.getOp());
    }

    /**
     * Check if an entry is a redirect operator (<, >, >>, etc.).
     */
    public static boolean isRedirectOperator(ParseEntry entry) {
        return entry != null && entry.isOperator() && REDIRECT_OPERATORS.contains(entry.getOp());
    }

    /**
     * Find the last string token and its index.
     */
    public static int findLastStringTokenIndex(List<ParseEntry> tokens) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i).isString()) {
                return i;
            }
        }
        return -1;
    }

    // ========================================================================
    // Security validation methods
    // ========================================================================

    /**
     * Check for shell-quote single quote bug in command.
     * Alias for hasShellQuoteSingleQuoteBug for backward compatibility.
     */
    public static boolean hasShellQuoteBug(String command) {
        return hasShellQuoteSingleQuoteBug(command);
    }

    /**
     * Check for malformed tokens in command.
     * Parses and validates in one step.
     */
    public static boolean hasMalformedTokens(String command) {
        ParseResult result = tryParseShellCommand(command);
        if (!result.isSuccess()) {
            return true;
        }
        return hasMalformedTokens(command, result.getTokens());
    }

    /**
     * Validate a command for security issues.
     *
     * @param command The command to validate
     * @return null if valid, error message if issues found
     */
    public static String validateCommand(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }

        if (hasShellQuoteSingleQuoteBug(command)) {
            return "Command contains shell-quote single quote bug pattern";
        }

        ParseResult result = tryParseShellCommand(command);
        if (!result.isSuccess()) {
            return "Failed to parse command: " + result.getError();
        }

        if (hasMalformedTokens(command, result.getTokens())) {
            return "Command contains malformed tokens";
        }

        return null;
    }
}
