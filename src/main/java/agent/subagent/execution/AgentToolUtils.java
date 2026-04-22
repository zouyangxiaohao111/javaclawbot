package agent.subagent.execution;

import agent.subagent.definition.AgentDefinition;

import java.util.List;
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
