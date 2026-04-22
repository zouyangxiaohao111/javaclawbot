package agent.subagent.execution;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.context.SubagentContext;
import agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 代理执行循环
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/runAgent.ts - runAgent()
 *
 * 核心执行逻辑：
 * 1. 初始化工具池
 * 2. 初始化 MCP 服务器（可选）
 * 3. 构建系统提示词
 * 4. 执行 query 循环
 * 5. 处理工具调用
 * 6. 返回结果
 */
public class runAgent {

    private static final Logger log = LoggerFactory.getLogger(runAgent.class);

    /**
     * 执行代理
     * 对应: export async function* runAgent({...})
     *
     * @param params 执行参数
     * @return 执行结果
     */
    public static AgentToolResult execute(RunAgentParams params) {
        log.info("Executing agent: type={}, prompt={}", params.agentType, params.prompt);

        try {
            // 1. 解析工具和权限
            List<String> availableTools = params.agent.getTools();
            List<String> disallowedTools = params.agent.getDisallowedTools();

            // 2. 构建过滤后的工具列表
            List<String> filteredTools = AgentToolUtils.filterTools(availableTools, params.agent);

            // 3. 获取代理模型
            String model = AgentToolUtils.getAgentModel(params.agent, params.defaultModel);

            // 4. 创建子代理上下文
            SubagentContext context = createSubagentContext(params);

            // 5. 执行 query 循环
            String result = executeQueryLoop(context, params);

            // 6. 返回结果
            return AgentToolResult.success(result);

        } catch (Exception e) {
            log.error("Error executing agent", e);
            return AgentToolResult.failure(e.getMessage());
        }
    }

    /**
     * 创建子代理上下文
     */
    private static SubagentContext createSubagentContext(RunAgentParams params) {
        SubagentContext.Builder builder = SubagentContext.builder()
                .agentId(params.agentId)
                .parentAgentId(params.parentAgentId)
                .shouldAvoidPermissionPrompts(true);

        if (params.toolUseContext != null) {
            builder.toolUseContext(params.toolUseContext);
        }

        return builder.build();
    }

    /**
     * 执行 query 循环
     * TODO: 实现完整的 query 循环
     */
    private static String executeQueryLoop(SubagentContext context, RunAgentParams params) {
        // TODO: 实现 LLM query 循环
        // 1. 调用 LLM 生成响应
        // 2. 处理工具调用
        // 3. 收集结果
        // 4. 返回最终响应
        return "Query loop not yet implemented";
    }

    /**
     * 执行参数
     */
    public static class RunAgentParams {
        /** 代理定义 */
        public AgentDefinition agent;

        /** 提示词 */
        public String prompt;

        /** 代理类型 */
        public String agentType;

        /** 代理 ID */
        public String agentId;

        /** 父代理 ID */
        public String parentAgentId;

        /** 默认模型 */
        public String defaultModel;

        /** 工具使用上下文 */
        public ToolUseContext toolUseContext;

        /** 是否后台运行 */
        public boolean background;

        /** 最大轮次 */
        public int maxTurns;

        /** 构建器 */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final RunAgentParams params = new RunAgentParams();

            public Builder agent(AgentDefinition agent) {
                params.agent = agent;
                return this;
            }

            public Builder prompt(String prompt) {
                params.prompt = prompt;
                return this;
            }

            public Builder agentType(String agentType) {
                params.agentType = agentType;
                return this;
            }

            public Builder agentId(String agentId) {
                params.agentId = agentId;
                return this;
            }

            public Builder parentAgentId(String parentAgentId) {
                params.parentAgentId = parentAgentId;
                return this;
            }

            public Builder defaultModel(String defaultModel) {
                params.defaultModel = defaultModel;
                return this;
            }

            public Builder toolUseContext(ToolUseContext toolUseContext) {
                params.toolUseContext = toolUseContext;
                return this;
            }

            public Builder background(boolean background) {
                params.background = background;
                return this;
            }

            public Builder maxTurns(int maxTurns) {
                params.maxTurns = maxTurns;
                return this;
            }

            public RunAgentParams build() {
                return params;
            }
        }
    }
}
