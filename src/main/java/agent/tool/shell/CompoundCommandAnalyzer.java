package agent.tool.shell;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Atomic replication of Claude Code bash/treeSitterAnalysis.ts.
 *
 * Original source: src/utils/bash/treeSitterAnalysis.ts
 *
 * Tree-sitter AST analysis utilities for bash command security validation.
 * Provides structured analysis of command structure for security validators.
 *
 * Key features:
 * - Quote context extraction (single/double/ANSI-C/heredoc)
 * - Compound structure detection (&&, ||, ;, pipelines, subshells)
 * - Dangerous pattern detection (command substitution, process substitution, etc.)
 * - Actual operator node detection
 *
 * Note: Java implementation uses regex-based parsing instead of tree-sitter
 * but maintains the same security semantics.
 */
public final class CompoundCommandAnalyzer {

    private static final System.Logger log = System.getLogger(CompoundCommandAnalyzer.class.getName());

    private CompoundCommandAnalyzer() {}

    // ========================================================================
    // QuoteContext - Original source: treeSitterAnalysis.ts lines 21-28
    // ========================================================================

    /**
     * Quote context extracted from command analysis.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → QuoteContext
     */
    public static class QuoteContext {
        /** Command text with single-quoted content removed (double-quoted content preserved) */
        private final String withDoubleQuotes;
        /** Command text with all quoted content removed */
        private final String fullyUnquoted;
        /** Like fullyUnquoted but preserves quote characters (', ") */
        private final String unquotedKeepQuoteChars;

        public QuoteContext(String withDoubleQuotes, String fullyUnquoted, String unquotedKeepQuoteChars) {
            this.withDoubleQuotes = withDoubleQuotes;
            this.fullyUnquoted = fullyUnquoted;
            this.unquotedKeepQuoteChars = unquotedKeepQuoteChars;
        }

        public String getWithDoubleQuotes() { return withDoubleQuotes; }
        public String getFullyUnquoted() { return fullyUnquoted; }
        public String getUnquotedKeepQuoteChars() { return unquotedKeepQuoteChars; }
    }

    // ========================================================================
    // CompoundStructure - Original source: treeSitterAnalysis.ts lines 30-43
    // ========================================================================

    /**
     * Structure of a compound command.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → CompoundStructure
     */
    public static class CompoundStructure {
        /** Whether the command has compound operators (&&, ||, ;) at the top level */
        private final boolean hasCompoundOperators;
        /** Whether the command has pipelines */
        private final boolean hasPipeline;
        /** Whether the command has subshells (...) */
        private final boolean hasSubshell;
        /** Whether the command has command groups {...} */
        private final boolean hasCommandGroup;
        /** Top-level compound operator types found */
        private final List<String> operators;
        /** Individual command segments split by compound operators */
        private final List<String> segments;

        public CompoundStructure(boolean hasCompoundOperators, boolean hasPipeline,
                                boolean hasSubshell, boolean hasCommandGroup,
                                List<String> operators, List<String> segments) {
            this.hasCompoundOperators = hasCompoundOperators;
            this.hasPipeline = hasPipeline;
            this.hasSubshell = hasSubshell;
            this.hasCommandGroup = hasCommandGroup;
            this.operators = operators != null ? operators : Collections.emptyList();
            this.segments = segments != null ? segments : Collections.singletonList("");
        }

        public boolean hasCompoundOperators() { return hasCompoundOperators; }
        public boolean hasPipeline() { return hasPipeline; }
        public boolean hasSubshell() { return hasSubshell; }
        public boolean hasCommandGroup() { return hasCommandGroup; }
        public List<String> getOperators() { return operators; }
        public List<String> getSegments() { return segments; }
    }

    // ========================================================================
    // DangerousPatterns - Original source: treeSitterAnalysis.ts lines 45-56
    // ========================================================================

