package agent.tool.shell;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger log = Logger.getLogger(BashProvider.class.getName());

    private final String shellPath;
    private volatile String currentSandboxTmpDir;
    private volatile String lastSnapshotFilePath;

    /**
     * Async snapshot creation promise.
     * Kicked off in constructor, awaited in buildExecCommand().
     *
     * Aligned with CC's bashProvider.ts → snapshotPromise.
     */
    private final CompletableFuture<String> snapshotPromise;

    private BashProvider(String shellPath) {
        this.shellPath = shellPath;
        // Kick off snapshot creation asynchronously
        // Aligned with CC's createBashShellProvider() which calls createAndSaveSnapshot() eagerly
        this.snapshotPromise = CompletableFuture.supplyAsync(() -> {
            ShellSnapshot.cleanupOldSnapshots();
            return ShellSnapshot.createSnapshot(shellPath);
        });
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
                // Shell environment snapshot — captures aliases, functions, shell options, PATH
                // Original: bashProvider.ts lines 63-68
                String snapshotFilePath = null;
                try {
                    snapshotFilePath = snapshotPromise.join();
                } catch (Exception ignored) {}

                // TOCTOU safety: check if snapshot file still exists
                // Original: bashProvider.ts lines 93-101
                if (snapshotFilePath != null && !Files.exists(Paths.get(snapshotFilePath))) {
                    snapshotFilePath = null;
                }

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

                // Build command parts
                // Original: bashProvider.ts lines 156-187
                StringBuilder scriptContent = new StringBuilder();

                // 1. Source snapshot file (aliases, functions, shell options, PATH)
                // Original: bashProvider.ts lines 161-167
                if (snapshotFilePath != null) {
                    String finalPath = isWindows()
                            ? windowsPathToPosixPath(snapshotFilePath)
                            : snapshotFilePath;
                    scriptContent.append("source ").append(quote(new String[]{finalPath})).append(" 2>/dev/null || true\n");
                }

                // 2. Disable extended glob patterns for security
                // Original: bashProvider.ts lines 176-179
                String disableExtglobCmd = getDisableExtglobCommand(shellPath);
                if (disableExtglobCmd != null) {
                    scriptContent.append(disableExtglobCmd).append("\n");
                }

                // 3. The user command (no eval wrapping needed when using script file)
                scriptContent.append(normalizedCommand).append("\n");

                // 4. Track cwd via pwd -P
                // Original: bashProvider.ts lines 185-186
                scriptContent.append("pwd -P >| ").append(quote(new String[]{shellCwdFilePath})).append("\n");

                // 将脚本写入临时文件，避免 -c 参数中的引号转义问题
                Path tempScript = Files.createTempFile("bash-cmd-", ".sh");
                Files.writeString(tempScript, scriptContent.toString(), StandardCharsets.UTF_8);

                // 删除临时文件的钩子
                tempScript.toFile().deleteOnExit();

                // 返回脚本文件路径作为命令（使用 source 或直接执行）
                String scriptPath = isWindows() ? windowsPathToPosixPath(tempScript.toString()) : tempScript.toString();

                // 使用 source 执行脚本（而不是直接执行，这样可以保持环境变量）
                String commandString = "source " + quote(new String[]{scriptPath});

                // === DEBUG LOG ===
                log.log(Level.FINE, "=== BashProvider Debug ===");
                log.log(Level.FINE, "原始命令: {0}", command);
                log.log(Level.FINE, "脚本内容:\n{0}", scriptContent);
                log.log(Level.FINE, "命令字符串: {0}", commandString);

                return new ExecCommandResult(commandString, cwdFilePath, tempScript);

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
     * Uses $'...' syntax which:
     * - Allows proper escaping of special characters
     * - Preserves double quotes inside the command
     * - Works correctly with eval
     *
     * The backslash escaping ensures:
     * - Single quotes become \'
     * - Backslashes become \\
     * - Double quotes are preserved (no escaping needed inside $'...')
     */
    private static String quoteForEval(String command) {
        // Use $'...' syntax with proper backslash escaping
        // This allows double quotes to be preserved inside the command
        String escaped = command
                .replace("\\", "\\\\")  // Escape backslashes first
                .replace("'", "\\'");    // Escape single quotes
        return "$'" + escaped + "'";
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
