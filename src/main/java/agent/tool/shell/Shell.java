package agent.tool.shell;

import agent.tool.shell.ShellProvider.ExecCommandResult;
import agent.tool.shell.ShellProvider.BuildExecCommandOpts;
import agent.tool.shell.ShellProvider.ShellType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(Shell.class.getName());

    // ========================================================================
    // Constants — aligned with Shell.ts
    // ========================================================================

    /**
     * Default timeout (30 minutes).
     * Original source: src/utils/Shell.ts → DEFAULT_TIMEOUT
     */
    private static final int DEFAULT_TIMEOUT_MS = 30 * 60 * 1000;

    /**
     * Stall watchdog: check interval (5 seconds).
     * Aligned with CC's LocalShellTask.tsx STALL_CHECK_INTERVAL_MS.
     */
    private static final int STALL_CHECK_INTERVAL_MS = 5_000;

    /**
     * Stall watchdog: threshold (45 seconds of no output growth).
     * Aligned with CC's LocalShellTask.tsx STALL_THRESHOLD_MS.
     */
    private static final int STALL_THRESHOLD_MS = 45_000;

    /**
     * Stall watchdog: bytes to read from tail of output.
     * Aligned with CC's LocalShellTask.tsx STALL_TAIL_BYTES.
     */
    private static final int STALL_TAIL_BYTES = 1024;


    /**
     * Patterns that suggest a command is blocked waiting for keyboard input.
     * Aligned with CC's LocalShellTask.tsx PROMPT_PATTERNS.
     */
    private static final java.util.regex.Pattern[] PROMPT_PATTERNS = {
            java.util.regex.Pattern.compile("(?i)\\(y/n\\)"),
            java.util.regex.Pattern.compile("(?i)\\[y/n\\]"),
            java.util.regex.Pattern.compile("(?i)\\(yes/no\\)"),
            java.util.regex.Pattern.compile("(?i)\\b(?:Do you|Would you|Shall I|Are you sure|Ready to)\\b.*\\?\\s*$"),
            java.util.regex.Pattern.compile("(?i)Press (?:any key|Enter)"),
            java.util.regex.Pattern.compile("(?i)Continue\\?"),
            java.util.regex.Pattern.compile("(?i)Overwrite\\?")
    };

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
    // BackgroundTask — background task state for run_in_background
    // ========================================================================

    /**
     * Background task state.
     *
     * Aligned with Claude Code's LocalShellTask.tsx task management.
     *
     * Output directory structure:
     *   <baseDir>/.bg-tasks/<taskId>/
     *     stdout.txt  — streaming stdout (available while task runs)
     *     stderr.txt  — streaming stderr
     *     exitcode    — appears when task completes (contains exit code number)
     */
    public static final class BackgroundTask {
        private final String taskId;
        private final Process process;
        private final Path outputDir;
        private final long startTimeMs;
        private volatile boolean completed;
        private volatile int exitCode;

        public BackgroundTask(String taskId, Process process, Path outputDir, long startTimeMs) {
            this.taskId = taskId;
            this.process = process;
            this.outputDir = outputDir;
            this.startTimeMs = startTimeMs;
            this.completed = false;
            this.exitCode = -1;
        }

        public String taskId() { return taskId; }
        public Process process() { return process; }
        public Path outputDir() { return outputDir; }
        public Path stdoutFile() { return outputDir.resolve("stdout.txt"); }
        public Path stderrFile() { return outputDir.resolve("stderr.txt"); }
        public long startTimeMs() { return startTimeMs; }
        public boolean completed() { return completed; }
        public int exitCode() { return exitCode; }
    }

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
     * Windows Git Bash 路径覆盖（从配置文件读取），优先级最高。
     */
    private static volatile String windowsBashPathOverride = null;

    /**
     * 设置 Windows Git Bash 路径覆盖。
     * 应在 AgentLoop 初始化时从 config 读取后调用。
     */
    public static void setWindowsBashPath(String path) {
        windowsBashPathOverride = (path != null && !path.isBlank()) ? path : null;
        if (windowsBashPathOverride != null && isWindows()) {
            Path shell = Paths.get(windowsBashPathOverride);
            if (!Files.exists(shell)) {
                log.error("windows GIT Bash 路径无效: {}" , path);
                throw new ShellException("Windows Git Bash 路径无效: " + path);
            }
        }

        // 清除缓存，让下次 getShellConfig 重新解析
        synchronized (shellConfigLock) {
            cachedShellConfig = null;
        }
    }

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

    /**
     * Base directory for background task output files.
     * Set from ExecTool's workingDir via setBackgroundOutputDir().
     */
    private static volatile String bgOutputBaseDir = null;

    /**
     * Set the base directory for background task output files.
     */
    public static void setBackgroundOutputDir(String dir) {
        bgOutputBaseDir = (dir != null && !dir.isBlank()) ? dir : null;
    }

    /**
     * Active background tasks registry.
     */
    private static final ConcurrentHashMap<String, BackgroundTask> backgroundTasks = new ConcurrentHashMap<>();

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
     * 1. Windows Bash path override from config (highest priority for Windows)
     * 2. CLAUDE_CODE_SHELL env var override (must contain "bash" or "zsh")
     * 3. $SHELL env var (bash or zsh)
     * 4. On Windows: auto-detect Git Bash from common installation paths
     * 5. which lookup for zsh/bash on PATH
     * 6. Fallback paths: /bin/bash, /bin/zsh, /usr/bin/bash, etc.
     *
     * @return Absolute path to the detected shell
     * @throws ShellException if no suitable shell is found
     */
    public static String findSuitableShell() {
        // 0. Windows Bash path override from config (highest priority)
        // 用户配置的路径必须有效，否则报错
        if (windowsBashPathOverride != null && !windowsBashPathOverride.isBlank() && isWindows()) {
            if (isExecutable(windowsBashPathOverride)) {
                logDebug("Using configured Windows Bash: " + windowsBashPathOverride);
                return windowsBashPathOverride;
            } else {
                // 用户配置了路径但无效，报错提示
                throw new ShellException(
                        "配置的 bash 路径无效: " + windowsBashPathOverride + "\n\n" +
                                "请检查配置文件 ~/.javaclawbot/config.json 中的 windowsBashPath 设置。\n\n" +
                                "当前配置值: \"" + windowsBashPathOverride + "\"\n\n" +
                                "解决方案:\n" +
                                "1. 确认路径正确，常见 Git Bash 路径:\n" +
                                "   - C:\\Program Files\\Git\\bin\\bash.exe\n" +
                                "   - C:\\Program Files (x86)\\Git\\bin\\bash.exe\n" +
                                "   - %LOCALAPPDATA%\\Programs\\Git\\bin\\bash.exe\n" +
                                "2. 或删除 windowsBashPath 配置，让系统自动检测\n" +
                                "3. 注意: /bin/bash 是 WSL 路径，在 Windows 上不可用\n\n" +
                                "配置示例:\n" +
                                "  \"agents\": {\n" +
                                "    \"defaults\": {\n" +
                                "      \"windowsBashPath\": \"C:\\\\Program Files\\\\Git\\\\bin\\\\bash.exe\"\n" +
                                "    }\n" +
                                "  }"
                );
            }
        }

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

        // 3. On Windows, auto-detect Git Bash from common installation paths
        // This prevents incorrectly detecting WSL's /bin/bash
        if (isWindows()) {
            String gitBashPath = detectGitBash();
            if (gitBashPath != null) {
                logDebug("Auto-detected Git Bash: " + gitBashPath);
                return gitBashPath;
            }
            // Windows 上未找到 Git Bash，直接报错提示用户配置
            throw new ShellException(
                    "未找到 Git Bash。请安装 Git for Windows 或在配置文件中设置 bash 路径。\n\n" +
                            "解决方案:\n" +
                            "1. 安装 Git for Windows: https://git-scm.com/download/win\n" +
                            "2. 或在配置文件 ~/.javaclawbot/config.json 中添加:\n" +
                            "   \"agents\": {\n" +
                            "     \"defaults\": {\n" +
                            "       \"windowsBashPath\": \"C:\\\\Program Files\\\\Git\\\\bin\\\\bash.exe\"\n" +
                            "     }\n" +
                            "   }\n\n" +
                            "常见 Git Bash 路径:\n" +
                            "  - C:\\Program Files\\Git\\bin\\bash.exe\n" +
                            "  - C:\\Program Files (x86)\\Git\\bin\\bash.exe\n" +
                            "  - %LOCALAPPDATA%\\Programs\\Git\\bin\\bash.exe"
            );
        }

        // 4. Try to locate shells using which
        // Original: Shell.ts lines 98-99
        String zshPath = which("zsh");
        String bashPath = which("bash");

        // 5. Populate shell paths from fallback locations
        // Original: Shell.ts lines 101-108
        String[] shellPaths = {"/bin", "/usr/bin", "/usr/local/bin", "/opt/homebrew/bin"};
        String[] shellOrder = preferBash ? new String[]{"bash", "zsh"} : new String[]{"zsh", "bash"};

        List<String> supportedShells = new ArrayList<>();
        for (String shell : shellOrder) {
            for (String path : shellPaths) {
                supportedShells.add(path + "/" + shell);
            }
        }

        // 6. Add discovered paths to the beginning of search list
        // Original: Shell.ts lines 111-118
        if (preferBash) {
            if (bashPath != null) supportedShells.add(0, bashPath);
            if (zshPath != null) supportedShells.add(zshPath);
        } else {
            if (zshPath != null) supportedShells.add(0, zshPath);
            if (bashPath != null) supportedShells.add(bashPath);
        }

        // 7. Always prioritize SHELL env variable if it's a supported shell type
        // Original: Shell.ts lines 120-123
        if (isEnvShellSupported && isExecutable(envShell)) {
            supportedShells.add(0, envShell);
        }

        // 8. Find first executable shell
        // Original: Shell.ts line 125
        String shellPath = null;
        for (String candidate : supportedShells) {
            if (candidate != null && isExecutable(candidate)) {
                shellPath = candidate;
                break;
            }
        }

        // 9. If no valid shell found, throw error
        // Original: Shell.ts lines 128-134
        if (shellPath == null) {
            throw new ShellException(
                    "No suitable shell found. requires a Posix shell environment. " +
                            "Please ensure you have a valid shell installed and the SHELL environment variable set."
            );
        }

        return shellPath;
    }

    /**
     * Auto-detect Git Bash on Windows from common installation paths.
     *
     * @return Path to bash.exe if found, null otherwise
     */
    private static String detectGitBash() {
        // Common Git Bash installation paths on Windows
        String[] commonPaths = {
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                "C:\\Git\\bin\\bash.exe",
                System.getenv("ProgramFiles") != null
                        ? System.getenv("ProgramFiles") + "\\Git\\bin\\bash.exe" : null,
                System.getenv("ProgramFiles(x86)") != null
                        ? System.getenv("ProgramFiles(x86)") + "\\Git\\bin\\bash.exe" : null,
                System.getenv("LOCALAPPDATA") != null
                        ? System.getenv("LOCALAPPDATA") + "\\Programs\\Git\\bin\\bash.exe" : null
        };

        for (String path : commonPaths) {
            if (path != null && isExecutable(path)) {
                return path;
            }
        }

        // Try to find bash.exe via where command (Windows equivalent of which)
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "bash.exe");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                // Take first line that contains "Git" (prefer Git Bash over WSL)
                for (String line : output.split("\n")) {
                    line = line.trim();
                    if (line.toLowerCase(Locale.ROOT).contains("git") && line.endsWith("bash.exe")) {
                        if (isExecutable(line)) {
                            return line;
                        }
                    }
                }
                // If no Git Bash found, take the first bash.exe if valid
                String first = output.split("\n")[0].trim();
                if (first.endsWith("bash.exe") && isExecutable(first)) {
                    return first;
                }
            }
        } catch (Exception ignored) {}

        return null;
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
                Path tempScriptFile = buildResult.tempScriptFile();

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
                    // Clean up temp cwd file
                    try {
                        Files.deleteIfExists(Paths.get(cwdFilePath));
                    } catch (Exception ignored) {}
                }

                // Clean up temp script file
                if (tempScriptFile != null) {
                    try {
                        Files.deleteIfExists(tempScriptFile);
                    } catch (Exception ignored) {}
                }

                return new ExecResult(stdout, stderr, exitCode, false, null);

            } catch (Exception e) {
                return new ExecResult("", "Shell exec error: " + e.getMessage(), 126, false, null);
            }
        }, pool);
    }

    // ========================================================================
    // execBackground — background task execution
    // ========================================================================

    /**
     * Execute a shell command in the background.
     *
     * Aligned with Claude Code's LocalShellTask.tsx → spawnShellTask().
     *
     * The process starts and this method returns immediately with a task ID.
     * Output is streamed to files in real-time.
     * Completion is detected by the presence of the exitcode file.
     *
     * @param command    The command to execute
     * @param shellType  The shell type
     * @param options    Execution options
     * @return CompletableFuture with ExecResult containing the backgroundTaskId
     */
    public static CompletableFuture<ExecResult> execBackground(
            String command,
            ShellType shellType,
            ExecOptions options
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShellProvider provider = resolveProvider(shellType);

                String id = String.format("%04x", (int) (Math.random() * 0x10000));

                BuildExecCommandOpts buildOpts = new BuildExecCommandOpts(id, null, false);
                ExecCommandResult buildResult = provider.buildExecCommand(command, buildOpts).join();
                String commandString = buildResult.commandString();

                String cwd = pwd();

                String binShell = provider.shellPath();
                List<String> shellArgs = provider.getSpawnArgs(commandString);
                Map<String, String> envOverrides = provider.getEnvironmentOverrides(command).join();

                List<String> fullCmd = new ArrayList<>();
                fullCmd.add(binShell);
                fullCmd.addAll(shellArgs);

                ProcessBuilder pb = new ProcessBuilder(fullCmd);
                pb.directory(new File(cwd));

                Map<String, String> env = pb.environment();
                env.put("SHELL", binShell);
                env.put("GIT_EDITOR", "true");
                env.put("CLAUDECODE", "1");
                env.putAll(envOverrides);

                if (!isWindows()) {
                    env.put("LC_ALL", "en_US.UTF-8");
                    env.put("LANG", "en_US.UTF-8");
                }

                // Create task output directory
                String taskId = "bg-" + UUID.randomUUID().toString().substring(0, 8);
                String baseDir = bgOutputBaseDir != null ? bgOutputBaseDir : System.getProperty("java.io.tmpdir");
                Path outputDir = Paths.get(baseDir, ".bg-tasks", taskId);
                Files.createDirectories(outputDir);

                Process process = pb.start();

                // Register task
                BackgroundTask task = new BackgroundTask(taskId, process, outputDir, System.currentTimeMillis());
                backgroundTasks.put(taskId, task);

                // Stream stdout to file
                pool.submit(() -> {
                    try (InputStream in = process.getInputStream();
                         OutputStream out = Files.newOutputStream(task.stdoutFile())) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            out.write(buf, 0, n);
                        }
                    } catch (IOException ignored) {}
                });

                // Stream stderr to file
                pool.submit(() -> {
                    try (InputStream in = process.getErrorStream();
                         OutputStream out = Files.newOutputStream(task.stderrFile())) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            out.write(buf, 0, n);
                        }
                    } catch (IOException ignored) {}
                });

                // Start stall watchdog
                Runnable cancelStallWatchdog = startStallWatchdog(task);

                // Monitor for completion
                pool.submit(() -> {
                    try {
                        int timeoutMs = (options != null && options.timeout() != null)
                                ? options.timeout() : DEFAULT_TIMEOUT_MS;
                        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

                        if (!finished) {
                            destroyProcessTree(process);
                            task.exitCode = -1;
                        } else {
                            task.exitCode = process.exitValue();
                        }

                        task.completed = true;
                        cancelStallWatchdog.run();

                        // Write exit code file (signals completion)
                        Files.writeString(outputDir.resolve("exitcode"), String.valueOf(task.exitCode));

                        // CWD tracking
                        if (options == null || !options.preventCwdChanges()) {
                            try {
                                String newCwd = new String(Files.readAllBytes(Paths.get(buildResult.cwdFilePath())), StandardCharsets.UTF_8).trim();
                                if (!newCwd.equals(cwd)) {
                                    setCwd(newCwd);
                                }
                            } catch (Exception ignored) {}
                            try { Files.deleteIfExists(Paths.get(buildResult.cwdFilePath())); } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        task.completed = true;
                        task.exitCode = -1;
                        cancelStallWatchdog.run();
                        try {
                            Files.writeString(outputDir.resolve("exitcode"), "-1");
                        } catch (Exception ignored) {}
                    }
                });

                return new ExecResult("", "", 0, false, taskId);

            } catch (Exception e) {
                return new ExecResult("", "Background exec error: " + e.getMessage(), 126, false, null);
            }
        }, pool);
    }

    // ========================================================================
    // Background task management
    // ========================================================================

    /**
     * Get a background task by ID.
     */
    public static BackgroundTask getBackgroundTask(String taskId) {
        return backgroundTasks.get(taskId);
    }

    /**
     * List all background task IDs.
     */
    public static List<String> listBackgroundTasks() {
        return new ArrayList<>(backgroundTasks.keySet());
    }

    /**
     * Clean up a completed background task (remove from registry, delete output files).
     */
    public static void cleanupTask(String taskId) {
        BackgroundTask task = backgroundTasks.remove(taskId);
        if (task == null) return;

        try {
            if (task.process.isAlive()) {
                destroyProcessTree(task.process);
            }
        } catch (Exception ignored) {}

        try {
            if (task.outputDir != null && Files.exists(task.outputDir)) {
                try (var stream = Files.walk(task.outputDir)) {
                    stream.sorted(Comparator.reverseOrder())
                          .map(Path::toFile)
                          .forEach(File::delete);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Clean up all background tasks on shutdown.
     */
    public static void cleanupAllBackgroundTasks() {
        for (String taskId : new ArrayList<>(backgroundTasks.keySet())) {
            cleanupTask(taskId);
        }
    }

    /**
     * Check if the tail of output looks like an interactive prompt.
     *
     * Aligned with CC's LocalShellTask.tsx looksLikePrompt().
     */
    static boolean looksLikePrompt(String tail) {
        String lastLine = tail.trim();
        int nl = lastLine.lastIndexOf('\n');
        if (nl >= 0) lastLine = lastLine.substring(nl + 1);
        for (java.util.regex.Pattern p : PROMPT_PATTERNS) {
            if (p.matcher(lastLine).find()) return true;
        }
        return false;
    }

    /**
     * Start a stall watchdog for a background task.
     *
     * Aligned with CC's LocalShellTask.tsx startStallWatchdog().
     *
     * Checks output file size every 5 seconds. If no growth for 45 seconds,
     * reads the last 1KB and checks if it looks like an interactive prompt.
     * If so, writes a stall-warning.txt to the task output directory.
     *
     * @param task The background task to monitor
     * @return A Runnable that cancels the watchdog
     */
    private static Runnable startStallWatchdog(BackgroundTask task) {
        final long[] lastSize = {0};
        final long[] lastGrowth = {System.currentTimeMillis()};
        final boolean[] cancelled = {false};

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stall-" + task.taskId());
            t.setDaemon(true);
            return t;
        });

        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = scheduler.scheduleAtFixedRate(() -> {
            if (cancelled[0] || task.completed()) return;

            try {
                long size = Files.exists(task.stdoutFile()) ? Files.size(task.stdoutFile()) : 0;
                if (size > lastSize[0]) {
                    lastSize[0] = size;
                    lastGrowth[0] = System.currentTimeMillis();
                    return;
                }

                if (System.currentTimeMillis() - lastGrowth[0] < STALL_THRESHOLD_MS) return;

                // Read tail of output
                if (size == 0) return;
                int tailLen = (int) Math.min(size, STALL_TAIL_BYTES);
                byte[] tail = new byte[tailLen];
                try (var raf = new java.io.RandomAccessFile(task.stdoutFile().toFile(), "r")) {
                    raf.seek(size - tailLen);
                    raf.readFully(tail);
                }
                String tailStr = new String(tail, StandardCharsets.UTF_8);

                if (!looksLikePrompt(tailStr)) {
                    // Not a prompt — keep watching, reset timer
                    lastGrowth[0] = System.currentTimeMillis();
                    return;
                }

                // Looks like an interactive prompt — write stall warning
                cancelled[0] = true;
                Files.writeString(task.outputDir().resolve("stall-warning.txt"),
                        "Command appears to be waiting for interactive input.\n\n" +
                        "Last output:\n" + tailStr.trim() + "\n\n" +
                        "The command is likely blocked on an interactive prompt. " +
                        "Kill this task and re-run with piped input (e.g., `echo y | command`) " +
                        "or a non-interactive flag if one exists."
                );
                if (futureHolder[0] != null) futureHolder[0].cancel(false);
                scheduler.shutdown();
            } catch (Exception ignored) {}
        }, STALL_CHECK_INTERVAL_MS, STALL_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        return () -> {
            cancelled[0] = true;
            if (futureHolder[0] != null) futureHolder[0].cancel(false);
            scheduler.shutdown();
        };
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

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void logDebug(String msg) {
        // Stub: original uses logForDebugging from src/utils/debug.ts
        log.debug(msg);
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
