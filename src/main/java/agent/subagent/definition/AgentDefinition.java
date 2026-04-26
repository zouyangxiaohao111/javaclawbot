package agent.subagent.definition;

import java.util.List;
import java.util.function.Supplier;

/**
 * 代理定义
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/loadAgentsDir.ts - AgentDefinition types
 *
 * 完整字段对应 BaseAgentDefinition:
 * - agentType: 代理类型标识
 * - whenToUse: 描述何时使用
 * - tools: 允许的工具 ['*'] 表示全部
 * - disallowedTools: 禁用的工具
 * - skills: Skill names to preload (comma-separated frontmatter)
 * - mcpServers: MCP servers specific to this agent
 * - hooks: Session-scoped hooks registered when agent starts
 * - color: Agent color name
 * - model: 模型：sonnet, opus, haiku, inherit
 * - effort: Effort level
 * - permissionMode: 权限模式
 * - maxTurns: 最大轮次
 * - filename: Original filename without .md extension
 * - baseDir: 基础目录
 * - criticalSystemReminder_EXPERIMENTAL: 关键系统提醒
 * - requiredMcpServers: MCP server name patterns required
 * - background: 是否默认后台运行
 * - initialPrompt: Prepended to the first user turn
 * - memory: Persistent memory scope
 * - isolation: 隔离模式
 * - omitClaudeMd: 是否在上下文构建时省略 CLAUDE.md
 */
public class AgentDefinition {
    /** 代理类型标识 */
    protected String agentType;

    /** 描述何时使用 */
    protected String whenToUse;

    /** 允许的工具 ['*'] 表示全部 */
    protected List<String> tools;

    /** 禁用的工具 */
    protected List<String> disallowedTools;

    /** Skill names to preload */
    protected List<String> skills;

    /** MCP servers specific to this agent */
    protected List<String> mcpServers;

    /** Session-scoped hooks registered when agent starts */
    protected Object hooks;

    /** Agent color name */
    protected String color;

    /** 模型：sonnet, opus, haiku, inherit */
    protected String model;

    /** Effort level */
    protected String effort;

    /** 权限模式 */
    protected PermissionMode permissionMode;

    /** 最大轮次 */
    protected int maxTurns;

    /** Original filename without .md extension */
    protected String filename;

    /** 基础目录 */
    protected String baseDir;

    /** 关键系统提醒，在每个用户轮次重新注入 */
    protected String criticalSystemReminder_EXPERIMENTAL;

    /** MCP server name patterns that must be configured */
    protected List<String> requiredMcpServers;

    /** 是否默认后台运行 */
    protected boolean background;

    /** Prepended to the first user turn */
    protected String initialPrompt;

    /** Persistent memory scope */
    protected String memory;

    /** 隔离模式 */
    protected Isolation isolation;

    /** 是否只读（不能编辑文件） */
    protected boolean readOnly;

    /** 是否在上下文构建时省略 CLAUDE.md */
    protected boolean omitClaudeMd;

    /** 待处理的快照更新 */
    protected String pendingSnapshotUpdate;

    /** 系统提示词构建器 */
    protected Supplier<String> getSystemPrompt;

    /** 来源：built-in, plugin, userSettings, projectSettings, flagSettings */
    protected String source;

    // =====================
    // Getters
    // =====================

    public String getAgentType() { return agentType; }
    public String getWhenToUse() { return whenToUse; }
    public List<String> getTools() { return tools; }
    public List<String> getDisallowedTools() { return disallowedTools; }
    public List<String> getSkills() { return skills; }
    public List<String> getMcpServers() { return mcpServers; }
    public Object getHooks() { return hooks; }
    public String getColor() { return color; }
    public String getModel() { return model; }
    public String getEffort() { return effort; }
    public PermissionMode getPermissionMode() { return permissionMode; }
    public int getMaxTurns() { return maxTurns; }
    public String getFilename() { return filename; }
    public String getBaseDir() { return baseDir; }
    public String getCriticalSystemReminder_EXPERIMENTAL() { return criticalSystemReminder_EXPERIMENTAL; }
    public List<String> getRequiredMcpServers() { return requiredMcpServers; }
    public boolean isBackground() { return background; }
    public String getInitialPrompt() { return initialPrompt; }
    public String getMemory() { return memory; }
    public Isolation getIsolation() { return isolation; }
    public boolean isReadOnly() { return readOnly; }
    public boolean isOmitClaudeMd() { return omitClaudeMd; }
    public String getPendingSnapshotUpdate() { return pendingSnapshotUpdate; }

    public String getSystemPrompt() {
        return getSystemPrompt != null ? getSystemPrompt.get() : "";
    }

    public Supplier<String> getSystemPromptSupplier() { return getSystemPrompt; }
    public String getSource() { return source; }

    // =====================
    // Setters
    // =====================

