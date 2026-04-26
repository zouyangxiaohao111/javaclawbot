package agent.subagent.builtin.general;

import agent.subagent.definition.AgentDefinition;

/**
 * General Purpose Agent 定义
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/generalPurposeAgent.ts
 */
public class GeneralPurposeAgent {

    public static final String AGENT_TYPE = "general-purpose";

    private static final String SHARED_PREFIX =
            "You are an agent for Claude Code, Anthropic's official CLI for Claude. " +
            "Given the user's message, you should use the tools available to complete the task. " +
            "Complete the task fully—don't gold-plate, but don't leave it half-done.";

    private static final String SHARED_GUIDELINES =
            "Your strengths:\n" +
            "- Searching for code, configurations, and patterns across large codebases\n" +
            "- Analyzing multiple files to understand system architecture\n" +
            "- Investigating complex questions that require exploring many files\n" +
            "- Performing multi-step research tasks\n" +
            "\n" +
            "Guidelines:\n" +
            "- For file searches: search broadly when you don't know where something lives. Use Read when you know the specific file path.\n" +
            "- For analysis: Start broad and narrow down. Use multiple search strategies if the first doesn't yield results.\n" +
            "- Be thorough: Check multiple locations, consider different naming conventions, look for related files.\n" +
            "- NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.\n" +
            "- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested.";

    // Note: absolute-path + emoji guidance is appended by enhanceSystemPromptWithEnvDetails.
    private static final String SYSTEM_PROMPT =
            SHARED_PREFIX + " When you complete the task, respond with a concise report covering what was done and any key findings — the caller will relay this to the user, so it only needs the essentials.\n\n" +
            SHARED_GUIDELINES;

    /**
     * General Purpose Agent 定义
     * 对应 TypeScript: export const GENERAL_PURPOSE_AGENT: BuiltInAgentDefinition
     *
     * tools: ['*'] 表示允许所有工具（只过滤 ALL_AGENT_DISALLOWED_TOOLS）
     */
    public static final AgentDefinition AGENT_DEFINITION = AgentDefinition.builder()
            .agentType(AGENT_TYPE)
            .whenToUse("General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks. When you are searching for a keyword or file and are not confident that you will find the right match in the first few tries use this agent to perform the search for you.")
            .tools(java.util.List.of("*"))  // wildcard = 所有工具
            .source("built-in")
            .baseDir("built-in")
            // model is intentionally omitted - uses getDefaultSubagentModel()
            .getSystemPrompt(() -> SYSTEM_PROMPT)
            .build();

    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public static String getAgentType() {
        return AGENT_TYPE;
    }

    /**
     * 获取 General Purpose Agent 定义
     */
    public static AgentDefinition getAgentDefinition() {
        return AGENT_DEFINITION;
    }
}