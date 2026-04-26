package agent.subagent.constants;

import java.util.Set;

/**
 * 代理工具常量
 *
 * 对应 Open-ClaudeCode: src/constants/tools.ts
 *
 * 定义所有代理相关的工具常量：
 * - ALL_AGENT_DISALLOWED_TOOLS: 所有代理不允许的工具
 * - CUSTOM_AGENT_DISALLOWED_TOOLS: 自定义代理不允许的工具
 * - ASYNC_AGENT_ALLOWED_TOOLS: 异步代理允许的工具
 * - IN_PROCESS_TEAMMATE_ALLOWED_TOOLS: 进程内队友允许的工具
 */
public class AgentToolConstants {

    /**
     * 所有代理不允许的工具
     * 对应: ALL_AGENT_DISALLOWED_TOOLS
     *
     * 注意: Agent 工具根据 USER_TYPE 决定是否允许：
     * - ant 用户允许使用 Agent 工具（支持嵌套代理）
     * - 其他用户不允许使用 Agent 工具
     */
    public static Set<String> getAllAgentDisallowedTools() {
        Set<String> disallowed = new java.util.HashSet<>();
        disallowed.add("TaskOutput");
        disallowed.add("ExitPlanMode");
        disallowed.add("EnterPlanMode");
        // Agent tool is disallowed for non-ant users
        if (!"ant".equals(System.getenv("USER_TYPE"))) {
            disallowed.add("Agent");
        }
        disallowed.add("AskUserQuestion");
        disallowed.add("TaskStopTool");
        return disallowed;
    }

    /**
     * 所有代理不允许的工具（缓存）
     */
    private static Set<String> ALL_AGENT_DISALLOWED_TOOLS_CACHE = null;

    /**
     * 获取所有代理不允许的工具（带缓存）
     * 对应: ALL_AGENT_DISALLOWED_TOOLS
     */
    public static Set<String> getAllAgentDisallowedToolsCached() {
        if (ALL_AGENT_DISALLOWED_TOOLS_CACHE == null) {
            ALL_AGENT_DISALLOWED_TOOLS_CACHE = getAllAgentDisallowedTools();
        }
        return ALL_AGENT_DISALLOWED_TOOLS_CACHE;
    }

    /**
     * 自定义代理不允许的工具
     * 对应: CUSTOM_AGENT_DISALLOWED_TOOLS
     * 自定义代理比内置代理有更多限制
     */
    public static Set<String> getCustomAgentDisallowedTools() {
        Set<String> disallowed = new java.util.HashSet<>(getAllAgentDisallowedToolsCached());
        // 自定义代理额外禁止 agent 工具（即使 ant 用户）
        disallowed.add("agent");
        return disallowed;
    }

    /**
     * 自定义代理不允许的工具（缓存）
     */
    private static Set<String> CUSTOM_AGENT_DISALLOWED_TOOLS_CACHE = null;

    /**
     * 获取自定义代理不允许的工具（带缓存）
     */
    public static Set<String> getCustomAgentDisallowedToolsCached() {
        if (CUSTOM_AGENT_DISALLOWED_TOOLS_CACHE == null) {
            CUSTOM_AGENT_DISALLOWED_TOOLS_CACHE = getCustomAgentDisallowedTools();
        }
        return CUSTOM_AGENT_DISALLOWED_TOOLS_CACHE;
    }

    /**
     * 异步代理允许的工具
     * 对应: ASYNC_AGENT_ALLOWED_TOOLS
     */
    public static final Set<String> ASYNC_AGENT_ALLOWED_TOOLS = Set.of(
        "read_file",
        "web_search",
        "TodoWrite",
        "Grep",
        "web_fetch",
        "Glob",
        "Bash",
        "powershell",
        "edit_file",
        "write_file",
        "notebook_edit",
        "skill",
        "synthetic_output",
        "tool_search",
        "enter_worktree",
        "exit_worktree"
    );

    /**
     * 进程内队友允许的工具
     * 对应: IN_PROCESS_TEAMMATE_ALLOWED_TOOLS
     */
    public static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED_TOOLS = Set.of(
        "TaskCreate",
        "TaskGet",
        "TaskList",
        "TaskUpdate",
        "message",
        "cron",
        "CronCreate",
        "CronDelete",
        "CronList"
    );

    /**
     * 检查工具是否允许所有代理使用
     */
    public static boolean isAllowedForAllAgents(String toolName) {
        return !getAllAgentDisallowedToolsCached().contains(toolName);
    }

    /**
     * 检查工具是否允许自定义代理使用
     */
    public static boolean isAllowedForCustomAgents(String toolName) {
        return !getCustomAgentDisallowedToolsCached().contains(toolName);
    }

    /**
     * 检查工具是否允许异步代理使用
     */
    public static boolean isAllowedForAsyncAgents(String toolName) {
        return ASYNC_AGENT_ALLOWED_TOOLS.contains(toolName);
    }

    /**
     * 检查工具是否允许进程内队友使用
     */
    public static boolean isAllowedForInProcessTeammates(String toolName) {
        return IN_PROCESS_TEAMMATE_ALLOWED_TOOLS.contains(toolName);
    }
}
