package agent.subagent.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代理定义注册表
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/loadAgentsDir.ts - agentRegistry
 *
 * 职责：管理所有代理定义的注册、查询和去重
 */
public class AgentDefinitionRegistry {

    /** 代理存储：agentType -> AgentDefinition */
    private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();

    /**
     * 注册代理定义
     */
    public void register(AgentDefinition agent) {
        if (agent == null || agent.getAgentType() == null) {
            throw new IllegalArgumentException("Agent and agentType cannot be null");
        }
        agents.put(agent.getAgentType(), agent);
    }

    /**
     * 获取代理定义
     */
    public AgentDefinition get(String agentType) {
        return agents.get(agentType);
    }

    /**
     * 获取所有代理定义
     */
    public List<AgentDefinition> getAll() {
        return new ArrayList<>(agents.values());
    }

    /**
     * 获取活跃代理（去重后）
     * 对应: getActiveAgentsFromList()
     */
    public List<AgentDefinition> getActiveAgents() {
        return getAll();
    }

    /**
     * 清空注册表
     */
    public void clear() {
        agents.clear();
    }

    /**
     * 检查是否存在
     */
    public boolean contains(String agentType) {
        return agents.containsKey(agentType);
    }

    /**
     * 获取代理数量
     */
    public int size() {
        return agents.size();
    }
}
