package agent.subagent.team.backends;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * iTerm2 后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - ITerm2Backend
 *
 * 使用 iTerm2 作为后端
 */
public class ITerm2Backend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(ITerm2Backend.class);

    @Override
    public BackendType type() {
        return BackendType.ITERM2;
    }

    @Override
    public String createPane(String name, String color) {
        // TODO: 实现 iTerm2 pane 创建
        log.warn("ITerm2Backend.createPane not yet implemented");
        return name;
    }

    @Override
    public void sendCommand(String paneId, String command) {
        // TODO: 实现 iTerm2 命令发送
        log.warn("ITerm2Backend.sendCommand not yet implemented");
    }

    @Override
    public void killPane(String paneId) {
        // TODO: 实现 iTerm2 pane 终止
        log.warn("ITerm2Backend.killPane not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        return false;  // TODO: 实现 iTerm2 检测
    }

    @Override
    public String getPaneOutput(String paneId) {
        log.warn("ITerm2Backend.getPaneOutput not yet implemented");
        return "";
    }

    @Override
    public String pollPaneOutput(String paneId) {
        log.warn("ITerm2Backend.pollPaneOutput not yet implemented");
        return "";
    }
}
