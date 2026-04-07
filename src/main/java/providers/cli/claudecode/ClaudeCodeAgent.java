package providers.cli.claudecode;

import lombok.extern.slf4j.Slf4j;
import providers.cli.*;
import providers.cli.model.CliSessionInfo;
import providers.cli.model.ModelOption;
import providers.cli.model.PermissionModeInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Claude Code CLI Agent
 */
@Slf4j
public class ClaudeCodeAgent implements CliAgent {

    // 默认模型列表
    private static final List<ModelOption> DEFAULT_MODELS = List.of(
            new ModelOption("sonnet", "Claude Sonnet 4", "sonnet"),
            new ModelOption("opus", "Claude Opus 4", "opus"),
            new ModelOption("haiku", "Claude Haiku 3.5", "haiku"),
            new ModelOption("claude-sonnet-4-20250514", "Claude Sonnet 4 (full)", null),
            new ModelOption("claude-opus-4-20250514", "Claude Opus 4 (full)", null)
    );

    // 权限模式列表
    private static final List<PermissionModeInfo> PERMISSION_MODES = List.of(
            new PermissionModeInfo("default", "Default", "默认",
                    "Ask for confirmation on each tool use", "每次工具使用都需要确认"),
            new PermissionModeInfo("acceptEdits", "Accept Edits", "接受编辑",
                    "Auto-accept file edit operations", "自动接受文件编辑操作"),
            new PermissionModeInfo("plan", "Plan Mode", "规划模式",
                    "Plan without making changes", "只规划不执行"),
            new PermissionModeInfo("auto", "Auto", "自动",
                    "Let Claude decide", "由 Claude 自动判断"),
            new PermissionModeInfo("bypassPermissions", "Bypass Permissions", "跳过权限",
                    "Auto-accept all operations (yolo)", "自动接受所有操作"),
            new PermissionModeInfo("dontAsk", "Don't Ask", "不询问",
                    "Auto-deny unknown tools", "自动拒绝未授权的工具")
    );

    @Override
    public String name() {
        return "claudecode";
    }

    @Override
    public String cliBinaryName() {
        return "claude";
    }

    @Override
    public boolean checkCliAvailable() {
        try {
            List<String> cmd = isWindows()
                    ? List.of("cmd.exe", "/c", "claude", "--version")
                    : List.of("claude", "--version");
            log.debug("Checking Claude CLI availability: {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (completed && process.exitValue() == 0) {
                // 读取版本信息
                String version = new String(process.getInputStream().readAllBytes()).trim();
                log.info("Claude CLI available: {}", version);
                return true;
            } else {
                log.warn("Claude CLI check failed, completed={}, exitCode={}", completed,
                        completed ? process.exitValue() : "timeout");
            }
        } catch (Exception e) {
            log.debug("Claude CLI not available: {}", e.getMessage());
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @Override
    public CompletableFuture<CliAgentSession> createSession(CliAgentConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new ClaudeCodeSession(config);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create Claude Code session", e);
            }
        });
    }

    @Override
    public CompletableFuture<List<CliSessionInfo>> listSessions(String workDir) {
        return CompletableFuture.supplyAsync(() -> {
            List<CliSessionInfo> sessions = new ArrayList<>();

            // Claude Code 会话存储在 ~/.claude/projects/
            String home = System.getProperty("user.home");
            Path claudeDir = Path.of(home, ".claude", "projects");

            if (!Files.exists(claudeDir)) {
                return sessions;
            }

            try {
                Files.list(claudeDir)
                        .filter(Files::isDirectory)
                        .forEach(projectDir -> {
                            try {
                                Files.list(projectDir)
                                        .filter(p -> p.toString().endsWith(".jsonl"))
                                        .forEach(sessionFile -> {
                                            try {
                                                String fileName = sessionFile.getFileName().toString();
                                                String sessionId = fileName.replace(".jsonl", "");

                                                long modified = Files.getLastModifiedTime(sessionFile).toMillis();
                                                long count = Files.lines(sessionFile).count();

                                                sessions.add(new CliSessionInfo(
                                                        sessionId,
                                                        null,
                                                        (int) count,
                                                        java.time.LocalDateTime.ofEpochSecond(
                                                                modified / 1000, 0, java.time.ZoneOffset.UTC),
                                                        null
                                                ));
                                            } catch (Exception e) {
                                                log.warn("Failed to read session file: {}", sessionFile, e);
                                            }
                                        });
                            } catch (Exception e) {
                                log.warn("Failed to list project dir: {}", projectDir, e);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to list Claude sessions", e);
            }

            sessions.sort(Comparator.comparing(CliSessionInfo::modifiedAt).reversed());
            return sessions;
        });
    }

    @Override
    public List<ModelOption> getAvailableModels() {
        return DEFAULT_MODELS;
    }

    @Override
    public List<PermissionModeInfo> getPermissionModes() {
        return PERMISSION_MODES;
    }

    @Override
    public String getProjectMemoryFile(String workDir) {
        return Path.of(workDir, "CLAUDE.md").toString();
    }
}