    /**
     * Dangerous pattern detection results.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → DangerousPatterns
     */
    public static class DangerousPatterns {
        /** Has $() or backtick command substitution */
        private final boolean hasCommandSubstitution;
        /** Has <() or >() process substitution */
        private final boolean hasProcessSubstitution;
        /** Has ${...} parameter expansion */
        private final boolean hasParameterExpansion;
        /** Has heredoc */
        private final boolean hasHeredoc;
        /** Has comment (#) */
        private final boolean hasComment;

        public DangerousPatterns(boolean hasCommandSubstitution, boolean hasProcessSubstitution,
                                boolean hasParameterExpansion, boolean hasHeredoc, boolean hasComment) {
            this.hasCommandSubstitution = hasCommandSubstitution;
            this.hasProcessSubstitution = hasProcessSubstitution;
            this.hasParameterExpansion = hasParameterExpansion;
            this.hasHeredoc = hasHeredoc;
            this.hasComment = hasComment;
        }

        public boolean hasCommandSubstitution() { return hasCommandSubstitution; }
        public boolean hasProcessSubstitution() { return hasProcessSubstitution; }
        public boolean hasParameterExpansion() { return hasParameterExpansion; }
        public boolean hasHeredoc() { return hasHeredoc; }
        public boolean hasComment() { return hasComment; }
        public boolean hasAnyDangerousPattern() {
            return hasCommandSubstitution || hasProcessSubstitution || hasParameterExpansion || hasHeredoc;
        }
    }

    // ========================================================================
    // AnalysisResult - Combined analysis result
    // ========================================================================

    /**
     * Complete analysis result combining all aspects.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → TreeSitterAnalysis
     */
    public static class AnalysisResult {
        private final QuoteContext quoteContext;
        private final CompoundStructure compoundStructure;
        private final boolean hasActualOperatorNodes;
        private final DangerousPatterns dangerousPatterns;

        public AnalysisResult(QuoteContext quoteContext, CompoundStructure compoundStructure,
                             boolean hasActualOperatorNodes, DangerousPatterns dangerousPatterns) {
            this.quoteContext = quoteContext;
            this.compoundStructure = compoundStructure;
            this.hasActualOperatorNodes = hasActualOperatorNodes;
            this.dangerousPatterns = dangerousPatterns;
        }

        public QuoteContext getQuoteContext() { return quoteContext; }
        public CompoundStructure getCompoundStructure() { return compoundStructure; }
        public boolean hasActualOperatorNodes() { return hasActualOperatorNodes; }
        public DangerousPatterns getDangerousPatterns() { return dangerousPatterns; }
    }

    // ========================================================================
    // analyzeCommand - Original source: treeSitterAnalysis.ts lines 496-506
    // ========================================================================

    /**
     * Perform complete analysis of a command.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → analyzeCommand()
     *
     * Extracts all security-relevant data from the command in one pass.
     *
     * @param command The command to analyze
     * @return Complete AnalysisResult
     */
    public static AnalysisResult analyzeCommand(String command) {
        log.log(System.Logger.Level.DEBUG, "Analyzing command (length={0})",
                command != null ? command.length() : 0);

        QuoteContext quoteContext = extractQuoteContext(command);
        CompoundStructure compoundStructure = extractCompoundStructure(command);
        boolean hasOperators = hasActualOperatorNodes(command);
        DangerousPatterns dangerousPatterns = extractDangerousPatterns(command);

        log.log(System.Logger.Level.INFO,
            "Command analysis: compound={0}, pipeline={1}, subshell={2}, dangerous={3}",
            compoundStructure.hasCompoundOperators(), compoundStructure.hasPipeline(),
            compoundStructure.hasSubshell(), dangerousPatterns.hasAnyDangerousPattern());

        return new AnalysisResult(quoteContext, compoundStructure, hasOperators, dangerousPatterns);
    }

    // ========================================================================
    // extractQuoteContext - Original source: treeSitterAnalysis.ts lines 224-290
    // ========================================================================

