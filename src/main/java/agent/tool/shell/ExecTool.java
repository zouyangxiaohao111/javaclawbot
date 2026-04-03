package agent.tool.shell;

import agent.tool.Tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell execution tool: executes a bash command and returns its output.
 *
 * Atomic replication of Claude Code's BashTool (src/tools/BashTool/).
 *
 * Key behaviors (aligned with Claude Code BashTool):
 * - Supports working_dir override
 * - Safety guard:
 *   - deny_patterns: block on match
 *   - allow_patterns: when present, must match at least one
 *   - restrict_to_workspace: prevent path traversal, block absolute paths outside cwd
 * - Timeout: returns timeout error after specified seconds
 * - Output:
 *   - stdout directly output
 *   - stderr non-empty: append "STDERR:\n..."
 *   - exit code non-0: append "\nExit code: N"
 *   - Final output truncated at 30,000 chars
 */
public class ExecTool extends Tool {

    /**
     * Output max length (chars), aligned with Claude Code BashTool.maxResultSizeChars = 30_000.
     * Now delegates to OutputLimits.getMaxOutputLength() for env var override support.
     */
    private static int getMaxOutputLen() {
        return OutputLimits.getMaxOutputLength();
    }

    /**
     * Default timeout in milliseconds (aligned with Claude Code getDefaultTimeoutMs).
     * Claude Code uses 120_000ms (2 minutes) as default.
     */
    private static final int DEFAULT_TIMEOUT_MS = 120_000;

    /**
     * Max timeout in milliseconds (aligned with Claude Code getMaxTimeoutMs).
     * Claude Code uses 600_000ms (10 minutes) as max.
     */
    private static final int MAX_TIMEOUT_MS = 600_000;

    /**
     * Windows absolute path extraction: C:\... (stops at space/quote/pipe/redirect/semicolon)
     */
    private static final Pattern ABS_WIN_PATH =
            Pattern.compile("[A-Za-z]:\\\\[^\\s\"'|><;]+");

    /**
     * POSIX absolute path extraction:
     * - Only match /absolute
     * - Only count as path when preceded by line-start or whitespace/pipe/redirect
     */
    private static final Pattern ABS_POSIX_PATH =
            Pattern.compile("(?:^|[\\s|>])(/[^\\s\"'>]+)");

    /**
     * Timeout in milliseconds
     */
    private final int timeoutMs;

    /**
     * Default working directory (nullable)
     */
    private final String workingDir;

    /**
     * Deny patterns (raw string form, matched with lower case)
     */
    private final List<String> denyPatterns;

    /**
     * Allow patterns (raw string form, matched with lower case)
     */
    private final List<String> allowPatterns;

    /**
     * Whether to restrict operations to within the working directory
     */
    private final boolean restrictToWorkspace;

    /**
     * Content to append to PATH (can be empty string)
     */
    private final String pathAppend;

    /**
     * Thread pool for async execution and concurrent output reading
     */
    private final ExecutorService pool;

