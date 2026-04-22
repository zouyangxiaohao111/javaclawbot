package agent.subagent.team.backends;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tmux 后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - TmuxBackend
 *
 * 使用 tmux 作为后端
 */
public class TmuxBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(TmuxBackend.class);

    @Override
    public BackendType type() {
        return BackendType.TMUX;
    }

    @Override
    public String createPane(String name, String color) {
        // TODO: 实现 tmux pane 创建
        log.warn("TmuxBackend.createPane not yet implemented");
        return name;
    }

    @Override
    public void sendCommand(String paneId, String command) {
        // TODO: 实现 tmux 命令发送
        log.warn("TmuxBackend.sendCommand not yet implemented");
    }

    @Override
    public void killPane(String paneId) {
        // TODO: 实现 tmux pane 终止
        log.warn("TmuxBackend.killPane not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        return false;  // TODO: 实现 tmux 检测
    }

    @Override
    public String getPaneOutput(String paneId) {
        log.warn("TmuxBackend.getPaneOutput not yet implemented");
        return "";
    }

    @Override
    public String pollPaneOutput(String paneId) {
        log.warn("TmuxBackend.pollPaneOutput not yet implemented");
        return "";
    }
}
