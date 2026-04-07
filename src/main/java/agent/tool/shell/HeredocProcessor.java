package agent.tool.shell;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atomic replication of Claude Code bash/heredoc.ts.
 *
 * Original source: src/utils/bash/heredoc.ts
 *
 * Heredoc extraction and restoration utilities for bash commands.
 *
 * The shell-quote library parses << as two separate < redirect operators,
 * which breaks command splitting for heredoc syntax. This module provides
 * utilities to extract heredocs before parsing and restore them after.
 *
 * Supported heredoc variations:
 * - <<WORD      - basic heredoc (expands $()/${} in body)
 * - <<'WORD'    - single-quoted delimiter (no expansion)
 * - <<"WORD"    - double-quoted delimiter (expansion)
 * - <<-WORD     - dash prefix (strips leading tabs)
 * - <<-'WORD'   - combined dash and quoted
 *
 * Security features:
 * - Prevents command smuggling via delimiter mismatches
 * - Handles line continuation attacks
 * - Detects backtick/$'...' constructs that desync quote tracking
 * - Filters nested heredocs
 * - Random salt placeholders prevent injection
 */
public final class HeredocProcessor {

    private static final System.Logger log = System.getLogger(HeredocProcessor.class.getName());

    private HeredocProcessor() {}

    // ========================================================================
    // Constants
    // ========================================================================

    private static final String HEREDOC_PLACEHOLDER_PREFIX = "__HEREDOC_";
    private static final String HEREDOC_PLACEHOLDER_SUFFIX = "__";

    /**
     * Regex pattern for matching heredoc start syntax.
     *
     * Original source: src/utils/bash/heredoc.ts lines 69-72
     *
     * Two alternatives handle quoted vs unquoted delimiters:
     * Alternative 1 (quoted): (['"]) (\\?\w+) \2
     * Alternative 2 (unquoted): \\?(\w+)
     *
     * SECURITY: The backslash MUST be inside the capture group for quoted
     * delimiters but OUTSIDE for unquoted ones.
     *
     * Uses [ \t]* (not \s*) to avoid matching across newlines.
     */
    private static final Pattern HEREDOC_START_PATTERN = Pattern.compile(
        "(?<!<)<<(?!<)(-)?[ \\t]*(?:(['\"])(\\\\?\\w+)\\2|\\\\?(\\w+))"
    );

    // ========================================================================
    // HeredocInfo - Original source: heredoc.ts lines 73-86
    // ========================================================================

    /**
     * Information about an extracted heredoc.
     */
    public static class HeredocInfo {
        private final String fullText;
        private final String delimiter;
        private final int operatorStartIndex;
        private final int operatorEndIndex;
        private final int contentStartIndex;
        private final int contentEndIndex;
        private final boolean isQuotedOrEscaped;

        public HeredocInfo(String fullText, String delimiter, int operatorStartIndex,
                          int operatorEndIndex, int contentStartIndex, int contentEndIndex,
                          boolean isQuotedOrEscaped) {
            this.fullText = fullText;
            this.delimiter = delimiter;
            this.operatorStartIndex = operatorStartIndex;
            this.operatorEndIndex = operatorEndIndex;
            this.contentStartIndex = contentStartIndex;
            this.contentEndIndex = contentEndIndex;
            this.isQuotedOrEscaped = isQuotedOrEscaped;
        }

        public String getFullText() { return fullText; }
        public String getDelimiter() { return delimiter; }
        public int getOperatorStartIndex() { return operatorStartIndex; }
        public int getOperatorEndIndex() { return operatorEndIndex; }
        public int getContentStartIndex() { return contentStartIndex; }
        public int getContentEndIndex() { return contentEndIndex; }
        public boolean isQuotedOrEscaped() { return isQuotedOrEscaped; }
    }

    // ========================================================================
    // HeredocExtractionResult - Original source: heredoc.ts lines 88-93
    // ========================================================================

    /**
     * Result of extracting heredocs from a command.
     */
    public static class HeredocExtractionResult {
        private final String processedCommand;
        private final Map<String, HeredocInfo> heredocs;

        public HeredocExtractionResult(String processedCommand, Map<String, HeredocInfo> heredocs) {
            this.processedCommand = processedCommand;
            this.heredocs = heredocs != null ? heredocs : new LinkedHashMap<>();
        }

        public String getProcessedCommand() { return processedCommand; }
        public Map<String, HeredocInfo> getHeredocs() { return heredocs; }
        public boolean hasHeredocs() { return !heredocs.isEmpty(); }
    }

