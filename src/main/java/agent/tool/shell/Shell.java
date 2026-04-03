package agent.tool.shell;

import agent.tool.shell.ShellProvider.ExecCommandResult;
import agent.tool.shell.ShellProvider.BuildExecCommandOpts;
import agent.tool.shell.ShellProvider.ShellType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic replication of Claude Code Shell.ts.
 *
 * Original source: src/utils/Shell.ts
 *
 * Main entry point for shell execution in Claude Code.
 * Provides:
 * - findSuitableShell(): dynamic shell detection (bash/zsh preference, fallback paths)
 * - getShellConfig(): memoized shell configuration
 * - exec(): execute a command via the detected shell
 * - setCwd(): manage current working directory
 *
 * Shell detection priority:
 * 1. CLAUDE_CODE_SHELL env var override (must contain "bash" or "zsh")
 * 2. $SHELL env var (if it contains "bash" or "zsh")
 * 3. which lookup for zsh/bash on PATH
 * 4. Fallback paths: /bin/bash, /bin/zsh, /usr/bin/bash, /usr/bin/zsh, etc.
 */
public final class Shell {

    // ========================================================================
    // Constants — aligned with Shell.ts
    // ========================================================================

    /**
     * Default timeout (30 minutes).
     * Original source: src/utils/Shell.ts → DEFAULT_TIMEOUT
     */
    private static final int DEFAULT_TIMEOUT_MS = 30 * 60 * 1000;

    // ========================================================================
    // ShellConfig — holds the resolved ShellProvider
    // ========================================================================

    /**
     * Shell configuration.
     *
     * Original source: src/utils/Shell.ts → ShellConfig
     *
     * @param provider The resolved ShellProvider
     */
    public record ShellConfig(ShellProvider provider) {}

    // ========================================================================
    // ExecOptions — options for exec()
    // ========================================================================

    /**
     * Execution options.
     *
     * Original source: src/utils/Shell.ts → ExecOptions
     *
     * @param timeout           Timeout in milliseconds (default: 30 min)
     * @param preventCwdChanges Whether to prevent cwd tracking updates
     * @param shouldUseSandbox  Whether to enable sandbox mode
     * @param shouldAutoBackground Whether to auto-background long-running commands
     */
    public record ExecOptions(
            Integer timeout,
            boolean preventCwdChanges,
            boolean shouldUseSandbox,
            boolean shouldAutoBackground
    ) {
        public ExecOptions() {
            this(null, false, false, false);
        }
    }

    // ========================================================================
    // ExecResult — result of shell execution
    // ========================================================================

    /**
     * Result of shell execution.
     *
     * Original source: src/utils/ShellCommand.ts → ShellCommand result
     *
     * @param stdout    Standard output
     * @param stderr    Standard error
     * @param exitCode  Process exit code
     * @param timedOut  Whether the command timed out
     */
    public record ExecResult(
            String stdout,
            String stderr,
            int exitCode,
            boolean timedOut,
            String backgroundTaskId
    ) {}

    // ========================================================================
    // Memoized shell config
    // ========================================================================

    /**
     * Memoized shell config — only resolved once per session.
     *
     * Original source: src/utils/Shell.ts → getShellConfig (memoize)
     */
    private static volatile ShellConfig cachedShellConfig = null;
    private static final Object shellConfigLock = new Object();

    /**
     * Memoized PowerShell provider.
     *
     * Original source: src/utils/Shell.ts → getPsProvider (memoize)
     */
    private static volatile ShellProvider cachedPsProvider = null;
    private static final Object psProviderLock = new Object();

    /**
     * Current working directory state.
     *
     * Original source: src/utils/Shell.ts → setCwdState / pwd
     */
    private static final AtomicReference<String> cwdState = new AtomicReference<>(
            System.getProperty("user.dir")
    );

