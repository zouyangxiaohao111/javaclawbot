package agent.subagent.team.backends.tmux;

import agent.subagent.team.backends.Backend;
import agent.subagent.team.backends.BackendType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tmux 后端
 *
 * 对应 Open-ClaudeCode:
 * - src/utils/swarm/backends/types.ts - PaneBackend
 * - src/tools/shared/spawnMultiAgent.ts - spawnTmuxTeammate()
 *
 * 职责：
 * 1. 管理 tmux 会话和 panes
 * 2. 创建分屏 pane
 * 3. 发送命令到 pane
 * 4. 收集 pane 输出
 * 5. 设置 pane 样式
 * 6. 隐藏/显示 panes
 */
public class TmuxBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(TmuxBackend.class);

    private static final String TMUX_COMMAND = "tmux";
    private static final String SESSION_PREFIX = "claude-";
    private static final String DISPLAY_NAME = "tmux";
    private static final String SWARM_WINDOW_NAME = "swarm-view";

    /** 会话存储: sessionName -> TmuxSession */
    private final Map<String, TmuxSession> sessions = new ConcurrentHashMap<>();

    /** pane 存储: paneId -> TmuxPaneInfo */
    private final Map<String, TmuxPaneInfo> panes = new ConcurrentHashMap<>();

    /** 是否已有第一个 teammate */
    private volatile boolean hasFirstTeammate = false;

    /** 当前 swarm window name */
    private String currentWindowName = SWARM_WINDOW_NAME;

    // =====================
    // Backend 接口实现
    // =====================

    @Override
    public BackendType type() {
        return BackendType.TMUX;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean supportsHideShow() {
        return true;  // tmux 支持通过移动 pane 到其他窗口来实现隐藏/显示
    }

    @Override
    public boolean isAvailable() {
        return isTmuxInstalled();
    }

    @Override
    public boolean isRunningInside() {
        // 检查是否在 tmux 会话中
        return isInsideTmux();
    }

    @Override
    public CreatePaneResult createPane(String name, String color) {
        try {
            String sessionName = SESSION_PREFIX + name;
            boolean isFirst = !hasFirstTeammate;

            // 获取或创建会话
            TmuxSession session = sessions.computeIfAbsent(sessionName, k -> {
                try {
                    Path workdir = Paths.get(System.getProperty("user.dir"));
                    return new TmuxSession(sessionName, workdir);
                } catch (TmuxSession.TmuxException e) {
                    log.error("Failed to create session: {}", e.getMessage());
                    throw new RuntimeException("Failed to create tmux session", e);
                }
            });

            // 创建分屏
            TmuxPane pane = session.splitWindow("horizontal");

            // 获取 target
            String target = pane.getTarget();

            // 存储 pane 信息
            TmuxPaneInfo info = new TmuxPaneInfo(pane.getPaneId(), target, sessionName, session, pane);
            panes.put(pane.getPaneId(), info);

            // 设置颜色
            if (color != null) {
                setPaneBorderColor(pane.getPaneId(), color);
            }

            // 设置标题
            setPaneTitle(pane.getPaneId(), name, color);

            // 标记已有第一个 teammate
            if (isFirst) {
                hasFirstTeammate = true;
            }

            log.info("Created tmux pane: paneId={}, session={}, target={}, isFirst={}",
                    pane.getPaneId(), sessionName, target, isFirst);
            return new CreatePaneResult(pane.getPaneId(), isFirst);

        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to create pane: {}", e.getMessage());
            throw new RuntimeException("Failed to create tmux pane", e);
        }
    }

    @Override
    public void sendCommand(String paneId, String command) {
        TmuxPaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }

        try {
            info.pane.sendCommand(command);
            log.debug("Sent command to pane {}: {}", paneId, command);
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to send command to pane {}: {}", paneId, e.getMessage());
            throw new RuntimeException("Failed to send command", e);
        }
    }

    @Override
    public void killPane(String paneId) {
        TmuxPaneInfo info = panes.remove(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }

        try {
            // 只关闭该 pane，不关闭整个会话
            // tmux close-pane 命令可以关闭单个 pane
            execTmux("kill-pane", "-t", info.target);
            log.info("Killed tmux pane: {}", paneId);
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to kill pane {}: {}", paneId, e.getMessage());
        }
    }

    @Override
    public void setPaneBorderColor(String paneId, String color) {
        TmuxPaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }

        try {
            // tmux set-option 命令设置 pane 边框颜色
            // 格式: set pane-border-format "[{color}]"
            execTmux("set-window-option", "-t", info.sessionName, "pane-border-format",
                    "[%" + color + "%" + "]");
            log.debug("Set pane border color: paneId={}, color={}", paneId, color);
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to set pane border color: {}", e.getMessage());
        }
    }

    @Override
    public void setPaneTitle(String paneId, String name, String color) {
        TmuxPaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return;
        }

        try {
            // 设置窗口标题
            execTmux("set-window-option", "-t", info.sessionName, "automatic-rename", "off");
            execTmux("rename-window", "-t", info.sessionName, name);

            // 设置 pane 标题格式
            String format = color != null ? "[%" + color + "% " + name + "]" : "[" + name + "]";
            execTmux("set-window-option", "-t", info.sessionName, "pane-border-format", format);

            log.debug("Set pane title: paneId={}, name={}, color={}", paneId, name, color);
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to set pane title: {}", e.getMessage());
        }
    }

    @Override
    public void enablePaneBorderStatus() {
        try {
            // 启用 pane 边框状态显示
            execTmux("set-window-option", "-g", "pane-border-status", "on");
            log.debug("Enabled pane border status");
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to enable pane border status: {}", e.getMessage());
        }
    }

    @Override
    public void rebalancePanes(boolean hasLeader) {
        // tmux 没有内置的自动重新平衡命令
        // 可以通过 select-layout 来尝试不同的布局
        for (TmuxPaneInfo info : panes.values()) {
            try {
                // 使用 even-horizontal 或 even-vertical 布局
                execTmux("select-layout", "-t", info.sessionName, "even-horizontal");
            } catch (TmuxSession.TmuxException e) {
                log.error("Failed to rebalance panes for session {}: {}", info.sessionName, e.getMessage());
            }
        }
        log.info("Rebalanced panes: hasLeader={}", hasLeader);
    }

    @Override
    public boolean hidePane(String paneId) {
        TmuxPaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return false;
        }

        try {
            // 将 pane 移动到隐藏窗口
            String hiddenWindow = "hidden-" + paneId;
            execTmux("new-window", "-d", "-n", hiddenWindow);
            execTmux("move-pane", "-t", hiddenWindow + ":" + info.pane.getTarget());
            execTmux("kill-pane", "-t", info.pane.getTarget());
            log.info("Hidden pane: {}", paneId);
            return true;
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to hide pane {}: {}", paneId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean showPane(String paneId, String targetWindowOrPane) {
        TmuxPaneInfo info = panes.get(paneId);
        if (info == null) {
            log.warn("Pane not found: {}", paneId);
            return false;
        }

        try {
            // 将 pane 从隐藏窗口移回主窗口
            execTmux("move-pane", "-s", targetWindowOrPane + ":" + paneId,
                    "-t", info.sessionName + ":" + info.pane.getTarget());
            log.info("Showed pane: {}", paneId);
            return true;
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to show pane {}: {}", paneId, e.getMessage());
            return false;
        }
    }

    @Override
    public String getPaneOutput(String paneId) {
        TmuxPaneInfo info = panes.get(paneId);
        if (info == null) {
            return "";
        }

        try {
            return info.pane.capturePane();
        } catch (TmuxSession.TmuxException e) {
            log.error("Failed to capture pane {}: {}", paneId, e.getMessage());
            return "";
        }
    }

    @Override
    public String pollPaneOutput(String paneId) {
        return getPaneOutput(paneId);
    }

    // =====================
    // 辅助方法
    // =====================

    /**
     * 检测 tmux 是否安装
     */
    public static boolean isTmuxInstalled() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{TMUX_COMMAND, "-V"});
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测是否在 tmux 会话中
     */
    private static boolean isInsideTmux() {
        try {
            // 检查 TMUX 环境变量
            String tmuxEnv = System.getenv("TMUX");
            return tmuxEnv != null && !tmuxEnv.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行 tmux 命令
     */
    private TmuxSession.ProcessResult execTmux(String... args) throws TmuxSession.TmuxException {
        return TmuxSession.execTmux(args);
    }

    /**
     * pane 信息
     */
    private static class TmuxPaneInfo {
        final String paneId;
        final String target;
        final String sessionName;
        final TmuxSession session;
        final TmuxPane pane;

        TmuxPaneInfo(String paneId, String target, String sessionName, TmuxSession session, TmuxPane pane) {
            this.paneId = paneId;
            this.target = target;
            this.sessionName = sessionName;
            this.session = session;
            this.pane = pane;
        }
    }

    // =====================
    // 生命周期管理
    // =====================

    /**
     * 获取所有会话
     */
    public Map<String, TmuxSession> getSessions() {
        return new ConcurrentHashMap<>(sessions);
    }

    /**
     * 获取所有 pane
     */
    public Map<String, TmuxPaneInfo> getPanes() {
        return new ConcurrentHashMap<>(panes);
    }

    /**
     * 关闭所有会话
     */
    public void shutdown() {
        for (TmuxSession session : sessions.values()) {
            try {
                session.kill();
            } catch (TmuxSession.TmuxException e) {
                log.error("Failed to kill session: {}", e.getMessage());
            }
        }
        sessions.clear();
        panes.clear();
    }
}
