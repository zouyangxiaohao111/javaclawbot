package agent.tool.shell;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Atomic replication of Claude Code shell/readOnlyCommandValidation.ts.
 *
 * Original source: src/utils/shell/readOnlyCommandValidation.ts
 *
 * Shared command validation maps for shell tools (BashTool, PowerShellTool, etc.).
 * Exports complete command configuration maps that any shell tool can import:
 * - GIT_READ_ONLY_COMMANDS: all git subcommands with safe flags and callbacks
 * - GH_READ_ONLY_COMMANDS: ant-only gh CLI commands (network-dependent)
 * - EXTERNAL_READONLY_COMMANDS: cross-shell commands that work in both bash and PowerShell
 * - containsVulnerableUncPath: UNC path detection for credential leak prevention
 * - validateFlags: flag walking/validation loop
 * - outputLimits are in OutputLimits.java
 */
public final class ReadOnlyCommandValidation {

    private ReadOnlyCommandValidation() {}

    // ========================================================================
    // FlagArgType — Original source: readOnlyCommandValidation.ts FlagArgType
    // ========================================================================

    /**
     * Flag argument type.
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → FlagArgType
     */
    public enum FlagArgType {
        /** No argument (--color, -n) */
        NONE,
        /** Integer argument (--context=3) */
        NUMBER,
        /** Any string argument (--relative=path) */
        STRING,
        /** Single character (delimiter) */
        CHAR,
        /** Literal "{}" only */
        BRACE,
        /** Literal "EOF" only */
        EOF
    }

    // ========================================================================
    // DangerCallback — functional interface for dangerous command detection
    // ========================================================================

    /**
     * Callback to check if a command is dangerous.
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → additionalCommandIsDangerousCallback
     *
     * @param rawCommand The raw command string
     * @param args       The list of tokens AFTER the command name
     * @return true if the command is dangerous, false if safe
     */
    @FunctionalInterface
    public interface DangerCallback {
        boolean isDangerous(String rawCommand, List<String> args);
    }

    // ========================================================================
    // ExternalCommandConfig — Original source: readOnlyCommandValidation.ts ExternalCommandConfig
    // ========================================================================

    /**
     * Configuration for an external command's safe flags and validation.
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → ExternalCommandConfig
     */
    public static final class ExternalCommandConfig {
        private final Map<String, FlagArgType> safeFlags;
        private final DangerCallback additionalCommandIsDangerousCallback;
        private final boolean respectsDoubleDash;

        public ExternalCommandConfig(
                Map<String, FlagArgType> safeFlags,
                DangerCallback additionalCommandIsDangerousCallback,
                boolean respectsDoubleDash
        ) {
            this.safeFlags = safeFlags != null ? safeFlags : Map.of();
            this.additionalCommandIsDangerousCallback = additionalCommandIsDangerousCallback;
            this.respectsDoubleDash = respectsDoubleDash;
        }

        public ExternalCommandConfig(Map<String, FlagArgType> safeFlags) {
            this(safeFlags, null, true);
        }

        public ExternalCommandConfig(Map<String, FlagArgType> safeFlags, DangerCallback callback) {
            this(safeFlags, callback, true);
        }

        public Map<String, FlagArgType> safeFlags() { return safeFlags; }
        public DangerCallback additionalCommandIsDangerousCallback() { return additionalCommandIsDangerousCallback; }
        public boolean respectsDoubleDash() { return respectsDoubleDash; }
    }

    // ========================================================================
    // ValidateFlagsOptions — options for validateFlags
    // ========================================================================

    /**
     * Options for validateFlags().
     */
    public static final class ValidateFlagsOptions {
        public String commandName;
        public String rawCommand;
        public List<String> xargsTargetCommands;

        public ValidateFlagsOptions commandName(String v) { this.commandName = v; return this; }
        public ValidateFlagsOptions rawCommand(String v) { this.rawCommand = v; return this; }
        public ValidateFlagsOptions xargsTargetCommands(List<String> v) { this.xargsTargetCommands = v; return this; }
    }

    // ========================================================================
    // Shared git flag groups
    // Original source: readOnlyCommandValidation.ts lines 44-101
    // ========================================================================

    private static Map<String, FlagArgType> GIT_REF_SELECTION_FLAGS = Map.of(
            "--all", FlagArgType.NONE,
            "--branches", FlagArgType.NONE,
            "--tags", FlagArgType.NONE,
            "--remotes", FlagArgType.NONE
    );

    private static Map<String, FlagArgType> GIT_DATE_FILTER_FLAGS = Map.of(
            "--since", FlagArgType.STRING,
            "--after", FlagArgType.STRING,
            "--until", FlagArgType.STRING,
            "--before", FlagArgType.STRING
    );

    private static Map<String, FlagArgType> GIT_LOG_DISPLAY_FLAGS = Map.ofEntries(
            Map.entry("--oneline", FlagArgType.NONE),
            Map.entry("--graph", FlagArgType.NONE),
            Map.entry("--decorate", FlagArgType.NONE),
            Map.entry("--no-decorate", FlagArgType.NONE),
            Map.entry("--date", FlagArgType.STRING),
            Map.entry("--relative-date", FlagArgType.NONE)
    );

    private static Map<String, FlagArgType> GIT_COUNT_FLAGS = Map.of(
            "--max-count", FlagArgType.NUMBER,
            "-n", FlagArgType.NUMBER
    );

    private static Map<String, FlagArgType> GIT_STAT_FLAGS = Map.ofEntries(
            Map.entry("--stat", FlagArgType.NONE),
            Map.entry("--numstat", FlagArgType.NONE),
            Map.entry("--shortstat", FlagArgType.NONE),
            Map.entry("--name-only", FlagArgType.NONE),
            Map.entry("--name-status", FlagArgType.NONE)
    );

    private static Map<String, FlagArgType> GIT_COLOR_FLAGS = Map.of(
            "--color", FlagArgType.NONE,
            "--no-color", FlagArgType.NONE
    );

    private static Map<String, FlagArgType> GIT_PATCH_FLAGS = Map.ofEntries(
            Map.entry("--patch", FlagArgType.NONE),
            Map.entry("-p", FlagArgType.NONE),
            Map.entry("--no-patch", FlagArgType.NONE),
            Map.entry("--no-ext-diff", FlagArgType.NONE),
            Map.entry("-s", FlagArgType.NONE)
    );

    private static Map<String, FlagArgType> GIT_AUTHOR_FILTER_FLAGS = Map.of(
            "--author", FlagArgType.STRING,
            "--committer", FlagArgType.STRING,
            "--grep", FlagArgType.STRING
    );

    // ========================================================================
    // GIT_READ_ONLY_COMMANDS — complete map
    // Original source: readOnlyCommandValidation.ts lines 107-923
    // ========================================================================

    /**
     * Helper to merge multiple flag maps.
     */
    @SafeVarargs
    private static Map<String, FlagArgType> mergeFlags(Map<String, FlagArgType>... maps) {
        Map<String, FlagArgType> result = new LinkedHashMap<>();
        for (Map<String, FlagArgType> map : maps) {
            result.putAll(map);
        }
        return Map.copyOf(result);
    }