    // Thread pool for async execution
    private static final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "shell-exec");
        t.setDaemon(true);
        return t;
    });

    private Shell() {}

    // ========================================================================
    // findSuitableShell — Atomic replication of Shell.ts findSuitableShell()
    // ========================================================================

    /**
     * Determines the best available shell to use.
     *
     * Original source: src/utils/Shell.ts → findSuitableShell()
     *
     * Resolution order:
     * 1. CLAUDE_CODE_SHELL env var override (must contain "bash" or "zsh")
     * 2. $SHELL env var (bash or zsh)
     * 3. which lookup for zsh/bash on PATH
     * 4. Fallback paths: /bin/bash, /bin/zsh, /usr/bin/bash, etc.
     *
     * @return Absolute path to the detected shell
     * @throws ShellException if no suitable shell is found
     */
    public static String findSuitableShell() {
        // 1. Check for explicit shell override first
        // Original: Shell.ts lines 75-89
        String shellOverride = System.getenv("CLAUDE_CODE_SHELL");
        if (shellOverride != null && !shellOverride.isBlank()) {
            boolean isSupported = shellOverride.contains("bash") || shellOverride.contains("zsh");
            if (isSupported && isExecutable(shellOverride)) {
                logDebug("Using shell override: " + shellOverride);
                return shellOverride;
            } else {
                logDebug("CLAUDE_CODE_SHELL=\"" + shellOverride + "\" is not a valid bash/zsh path, falling back to detection");
            }
        }

        // 2. Check user's preferred shell from environment
        // Original: Shell.ts lines 92-96
        String envShell = System.getenv("SHELL");
        boolean isEnvShellSupported = envShell != null &&
                (envShell.contains("bash") || envShell.contains("zsh"));
        boolean preferBash = envShell != null && envShell.contains("bash");

        // 3. Try to locate shells using which
        // Original: Shell.ts lines 98-99
        String zshPath = which("zsh");
        String bashPath = which("bash");

        // 4. Populate shell paths from fallback locations
        // Original: Shell.ts lines 101-108
        String[] shellPaths = {"/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"};
        String[] shellOrder = preferBash ? new String[]{"bash", "zsh"} : new String[]{"zsh", "bash"};

        List<String> supportedShells = new ArrayList<>();
        for (String shell : shellOrder) {
            for (String path : shellPaths) {
                supportedShells.add(path + "/" + shell);
            }
        }

        // 5. Add discovered paths to the beginning of search list
        // Original: Shell.ts lines 111-118
        if (preferBash) {
            if (bashPath != null) supportedShells.add(0, bashPath);
            if (zshPath != null) supportedShells.add(zshPath);
        } else {
            if (zshPath != null) supportedShells.add(0, zshPath);
            if (bashPath != null) supportedShells.add(bashPath);
        }

        // 6. Always prioritize SHELL env variable if it's a supported shell type
        // Original: Shell.ts lines 120-123
        if (isEnvShellSupported && isExecutable(envShell)) {
            supportedShells.add(0, envShell);
        }

        // 7. Find first executable shell
        // Original: Shell.ts line 125
        String shellPath = null;
        for (String candidate : supportedShells) {
            if (candidate != null && isExecutable(candidate)) {
                shellPath = candidate;
                break;
            }
        }

        // 8. If no valid shell found, throw error
        // Original: Shell.ts lines 128-134
        if (shellPath == null) {
            throw new ShellException(
                    "No suitable shell found. Claude CLI requires a Posix shell environment. " +
                            "Please ensure you have a valid shell installed and the SHELL environment variable set."
            );
        }

        return shellPath;
    }

    // ========================================================================
    // getShellConfig — Atomic replication of Shell.ts getShellConfig()
    // ========================================================================

    /**
     * Get the memoized shell config. Resolves once per session.
     *
     * Original source: src/utils/Shell.ts → getShellConfig (memoize)
     */
    public static ShellConfig getShellConfig() {
        if (cachedShellConfig == null) {
            synchronized (shellConfigLock) {
                if (cachedShellConfig == null) {
                    String binShell = findSuitableShell();
                    ShellProvider provider = BashProvider.create(binShell);
                    cachedShellConfig = new ShellConfig(provider);
                }
            }
        }
        return cachedShellConfig;
    }

    // ========================================================================
    // getPsProvider — Atomic replication of Shell.ts getPsProvider()
    // ========================================================================

    /**
     * Get the memoized PowerShell provider.
     *
     * Original source: src/utils/Shell.ts → getPsProvider (memoize)
     */
    public static ShellProvider getPsProvider() {
        if (cachedPsProvider == null) {
            synchronized (psProviderLock) {
                if (cachedPsProvider == null) {
                    String psPath = PowerShellDetection.getCachedPowerShellPath();
                    if (psPath == null) {
                        throw new ShellException("PowerShell is not available");
                    }
                    cachedPsProvider = PowerShellProvider.create(psPath);
                }
            }
        }
        return cachedPsProvider;
    }

    // ========================================================================
    // resolveProvider — Atomic replication of Shell.ts resolveProvider
    // ========================================================================

    /**
     * Resolve the ShellProvider for the given shell type.
     *
     * Original source: src/utils/Shell.ts → resolveProvider
     */
    public static ShellProvider resolveProvider(ShellType shellType) {
        return switch (shellType) {
            case BASH -> getShellConfig().provider();
            case POWERSHELL -> getPsProvider();
        };
    }

    // ========================================================================
    // exec — Atomic replication of Shell.ts exec()
    // ========================================================================

    /**
     * Execute a shell command using the environment snapshot.
     * Creates a new shell process for each command execution.
     *
     * Original source: src/utils/Shell.ts → exec()
     *
     * Execution flow:
     * 1. Resolve provider for shell type
     * 2. Build command string (via provider)
     * 3. Spawn process with detected shell binary
     * 4. Handle timeout, capture output
     * 5. Track cwd changes via temp file
     * 6. Return ExecResult
     *
     * @param command    The command to execute
     * @param shellType  The shell type (bash or powershell)
     * @param options    Execution options
     * @return CompletableFuture with the execution result
     */
    public static CompletableFuture<ExecResult> exec(
            String command,
            ShellType shellType,
            ExecOptions options
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellProvider provider = resolveProvider(shellType);

                // Generate random ID for temp file naming
                // Original: Shell.ts line 199-201
                String id = String.format("%04x", (int) (Math.random() * 0x10000));

                // Build command string
                // Original: Shell.ts lines 209-214
                BuildExecCommandOpts buildOpts = new BuildExecCommandOpts(
                        id,
                        null,  // sandboxTmpDir — no sandbox in Java
                        false  // useSandbox
                );
                ExecCommandResult buildResult = provider.buildExecCommand(command, buildOpts).join();
                String commandString = buildResult.commandString();
                String cwdFilePath = buildResult.cwdFilePath();

                String cwd = pwd();

                // Check if aborted (no abort signal in Java — skip)
                // Original: Shell.ts lines 241-243

                String binShell = provider.shellPath();
                List<String> shellArgs = provider.getSpawnArgs(commandString);
                Map<String, String> envOverrides = provider.getEnvironmentOverrides(command).join();

                // Build spawn command: [binShell, ...shellArgs]
                // Original: Shell.ts line 316
                List<String> fullCmd = new ArrayList<>();
                fullCmd.add(binShell);
                fullCmd.addAll(shellArgs);

                ProcessBuilder pb = new ProcessBuilder(fullCmd);
                pb.directory(new File(cwd));

                // Set environment
                // Original: Shell.ts lines 317-328
                Map<String, String> env = pb.environment();
                env.put("SHELL", binShell);
                env.put("GIT_EDITOR", "true");
                env.put("CLAUDECODE", "1");
                env.putAll(envOverrides);

                // Set UTF-8 locale on non-Windows
                if (!isWindows()) {
                    env.put("LC_ALL", "en_US.UTF-8");
                    env.put("LANG", "en_US.UTF-8");
                }

                Process process = pb.start();

                // Concurrently read stdout/stderr
                Process p = process;
                Future<byte[]> outF = pool.submit(() -> readAllBytes(p.getInputStream()));
                Future<byte[]> errF = pool.submit(() -> readAllBytes(p.getErrorStream()));

                // Wait with timeout
                int timeoutMs = (options != null && options.timeout() != null)
                        ? options.timeout() : DEFAULT_TIMEOUT_MS;
                boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

                if (!finished) {
                    destroyProcessTree(p);
                    return new ExecResult("", "", -1, true, null);
                }

                int exitCode = p.exitValue();
                byte[] outBytes = safeGetBytes(outF);
                byte[] errBytes = safeGetBytes(errF);

                String stdout = new String(outBytes, StandardCharsets.UTF_8);
                String stderr = new String(errBytes, StandardCharsets.UTF_8);

                // Track cwd changes
                // Original: Shell.ts lines 395-413
                if (!options.preventCwdChanges()) {
                    try {
                        String newCwd = new String(Files.readAllBytes(Paths.get(cwdFilePath)), StandardCharsets.UTF_8).trim();
                        if (!newCwd.equals(cwd)) {
                            setCwd(newCwd);
                        }
                    } catch (Exception ignored) {
                        // File may not exist if command failed before pwd ran
                    }
                    // Clean up temp file
                    try {
                        Files.deleteIfExists(Paths.get(cwdFilePath));
                    } catch (Exception ignored) {}
                }

                return new ExecResult(stdout, stderr, exitCode, false, null);

            } catch (Exception e) {
                return new ExecResult("", "Shell exec error: " + e.getMessage(), 126, false, null);
            }
        }, pool);
    }

    // ========================================================================
    // setCwd / pwd — Atomic replication of Shell.ts setCwd() / pwd()
    // ========================================================================

    /**
     * Set the current working directory.
     *
     * Original source: src/utils/Shell.ts → setCwd()
     */
    public static void setCwd(String path) {
        String resolved = Paths.get(path).toAbsolutePath().normalize().toString();
        try {
            resolved = Paths.get(resolved).toRealPath().toString();
        } catch (IOException e) {
            if (e instanceof NoSuchFileException) {
                throw new ShellException("Path \"" + resolved + "\" does not exist");
            }
            // Keep resolved as-is for other errors
        }
        cwdState.set(resolved);
    }

    /**
     * Get the current working directory.
     *
     * Original source: src/utils/cwd.ts → pwd()
     */
    public static String pwd() {
        return cwdState.get();
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Check if a path is executable.
     *
     * Original source: src/utils/Shell.ts → isExecutable()
     */
    static boolean isExecutable(String shellPath) {
        try {
            // Primary check: X_OK
            if (Files.isExecutable(Paths.get(shellPath))) {
                return true;
            }
        } catch (Exception ignored) {}

        // Fallback: try to execute the shell with --version
        // Original: Shell.ts lines 56-67
        try {
            ProcessBuilder pb = new ProcessBuilder(shellPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(1, TimeUnit.SECONDS);
            if (finished) {
                p.getInputStream().close();
                return true;
            }
            p.destroyForcibly();
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Simple which(1) implementation — find executable on PATH.
     *
     * Original source: src/utils/which.ts → which()
     */
    static String which(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String separator = isWindows() ? ";" : ":";
        String[] paths = pathEnv.split(separator);

        for (String dir : paths) {
            if (dir.isEmpty()) continue;
            Path candidate = Paths.get(dir, command);
            if (Files.isExecutable(candidate)) {
                try {
                    return candidate.toRealPath().toString();
                } catch (IOException e) {
                    return candidate.toString();
                }
            }
            // On Windows, also check with .exe extension
            if (isWindows()) {
                candidate = Paths.get(dir, command + ".exe");
                if (Files.isExecutable(candidate)) {
                    try {
                        return candidate.toRealPath().toString();
                    } catch (IOException e) {
                        return candidate.toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Destroy process tree — kill descendants first, then main process.
     */
    private static void destroyProcessTree(Process p) {
        ProcessHandle ph = p.toHandle();
        ph.descendants().forEach(h -> {
            try { h.destroy(); } catch (Exception ignored) {}
        });
        try { ph.destroy(); } catch (Exception ignored) {}

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        ph.descendants().forEach(h -> {
            try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception ignored) {}
        });
        try { if (ph.isAlive()) ph.destroyForcibly(); } catch (Exception ignored) {}
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] safeGetBytes(Future<byte[]> f) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return new byte[0];
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void logDebug(String msg) {
        // Stub: original uses logForDebugging from src/utils/debug.ts
        System.getLogger("shell").log(System.Logger.Level.DEBUG, msg);
    }

    // ========================================================================
    // ShellException
    // ========================================================================

    /**
     * Exception thrown when no suitable shell is found.
     */
    public static final class ShellException extends RuntimeException {
        public ShellException(String message) {
            super(message);
        }
    }
}
