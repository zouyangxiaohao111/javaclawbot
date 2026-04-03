package agent.tool.shell;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Atomic replication of Claude Code shell/bashProvider.ts.
 *
 * Original source: src/utils/shell/bashProvider.ts
 *
 * BashProvider handles:
 * - Building the full command string (snapshot sourcing, env setup, extglob disable, eval wrap, cwd tracking)
 * - Providing spawn args (["-c", "-l", cmd] or ["-c", cmd] if snapshot exists)
 * - Environment variable overrides (tmux socket isolation, sandbox tmpdir, session env vars)
 *
 * The command assembly is:
 *   source <snapshot_file> 2>/dev/null || true
 *   && <session_env_script>
 *   && { shopt -u extglob || setopt NO_EXTENDED_GLOB; } >/dev/null 2>&1 || true
 *   && eval <quoted_command>
 *   && pwd -P >| <cwd_temp_file>
 */
public final class BashProvider implements ShellProvider {

    private final String shellPath;
    private volatile String currentSandboxTmpDir;
    private volatile String lastSnapshotFilePath;

    private BashProvider(String shellPath) {
        this.shellPath = shellPath;
    }

    /**
     * Factory method to create a BashProvider.
     *
     * Original source: src/utils/shell/bashProvider.ts → createBashShellProvider()
     *
     * @param shellPath Absolute path to the bash/zsh binary
     * @return A new BashProvider instance
     */
    public static BashProvider create(String shellPath) {
        return new BashProvider(shellPath);
    }

    // ========================================================================
    // ShellProvider interface implementation
    // ========================================================================

    @Override
    public ShellType type() {
        return ShellType.BASH;
    }

    @Override
    public String shellPath() {
        return shellPath;
    }

    @Override
    public boolean detached() {
        // Original: bashProvider.ts → detached: true
        return true;
    }

    /**
     * Build the full command string including all shell-specific setup.
     *
     * Original source: src/utils/shell/bashProvider.ts → buildExecCommand()
     *
     * Assembly order:
     * 1. source <snapshot_file> 2>/dev/null || true
     * 2. <session_env_script>
     * 3. Disable extglob command
     * 4. eval <quoted_command>
     * 5. pwd -P >| <cwd_temp_file>
     *
     * @param command The raw user command
     * @param opts    Build options (id, sandboxTmpDir, useSandbox)
     * @return ExecCommandResult with the assembled command string and cwd temp file path
     */
    @Override
    public CompletableFuture<ExecCommandResult> buildExecCommand(String command, BuildExecCommandOpts opts) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Stub: snapshot creation — original uses createAndSaveSnapshot(shellPath)
                // In Java we skip snapshot creation since there's no shell environment snapshot mechanism.
                // The snapshot file is used to restore user's shell environment (aliases, functions, etc.)
                // Original: bashProvider.ts lines 63-68
                String snapshotFilePath = null; // Would be: createAndSaveSnapshot(shellPath)

                // Original: bashProvider.ts lines 85-103
                // Check if snapshot still exists — not applicable without snapshot
                lastSnapshotFilePath = snapshotFilePath;

                // Stash sandboxTmpDir for use in getEnvironmentOverrides
                // Original: bashProvider.ts line 106
                currentSandboxTmpDir = opts.useSandbox() ? opts.sandboxTmpDir() : null;

                // Determine temp directory paths
                // Original: bashProvider.ts lines 108-121
                String tmpdir = System.getProperty("java.io.tmpdir", "/tmp");
                String shellTmpdir = isWindows() ? windowsPathToPosixPath(tmpdir) : tmpdir;

                // shellCwdFilePath: POSIX path used inside the bash command (pwd -P >| ...)
                // cwdFilePath: native OS path used by Java for reading
                String shellCwdFilePath = opts.useSandbox()
                        ? posixJoin(opts.sandboxTmpDir(), "cwd-" + opts.id())
                        : posixJoin(shellTmpdir, "claude-" + opts.id() + "-cwd");
                String cwdFilePath = opts.useSandbox()
                        ? posixJoin(opts.sandboxTmpDir(), "cwd-" + opts.id())
                        : nativeJoin(tmpdir, "claude-" + opts.id() + "-cwd");

                // Defensive rewrite: normalize Windows CMD-style 2>nul to POSIX
                // Original: bashProvider.ts lines 124-127
                String normalizedCommand = rewriteWindowsNullRedirect(command);

                // Quote the command for eval
                // Original: bashProvider.ts line 129
                // Simplified quoting — original uses quoteShellCommand with stdin redirect detection
                String quotedCommand = quoteForEval(normalizedCommand);