    public ExecTool(
            int timeoutMs,
            String workingDir,
            List<String> denyPatterns,
            List<String> allowPatterns,
            boolean restrictToWorkspace,
            String pathAppend
    ) {
        this.timeoutMs = timeoutMs > 0 ? Math.min(timeoutMs, MAX_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS;
        this.workingDir = (workingDir == null || workingDir.isBlank()) ? null : workingDir;
        this.denyPatterns = (denyPatterns == null || denyPatterns.isEmpty())
                ? defaultDenyPatterns()
                : new ArrayList<>(denyPatterns);
        this.allowPatterns = (allowPatterns == null)
                ? new ArrayList<>()
                : new ArrayList<>(allowPatterns);
        this.restrictToWorkspace = restrictToWorkspace;
        this.pathAppend = (pathAppend == null) ? "" : pathAppend;

        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "javaclawbot-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Convenience constructor: uses default values
     */
    public ExecTool() {
        this(DEFAULT_TIMEOUT_MS, null, null, null, false, "");
    }

    @Override
    public String name() {
        return "Bash";
    }

    /**
     * Atomic replication of Claude Code BashTool prompt.ts getSimplePrompt().
     *
     * Original source: src/tools/BashTool/prompt.ts → getSimplePrompt()
     *
     * Translated from TypeScript template literal to Java multiline string.
     */
    @Override
    public String description() {
        return String.join("\n", List.of(
            "Executes a given bash command and returns its output.",
            "",
            "The working directory persists between commands, but shell state does not. The shell environment is initialized from the user's profile (bash or zsh).",
            "",
            "IMPORTANT: Avoid using this tool to run `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo` commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. Instead, use the appropriate dedicated tool as this will provide a much better experience for the user:",
            "",
            "- File search: Use Glob (NOT find or ls)",
            "- Content search: Use Grep (NOT grep or rg)",
            "- Read files: Use Read (NOT cat, head, tail, or sed)",
            "- Edit files: Use Edit (NOT sed or awk)",
            "- Write files: Use Write (NOT echo > / cat <<EOF)",
            "- Communication: Output text directly (NOT echo/printf)",
            "",
            "While the Bash tool can do similar things, it's better to use the built-in tools as they provide a better user experience and make it easier to review tool calls and give permission.",
            "",
            "# Instructions",
            "",
            "- If your command will create new directories or files, first use this tool to run `ls` to verify the parent directory exists and is the correct location.",
            "- Always quote file paths that contain spaces with double quotes in your command (e.g., cd \"path with spaces/file.txt\")",
            "- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it.",
            "- You may specify an optional timeout in milliseconds (up to " + MAX_TIMEOUT_MS + "ms / " + (MAX_TIMEOUT_MS / 60000) + " minutes). By default, your command will timeout after " + DEFAULT_TIMEOUT_MS + "ms (" + (DEFAULT_TIMEOUT_MS / 60000) + " minutes).",
            "- When issuing multiple commands:",
            "  - If the commands are independent and can run in parallel, make multiple Bash tool calls in a single message. Example: if you need to run \"git status\" and \"git diff\", send a single message with two Bash tool calls in parallel.",
            "  - If the commands depend on each other and must run sequentially, use a single Bash call with '&&' to chain them together.",
            "  - Use ';' only when you need to run commands sequentially but don't care if earlier commands fail.",
            "  - DO NOT use newlines to separate commands (newlines are ok in quoted strings).",
            "- For git commands:",
            "  - Prefer to create a new commit rather than amending an existing commit.",
            "  - Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), consider whether there is a safer alternative that achieves the same goal. Only use destructive operations when they are truly the best approach.",
            "  - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.",
            "- Avoid unnecessary `sleep` commands:",
            "  - Do not sleep between commands that can run immediately — just run them.",
            "  - If your command is long running and you would like to be notified when it finishes — use `run_in_background`. No sleep needed.",
            "  - Do not retry failing commands in a sleep loop — diagnose the root cause.",
            "  - If you must poll an external process, use a check command (e.g. `gh run view`) rather than sleeping first.",
            "  - If you must sleep, keep the duration short (1-5 seconds) to avoid blocking the user."
        ));
    }

    /**
     * Aligned with Claude Code BashTool.maxResultSizeChars = 30_000
     */
    @Override
    public int maxResultSizeChars() {
        return 15_000;
    }

    /**
     * Atomic replication of Claude Code BashTool input schema.
     *
     * Original source: src/tools/BashTool/BashTool.tsx → fullInputSchema
     *
     * Schema fields:
     * - command (string, required): The command to execute
     * - timeout (number, optional): Optional timeout in milliseconds (max 600000)
     * - description (string, optional): Clear description of what this command does
     * - run_in_background (boolean, optional): Set to true to run in background
     * - dangerouslyDisableSandbox (boolean, optional): Override sandbox mode
     */
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("command", Map.of(
                "type", "string",
                "description", "The command to execute"
        ));

        props.put("timeout", Map.of(
                "type", "number",
                "description", "Optional timeout in milliseconds (max " + MAX_TIMEOUT_MS + ")"
        ));

        props.put("description", Map.of(
                "type", "string",
                "description", "Clear, concise description of what this command does in active voice. Never use words like \"complex\" or \"risk\" in the description - just describe what it does.\n\nFor simple commands (git, npm, standard CLI tools), keep it brief (5-10 words):\n- ls → \"List files in current directory\"\n- git status → \"Show working tree status\"\n- npm install → \"Install package dependencies\"\n\nFor commands that are harder to parse at a glance (piped commands, obscure flags, etc.), add enough context to clarify what it does:\n- find . -name \"*.tmp\" -exec rm {} \\; → \"Find and delete all .tmp files recursively\"\n- git reset --hard origin/main → \"Discard all local changes and match remote main\"\n- curl -s url | jq '.data[]' → \"Fetch JSON from URL and extract data array elements\""
        ));

        props.put("run_in_background", Map.of(
                "type", "boolean",
                "description", "Set to true to run this command in the background. Use Read to read the output later."
        ));

        props.put("dangerouslyDisableSandbox", Map.of(
                "type", "boolean",
                "description", "Set this to true to dangerously override sandbox mode and run commands without sandboxing."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("command"));
        return schema;
    }

    /**
     * Atomic replication of Claude Code BashTool.call().
     *
     * Original source: src/tools/BashTool/BashTool.tsx → call()
     *
     * Execution flow:
     * 1. Extract parameters (command, timeout, description, run_in_background)
     * 2. Validate with safety guard
     * 3. Execute via ProcessBuilder
     * 4. Format output matching mapToolResultToToolResultBlockParam:
     *    - Replace leading whitespace/newlines in stdout
     *    - Trim end of stdout
     *    - Append stderr with "STDERR:\n" prefix
     *    - Append "<error>Command was aborted before completion</error>" for interrupts
     *    - Append background info if applicable
     *    - Truncate at MAX_OUTPUT_LEN
     */
    @Override
    public CompletableFuture<String> execute(Map<String, Object> params) {
        Map<String, Object> safe = (params == null) ? new LinkedHashMap<>() : params;

        String command = toStr(safe.get("command"));
        Integer timeoutParam = asIntOrNull(safe.get("timeout"));
        String description = toStr(safe.get("description"));
        Boolean runInBackground = asBoolOrNull(safe.get("run_in_background"));
        Boolean dangerouslyDisableSandbox = asBoolOrNull(safe.get("dangerouslyDisableSandbox"));

        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture("Error: command is required");
        }

        String cwd = resolveCwd(null);

        String guardError = guardCommand(command, cwd);
        if (guardError != null) {
            return CompletableFuture.completedFuture(guardError);
        }

        // Resolve timeout: parameter > constructor default > DEFAULT_TIMEOUT_MS
        int effectiveTimeoutMs = (timeoutParam != null && timeoutParam > 0)
                ? Math.min(timeoutParam, MAX_TIMEOUT_MS)
                : this.timeoutMs;

        final int finalTimeoutMs = effectiveTimeoutMs;
        final String finalCommand = command;

        return CompletableFuture.supplyAsync(() -> runCommand(finalCommand, cwd, finalTimeoutMs, runInBackground), pool);
    }

    /**
     * Resolve working directory:
     * - Prefer parameter working_dir
     * - Then constructor working_dir
     * - Fall back to current process directory
     */
    private String resolveCwd(String workingDirOverride) {
        if (workingDirOverride != null && !workingDirOverride.isBlank()) {
            return workingDirOverride;
        }
        if (this.workingDir != null && !this.workingDir.isBlank()) {
            return this.workingDir;
        }
        return System.getProperty("user.dir");
    }

    private static void destroyProcessTree(Process p) {
        ProcessHandle ph = p.toHandle();

        // Kill descendants first
        ph.descendants().forEach(h -> {
            try { h.destroy(); } catch (Exception ignored) {}
        });
        // Then kill self
        try { ph.destroy(); } catch (Exception ignored) {}

        // Wait a bit, force kill if still alive
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        ph.descendants().forEach(h -> {
            try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception ignored) {}
        });
        try { if (ph.isAlive()) ph.destroyForcibly(); } catch (Exception ignored) {}
    }