    /**
     * Extract quote context from the command.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → extractQuoteContext()
     *
     * Three variants:
     * - withDoubleQuotes: remove single-quoted content, keep double-quoted content (without delimiters)
     * - fullyUnquoted: remove all quoted content entirely
     * - unquotedKeepQuoteChars: remove content but keep delimiter chars (' and ")
     *
     * @param command The command to analyze
     * @return QuoteContext with three unquoted variants
     */
    public static QuoteContext extractQuoteContext(String command) {
        if (command == null || command.isEmpty()) {
            return new QuoteContext("", "", "");
        }

        // Track quote spans
        List<int[]> singleQuoteSpans = new ArrayList<>();
        List<int[]> doubleQuoteSpans = new ArrayList<>();
        List<int[]> ansiCSpans = new ArrayList<>();
        List<int[]> quotedHeredocSpans = new ArrayList<>();

        boolean inSingle = false;
        boolean inDouble = false;
        int singleStart = -1;
        int doubleStart = -1;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // ANSI-C quoting $'...'
            if (c == '$' && i + 1 < command.length() && command.charAt(i + 1) == '\'' && !inSingle && !inDouble) {
                int start = i;
                i += 2; // skip $'
                while (i < command.length() && command.charAt(i) != '\'') {
                    if (command.charAt(i) == '\\' && i + 1 < command.length()) i++;
                    i++;
                }
                if (i < command.length()) {
                    ansiCSpans.add(new int[]{start, i + 1});
                }
                continue;
            }

            // Handle escapes outside single quotes
            if (c == '\\' && !inSingle && i + 1 < command.length()) {
                i++;
                continue;
            }

            // Double quote toggling
            if (c == '"' && !inSingle) {
                if (!inDouble) {
                    doubleStart = i;
                    inDouble = true;
                } else {
                    doubleQuoteSpans.add(new int[]{doubleStart, i + 1});
                    inDouble = false;
                }
                continue;
            }

            // Single quote toggling
            if (c == '\'' && !inDouble) {
                if (!inSingle) {
                    singleStart = i;
                    inSingle = true;
                } else {
                    singleQuoteSpans.add(new int[]{singleStart, i + 1});
                    inSingle = false;
                }
                continue;
            }
        }

        // Check for quoted heredocs
        if (HeredocProcessor.containsHeredoc(command)) {
            HeredocProcessor.HeredocExtractionResult heredocResult =
                HeredocProcessor.extractHeredocs(command, new HeredocProcessor.ExtractionOptions(true));
            for (Map.Entry<String, HeredocProcessor.HeredocInfo> entry : heredocResult.getHeredocs().entrySet()) {
                HeredocProcessor.HeredocInfo info = entry.getValue();
                if (info.isQuotedOrEscaped()) {
                    quotedHeredocSpans.add(new int[]{info.getOperatorStartIndex(), info.getContentEndIndex()});
                }
            }
        }

        // Build withDoubleQuotes: remove single-quoted, ansi-c, heredoc content,
        // but keep double-quoted content (without delimiters)
        String withDoubleQuotes = buildWithDoubleQuotes(command, singleQuoteSpans, ansiCSpans,
                                                         quotedHeredocSpans, doubleQuoteSpans);

        // Build fullyUnquoted: remove all quoted content
        String fullyUnquoted = buildFullyUnquoted(command, singleQuoteSpans, doubleQuoteSpans,
                                                    ansiCSpans, quotedHeredocSpans);

        // Build unquotedKeepQuoteChars: remove content but keep delimiters
        String unquotedKeepQuoteChars = buildUnquotedKeepQuoteChars(command, singleQuoteSpans,
                                                                     doubleQuoteSpans, ansiCSpans);

