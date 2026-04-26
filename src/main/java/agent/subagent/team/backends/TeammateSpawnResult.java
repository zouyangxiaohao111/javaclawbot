package agent.subagent.team.backends;

/**
 * Teammate Spawn 结果
 *
 * 对应 Open-ClaudeCode: src/utils/swarm/backends/types.ts - TeammateSpawnResult
 */
public class TeammateSpawnResult {

    /** Spawn 是否成功 */
    private final boolean success;

    /** 唯一 agent ID (格式: agentName@teamName) */
    private final String agentId;

    /** 如果 spawn 失败，错误消息 */
    private final String error;

    /** 用于生命周期管理的中止控制器（仅 in-process）
     * Leader 使用此来取消/终止 teammate
     * 对于基于 pane 的 teammate，使用 kill() 方法 */
    private final InProcessBackend.AbortController abortController;

    /** AppState.tasks 中的任务 ID（仅 in-process）
     * 用于 UI 渲染和进度追踪
     * agentId 是逻辑标识符；taskId 用于 AppState 索引 */
    private final String taskId;

    /** Pane ID（仅基于 pane 的后端） */
    private final String paneId;

    public TeammateSpawnResult(boolean success, String agentId, String error,
                               InProcessBackend.AbortController abortController,
                               String taskId, String paneId) {
        this.success = success;
        this.agentId = agentId;
        this.error = error;
        this.abortController = abortController;
        this.taskId = taskId;
        this.paneId = paneId;
    }

    public boolean isSuccess() { return success; }
    public String getAgentId() { return agentId; }
    public String getError() { return error; }
    public InProcessBackend.AbortController getAbortController() { return abortController; }
    public String getTaskId() { return taskId; }
    public String getPaneId() { return paneId; }

    /**
     * 创建成功结果
     */
    public static TeammateSpawnResult success(String agentId, String paneId, String taskId,
                                               InProcessBackend.AbortController abortController) {
        return new TeammateSpawnResult(true, agentId, null, abortController, taskId, paneId);
    }

    /**
     * 创建失败结果
     */
    public static TeammateSpawnResult failure(String error) {
        return new TeammateSpawnResult(false, null, error, null, null, null);
    }

    @Override
    public String toString() {
        return "TeammateSpawnResult{" +
                "success=" + success +
                ", agentId='" + agentId + '\'' +
                ", error='" + error + '\'' +
                ", taskId='" + taskId + '\'' +
                ", paneId='" + paneId + '\'' +
                '}';
    }
}
