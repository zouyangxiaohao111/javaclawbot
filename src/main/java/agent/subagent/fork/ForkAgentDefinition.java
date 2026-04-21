package agent.subagent.fork;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.PermissionMode;

import java.util.Map;

import java.util.*;

/**
 * Fork 代理定义
 *
 * 这是一个特殊的内置代理类型，用于隐式 Fork。
 * 当用户调用 Agent 工具时不指定 subagent_type 时使用。
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/forkSubagent.ts - FORK_AGENT
 */
public class ForkAgentDefinition extends AgentDefinition {

    /** Fork 指令前缀 */
    public static final String FORK_DIRECTIVE_PREFIX = "# directive\n";

    /** Fork 占位符结果 - 所有 tool_use 的 placeholder 结果必须相同以共享 Cache */
    public static final String FORK_PLACEHOLDER_RESULT = "Fork started — processing in background";

    /** Fork Boilerplate 标签 */
    public static final String FORK_BOILERPLATE_TAG = "FORK_BOILERPLATE_TAG";

    public ForkAgentDefinition() {
        this.agentType = "fork";
        this.whenToUse = "Implicit fork — inherits full conversation context. Not selectable via subagent_type; triggered by omitting subagent_type when the fork experiment is active.";
        this.tools = List.of("*");  // 继承父代理的全部工具
        this.maxTurns = 200;
        this.model = "inherit";  // 继承父代理的模型
        this.permissionMode = PermissionMode.BUBBLE;  // 权限提示冒泡到父终端
        this.source = "built-in";
        this.baseDir = "built-in";
        // getSystemPrompt 返回空字符串 - Fork 路径使用父代理已渲染的系统提示
        this.getSystemPrompt = () -> "";
    }

    /**
     * 构建子代理消息
     *
     * Fork 子代理的消息格式：
     * 1. 完整的父代理 assistant 消息（包含所有 tool_use）
     * 2. User 消息：placeholder tool_results + per-child 指令
     *
     * 这样构造是为了最大化 Cache 命中率 - 只有最后一条消息不同。
     *
     * 对应 Open-ClaudeCode: buildForkedMessages()
     */
    public static List<Map<String, Object>> buildForkedMessages(
            String directive,
            Map<String, Object> assistantMessage
    ) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1. 克隆完整的 assistant 消息（避免修改原始消息）
        Map<String, Object> clonedAssistant = cloneAssistantMessage(assistantMessage);
        messages.add(clonedAssistant);

        // 2. 提取所有 tool_use 块
        List<Map<String, Object>> toolUseBlocks = extractToolUseBlocks(assistantMessage);

        if (toolUseBlocks.isEmpty()) {
            // 没有 tool_use 块，只返回包含指令的 user 消息
            return List.of(createUserMessage(List.of(
                    createTextBlock(buildChildMessage(directive))
            )));
        }

        // 3. 构建 tool_result 消息
        List<Object> contentBlocks = new ArrayList<>();

        for (Map<String, Object> toolUse : toolUseBlocks) {
            String toolUseId = extractToolUseId(toolUse);
            if (toolUseId != null) {
                contentBlocks.add(createToolResultBlock(toolUseId, FORK_PLACEHOLDER_RESULT));
            }
        }

        // 4. 添加 per-child 指令
        contentBlocks.add(createTextBlock(buildChildMessage(directive)));

        // 5. 构建 user 消息
        messages.add(createUserMessage(contentBlocks));

        return messages;
    }

    /**
     * 构建子代理消息（完整版本，包含所有父代理消息历史）
     *
     * 对应 Open-ClaudeCode: buildForkedMessages() 重载版本
     */
    public static List<Map<String, Object>> buildForkedMessages(
            String directive,
            List<Map<String, Object>> parentMessages
    ) {
        if (parentMessages == null || parentMessages.isEmpty()) {
            // 没有父消息，只有指令
            return List.of(createUserMessage(List.of(
                    createTextBlock(buildChildMessage(directive))
            )));
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
            return List.of(createUserMessage(List.of(
                    createTextBlock(buildChildMessage(directive))
            )));
        }

        // 构建新消息列表：父代理消息 + 新构建的 fork 消息
        List<Map<String, Object>> messages = new ArrayList<>(parentMessages);
        messages.addAll(buildForkedMessages(directive, lastAssistantMessage));
        return messages;
    }

    /**
     * 构建子代理的子消息
     *
     * 对应 Open-ClaudeCode: buildChildMessage()
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

    // =====================
    // 私有辅助方法
    // =====================

    /**
     * 克隆 assistant 消息，避免修改原始消息
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cloneAssistantMessage(Map<String, Object> assistantMessage) {
        Map<String, Object> cloned = new LinkedHashMap<>(assistantMessage);

        if (cloned.containsKey("message")) {
            Map<String, Object> originalMessage = (Map<String, Object>) cloned.get("message");
            Map<String, Object> clonedMessage = new LinkedHashMap<>(originalMessage);

            if (originalMessage.containsKey("content")) {
                List<Object> originalContent = (List<Object>) originalMessage.get("content");
                clonedMessage.put("content", new ArrayList<>(originalContent));
            }

            cloned.put("message", clonedMessage);
        }

        return cloned;
    }

    /**
     * 从 assistant 消息中提取所有 tool_use 块
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractToolUseBlocks(Map<String, Object> assistantMessage) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        if (!assistantMessage.containsKey("message")) {
            return blocks;
        }

        Map<String, Object> message = (Map<String, Object>) assistantMessage.get("message");
        if (!message.containsKey("content")) {
            return blocks;
        }

        List<Object> content = (List<Object>) message.get("content");
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

    /**
     * 从 tool_use 块中提取 ID
     */
    private static String extractToolUseId(Map<String, Object> toolUse) {
        // tool_use 的 ID 在顶层或嵌套在 input 中
        if (toolUse.containsKey("id")) {
            return String.valueOf(toolUse.get("id"));
        }
        // 某些格式可能没有 id，尝试使用 name 作为标识
        if (toolUse.containsKey("name")) {
            return "tool_" + toolUse.get("name");
        }
        return null;
    }

    /**
     * 创建 user 消息
     */
    private static Map<String, Object> createUserMessage(List<Object> content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        return msg;
    }

    /**
     * 创建文本块
     */
    private static Map<String, Object> createTextBlock(String text) {
        return Map.of(
                "type", "text",
                "text", text
        );
    }

    /**
     * 创建 tool_result 块
     */
    private static Map<String, Object> createToolResultBlock(String toolUseId, String content) {
        return Map.of(
                "type", "tool_result",
                "tool_use_id", toolUseId,
                "content", List.of(Map.of(
                        "type", "text",
                        "text", content
                ))
        );
    }
}
