package agent.tool.shell;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Shell environment snapshot creation.
 *
 * Aligned with Claude Code's ShellSnapshot.ts.
 *
 * Captures user's shell environment (aliases, functions, shell options, PATH)
 * into a snapshot file that is sourced before each command execution.
 *
 * Original source: src/utils/bash/ShellSnapshot.ts
 *
 * Snapshot lifecycle:
 * 1. Created asynchronously when BashProvider is initialized
 * 2. Stored at ~/.claude/shell-snapshots/snapshot-{type}-{ts}-{id}.sh
 * 3. Sourced before each command via BashProvider.buildExecCommand()
 * 4. Cleaned up on shutdown or after 24 hours
 */
@Slf4j
public final class ShellSnapshot {


    private ShellSnapshot() {}

    /**
     * Create a shell environment snapshot.
     *
     * Aligned with ShellSnapshot.ts → createAndSaveSnapshot().
     *
     * Runs a login shell child process that sources the user's config
     * and captures the environment into a snapshot file.
     *
     * @param shellPath Path to the shell binary
     * @return Path to the created snapshot file, or null on failure
     */
    public static String createSnapshot(String shellPath) {
        try {
            Path snapshotDir = getSnapshotDir();
            Files.createDirectories(snapshotDir);

            String shellType = detectShellType(shellPath);
            String id = String.format("%04x", (int) (Math.random() * 0x10000));
            long timestamp = System.currentTimeMillis() / 1000;

            Path snapshotFile = snapshotDir.resolve(
                    String.format("snapshot-%s-%d-%s.sh", shellType, timestamp, id));

            // Convert Windows path to POSIX format for shell scripts
            String outputFile = toPosixPath(snapshotFile.toString());

            String script = getSnapshotScript(shellPath, outputFile);

            // 将脚本写入临时文件，避免 -c 参数中的引号转义问题
            Path tempScript = Files.createTempFile("snapshot-script-", ".sh");
            Files.writeString(tempScript, script, StandardCharsets.UTF_8);

            try {
                // Run as login shell with 10-second timeout
                // Original: ShellSnapshot.ts lines 456-471
                // 将临时脚本路径转换为 POSIX 格式（Windows 需要）
                String tempScriptPath = isWindows() ? toPosixPath(tempScript.toString()) : tempScript.toString();
                ProcessBuilder pb = new ProcessBuilder(shellPath, "-l", tempScriptPath);
                pb.redirectErrorStream(true);

                Process process = pb.start();
                boolean finished = process.waitFor(60, TimeUnit.SECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    log.warn("Snapshot creation timed out");
                    return null;
                }

                if (process.exitValue() != 0) {
                    String error = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("Snapshot creation failed: {}" , error);
                    return null;
                }

                // Verify snapshot file was created and is non-empty
                if (!Files.exists(snapshotFile) || Files.size(snapshotFile) == 0) {
                    log.warn("Snapshot file was not created or is empty");
                    return null;
                }

                log.debug("Created shell snapshot: {}", snapshotFile);
                return snapshotFile.toString();

            } finally {
                // 清理临时脚本文件
                Files.deleteIfExists(tempScript);
            }

        } catch (Exception e) {
            log.warn("创建shell快照失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Convert Windows path to POSIX format for shell scripts.
     *
     * On Windows, converts C:\Users\... to /c/Users/...
     * On other platforms, returns the path unchanged.
     */
    private static String toPosixPath(String path) {
        if (!isWindows()) {
            return path;
        }

        // Handle Windows drive letter: C:\... -> /c/...
        if (path.length() >= 2 && path.charAt(1) == ':') {
            char drive = Character.toLowerCase(path.charAt(0));
            String rest = path.substring(2).replace('\\', '/');
            return "/" + drive + rest;
        }

        // Already a path with forward slashes or UNC path
        return path.replace('\\', '/');
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /**
     * Generate the snapshot creation script.
     *
     * Aligned with ShellSnapshot.ts → getSnapshotScript().
     *
     * The script:
     * 1. Sources user's config file (.bashrc/.zshrc)
     * 2. Initializes the snapshot file
     * 3. Captures functions (base64-encoded for bash)
     * 4. Captures shell options (shopt -p / setopt)
     * 5. Enables alias expansion
     * 6. Captures aliases
     * 7. Exports PATH
     *
     * @param shellPath Path to the shell binary
     * @param outputFile Path where the snapshot file will be written
     * @return The shell script to execute
     */
    private static String getSnapshotScript(String shellPath, String outputFile) {
        String shellType = detectShellType(shellPath);
        String configFile = getUserConfigFile(shellPath);

        StringBuilder script = new StringBuilder();

        // Source user's config file if it exists
        // Original: ShellSnapshot.ts lines 345-360
        if (configFile != null) {
            // Convert to POSIX path on Windows
            String posixConfig = toPosixPath(configFile);
            script.append("source ").append(quote(posixConfig)).append(" < /dev/null 2>/dev/null || true\n");
        }

        // Initialize snapshot file
        script.append("echo '# Shell environment snapshot' >| ").append(quote(outputFile)).append("\n");

        // Clear aliases to avoid conflicts
        // Original: ShellSnapshot.ts lines 237-241
        script.append("echo 'unalias -a 2>/dev/null || true' >> ").append(quote(outputFile)).append("\n");

        // Capture shell-specific content
        // Original: ShellSnapshot.ts → getUserSnapshotContent()
        if ("zsh".equals(shellType)) {
            appendZshCapture(script, outputFile);
        } else {
            appendBashCapture(script, outputFile);
        }

        // Enable alias expansion
        // Original: ShellSnapshot.ts lines 252-254
        if (!"zsh".equals(shellType)) {
            script.append("echo 'shopt -s expand_aliases' >> ").append(quote(outputFile)).append("\n");
        }

        // Capture aliases (filter out winpty aliases on Windows, cap at 1000)
        // Original: ShellSnapshot.ts lines 256-263
        script.append("alias 2>/dev/null | grep -v 'winpty' | sed 's/^alias //g' | sed 's/^/alias -- /' | head -n 1000 >> ").append(quote(outputFile)).append("\n");

        // Export PATH
        // Original: ShellSnapshot.ts line 339
        script.append("printf 'export PATH=\"%s\"\\n' \"$PATH\" >> ").append(quote(outputFile)).append("\n");

        return script.toString();
    }

    /**
     * Quote a path for shell use (single-quote with escaping)
     */
    private static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * Append bash-specific capture commands.
     *
     * Aligned with ShellSnapshot.ts → getUserSnapshotContent() for bash.
     */
    private static void appendBashCapture(StringBuilder script, String outputFile) {
        // Capture functions (filter out completion functions starting with single _)
        // Original: ShellSnapshot.ts lines 209-232
        script.append("echo '# Functions' >> ").append(quote(outputFile)).append("\n");
        script.append("if command -v declare > /dev/null 2>&1; then\n");
        script.append("  declare -F 2>/dev/null | cut -d' ' -f3 | grep -vE '^_[^_]' | while read func; do\n");
        script.append("    declare -f \"$func\" 2>/dev/null >> ").append(quote(outputFile)).append("\n");
        script.append("  done\n");
        script.append("fi\n");

        // Capture shell options
        // Original: ShellSnapshot.ts lines 244-251
        script.append("echo '# Shell Options' >> ").append(quote(outputFile)).append("\n");
        script.append("shopt -p 2>/dev/null | head -n 1000 >> ").append(quote(outputFile)).append("\n");
        script.append("set -o 2>/dev/null | grep 'on$' | awk '{print \"set -o \" $1}' | head -n 1000 >> ").append(quote(outputFile)).append("\n");
    }

    /**
     * Append zsh-specific capture commands.
     *
     * Aligned with ShellSnapshot.ts → getUserSnapshotContent() for zsh.
     */
    private static void appendZshCapture(StringBuilder script, String outputFile) {
        // Capture functions (zsh uses typeset -f)
        // Original: ShellSnapshot.ts lines 209-232 (zsh branch)
        script.append("echo '# Functions' >> ").append(quote(outputFile)).append("\n");
        script.append("typeset -f 2>/dev/null | head -n 5000 >> ").append(quote(outputFile)).append("\n");

        // Capture shell options
        // Original: ShellSnapshot.ts lines 244-251 (zsh branch)
        script.append("echo '# Shell Options' >> ").append(quote(outputFile)).append("\n");
        script.append("setopt 2>/dev/null | sed 's/^/setopt /' | head -n 1000 >> ").append(quote(outputFile)).append("\n");
    }

    /**
     * Detect shell type from the binary path.
     */
    private static String detectShellType(String shellPath) {
        if (shellPath.contains("zsh")) return "zsh";
        if (shellPath.contains("bash")) return "bash";
        return "sh";
    }

    /**
     * Get the user's shell config file path.
     *
     * Aligned with ShellSnapshot.ts → getConfigFile().
     */
    private static String getUserConfigFile(String shellPath) {
        String home = System.getProperty("user.home");
        if (shellPath.contains("zsh")) {
            Path zshrc = Paths.get(home, ".zshrc");
            return Files.exists(zshrc) ? zshrc.toString() : null;
        }
        if (shellPath.contains("bash")) {
            Path bashrc = Paths.get(home, ".bashrc");
            if (Files.exists(bashrc)) return bashrc.toString();
            Path profile = Paths.get(home, ".profile");
            if (Files.exists(profile)) return profile.toString();
            return null;
        }
        Path profile = Paths.get(home, ".profile");
        return Files.exists(profile) ? profile.toString() : null;
    }

    /**
     * Get the snapshot directory path.
     *
     * Aligned with CC's ~/.claude/shell-snapshots/
     */
    private static Path getSnapshotDir() {
        return Paths.get(System.getProperty("user.home"), ".javaclawbot", "shell-snapshots");
    }

    /**
     * Clean up old snapshot files (older than 24 hours).
     * Called on startup when a new snapshot is being created.
     */
    public static void cleanupOldSnapshots() {
        try {
            Path dir = getSnapshotDir();
            if (!Files.exists(dir)) return;

            long now = System.currentTimeMillis();
            long maxAgeMs = 24 * 60 * 60 * 1000;

            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".sh"))
                        .filter(p -> {
                            try {
                                return now - Files.getLastModifiedTime(p).toMillis() > maxAgeMs;
                            } catch (Exception e) { return false; }
                        })
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        } catch (Exception ignored) {}
    }
}
