package agent.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell 执行工具：执行命令并返回输出。
 *
 * 主要行为：
 * - 支持 working_dir 覆盖
 * - 安全防护：
 *   - deny_patterns：命中即阻止
 *   - allow_patterns：存在时必须命中其中之一才允许
 *   - restrict_to_workspace：阻止路径穿越、阻止使用工作目录以外的绝对路径
 * - 超时：超过 timeout 秒返回超时错误
 * - 输出：
 *   - stdout 直接输出
 *   - stderr 非空时追加 "STDERR:\n..."
 *   - exit code 非 0 时追加 "\nExit code: N"
 *   - 最终输出超长（>10000）截断并追加提示
 */
public class ExecTool extends Tool {

    /** 输出最大长度（字符数） */
    private static final int MAX_OUTPUT_LEN = 10_000;

    /** 默认超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Windows 绝对路径提取：C:\...（遇到空格/引号/管道/重定向/分号等停止） */
    private static final Pattern ABS_WIN_PATH =
            Pattern.compile("[A-Za-z]:\\\\[^\\s\"'|><;]+");

    /**
     * POSIX 绝对路径提取：
     * - 只匹配 /absolute
     * - 只有在行首或空白/管道/重定向符后面出现才算路径，避免误抓
     */
    private static final Pattern ABS_POSIX_PATH =
            Pattern.compile("(?:^|[\\s|>])(/[^\\s\"'>]+)");

    /** 超时（秒） */
    private final int timeoutSeconds;

    /** 默认工作目录（可为空） */
    private final String workingDir;

    /** 禁止模式（原始字符串形式，匹配时使用 lower） */
    private final List<String> denyPatterns;

    /** 允许模式（原始字符串形式，匹配时使用 lower） */
    private final List<String> allowPatterns;

    /** 是否限制只能在工作目录范围内操作 */
    private final boolean restrictToWorkspace;

    /** 追加到 PATH 的内容（可为空字符串） */
    private final String pathAppend;

    /** 用于异步执行与并发读取输出 */
    private final ExecutorService pool;