    /**
     * Complete map of all git read-only subcommands with their safe flags.
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → GIT_READ_ONLY_COMMANDS
     */
    public static final Map<String, ExternalCommandConfig> GIT_READ_ONLY_COMMANDS = Map.ofEntries(
            // git diff
            Map.entry("git diff", new ExternalCommandConfig(mergeFlags(
                    GIT_STAT_FLAGS, GIT_COLOR_FLAGS,
                    Map.ofEntries(
                            Map.entry("--dirstat", FlagArgType.NONE),
                            Map.entry("--summary", FlagArgType.NONE),
                            Map.entry("--patch-with-stat", FlagArgType.NONE),
                            Map.entry("--word-diff", FlagArgType.NONE),
                            Map.entry("--word-diff-regex", FlagArgType.STRING),
                            Map.entry("--color-words", FlagArgType.NONE),
                            Map.entry("--no-renames", FlagArgType.NONE),
                            Map.entry("--no-ext-diff", FlagArgType.NONE),
                            Map.entry("--check", FlagArgType.NONE),
                            Map.entry("--ws-error-highlight", FlagArgType.STRING),
                            Map.entry("--full-index", FlagArgType.NONE),
                            Map.entry("--binary", FlagArgType.NONE),
                            Map.entry("--abbrev", FlagArgType.NUMBER),
                            Map.entry("--break-rewrites", FlagArgType.NONE),
                            Map.entry("--find-renames", FlagArgType.NONE),
                            Map.entry("--find-copies", FlagArgType.NONE),
                            Map.entry("--find-copies-harder", FlagArgType.NONE),
                            Map.entry("--irreversible-delete", FlagArgType.NONE),
                            Map.entry("--diff-algorithm", FlagArgType.STRING),
                            Map.entry("--histogram", FlagArgType.NONE),
                            Map.entry("--patience", FlagArgType.NONE),
                            Map.entry("--minimal", FlagArgType.NONE),
                            Map.entry("--ignore-space-at-eol", FlagArgType.NONE),
                            Map.entry("--ignore-space-change", FlagArgType.NONE),
                            Map.entry("--ignore-all-space", FlagArgType.NONE),
                            Map.entry("--ignore-blank-lines", FlagArgType.NONE),
                            Map.entry("--inter-hunk-context", FlagArgType.NUMBER),
                            Map.entry("--function-context", FlagArgType.NONE),
                            Map.entry("--exit-code", FlagArgType.NONE),
                            Map.entry("--quiet", FlagArgType.NONE),
                            Map.entry("--cached", FlagArgType.NONE),
                            Map.entry("--staged", FlagArgType.NONE),
                            Map.entry("--pickaxe-regex", FlagArgType.NONE),
                            Map.entry("--pickaxe-all", FlagArgType.NONE),
                            Map.entry("--no-index", FlagArgType.NONE),
                            Map.entry("--relative", FlagArgType.STRING),
                            Map.entry("--diff-filter", FlagArgType.STRING),
                            Map.entry("-p", FlagArgType.NONE),
                            Map.entry("-u", FlagArgType.NONE),
                            Map.entry("-s", FlagArgType.NONE),
                            Map.entry("-M", FlagArgType.NONE),
                            Map.entry("-C", FlagArgType.NONE),
                            Map.entry("-B", FlagArgType.NONE),
                            Map.entry("-D", FlagArgType.NONE),
                            Map.entry("-l", FlagArgType.NONE),
                            Map.entry("-S", FlagArgType.STRING),
                            Map.entry("-G", FlagArgType.STRING),
                            Map.entry("-O", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.NONE)
                    )))),

            // git log
            Map.entry("git log", new ExternalCommandConfig(mergeFlags(
                    GIT_LOG_DISPLAY_FLAGS, GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS,
                    GIT_COUNT_FLAGS, GIT_STAT_FLAGS, GIT_COLOR_FLAGS, GIT_PATCH_FLAGS, GIT_AUTHOR_FILTER_FLAGS,
                    Map.ofEntries(
                            Map.entry("--abbrev-commit", FlagArgType.NONE),
                            Map.entry("--full-history", FlagArgType.NONE),
                            Map.entry("--dense", FlagArgType.NONE),
                            Map.entry("--sparse", FlagArgType.NONE),
                            Map.entry("--simplify-merges", FlagArgType.NONE),
                            Map.entry("--ancestry-path", FlagArgType.NONE),
                            Map.entry("--source", FlagArgType.NONE),
                            Map.entry("--first-parent", FlagArgType.NONE),
                            Map.entry("--merges", FlagArgType.NONE),
                            Map.entry("--no-merges", FlagArgType.NONE),
                            Map.entry("--reverse", FlagArgType.NONE),
                            Map.entry("--walk-reflogs", FlagArgType.NONE),
                            Map.entry("--skip", FlagArgType.NUMBER),
                            Map.entry("--max-age", FlagArgType.NUMBER),
                            Map.entry("--min-age", FlagArgType.NUMBER),
                            Map.entry("--no-min-parents", FlagArgType.NONE),
                            Map.entry("--no-max-parents", FlagArgType.NONE),
                            Map.entry("--follow", FlagArgType.NONE),
                            Map.entry("--no-walk", FlagArgType.NONE),
                            Map.entry("--left-right", FlagArgType.NONE),
                            Map.entry("--cherry-mark", FlagArgType.NONE),
                            Map.entry("--cherry-pick", FlagArgType.NONE),
                            Map.entry("--boundary", FlagArgType.NONE),
                            Map.entry("--topo-order", FlagArgType.NONE),
                            Map.entry("--date-order", FlagArgType.NONE),
                            Map.entry("--author-date-order", FlagArgType.NONE),
                            Map.entry("--pretty", FlagArgType.STRING),
                            Map.entry("--format", FlagArgType.STRING),
                            Map.entry("--diff-filter", FlagArgType.STRING),
                            Map.entry("-S", FlagArgType.STRING),
                            Map.entry("-G", FlagArgType.STRING),
                            Map.entry("--pickaxe-regex", FlagArgType.NONE),
                            Map.entry("--pickaxe-all", FlagArgType.NONE)
                    )))),

            // git show
            Map.entry("git show", new ExternalCommandConfig(mergeFlags(
                    GIT_LOG_DISPLAY_FLAGS, GIT_STAT_FLAGS, GIT_COLOR_FLAGS, GIT_PATCH_FLAGS,
                    Map.ofEntries(
                            Map.entry("--abbrev-commit", FlagArgType.NONE),
                            Map.entry("--word-diff", FlagArgType.NONE),
                            Map.entry("--word-diff-regex", FlagArgType.STRING),
                            Map.entry("--color-words", FlagArgType.NONE),
                            Map.entry("--pretty", FlagArgType.STRING),
                            Map.entry("--format", FlagArgType.STRING),
                            Map.entry("--first-parent", FlagArgType.NONE),
                            Map.entry("--raw", FlagArgType.NONE),
                            Map.entry("--diff-filter", FlagArgType.STRING),
                            Map.entry("-m", FlagArgType.NONE),
                            Map.entry("--quiet", FlagArgType.NONE)
                    )))),

            // git shortlog
            Map.entry("git shortlog", new ExternalCommandConfig(mergeFlags(
                    GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS,
                    Map.ofEntries(
                            Map.entry("-s", FlagArgType.NONE),
                            Map.entry("--summary", FlagArgType.NONE),
                            Map.entry("-n", FlagArgType.NONE),
                            Map.entry("--numbered", FlagArgType.NONE),
                            Map.entry("-e", FlagArgType.NONE),
                            Map.entry("--email", FlagArgType.NONE),
                            Map.entry("-c", FlagArgType.NONE),
                            Map.entry("--committer", FlagArgType.NONE),
                            Map.entry("--group", FlagArgType.STRING),
                            Map.entry("--format", FlagArgType.STRING),
                            Map.entry("--no-merges", FlagArgType.NONE),
                            Map.entry("--author", FlagArgType.STRING)
                    )))),

            // git reflog
            Map.entry("git reflog", new ExternalCommandConfig(
                    mergeFlags(GIT_LOG_DISPLAY_FLAGS, GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS, GIT_COUNT_FLAGS, GIT_AUTHOR_FILTER_FLAGS),
                    (ReadOnlyCommandValidation::gitReflogIsDangerous)
            )),

            // git stash list
            Map.entry("git stash list", new ExternalCommandConfig(
                    mergeFlags(GIT_LOG_DISPLAY_FLAGS, GIT_REF_SELECTION_FLAGS, GIT_COUNT_FLAGS)
            )),

            // git ls-remote
            Map.entry("git ls-remote", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--branches", FlagArgType.NONE),
                    Map.entry("-b", FlagArgType.NONE),
                    Map.entry("--tags", FlagArgType.NONE),
                    Map.entry("-t", FlagArgType.NONE),
                    Map.entry("--heads", FlagArgType.NONE),
                    Map.entry("-h", FlagArgType.NONE),
                    Map.entry("--refs", FlagArgType.NONE),
                    Map.entry("--quiet", FlagArgType.NONE),
                    Map.entry("-q", FlagArgType.NONE),
                    Map.entry("--exit-code", FlagArgType.NONE),
                    Map.entry("--get-url", FlagArgType.NONE),
                    Map.entry("--symref", FlagArgType.NONE),
                    Map.entry("--sort", FlagArgType.STRING)
            ))),

            // git status
            Map.entry("git status", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--short", FlagArgType.NONE),
                    Map.entry("-s", FlagArgType.NONE),
                    Map.entry("--branch", FlagArgType.NONE),
                    Map.entry("-b", FlagArgType.NONE),
                    Map.entry("--porcelain", FlagArgType.NONE),
                    Map.entry("--long", FlagArgType.NONE),
                    Map.entry("--verbose", FlagArgType.NONE),
                    Map.entry("-v", FlagArgType.NONE),
                    Map.entry("--untracked-files", FlagArgType.STRING),
                    Map.entry("-u", FlagArgType.STRING),
                    Map.entry("--ignored", FlagArgType.NONE),
                    Map.entry("--ignore-submodules", FlagArgType.STRING),
                    Map.entry("--column", FlagArgType.NONE),
                    Map.entry("--no-column", FlagArgType.NONE),
                    Map.entry("--ahead-behind", FlagArgType.NONE),
                    Map.entry("--no-ahead-behind", FlagArgType.NONE),
                    Map.entry("--renames", FlagArgType.NONE),
                    Map.entry("--no-renames", FlagArgType.NONE),
                    Map.entry("--find-renames", FlagArgType.STRING),
                    Map.entry("-M", FlagArgType.STRING)
            ))),

            // git blame
            Map.entry("git blame", new ExternalCommandConfig(mergeFlags(
                    GIT_COLOR_FLAGS,
                    Map.ofEntries(
                            Map.entry("-L", FlagArgType.STRING),
                            Map.entry("--porcelain", FlagArgType.NONE),
                            Map.entry("-p", FlagArgType.NONE),
                            Map.entry("--line-porcelain", FlagArgType.NONE),
                            Map.entry("--incremental", FlagArgType.NONE),
                            Map.entry("--root", FlagArgType.NONE),
                            Map.entry("--show-stats", FlagArgType.NONE),
                            Map.entry("--show-name", FlagArgType.NONE),
                            Map.entry("--show-number", FlagArgType.NONE),
                            Map.entry("-n", FlagArgType.NONE),
                            Map.entry("--show-email", FlagArgType.NONE),
                            Map.entry("-e", FlagArgType.NONE),
                            Map.entry("-f", FlagArgType.NONE),
                            Map.entry("--date", FlagArgType.STRING),
                            Map.entry("-w", FlagArgType.NONE),
                            Map.entry("--ignore-rev", FlagArgType.STRING),
                            Map.entry("--ignore-revs-file", FlagArgType.STRING),
                            Map.entry("-M", FlagArgType.NONE),
                            Map.entry("-C", FlagArgType.NONE),
                            Map.entry("--score-debug", FlagArgType.NONE),
                            Map.entry("--abbrev", FlagArgType.NUMBER),
                            Map.entry("-s", FlagArgType.NONE),
                            Map.entry("-l", FlagArgType.NONE),
                            Map.entry("-t", FlagArgType.NONE)
                    )))),

            // git ls-files
            Map.entry("git ls-files", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--cached", FlagArgType.NONE),
                    Map.entry("-c", FlagArgType.NONE),
                    Map.entry("--deleted", FlagArgType.NONE),
                    Map.entry("-d", FlagArgType.NONE),
                    Map.entry("--modified", FlagArgType.NONE),
                    Map.entry("-m", FlagArgType.NONE),
                    Map.entry("--others", FlagArgType.NONE),
                    Map.entry("-o", FlagArgType.NONE),
                    Map.entry("--ignored", FlagArgType.NONE),
                    Map.entry("-i", FlagArgType.NONE),
                    Map.entry("--stage", FlagArgType.NONE),
                    Map.entry("-s", FlagArgType.NONE),
                    Map.entry("--killed", FlagArgType.NONE),
                    Map.entry("-k", FlagArgType.NONE),
                    Map.entry("--unmerged", FlagArgType.NONE),
                    Map.entry("-u", FlagArgType.NONE),
                    Map.entry("--directory", FlagArgType.NONE),
                    Map.entry("--no-empty-directory", FlagArgType.NONE),
                    Map.entry("--eol", FlagArgType.NONE),
                    Map.entry("--full-name", FlagArgType.NONE),
                    Map.entry("--abbrev", FlagArgType.NUMBER),
                    Map.entry("--debug", FlagArgType.NONE),
                    Map.entry("-z", FlagArgType.NONE),
                    Map.entry("-t", FlagArgType.NONE),
                    Map.entry("-v", FlagArgType.NONE),
                    Map.entry("-f", FlagArgType.NONE),
                    Map.entry("--exclude", FlagArgType.STRING),
                    Map.entry("-x", FlagArgType.STRING),
                    Map.entry("--exclude-from", FlagArgType.STRING),
                    Map.entry("-X", FlagArgType.STRING),
                    Map.entry("--exclude-per-directory", FlagArgType.STRING),
                    Map.entry("--exclude-standard", FlagArgType.NONE),
                    Map.entry("--error-unmatch", FlagArgType.NONE),
                    Map.entry("--recurse-submodules", FlagArgType.NONE)
            ))),

            // git config --get
            Map.entry("git config --get", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--local", FlagArgType.NONE),
                    Map.entry("--global", FlagArgType.NONE),
                    Map.entry("--system", FlagArgType.NONE),
                    Map.entry("--worktree", FlagArgType.NONE),
                    Map.entry("--default", FlagArgType.STRING),
                    Map.entry("--type", FlagArgType.STRING),
                    Map.entry("--bool", FlagArgType.NONE),
                    Map.entry("--int", FlagArgType.NONE),
                    Map.entry("--bool-or-int", FlagArgType.NONE),
                    Map.entry("--path", FlagArgType.NONE),
                    Map.entry("--expiry-date", FlagArgType.NONE),
                    Map.entry("-z", FlagArgType.NONE),
                    Map.entry("--null", FlagArgType.NONE),
                    Map.entry("--name-only", FlagArgType.NONE),
                    Map.entry("--show-origin", FlagArgType.NONE),
                    Map.entry("--show-scope", FlagArgType.NONE)
            ))),

            // git remote show
            Map.entry("git remote show", new ExternalCommandConfig(
                    Map.of("-n", FlagArgType.NONE),
                    (rawCmd, args) -> {
                        List<String> positional = new ArrayList<>(args);
                        positional.removeIf(a -> "-n".equals(a));
                        if (positional.size() != 1) return true;
                        return !positional.get(0).matches("^[a-zA-Z0-9_-]+$");
                    }
            )),

            // git remote
            Map.entry("git remote", new ExternalCommandConfig(
                    Map.of("-v", FlagArgType.NONE, "--verbose", FlagArgType.NONE),
                    (rawCmd, args) -> args.stream().anyMatch(a -> !"-v".equals(a) && !"--verbose".equals(a))
            )),

            // git merge-base
            Map.entry("git merge-base", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--is-ancestor", FlagArgType.NONE),
                    Map.entry("--fork-point", FlagArgType.NONE),
                    Map.entry("--octopus", FlagArgType.NONE),
                    Map.entry("--independent", FlagArgType.NONE),
                    Map.entry("--all", FlagArgType.NONE)
            ))),

            // git rev-parse
            Map.entry("git rev-parse", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--verify", FlagArgType.NONE),
                    Map.entry("--short", FlagArgType.STRING),
                    Map.entry("--abbrev-ref", FlagArgType.NONE),
                    Map.entry("--symbolic", FlagArgType.NONE),
                    Map.entry("--symbolic-full-name", FlagArgType.NONE),
                    Map.entry("--show-toplevel", FlagArgType.NONE),
                    Map.entry("--show-cdup", FlagArgType.NONE),
                    Map.entry("--show-prefix", FlagArgType.NONE),
                    Map.entry("--git-dir", FlagArgType.NONE),
                    Map.entry("--git-common-dir", FlagArgType.NONE),
                    Map.entry("--absolute-git-dir", FlagArgType.NONE),
                    Map.entry("--show-superproject-working-tree", FlagArgType.NONE),
                    Map.entry("--is-inside-work-tree", FlagArgType.NONE),
                    Map.entry("--is-inside-git-dir", FlagArgType.NONE),
                    Map.entry("--is-bare-repository", FlagArgType.NONE),
                    Map.entry("--is-shallow-repository", FlagArgType.NONE),
                    Map.entry("--is-shallow-update", FlagArgType.NONE),
                    Map.entry("--path-prefix", FlagArgType.NONE)
            ))),

            // git rev-list
            Map.entry("git rev-list", new ExternalCommandConfig(mergeFlags(
                    GIT_REF_SELECTION_FLAGS, GIT_DATE_FILTER_FLAGS, GIT_COUNT_FLAGS, GIT_AUTHOR_FILTER_FLAGS,
                    Map.ofEntries(
                            Map.entry("--count", FlagArgType.NONE),
                            Map.entry("--reverse", FlagArgType.NONE),
                            Map.entry("--first-parent", FlagArgType.NONE),
                            Map.entry("--ancestry-path", FlagArgType.NONE),
                            Map.entry("--merges", FlagArgType.NONE),
                            Map.entry("--no-merges", FlagArgType.NONE),
                            Map.entry("--min-parents", FlagArgType.NUMBER),
                            Map.entry("--max-parents", FlagArgType.NUMBER),
                            Map.entry("--no-min-parents", FlagArgType.NONE),
                            Map.entry("--no-max-parents", FlagArgType.NONE),
                            Map.entry("--skip", FlagArgType.NUMBER),
                            Map.entry("--max-age", FlagArgType.NUMBER),
                            Map.entry("--min-age", FlagArgType.NUMBER),
                            Map.entry("--walk-reflogs", FlagArgType.NONE),
                            Map.entry("--oneline", FlagArgType.NONE),
                            Map.entry("--abbrev-commit", FlagArgType.NONE),
                            Map.entry("--pretty", FlagArgType.STRING),
                            Map.entry("--format", FlagArgType.STRING),
                            Map.entry("--abbrev", FlagArgType.NUMBER),
                            Map.entry("--full-history", FlagArgType.NONE),
                            Map.entry("--dense", FlagArgType.NONE),
                            Map.entry("--sparse", FlagArgType.NONE),
                            Map.entry("--source", FlagArgType.NONE),
                            Map.entry("--graph", FlagArgType.NONE)
                    )))),

            // git describe
            Map.entry("git describe", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--tags", FlagArgType.NONE),
                    Map.entry("--match", FlagArgType.STRING),
                    Map.entry("--exclude", FlagArgType.STRING),
                    Map.entry("--long", FlagArgType.NONE),
                    Map.entry("--abbrev", FlagArgType.NUMBER),
                    Map.entry("--always", FlagArgType.NONE),
                    Map.entry("--contains", FlagArgType.NONE),
                    Map.entry("--first-match", FlagArgType.NONE),
                    Map.entry("--exact-match", FlagArgType.NONE),
                    Map.entry("--candidates", FlagArgType.NUMBER),
                    Map.entry("--dirty", FlagArgType.NONE),
                    Map.entry("--broken", FlagArgType.NONE)
            ))),

            // git cat-file
            Map.entry("git cat-file", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("-t", FlagArgType.NONE),
                    Map.entry("-s", FlagArgType.NONE),
                    Map.entry("-p", FlagArgType.NONE),
                    Map.entry("-e", FlagArgType.NONE),
                    Map.entry("--batch-check", FlagArgType.NONE),
                    Map.entry("--allow-undetermined-type", FlagArgType.NONE)
            ))),

            // git for-each-ref
            Map.entry("git for-each-ref", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--format", FlagArgType.STRING),
                    Map.entry("--sort", FlagArgType.STRING),
                    Map.entry("--count", FlagArgType.NUMBER),
                    Map.entry("--contains", FlagArgType.STRING),
                    Map.entry("--no-contains", FlagArgType.STRING),
                    Map.entry("--merged", FlagArgType.STRING),
                    Map.entry("--no-merged", FlagArgType.STRING),
                    Map.entry("--points-at", FlagArgType.STRING)
            ))),

            // git grep
            Map.entry("git grep", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("-e", FlagArgType.STRING),
                    Map.entry("-E", FlagArgType.NONE),
                    Map.entry("--extended-regexp", FlagArgType.NONE),
                    Map.entry("-G", FlagArgType.NONE),
                    Map.entry("--basic-regexp", FlagArgType.NONE),
                    Map.entry("-F", FlagArgType.NONE),
                    Map.entry("--fixed-strings", FlagArgType.NONE),
                    Map.entry("-P", FlagArgType.NONE),
                    Map.entry("--perl-regexp", FlagArgType.NONE),
                    Map.entry("-i", FlagArgType.NONE),
                    Map.entry("--ignore-case", FlagArgType.NONE),
                    Map.entry("-v", FlagArgType.NONE),
                    Map.entry("--invert-match", FlagArgType.NONE),
                    Map.entry("-w", FlagArgType.NONE),
                    Map.entry("--word-regexp", FlagArgType.NONE),
                    Map.entry("-n", FlagArgType.NONE),
                    Map.entry("--line-number", FlagArgType.NONE),
                    Map.entry("-c", FlagArgType.NONE),
                    Map.entry("--count", FlagArgType.NONE),
                    Map.entry("-l", FlagArgType.NONE),
                    Map.entry("--files-with-matches", FlagArgType.NONE),
                    Map.entry("-L", FlagArgType.NONE),
                    Map.entry("--files-without-match", FlagArgType.NONE),
                    Map.entry("-h", FlagArgType.NONE),
                    Map.entry("-H", FlagArgType.NONE),
                    Map.entry("--heading", FlagArgType.NONE),
                    Map.entry("--break", FlagArgType.NONE),
                    Map.entry("--full-name", FlagArgType.NONE),
                    Map.entry("--color", FlagArgType.NONE),
                    Map.entry("--no-color", FlagArgType.NONE),
                    Map.entry("-o", FlagArgType.NONE),
                    Map.entry("--only-matching", FlagArgType.NONE),
                    Map.entry("-A", FlagArgType.NUMBER),
                    Map.entry("--after-context", FlagArgType.NUMBER),
                    Map.entry("-B", FlagArgType.NUMBER),
                    Map.entry("--before-context", FlagArgType.NUMBER),
                    Map.entry("-C", FlagArgType.NUMBER),
                    Map.entry("--context", FlagArgType.NUMBER),
                    Map.entry("--and", FlagArgType.NONE),
                    Map.entry("--or", FlagArgType.NONE),
                    Map.entry("--not", FlagArgType.NONE),
                    Map.entry("--max-depth", FlagArgType.NUMBER),
                    Map.entry("--untracked", FlagArgType.NONE),
                    Map.entry("--no-index", FlagArgType.NONE),
                    Map.entry("--recurse-submodules", FlagArgType.NONE),
                    Map.entry("--cached", FlagArgType.NONE),
                    Map.entry("--threads", FlagArgType.NUMBER),
                    Map.entry("-q", FlagArgType.NONE),
                    Map.entry("--quiet", FlagArgType.NONE)
            ))),

            // git stash show
            Map.entry("git stash show", new ExternalCommandConfig(mergeFlags(
                    GIT_STAT_FLAGS, GIT_COLOR_FLAGS, GIT_PATCH_FLAGS,
                    Map.ofEntries(
                            Map.entry("--word-diff", FlagArgType.NONE),
                            Map.entry("--word-diff-regex", FlagArgType.STRING),
                            Map.entry("--diff-filter", FlagArgType.STRING),
                            Map.entry("--abbrev", FlagArgType.NUMBER)
                    )))),

            // git worktree list
            Map.entry("git worktree list", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--porcelain", FlagArgType.NONE),
                    Map.entry("-v", FlagArgType.NONE),
                    Map.entry("--verbose", FlagArgType.NONE),
                    Map.entry("--expire", FlagArgType.STRING)
            ))),

            // git tag — with dangerous callback to block tag creation
            Map.entry("git tag", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("-l", FlagArgType.NONE),
                            Map.entry("--list", FlagArgType.NONE),
                            Map.entry("-n", FlagArgType.NUMBER),
                            Map.entry("--contains", FlagArgType.STRING),
                            Map.entry("--no-contains", FlagArgType.STRING),
                            Map.entry("--merged", FlagArgType.STRING),
                            Map.entry("--no-merged", FlagArgType.STRING),
                            Map.entry("--sort", FlagArgType.STRING),
                            Map.entry("--format", FlagArgType.STRING),
                            Map.entry("--points-at", FlagArgType.STRING),
                            Map.entry("--column", FlagArgType.NONE),
                            Map.entry("--no-column", FlagArgType.NONE),
                            Map.entry("-i", FlagArgType.NONE),
                            Map.entry("--ignore-case", FlagArgType.NONE)
                    ),
                    ReadOnlyCommandValidation::gitTagIsDangerous
            )),

            // git branch — with dangerous callback to block branch creation
            Map.entry("git branch", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("-l", FlagArgType.NONE),
                            Map.entry("--list", FlagArgType.NONE),
                            Map.entry("-a", FlagArgType.NONE),
                            Map.entry("--all", FlagArgType.NONE),
                            Map.entry("-r", FlagArgType.NONE),
                            Map.entry("--remotes", FlagArgType.NONE),
                            Map.entry("-v", FlagArgType.NONE),
                            Map.entry("-vv", FlagArgType.NONE),
                            Map.entry("--verbose", FlagArgType.NONE),
                            Map.entry("--color", FlagArgType.NONE),
                            Map.entry("--no-color", FlagArgType.NONE),
                            Map.entry("--column", FlagArgType.NONE),
                            Map.entry("--no-column", FlagArgType.NONE),
                            Map.entry("--abbrev", FlagArgType.NUMBER),
                            Map.entry("--no-abbrev", FlagArgType.NONE),
                            Map.entry("--contains", FlagArgType.STRING),
                            Map.entry("--no-contains", FlagArgType.STRING),
                            Map.entry("--merged", FlagArgType.NONE),
                            Map.entry("--no-merged", FlagArgType.NONE),
                            Map.entry("--points-at", FlagArgType.STRING),
                            Map.entry("--sort", FlagArgType.STRING),
                            Map.entry("--show-current", FlagArgType.NONE),
                            Map.entry("-i", FlagArgType.NONE),
                            Map.entry("--ignore-case", FlagArgType.NONE)
                    ),
                    ReadOnlyCommandValidation::gitBranchIsDangerous
            ))
    );

    // ========================================================================
    // GH_READ_ONLY_COMMANDS
    // Original source: readOnlyCommandValidation.ts lines 984-1380
    // ========================================================================

    /**
     * GH CLI read-only commands (ant-only, network-dependent).
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → GH_READ_ONLY_COMMANDS
     */
    public static final Map<String, ExternalCommandConfig> GH_READ_ONLY_COMMANDS = Map.ofEntries(
            Map.entry("gh pr view", new ExternalCommandConfig(
                    Map.of("--json", FlagArgType.STRING, "--comments", FlagArgType.NONE, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh pr list", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--state", FlagArgType.STRING), Map.entry("-s", FlagArgType.STRING),
                            Map.entry("--author", FlagArgType.STRING), Map.entry("--assignee", FlagArgType.STRING),
                            Map.entry("--label", FlagArgType.STRING), Map.entry("--limit", FlagArgType.NUMBER),
                            Map.entry("-L", FlagArgType.NUMBER), Map.entry("--base", FlagArgType.STRING),
                            Map.entry("--head", FlagArgType.STRING), Map.entry("--search", FlagArgType.STRING),
                            Map.entry("--json", FlagArgType.STRING), Map.entry("--draft", FlagArgType.NONE),
                            Map.entry("--app", FlagArgType.STRING), Map.entry("--repo", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.STRING)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh pr diff", new ExternalCommandConfig(
                    Map.of("--color", FlagArgType.STRING, "--name-only", FlagArgType.NONE, "--patch", FlagArgType.NONE, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh pr checks", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--watch", FlagArgType.NONE), Map.entry("--required", FlagArgType.NONE),
                            Map.entry("--fail-fast", FlagArgType.NONE), Map.entry("--json", FlagArgType.STRING),
                            Map.entry("--interval", FlagArgType.NUMBER), Map.entry("--repo", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.STRING)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh issue view", new ExternalCommandConfig(
                    Map.of("--json", FlagArgType.STRING, "--comments", FlagArgType.NONE, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh issue list", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--state", FlagArgType.STRING), Map.entry("-s", FlagArgType.STRING),
                            Map.entry("--assignee", FlagArgType.STRING), Map.entry("--author", FlagArgType.STRING),
                            Map.entry("--label", FlagArgType.STRING), Map.entry("--limit", FlagArgType.NUMBER),
                            Map.entry("-L", FlagArgType.NUMBER), Map.entry("--milestone", FlagArgType.STRING),
                            Map.entry("--search", FlagArgType.STRING), Map.entry("--json", FlagArgType.STRING),
                            Map.entry("--app", FlagArgType.STRING), Map.entry("--repo", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.STRING)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh repo view", new ExternalCommandConfig(
                    Map.of("--json", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh run list", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--branch", FlagArgType.STRING), Map.entry("-b", FlagArgType.STRING),
                            Map.entry("--status", FlagArgType.STRING), Map.entry("-s", FlagArgType.STRING),
                            Map.entry("--workflow", FlagArgType.STRING), Map.entry("-w", FlagArgType.STRING),
                            Map.entry("--limit", FlagArgType.NUMBER), Map.entry("-L", FlagArgType.NUMBER),
                            Map.entry("--json", FlagArgType.STRING), Map.entry("--repo", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.STRING), Map.entry("--event", FlagArgType.STRING),
                            Map.entry("-e", FlagArgType.STRING), Map.entry("--user", FlagArgType.STRING),
                            Map.entry("-u", FlagArgType.STRING), Map.entry("--created", FlagArgType.STRING),
                            Map.entry("--commit", FlagArgType.STRING), Map.entry("-c", FlagArgType.STRING)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh run view", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--log", FlagArgType.NONE), Map.entry("--log-failed", FlagArgType.NONE),
                            Map.entry("--exit-status", FlagArgType.NONE), Map.entry("--verbose", FlagArgType.NONE),
                            Map.entry("-v", FlagArgType.NONE), Map.entry("--json", FlagArgType.STRING),
                            Map.entry("--repo", FlagArgType.STRING), Map.entry("-R", FlagArgType.STRING),
                            Map.entry("--job", FlagArgType.STRING), Map.entry("-j", FlagArgType.STRING),
                            Map.entry("--attempt", FlagArgType.NUMBER), Map.entry("-a", FlagArgType.NUMBER)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh auth status", new ExternalCommandConfig(
                    Map.of("--active", FlagArgType.NONE, "-a", FlagArgType.NONE, "--hostname", FlagArgType.STRING, "-h", FlagArgType.STRING, "--json", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh pr status", new ExternalCommandConfig(
                    Map.of("--conflict-status", FlagArgType.NONE, "-c", FlagArgType.NONE, "--json", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh issue status", new ExternalCommandConfig(
                    Map.of("--json", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh release list", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--exclude-drafts", FlagArgType.NONE), Map.entry("--exclude-pre-releases", FlagArgType.NONE),
                            Map.entry("--json", FlagArgType.STRING), Map.entry("--limit", FlagArgType.NUMBER),
                            Map.entry("-L", FlagArgType.NUMBER), Map.entry("--order", FlagArgType.STRING),
                            Map.entry("-O", FlagArgType.STRING), Map.entry("--repo", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.STRING)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh release view", new ExternalCommandConfig(
                    Map.of("--json", FlagArgType.STRING, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh workflow list", new ExternalCommandConfig(
                    Map.of("--all", FlagArgType.NONE, "-a", FlagArgType.NONE, "--json", FlagArgType.STRING, "--limit", FlagArgType.NUMBER, "-L", FlagArgType.NUMBER, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh workflow view", new ExternalCommandConfig(
                    Map.of("--ref", FlagArgType.STRING, "-r", FlagArgType.STRING, "--yaml", FlagArgType.NONE, "-y", FlagArgType.NONE, "--repo", FlagArgType.STRING, "-R", FlagArgType.STRING),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            )),
            Map.entry("gh label list", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--json", FlagArgType.STRING), Map.entry("--limit", FlagArgType.NUMBER),
                            Map.entry("-L", FlagArgType.NUMBER), Map.entry("--order", FlagArgType.STRING),
                            Map.entry("--search", FlagArgType.STRING), Map.entry("-S", FlagArgType.STRING),
                            Map.entry("--sort", FlagArgType.STRING), Map.entry("--repo", FlagArgType.STRING),
                            Map.entry("-R", FlagArgType.STRING)
                    ),
                    ReadOnlyCommandValidation::ghIsDangerousCallback
            ))
    );

    // ========================================================================
    // DOCKER_READ_ONLY_COMMANDS
    // ========================================================================

    public static final Map<String, ExternalCommandConfig> DOCKER_READ_ONLY_COMMANDS = Map.of(
            "docker logs", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--follow", FlagArgType.NONE), Map.entry("-f", FlagArgType.NONE),
                    Map.entry("--tail", FlagArgType.STRING), Map.entry("-n", FlagArgType.STRING),
                    Map.entry("--timestamps", FlagArgType.NONE), Map.entry("-t", FlagArgType.NONE),
                    Map.entry("--since", FlagArgType.STRING), Map.entry("--until", FlagArgType.STRING),
                    Map.entry("--details", FlagArgType.NONE)
            )),
            "docker inspect", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("--format", FlagArgType.STRING), Map.entry("-f", FlagArgType.STRING),
                    Map.entry("--type", FlagArgType.STRING), Map.entry("--size", FlagArgType.NONE),
                    Map.entry("-s", FlagArgType.NONE)
            ))
    );

    // ========================================================================
    // RIPGREP_READ_ONLY_COMMANDS
    // ========================================================================

    public static final Map<String, ExternalCommandConfig> RIPGREP_READ_ONLY_COMMANDS = Map.of(
            "rg", new ExternalCommandConfig(Map.ofEntries(
                    Map.entry("-e", FlagArgType.STRING), Map.entry("--regexp", FlagArgType.STRING),
                    Map.entry("-f", FlagArgType.STRING),
                    Map.entry("-i", FlagArgType.NONE), Map.entry("--ignore-case", FlagArgType.NONE),
                    Map.entry("-S", FlagArgType.NONE), Map.entry("--smart-case", FlagArgType.NONE),
                    Map.entry("-F", FlagArgType.NONE), Map.entry("--fixed-strings", FlagArgType.NONE),
                    Map.entry("-w", FlagArgType.NONE), Map.entry("--word-regexp", FlagArgType.NONE),
                    Map.entry("-v", FlagArgType.NONE), Map.entry("--invert-match", FlagArgType.NONE),
                    Map.entry("-c", FlagArgType.NONE), Map.entry("--count", FlagArgType.NONE),
                    Map.entry("-l", FlagArgType.NONE), Map.entry("--files-with-matches", FlagArgType.NONE),
                    Map.entry("--files-without-match", FlagArgType.NONE),
                    Map.entry("-n", FlagArgType.NONE), Map.entry("--line-number", FlagArgType.NONE),
                    Map.entry("-o", FlagArgType.NONE), Map.entry("--only-matching", FlagArgType.NONE),
                    Map.entry("-A", FlagArgType.NUMBER), Map.entry("--after-context", FlagArgType.NUMBER),
                    Map.entry("-B", FlagArgType.NUMBER), Map.entry("--before-context", FlagArgType.NUMBER),
                    Map.entry("-C", FlagArgType.NUMBER), Map.entry("--context", FlagArgType.NUMBER),
                    Map.entry("-H", FlagArgType.NONE), Map.entry("-h", FlagArgType.NONE),
                    Map.entry("--heading", FlagArgType.NONE), Map.entry("--no-heading", FlagArgType.NONE),
                    Map.entry("-q", FlagArgType.NONE), Map.entry("--quiet", FlagArgType.NONE),
                    Map.entry("--column", FlagArgType.NONE),
                    Map.entry("-g", FlagArgType.STRING), Map.entry("--glob", FlagArgType.STRING),
                    Map.entry("-t", FlagArgType.STRING), Map.entry("--type", FlagArgType.STRING),
                    Map.entry("-T", FlagArgType.STRING), Map.entry("--type-not", FlagArgType.STRING),
                    Map.entry("--type-list", FlagArgType.NONE),
                    Map.entry("--hidden", FlagArgType.NONE), Map.entry("--no-ignore", FlagArgType.NONE),
                    Map.entry("-u", FlagArgType.NONE),
                    Map.entry("-m", FlagArgType.NUMBER), Map.entry("--max-count", FlagArgType.NUMBER),
                    Map.entry("-d", FlagArgType.NUMBER), Map.entry("--max-depth", FlagArgType.NUMBER),
                    Map.entry("-a", FlagArgType.NONE), Map.entry("--text", FlagArgType.NONE),
                    Map.entry("-z", FlagArgType.NONE),
                    Map.entry("-L", FlagArgType.NONE), Map.entry("--follow", FlagArgType.NONE),
                    Map.entry("--color", FlagArgType.STRING), Map.entry("--json", FlagArgType.NONE),
                    Map.entry("--stats", FlagArgType.NONE),
                    Map.entry("--help", FlagArgType.NONE), Map.entry("--version", FlagArgType.NONE),
                    Map.entry("--debug", FlagArgType.NONE),
                    Map.entry("--", FlagArgType.NONE)
            ))
    );

    // ========================================================================
    // PYRIGHT_READ_ONLY_COMMANDS
    // ========================================================================

    public static final Map<String, ExternalCommandConfig> PYRIGHT_READ_ONLY_COMMANDS = Map.of(
            "pyright", new ExternalCommandConfig(
                    Map.ofEntries(
                            Map.entry("--outputjson", FlagArgType.NONE),
                            Map.entry("--project", FlagArgType.STRING), Map.entry("-p", FlagArgType.STRING),
                            Map.entry("--pythonversion", FlagArgType.STRING),
                            Map.entry("--pythonplatform", FlagArgType.STRING),
                            Map.entry("--typeshedpath", FlagArgType.STRING),
                            Map.entry("--venvpath", FlagArgType.STRING),
                            Map.entry("--level", FlagArgType.STRING),
                            Map.entry("--stats", FlagArgType.NONE),
                            Map.entry("--verbose", FlagArgType.NONE),
                            Map.entry("--version", FlagArgType.NONE),
                            Map.entry("--dependencies", FlagArgType.NONE),
                            Map.entry("--warnings", FlagArgType.NONE)
                    ),
                    (rawCmd, args) -> args.stream().anyMatch(t -> "--watch".equals(t) || "-w".equals(t)),
                    false  // respectsDoubleDash = false
            )
    );

    // ========================================================================
    // EXTERNAL_READONLY_COMMANDS
    // ========================================================================

    public static final List<String> EXTERNAL_READONLY_COMMANDS = List.of(
            "docker ps",
            "docker images"
    );

    // ========================================================================
    // containsVulnerableUncPath — Original source: readOnlyCommandValidation.ts lines 1562-1638
    // ========================================================================

    /**
     * Check if a path or command contains a UNC path that could trigger network
     * requests (NTLM/Kerberos credential leakage, WebDAV attacks).
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → containsVulnerableUncPath()
     *
     * Detects:
     * 1. Backslash UNC paths: \\server\share
     * 2. Forward-slash UNC paths: //server/share (not URLs)
     * 3. Mixed separator paths: /\server, \\/server
     * 4. WebDAV SSL/port patterns: \\server@SSL@8443\
     * 5. DavWWWRoot marker
     * 6. IPv4 UNC: \\192.168.1.1\share
     * 7. IPv6 UNC: \\[2001:db8::1]\share
     */
    public static boolean containsVulnerableUncPath(String pathOrCommand) {
        // Original: readOnlyCommandValidation.ts lines 1562-1638

        // Only check on Windows
        if (!isWindows()) return false;

        // 1. Backslash UNC paths
        if (Pattern.compile("\\\\\\\\[^\\s\\\\/]+(?:@(?:\\d+|ssl))?(?:[\\\\/]|$|\\s)", Pattern.CASE_INSENSITIVE)
                .matcher(pathOrCommand).find()) {
            return true;
        }

        // 2. Forward-slash UNC paths (negative lookbehind for URLs)
        if (Pattern.compile("(?<!:)//[^\\s\\\\/]+(?:@(?:\\d+|ssl))?(?:[\\\\/]|$|\\s)", Pattern.CASE_INSENSITIVE)
                .matcher(pathOrCommand).find()) {
            return true;
        }

        // 3. Mixed separator: /\... (requires 2+ backslashes)
        if (Pattern.compile("/\\\\{2,}[^\\s\\\\/]").matcher(pathOrCommand).find()) {
            return true;
        }

        // 4. Reverse mixed separator: \\/...
        if (Pattern.compile("\\\\{2,}/[^\\s\\\\/]").matcher(pathOrCommand).find()) {
            return true;
        }

        // 5. WebDAV SSL/port patterns
        if (Pattern.compile("@SSL@\\d+", Pattern.CASE_INSENSITIVE).matcher(pathOrCommand).find()
                || Pattern.compile("@\\d+@SSL", Pattern.CASE_INSENSITIVE).matcher(pathOrCommand).find()) {
            return true;
        }

        // 6. DavWWWRoot marker
        if (Pattern.compile("DavWWWRoot", Pattern.CASE_INSENSITIVE).matcher(pathOrCommand).find()) {
            return true;
        }

        // 7. IPv4 UNC
        if (Pattern.compile("^\\\\\\\\(\\d{1,3}\\.){3}\\d{1,3}[\\\\/]").matcher(pathOrCommand).find()
                || Pattern.compile("^//(\\d{1,3}\\.){3}\\d{1,3}[\\\\/]").matcher(pathOrCommand).find()) {
            return true;
        }

        // 8. IPv6 UNC
        if (Pattern.compile("^\\\\\\\\\\[[\\da-fA-F:]+\\][\\\\/]").matcher(pathOrCommand).find()
                || Pattern.compile("^//\\[[\\da-fA-F:]+\\][\\\\/]").matcher(pathOrCommand).find()) {
            return true;
        }

        return false;
    }

    // ========================================================================
    // FLAG_PATTERN — Original source: readOnlyCommandValidation.ts line 1645
    // ========================================================================

    public static final Pattern FLAG_PATTERN = Pattern.compile("^-[a-zA-Z0-9_-]");

    // ========================================================================
    // validateFlagArgument — Original source: readOnlyCommandValidation.ts lines 1650-1670
    // ========================================================================

    /**
     * Validates flag arguments based on their expected type.
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → validateFlagArgument()
     */
    public static boolean validateFlagArgument(String value, FlagArgType argType) {
        return switch (argType) {
            case NONE -> false; // Should not have been called for 'none' type
            case NUMBER -> value != null && value.matches("^\\d+$");
            case STRING -> true; // Any string including empty is valid
            case CHAR -> value != null && value.length() == 1;
            case BRACE -> "{}".equals(value);
            case EOF -> "EOF".equals(value);
        };
    }

    // ========================================================================
    // validateFlags — Original source: readOnlyCommandValidation.ts lines 1684-1893
    // ========================================================================

    /**
     * Validates the flags/arguments portion of a tokenized command against a config.
     *
     * Original source: src/utils/shell/readOnlyCommandValidation.ts → validateFlags()
     *
     * This is the flag-walking loop extracted from BashTool's isCommandSafeViaFlagParsing.
     *
     * @param tokens     Pre-tokenized args
     * @param startIndex Where to start validating (after command tokens)
     * @param config     The safe flags config
     * @param options    Optional command name, raw command, xargs target commands
     * @return true if all flags are valid, false otherwise
     */
    public static boolean validateFlags(
            List<String> tokens,
            int startIndex,
            ExternalCommandConfig config,
            ValidateFlagsOptions options
    ) {
        // Original: readOnlyCommandValidation.ts lines 1684-1893
        int i = startIndex;

        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token == null || token.isEmpty()) { i++; continue; }

            // xargs special handling
            if (options != null && options.xargsTargetCommands != null
                    && "xargs".equals(options.commandName)
                    && (!token.startsWith("-") || "--".equals(token))) {
                if ("--".equals(token) && i + 1 < tokens.size()) {
                    i++;
                    token = tokens.get(i);
                }
                if (token != null && options.xargsTargetCommands.contains(token)) break;
                return false;
            }

            if ("--".equals(token)) {
                if (config.respectsDoubleDash()) {
                    i++;
                    break;
                }
                i++;
                continue;
            }

            if (token.startsWith("-") && token.length() > 1 && FLAG_PATTERN.matcher(token).find()) {
                boolean hasEquals = token.contains("=");
                String[] parts = token.split("=", 2);
                String flag = parts[0];
                String inlineValue = parts.length > 1 ? parts[1] : "";

                if (flag == null || flag.isEmpty()) return false;

                FlagArgType flagArgType = config.safeFlags().get(flag);

                if (flagArgType == null) {
                    // git numeric shorthand
                    if (options != null && "git".equals(options.commandName) && flag.matches("^-\\d+$")) {
                        i++; continue;
                    }

                    // Attached numeric argument (grep/rg)
                    if (options != null && ("grep".equals(options.commandName) || "rg".equals(options.commandName))
                            && flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                        String potentialFlag = flag.substring(0, 2);
                        String potentialValue = flag.substring(2);
                        FlagArgType pft = config.safeFlags().get(potentialFlag);
                        if (pft != null && potentialValue.matches("^\\d+$")
                                && (pft == FlagArgType.NUMBER || pft == FlagArgType.STRING)) {
                            if (validateFlagArgument(potentialValue, pft)) { i++; continue; }
                            else return false;
                        }
                    }

                    // Combined single-letter flags
                    if (flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                        for (int j = 1; j < flag.length(); j++) {
                            String singleFlag = "-" + flag.charAt(j);
                            FlagArgType ft = config.safeFlags().get(singleFlag);
                            if (ft == null) return false;
                            if (ft != FlagArgType.NONE) return false;
                        }
                        i++; continue;
                    }

                    return false; // Unknown flag
                }

                // Validate flag arguments
                if (flagArgType == FlagArgType.NONE) {
                    if (hasEquals) return false;
                    i++;
                } else {
                    String argValue;
                    if (hasEquals) {
                        argValue = inlineValue;
                        i++;
                    } else {
                        if (i + 1 >= tokens.size()) return false;
                        String nextToken = tokens.get(i + 1);
                        if (nextToken != null && nextToken.startsWith("-") && nextToken.length() > 1
                                && FLAG_PATTERN.matcher(nextToken).find()) {
                            return false;
                        }
                        argValue = nextToken != null ? nextToken : "";
                        i += 2;
                    }

                    // Defense-in-depth: reject string args starting with '-'
                    if (flagArgType == FlagArgType.STRING && argValue.startsWith("-")) {
                        if ("--sort".equals(flag) && options != null && "git".equals(options.commandName)
                                && argValue.matches("^-[a-zA-Z].*")) {
                            // git --sort allows - prefix for reverse
                        } else {
                            return false;
                        }
                    }

                    if (!validateFlagArgument(argValue, flagArgType)) return false;
                }
            } else {
                i++; // Non-flag positional arg
            }
        }

        return true;
    }

    // ========================================================================
    // Callbacks: git reflog, git tag, git branch, gh
    // ========================================================================

    /**
     * git reflog dangerous callback.
     * Original source: readOnlyCommandValidation.ts lines 283-303
     */
    private static boolean gitReflogIsDangerous(String rawCommand, List<String> args) {
        Set<String> dangerous = Set.of("expire", "delete", "exists");
        for (String token : args) {
            if (token == null || token.startsWith("-")) continue;
            if (dangerous.contains(token)) return true;
            return false;
        }
        return false;
    }

    /**
     * git tag dangerous callback — blocks tag creation via positional args.
     * Original source: readOnlyCommandValidation.ts lines 739-805
     */
    private static boolean gitTagIsDangerous(String rawCommand, List<String> args) {
        Set<String> flagsWithArgs = Set.of("--contains", "--no-contains", "--merged",
                "--no-merged", "--points-at", "--sort", "--format", "-n");
        boolean seenListFlag = false;
        boolean seenDashDash = false;
        int i = 0;
        while (i < args.size()) {
            String token = args.get(i);
            if (token == null) { i++; continue; }
            if ("--".equals(token) && !seenDashDash) { seenDashDash = true; i++; continue; }
            if (!seenDashDash && token.startsWith("-")) {
                if ("--list".equals(token) || "-l".equals(token)) seenListFlag = true;
                else if (token.length() > 2 && !token.contains("=") && token.indexOf('l') > 0) seenListFlag = true;
                if (token.contains("=")) i++;
                else if (flagsWithArgs.contains(token)) i += 2;
                else i++;
            } else {
                if (!seenListFlag) return true;
                i++;
            }
        }
        return false;
    }

    /**
     * git branch dangerous callback — blocks branch creation via positional args.
     * Original source: readOnlyCommandValidation.ts lines 851-922
     */
    private static boolean gitBranchIsDangerous(String rawCommand, List<String> args) {
        Set<String> flagsWithArgs = Set.of("--contains", "--no-contains", "--points-at", "--sort");
        Set<String> flagsWithOptionalArgs = Set.of("--merged", "--no-merged");
        boolean seenListFlag = false;
        boolean seenDashDash = false;
        String lastFlag = "";
        int i = 0;
        while (i < args.size()) {
            String token = args.get(i);
            if (token == null) { i++; continue; }
            if ("--".equals(token) && !seenDashDash) { seenDashDash = true; lastFlag = ""; i++; continue; }
            if (!seenDashDash && token.startsWith("-")) {
                if ("--list".equals(token) || "-l".equals(token)) seenListFlag = true;
                else if (token.length() > 2 && !token.contains("=") && token.indexOf('l') > 0) seenListFlag = true;
                if (token.contains("=")) { lastFlag = token.split("=")[0]; i++; }
                else if (flagsWithArgs.contains(token)) { lastFlag = token; i += 2; }
                else { lastFlag = token; i++; }
            } else {
                boolean lastFlagHasOptionalArg = flagsWithOptionalArgs.contains(lastFlag);
                if (!seenListFlag && !lastFlagHasOptionalArg) return true;
                i++;
            }
        }
        return false;
    }

    /**
     * gh dangerous callback — prevents network exfiltration via repo argument.
     * Original source: readOnlyCommandValidation.ts lines 944-982
     */
    public static boolean ghIsDangerousCallback(String rawCommand, List<String> args) {
        for (String token : args) {
            if (token == null || token.isEmpty()) continue;
            String value = token;
            if (token.startsWith("-")) {
                int eqIdx = token.indexOf('=');
                if (eqIdx == -1) continue;
                value = token.substring(eqIdx + 1);
                if (value.isEmpty()) continue;
            }
            if (!value.contains("/") && !value.contains("://") && !value.contains("@")) continue;
            if (value.contains("://")) return true;
            if (value.contains("@")) return true;
            long slashCount = value.chars().filter(c -> c == '/').count();
            if (slashCount >= 2) return true;
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
