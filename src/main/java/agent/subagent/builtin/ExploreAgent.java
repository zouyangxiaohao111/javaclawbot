package agent.subagent.builtin;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.PermissionMode;

import java.util.List;

/**
 * 探索代理
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/exploreAgent.ts - EXPLORE_AGENT
 *
 * 只读搜索代理，用于快速查找文件、搜索代码
 */
public class ExploreAgent {

    /** 最少查询次数常量 */
    public static final int EXPLORE_AGENT_MIN_QUERIES = 3;

    private static final String SYSTEM_PROMPT =
        "You are a file search specialist for Claude Code, Anthropic's official CLI for Claude. " +
        "You excel at thoroughly navigating and exploring codebases.\n\n" +
        "=== CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===\n" +
        "This is a READ-ONLY exploration task. You are STRICTLY PROHIBITED from:\n" +
        "- Creating new files (no Write, touch, or file creation of any kind)\n" +
        "- Modifying existing files (no Edit operations)\n" +
        "- Deleting files (no rm or deletion)\n" +
        "- Moving or copying files (no mv or cp)\n" +
        "- Creating temporary files anywhere, including /tmp\n" +
        "- Using redirect operators (>, >>, |) or heredocs to write to files\n" +
        "- Running ANY commands that change system state\n\n" +
        "Your role is EXCLUSIVELY to search and analyze existing code. You do NOT have access to file editing tools - attempting to edit files will fail.\n\n" +
        "Your strengths:\n" +
        "- Rapidly finding files using glob patterns\n" +
        "- Searching code and text with powerful regex patterns\n" +
        "- Reading and analyzing file contents\n\n" +
        "Guidelines:\n" +
        "- Use GlobTool for broad file pattern matching\n" +
        "- Use GrepTool for searching file contents with regex\n" +
        "- Use Read when you know the specific file path you need to read\n" +
        "- Use Bash ONLY for read-only operations (ls, git status, git log, git diff, find, grep, cat, head, tail)\n" +
        "- NEVER use Bash for: mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install, or any file creation/modification\n" +
        "- Adapt your search approach based on the thoroughness level specified by the caller\n" +
        "- Communicate your final report directly as a regular message - do NOT attempt to create files\n\n" +
        "NOTE: You are meant to be a fast agent that returns output as quickly as possible. In order to achieve this you must:\n" +
        "- Make efficient use of the tools that you have at your disposal: be smart about how you search for files and implementations\n" +
        "- Wherever possible you should try to spawn multiple parallel tool calls for grepping and reading files\n\n" +
        "Complete the user's search request efficiently and report your findings clearly.";

    /**
     * 获取探索代理定义
     * 对应: EXPLORE_AGENT
     *
     * 注意: model 根据环境变量 USER_TYPE 决定:
     * - ant 用户: inherit (使用主代理的模型)
     * - 外部用户: haiku (为了速度)
     */
    public static AgentDefinition get() {
        AgentDefinition agent = new AgentDefinition();
        agent.setAgentType("Explore");
        agent.setWhenToUse(
            "Fast agent specialized for exploring codebases. Use this when you need to quickly " +
            "find files by patterns (eg. \"src/components/**/*.tsx\"), search code for keywords " +
            "(eg. \"API endpoints\"), or answer questions about the codebase (eg. \"how do API " +
            "endpoints work?\"). When calling this agent, specify the desired thoroughness level: " +
            "\"quick\" for basic searches, \"medium\" for moderate exploration, or \"very thorough\" " +
            "for comprehensive analysis across multiple locations and naming conventions.");
        // 注意: tools 未设置，意味着全部工具可用（disallowedTools 会过滤）
        agent.setDisallowedTools(List.of(
            "Agent",           // 不能嵌套调用 Agent
            "ExitPlanMode",    // 不能退出 Plan 模式
            "Edit",            // 不能编辑
            "Write",           // 不能写文件
            "NotebookEdit"     // 不能编辑 Notebook
        ));
        // 根据环境变量决定模型
        // ant 用户使用 inherit，外部用户使用 haiku
        agent.setModel(getModelForEnvironment());
        agent.setPermissionMode(PermissionMode.PLAN);
        agent.setMaxTurns(50);
        agent.setSource("built-in");
        agent.setBaseDir("built-in");
        // Explore 是只读搜索代理，不需要 CLAUDE.md 的规则
        agent.setOmitClaudeMd(true);
        agent.setGetSystemPrompt(() -> SYSTEM_PROMPT);
        return agent;
    }

    /**
     * 根据环境变量获取模型
     * 对应 TypeScript: process.env.USER_TYPE === 'ant' ? 'inherit' : 'haiku'
     */
    private static String getModelForEnvironment() {
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            return "inherit";  // ant 用户继承主代理模型
        }
        return "haiku";  // 外部用户使用快速模型
    }
}
