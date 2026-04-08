package agent.tool.shell;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Atomic replication of Claude Code shell/shellProvider.ts.
 *
 * Original source: src/utils/shell/shellProvider.ts
 *
 * Defines the ShellType enum and ShellProvider interface.
 * ShellProvider is the abstraction layer for different shell backends
 * (bash, PowerShell). Each provider knows how to:
 * - Build the full command string (snapshot sourcing, env setup, extglob disable, eval wrap, cwd tracking)
 * - Provide spawn args for the process
 * - Provide environment variable overrides
 */
public interface ShellProvider {

    // ========================================================================
    // ShellType — Atomic replication of shellProvider.ts SHELL_TYPES
    // ========================================================================

    /**
     * Shell type enum.
     *
     * Original source: src/utils/shell/shellProvider.ts → SHELL_TYPES
     */
    enum ShellType {
        BASH,
        POWERSHELL
    }

    // ========================================================================
    // ExecCommandResult — result of buildExecCommand()
    // ========================================================================

    /**
     * Result of buildExecCommand().
     *
     * Original source: src/utils/shell/bashProvider.ts → buildExecCommand() return type
     *
     * @param commandString The full assembled command string to pass to the shell
     * @param cwdFilePath   The temp file path where pwd output is written for cwd tracking
     * @param tempScriptFile Optional temp script file to clean up after execution
     */
    record ExecCommandResult(String commandString, String cwdFilePath, Path tempScriptFile) {
        public ExecCommandResult(String commandString, String cwdFilePath) {
            this(commandString, cwdFilePath, null);
        }
    }

    // ========================================================================
    // BuildExecCommandOpts — options for buildExecCommand()
    // ========================================================================

    /**
     * Options for buildExecCommand().
     *
     * Original source: src/utils/shell/bashProvider.ts → buildExecCommand() opts parameter
     *
     * @param id            Random hex ID for temp file naming
     * @param sandboxTmpDir Sandbox temp directory (null if not sandboxed)
     * @param useSandbox    Whether sandbox mode is enabled
     */
    record BuildExecCommandOpts(
            String id,
            String sandboxTmpDir,
            boolean useSandbox
    ) {
        public BuildExecCommandOpts(String id, String sandboxTmpDir, boolean useSandbox) {
            this.id = id;
            this.sandboxTmpDir = sandboxTmpDir;
            this.useSandbox = useSandbox;
        }
    }

    // ========================================================================
    // ShellProvider interface methods
    // ========================================================================

    /**
     * Shell type (bash or powershell).
     *
     * Original source: src/utils/shell/shellProvider.ts → ShellProvider.type
     */
    ShellType type();

    /**
     * Absolute path to the shell binary.
     *
     * Original source: src/utils/shell/shellProvider.ts → ShellProvider.shellPath
     */
    String shellPath();

    /**
     * Whether to spawn the process detached.
     * Bash: true, PowerShell: false.
     *
     * Original source: src/utils/shell/shellProvider.ts → ShellProvider.detached
     */
    boolean detached();

    /**
     * Build the full command string including all shell-specific setup.
     * For bash: source snapshot, session env, disable extglob, eval-wrap, pwd tracking.
     * For PowerShell: cwd tracking, exit-code capture, optional -EncodedCommand.
     *
     * Original source: src/utils/shell/shellProvider.ts → ShellProvider.buildExecCommand
     */
    CompletableFuture<ExecCommandResult> buildExecCommand(String command, BuildExecCommandOpts opts);

    /**
     * Shell args for spawn (e.g., ["-c", "-l", cmd] for bash).
     *
     * Original source: src/utils/shell/shellProvider.ts → ShellProvider.getSpawnArgs
     */
    List<String> getSpawnArgs(String commandString);

    /**
     * Extra env vars for this shell type.
     * May perform async initialization (e.g., tmux socket setup for bash).
     *
     * Original source: src/utils/shell/shellProvider.ts → ShellProvider.getEnvironmentOverrides
     */
    CompletableFuture<Map<String, String>> getEnvironmentOverrides(String command);

    /**
     * Default hook shell type.
     *
     * Original source: src/utils/shell/shellProvider.ts → DEFAULT_HOOK_SHELL
     */
    String DEFAULT_HOOK_SHELL = "bash";
}
