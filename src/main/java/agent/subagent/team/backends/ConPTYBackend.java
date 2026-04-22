package agent.subagent.team.backends;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ConPTY 后端
 *
 * 对应 Open-ClaudeCode: 无直接对应（Windows 特有的扩展）
 *
 * 使用 Windows ConPTY 作为后端
 */
public class ConPTYBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(ConPTYBackend.class);

    @Override
    public BackendType type() {
        return BackendType.CONPTY;
    }

    @Override
    public String createPane(String name, String color) {
        // TODO: 实现 ConPTY pane 创建
        log.warn("ConPTYBackend.createPane not yet implemented");
        return name;
    }

    @Override
    public void sendCommand(String paneId, String command) {
        // TODO: 实现 ConPTY 命令发送
        log.warn("ConPTYBackend.sendCommand not yet implemented");
    }

    @Override
    public void killPane(String paneId) {
        // TODO: 实现 ConPTY pane 终止
        log.warn("ConPTYBackend.killPane not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        return false;  // TODO: 实现 ConPTY 检测
    }

    @Override
    public String getPaneOutput(String paneId) {
        log.warn("ConPTYBackend.getPaneOutput not yet implemented");
        return "";
    }

    @Override
    public String pollPaneOutput(String paneId) {
        log.warn("ConPTYBackend.pollPaneOutput not yet implemented");
        return "";
    }
}
