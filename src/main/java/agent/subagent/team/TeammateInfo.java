package agent.subagent.team;

import agent.subagent.team.backends.BackendType;

/**
 * Teammate 信息
 *
 * 对应 Open-ClaudeCode:
 * - src/tools/shared/spawnMultiAgent.ts - SpawnOutput
 * - src/utils/swarm/backends/types.ts - TeammateIdentity
 */
public class TeammateInfo {

    // =====================
    // TeammateIdentity 字段
    // =====================

    /** Teammate 名称 (如 "researcher", "tester") */
    private final String name;

    /** 团队名称 */
    private final String teamName;

    /** 分配的颜色（UI 差异化） */
    private final String color;

    /** 是否需要计划模式批准 */
    private final boolean planModeRequired;

    // =====================
    // SpawnOutput 字段
    // =====================

    /** 唯一标识符 */
    private final String teammateId;

    /** 唯一 agent ID (格式: agentName@teamName) */
    private final String agentId;

    /** Agent 类型 */
    private final String agentType;

    /** 使用的模型 */
    private final String model;

    /** Pane ID */
    private final String paneId;

    /** 是否是第一个 teammate (影响布局策略) */
    private final boolean isFirstTeammate;

    /** 创建时间 */
    private final long createdAt;

    /** 状态 */
    private String status;

    /** 最后输出 */
    private String lastOutput;

    // =====================
    // Pane 特定字段 (tmux/iTerm2)
    // =====================

    /** tmux session name */
    private String tmuxSessionName;

    /** tmux window name */
    private String tmuxWindowName;

    /** 是否使用 split pane */
    private boolean isSplitPane;

    // =====================
    // 构造函数
    // =====================

    public TeammateInfo(String name, String teamName, BackendType backendType, String initialPrompt) {
        this.name = name;
        this.teamName = teamName;
        this.color = null;
        this.planModeRequired = false;
        this.teammateId = generateId(name, teamName);
        this.agentId = formatAgentId(name, teamName);
        this.agentType = null;
        this.model = null;
        this.paneId = this.teammateId;
        this.isFirstTeammate = true;
        this.createdAt = System.currentTimeMillis();
        this.status = "running";
        this.lastOutput = "";
    }

    private TeammateInfo(Builder builder) {
        this.name = builder.name;
        this.teamName = builder.teamName;
        this.color = builder.color;
        this.planModeRequired = builder.planModeRequired;
        this.teammateId = builder.teammateId != null ? builder.teammateId : generateId(builder.name, builder.teamName);
        this.agentId = builder.agentId != null ? builder.agentId : formatAgentId(builder.name, builder.teamName);
        this.agentType = builder.agentType;
        this.model = builder.model;
        this.paneId = builder.paneId != null ? builder.paneId : this.teammateId;
        this.isFirstTeammate = builder.isFirstTeammate;
        this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
        this.status = builder.status != null ? builder.status : "running";
        this.lastOutput = builder.lastOutput != null ? builder.lastOutput : "";
        this.tmuxSessionName = builder.tmuxSessionName;
        this.tmuxWindowName = builder.tmuxWindowName;
        this.isSplitPane = builder.isSplitPane;
    }

    // =====================
    // Getters
    // =====================

    public String getName() { return name; }
    public String getTeamName() { return teamName; }
    public String getColor() { return color; }
    public boolean isPlanModeRequired() { return planModeRequired; }
    public String getTeammateId() { return teammateId; }
    public String getAgentId() { return agentId; }
    public String getAgentType() { return agentType; }
    public String getModel() { return model; }
    public String getPaneId() { return paneId; }
    public boolean isFirstTeammate() { return isFirstTeammate; }
    public long getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public String getLastOutput() { return lastOutput; }
    public String getTmuxSessionName() { return tmuxSessionName; }
    public String getTmuxWindowName() { return tmuxWindowName; }
    public boolean isSplitPane() { return isSplitPane; }

    // =====================
    // Setters
    // =====================

    public void setStatus(String status) { this.status = status; }
    public void setLastOutput(String lastOutput) { this.lastOutput = lastOutput; }
    public void setTmuxSessionName(String tmuxSessionName) { this.tmuxSessionName = tmuxSessionName; }
    public void setTmuxWindowName(String tmuxWindowName) { this.tmuxWindowName = tmuxWindowName; }
    public void setSplitPane(boolean isSplitPane) { this.isSplitPane = isSplitPane; }

    // =====================
    // 工具方法
    // =====================

    private static String generateId(String name, String teamName) {
        return name + "-" + System.currentTimeMillis() % 10000;
    }

    private static String formatAgentId(String name, String teamName) {
        return teamName != null && !teamName.isEmpty() ? name + "@" + teamName : name;
    }

    // =====================
    // Builder
    // =====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String teamName;
        private String color;
        private boolean planModeRequired;
        private String teammateId;
        private String agentId;
        private String agentType;
        private String model;
        private String paneId;
        private boolean isFirstTeammate = true;
        private long createdAt;
        private String status;
        private String lastOutput;
        private String tmuxSessionName;
        private String tmuxWindowName;
        private boolean isSplitPane;

        public Builder name(String name) { this.name = name; return this; }
        public Builder teamName(String teamName) { this.teamName = teamName; return this; }
        public Builder color(String color) { this.color = color; return this; }
        public Builder planModeRequired(boolean planModeRequired) { this.planModeRequired = planModeRequired; return this; }
        public Builder teammateId(String teammateId) { this.teammateId = teammateId; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder agentType(String agentType) { this.agentType = agentType; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder paneId(String paneId) { this.paneId = paneId; return this; }
        public Builder isFirstTeammate(boolean isFirstTeammate) { this.isFirstTeammate = isFirstTeammate; return this; }
        public Builder createdAt(long createdAt) { this.createdAt = createdAt; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder lastOutput(String lastOutput) { this.lastOutput = lastOutput; return this; }
        public Builder tmuxSessionName(String tmuxSessionName) { this.tmuxSessionName = tmuxSessionName; return this; }
        public Builder tmuxWindowName(String tmuxWindowName) { this.tmuxWindowName = tmuxWindowName; return this; }
        public Builder isSplitPane(boolean isSplitPane) { this.isSplitPane = isSplitPane; return this; }

        public TeammateInfo build() {
            return new TeammateInfo(this);
        }
    }

    @Override
    public String toString() {
        return "TeammateInfo{" +
                "name='" + name + '\'' +
                ", teamName='" + teamName + '\'' +
                ", agentId='" + agentId + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