                // Build command parts
                // Original: bashProvider.ts lines 156-187
                List<String> commandParts = new ArrayList<>();

                // 1. Source snapshot file (stub: skip — no snapshot in Java)
                // Original: bashProvider.ts lines 161-167
                if (snapshotFilePath != null) {
                    String finalPath = isWindows()
                            ? windowsPathToPosixPath(snapshotFilePath)
                            : snapshotFilePath;
                    commandParts.add("source " + quote(new String[]{finalPath}) + " 2>/dev/null || true");
                }

                // 2. Session environment script (stub: skip)
                // Original: bashProvider.ts lines 170-173
                // String sessionEnvScript = getSessionEnvironmentScript();
                // if (sessionEnvScript != null && !sessionEnvScript.isEmpty()) {
                //     commandParts.add(sessionEnvScript);
                // }

                // 3. Disable extended glob patterns for security
                // Original: bashProvider.ts lines 176-179
                String disableExtglobCmd = getDisableExtglobCommand(shellPath);
                if (disableExtglobCmd != null) {
                    commandParts.add(disableExtglobCmd);
                }

                // 4. eval-wrap the command
                // Original: bashProvider.ts line 184
                // When sourcing a file with aliases, they won't be expanded in the same
                // command line because the shell parses the entire line before execution.
                // Using eval after sourcing causes a second parsing pass where aliases are available.
                commandParts.add("eval " + quotedCommand);

                // 5. Track cwd via pwd -P
                // Original: bashProvider.ts lines 185-186
                commandParts.add("pwd -P >| " + quote(new String[]{shellCwdFilePath}));

                String commandString = String.join(" && ", commandParts);

                // 6. Apply CLAUDE_CODE_SHELL_PREFIX if set
                // Original: bashProvider.ts lines 189-195
                String shellPrefix = System.getenv("CLAUDE_CODE_SHELL_PREFIX");
                if (shellPrefix != null && !shellPrefix.isBlank()) {
                    commandString = formatShellPrefixCommand(shellPrefix, commandString);
                }

