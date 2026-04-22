package agent.subagent.team.backends;

/**
 * 后端接口
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - Backend 接口
 *
 * 定义后端的标准操作
 */
public interface Backend {

    /**
     * 获取后端类型
     */
    BackendType type();

    /**
     * 创建 pane
     * 对应: createPane()
     *
     * @param name pane 名称
     * @param color 颜色配置
     * @return pane ID
     */
    String createPane(String name, String color);

    /**
     * 发送命令到 pane
     * 对应: sendCommand()
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

    /**
     * 检查后端是否可用
     * 对应: isAvailable()
     *
     * @return 是否可用
     */
    boolean isAvailable();

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
}
