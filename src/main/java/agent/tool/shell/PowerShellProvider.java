package agent.tool.shell;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Atomic replication of Claude Code shell/powershellProvider.ts.
 *
 * Original source: src/utils/shell/powershellProvider.ts
 *
 * PowerShellProvider handles:
 * - Building PowerShell command strings with cwd tracking and exit-code capture
 * - Providing spawn args ([-NoProfile, -NonInteractive, -Command, cmd])
 * - Sandbox mode: base64-encoded command that survives shell-quoting layers
 * - Environment variable overrides (sandbox tmpdir, session env vars)
 */
public final class PowerShellProvider implements ShellProvider {

    private final String shellPath;
    private volatile String currentSandboxTmpDir;

    private PowerShellProvider(String shellPath) {
        this.shellPath = shellPath;
    }

    /**
     * Factory method.
     *
     * Original source: src/utils/shell/powershellProvider.ts → createPowerShellProvider()
     */
    public static PowerShellProvider create(String shellPath) {
        return new PowerShellProvider(shellPath);
    }

    // ========================================================================
    // ShellProvider interface implementation
    // ========================================================================

    @Override
    public ShellType type() {
        return ShellType.POWERSHELL;
    }

    @Override
    public String shellPath() {
        return shellPath;
    }

    @Override
    public boolean detached() {
        // Original: powershellProvider.ts → detached: false
        return false;
    }

    /**
     * Build the full command string for PowerShell execution.
     *
     * Original source: src/utils/shell/powershellProvider.ts → buildExecCommand()
     *
     * For sandbox mode: base64-encoded command that survives shell-quoting.
     * For non-sandbox: bare PS command with cwd tracking appended.
     *
     * Exit-code capture:
     *   $_ec = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($?) { 0 } else { 1 }
     *   (Get-Location).Path | Out-File -FilePath '<cwdFile>' -Encoding utf8 -NoNewline
     *   exit $_ec
     */
    @Override
    public CompletableFuture<ExecCommandResult> buildExecCommand(String command, BuildExecCommandOpts opts) {
        return CompletableFuture.supplyAsync(() -> {
            // Original: powershellProvider.ts lines 35-96

            // Stash sandboxTmpDir for getEnvironmentOverrides
            // Original: powershellProvider.ts line 44
            currentSandboxTmpDir = opts.useSandbox() ? opts.sandboxTmpDir() : null;

            // When sandboxed, tmpdir() is not writable — use sandboxTmpDir
            // Original: powershellProvider.ts lines 49-53
            String cwdFilePath;
            if (opts.useSandbox() && opts.sandboxTmpDir() != null) {
                cwdFilePath = opts.sandboxTmpDir() + "/claude-pwd-ps-" + opts.id();
            } else {
                cwdFilePath = System.getProperty("java.io.tmpdir") + "/claude-pwd-ps-" + opts.id();
            }

            // Escape single quotes in cwd file path for PS string
            // Original: powershellProvider.ts line 54
            String escapedCwdFilePath = cwdFilePath.replace("'", "''");

            // Exit-code capture: prefer $LASTEXITCODE when a native exe ran
            // Original: powershellProvider.ts lines 55-65
            String cwdTracking = "\n; $_ec = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($?) { 0 } else { 1 }" +
                    "\n; (Get-Location).Path | Out-File -FilePath '" + escapedCwdFilePath + "' -Encoding utf8 -NoNewline" +
                    "\n; exit $_ec";

            String psCommand = command + cwdTracking;

            // Sandbox mode: base64-encoded command wrapped in pwsh invocation
            // Original: powershellProvider.ts lines 68-94
            String commandString;
            if (opts.useSandbox()) {
                // Single-quote the shellPath so space-containing install paths survive
                // the inner `/bin/sh -c` word-split.
                String escapedShellPath = "'" + shellPath.replace("'", "'\\''") + "'";
                commandString = String.join(" ",
                        escapedShellPath,
                        "-NoProfile",
                        "-NonInteractive",
                        "-EncodedCommand",
                        encodePowerShellCommand(psCommand)
                );
            } else {
                commandString = psCommand;
            }

            return new ExecCommandResult(commandString, cwdFilePath);
        });
    }

    /**
     * Get spawn args for PowerShell.
     *
     * Original source: src/utils/shell/powershellProvider.ts → getSpawnArgs()
     */
    @Override
    public List<String> getSpawnArgs(String commandString) {
        return buildPowerShellArgs(commandString);
    }

    /**
     * Get environment variable overrides.
     *
     * Original source: src/utils/shell/powershellProvider.ts → getEnvironmentOverrides()
     */
    @Override
    public CompletableFuture<Map<String, String>> getEnvironmentOverrides(String command) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> env = new LinkedHashMap<>();

            // Session env vars FIRST (ordering matters: sandbox TMPDIR should win)
            // Original: powershellProvider.ts lines 112-113

            // Sandbox tmpdir overrides
            // Original: powershellProvider.ts lines 114-119
            if (currentSandboxTmpDir != null) {
                env.put("TMPDIR", currentSandboxTmpDir);
                env.put("CLAUDE_CODE_TMPDIR", currentSandboxTmpDir);
            }

            return env;
        });
    }

    // ========================================================================
    // Static utility methods
    // ========================================================================

    /**
     * Build PowerShell invocation flags + command.
     *
     * Original source: src/utils/shell/powershellProvider.ts → buildPowerShellArgs()
     *
     * Shared by the provider's getSpawnArgs and the hook spawn path in hooks.ts.
     *
     * @param cmd The command string to execute
     * @return List of args: [-NoProfile, -NonInteractive, -Command, cmd]
     */
    public static List<String> buildPowerShellArgs(String cmd) {
        // Original: powershellProvider.ts lines 11-13
        return List.of("-NoProfile", "-NonInteractive", "-Command", cmd);
    }

    /**
     * Base64-encode a string as UTF-16LE for PowerShell's -EncodedCommand.
     *
     * Original source: src/utils/shell/powershellProvider.ts → encodePowerShellCommand()
     *
     * Same encoding the parser uses (parser.ts toUtf16LeBase64). The output
     * is [A-Za-z0-9+/=] only — survives ANY shell-quoting layer, including
     * @anthropic-ai/sandbox-runtime's shellquote.quote() which would otherwise
     * corrupt !$? to \!$? when re-wrapping a single-quoted string in double
     * quotes.
     *
     * @param psCommand The PowerShell command to encode
     * @return Base64-encoded UTF-16LE string
     */
    static String encodePowerShellCommand(String psCommand) {
        // Original: powershellProvider.ts lines 23-25
        // Convert to UTF-16LE bytes, then base64 encode
        byte[] utf16leBytes = psCommand.getBytes(StandardCharsets.UTF_16LE);
        return Base64.getEncoder().encodeToString(utf16leBytes);
    }
}