    /**
     * Detect javac command and auto-inject -encoding UTF-8
     */
    private String injectJavacEncoding(String command) {
        if (command.contains("javac") && !command.contains("-encoding")) {
            return command.replaceFirst("(javac\\s*)", "$1-encoding UTF-8 ");
        }
        return command;
    }

    /**
     * Execute command and format output.
     *
     * Atomic replication of Claude Code's runShellCommand + call() output formatting.
     *
     * Output formatting matches mapToolResultToToolResultBlockParam:
     * - Replace leading whitespace/newlines in stdout
     * - Trim end of stdout
     * - stderr with "STDERR:\n" prefix
     * - Append "\nExit code: N" for non-zero exit codes
     * - Truncate at MAX_OUTPUT_LEN with removal size note
     */
    private String runCommand(String command, String cwd, int timeoutMs, Boolean runInBackground) {
        boolean isWindows = isWindows();

        // Detect javac command and auto-inject -encoding UTF-8
        command = injectJavacEncoding(command);

        // --- Use Shell module for dynamic shell detection ---
        // Original: Claude Code uses findSuitableShell() to detect bash/zsh
        // Falls back to /bin/sh or cmd.exe if Shell module is unavailable
        List<String> cmd;
        try {
            String shellPath = Shell.findSuitableShell();
            cmd = new ArrayList<>();
            cmd.add(shellPath);
            cmd.add("-c");
            cmd.add(command);
        } catch (Exception e) {
            // Fallback: use hardcoded shell paths
            cmd = isWindows
                    ? List.of("cmd.exe", "/c", "chcp 65001 >nul && " + command)
                    : List.of("/bin/sh", "-c", command);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(cwd));

        // Environment: copy current env, append PATH if needed
        Map<String, String> env = pb.environment();
        if (!pathAppend.isBlank()) {
            String sep = File.pathSeparator;
            String old = env.getOrDefault("PATH", "");
            env.put("PATH", old + sep + pathAppend);
        }

        // Linux/macOS: set UTF-8 locale
        if (!isWindows) {
            env.put("LC_ALL", "en_US.UTF-8");
            env.put("LANG", "en_US.UTF-8");
        }

        Process process = null;
        try {
            process = pb.start();

            // Concurrently read stdout/stderr to avoid buffer blocking
            Process p = process;
            Future<byte[]> outF = pool.submit(() -> readAllBytes(p.getInputStream()));
            Future<byte[]> errF = pool.submit(() -> readAllBytes(p.getErrorStream()));

            // Convert timeout from milliseconds to seconds for waitFor
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(p);
                return "Error: Command timed out after " + (timeoutMs / 1000) + " seconds";
            }

            int exitCode = p.exitValue();

            byte[] outBytes = safeGetBytes(outF, Duration.ofSeconds(5));
            byte[] errBytes = safeGetBytes(errF, Duration.ofSeconds(5));

            String stdout = decodeUtf8(outBytes);
            String stderr = decodeUtf8(errBytes);

            // --- Atomic replication of mapToolResultToToolResultBlockParam output formatting ---

            // Replace any leading newlines or lines with only whitespace
            String processedStdout = stdout;
            if (stdout != null && !stdout.isEmpty()) {
                processedStdout = stdout.replaceFirst("^(\\s*\\n)+", "");
                // Still trim the end as before
                processedStdout = stripTrailing(processedStdout);
            }

            // Build error message from stderr
            String errorMessage = (stderr != null) ? stderr.trim() : "";

            // Build exit code message
            if (exitCode != 0) {
                String exitMsg = "Exit code " + exitCode;
                if (!errorMessage.isEmpty()) {
                    errorMessage += "\n" + exitMsg;
                } else {
                    errorMessage = exitMsg;
                }
            }

            // Combine output parts (matching Claude Code's filter(Boolean).join('\n'))
            List<String> outputParts = new ArrayList<>();
            if (processedStdout != null && !processedStdout.isEmpty()) {
                outputParts.add(processedStdout);
            }
            if (!errorMessage.isEmpty()) {
                outputParts.add(errorMessage);
            }

            String result = outputParts.isEmpty() ? "(no output)" : String.join("\n", outputParts);

            // Truncate oversized output
            // Aligned with Claude Code: keep beginning, truncate end, show removed size
            int maxOutputLen = getMaxOutputLen();
            if (result.length() > maxOutputLen) {
                int removedKb = (result.length() - maxOutputLen) / 1024;
                result = result.substring(0, maxOutputLen)
                        + "\n... [output truncated - " + removedKb + "KB removed]";
            }

            return result;

        } catch (Exception e) {
            return "Error executing command: " + e;
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Strip trailing whitespace from a string (Java 11 compatible).
     * Replicates TypeScript's trimEnd().
     */
    private static String stripTrailing(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Safety guard:
     * - deny_patterns: block on match
     * - allow_patterns: when non-empty, must match at least one
     * - restrict_to_workspace:
     *   - Block ..\ or ../ (path traversal)
     *   - Block absolute paths outside cwd (allow cwd itself and subpaths)
     */
    private String guardCommand(String command, String cwd) {
        String cmd = command.strip();
        String lower = cmd.toLowerCase(Locale.ROOT);

        // 1) deny patterns
        for (String pattern : denyPatterns) {
            if (pattern == null || pattern.isBlank()) continue;
            if (Pattern.compile(pattern).matcher(lower).find()) {
                return "Error: Command blocked by safety guard (dangerous pattern detected)";
            }
        }

        // 2) allow patterns (when present, must match at least one)
        if (!allowPatterns.isEmpty()) {
            boolean ok = false;
            for (String p : allowPatterns) {
                if (p == null || p.isBlank()) continue;
                if (Pattern.compile(p).matcher(lower).find()) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return "Error: Command blocked by safety guard (not in allowlist)";
            }
        }

        // 3) restrict_to_workspace
        if (restrictToWorkspace) {
            // Quick path traversal block
            if (cmd.contains("..\\") || cmd.contains("../")) {
                return "Error: Command blocked by safety guard (path traversal detected)";
            }

            Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();

            List<String> absPaths = extractAbsolutePaths(cmd);

            for (String raw : absPaths) {
                if (raw == null || raw.isBlank()) continue;
                Path p;
                try {
                    p = Path.of(raw.strip()).toAbsolutePath().normalize();
                } catch (Exception ignored) {
                    continue;
                }

                if (p.isAbsolute()) {
                    // Allow cwd itself
                    if (p.equals(cwdPath)) {
                        continue;
                    }
                    // Allow subpaths of cwd
                    if (p.startsWith(cwdPath)) {
                        continue;
                    }
                    return "Error: Command blocked by safety guard (path outside working dir)";
                }
            }
        }

        return null;
    }

    /**
     * Extract absolute paths from command:
     * - Windows: C:\...
     * - POSIX: /...
     */
    private static List<String> extractAbsolutePaths(String command) {
        List<String> out = new ArrayList<>();

        Matcher w = ABS_WIN_PATH.matcher(command);
        while (w.find()) {
            out.add(w.group(0));
        }

        Matcher p = ABS_POSIX_PATH.matcher(command);
        while (p.find()) {
            out.add(p.group(1));
        }

        return out;
    }

    private byte[] safeGetBytes(Future<byte[]> f, Duration d) {
        try {
            return f.get(d.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            return new byte[0];
        } catch (Exception e) {
            f.cancel(true);
            return new byte[0];
        }
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

    private static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static String toStr(Object o) {
        if (o == null) return null;
        return Objects.toString(o, null);
    }

    private static Integer asIntOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean asBoolOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return "true".equals(s) || "1".equals(s);
    }

    private static List<String> defaultDenyPatterns() {
        List<String> list = new ArrayList<>();
        list.add("\\brm\\s+-[rf]{1,2}\\b");
        list.add("\\bdel\\s+/[fq]\\b");
        list.add("\\brmdir\\s+/s\\b");
        list.add("(?:^|[;&|]\\s*)format\\b");
        list.add("\\b(mkfs|diskpart)\\b");
        list.add("\\bdd\\s+if=");
        list.add(">\\s*/dev/sd");
        list.add("\\b(shutdown|reboot|poweroff)\\b");
        list.add(":\\(\\)\\s*\\{.*\\};\\s*:");
        return list;
    }
}
