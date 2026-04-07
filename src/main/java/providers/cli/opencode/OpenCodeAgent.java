package providers.cli.opencode;

import lombok.extern.slf4j.Slf4j;
import providers.cli.*;
import providers.cli.model.CliSessionInfo;
import providers.cli.model.ModelOption;
import providers.cli.model.PermissionModeInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * OpenCode CLI Agent
 */
@Slf4j
public class OpenCodeAgent implements CliAgent {

    // 默认模型列表
    private static final List<ModelOption> DEFAULT_MODELS = List.of(
            new ModelOption("anthropic/claude-sonnet-4-20250514", "Claude Sonnet 4", "claude"),
            new ModelOption("anthropic/claude-opus-4-20250514", "Claude Opus 4", "opus"),
            new ModelOption("openai/gpt-4o", "GPT-4o", "gpt4"),
            new ModelOption("openai/gpt-4.1", "GPT-4.1", "gpt41"),
            new ModelOption("google/gemini-2.5-pro", "Gemini 2.5 Pro", "gemini"),
            new ModelOption("deepseek/deepseek-chat", "DeepSeek Chat", "deepseek")
    );

    // 权限模式列表
    private static final List<PermissionModeInfo> PERMISSION_MODES = List.of(
            new PermissionModeInfo("default", "Default", "默认",
                    "Ask for confirmation on each tool use", "每次工具使用都需要确认"),
            new PermissionModeInfo("auto", "Auto", "自动",
                    "Auto-accept all operations", "自动接受所有操作"),
            new PermissionModeInfo("plan", "Plan Mode", "规划模式",
                    "Plan without making changes", "只规划不执行")
    );

    @Override
    public String name() {
        return "opencode";
    }

    @Override
    public String cliBinaryName() {
        return "opencode";
    }

    @Override
    public boolean checkCliAvailable() {
        try {
            List<String> cmd = isWindows()
                    ? List.of("cmd.exe", "/c", "opencode", "--version")
                    : List.of("opencode", "--version");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (completed && process.exitValue() == 0) {
                return true;
            }
        } catch (Exception e) {
            log.debug("OpenCode CLI not available: {}", e.getMessage());
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
                return new OpenCodeSession(config);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create OpenCode session", e);
            }
        });
    }

    @Override
    public CompletableFuture<List<CliSessionInfo>> listSessions(String workDir) {
        return CompletableFuture.supplyAsync(() -> {
            List<CliSessionInfo> sessions = new ArrayList<>();

            // OpenCode 会话存储在 ~/.opencode/sessions/
            String home = System.getProperty("user.home");
            Path opencodeDir = Path.of(home, ".opencode", "sessions");

            if (!Files.exists(opencodeDir)) {
                return sessions;
            }

            try {
                Files.list(opencodeDir)
                        .filter(Files::isDirectory)
                        .forEach(sessionDir -> {
                            try {
                                String sessionId = sessionDir.getFileName().toString();
                                long modified = Files.getLastModifiedTime(sessionDir).toMillis();

                                // 计算消息数
                                Path historyFile = sessionDir.resolve("history.json");
                                int count = 0;
                                if (Files.exists(historyFile)) {
                                    count = (int) Files.lines(historyFile).count();
                                }

                                sessions.add(new CliSessionInfo(
                                        sessionId,
                                        null,
                                        count,
                                        java.time.LocalDateTime.ofEpochSecond(
                                                modified / 1000, 0, java.time.ZoneOffset.UTC),
                                        null
                                ));
                            } catch (Exception e) {
                                log.warn("Failed to read session dir: {}", sessionDir, e);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to list OpenCode sessions", e);
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
        return Path.of(workDir, "OPENCODE.md").toString();
    }
}
