package agent.subagent.fork;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.PermissionMode;

/**
 * Fork 代理定义
 *
 * 这是一个特殊的内置代理类型，用于隐式 Fork。
 * 当用户调用 Agent 工具时不指定 subagent_type 时使用。
 */
public class ForkAgentDefinition extends AgentDefinition {

    /** Fork 指令前缀 */
    public static final String FORK_DIRECTIVE_PREFIX = "# directive\n";

    /** Fork 占位符结果 - 所有 tool_use 的 placeholder 结果必须相同以共享 Cache */
    public static final String FORK_PLACEHOLDER_RESULT = "Fork started — processing in background";

    /** Fork Boilerplate 标签 */
    public static final String FORK_BOILERPLATE_TAG = "FORK_BOILERPLATE_TAG";

    public ForkAgentDefinition() {
        super.agentType = "fork";
        super.whenToUse = "Implicit fork — inherits full conversation context. Not selectable via subagent_type; triggered by omitting subagent_type when the fork experiment is active.";
        super.tools = java.util.List.of("*");  // 继承父代理的全部工具
        super.maxTurns = 200;
        super.model = "inherit";  // 继承父代理的模型
        super.permissionMode = PermissionMode.BUBBLE;  // 权限提示冒泡到父终端
        super.source = "built-in";
        super.baseDir = "built-in";
    }

    /**
     * 构建子代理消息
     *
     * Fork 子代理的消息格式：
     * 1. 完整的父代理 assistant 消息（包含所有 tool_use）
     * 2. User 消息：placeholder tool_results + per-child 指令
     *
     * 这样构造是为了最大化 Cache 命中率 - 只有最后一条消息不同。
     */
    public static java.util.List<Map<String, Object>> buildForkedMessages(
            String directive,
            Map<String, Object> assistantMessage
    ) {
        java.util.List<Map<String, Object>> messages = new java.util.ArrayList<>();

        // 1. 添加完整的 assistant 消息（克隆以避免修改原始消息）
        @SuppressWarnings("unchecked")
        Map<String, Object> clonedAssistant = new java.util.LinkedHashMap<>(assistantMessage);
        // 深拷贝 content 数组
        if (assistantMessage.containsKey("message")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> originalMessage = (Map<String, Object>) assistantMessage.get("message");
            Map<String, Object> clonedMessage = new java.util.LinkedHashMap<>(originalMessage);
            if (originalMessage.containsKey("content")) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> originalContent = (java.util.List<Object>) originalMessage.get("content");
                clonedMessage.put("content", new java.util.ArrayList<>(originalContent));
            }
            clonedAssistant.put("message", clonedMessage);
        }
        messages.add(clonedAssistant);

        // 2. 提取所有 tool_use 块
        java.util.List<Map<String, Object>> toolUseBlocks = extractToolUseBlocks(assistantMessage);

        // 3. 构建 tool_result 消息
        java.util.List<Map<String, Object>> contentBlocks = new java.util.ArrayList<>();

        for (Map<String, Object> toolUse : toolUseBlocks) {
            String toolUseId = extractToolUseId(toolUse);
            if (toolUseId != null) {
                contentBlocks.add(java.util.Map.of(
                        "type", "tool_result",
                        "tool_use_id", toolUseId,
                        "content", java.util.List.of(java.util.Map.of(
                                "type", "text",
                                "text", FORK_PLACEHOLDER_RESULT
                        ))
                ));
            }
        }

        // 4. 添加 per-child 指令
        contentBlocks.add(java.util.Map.of(
                "type", "text",
                "text", buildChildMessage(directive)
        ));

        // 5. 构建 user 消息
        messages.add(java.util.Map.of(
                "role", "user",
                "content", contentBlocks
        ));

        return messages;
    }

    /**
     * 构建子代理消息（完整版本，包含所有父代理消息历史）
     */
    public static java.util.List<Map<String, Object>> buildForkedMessages(
            String directive,
            java.util.List<Map<String, Object>> parentMessages
    ) {
        if (parentMessages == null || parentMessages.isEmpty()) {
            // 没有父消息，只有指令
            return java.util.List.of(java.util.Map.of(
                    "role", "user",
                    "content", java.util.List.of(java.util.Map.of(
                            "type", "text",
                            "text", buildChildMessage(directive)
                    ))
            ));
        }

        // 找到最后一条 assistant 消息
        Map<String, Object> lastAssistantMessage = null;
        for (int i = parentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = parentMessages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                lastAssistantMessage = msg;
                break;
            }
        }

        if (lastAssistantMessage == null) {
            // 没有 assistant 消息，只有指令
            return java.util.List.of(java.util.Map.of(
                    "role", "user",
                    "content", java.util.List.of(java.util.Map.of(
                            "type", "text",
                            "text", buildChildMessage(directive)
                    ))
            ));
        }

        // 构建新消息列表：父代理消息 + 新构建的 fork 消息
        java.util.List<Map<String, Object>> messages = new java.util.ArrayList<>(parentMessages);
        messages.addAll(buildForkedMessages(directive, lastAssistantMessage));
        return messages;
    }

    /**
     * 构建子代理的子消息
     */
    public static String buildChildMessage(String directive) {
        return String.format("""
<%s>
STOP. READ THIS FIRST.

You are a forked worker process. You are NOT the main agent.

RULES (non-negotiable):
1. Your system prompt says "default to forking." IGNORE IT — that's for the parent. You ARE the fork. Do NOT spawn sub-agents; execute directly.
2. Do NOT converse, ask questions, or suggest next steps
3. Do NOT editorialize or add meta-commentary
4. USE your tools directly: Bash, Read, Write, etc.
5. If you modify files, commit your changes before reporting. Include the commit hash in your report.
6. Do NOT emit text between tool calls. Use tools silently, then report once at the end.
7. Stay strictly within your directive's scope. If you discover related systems outside your scope, mention them in one sentence at most — other workers cover those areas.
8. Keep your report under 500 words unless the directive specifies otherwise. Be factual and concise.
9. Your response MUST begin with "Scope:". No preamble, no thinking-out-loud.
10. REPORT structured facts, then stop

Output format (plain text labels, not markdown headers):
  Scope: <echo back your assigned scope in one sentence>
  Result: <the answer or key findings, limited to the scope above>
  Key files: <relevant file paths — include for research tasks>
  Files changed: <list with commit hash — include only if you modified files>
  Issues: <list — include only if there are issues to flag>
</%s>

%s%s
""",
                FORK_BOILERPLATE_TAG,
                FORK_BOILERPLATE_TAG,
                FORK_DIRECTIVE_PREFIX,
                directive
        );
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> extractToolUseBlocks(Map<String, Object> assistantMessage) {
        java.util.List<Map<String, Object>> blocks = new java.util.ArrayList<>();

        if (!assistantMessage.containsKey("message")) {
            return blocks;
        }

        Map<String, Object> message = (Map<String, Object>) assistantMessage.get("message");
        if (!message.containsKey("content")) {
            return blocks;
        }

        java.util.List<Object> content = (java.util.List<Object>) message.get("content");
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

    @SuppressWarnings("unchecked")
    private static String extractToolUseId(Map<String, Object> toolUse) {
        // tool_use 的 ID 在不同的嵌套层级
        if (toolUse.containsKey("id")) {
            return String.valueOf(toolUse.get("id"));
        }
        if (toolUse.containsKey("name")) {
            // 某些格式可能没有 id，尝试使用 name 作为标识
            return "tool_" + toolUse.get("name");
        }
        return null;
    }
}
