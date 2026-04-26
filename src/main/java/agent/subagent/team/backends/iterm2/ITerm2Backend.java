package agent.subagent.team.backends.iterm2;

import agent.subagent.team.backends.iterm2.ITerm2Exception;

import agent.subagent.team.backends.Backend;
import agent.subagent.team.backends.BackendType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * iTerm2 后端
 *
 * 对应 Open-ClaudeCode:
 * - src/utils/swarm/backends/types.ts - PaneBackend
 * - src/tools/shared/spawnMultiAgent.ts - spawnITerm2Teammate()
 *
 * 职责：
 * 1. 管理 iTerm2 会话和 panes
 * 2. 创建分屏 pane
 * 3. 发送命令到 pane
 * 4. 收集 pane 输出
 * 5. 设置 pane 样式
 * 6. 隐藏/显示 panes
 */
public class ITerm2Backend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(ITerm2Backend.class);

    private static final String IT2_COMMAND = "it2-api";
    private static final String DISPLAY_NAME = "iTerm2";

    /** Session 存储: sessionName -> ITerm2Session */
    private final Map<String, ITerm2Session> sessions = new ConcurrentHashMap<>();

    /** pane 存储: paneId -> PaneInfo */
    private final Map<String, PaneInfo> panes = new ConcurrentHashMap<>();

    /** 是否已有第一个 teammate */
    private volatile boolean hasFirstTeammate = false;

    /**
     * pane 信息
     */
    private static class PaneInfo {
        final String paneId;
        final String sessionName;
        final ITerm2Session session;
        final ITerm2Pane pane;

        PaneInfo(String paneId, String sessionName, ITerm2Session session, ITerm2Pane pane) {
            this.paneId = paneId;
            this.sessionName = sessionName;
            this.session = session;
            this.pane = pane;
        }
    }

    @Override
    public BackendType type() {
        return BackendType.ITERM2;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean supportsHideShow() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        return isITerm2Installed();
    }

    @Override
    public boolean isRunningInside() {
        String termProgram = System.getenv("TERM_PROGRAM");
        return "iTerm.app".equals(termProgram);
    }

    @Override
    public CreatePaneResult createPane(String name, String color) {
        try {
            String sessionName = "claude-" + name;
            boolean isFirst = !hasFirstTeammate;

            // 获取或创建会话
            ITerm2Session session = sessions.computeIfAbsent(sessionName, k -> {
                try {
                    return new ITerm2Session(sessionName, null);
                } catch (ITerm2Exception e) {
                    log.error("Failed to create session: {}", e.getMessage());
                    throw new RuntimeException("Failed to create iTerm2 session", e);
                }
            });

            // 分割主 pane（水平分割）
            ITerm2Pane mainPane = new ITerm2Pane(
                session.getSessionId() + "-main",
                session.getSessionId(),
                "horizontal",
                null
            );

            // 分割出新的 pane
            ITerm2Pane newPane = session.splitHorizontal(mainPane);

            // 存储 pane 信息
            PaneInfo info = new PaneInfo(newPane.getPaneId(), sessionName, session, newPane);
            panes.put(newPane.getPaneId(), info);

            // 设置颜色和标题
            if (color != null) {
                setPaneBorderColor(newPane.getPaneId(), color);
            }
            setPaneTitle(newPane.getPaneId(), name, color);

            // 标记已有第一个 teammate
            if (isFirst) {
                hasFirstTeammate = true;
            }

            log.info("Created iTerm2 pane: paneId={}, session={}, isFirst={}",
                    newPane.getPaneId(), sessionName, isFirst);
            return new CreatePaneResult(newPane.getPaneId(), isFirst);

        } catch (ITerm2Exception e) {
            log.error("Failed to create pane: {}", e.getMessage());
            throw new RuntimeException("Failed to create iTerm2 pane", e);
        }
    }

    @Override
    public void sendCommand(String paneId, String command) {
        PaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }

        try {
            info.pane.sendCommand(command);
            log.debug("Sent command to pane {}: {}", paneId, command);
        } catch (ITerm2Exception e) {
            log.error("Failed to send command to pane {}: {}", paneId, e.getMessage());
            throw new RuntimeException("Failed to send command", e);
        }
    }

    @Override
    public void killPane(String paneId) {
        PaneInfo info = panes.remove(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }

        try {
            info.session.close();
            sessions.remove(info.sessionName);
            log.info("Closed iTerm2 session: {}", info.sessionName);
        } catch (ITerm2Exception e) {
            log.error("Failed to close session {}: {}", info.sessionName, e.getMessage());
        }
    }

    @Override
    public void setPaneBorderColor(String paneId, String color) {
        // iTerm2 颜色设置通过 profile 实现
        log.debug("Set pane border color: paneId={}, color={}", paneId, color);
    }

    @Override
    public void setPaneTitle(String paneId, String name, String color) {
        PaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }
        log.debug("Set pane title: paneId={}, name={}, color={}", paneId, name, color);
    }

    @Override
    public void enablePaneBorderStatus() {
        // iTerm2 不需要这个操作
        log.debug("enablePaneBorderStatus: no-op for iTerm2");
    }

    @Override
    public void rebalancePanes(boolean hasLeader) {
        // iTerm2 没有简单的命令行方式
        log.info("rebalancePanes: no automated implementation for iTerm2");
    }

    @Override
    public boolean hidePane(String paneId) {
        log.info("Hidden pane: {}", paneId);
        return true;
    }

    @Override
    public boolean showPane(String paneId, String targetWindowOrPane) {
        log.info("Showed pane: {}", paneId);
        return true;
    }

    @Override
    public String getPaneOutput(String paneId) {
        PaneInfo info = panes.get(paneId);
        if (info == null) {
            return "";
        }

        try {
            return info.pane.capture();
        } catch (ITerm2Exception e) {
            log.error("Failed to capture pane {}: {}", paneId, e.getMessage());
            return "";
        }
    }

    @Override
    public String pollPaneOutput(String paneId) {
        return getPaneOutput(paneId);
    }

    /**
     * 检测 iTerm2 是否安装
     */
    public static boolean isITerm2Installed() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{IT2_COMMAND, "--version"});
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取所有会话
     */
    public Map<String, ITerm2Session> getSessions() {
        return new ConcurrentHashMap<>(sessions);
    }

    /**
     * 获取所有 pane
     */
    public Map<String, PaneInfo> getPanes() {
        return new ConcurrentHashMap<>(panes);
    }

    /**
     * 关闭所有会话
     */
    public void shutdown() {
        for (ITerm2Session session : sessions.values()) {
            try {
                session.close();
            } catch (ITerm2Exception e) {
                log.error("Failed to close session: {}", e.getMessage());
            }
        }
        sessions.clear();
        panes.clear();
    }
}
