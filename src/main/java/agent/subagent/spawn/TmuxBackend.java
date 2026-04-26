package agent.subagent.spawn;

import agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Tmux 后端实现
 * 对应 Open-ClaudeCode: tmux backend
 */
public class TmuxBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(TmuxBackend.class);

    @Override
    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder("tmux", "-V").start();
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
                String workingDir = config.getWorkingDirectory() != null
                        ? config.getWorkingDirectory()
                        : System.getProperty("user.home");

                // 构建启动命令
                String command = buildLaunchCommand(config);

                // 创建 tmux session
                ProcessBuilder pb = new ProcessBuilder(
                        "tmux", "new-session", "-d", "-s", sessionName,
                        "-c", workingDir, command
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    log.info("Tmux session created: {}", sessionName);
                    return SpawnResult.success(sessionName, "Tmux session created");
                } else {
                    String error = new String(process.getInputStream().readAllBytes());
                    log.error("Tmux spawn failed: {}", error);
                    return SpawnResult.failure("tmux spawn failed: " + error);
                }

            } catch (Exception e) {
                log.error("Tmux spawn error", e);
                return SpawnResult.failure("tmux spawn error: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> terminate(String sessionName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Process process = new ProcessBuilder(
                        "tmux", "kill-session", "-t", sessionName
                ).start();

                int exitCode = process.waitFor();
                log.info("Tmux session terminated: {}, exitCode={}", sessionName, exitCode);
                return exitCode == 0;

            } catch (Exception e) {
                log.error("Tmux terminate error", e);
                return false;
            }
        });
    }

    /**
     * 构建启动命令
     */
    private String buildLaunchCommand(SpawnConfig config) {
        // 构建 claude 命令
        StringBuilder cmd = new StringBuilder();
        cmd.append("claude");

        // 添加子代理参数
        cmd.append(" --subagent");
        cmd.append(" --prompt \"").append(escape(config.getPrompt())).append("\"");

        // 添加 agent type
        if (config.getAgentType() != null) {
            cmd.append(" --agent-type ").append(config.getAgentType());
        }

        // 添加 model
        if (config.getModel() != null) {
            cmd.append(" --model ").append(config.getModel());
        }

        return cmd.toString();
    }

    private String escape(String input) {
        if (input == null) return "";
        return input.replace("\"", "\\\"");
    }
}
