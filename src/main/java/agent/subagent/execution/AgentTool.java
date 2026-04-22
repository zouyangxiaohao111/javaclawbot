package agent.subagent.execution;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.definition.AgentDefinitionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 工具主入口
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/AgentTool.tsx - AgentTool
 *
 * 职责：
 * 1. 解析 LLM 调用参数（name, team_name, subagent_type, prompt 等）
 * 2. 路由到不同执行路径：
 *    - team_name + name → spawnTeammate
 *    - subagent_type → Named Subagent
 *    - 无 subagent_type → Fork Subagent
 * 3. 处理同步/异步执行
 * 4. 返回执行结果
 */
public class AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /** 代理定义加载器 */
    private final AgentDefinitionLoader loader;

    public AgentTool() {
        this.loader = new AgentDefinitionLoader();
    }

    /**
     * 获取工具名称
     */
    public String name() {
        return "Agent";
    }

    /**
     * 获取工具描述
     */
    public String description() {
        return "Spawn a sub-agent to handle a task. Use this to delegate work to specialized agents " +
               "like Explore (for searching code), Plan (for designing implementation plans), or " +
               "general-purpose (for complex multi-step tasks).";
    }

    /**
     * 执行 Agent 工具
     *
     * @param args 工具参数
     * @return 执行结果
     */
    public CompletableFuture<String> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 解析参数
                String name = getString(args, "name", null);
                String teamName = getString(args, "team_name", null);
                String subagentType = getString(args, "subagent_type", null);
                String prompt = getString(args, "prompt", null);
                Boolean background = getBoolean(args, "background", false);

                log.info("Agent tool called: name={}, teamName={}, subagentType={}, background={}",
                        name, teamName, subagentType, background);

                // 2. 路由决策
                if (teamName != null && name != null) {
                    return spawnTeammate(teamName, name, prompt, background);
                } else if (subagentType != null) {
                    return runNamedAgent(subagentType, prompt, background);
                } else {
                    return runForkAgent(prompt, background);
                }
            } catch (Exception e) {
                log.error("Error executing Agent tool", e);
                return "Error: " + e.getMessage();
            }
        });
    }

    private String getString(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private Boolean getBoolean(Map<String, Object> args, String key, Boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    private String spawnTeammate(String teamName, String name, String prompt, Boolean background) {
        log.info("Spawning teammate: team={}, name={}, prompt={}", teamName, name, prompt);
        return "{\"error\": \"Teammate spawning not yet implemented\"}";
    }

    private String runNamedAgent(String subagentType, String prompt, Boolean background) {
        log.info("Running named agent: type={}, prompt={}", subagentType, prompt);

        List<AgentDefinition> agents = loader.getAgentDefinitionsWithOverrides(null);
        AgentDefinition agent = agents.stream()
                .filter(a -> a.getAgentType().equals(subagentType))
                .findFirst()
                .orElse(null);

        if (agent == null) {
            log.warn("Agent not found: {}", subagentType);
            return "{\"error\": \"Agent not found: " + subagentType + "\"}";
        }

        return "{\"status\": \"Agent execution not yet implemented\", \"agentType\": \"" + subagentType + "\"}";
    }

    private String runForkAgent(String prompt, Boolean background) {
        log.info("Running fork agent: prompt={}", prompt);
        return "{\"status\": \"Fork execution not yet implemented\", \"prompt\": \"" + prompt + "\"}";
    }

    public AgentDefinitionLoader getLoader() {
        return loader;
    }
}
