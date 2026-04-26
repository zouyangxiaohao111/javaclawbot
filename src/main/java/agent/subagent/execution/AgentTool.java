package agent.subagent.execution;

import agent.tool.Tool;
import agent.subagent.definition.AgentDefinitionLoader;
import agent.subagent.builtin.general.GeneralPurposeAgent;
import agent.subagent.builtin.explore.ExploreAgent;
import agent.subagent.builtin.plan.PlanAgent;
import agent.subagent.fork.ForkAgentExecutor;
import agent.subagent.fork.ForkContext;
import agent.subagent.context.SubagentContext;
import agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Agent 工具主入口
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/AgentTool.tsx - AgentTool
 *
 * 根据 subagent_type 调用不同的执行器：
 * - general-purpose → GeneralPurposeAgent
 * - Explore → ExploreAgent
 * - Plan → PlanAgent
 * - null (fork) → ForkAgentExecutor
 */
public class AgentTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    private final AgentDefinitionLoader loader;

    /** Fork 执行器（可选） */
    private ForkAgentExecutor forkExecutor;

    /** Background agent executor for built-in agents */
    private BackgroundAgentExecutor backgroundExecutor;

    /** Sessions 目录 */
    private Path sessionsDir;

    /** Session 管理器 */
    private session.SessionManager sessions;

    public AgentTool() {
        this.loader = new AgentDefinitionLoader();
    }

    /**
     * 设置 Fork 执行器（由 AgentLoop 注入）
     */
    public void setForkExecutor(ForkAgentExecutor forkExecutor) {
        this.forkExecutor = forkExecutor;
    }

    /**
     * 设置 Background agent executor（由 AgentLoop 注入）
     */
    public void setBackgroundExecutor(BackgroundAgentExecutor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * 设置 Sessions 目录
     */
    public void setSessionsDir(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    /**
     * 设置 Session 管理器
     */
    public void setSessions(session.SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        return "Spawn a sub-agent to handle a task. Use this to delegate work to specialized agents " +
               "like Explore (for searching code), Plan (for designing implementation plans), or " +
               "general-purpose (for complex multi-step tasks).** Important reminder **: If using background mode, do not poll to detect the status of this sub agent, as it will automatically notify you";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> subagentTypeEnum = Map.of(
            "type", "string",
            "enum", List.of("general-purpose", "Explore", "Plan"),
            "description", "Type of subagent: general-purpose (default), Explore (for code search), Plan (for planning)"
        );

        return Map.of(
            "type", "object",
            "properties", Map.of(
                "name", Map.of("type", "string", "description", "Name of the teammate to spawn"),
                "team_name", Map.of("type", "string", "description", "Team name for the teammate(暂未实现Team模式)"),
                "subagent_type", subagentTypeEnum,
                "prompt", Map.of("type", "string", "description", "Task prompt for the subagent"),
                "background", Map.of("type", "boolean", "description", "Whether to run in background")
            )
        );
    }



    @Override
    public CompletableFuture<String> execute(Map<String, Object> args, ToolUseContext parentContext) {
        // 关键：在 supplyAsync 之前获取上下文！
        // 调试日志：记录 parentContext 的状态
        log.info("[AgentTool.execute] 父工具上下文={}, tools count={}, thread={}",
                parentContext != null ? "non-null" : "null",
                parentContext != null && parentContext.getTools() != null ? parentContext.getTools().size() : -1,
                Thread.currentThread().getName());

        return CompletableFuture.supplyAsync(() -> {
            try {
                String name = getString(args, "name", null);
                String teamName = getString(args, "team_name", null);
                String subagentType = getString(args, "subagent_type", "general-purpose");
                String prompt = getString(args, "prompt", "");
                Boolean background = getBoolean(args, "background", false);

                //log.info("Agent tool called: name={}, teamName={}, subagentType={}, background={}", name, teamName, subagentType, background);

                // 根据参数选择执行路径
                if (teamName != null && name != null) {
                    // spawnTeammate 路径 - 创建具名队友
                    return spawnTeammate(teamName, name, prompt, background, parentContext);
                } else if (subagentType != null) {
                    // runNamedAgent 路径 - 运行内置代理
                    return runNamedAgent(subagentType, prompt, background, parentContext);
                } else {
                    // runForkAgent 路径 - fork 新代理
                    return runForkAgent(prompt, background, parentContext);
                }
            } catch (Exception e) {
                log.error("Error executing Agent tool", e);
                return "{\"error\": \"" + e.getMessage() + "\"}";
            }
        });
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        log.error("Agent工具不支持 execute(Map<String, Object> args)！请使用带工具上下文的");
        return CompletableFuture.completedFuture("Agent工具不支持 execute(Map<String, Object> args)！");
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

    /**
     * Spawn Teammate - 创建队友进程（tmux/iTerm2）
     * 对应 Open-ClaudeCode: spawnTeammate()
     */
    private String spawnTeammate(String teamName, String name, String prompt, Boolean background, ToolUseContext context) {
        log.info("Spawning teammate: team={}, name={}, prompt={}, background={}",
                teamName, name, prompt, background);

        try {
            // 从 ToolUseContextHolder 获取当前上下文
            if (context == null) {
                log.warn("No ToolUseContext available for spawnTeammate");
                return "{\"error\": \"No ToolUseContext available\"}";
            }

            // 使用 ToolUseContext 重载方法
            agent.subagent.spawn.SpawnResult result =
                agent.subagent.spawn.SpawnTeammate.spawn(
                    teamName, name, prompt, background,
                    System.getProperty("user.dir"),
                    context
                ).join();

            if (result.isSuccess()) {
                return "{\"status\": \"spawned\", \"sessionName\": \"" +
                       result.getSessionName() + "\", \"team\": \"" + teamName +
                       "\", \"name\": \"" + name + "\"}";
            } else {
                return "{\"error\": \"Failed to spawn teammate: " + result.getError() + "\"}";
            }
        } catch (Exception e) {
            log.error("Error spawning teammate", e);
            return "{\"error\": \"Failed to spawn teammate: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Run Named Agent - 运行内置代理
     * 对应 Open-ClaudeCode: runNamedAgent()
     *
     * @param subagentType 代理类型 (general-purpose, Explore, Plan)
     * @param prompt 任务提示词
     * @param background 是否后台运行
     * @param parentContext 父级工具使用上下文（从 execute 方法传入，避免 ThreadLocal 跨线程丢失）
     * @return 执行结果 JSON
     */
    private String runNamedAgent(String subagentType, String prompt, Boolean background, ToolUseContext parentContext) {

        try {
            // 如果是后台执行且有 BackgroundAgentExecutor，使用异步执行
            if (background != null && background && backgroundExecutor != null) {
                String systemPrompt = getSystemPromptForType(subagentType);

                // 从 sessionId 解析 channel 和 chatId
                String sessionKey = parentContext != null ? parentContext.getSessionId() : null;
                String channel = null;
                String chatId = null;
                if (sessionKey != null && sessionKey.contains(":")) {
                    String[] parts = sessionKey.split(":", 2);
                    channel = parts[0];
                    chatId = parts.length > 1 ? parts[1] : "";
                }

                return backgroundExecutor.executeAsync(
                        subagentType, prompt, systemPrompt, parentContext,
                        sessionKey, channel, chatId);
            }

            // 同步执行 - 传递父上下文给静态入口点
            switch (subagentType) {
                case "Explore":
                    return RunAgent.runExplore(prompt, background, parentContext);
                case "Plan":
                    return RunAgent.runPlan(prompt, background, parentContext);
                case "general-purpose":
                default:
                    return RunAgent.runGeneralPurpose(prompt, background, parentContext);
            }
        } catch (Exception e) {
            log.error("Error running named agent", e);
            return "{\"error\": \"Failed to run agent: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 根据代理类型获取系统提示词
     */
    private String getSystemPromptForType(String subagentType) {
        switch (subagentType) {
            case "Explore":
                return ExploreAgent.getSystemPrompt();
            case "Plan":
                return PlanAgent.getSystemPrompt();
            case "general-purpose":
            default:
                return GeneralPurposeAgent.getSystemPrompt();
        }
    }

    /**
     * Run Fork Agent - Fork 新代理
     * 对应 Open-ClaudeCode: runForkAgent()
     *
     * 使用 ForkAgentExecutor 执行真正的 fork（隔离上下文，不污染父代理）
     *
     * @param prompt 任务提示词
     * @param background 是否后台运行
     * @param parentContext 父级工具使用上下文（从 execute 方法传入）
     * @return 执行结果 JSON
     */
    private String runForkAgent(String prompt, Boolean background, ToolUseContext parentContext) {
        log.info("Running fork agent: prompt={}, background={}, hasContext={}", prompt, background, parentContext != null);

        try {
            // 检查 ForkExecutor 是否可用
            if (forkExecutor == null || sessionsDir == null || sessions == null) {
                log.warn("ForkExecutor not available, falling back to RunAgent");
                return RunAgent.runGeneralPurpose(prompt, background, parentContext);
            }

            // 获取当前会话
            String sessionKey = parentContext != null ? parentContext.getSessionId() : null;
            session.Session currentSession = (sessionKey != null && sessions != null) ? sessions.getOrCreate(sessionKey) : null;
            List<Map<String, Object>> parentMessages = currentSession != null ? currentSession.getMessages() : List.of();
            String sessionId = currentSession != null ? currentSession.getSessionId() : sessionKey;

            // 获取最后一条 assistant 消息
            Map<String, Object> lastAssistantMessage = null;
            for (int i = parentMessages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = parentMessages.get(i);
                if ("assistant".equals(msg.get("role"))) {
                    lastAssistantMessage = msg;
                    break;
                }
            }

            // 构建 ForkContext
            ForkContext forkContext = ForkContext.builder()
                    .parentAgentId("main")
                    .directive(prompt)
                    .parentMessages(parentMessages)
                    .parentAssistantMessage(lastAssistantMessage)
                    .build();

            // 创建 SubagentContext
            SubagentContext subagentContext = SubagentContext.builder().build();

            if (background) {
                // Async: 立即返回任务ID，后台执行
                CompletableFuture<ForkAgentExecutor.ForkResult> future =
                        forkExecutor.execute(sessionId, forkContext, subagentContext);

                String forkId = java.util.UUID.randomUUID().toString().substring(0, 8);
                future.whenComplete((result, ex) -> {
                    log.info("Fork completed: forkId={}, success={}", forkId, result != null && result.success);
                });

                return String.format(
                        "{\"status\":\"async_launched\",\"agentId\":\"%s\",\"prompt\":\"%s\"}",
                        forkId, escapeJson(prompt));
            } else {
                // Sync: 阻塞等待结果
                ForkAgentExecutor.ForkResult result = forkExecutor.execute(sessionId, forkContext, subagentContext)
                        .toCompletableFuture().join();
                return result.toJson();
            }
        } catch (Exception e) {
            log.error("Error running fork agent", e);
            return "{\"error\": \"Failed to fork agent: " + e.getMessage() + "\"}";
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    public AgentDefinitionLoader getLoader() {
        return loader;
    }
}
