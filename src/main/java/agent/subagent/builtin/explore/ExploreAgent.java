package agent.subagent.builtin.explore;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.PermissionMode;

/**
 * Explore Agent 定义
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/exploreAgent.ts
 *
 * Explore 是一个只读搜索代理，用于研究代码库。
 */
public class ExploreAgent {

    public static final String AGENT_TYPE = "Explore";

    // Note: embedded search tools detection is simplified for Java version
    // In TypeScript: const embedded = hasEmbeddedSearchTools()
    private static final boolean EMBEDDED = false;

    private static final String GLOB_GUIDANCE = EMBEDDED
            ? "- Use `find` via Bash for broad file pattern matching"
            : "- Use Glob for broad file pattern matching";

    private static final String GREP_GUIDANCE = EMBEDDED
            ? "- Use `grep` via Bash for searching file contents with regex"
            : "- Use Grep for searching file contents with regex";

    private static final String SYSTEM_PROMPT = "You are a file search specialist for Claude Code, Anthropic's official CLI for Claude. You excel at thoroughly navigating and exploring codebases.\n\n" +
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
            GLOB_GUIDANCE + "\n" +
            GREP_GUIDANCE + "\n" +
            "- Use Read when you know the specific file path you need to read\n" +
            "- Use Bash ONLY for read-only operations (ls, git status, git log, git diff, find" + (EMBEDDED ? ", grep" : "") + ", cat, head, tail)\n" +
            "- NEVER use Bash for: mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install, or any file creation/modification\n" +
            "- Adapt your search approach based on the thoroughness level specified by the caller\n" +
            "- Communicate your final report directly as a regular message - do NOT attempt to create files\n\n" +
            "NOTE: You are meant to be a fast agent that returns output as quickly as possible. In order to achieve this you must:\n" +
            "- Make efficient use of the tools that you have at your disposal: be smart about how you search for files and implementations\n" +
            "- Wherever possible you should try to spawn multiple parallel tool calls for grepping and reading files\n\n" +
            "Complete the user's search request efficiently and report your findings clearly.";

    /**
     * Explore Agent 定义
     * 对应 TypeScript: export const EXPLORE_AGENT: BuiltInAgentDefinition
     */
    public static final AgentDefinition AGENT_DEFINITION = AgentDefinition.builder()
            .agentType(AGENT_TYPE)
            .whenToUse("Fast agent specialized for exploring codebases. Use this when you need to quickly find files by patterns (eg. \"src/components/**/*.tsx\"), search code for keywords (eg. \"API endpoints\"), or answer questions about the codebase (eg. \"how do API endpoints work?\"). When calling this agent, specify the desired thoroughness level: \"quick\" for basic searches, \"medium\" for moderate exploration, or \"very thorough\" for comprehensive analysis across multiple locations and naming conventions.")
            .disallowedTools(java.util.List.of(
                    "Agent",
                    "edit_file",
                    "write_file",
                    "EnterPlanMode",
                    "AskUserQuestion"
            ))
            .source("built-in")
            .baseDir("built-in")
            // Ants get inherit to use the main agent's model; external users get haiku for speed
            // Note: For ants, getAgentModel() checks tengu_explore_agent GrowthBook flag at runtime
            //.model(System.getenv("USER_TYPE") != null && "ant".equals(System.getenv("USER_TYPE")) ? "inherit" : "haiku")
            .model(null)
            // Explore is a fast read-only search agent — it doesn't need commit/PR/lint
            // rules from CLAUDE.md. The main agent has full context and interprets results.
            .omitClaudeMd(true)
            .getSystemPrompt(() -> SYSTEM_PROMPT)
            .build();

    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public static String getAgentType() {
        return AGENT_TYPE;
    }

    public static String getModel() {
        // 对应 TypeScript: process.env.USER_TYPE === 'ant' ? 'inherit' : 'haiku'
        return System.getenv("USER_TYPE") != null && "ant".equals(System.getenv("USER_TYPE")) ? "inherit" : "haiku";
    }

    /**
     * 获取 Explore Agent 定义
     */
    public static AgentDefinition getAgentDefinition() {
        return AGENT_DEFINITION;
    }
}