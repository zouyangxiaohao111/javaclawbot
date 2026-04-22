package agent.subagent.definition;

import java.util.List;
import java.util.function.Supplier;

/**
 * 代理定义
 *
 * 定义代理的类型、工具权限、模型选择等配置
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

    /** 模型：sonnet, opus, haiku, inherit */
    protected String model;

    /** 权限模式 */
    protected PermissionMode permissionMode;

    /** 最大轮次 */
    protected int maxTurns;

    /** 是否默认后台运行 */
    protected boolean background;

    /** 隔离模式 */
    protected Isolation isolation;

    /** 系统提示词构建器 */
    protected Supplier<String> getSystemPrompt;

    /** 来源：built-in, user, plugin */
    protected String source;

    /** 基础目录 */
    protected String baseDir;

    /** 是否只读（不能编辑文件） */
    protected boolean readOnly;

    /** 是否在上下文构建时省略 CLAUDE.md */
    protected boolean omitClaudeMd;

    public String getAgentType() { return agentType; }
    public String getWhenToUse() { return whenToUse; }
    public List<String> getTools() { return tools; }
    public List<String> getDisallowedTools() { return disallowedTools; }
    public String getModel() { return model; }
    public PermissionMode getPermissionMode() { return permissionMode; }
    public int getMaxTurns() { return maxTurns; }
    public boolean isBackground() { return background; }
    public Isolation getIsolation() { return isolation; }
    public String getSystemPrompt() {
        return getSystemPrompt != null ? getSystemPrompt.get() : "";
    }
    public Supplier<String> getSystemPromptSupplier() { return getSystemPrompt; }
    public String getSource() { return source; }
    public String getBaseDir() { return baseDir; }
    public boolean isReadOnly() { return readOnly; }
    public boolean isOmitClaudeMd() { return omitClaudeMd; }

    // Setters for builder pattern
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public void setDisallowedTools(List<String> disallowedTools) { this.disallowedTools = disallowedTools; }
    public void setModel(String model) { this.model = model; }
    public void setPermissionMode(PermissionMode permissionMode) { this.permissionMode = permissionMode; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
    public void setBackground(boolean background) { this.background = background; }
    public void setIsolation(Isolation isolation) { this.isolation = isolation; }
    public void setGetSystemPrompt(Supplier<String> getSystemPrompt) { this.getSystemPrompt = getSystemPrompt; }
    public void setSource(String source) { this.source = source; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public void setOmitClaudeMd(boolean omitClaudeMd) { this.omitClaudeMd = omitClaudeMd; }

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
     * 隔离模式
     */
    public enum Isolation {
        /** Git Worktree 隔离 */
        WORKTREE,

        /** 远程执行（CCR） */
        REMOTE
    }
}