    public ExecTool(
            int timeoutSeconds,
            String workingDir,
            List<String> denyPatterns,
            List<String> allowPatterns,
            boolean restrictToWorkspace,
            String pathAppend
    ) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
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
            Thread t = new Thread(r, "nanobot-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 便捷构造：参数使用默认值
     */
    public ExecTool() {
        this(DEFAULT_TIMEOUT_SECONDS, null, null, null, false, "");
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public String description() {
        return "Execute a shell command and return its output. Use with caution.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "string");
        command.put("description", "The shell command to execute");
        props.put("command", command);

        Map<String, Object> wd = new LinkedHashMap<>();
        wd.put("type", "string");
        wd.put("description", "Optional working directory for the command");
        props.put("working_dir", wd);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", props);
        out.put("required", List.of("command"));
        return out;
    }

    @Override
    public CompletableFuture<String> execute(Map<String, Object> params) {
        Map<String, Object> safe = (params == null) ? new LinkedHashMap<>() : params;

        String command = toStr(safe.get("command"));
        String workingDirOverride = toStr(safe.get("working_dir"));

        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture("Error: command is required");
        }

        String cwd = resolveCwd(workingDirOverride);

        String guardError = guardCommand(command, cwd);
        if (guardError != null) {
            return CompletableFuture.completedFuture(guardError);
        }

        return CompletableFuture.supplyAsync(() -> runCommand(command, cwd), pool);
    }

    /**
     * 解析工作目录：
     * - 优先使用参数 working_dir
     * - 再使用构造时的 working_dir
     * - 都没有则使用当前进程目录
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

        // 先杀子孙
        ph.descendants().forEach(h -> {
            try { h.destroy(); } catch (Exception ignored) {}
        });
        // 再杀自己
        try { ph.destroy(); } catch (Exception ignored) {}

        // 等一下，不行就强杀
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        ph.descendants().forEach(h -> {
            try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception ignored) {}
        });
        try { if (ph.isAlive()) ph.destroyForcibly(); } catch (Exception ignored) {}
    }

    /**
     * 执行命令并拼接输出：
     * - stdout：直接输出
     * - stderr：非空时输出 "STDERR:\n..."
     * - exit code：非 0 时追加 "\nExit code: N"
     * - 没有任何输出时返回 "(no output)"
     * - 输出过长时截断
     */
    private String runCommand(String command, String cwd) {
        boolean isWindows = isWindows();

        List<String> cmd = isWindows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(cwd));

        // 环境变量：复制当前环境，并按需要追加 PATH
        Map<String, String> env = pb.environment();
        if (!pathAppend.isBlank()) {
            String sep = File.pathSeparator;
            String old = env.getOrDefault("PATH", "");
            env.put("PATH", old + sep + pathAppend);
        }

        Process process = null;
        try {
            process = pb.start();

            // 并发读取 stdout / stderr，避免缓冲区阻塞
            Process p = process;
            Future<byte[]> outF = pool.submit(() -> readAllBytes(p.getInputStream()));
            Future<byte[]> errF = pool.submit(() -> readAllBytes(p.getErrorStream()));

            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            /*if (!finished) {
                // 超时：kill 并等待退出，尽量释放资源
                p.destroy();
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        p.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return "Error: Command timed out after " + timeoutSeconds + " seconds";
            }*/
            if (!finished) {
                destroyProcessTree(p);
                return "Error: Command timed out after " + timeoutSeconds + " seconds";
            }

            int exit = p.exitValue();

            byte[] outBytes = safeGetBytes(outF, Duration.ofSeconds(5));
            byte[] errBytes = safeGetBytes(errF, Duration.ofSeconds(5));

            String stdout = decodeUtf8(outBytes);
            String stderr = decodeUtf8(errBytes);

            List<String> outputParts = new ArrayList<>();

            if (stdout != null && !stdout.isEmpty()) {
                outputParts.add(stdout);
            }

            if (stderr != null) {
                String stderrTrim = stderr.strip();
                if (!stderrTrim.isEmpty()) {
                    outputParts.add("STDERR:\n" + stderr);
                }
            }

            if (exit != 0) {
                outputParts.add("\nExit code: " + exit);
            }

            String result = outputParts.isEmpty() ? "(no output)" : String.join("\n", outputParts);

            // 截断超长输出
            if (result.length() > MAX_OUTPUT_LEN) {
                int more = result.length() - MAX_OUTPUT_LEN;
                result = result.substring(0, MAX_OUTPUT_LEN)
                        + "\n... (truncated, " + more + " more chars)";
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
     * 安全防护：
     * - deny_patterns：命中直接阻止
     * - allow_patterns：非空时必须命中其一
     * - restrict_to_workspace：
     *   - 阻止出现 ..\ 或 ../
     *   - 若命令中包含绝对路径，必须位于 cwd 目录下（允许 cwd 自身）
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

        // 2) allow patterns（存在则必须命中其一）
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
            // 路径穿越快速拦截（按原命令字符串检查）
            if (cmd.contains("..\\") || cmd.contains("../")) {
                return "Error: Command blocked by safety guard (path traversal detected)";
            }

            // cwd 规范化（不要求实际存在）
            Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();

            // 提取命令中的绝对路径
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
                    // 允许 cwd 本身
                    if (p.equals(cwdPath)) {
                        continue;
                    }
                    // 允许 cwd 的子路径
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
     * 提取命令中的绝对路径：
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
            // group(1) 为捕获到的 /absolute
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
        // UTF-8 解码，遇到非法字节时使用替代字符
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

    private static List<String> defaultDenyPatterns() {
        List<String> list = new ArrayList<>();
        list.add("\\brm\\s+-[rf]{1,2}\\b");             // rm -r, rm -rf, rm -fr
        list.add("\\bdel\\s+/[fq]\\b");                 // del /f, del /q
        list.add("\\brmdir\\s+/s\\b");                  // rmdir /s
        list.add("(?:^|[;&|]\\s*)format\\b");           // format（独立命令）
        list.add("\\b(mkfs|diskpart)\\b");              // 磁盘操作
        list.add("\\bdd\\s+if=");                       // dd
        list.add(">\\s*/dev/sd");                       // 写入磁盘设备
        list.add("\\b(shutdown|reboot|poweroff)\\b");   // 关机/重启
        list.add(":\\(\\)\\s*\\{.*\\};\\s*:");          // fork bomb
        return list;
    }
}