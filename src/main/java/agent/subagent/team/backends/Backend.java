package agent.subagent.team.backends;

/**
 * 后端接口
 *
 * 对应 Open-ClaudeCode: src/utils/swarm/backends/types.ts - PaneBackend
 *
 * 定义 pane 管理后端的标准操作：
 * 1. 创建和销毁 panes
 * 2. 发送命令到 panes
 * 3. 设置 pane 样式（颜色、标题）
 * 4. 隐藏/显示 panes
 * 5. 重新平衡布局
 */
public interface Backend {

    // =====================
    // 基本信息
    // =====================

    /**
     * 获取后端类型
     */
    BackendType type();

    /**
     * 后端的可读名称
     */
    String displayName();

    /**
     * 是否支持隐藏和显示 panes
     */
    boolean supportsHideShow();

    // =====================
    // 可用性检测
    // =====================

    /**
     * 检查后端在系统上是否可用
     * 对应: isAvailable()
     */
    boolean isAvailable();

    /**
     * 检查是否在此后端的环境中运行
     * 对应: isRunningInside()
     */
    boolean isRunningInside();

    // =====================
    // Pane 管理
    // =====================

    /**
     * 创建 teammate 的新 pane
     * 对应: createTeammatePaneInSwarmView()
     *
     * @param name teammate 名称（用于显示）
     * @param color 颜色配置
     * @return 创建 pane 的结果
     */
    CreatePaneResult createPane(String name, String color);

    /**
     * 发送命令到指定 pane
     * 对应: sendCommandToPane()
     *
     * @param paneId pane ID
     * @param command 命令
     */
    void sendCommand(String paneId, String command);

    /**
     * 终止 pane
     * 对应: killPane()
     *
     * @param paneId pane ID
     */
    void killPane(String paneId);

    // =====================
    // Pane 样式
    // =====================

    /**
     * 设置 pane 边框颜色
     * 对应: setPaneBorderColor()
     *
     * @param paneId pane ID
     * @param color 颜色名称
     */
    void setPaneBorderColor(String paneId, String color);

    /**
     * 设置 pane 标题
     * 对应: setPaneTitle()
     *
     * @param paneId pane ID
     * @param name 标题
     * @param color 颜色
     */
    void setPaneTitle(String paneId, String name, String color);

    /**
     * 启用 pane 边框状态显示（边框中显示标题）
     * 对应: enablePaneBorderStatus()
     */
    void enablePaneBorderStatus();

    // =====================
    // 布局管理
    // =====================

    /**
     * 重新平衡 panes 以达到期望的布局
     * 对应: rebalancePanes()
     *
     * @param hasLeader 是否有 leader pane（影响布局策略）
     */
    void rebalancePanes(boolean hasLeader);

    // =====================
    // 隐藏/显示
    // =====================

    /**
     * 隐藏 pane（通过分离到隐藏窗口）
     * 对应: hidePane()
     *
     * @param paneId pane ID
     * @return 是否成功隐藏
     */
    boolean hidePane(String paneId);

    /**
     * 显示之前隐藏的 pane
     * 对应: showPane()
     *
     * @param paneId pane ID
     * @param targetWindowOrPane 目标窗口或 pane
     * @return 是否成功显示
     */
    boolean showPane(String paneId, String targetWindowOrPane);

    // =====================
    // 输出获取
    // =====================

    /**
     * 获取 pane 的输出
     * 对应: getPaneOutput()
     *
     * @param paneId pane ID
     * @return pane 输出
     */
    String getPaneOutput(String paneId);

    /**
     * 轮询 pane 的新输出
     * 对应: pollPaneOutput()
     *
     * @param paneId pane ID
     * @return 新输出内容
     */
    String pollPaneOutput(String paneId);

    // =====================
    // 辅助类
    // =====================

    /**
     * 创建 pane 的结果
     *
     * 对应 Open-ClaudeCode: CreatePaneResult
     */
    class CreatePaneResult {
        /** 新创建的 pane ID */
        private final String paneId;

        /** 是否是第一个 teammate pane（影响布局策略） */
        private final boolean isFirstTeammate;

        public CreatePaneResult(String paneId, boolean isFirstTeammate) {
            this.paneId = paneId;
            this.isFirstTeammate = isFirstTeammate;
        }

        public String getPaneId() { return paneId; }
        public boolean isFirstTeammate() { return isFirstTeammate; }

        public static CreatePaneResult first(String paneId) {
            return new CreatePaneResult(paneId, true);
        }

        public static CreatePaneResult notFirst(String paneId) {
            return new CreatePaneResult(paneId, false);
        }
    }
}
