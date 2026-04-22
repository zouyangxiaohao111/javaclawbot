package agent.subagent.team;

import agent.subagent.team.backends.BackendType;

/**
 * Teammate 信息
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - SpawnOutput
 */
public class TeammateInfo {

    /** 唯一标识符 */
    private final String id;

    /** Teammate 名称 */
    private final String name;

    /** 团队名称 */
    private final String teamName;

    /** 后端类型 */
    private final BackendType backendType;

    /** Pane ID */
    private final String paneId;

    /** 初始提示词 */
    private final String initialPrompt;

    /** 创建时间 */
    private final long createdAt;

    /** 状态 */
    private String status;

    /** 最后输出 */
    private String lastOutput;

    public TeammateInfo(String id, String name, String teamName, BackendType backendType, String initialPrompt) {
        this.id = id;
        this.name = name;
        this.teamName = teamName;
        this.backendType = backendType;
        this.paneId = id;  // paneId 与 id 相同
        this.initialPrompt = initialPrompt;
        this.createdAt = System.currentTimeMillis();
        this.status = "running";
        this.lastOutput = "";
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTeamName() {
        return teamName;
    }

    public BackendType getBackendType() {
        return backendType;
    }

    public String getPaneId() {
        return paneId;
    }

    public String getInitialPrompt() {
        return initialPrompt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastOutput() {
        return lastOutput;
    }

    public void setLastOutput(String lastOutput) {
        this.lastOutput = lastOutput;
    }

    @Override
    public String toString() {
        return "TeammateInfo{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", teamName='" + teamName + '\'' +
                ", backendType=" + backendType +
                ", status='" + status + '\'' +
                '}';
    }
}
