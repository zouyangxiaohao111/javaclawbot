package agent.subagent.execution;

import agent.subagent.definition.AgentDefinition;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AgentTool 工具函数
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/agentToolUtils.ts
 */
public class AgentToolUtils {

    /**
     * 过滤被禁用的工具
     * 对应: filterTools()
     *
     * @param allTools 所有可用工具
     * @param agent 代理定义
     * @return 过滤后的工具列表
     */
    public static List<String> filterTools(List<String> allTools, AgentDefinition agent) {
        if (agent == null) {
            return allTools;
        }

        // 如果 agent.tools 是 ["*"]，返回全部工具
        if (agent.hasWildcardTools()) {
            // 然后移除 disallowedTools
            if (agent.getDisallowedTools() != null && !agent.getDisallowedTools().isEmpty()) {
                return allTools.stream()
                    .filter(tool -> !agent.getDisallowedTools().contains(tool))
                    .collect(Collectors.toList());
            }
            return allTools;
        }

        // 否则，只保留 agent.tools 中的工具
        if (agent.getTools() != null && !agent.getTools().isEmpty()) {
            List<String> allowed = allTools.stream()
                .filter(tool -> agent.getTools().contains(tool))
                .collect(Collectors.toList());

            // 移除 disallowedTools
            if (agent.getDisallowedTools() != null && !agent.getDisallowedTools().isEmpty()) {
                return allowed.stream()
                    .filter(tool -> !agent.getDisallowedTools().contains(tool))
                    .collect(Collectors.toList());
            }

            return allowed;
        }

        return allTools;
    }