    // ========================================================================
    // ExtractionOptions
    // ========================================================================

    /**
     * Options for heredoc extraction.
     */
    public static class ExtractionOptions {
        private final boolean quotedOnly;

        public ExtractionOptions() { this.quotedOnly = false; }
        public ExtractionOptions(boolean quotedOnly) { this.quotedOnly = quotedOnly; }
        public boolean isQuotedOnly() { return quotedOnly; }
    }

    // ========================================================================
    // extractHeredocs - Original source: heredoc.ts lines 113-687
    // ========================================================================

    /**
     * Extracts heredocs from a command string and replaces them with placeholders.
     *
     * Original source: src/utils/bash/heredoc.ts → extractHeredocs()
     *
     * Security checks applied before extraction:
     * 1. $'...' or $"..." (ANSI-C / locale quoting)
     * 2. Backticks before first <<
     * 3. Arithmetic evaluation (( ... )) using << as bit-shift
     * 4. Quotes/comment state tracking (incremental scanner)
     * 5. Backslash escape handling
     * 6. Line continuation detection
     * 7. Early closure detection (metacharacters after delimiter)
     * 8. Nested heredoc filtering
     * 9. Overlapping content start detection
     *
     * @param command The shell command potentially containing heredocs
     * @param options Extraction options
     * @return HeredocExtractionResult with processed command and placeholder map
     */
    public static HeredocExtractionResult extractHeredocs(String command, ExtractionOptions options) {
        Map<String, HeredocInfo> heredocs = new LinkedHashMap<>();

        if (command == null || !command.contains("<<")) {
            return new HeredocExtractionResult(command, heredocs);
        }

        log.log(System.Logger.Level.INFO, "Extracting heredocs from command (length={0})", command.length());

        // --- Security Pre-validation ---
        // Check 1: $'...' or $"..." patterns
        if (Pattern.compile("\\$['\"]").matcher(command).find()) {
            log.log(System.Logger.Level.DEBUG, "Skipping heredoc extraction: $'...' or $\"...\" detected");
            return new HeredocExtractionResult(command, heredocs);
        }

        // Check 2: Backticks before first <<
        int firstHeredocPos = command.indexOf("<<");
        if (firstHeredocPos > 0 && command.substring(0, firstHeredocPos).contains("`")) {
            log.log(System.Logger.Level.DEBUG, "Skipping heredoc extraction: backticks before <<");
            return new HeredocExtractionResult(command, heredocs);
        }

        // Check 3: Arithmetic evaluation (( ... ))
        if (firstHeredocPos > 0) {
            String before = command.substring(0, firstHeredocPos);
            int openArith = countOccurrences(before, "((");
            int closeArith = countOccurrences(before, "))");
            if (openArith > closeArith) {
                log.log(System.Logger.Level.DEBUG, "Skipping heredoc extraction: unbalanced (( before <<");
                return new HeredocExtractionResult(command, heredocs);
            }
        }

        // --- Main extraction loop ---
        Matcher matcher = HEREDOC_START_PATTERN.matcher(command);
        List<HeredocInfo> heredocMatches = new ArrayList<>();
        List<int[]> skippedRanges = new ArrayList<>();

        // Incremental quote/comment scanner state
        int scanPos = 0;
        boolean inSq = false, inDq = false, inCmt = false;
        boolean dqEsc = false;
        int pendingBs = 0;

        while (matcher.find()) {
            int startIdx = matcher.start();

            // Advance scanner
            for (; scanPos < startIdx; scanPos++) {
                char ch = command.charAt(scanPos);
                if (ch == '\n') inCmt = false;

                if (inSq) { if (ch == '\'') inSq = false; continue; }
                if (inDq) {
                    if (dqEsc) { dqEsc = false; continue; }
                    if (ch == '\\') { dqEsc = true; continue; }
                    if (ch == '"') inDq = false;
                    continue;
                }
                if (ch == '\\') { pendingBs++; continue; }
                boolean esc = pendingBs % 2 == 1;
                pendingBs = 0;
                if (esc) continue;
                if (ch == '\'') inSq = true;
                else if (ch == '"') inDq = true;
                else if (!inCmt && ch == '#') inCmt = true;
            }

            // Skip if inside quote or comment
            if (inSq || inDq) {
                log.log(System.Logger.Level.DEBUG, "Skipping heredoc at {0}: inside quote", startIdx);
                continue;
            }
            if (inCmt) {
                log.log(System.Logger.Level.DEBUG, "Skipping heredoc at {0}: inside comment", startIdx);
                continue;
            }
            if (pendingBs % 2 == 1) {
                log.log(System.Logger.Level.DEBUG, "Skipping heredoc at {0}: escaped", startIdx);
                continue;
            }

            // Skip if inside previously skipped heredoc body
            boolean insideSkipped = false;
            for (int[] range : skippedRanges) {
                if (startIdx > range[0] && startIdx < range[1]) { insideSkipped = true; break; }
            }
            if (insideSkipped) continue;

            // Extract heredoc info
            boolean isDash = "-".equals(matcher.group(1));
            String quoteChar = matcher.group(2);
            String delimiter = matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (delimiter == null) continue;

            int operatorEndIndex = startIdx + matcher.group(0).length();
            boolean isQuoted = quoteChar != null || matcher.group(0).contains("\\");

            // Verify closing quote matched (for quoted delimiters)
            if (quoteChar != null && operatorEndIndex > 0) {
                if (command.charAt(operatorEndIndex - 1) != quoteChar.charAt(0)) {
                    log.log(System.Logger.Level.DEBUG, "Skipping heredoc: closing quote mismatch");
                    continue;
                }
            }

            // Check next character is a bash metacharacter or end-of-string
            if (operatorEndIndex < command.length()) {
                char nextChar = command.charAt(operatorEndIndex);
                if (!isBashMetachar(nextChar)) {
                    log.log(System.Logger.Level.DEBUG, "Skipping heredoc: next char ''{0}'' not metachar", nextChar);
                    continue;
                }
            }

            // Find first unquoted newline after operator
            int nlOffset = findUnquotedNewline(command, operatorEndIndex);
            if (nlOffset == -1) continue;

            // Check for line continuation
            String sameLine = command.substring(operatorEndIndex, operatorEndIndex + nlOffset);
            int trailingBs = countTrailingBackslashes(sameLine);
            if (trailingBs % 2 == 1) {
                log.log(System.Logger.Level.DEBUG, "Skipping heredoc: line continuation detected");
                continue;
            }

            int contentStart = operatorEndIndex + nlOffset;
            String afterNl = command.substring(contentStart + 1);
            String[] lines = afterNl.split("\n", -1);

            // Find closing delimiter
            int closingIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String checkLine = isDash ? line.replaceFirst("^\\t*", "") : line;

                if (checkLine.equals(delimiter)) { closingIdx = i; break; }

                // Early closure detection
                if (checkLine.length() > delimiter.length() && checkLine.startsWith(delimiter)) {
                    char after = checkLine.charAt(delimiter.length());
                    if (")}`|&;(<>".indexOf(after) >= 0) { closingIdx = -1; break; }
                }
            }

            // Handle quotedOnly mode
            if (options != null && options.isQuotedOnly() && !isQuoted) {
                int skipEnd = closingIdx == -1 ? command.length()
                    : contentStart + 1 + String.join("\n", Arrays.copyOfRange(lines, 0, closingIdx + 1)).length();
                skippedRanges.add(new int[]{contentStart, skipEnd});
                continue;
            }

            if (closingIdx == -1) continue;

            // Calculate end position
            int contentLen = String.join("\n", Arrays.copyOfRange(lines, 0, closingIdx + 1)).length();
            int contentEnd = contentStart + 1 + contentLen;

            String opText = command.substring(startIdx, operatorEndIndex);
            String contentText = command.substring(contentStart, contentEnd);
            String fullText = opText + contentText;

            heredocMatches.add(new HeredocInfo(fullText, delimiter, startIdx,
                operatorEndIndex, contentStart, contentEnd, isQuoted));
        }

