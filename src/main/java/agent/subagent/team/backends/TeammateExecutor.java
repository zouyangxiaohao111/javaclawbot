package agent.subagent.team.backends;

import agent.subagent.team.messaging.TeammateMailbox.MailboxMessage;

/**
 * Teammate 执行器接口
 *
 * 对应 Open-ClaudeCode: src/utils/swarm/backends/types.ts - TeammateExecutor
 *
 * 定义 teammate 生命周期管理的标准操作：
 * 1. Spawn - 创建新的 teammate
 * 2. SendMessage - 向 teammate 发送消息
 * 3. Terminate - 优雅终止 teammate
 * 4. Kill - 强制终止 teammate
 * 5. IsActive - 检查 teammate 是否活跃
 *
 * TeammateExecutor 负责高级的 teammate 生命周期操作，
 * 而 PaneBackend 负责底层 pane 操作。
 */
public interface TeammateExecutor {

    /**
     * 获取后端类型
     *
     * @return BackendType
     */
    BackendType getType();

    /**
     * 检查此执行器在系统上是否可用
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * Spawn 一个新的 teammate
     *
     * @param config spawn 配置
     * @return spawn 结果
     */
    TeammateSpawnResult spawn(TeammateSpawnConfig config);

    /**
     * 向 teammate 发送消息
     *
     * @param agentId agent ID
     * @param message 消息
     */
    void sendMessage(String agentId, MailboxMessage message);

    /**
     * 终止 teammate（优雅关闭请求）
     *
     * @param agentId agent ID
     * @param reason 终止原因
     * @return 是否成功
     */
    boolean terminate(String agentId, String reason);

    /**
     * 强制终止 teammate（立即终止）
     *
     * @param agentId agent ID
     * @return 是否成功
     */
    boolean kill(String agentId);

    /**
     * 检查 teammate 是否仍然活跃
     *
     * @param agentId agent ID
     * @return 是否活跃
     */
    boolean isActive(String agentId);
}
