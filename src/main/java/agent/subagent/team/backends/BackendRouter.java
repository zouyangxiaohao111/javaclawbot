package agent.subagent.team.backends;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 后端路由器
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - BackendRouter
 *
 * 职责：
 * 1. 检测平台
 * 2. 检测可用工具（tmux, it2 CLI）
 * 3. 根据配置和可用性选择后端
 * 4. 支持配置覆盖
 */
public class BackendRouter {

    private static final Logger log = LoggerFactory.getLogger(BackendRouter.class);

    /** 配置的后端类型环境变量 */
    private static final String BACKEND_ENV_VAR = "JAVACLAWBOT_BACKEND";

    /**
     * 检测可用后端并返回
     * 对应: detectBackend()
     *
     * @return 检测到的可用后端
     */
    public Backend detectBackend() {
        // 1. 检查明确配置（环境变量或配置）
        String configured = getConfiguredBackend();
        if (configured != null) {
            log.debug("Using configured backend: {}", configured);
            return createBackend(configured);
        }

        // 2. 检测平台
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows: ConPTY > InProcess
            if (isConPTYAvailable()) {
                log.debug("Using ConPTY backend");
                return new ConPTYBackend();
            }
            log.debug("Using InProcess backend (ConPTY not available)");
            return new InProcessBackend();
        }

        if (os.contains("mac")) {
            // macOS: iTerm2 > tmux > InProcess
            if (isITerm2Available()) {
                log.debug("Using ITerm2 backend");
                return new ITerm2Backend();
            }
            if (isTmuxAvailable()) {
                log.debug("Using Tmux backend");
                return new TmuxBackend();
            }
            log.debug("Using InProcess backend (tmux/iTerm2 not available)");
            return new InProcessBackend();
        }

        // Linux: tmux > InProcess
        if (isTmuxAvailable()) {
            log.debug("Using Tmux backend");
            return new TmuxBackend();
        }
        log.debug("Using InProcess backend (tmux not available)");
        return new InProcessBackend();
    }

    /**
     * 获取配置的后端类型
     * 对应: getConfiguredBackend()
     *
     * @return 配置的后端类型，或 null
     */
    private String getConfiguredBackend() {
        String configured = System.getenv(BACKEND_ENV_VAR);
        if (configured != null && !configured.isEmpty()) {
            return configured.toLowerCase();
        }
        return null;
    }

    /**
     * 创建后端实例
     *
     * @param backendType 后端类型
     * @return 后端实例
     */
    public Backend createBackend(String backendType) {
        return switch (backendType.toLowerCase()) {
            case "in_process", "inprocess" -> new InProcessBackend();
            case "tmux" -> new TmuxBackend();
            case "iterm2", "iterm" -> new ITerm2Backend();
            case "conpty" -> new ConPTYBackend();
            default -> {
                log.warn("Unknown backend type: {}, falling back to InProcessBackend", backendType);
                yield new InProcessBackend();
            }
        };
    }

    /**
     * 检测 tmux 是否可用
     * 对应: isTmuxAvailable()
     *
     * @return tmux 是否可用
     */
    public boolean isTmuxAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("tmux -V");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("tmux not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测 iTerm2 是否可用
     * 对应: isITerm2Available()
     *
     * @return iTerm2 CLI 是否可用
     */
    public boolean isITerm2Available() {
        try {
            // 检查 it2li 命令是否存在
            Process process = Runtime.getRuntime().exec("which it2li");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("iTerm2 not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检测 ConPTY 是否可用
     * 对应: isConPTYAvailable()
     *
     * @return ConPTY 是否可用
     */
    public boolean isConPTYAvailable() {
        // Windows 特定检测
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("win")) {
            return false;
        }
        // TODO: 更精确的 ConPTY 可用性检测
        return true;
    }
}