        return new QuoteContext(withDoubleQuotes, fullyUnquoted, unquotedKeepQuoteChars);
    }

    private static String buildWithDoubleQuotes(String command, List<int[]> singleSpans,
            List<int[]> ansiCSpans, List<int[]> heredocSpans, List<int[]> doubleSpans) {
        // Remove single-quoted, ANSI-C, and heredoc content entirely
        Set<Integer> removePositions = new HashSet<>();
        for (int[] span : singleSpans) addRange(removePositions, span[0], span[1]);
        for (int[] span : ansiCSpans) addRange(removePositions, span[0], span[1]);
        for (int[] span : heredocSpans) addRange(removePositions, span[0], span[1]);

        // For double quotes: remove only the delimiters
        Set<Integer> removeDelimiters = new HashSet<>();
        for (int[] span : doubleSpans) {
            removeDelimiters.add(span[0]);
            removeDelimiters.add(span[1] - 1);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.length(); i++) {
            if (removePositions.contains(i) || removeDelimiters.contains(i)) continue;
            sb.append(command.charAt(i));
        }
        return sb.toString();
    }

    private static String buildFullyUnquoted(String command, List<int[]> singleSpans,
            List<int[]> doubleSpans, List<int[]> ansiCSpans, List<int[]> heredocSpans) {
        Set<Integer> removePositions = new HashSet<>();
        for (int[] span : singleSpans) addRange(removePositions, span[0], span[1]);
        for (int[] span : doubleSpans) addRange(removePositions, span[0], span[1]);
        for (int[] span : ansiCSpans) addRange(removePositions, span[0], span[1]);
        for (int[] span : heredocSpans) addRange(removePositions, span[0], span[1]);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.length(); i++) {
            if (removePositions.contains(i)) continue;
            sb.append(command.charAt(i));
        }
        return sb.toString();
    }

    private static String buildUnquotedKeepQuoteChars(String command, List<int[]> singleSpans,
            List<int[]> doubleSpans, List<int[]> ansiCSpans) {
        // Sort spans descending by start for safe removal
        List<int[]> allSpans = new ArrayList<>();
        allSpans.addAll(singleSpans);
        allSpans.addAll(doubleSpans);
        allSpans.addAll(ansiCSpans);
        allSpans.sort((a, b) -> Integer.compare(b[0], a[0]));

        String result = command;
        for (int[] span : allSpans) {
            // Keep only the delimiters
            String before = result.substring(0, Math.min(span[0], result.length()));
            String after = span[1] <= result.length() ? result.substring(span[1]) : "";
            result = before + "''" + after; // Keep quote chars as placeholders
        }
        return result;
    }

    private static void addRange(Set<Integer> set, int start, int end) {
        for (int i = start; i < end; i++) set.add(i);
    }

    // ========================================================================
    // extractCompoundStructure - Original source: treeSitterAnalysis.ts lines 296-411
    // ========================================================================

    /**
     * Extract compound command structure.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → extractCompoundStructure()
     *
     * Detects:
     * - Compound operators: && || ;
     * - Pipelines: |
     * - Subshells: (...)
     * - Command groups: {...}
     *
     * @param command The command to analyze
     * @return CompoundStructure with detected features
     */
    public static CompoundStructure extractCompoundStructure(String command) {
        if (command == null || command.isEmpty()) {
            return new CompoundStructure(false, false, false, false, Collections.emptyList(),
                                        Collections.singletonList(""));
        }

        List<String> operators = new ArrayList<>();
        List<String> segments = new ArrayList<>();
        boolean hasPipeline = false;
        boolean hasSubshell = false;
        boolean hasCommandGroup = false;

        // Parse respecting quotes
        ShellQuote.ParseResult parseResult = ShellQuote.tryParseShellCommand(command);
        if (!parseResult.isSuccess()) {
            return new CompoundStructure(false, false, false, false, Collections.emptyList(),
                                        Collections.singletonList(command));
        }

        List<ShellQuote.ParseEntry> tokens = parseResult.getTokens();
        List<String> currentSegment = new ArrayList<>();

        for (ShellQuote.ParseEntry entry : tokens) {
            if (entry.isOperator()) {
                String op = entry.getOp();

                // Compound operators
                if ("&&".equals(op) || "||".equals(op) || ";".equals(op)) {
                    operators.add(op);
                    if (!currentSegment.isEmpty()) {
                        segments.add(String.join(" ", currentSegment));
                        currentSegment.clear();
                    }
                    continue;
                }

                // Pipe operator
                if ("|".equals(op)) {
                    hasPipeline = true;
                    if (!currentSegment.isEmpty()) {
                        segments.add(String.join(" ", currentSegment));
                        currentSegment.clear();
                    }
                    continue;
                }

                // Include other operators in current segment
                currentSegment.add(op);
            } else if (entry.isString()) {
                String value = entry.getValue();

                // Detect subshells
                if (value.startsWith("(") && value.endsWith(")")) {
                    hasSubshell = true;
                    segments.add(value);
                    continue;
                }

                // Detect command groups
                if (value.startsWith("{") && value.endsWith("}")) {
                    hasCommandGroup = true;
                    segments.add(value);
                    continue;
                }

                currentSegment.add(value);
            } else if (entry.isGlob()) {
                currentSegment.add(entry.getPattern());
            }
        }

        if (!currentSegment.isEmpty()) {
            segments.add(String.join(" ", currentSegment));
        }

        if (segments.isEmpty()) {
            segments.add(command);
        }

        log.log(System.Logger.Level.DEBUG, "Compound structure: ops={0}, segments={1}, pipeline={2}, subshell={3}",
                operators, segments.size(), hasPipeline, hasSubshell);

        return new CompoundStructure(!operators.isEmpty(), hasPipeline, hasSubshell, hasCommandGroup,
                                    operators, segments);
    }

    // ========================================================================
    // hasActualOperatorNodes - Original source: treeSitterAnalysis.ts lines 421-443
    // ========================================================================

    /**
     * Check whether the command contains actual compound operators.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → hasActualOperatorNodes()
     *
     * This eliminates false positives like `find -exec \;` where the semicolon
     * is escaped and part of a command argument, not a compound operator.
     *
     * @param command The command to check
     * @return true if actual operator nodes exist
     */
    public static boolean hasActualOperatorNodes(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        ShellQuote.ParseResult parseResult = ShellQuote.tryParseShellCommand(command);
        if (!parseResult.isSuccess()) {
            return false;
        }

        for (ShellQuote.ParseEntry entry : parseResult.getTokens()) {
            if (entry.isOperator()) {
                String op = entry.getOp();
                if (";".equals(op) || "&&".equals(op) || "||".equals(op)) {
                    log.log(System.Logger.Level.DEBUG, "Actual operator node found: {0}", op);
                    return true;
                }
            }
        }

        return false;
    }

    // ========================================================================
    // extractDangerousPatterns - Original source: treeSitterAnalysis.ts lines 448-489
    // ========================================================================

    /**
     * Extract dangerous pattern information from the command.
     *
     * Original source: src/utils/bash/treeSitterAnalysis.ts → extractDangerousPatterns()
     *
     * Detects:
     * - Command substitution: $() or backticks (outside safe quotes)
     * - Process substitution: <() or >()
     * - Parameter expansion: ${...}
     * - Heredoc: <<
     * - Comment: #
     *
     * @param command The command to analyze
     * @return DangerousPatterns with detected features
     */
    public static DangerousPatterns extractDangerousPatterns(String command) {
        if (command == null || command.isEmpty()) {
            return new DangerousPatterns(false, false, false, false, false);
        }

        // Use fullyUnquoted context to check patterns outside quotes
        QuoteContext quoteCtx = extractQuoteContext(command);
        String unquoted = quoteCtx.getFullyUnquoted();

        boolean hasCommandSub = unquoted.contains("$(") || unquoted.contains("`");
        boolean hasProcessSub = unquoted.contains("<(") || unquoted.contains(">(");
        boolean hasParamExpansion = unquoted.contains("${");
        boolean hasHeredoc = unquoted.contains("<<");
        boolean hasComment = unquoted.contains("#");

        if (hasCommandSub || hasProcessSub || hasParamExpansion || hasHeredoc) {
            log.log(System.Logger.Level.WARNING,
                "Dangerous patterns detected: cmdSub={0}, procSub={1}, paramExp={2}, heredoc={3}",
                hasCommandSub, hasProcessSub, hasParamExpansion, hasHeredoc);
        }

        return new DangerousPatterns(hasCommandSub, hasProcessSub, hasParamExpansion, hasHeredoc, hasComment);
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
}