    /**
     * 过滤包含不完整工具调用的消息
     * 对应 Open-ClaudeCode: src/tools/AgentTool/runAgent.ts - filterIncompleteToolCalls()
     *
     * 过滤掉 assistant 消息中包含 tool_use（但没有对应 tool_result）的消息。
     * 这防止在 fork 时发送孤立的工具调用导致 API 错误。
     *
     * @param messages 消息列表
     * @return 过滤后的消息列表
     */
    public static List<Map<String, Object>> filterIncompleteToolCalls(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // 构建一个包含 tool_use_id 的集合（从 user 消息的 tool_result 中获取）
        Set<String> toolUseIdsWithResults = new HashSet<>();

        for (Map<String, Object> message : messages) {
            String type = getMessageType(message);
            if ("user".equals(type)) {
                Object contentObj = message.get("content");
                if (contentObj instanceof List) {
                    List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
                    for (Map<String, Object> block : content) {
                        String blockType = (String) block.get("type");
                        if ("tool_result".equals(blockType)) {
                            String toolUseId = (String) block.get("tool_use_id");
                            if (toolUseId != null && !toolUseId.isEmpty()) {
                                toolUseIdsWithResults.add(toolUseId);
                            }
                        }
                    }
                }
            }
        }

        // 过滤掉包含不完整 tool_use 的 assistant 消息
        return messages.stream().filter(message -> {
            String type = getMessageType(message);
            if (!"assistant".equals(type)) {
                return true;
            }

            Object contentObj = message.get("content");
            if (!(contentObj instanceof List)) {
                return true;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
            // 检查是否包含没有对应结果的 tool_use
            for (Map<String, Object> block : content) {
                String blockType = (String) block.get("type");
                if ("tool_use".equals(blockType)) {
                    String id = (String) block.get("id");
                    if (id != null && !id.isEmpty() && !toolUseIdsWithResults.contains(id)) {
                        // 包含不完整的 tool_use，排除此消息
                        return false;
                    }
                }
            }
            return true;
        }).collect(Collectors.toList());
    }

    /**
     * 获取消息类型
     */
    private static String getMessageType(Map<String, Object> message) {
        if (message == null) {
            return null;
        }
        return (String) message.get("type");
    }

    /**
     * 过滤包含未解析工具调用的消息
     * 对应 Open-ClaudeCode: src/utils/messages.ts - filterUnresolvedToolUses()
     *
     * 过滤掉 assistant 消息中所有 tool_use 都没有对应 tool_result 的消息。
     * 这用于恢复时清理孤立的工具调用。
     *
     * @param messages 消息列表
     * @return 过滤后的消息列表
     */
    public static List<Map<String, Object>> filterUnresolvedToolCalls(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // 收集所有 tool_use_id 和 tool_result_id
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();

        for (Map<String, Object> msg : messages) {
            String type = getMessageType(msg);
            if (!"user".equals(type) && !"assistant".equals(type)) {
                continue;
            }

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List)) {
                continue;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
            for (Map<String, Object> block : content) {
                String blockType = (String) block.get("type");
                if ("tool_use".equals(blockType)) {
                    String id = (String) block.get("id");
                    if (id != null) {
                        toolUseIds.add(id);
                    }
                }
                if ("tool_result".equals(blockType)) {
                    String toolUseId = (String) block.get("tool_use_id");
                    if (toolUseId != null) {
                        toolResultIds.add(toolUseId);
                    }
                }
            }
        }

        // 找出未解析的 ID
        Set<String> unresolvedIds = new HashSet<>();
        for (String id : toolUseIds) {
            if (!toolResultIds.contains(id)) {
                unresolvedIds.add(id);
            }
        }

        if (unresolvedIds.isEmpty()) {
            return messages;
        }

        // 过滤掉所有 tool_use 块都未解析的 assistant 消息
        return messages.stream().filter(msg -> {
            String type = getMessageType(msg);
            if (!"assistant".equals(type)) {
                return true;
            }

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List)) {
                return true;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
            List<String> toolUseBlockIds = new ArrayList<>();
            for (Map<String, Object> block : content) {
                if ("tool_use".equals(block.get("type"))) {
                    String id = (String) block.get("id");
                    if (id != null) {
                        toolUseBlockIds.add(id);
                    }
                }
            }

            if (toolUseBlockIds.isEmpty()) {
                return true;
            }

            // 只有当所有 tool_use 块都未解析时才移除消息
            return !toolUseBlockIds.stream().allMatch(unresolvedIds::contains);
        }).collect(Collectors.toList());
    }

    /**
     * 过滤只包含空白内容的 assistant 消息
     * 对应 Open-ClaudeCode: src/utils/messages.ts - filterWhitespaceOnlyAssistantMessages()
     *
     * 过滤掉只包含空白文本的 assistant 消息。这些消息会导致 API 错误。
     *
     * @param messages 消息列表
     * @return 过滤后的消息列表
     */
    public static List<Map<String, Object>> filterWhitespaceOnlyAssistantMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        return messages.stream().filter(msg -> {
            String type = getMessageType(msg);
            if (!"assistant".equals(type)) {
                return true;
            }

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List)) {
                return true;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
            if (content.isEmpty()) {
                return true;
            }

            // 检查是否有非空白内容
            for (Map<String, Object> block : content) {
                String blockType = (String) block.get("type");
                if ("text".equals(blockType)) {
                    String text = (String) block.get("text");
                    if (text != null && !text.trim().isEmpty()) {
                        return true;
                    }
                } else if (!"thinking".equals(blockType) && !"redacted_thinking".equals(blockType)) {
                    // 有其他类型的块（非思考、非空白文本），保留消息
                    return true;
                }
            }

            // 只有思考块或空白文本，过滤掉
            return false;
        }).collect(Collectors.toList());
    }

    /**
     * 过滤孤立的只包含思考的消息
     * 对应 Open-ClaudeCode: src/utils/messages.ts - filterOrphanedThinkingOnlyMessages()
     *
     * 过滤掉没有对应非思考内容的 assistant 思考消息。
     * 这些孤立的思考消息会在后续处理中合并到对应的消息中。
     *
     * @param messages 消息列表
     * @return 过滤后的消息列表
     */
    public static List<Map<String, Object>> filterOrphanedThinkingOnlyMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // 第一遍：收集有非思考内容的消息 ID
        Set<String> messageIdsWithNonThinkingContent = new HashSet<>();
        for (Map<String, Object> msg : messages) {
            String type = getMessageType(msg);
            if (!"assistant".equals(type)) {
                continue;
            }

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List)) {
                continue;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
            boolean hasNonThinking = content.stream()
                    .anyMatch(block -> {
                        String blockType = (String) block.get("type");
                        return !"thinking".equals(blockType) && !"redacted_thinking".equals(blockType);
                    });

            if (hasNonThinking) {
                Object msgId = msg.get("id");
                if (msgId != null) {
                    messageIdsWithNonThinkingContent.add(msgId.toString());
                }
            }
        }

        // 第二遍：过滤孤立思考消息
        return messages.stream().filter(msg -> {
            String type = getMessageType(msg);
            if (!"assistant".equals(type)) {
                return true;
            }

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List)) {
                return true;
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;
            boolean isThinkingOnly = content.stream()
                    .allMatch(block -> {
                        String blockType = (String) block.get("type");
                        return "thinking".equals(blockType) || "redacted_thinking".equals(blockType);
                    });

            if (!isThinkingOnly) {
                return true;
            }

            // 这是一个只包含思考的消息
            Object msgId = msg.get("id");
            if (msgId == null) {
                // 没有 ID 的消息无法判断是否有配对，保留
                return true;
            }

            // 如果有对应的非思考消息，则过滤掉这个孤立思考消息
            // （它会在 normalizeMessages 中合并到配对消息）
            return messageIdsWithNonThinkingContent.contains(msgId.toString());
        }).collect(Collectors.toList());
    }

    /**
     * 获取代理模型
     * 对应: getAgentModel()
     *
     * @param agent 代理定义
     * @param defaultModel 默认模型
     * @return 实际使用的模型
     */
    public static String getAgentModel(AgentDefinition agent, String defaultModel) {
        if (agent == null || agent.getModel() == null) {
            return defaultModel;
        }

        // "inherit" 表示使用父代理的模型
        if ("inherit".equalsIgnoreCase(agent.getModel())) {
            return defaultModel;
        }

        return agent.getModel();
    }

    /**
     * 检查代理是否有指定的工具权限
     *
     * @param agent 代理定义
     * @param toolName 工具名称
     * @return 是否有权限
     */
    public static boolean hasToolPermission(AgentDefinition agent, String toolName) {
        if (agent == null) {
            return true;
        }

        // 检查是否在禁用列表中
        if (agent.getDisallowedTools() != null && agent.getDisallowedTools().contains(toolName)) {
            return false;
        }

        // 如果有通配符权限
        if (agent.hasWildcardTools()) {
            return true;
        }

        // 检查是否在允许列表中
        if (agent.getTools() != null && agent.getTools().contains(toolName)) {
            return true;
        }

        return agent.getTools() == null || agent.getTools().isEmpty();
    }

    /**
     * 获取代理的最大轮次
     *
     * @param agent 代理定义
     * @param defaultMaxTurns 默认最大轮次
     * @return 最大轮次
     */
    public static int getMaxTurns(AgentDefinition agent, int defaultMaxTurns) {
        if (agent == null || agent.getMaxTurns() <= 0) {
            return defaultMaxTurns;
        }
        return agent.getMaxTurns();
    }
}
