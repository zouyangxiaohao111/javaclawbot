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
    public String displayName() {
        return "ConPTY";
    }

    @Override
    public boolean supportsHideShow() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;  // TODO: 实现 ConPTY 检测
    }

    @Override
    public boolean isRunningInside() {
        return false;
    }

    @Override
    public CreatePaneResult createPane(String name, String color) {
        // TODO: 实现 ConPTY pane 创建
        log.warn("ConPTYBackend.createPane not yet implemented");
        return CreatePaneResult.first(name);
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
    public void setPaneBorderColor(String paneId, String color) {
        log.warn("ConPTYBackend.setPaneBorderColor not yet implemented");
    }

    @Override
    public void setPaneTitle(String paneId, String name, String color) {
        log.warn("ConPTYBackend.setPaneTitle not yet implemented");
    }

    @Override
    public void enablePaneBorderStatus() {
        log.warn("ConPTYBackend.enablePaneBorderStatus not yet implemented");
    }

    @Override
    public void rebalancePanes(boolean hasLeader) {
        log.warn("ConPTYBackend.rebalancePanes not yet implemented");
    }

    @Override
    public boolean hidePane(String paneId) {
        log.warn("ConPTYBackend.hidePane not yet implemented");
        return false;
    }

    @Override
    public boolean showPane(String paneId, String targetWindowOrPane) {
        log.warn("ConPTYBackend.showPane not yet implemented");
        return false;
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
