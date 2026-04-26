package agent.subagent.spawn;

import agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * iTerm2 后端实现
 * 对应 Open-ClaudeCode: iTerm2 backend
 */
public class ITerm2Backend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(ITerm2Backend.class);

    @Override
    public boolean isAvailable() {
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("osascript", "-e", "tell application \"iTerm2\" to activate").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<SpawnResult> spawn(SpawnConfig config, ToolUseContext toolUseContext) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionName = config.getSessionName();

                // 构建 AppleScript 命令来创建 iTerm2 窗口
                String script = buildAppleScript(config);

                Process process = new ProcessBuilder(
                        "osascript", "-e", script
                ).start();

                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    log.info("iTerm2 session created: {}", sessionName);
                    return SpawnResult.success(sessionName, "iTerm2 session created");
                } else {
                    String error = new String(process.getInputStream().readAllBytes());
                    log.error("iTerm2 spawn failed: {}", error);
                    return SpawnResult.failure("iTerm2 spawn failed: " + error);
                }

            } catch (Exception e) {
                log.error("iTerm2 spawn error", e);
                return SpawnResult.failure("iTerm2 spawn error: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> terminate(String sessionName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String script = String.format(
                        "tell application \"iTerm2\" to close window \"%s\"", sessionName
                );
                Process process = new ProcessBuilder(
                        "osascript", "-e", script
                ).start();

                int exitCode = process.waitFor();
                log.info("iTerm2 session terminated: {}, exitCode={}", sessionName, exitCode);
                return exitCode == 0;

            } catch (Exception e) {
                log.error("iTerm2 terminate error", e);
                return false;
            }
        });
    }

    private String buildAppleScript(SpawnConfig config) {
        // 构建 AppleScript 来创建 iTerm2 窗口并运行命令
        String command = buildLaunchCommand(config);
        return String.format(
                "tell application \"iTerm2\"\n" +
                "  activate\n" +
                "  create window with default profile\n" +
                "  tell current window\n" +
                "    set name to \"%s\"\n" +
                "    tell current session\n" +
                "      write text \"%s\"\n" +
                "    end tell\n" +
                "  end tell\n" +
                "end tell",
                config.getSessionName(),
                escapeAppleScript(command)
        );
    }

    private String buildLaunchCommand(SpawnConfig config) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("claude --subagent");
        cmd.append(" --prompt \"").append(escapeShell(config.getPrompt())).append("\"");
        if (config.getAgentType() != null) {
            cmd.append(" --agent-type ").append(config.getAgentType());
        }
        if (config.getModel() != null) {
            cmd.append(" --model ").append(config.getModel());
        }
        return cmd.toString();
    }

    private String escapeAppleScript(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeShell(String input) {
        if (input == null) return "";
        return input.replace("\"", "\\\"");
    }
}
