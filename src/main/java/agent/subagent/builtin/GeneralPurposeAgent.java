package agent.subagent.builtin;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.PermissionMode;

import java.util.List;

/**
 * 通用代理
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/generalPurposeAgent.ts - GENERAL_PURPOSE_AGENT
 *
 * 用于通用任务、研究复杂问题、搜索代码、执行多步骤任务
 */
public class GeneralPurposeAgent {

    private static final String SHARED_PREFIX =
        "You are an agent for Claude Code, Anthropic's official CLI for Claude. " +
        "Given the user's message, you should use the tools available to complete the task. " +
        "Complete the task fully—don't gold-plate, but don't leave it half-done.";

    private static final String SHARED_GUIDELINES =
        "Your strengths:\n" +
        "- Searching for code, configurations, and patterns across large codebases\n" +
        "- Analyzing multiple files to understand system architecture\n" +
        "- Investigating complex questions that require exploring many files\n" +
        "- Performing multi-step research tasks\n\n" +
        "Guidelines:\n" +
        "- For file searches: search broadly when you don't know where something lives. Use Read when you know the specific file path.\n" +
        "- For analysis: Start broad and narrow down. Use multiple search strategies if the first doesn't yield results.\n" +
        "- Be thorough: Check multiple locations, consider different naming conventions, look for related files.\n" +
        "- NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.\n" +
        "- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested.";

    /**
     * 获取通用代理定义
     * 对应: GENERAL_PURPOSE_AGENT
     */
    public static AgentDefinition get() {
        AgentDefinition agent = new AgentDefinition();
        agent.setAgentType("general-purpose");
        agent.setWhenToUse(
            "General-purpose agent for researching complex questions, searching for code, " +
            "and executing multi-step tasks. When you are searching for a keyword or file " +
            "and are not confident that you will find the right match in the first few tries " +
            "use this agent to perform the search for you.");
        agent.setTools(List.of("*"));  // 全部工具
        agent.setDisallowedTools(null);  // 无禁用工具
        agent.setModel(null);  // 使用默认模型（继承）
        agent.setPermissionMode(PermissionMode.ACCEPT_EDITS);
        agent.setMaxTurns(200);
        agent.setSource("built-in");
        agent.setBaseDir("built-in");
        agent.setGetSystemPrompt(() -> getSystemPrompt());
        return agent;
    }

    /**
     * 获取通用代理的系统提示词
     */
    private static String getSystemPrompt() {
        return SHARED_PREFIX +
            " When you complete the task, respond with a concise report covering " +
            "what was done and any key findings — the caller will relay this to the user, " +
            "so it only needs the essentials.\n\n" +
            SHARED_GUIDELINES;
    }
}