                return new ExecCommandResult(commandString, cwdFilePath);

            } catch (Exception e) {
                throw new RuntimeException("Failed to build bash command", e);
            }
        });
    }

    /**
     * Get spawn args for the shell process.
     *
     * Original source: src/utils/shell/bashProvider.ts → getSpawnArgs()
     *
     * Returns ["-c", "-l", commandString] for login shell,
     * or ["-c", commandString] when snapshot exists (skip login shell init).
     */
    @Override
    public List<String> getSpawnArgs(String commandString) {
        // Original: bashProvider.ts lines 200-206
        boolean skipLoginShell = lastSnapshotFilePath != null;
        if (skipLoginShell) {
            return List.of("-c", commandString);
        }
        return List.of("-c", "-l", commandString);
    }

    /**
     * Get environment variable overrides for this shell execution.
     *
     * Original source: src/utils/shell/bashProvider.ts → getEnvironmentOverrides()
     *
     * Handles:
     * - TMUX socket isolation (deferred until tmux is used)
     * - Sandbox tmpdir overrides (TMPDIR, CLAUDE_CODE_TMPDIR, TMPPREFIX)
     * - Session env vars set via /env
     */
    @Override
    public CompletableFuture<Map<String, String>> getEnvironmentOverrides(String command) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> env = new LinkedHashMap<>();

            // TMUX socket isolation (stub: no tmux in Java)
            // Original: bashProvider.ts lines 210-234
            // Original logic:
            //   if (process.env.USER_TYPE === 'ant' && (hasTmuxToolBeenUsed() || commandUsesTmux)) {
            //     await ensureSocketInitialized();
            //   }
            //   const claudeTmuxEnv = getClaudeTmuxEnv();
            //   if (claudeTmuxEnv) { env.TMUX = claudeTmuxEnv; }
            boolean commandUsesTmux = command.contains("tmux");
            if (commandUsesTmux) {
                // Stub: no tmux socket isolation in Java
            }

            // Sandbox tmpdir overrides
            // Original: bashProvider.ts lines 235-248
            if (currentSandboxTmpDir != null) {
                String posixTmpDir = currentSandboxTmpDir;
                if (isWindows()) {
                    posixTmpDir = windowsPathToPosixPath(posixTmpDir);
                }
                env.put("TMPDIR", posixTmpDir);
                env.put("CLAUDE_CODE_TMPDIR", posixTmpDir);
                // Zsh uses TMPPREFIX for heredoc temp files
                env.put("TMPPREFIX", posixJoin(posixTmpDir, "zsh"));
            }

            // Session env vars (stub: no session env vars in Java)
            // Original: bashProvider.ts lines 249-251
            // for (const [key, value] of getSessionEnvVars()) { env[key] = value; }

            return env;
        });
    }

    // ========================================================================
    // getDisableExtglobCommand — Atomic replication of bashProvider.ts
    // ========================================================================

    /**
     * Returns a shell command to disable extended glob patterns for security.
     *
     * Original source: src/utils/shell/bashProvider.ts → getDisableExtglobCommand()
     *
     * Extended globs (bash extglob, zsh EXTENDED_GLOB) can be exploited via
     * malicious filenames that expand after security validation.
     *
     * When CLAUDE_CODE_SHELL_PREFIX is set, includes BOTH bash and zsh commands
     * because the actual executing shell may differ from shellPath.
     * Redirects both stdout and stderr because zsh's command_not_found_handler
     * writes to stdout instead of stderr.
     *
     * @param shellPath The detected shell path
     * @return The disable command, or null if unknown shell
     */
    static String getDisableExtglobCommand(String shellPath) {
        // Original: bashProvider.ts lines 39-56

        // When CLAUDE_CODE_SHELL_PREFIX is set, include both bash and zsh commands
        if (System.getenv("CLAUDE_CODE_SHELL_PREFIX") != null) {
            return "{ shopt -u extglob || setopt NO_EXTENDED_GLOB; } >/dev/null 2>&1 || true";
        }

        // No shell prefix — use shell-specific command
        if (shellPath.contains("bash")) {
            return "shopt -u extglob 2>/dev/null || true";
        } else if (shellPath.contains("zsh")) {
            return "setopt NO_EXTENDED_GLOB 2>/dev/null || true";
        }

        // Unknown shell — do nothing
        return null;
    }

    // ========================================================================
    // Shell quoting utilities
    // ========================================================================

    /**
     * Quote an array of strings for shell use (single-quote each).
     *
     * Original source: src/utils/bash/shellQuote.ts → quote()
     *
     * Wraps each argument in single quotes, escaping embedded single quotes
     * with '\'' (end quote, escaped quote, start quote).
     */
    static String quote(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append("'");
            String arg = args[i];
            if (arg != null) {
                sb.append(arg.replace("'", "'\\''"));
            }
            sb.append("'");
        }
        return sb.toString();
    }

    /**
     * Quote a command for eval wrapping.
     *
     * Original source: src/utils/bash/shellQuoting.ts → quoteShellCommand()
     *
     * Wraps the command in single quotes for eval, with proper escaping.
     */
    private static String quoteForEval(String command) {
        // Simplified: wrap in single quotes for eval
        // Original uses more sophisticated quoting with heredoc handling
        return "'" + command.replace("'", "'\\''") + "'";
    }

    /**
     * Rewrite Windows CMD-style `2>nul` redirects to POSIX `2>/dev/null`.
     *
     * Original source: src/utils/bash/shellQuoting.ts → rewriteWindowsNullRedirect()
     *
     * The model sometimes emits Windows CMD-style redirects. In POSIX bash
     * (including Git Bash on Windows), this creates a literal file named "nul".
     */
    static String rewriteWindowsNullRedirect(String command) {
        if (command == null) return null;
        return command.replaceAll("2>nul\\b", "2>/dev/null")
                .replaceAll(">nul\\b", "> /dev/null");
    }

    // ========================================================================
    // Path utilities
    // ========================================================================

    /**
     * Convert Windows path to POSIX path (e.g., C:\Users → /c/Users).
     *
     * Original source: src/utils/windowsPaths.ts → windowsPathToPosixPath()
     */
    static String windowsPathToPosixPath(String windowsPath) {
        if (windowsPath == null) return null;
        if (windowsPath.length() >= 2 && windowsPath.charAt(1) == ':') {
            char drive = Character.toLowerCase(windowsPath.charAt(0));
            return "/" + drive + "/" + windowsPath.substring(3).replace('\\', '/');
        }
        return windowsPath.replace('\\', '/');
    }

    /**
     * POSIX path join.
     */
    static String posixJoin(String... parts) {
        return String.join("/", parts);
    }

    /**
     * Native path join.
     */
    static String nativeJoin(String... parts) {
        return String.join(File.separator, parts);
    }

    /**
     * Format shell prefix command.
     *
     * Original source: src/utils/bash/shellPrefix.ts → formatShellPrefixCommand()
     *
     * Wraps the command with the CLAUDE_CODE_SHELL_PREFIX.
     */
    static String formatShellPrefixCommand(String prefix, String command) {
        return prefix + " " + command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
