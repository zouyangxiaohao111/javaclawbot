package agent.subagent.spawn;

/**
 * Spawn 配置
 */
public class SpawnConfig {

    private String teamName;
    private String name;
    private String prompt;
    private boolean background;
    private String workingDirectory;
    private String agentType = "general-purpose";
    private String model;

    // Getters and Setters
    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public boolean isBackground() { return background; }
    public void setBackground(boolean background) { this.background = background; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    /**
     * 获取会话名称（用于 tmux/iTerm2）
     */
    public String getSessionName() {
        if (teamName != null && name != null) {
            return teamName + "-" + name;
        }
        return name != null ? name : "default";
    }
}
