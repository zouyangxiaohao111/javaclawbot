package agent.subagent.team.backends;

import java.util.List;

/**
 * Teammate Spawn 配置
 *
 * 对应 Open-ClaudeCode: src/utils/swarm/backends/types.ts - TeammateSpawnConfig
 *
 * 用于创建 teammate 的完整配置
 */
public class TeammateSpawnConfig {

    /** Teammate 名称 (如 "researcher", "tester") */
    private String name;

    /** 团队名称 */
    private String teamName;

    /** 分配的颜色（UI 差异化） */
    private String color;

    /** 是否需要计划模式批准 */
    private boolean planModeRequired;

    /** 发送给 teammate 的初始提示词 */
    private String prompt;

    /** 工作目录 */
    private String cwd;

    /** 使用的模型 */
    private String model;

    /** 系统提示词 */
    private String systemPrompt;

    /** 系统提示词应用模式: 'default' | 'replace' | 'append' */
    private String systemPromptMode;

    /** 可选的 git worktree 路径 */
    private String worktreePath;

    /** 父会话 ID（用于上下文链接） */
    private String parentSessionId;

    /** 授予此 teammate 的工具权限 */
    private List<String> permissions;

    /** 是否允许未列出工具的权限提示
     * 当为 false（默认）时，未列出工具自动拒绝 */
    private boolean allowPermissionPrompts;

    // =====================
    // Getters and Setters
    // =====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTeamName() { return teamName; }
    public void setTeamName(String teamName) { this.teamName = teamName; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public boolean isPlanModeRequired() { return planModeRequired; }
    public void setPlanModeRequired(boolean planModeRequired) { this.planModeRequired = planModeRequired; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getSystemPromptMode() { return systemPromptMode; }
    public void setSystemPromptMode(String systemPromptMode) { this.systemPromptMode = systemPromptMode; }

    public String getWorktreePath() { return worktreePath; }
    public void setWorktreePath(String worktreePath) { this.worktreePath = worktreePath; }

    public String getParentSessionId() { return parentSessionId; }
    public void setParentSessionId(String parentSessionId) { this.parentSessionId = parentSessionId; }

    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }

    public boolean isAllowPermissionPrompts() { return allowPermissionPrompts; }
    public void setAllowPermissionPrompts(boolean allowPermissionPrompts) { this.allowPermissionPrompts = allowPermissionPrompts; }

    // =====================
    // Builder
    // =====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final TeammateSpawnConfig config = new TeammateSpawnConfig();

        public Builder name(String name) { config.name = name; return this; }
        public Builder teamName(String teamName) { config.teamName = teamName; return this; }
        public Builder color(String color) { config.color = color; return this; }
        public Builder planModeRequired(boolean planModeRequired) { config.planModeRequired = planModeRequired; return this; }
        public Builder prompt(String prompt) { config.prompt = prompt; return this; }
        public Builder cwd(String cwd) { config.cwd = cwd; return this; }
        public Builder model(String model) { config.model = model; return this; }
        public Builder systemPrompt(String systemPrompt) { config.systemPrompt = systemPrompt; return this; }
        public Builder systemPromptMode(String systemPromptMode) { config.systemPromptMode = systemPromptMode; return this; }
        public Builder worktreePath(String worktreePath) { config.worktreePath = worktreePath; return this; }
        public Builder parentSessionId(String parentSessionId) { config.parentSessionId = parentSessionId; return this; }
        public Builder permissions(List<String> permissions) { config.permissions = permissions; return this; }
        public Builder allowPermissionPrompts(boolean allowPermissionPrompts) { config.allowPermissionPrompts = allowPermissionPrompts; return this; }

        public TeammateSpawnConfig build() {
            return config;
        }
    }
}