    public void setAgentType(String agentType) { this.agentType = agentType; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public void setDisallowedTools(List<String> disallowedTools) { this.disallowedTools = disallowedTools; }
    public void setSkills(List<String> skills) { this.skills = skills; }
    public void setMcpServers(List<String> mcpServers) { this.mcpServers = mcpServers; }
    public void setHooks(Object hooks) { this.hooks = hooks; }
    public void setColor(String color) { this.color = color; }
    public void setModel(String model) { this.model = model; }
    public void setEffort(String effort) { this.effort = effort; }
    public void setPermissionMode(PermissionMode permissionMode) { this.permissionMode = permissionMode; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    public void setFilename(String filename) { this.filename = filename; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public void setCriticalSystemReminder_EXPERIMENTAL(String criticalSystemReminder_EXPERIMENTAL) { this.criticalSystemReminder_EXPERIMENTAL = criticalSystemReminder_EXPERIMENTAL; }
    public void setRequiredMcpServers(List<String> requiredMcpServers) { this.requiredMcpServers = requiredMcpServers; }
    public void setBackground(boolean background) { this.background = background; }
    public void setInitialPrompt(String initialPrompt) { this.initialPrompt = initialPrompt; }
    public void setMemory(String memory) { this.memory = memory; }
    public void setIsolation(Isolation isolation) { this.isolation = isolation; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public void setOmitClaudeMd(boolean omitClaudeMd) { this.omitClaudeMd = omitClaudeMd; }
    public void setPendingSnapshotUpdate(String pendingSnapshotUpdate) { this.pendingSnapshotUpdate = pendingSnapshotUpdate; }
    public void setGetSystemPrompt(Supplier<String> getSystemPrompt) { this.getSystemPrompt = getSystemPrompt; }
    public void setSource(String source) { this.source = source; }

    // =====================
    // Utility Methods
    // =====================

    /**
     * 判断是否为内置代理
     */
    public boolean isBuiltIn() {
        return "built-in".equals(source);
    }

    /**
     * 判断是否有全部工具权限
     */
    public boolean hasWildcardTools() {
        return tools != null && (tools.size() == 1 && "*".equals(tools.get(0)));
    }

    /**
     * 判断是否需要特定的 MCP 服务器
     * @param availableServers 可用的 MCP 服务器列表
     * @return 是否满足要求
     */
    public boolean hasRequiredMcpServers(List<String> availableServers) {
        if (requiredMcpServers == null || requiredMcpServers.isEmpty()) {
            return true;
        }
        if (availableServers == null || availableServers.isEmpty()) {
            return false;
        }
        // 每个 required pattern 必须匹配至少一个可用的 server（不区分大小写）
        for (String pattern : requiredMcpServers) {
            boolean found = availableServers.stream()
                    .anyMatch(server -> server.toLowerCase().contains(pattern.toLowerCase()));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    // =====================
    // Nested Types
    // =====================

    /**
     * 隔离模式
     *
     * 对应 TypeScript: 'worktree' | 'remote'
     */
    public enum Isolation {
        /** Git Worktree 隔离 */
        WORKTREE,

        /** 远程执行（CCR，仅 ant 用户可用） */
        REMOTE
    }

    // =====================
    // Builder Pattern
    // =====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AgentDefinition instance = new AgentDefinition();

        public Builder agentType(String agentType) { instance.agentType = agentType; return this; }
        public Builder whenToUse(String whenToUse) { instance.whenToUse = whenToUse; return this; }
        public Builder tools(List<String> tools) { instance.tools = tools; return this; }
        public Builder disallowedTools(List<String> disallowedTools) { instance.disallowedTools = disallowedTools; return this; }
        public Builder skills(List<String> skills) { instance.skills = skills; return this; }
        public Builder mcpServers(List<String> mcpServers) { instance.mcpServers = mcpServers; return this; }
        public Builder hooks(Object hooks) { instance.hooks = hooks; return this; }
        public Builder color(String color) { instance.color = color; return this; }
        public Builder model(String model) { instance.model = model; return this; }
        public Builder effort(String effort) { instance.effort = effort; return this; }
        public Builder permissionMode(PermissionMode permissionMode) { instance.permissionMode = permissionMode; return this; }
        public Builder maxTurns(int maxTurns) { instance.maxTurns = maxTurns; return this; }
        public Builder filename(String filename) { instance.filename = filename; return this; }
        public Builder baseDir(String baseDir) { instance.baseDir = baseDir; return this; }
        public Builder criticalSystemReminder_EXPERIMENTAL(String criticalSystemReminder_EXPERIMENTAL) { instance.criticalSystemReminder_EXPERIMENTAL = criticalSystemReminder_EXPERIMENTAL; return this; }
        public Builder requiredMcpServers(List<String> requiredMcpServers) { instance.requiredMcpServers = requiredMcpServers; return this; }
        public Builder background(boolean background) { instance.background = background; return this; }
        public Builder initialPrompt(String initialPrompt) { instance.initialPrompt = initialPrompt; return this; }
        public Builder memory(String memory) { instance.memory = memory; return this; }
        public Builder isolation(Isolation isolation) { instance.isolation = isolation; return this; }
        public Builder readOnly(boolean readOnly) { instance.readOnly = readOnly; return this; }
        public Builder omitClaudeMd(boolean omitClaudeMd) { instance.omitClaudeMd = omitClaudeMd; return this; }
        public Builder pendingSnapshotUpdate(String pendingSnapshotUpdate) { instance.pendingSnapshotUpdate = pendingSnapshotUpdate; return this; }
        public Builder getSystemPrompt(Supplier<String> getSystemPrompt) { instance.getSystemPrompt = getSystemPrompt; return this; }
        public Builder source(String source) { instance.source = source; return this; }

        public AgentDefinition build() {
            return instance;
        }
    }
}