        if (heredocMatches.isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "No valid heredocs found");
            return new HeredocExtractionResult(command, heredocs);
        }

        // Filter nested heredocs
        List<HeredocInfo> topLevel = new ArrayList<>();
        for (HeredocInfo cand : heredocMatches) {
            boolean nested = false;
            for (HeredocInfo other : heredocMatches) {
                if (cand != other && cand.operatorStartIndex > other.contentStartIndex
                    && cand.operatorStartIndex < other.contentEndIndex) {
                    nested = true; break;
                }
            }
            if (!nested) topLevel.add(cand);
        }

        if (topLevel.isEmpty()) return new HeredocExtractionResult(command, heredocs);

        // Check for overlapping content starts
        Set<Integer> starts = new HashSet<>();
        for (HeredocInfo info : topLevel) starts.add(info.contentStartIndex);
        if (starts.size() < topLevel.size()) {
            log.log(System.Logger.Level.DEBUG, "Overlapping content starts, skipping");
            return new HeredocExtractionResult(command, heredocs);
        }

        // Generate salt and replace
        String salt = generateSalt();
        topLevel.sort((a, b) -> Integer.compare(b.contentEndIndex, a.contentEndIndex));

        String processed = command;
        for (int i = 0; i < topLevel.size(); i++) {
            HeredocInfo info = topLevel.get(i);
            int phIdx = topLevel.size() - 1 - i;
            String placeholder = HEREDOC_PLACEHOLDER_PREFIX + phIdx + "_" + salt + HEREDOC_PLACEHOLDER_SUFFIX;
            heredocs.put(placeholder, info);

            processed = processed.substring(0, info.operatorStartIndex) + placeholder
                + processed.substring(info.operatorEndIndex, info.contentStartIndex)
                + processed.substring(info.contentEndIndex);

            log.log(System.Logger.Level.INFO, "Extracted heredoc delimiter=''{0}'' -> {1}",
                    info.delimiter, placeholder);
        }

        log.log(System.Logger.Level.INFO, "Heredoc extraction complete: {0} heredoc(s)", heredocs.size());
        return new HeredocExtractionResult(processed, heredocs);
    }

    public static HeredocExtractionResult extractHeredocs(String command) {
        return extractHeredocs(command, new ExtractionOptions(false));
    }

    // ========================================================================
    // restoreHeredocs - Original source: heredoc.ts lines 711-720
    // ========================================================================

    /**
     * Restores heredoc placeholders back to original content.
     *
     * Original source: src/utils/bash/heredoc.ts → restoreHeredocs()
     *
     * @param parts Array of strings that may contain heredoc placeholders
     * @param heredocs The map of placeholders from extractHeredocs
     * @return New array with placeholders replaced by original content
     */
    public static String[] restoreHeredocs(String[] parts, Map<String, HeredocInfo> heredocs) {
        if (heredocs == null || heredocs.isEmpty() || parts == null) return parts;

        log.log(System.Logger.Level.DEBUG, "Restoring {0} heredoc(s)", heredocs.size());
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = restoreInString(parts[i], heredocs);
        }
        return result;
    }

    /**
     * Restore heredocs in a single string.
     */
    public static String restoreInString(String text, Map<String, HeredocInfo> heredocs) {
        if (heredocs == null || heredocs.isEmpty() || text == null) return text;
        String result = text;
        for (Map.Entry<String, HeredocInfo> e : heredocs.entrySet()) {
            result = result.replace(e.getKey(), e.getValue().fullText);
        }
        return result;
    }

    // ========================================================================
    // containsHeredoc - Original source: heredoc.ts lines 731-733
    // ========================================================================

    /**
     * Quick check if a command contains heredoc syntax.
     *
     * Original source: src/utils/bash/heredoc.ts → containsHeredoc()
     * Excludes bit-shift operators.
     *
     * @param command The shell command string
     * @return true if heredoc syntax detected
     */
    public static boolean containsHeredoc(String command) {
        if (command == null || !command.contains("<<")) return false;

        // Exclude bit-shift operators
        if (Pattern.compile("\\d\\s*<<\\s*\\d").matcher(command).find()) return false;
        if (Pattern.compile("\\[\\[\\s*\\d+\\s*<<\\s*\\d+\\s*\\]\\]").matcher(command).find()) return false;
        if (Pattern.compile("\\$\\(\\(.*<<.*\\)\\)").matcher(command).find()) return false;

        boolean found = HEREDOC_START_PATTERN.matcher(command).find();
        if (found) log.log(System.Logger.Level.DEBUG, "Heredoc syntax detected");
        return found;
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private static String generateSalt() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    private static boolean isBashMetachar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '|' || c == '&'
            || c == ';' || c == '(' || c == ')' || c == '<' || c == '>';
    }

    private static int findUnquotedNewline(String command, int start) {
        boolean inSq = false, inDq = false;
        for (int i = start; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (inSq) { if (ch == '\'') inSq = false; continue; }
            if (inDq) {
                if (ch == '\\' && i + 1 < command.length()) { i++; continue; }
                if (ch == '"') inDq = false;
                continue;
            }
            if (ch == '\n') return i - start;
            int bs = 0;
            for (int j = i - 1; j >= start && command.charAt(j) == '\\'; j--) bs++;
            if (bs % 2 == 1) continue;
            if (ch == '\'') inSq = true;
            else if (ch == '"') inDq = true;
        }
        return -1;
    }

    private static int countTrailingBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) count++;
        return count;
    }
}
