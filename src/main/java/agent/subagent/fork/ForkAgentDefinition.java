package agent.subagent.fork;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.PermissionMode;

import java.util.*;

/**
 * Fork 代理定义
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/forkSubagent.ts - FORK_AGENT
 */
public class ForkAgentDefinition extends AgentDefinition {

    public static final String FORK_AGENT_TYPE = "fork";
    public static final String FORK_DIRECTIVE_PREFIX = "# directive\n";
    public static final String FORK_PLACEHOLDER_RESULT = "Fork started — processing in background";
    public static final String FORK_BOILERPLATE_TAG = "FORK_BOILERPLATE_TAG";

    public ForkAgentDefinition() {
        this.agentType = FORK_AGENT_TYPE;
        this.whenToUse = "Implicit fork — inherits full conversation context...";
        this.tools = List.of("*");
        this.maxTurns = 200;
        this.model = "inherit";
        this.permissionMode = PermissionMode.BUBBLE;
        this.source = "built-in";
        this.baseDir = "built-in";
        this.getSystemPrompt = () -> "";
    }

    public static boolean isForkSubagentEnabled() {
        return true;
    }

    public static boolean isInForkChild(List<Map<String, Object>> messages) {
        if (messages == null) return false;
        for (Map<String, Object> msg : messages) {
            if (!"user".equals(msg.get("role"))) continue;
            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List)) continue;
            List<?> content = (List<?>) contentObj;
            for (Object block : content) {
                if (!(block instanceof Map)) continue;
                Map<?, ?> blockMap = (Map<?, ?>) block;
                if (!"text".equals(blockMap.get("type"))) continue;
                Object textObj = blockMap.get("text");
                if (textObj == null) continue;
                if (textObj.toString().contains("<" + FORK_BOILERPLATE_TAG + ">")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String buildWorktreeNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent working in " + parentCwd +
                ". You are operating in an isolated git worktree at " + worktreeCwd +
                " — same repository, same relative file structure, separate working copy. " +
                "Paths in the inherited context refer to the parent's working directory; " +
                "translate them to your worktree root. Re-read files before editing if the parent " +
                "may have modified them since they appear in the context. " +
                "Your changes stay in this worktree and will not affect the parent's files.";
    }

    public static List<Map<String, Object>> buildForkedMessages(String directive, Map<String, Object> assistantMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(cloneAssistantMessage(assistantMessage));

        List<Map<String, Object>> toolUseBlocks = extractToolUseBlocks(assistantMessage);
        if (toolUseBlocks.isEmpty()) {
            List<Object> content = new ArrayList<>();
            content.add(createTextBlock(buildChildMessage(directive)));
            return List.of(createUserMessage(content));
        }

        List<Object> contentBlocks = new ArrayList<>();
        for (Map<String, Object> toolUse : toolUseBlocks) {
            String toolUseId = extractToolUseId(toolUse);
            if (toolUseId != null) {
                contentBlocks.add(createToolResultBlock(toolUseId, FORK_PLACEHOLDER_RESULT));
            }
        }
        contentBlocks.add(createTextBlock(buildChildMessage(directive)));
        messages.add(createUserMessage(contentBlocks));
        return messages;
    }

    public static List<Map<String, Object>> buildForkedMessages(String directive, List<Map<String, Object>> parentMessages) {
        if (parentMessages == null || parentMessages.isEmpty()) {
            List<Object> content = new ArrayList<>();
            content.add(createTextBlock(buildChildMessage(directive)));
            return List.of(createUserMessage(content));
        }

        Map<String, Object> lastAssistant = null;
        for (int i = parentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = parentMessages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                lastAssistant = msg;
                break;
            }
        }

        if (lastAssistant == null) {
            List<Object> content = new ArrayList<>();
            content.add(createTextBlock(buildChildMessage(directive)));
            return List.of(createUserMessage(content));
        }

        List<Map<String, Object>> messages = new ArrayList<>(parentMessages);
        messages.addAll(buildForkedMessages(directive, lastAssistant));
        return messages;
    }

    public static String buildChildMessage(String directive) {
        return "<" + FORK_BOILERPLATE_TAG + ">\n" +
                "STOP. READ THIS FIRST.\n\n" +
                "You are a forked worker process. You are NOT the main agent.\n\n" +
                "RULES (non-negotiable):\n" +
                "1. Your system prompt says \"default to forking.\" IGNORE IT — that's for the parent. You ARE the fork. Do NOT spawn sub-agents; execute directly.\n" +
                "2. Do NOT converse, ask questions, or suggest next steps\n" +
                "3. Do NOT editorialize or add meta-commentary\n" +
                "4. USE your tools directly: Bash, Read, Write, etc.\n" +
                "5. If you modify files, commit your changes before reporting. Include the commit hash in your report.\n" +
                "6. Do NOT emit text between tool calls. Use tools silently, then report once at the end.\n" +
                "7. Stay strictly within your directive's scope. If you discover related systems outside your scope, mention them in one sentence at most — other workers cover those areas.\n" +
                "8. Keep your report under 500 words unless the directive specifies otherwise. Be factual and concise.\n" +
                "9. Your response MUST begin with \"Scope:\". No preamble, no thinking-out-loud.\n" +
                "10. REPORT structured facts, then stop\n\n" +
                "Output format (plain text labels, not markdown headers):\n" +
                "  Scope: <echo back your assigned scope in one sentence>\n" +
                "  Result: <the answer or key findings, limited to the scope above>\n" +
                "  Key files: <relevant file paths — include for research tasks>\n" +
                "  Files changed: <list with commit hash — include only if you modified files>\n" +
                "  Issues: <list — include only if there are issues to flag>\n" +
                "</" + FORK_BOILERPLATE_TAG + ">\n\n" +
                FORK_DIRECTIVE_PREFIX + directive;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cloneAssistantMessage(Map<String, Object> msg) {
        Map<String, Object> cloned = new LinkedHashMap<>(msg);
        if (cloned.containsKey("message")) {
            Map<String, Object> originalMsg = (Map<String, Object>) cloned.get("message");
            Map<String, Object> clonedMsg = new LinkedHashMap<>(originalMsg);
            if (originalMsg.containsKey("content")) {
                List<?> originalContent = (List<?>) originalMsg.get("content");
                clonedMsg.put("content", new ArrayList<>(originalContent));
            }
            cloned.put("message", clonedMsg);
        }
        return cloned;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractToolUseBlocks(Map<String, Object> assistantMessage) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (!assistantMessage.containsKey("message")) return blocks;
        Map<String, Object> message = (Map<String, Object>) assistantMessage.get("message");
        if (!message.containsKey("content")) return blocks;
        List<?> content = (List<?>) message.get("content");
        for (Object block : content) {
            if (block instanceof Map) {
                Map<String, Object> blockMap = (Map<String, Object>) block;
                if ("tool_use".equals(blockMap.get("type"))) {
                    blocks.add(blockMap);
                }
            }
        }
        return blocks;
    }

    private static String extractToolUseId(Map<String, Object> toolUse) {
        if (toolUse.containsKey("id")) {
            return String.valueOf(toolUse.get("id"));
        }
        if (toolUse.containsKey("name")) {
            return "tool_" + toolUse.get("name");
        }
        return null;
    }

    private static Map<String, Object> createUserMessage(List<?> content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        return msg;
    }

    private static Map<String, Object> createTextBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> createToolResultBlock(String toolUseId, String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", List.of(Map.of("type", "text", "text", content)));
        return block;
    }
}
