package agent.subagent.builtin;

import agent.subagent.definition.AgentDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置代理注册表
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/builtInAgents.ts - getBuiltInAgents()
 *
 * 职责：提供所有内置代理的注册表
 */
public class BuiltInAgents {

    /**
     * 获取所有内置代理
     * 对应: getBuiltInAgents()
     */
    public static List<AgentDefinition> getBuiltInAgents() {
        List<AgentDefinition> agents = new ArrayList<>();

        // 添加通用代理
        agents.add(GeneralPurposeAgent.get());

        // 添加探索代理（只读搜索）
        agents.add(ExploreAgent.get());

        // 添加计划代理（只读规划）
        agents.add(PlanAgent.get());

        return agents;
    }
}
