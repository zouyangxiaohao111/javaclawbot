package agent.subagent.execution;

import agent.subagent.constants.AgentToolConstants;
import agent.subagent.definition.AgentDefinition;
import agent.subagent.builtin.general.GeneralPurposeAgent;
import agent.subagent.builtin.explore.ExploreAgent;
import agent.subagent.builtin.plan.PlanAgent;
import agent.subagent.definition.PermissionMode;
import agent.subagent.mcp.AgentMcpConnection;
import agent.tool.Tool;
import agent.tool.ToolView;
import agent.tool.ToolUseContext;

import static utils.Helpers.stripThink;

import cn.hutool.core.util.StrUtil;
import providers.LLMProvider;
import providers.LLMResponse;
import providers.ProviderFactory;
import config.Config;
import config.ConfigIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.Helpers;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 代理执行循环
 * 对应 Open-ClaudeCode: src/tools/AgentTool/runAgent.ts - runAgent()
 *
 * 核心功能：
 * 1. 构建消息列表
 * 2. 调用 LLM
 * 3. 处理工具调用
 * 4. 返回结果
 */
public class RunAgent {

    private static final Logger log = LoggerFactory.getLogger(RunAgent.class);

    /** 最大循环次数 {} */
    private static final int MAX_ITERATIONS = Integer.MAX_VALUE;

    /** 默认最大 tokens */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    /** 默认温度 */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    // =====================
    // 内置代理执行方法（静态入口，供 AgentTool 调用）
    // =====================

    /**
     * 执行 general-purpose 代理
     * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/generalPurposeAgent.ts
     *
     * @param prompt 用户提示词
     * @param background 是否后台运行
     * @param parentContext 父级工具使用上下文（从调用方传入，不再依赖 ThreadLocal）
     * @return 执行结果 JSON
     */
    public static String runGeneralPurpose(String prompt, boolean background, ToolUseContext parentContext) {
        log.info("=== RunAgent.runGeneralPurpose START === prompt={}, background={}", prompt, background);
        try {
            AgentDefinition agentDef = GeneralPurposeAgent.getAgentDefinition();
            String systemPrompt = agentDef.getSystemPrompt();
            String agentType = agentDef.getAgentType();
            log.info("GeneralPurpose agent: agentType={}", agentType);

            // 解析并过滤工具
            List<Tool> availableTools = getToolsFromContextAsToolList(parentContext, parentContext.getToolView());
            ResolvedAgentTools resolvedTools = resolveAgentTools(parentContext, agentDef, availableTools, false);

            // 创建子代理上下文，包含解析后的工具
            ToolUseContext context = createSubagentContextWithTools(parentContext, agentType, resolvedTools.resolvedTools, parentContext.getToolView());

            String result = executeQueryLoop(systemPrompt, prompt, agentType, null, null, context);
            log.info("=== RunAgent.runGeneralPurpose END === result length={}，result:\n{}", result != null ? result.length() : 0, result);
            return result;
        } catch (Exception e) {
            log.error("Error running general-purpose agent", e);
            return buildErrorResult(e.getMessage());
        }
    }

    /**
     * 执行 Explore 代理
     * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/exploreAgent.ts
     *
     * @param prompt 用户提示词
     * @param background 是否后台运行
     * @param parentContext 父级工具使用上下文（从调用方传入，不再依赖 ThreadLocal）
     * @return 执行结果 JSON
     */
    public static String runExplore(String prompt, boolean background, ToolUseContext parentContext) {
        log.info("=== RunAgent.runExplore START === prompt={}, background={}", prompt, background);
        try {
            AgentDefinition agentDef = ExploreAgent.getAgentDefinition();
            String systemPrompt = agentDef.getSystemPrompt();
            String agentType = agentDef.getAgentType();
            String model = agentDef.getModel();
            log.info("Explore agent: agentType={}, model={}", agentType, model);

            // 解析并过滤工具
            List<Tool> availableTools = getToolsFromContextAsToolList(parentContext, parentContext.getToolView());
            ResolvedAgentTools resolvedTools = resolveAgentTools(parentContext, agentDef, availableTools, false);

            // 创建子代理上下文，包含解析后的工具
            ToolUseContext context = createSubagentContextWithTools(parentContext, agentType, resolvedTools.resolvedTools,  parentContext.getToolView());

            String result = executeQueryLoop(systemPrompt, prompt, agentType, model, null, context);
            log.info("=== RunAgent.runExplore END === result length={}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("Error running explore agent", e);
            return buildErrorResult(e.getMessage());
        }
    }

    /**
     * 执行 Plan 代理
     * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/planAgent.ts
     *
     * @param prompt 用户提示词
     * @param background 是否后台运行
     * @param parentContext 父级工具使用上下文（从调用方传入，不再依赖 ThreadLocal）
     * @return 执行结果 JSON
     */
    public static String runPlan(String prompt, boolean background, ToolUseContext parentContext) {
        log.info("=== RunAgent.runPlan START === background={}", background);
        try {
            AgentDefinition agentDef = PlanAgent.getAgentDefinition();
            String systemPrompt = agentDef.getSystemPrompt();
            String agentType = agentDef.getAgentType();


            // 解析并过滤工具
            List<Tool> availableTools = getToolsFromContextAsToolList(parentContext, parentContext.getToolView());
            ResolvedAgentTools resolvedTools = resolveAgentTools(parentContext, agentDef, availableTools, false);

            // 创建子代理上下文，包含解析后的工具
            ToolUseContext context = createSubagentContextWithTools(parentContext, agentType, resolvedTools.resolvedTools, parentContext.getToolView());

            String result = executeQueryLoop(systemPrompt, prompt, agentType, null, null, context);
            log.info("=== RunAgent.runPlan END ===");
            return result;
        } catch (Exception e) {
            log.error("Error running plan agent", e);
            return buildErrorResult(e.getMessage());
        }
    }

    // =====================
    // 核心 Query 循环
    // =====================

    /**
     * 执行 query 循环（公开入口，供 BackgroundAgentExecutor 调用）
     * 对应 Open-ClaudeCode: runAgent.ts 中的 query 循环
     */
    public static String executeQueryLoopAsync(String systemPrompt, String userPrompt,
                                               String agentType, String model,
                                               LLMProvider provider, ToolUseContext toolUseContext) {
        return executeQueryLoop(systemPrompt, userPrompt, agentType, model, provider, toolUseContext);
    }

    /**
     * 执行 query 循环
     * 对应 Open-ClaudeCode: runAgent.ts 中的 query 循环
     * 核心流程：
     * 1. 构建消息列表
     * 2. 调用 LLM
     * 3. 处理工具调用
     * 4. 循环直到完成
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param agentType 代理类型
     * @param model 模型（可选，null 使用默认）
     * @param provider LLM 提供者（可选，null 返回占位结果）
     * @param toolUseContext 工具使用上下文（用于获取可用工具）
     * @return 执行结果 JSON
     */
    private static String executeQueryLoop(String systemPrompt, String userPrompt,
                                          String agentType, String model,
                                          LLMProvider provider, ToolUseContext toolUseContext) {
        // 关键：将 toolUseContext 设置到 ThreadLocal 中，以便 executeTool 能够获取
        // 使用 runWithContext 确保上下文正确设置和清理
        return executeQueryLoopWithContext(systemPrompt, userPrompt, agentType, model, provider, toolUseContext);
    }

    /**
     * 在上下文环境中执行 query 循环的实际逻辑
     */
    private static String executeQueryLoopWithContext(String systemPrompt, String userPrompt,
                                          String agentType, String model,
                                          LLMProvider provider, ToolUseContext toolUseContext) {
        // 构建消息列表
        List<Map<String, Object>> messages = buildMessages(systemPrompt, userPrompt);

        StringBuilder finalResult = new StringBuilder();
        AtomicInteger iterations = new AtomicInteger(0);
        AtomicBoolean stopSignal = new AtomicBoolean(false);

        // 获取可用工具数量用于日志
        int availableToolsCount = toolUseContext != null && toolUseContext.getTools() != null
                ? toolUseContext.getTools().size() : 0;
        log.info("[子代理 {}] 开始loop循环， 发现可用工具数： {}", agentType, availableToolsCount);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (stopSignal.get()) {
                log.info("[子代理 {}] loop循环结束: 循环总次数={}", agentType, iterations.get());
                break;
            }

            iterations.incrementAndGet();
            log.info("[子代理 {}] 循环次数 {} 开始", agentType, iterations.get());

            try {
                // 如果没有 provider，从配置中获取
                if (provider == null) {
                    provider = getProviderFromConfig(model);
                    if (provider == null) {
                        log.warn("[子代理 {}] 未配置模型, 返回占位符", agentType);
                        return buildPlaceholderResult(agentType, iterations.get());
                    }
                }

                // 从 ToolUseContext 获取可用工具
                List<Map<String, Object>> tools = getToolsFromContext(toolUseContext);
                log.debug("[子代理 {}] 循环次数 {}: , 可用工具数: {}", agentType, iterations.get(),
                        tools != null ? tools.size() : 0);

                // 调用 LLM
                LLMResponse response = callLLM(provider, messages, model, stopSignal, tools);

                if (response == null) {
                    log.error("[子代理 {}] 循环次数 {}: LLM call returned null response", agentType, iterations.get());
                    return buildErrorResult("LLM call returned null response");
                }
                
                // =================== 以下代表执行成功 ===================
                // 如果推理为空，从 content 中提取 think 标签并设置到 ReasoningContent
                if(StrUtil.isBlank(response.getReasoningContent())) {
                    String thinkBlock = Helpers.obtainThinkBlock(response.getContent());
                    if(StrUtil.isNotBlank(thinkBlock)) {
                        response.setReasoningContent(thinkBlock);
                    }
                }

                // 记录 LLM 思考内容
                if (response.getReasoningContent() != null && !response.getReasoningContent().isBlank()) {
                    log.info("[子代理 {}] 循环次数 {} LLM 思考:\n{}", agentType, iterations.get(), response.getReasoningContent());
                }

                // 移除思考标签，获取干净的内容
                String cleanContent = stripThink(response.getContent());
                if (cleanContent != null && !cleanContent.isBlank()) {
                    log.info("[子代理 {}] 循环次数 {} LLM 回复:\n{}", agentType, iterations.get(), cleanContent);
                }

                if (response.hasToolCalls()) {
                    log.info("[子代理 {}] 循环次数 {}: {} tool call(s)", agentType, iterations.get(), response.getToolCalls().size());



                    // 添加助手消息（复用主代理的 ContextBuilder）
                    List<Map<String, Object>> toolCallDicts = Helpers.buildToolCallDicts(response.getToolCalls());
                    String content = (cleanContent != null && !cleanContent.isEmpty()) ? cleanContent : null;
                    messages = Helpers.addAssistantMessage(
                            messages, content, toolCallDicts,
                            response.getReasoningContent(),
                            response.getThinkingBlocks());

                    // 处理每个工具调用
                    for (var toolCall : response.getToolCalls()) {
                        String toolName = toolCall.getName();
                        Map<String, Object> toolArgs = toolCall.getArguments();
                        String toolCallId = toolCall.getId();

                        log.info("[子代理 {}] 循环次数 {} 执行工具: name={}, id={}", agentType, iterations.get(), toolName, toolCallId);

                        String toolResult = executeTool(toolName, toolArgs, toolUseContext);

                        String toolResultForLog = toolResult;
                        if (toolResult.length() > 500) {
                            toolResultForLog = toolResult.substring(0, 500) + "... [truncated, total " + toolResult.length() + " chars]";
                        }
                        log.info("[子代理 {}] 循环次数 {} tool result: {}\n{}", agentType, iterations.get(), toolName, toolResultForLog);

                        // 添加工具结果消息（复用主代理的 ContextBuilder）
                        messages = Helpers.addToolResult(messages, toolCallId, toolName, toolResult);
                    }
                } else {
                    // 没有工具调用，返回结果
                    if (cleanContent != null && !cleanContent.isEmpty()) {
                        finalResult.append(cleanContent);
                    }
                    log.info("[子代理 {}] 循环次数 {}: no tool calls, finishing. Response length={}",
                            agentType, iterations.get(), finalResult.length());
                    break;
                }

            } catch (Exception e) {
                log.error("[子代理 {}] 循环次数 {} error: {}", agentType, iterations.get(), e.getMessage(), e);
                return buildErrorResult(e.getMessage());
            }
        }

        log.info("[子代理 {}] loop循环完成: 循环总次数={}, final result length={}",
                agentType, iterations.get(), finalResult.length());
        return buildResult(finalResult.toString(), agentType, iterations.get());
    }

    /**
     * 构建消息列表
     */
    private static List<Map<String, Object>> buildMessages(String systemPrompt, String userPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 添加系统消息
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }

        // 添加用户消息
        if (userPrompt != null && !userPrompt.isEmpty()) {
            messages.add(Map.of("role", "user", "content", userPrompt));
        }

        return messages;
    }

    /**
     * 调用 LLM
     * 对应 Open-ClaudeCode: query.ts 中的 LLM 调用
     */
    private static LLMResponse callLLM(LLMProvider provider,
                                       List<Map<String, Object>> messages,
                                       String model,
                                       AtomicBoolean stopSignal,
                                       List<Map<String, Object>> tools) {
        try {
            CompletableFuture<LLMResponse> future = provider.chatWithRetry(
                    messages,
                    tools,
                    model,
                    DEFAULT_MAX_TOKENS,
                    DEFAULT_TEMPERATURE,
                    null, // reasoningEffort
                    null, // think
                    null, // extraBody
                    stopSignal::get
            );

            return future.join();
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从 ToolUseContext 获取可用工具列表
     * 对应 Open-ClaudeCode: toolUseContext.options.tools
     *
     * @param context 工具使用上下文
     * @return 工具列表（可能为 null）
     */
    private static List<Map<String, Object>> getToolsFromContext(ToolUseContext context) {
        if (context == null || context.getTools() == null) {
            log.debug("No tools available in context");
            return null;
        }
        log.debug("Found {} tools in context", context.getTools().size());
        return context.getTools();
    }

    private static String extractToolName(Map<String, Object> def) {
        if (def == null) return null;
        Object fn = def.get("function");
        if (fn instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name == null ? null : String.valueOf(name);
        }
        return null;
    }

    /**
     * 从 ToolUseContext 获取可用工具列表作为 Tool 对象列表
     * 用于 resolveAgentTools
     *
     * @param context 工具使用上下文
     * @param toolView 工具视图（可选，用于查找工具实例）
     * @return Tool 对象列表
     */
    private static List<Tool> getToolsFromContextAsToolList(ToolUseContext context, ToolView toolView) {
        if (context == null || context.getTools() == null) {
            log.debug("No tools available in context for Tool list");
            return new ArrayList<>();
        }
        List<Tool> tools = new ArrayList<>();

        for (Map<String, Object> toolDef : context.getTools()) {
            String name = extractToolName(toolDef);
            if (name != null && toolView != null) {
                Tool t = toolView.get(name);
                if (t != null) {
                    tools.add(t);
                }
            }
        }
        log.debug("Found {} tools in context as Tool list", tools.size());
        return tools;
    }

    /**
     * 创建包含解析后工具的子代理上下文
     * 对应 Open-ClaudeCode: createSubagentContext 中设置 tools 的逻辑
     *
     * @param parentContext 父级上下文
     * @param agentType 代理类型
     * @param resolvedTools 解析后的工具列表
     * @param toolView 用于 fallback 的 ToolView（可为空）
     * @return 新的子代理上下文
     */
    private static ToolUseContext createSubagentContextWithTools(ToolUseContext parentContext, String agentType, List<Tool> resolvedTools, ToolView toolView) {
        // 将 Tool 对象列表转换为 Map 列表（工具定义格式）
        List<Map<String, Object>> toolDefs = new ArrayList<>();
        if (resolvedTools != null) {
            for (Tool tool : resolvedTools) {
                toolDefs.add(tool.toSchema());
            }
        }

        // 使用 createSubagentContext 基础结构，但覆盖 tools 字段
        // 注意：这里复用 createSubagentContext 的逻辑，但传入解析后的工具定义
        String agentId = "subagent-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // 第二选择：创建基于 fallbackToolView 的上下文
        if (parentContext == null) {
            if (toolView == null) {
                log.error("No parent context and no fallback ToolView available, cannot create subagent context");
                throw new IllegalStateException("Cannot create subagent context: no ToolView available");
            }
            log.warn("No parent context found, creating fallback context with provided ToolView");
            return ToolUseContext.builder()
                    .agentId(agentId)
                    .agentType(agentType)
                    .tools(toolDefs.isEmpty() ? toolView.getDefinitions() : toolDefs)
                    .toolView(toolView)
                    .nestedMemoryAttachmentTriggers(new java.util.HashSet<>())
                    .loadedNestedMemoryPaths(new java.util.HashSet<>())
                    .dynamicSkillDirTriggers(new java.util.HashSet<>())
                    .discoveredSkillNames(new java.util.HashSet<>())
                    .queryTracking(new ToolUseContext.QueryTracking())
                    .build();
        }

        // 创建 abort controller linked to parent
        AtomicBoolean childAbortController = new AtomicBoolean(false);
        AtomicBoolean parentAbort = parentContext.getAbortController();
        if (parentAbort != null) {
            Thread observer = new Thread(() -> {
                try {
                    while (!parentAbort.get() && !childAbortController.get()) {
                        Thread.sleep(100);
                    }
                    if (!childAbortController.get()) {
                        childAbortController.set(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            observer.setDaemon(true);
            observer.setName("subagent-abort-" + agentId);
            observer.start();
        }

        // 获取或创建 query tracking
        ToolUseContext.QueryTracking parentQueryTracking = parentContext.getQueryTracking();
        ToolUseContext.QueryTracking childQueryTracking =
                parentQueryTracking != null ? parentQueryTracking.child() : new ToolUseContext.QueryTracking();

        // 构建子代理上下文 - 复制父上下文的所有字段，但使用解析后的工具
        return ToolUseContext.builder()
                .agentId(agentId)
                .agentType(agentType)
                .abortController(childAbortController)
                .workspace(parentContext.getWorkspace())
                .restrictToWorkspace(parentContext.isRestrictToWorkspace())
                .mainLoopModel(parentContext.getMainLoopModel())
                .messages(null)
                .sessionId(parentContext.getSessionId())
                .mcpClients(parentContext.getMcpClients() != null ? new java.util.ArrayList<>(parentContext.getMcpClients()) : null)
                // 关键：使用解析后的工具（经过 disallowedTools 过滤）
                .tools(toolDefs)
                .nestedMemoryAttachmentTriggers(new java.util.HashSet<>())
                .loadedNestedMemoryPaths(new java.util.HashSet<>())
                .dynamicSkillDirTriggers(new java.util.HashSet<>())
                .discoveredSkillNames(new java.util.HashSet<>())
                .toolDecisions(null)
                .queryTracking(childQueryTracking)
                .fileReadingLimits(parentContext.getFileReadingLimits())
                .userModified(parentContext.getUserModified())
                .appState(parentContext.getAppState())
                .setAppState(parentContext.getSetAppState())
                .setAppStateForTasks(parentContext.getSetAppStateForTasks())
                .build();
    }

    /**
     * 执行工具
     * 对应 Open-ClaudeCode: tools.ts 中的工具执行
     *
     * @param toolName       工具名称
     * @param args           工具参数
     * @param context
     * @return 工具执行结果
     */
    private static String executeTool(String toolName, Map<String, Object> args, ToolUseContext context) {
        log.info("Executing tool: name={}, args={}", toolName, args);

        try {

            if (context == null) {
                log.warn("No ToolUseContext available, returning placeholder");
                return "{\"status\": \"no context\", \"tool\": \"" + toolName + "\"}";
            }

            // 从 ToolRegistry 获取工具
            Tool tool = context.getTool(toolName);
            if (tool == null) {
                log.warn("Tool not found: {}", toolName);
                return "{\"error\": \"Tool not found: " + toolName + "\"}";
            }

            // 执行工具
            String result = tool.execute(args).toCompletableFuture().join();
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("Tool execution failed: name={}", toolName, e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 解析代理工具
     * 对应 Open-ClaudeCode: agentToolUtils.ts - resolveAgentTools()
     *
     * @param parentContext
     * @param agentDefinition 代理定义
     * @param availableTools  可用工具列表
     * @param isAsync         是否异步
     * @return 解析后的工具
     */
    public static ResolvedAgentTools resolveAgentTools(
            ToolUseContext parentContext, AgentDefinition agentDefinition,
            List<Tool> availableTools,
            boolean isAsync
    ) {
        List<String> agentTools = agentDefinition != null ? agentDefinition.getTools() : null;
        List<String> disallowedTools = agentDefinition != null ? agentDefinition.getDisallowedTools() : null;
        PermissionMode permissionMode = agentDefinition != null ? agentDefinition.getPermissionMode() : PermissionMode.BYPASS_PERMISSIONS;
        String source = agentDefinition != null ? agentDefinition.getSource() : null;

        // 先过滤不允许的工具（对应 filterToolsForAgent）
        List<Tool> filteredAvailableTools = filterToolsForAgent(parentContext,
                availableTools,
                "built-in".equals(source),
                isAsync,
                permissionMode
        );

        // 创建 disallowed 工具集合用于快速查找（统一转小写用于忽略大小写比较）
        Set<String> disallowedToolSet = new java.util.HashSet<>();
        if (disallowedTools != null) {
            for (String toolSpec : disallowedTools) {
                String toolName = parseToolName(toolSpec);
                disallowedToolSet.add(toolName.toLowerCase());
            }
        }

        // 根据 disallowed 列表过滤可用工具（忽略大小写）
        List<Tool> allowedAvailableTools = filteredAvailableTools.stream()
                .filter(tool -> !disallowedToolSet.contains(tool.name().toLowerCase()))
                .collect(Collectors.toList());

        // 检查通配符
        boolean hasWildcard = agentTools == null ||
                (agentTools.size() == 1 && "*".equals(agentTools.get(0)));

        if (hasWildcard) {
            return new ResolvedAgentTools(true, List.of(), List.of(), allowedAvailableTools, List.of());
        }

        // 解析具体工具
        Map<String, Tool> toolMap = new HashMap<>();
        for (Tool tool : allowedAvailableTools) {
            toolMap.put(tool.name(), tool);
        }

        List<String> validTools = new ArrayList<>();
        List<String> invalidTools = new ArrayList<>();
        List<Tool> resolved = new ArrayList<>();
        Set<String> resolvedToolsSet = new java.util.HashSet<>();
        List<String> allowedAgentTypes = new ArrayList<>();

        for (String toolSpec : agentTools) {
            String toolName = parseToolName(toolSpec);
            Tool tool = toolMap.get(toolName);
            if (tool != null) {
                validTools.add(toolSpec);
                if (!resolvedToolsSet.contains(toolName)) {
                    resolved.add(tool);
                    resolvedToolsSet.add(toolName);
                }
                // 特殊处理 Agent 工具
                if ("agent".equals(toolName)) {
                    allowedAgentTypes = parseAgentTypes(toolSpec);
                }
            } else {
                invalidTools.add(toolSpec);
            }
        }

        return new ResolvedAgentTools(false, validTools, invalidTools, resolved, allowedAgentTypes);
    }

    /**
     * 过滤代理工具
     * 对应 Open-ClaudeCode: agentToolUtils.ts - filterToolsForAgent()
     */
    private static List<Tool> filterToolsForAgent(
            ToolUseContext parentContext, List<Tool> tools,
            boolean isBuiltIn,
            boolean isAsync,
            PermissionMode permissionMode
    ) {
        return tools.stream().filter(tool -> {
            String toolName = tool.name();

            // MCP 工具始终允许
            if (toolName.startsWith("mcp__")) {
                return true;
            }

            // ExitPlanMode 在 plan 模式下允许
            if ("ExitPlanMode".equals(toolName) && permissionMode == PermissionMode.PLAN) {
                return true;
            }

            // 检查全局不允许的工具
            if (AgentToolConstants.getAllAgentDisallowedToolsCached().contains(toolName)) {
                return false;
            }

            // 检查自定义代理不允许的工具
            if (!isBuiltIn && AgentToolConstants.getCustomAgentDisallowedToolsCached().contains(toolName)) {
                return false;
            }

            // 检查异步代理允许的工具
            if (isAsync && !AgentToolConstants.ASYNC_AGENT_ALLOWED_TOOLS.contains(toolName)) {
                // Agent Swarms 启用且是进程内队友
                if (isAgentSwarmsEnabled() && isInProcessTeammate(parentContext)) {
                    // 允许 AgentTool 用于进程内队友
                    if ("agent".equals(toolName)) {
                        return true;
                    }
                    // 允许任务工具用于进程内队友协调
                    if (AgentToolConstants.IN_PROCESS_TEAMMATE_ALLOWED_TOOLS.contains(toolName)) {
                        return true;
                    }
                }
                return false;
            }

            return true;
        }).collect(Collectors.toList());
    }

    /**
     * 检查是否是进程内队友
     */
    private static boolean isInProcessTeammate(ToolUseContext context) {
        // 从上下文检查是否是进程内队友
        return context != null && context.isInProcessTeammate();
    }

    /**
     * 检查是否启用 Agent Swarms
     * 对应 Open-ClaudeCode: src/utils/agentSwarmsEnabled.ts - isAgentSwarmsEnabled()
     */
    private static boolean isAgentSwarmsEnabled() {
        // Ant: always on
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            return true;
        }

        // External: require opt-in via env var or flag
        String experimentalAgentTeams = System.getenv("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS");
        boolean hasEnvFlag = "true".equalsIgnoreCase(experimentalAgentTeams) ||
                            "1".equalsIgnoreCase(experimentalAgentTeams);

        // TODO: Check --agent-teams flag via isAgentTeamsFlagSet()
        // For now, rely on env var
        if (!hasEnvFlag) {
            return false;
        }

        // Killswitch - for now always return true
        // TODO: Check feature flag tengu_amber_flint
        return true;
    }

    /**
     * 解析工具名称
     */
    private static String parseToolName(String toolSpec) {
        if (toolSpec == null) return "";
        // 处理 "agent:general-purpose" 格式
        int colonIndex = toolSpec.indexOf(':');
        if (colonIndex > 0) {
            return toolSpec.substring(0, colonIndex);
        }
        return toolSpec;
    }

    /**
     * 解析代理类型
     */
    private static List<String> parseAgentTypes(String toolSpec) {
        List<String> types = new ArrayList<>();
        if (toolSpec == null) return types;

        // 处理 "agent:general-purpose,explore" 格式
        int colonIndex = toolSpec.indexOf(':');
        if (colonIndex > 0 && colonIndex < toolSpec.length() - 1) {
            String typesStr = toolSpec.substring(colonIndex + 1);
            types.addAll(Arrays.asList(typesStr.split(",")));
        }
        return types;
    }

    /**
     * 解析后的代理工具
     */
    public static class ResolvedAgentTools {
        public final boolean hasWildcard;
        public final List<String> validTools;
        public final List<String> invalidTools;
        public final List<Tool> resolvedTools;
        public final List<String> allowedAgentTypes;

        public ResolvedAgentTools(boolean hasWildcard, List<String> validTools,
                                  List<String> invalidTools, List<Tool> resolvedTools,
                                  List<String> allowedAgentTypes) {
            this.hasWildcard = hasWildcard;
            this.validTools = validTools;
            this.invalidTools = invalidTools;
            this.resolvedTools = resolvedTools;
            this.allowedAgentTypes = allowedAgentTypes;
        }
    }

    // =====================
    // 结果构建方法
    // =====================

    private static String buildResult(String content, String agentType, int iterations) {
        return String.format(
                "{\"status\": \"completed\", \"content\": \"%s\", \"agentType\": \"%s\", \"iterations\": %d}",
                escapeJson(content), agentType, iterations
        );
    }

    private static String buildErrorResult(String error) {
        return "{\"status\": \"error\", \"error\": \"" + escapeJson(error) + "\"}";
    }

    private static String buildPlaceholderResult(String agentType, int iterations) {
        return String.format(
                "{\"status\": \"placeholder\", \"content\": \"LLM provider not configured\", \"agentType\": \"%s\", \"iterations\": %d}",
                agentType, iterations
        );
    }

    /**
     * 从配置中获取 LLM Provider
     *
     * 当 provider 参数为 null 时调用此方法
     */
    private static LLMProvider getProviderFromConfig(String model) {
        try {
            Config config = ConfigIO.loadConfig(null);
            if (config == null) {
                log.warn("Failed to load config for provider");
                return null;
            }
            String defaultModel = model;
            String providerName = null;
            if (defaultModel == null || defaultModel.isBlank()) {
                defaultModel = config.getAgents().getDefaults().getModel();
                providerName = config.getAgents().getDefaults().getProvider();
            }
            if (defaultModel == null || defaultModel.isBlank()) {
                log.warn("No default model configured");
                return null;
            }

            log.info("getProviderFromConfig: using model={}", defaultModel);
            return ProviderFactory.createProvider(config, providerName, defaultModel);
        } catch (Exception e) {
            log.error("Error creating provider from config", e);
            return null;
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // =====================
    // 原有方法（保留兼容）
    // =====================

    /**
     * 执行代理
     * 对应: export async function* runAgent({...})
     */
    public static AgentToolResult execute(RunAgentParams params) {
        log.info("Executing agent: type={}, prompt={}", params.agentType, params.prompt);

        // 创建隔离的子代理上下文
        // 对应 Open-ClaudeCode: createSubagentContext()
        ToolUseContext isolatedContext = createSubagentContext(params);

        // 创建子代理专属 MCP 连接（如果有的话）
        // 对应 Open-ClaudeCode: initializeAgentMcpServers()
        AgentMcpConnection agentMcpConnection = null;
        if (params.agent != null) {
            try {
                String workspace = params.toolUseContext != null ? params.toolUseContext.getWorkspace() : null;
                agentMcpConnection = AgentMcpConnection.create(
                        params.agentId,
                        params.agent,
                        workspace
                );
                log.debug("Created agent MCP connection: hasAdditionalTools={}",
                        agentMcpConnection != null && agentMcpConnection.hasAdditionalTools());
            } catch (Exception e) {
                log.warn("Failed to create agent MCP connection: {}", e.getMessage());
            }
        }

        try {
            // 1. 获取系统提示词
            String systemPrompt = getSystemPromptForAgentType(params.agentType);

            // 2. 执行 query 循环（使用隔离的上下文）
            String result = executeQueryLoop(
                    systemPrompt,
                    params.prompt,
                    params.agentType,
                    params.defaultModel,
                    null, // provider - 需要从上下文获取
                    isolatedContext
            );

            // 3. 返回结果
            return AgentToolResult.success(result);

        } catch (Exception e) {
            log.error("Error executing agent", e);
            return AgentToolResult.failure(e.getMessage());
        } finally {
            // 清理子代理专属 MCP 连接
            // 对应 Open-ClaudeCode: await mcpCleanup()
            if (agentMcpConnection != null) {
                try {
                    agentMcpConnection.close();
                    log.debug("Closed agent MCP connection for {}", params.agentType);
                } catch (Exception e) {
                    log.warn("Error closing agent MCP connection: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 根据代理类型获取系统提示词
     */
    private static String getSystemPromptForAgentType(String agentType) {
        if (agentType == null) {
            return GeneralPurposeAgent.getSystemPrompt();
        }
        switch (agentType) {
            case "Explore":
                return ExploreAgent.getSystemPrompt();
            case "Plan":
                return PlanAgent.getSystemPrompt();
            default:
                return GeneralPurposeAgent.getSystemPrompt();
        }
    }

    /**
     * 创建隔离的子代理 ToolUseContext
     * 对应 Open-ClaudeCode: src/utils/forkedAgent.ts - createSubagentContext()
     *
     * 创建子代理的隔离上下文，防止对父代理状态的干扰：
     * 1. 克隆文件状态缓存 (readFileState)
     * 2. 创建新的追踪集合 (nestedMemoryAttachmentTriggers, discoveredSkillNames 等)
     * 3. 处理 abortController（创建子控制器或共享父控制器）
     * 4. 包装 getAppState 设置 shouldAvoidPermissionPrompts
     * 5. 克隆 contentReplacementState
     *
     * @param params 执行参数
     * @return 隔离的 ToolUseContext
     */
    private static ToolUseContext createSubagentContext(RunAgentParams params) {
        ToolUseContext parentContext = params.toolUseContext;
        if (parentContext == null) {
            // 没有父上下文，返回新的空上下文
            log.warn("No parent context provided, creating minimal subagent context");
            return ToolUseContext.builder()
                    .agentId(params.agentId)
                    .nestedMemoryAttachmentTriggers(new java.util.HashSet<>())
                    .loadedNestedMemoryPaths(new java.util.HashSet<>())
                    .dynamicSkillDirTriggers(new java.util.HashSet<>())
                    .discoveredSkillNames(new java.util.HashSet<>())
                    .queryTracking(new ToolUseContext.QueryTracking())
                    .build();
        }

        // 创建子 AbortController（链接到父控制器）
        AtomicBoolean childAbortController = new AtomicBoolean(false);
        AtomicBoolean parentAbort = parentContext.getAbortController();
        if (parentAbort != null) {
            // 创建观察线程，当父中止时子也中止
            Thread observer = new Thread(() -> {
                try {
                    // 等待父中止信号（最多 30 天）
                    while (!parentAbort.get() && !childAbortController.get()) {
                        Thread.sleep(100);
                    }
                    if (!childAbortController.get()) {
                        childAbortController.set(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            observer.setDaemon(true);
            observer.setName("subagent-abort-observer-" + params.agentId);
            observer.start();
        }

        // 克隆文件状态缓存
        Map<String, Object> clonedFileState = cloneFileStateCache(parentContext);

        // 获取父查询追踪并创建子追踪链（depth +1）
        ToolUseContext.QueryTracking parentQueryTracking = parentContext.getQueryTracking();
        ToolUseContext.QueryTracking childQueryTracking;
        if (parentQueryTracking != null) {
            childQueryTracking = parentQueryTracking.child();
        } else {
            childQueryTracking = new ToolUseContext.QueryTracking();
        }

        // 构建隔离的 ToolUseContext
        // 对应 TypeScript createSubagentContext() 的完整实现
        // 关键：复制 tools 从父上下文，这是 subagent 能够调用工具的前提
        ToolUseContext.Builder builder = ToolUseContext.builder()
                // 基础字段
                .agentId(params.agentId)
                .agentType(params.agentType)
                .abortController(childAbortController)
                .workspace(parentContext.getWorkspace())
                .restrictToWorkspace(parentContext.isRestrictToWorkspace())
                .mainLoopModel(parentContext.getMainLoopModel())
                .messages(null) // 子代理有自己的消息列表
                .sessionId(parentContext.getSessionId())

                // MCP 客户端 - 克隆自父级
                .mcpClients(parentContext.getMcpClients())

                // 工具列表 - 从父上下文复制（关键修复）
                // 这是 subagent 能够使用工具的核心：LLM 需要工具定义来知道可以调用哪些工具
                .tools(parentContext.getTools())

                // 上下文隔离字段 - 全部新建隔离集合
                .nestedMemoryAttachmentTriggers(new java.util.HashSet<>())
                .loadedNestedMemoryPaths(new java.util.HashSet<>())
                .dynamicSkillDirTriggers(new java.util.HashSet<>())
                .discoveredSkillNames(new java.util.HashSet<>())
                .toolDecisions(null) // 新的工具决策状态

                // 内容替换状态 - 克隆自父级以保持 prompt cache 稳定性
                .contentReplacementState(cloneContentReplacementState(parentContext.getContentReplacementState()))

                // 查询追踪链 - 深度 +1
                .queryTracking(childQueryTracking)

                // 文件读取限制 - 继承自父级
                .fileReadingLimits(parentContext.getFileReadingLimits())

                // 用户修改状态 - 继承自父级
                .userModified(parentContext.getUserModified());

        // 克隆 MCP 客户端
        if (parentContext.getMcpClients() != null) {
            builder.mcpClients(new java.util.ArrayList<>(parentContext.getMcpClients()));
        }

        // 包装 appState 设置 shouldAvoidPermissionPrompts
        Object parentAppState = parentContext.getAppState();
        Object parentSetAppState = parentContext.getSetAppState();
        Object parentSetAppStateForTasks = parentContext.getSetAppStateForTasks();

        // 设置 AppState（隔离的视图）
        builder.appState(parentAppState);

        // setAppState 设为空操作（除非显式共享）
        // 对应 TypeScript: setAppState: overrides?.shareSetAppState ? parentContext.setAppState : () => {}
        builder.setAppState(parentSetAppStateForTasks != null ? parentSetAppStateForTasks : parentSetAppState);

        // setAppStateForTasks 始终指向根存储（即使 setAppState 是 no-op）
        // 这样异步代理的后台 bash 任务才能被注册和终止
        if (parentSetAppStateForTasks != null) {
            builder.setAppStateForTasks(parentSetAppStateForTasks);
        } else if (parentSetAppState != null) {
            builder.setAppStateForTasks(parentSetAppState);
        }

        // 本地拒绝跟踪状态 - 隔离的或共享的
        // 对应 TypeScript: localDenialTracking: overrides?.shareSetAppState ? parentContext.localDenialTracking : createDenialTrackingState()
        // Java 版本暂时创建简单的拒绝跟踪状态
        builder.localDenialTracking(new agent.subagent.context.DenialTrackingState());

        // 回调函数 - 全部设为 no-op 或 undefined
        // setInProgressToolUseIDs
        builder.setInProgressToolUseIDs(ids -> {});
        // setResponseLength - 共享的
        Consumer<Long> parentSetResponseLength = parentContext.getSetResponseLength();
        builder.setResponseLength(parentSetResponseLength != null ? parentSetResponseLength : count -> {});
        // pushApiMetricsEntry - 共享的
        Consumer<Long> parentPushApiMetricsEntry = parentContext.getPushApiMetricsEntry();
        builder.pushApiMetricsEntry(parentPushApiMetricsEntry);
        // updateFileHistoryState
        builder.updateFileHistoryState(() -> {});
        // updateAttributionState - 函数式，可以安全共享
        builder.updateAttributionState(parentContext.getUpdateAttributionState());

        // UI 回调 - 全部为 undefined（子代理不能控制父代理 UI）
        // 对应 TypeScript: addNotification: undefined, setToolJSX: undefined, etc.
        builder.addNotification(null);
        builder.setToolJSX(null);
        builder.setStreamMode(null);
        builder.setSDKStatus(null);
        builder.openMessageSelector(null);

        // 其他字段
        builder.criticalSystemReminder_EXPERIMENTAL(parentContext.getCriticalSystemReminder_EXPERIMENTAL());
        builder.requireCanUseTool(parentContext.getRequireCanUseTool());
        builder.isNonInteractiveSession(parentContext.getIsNonInteractiveSession());

        log.debug("Created isolated subagent context: agentId={}, hasParentAbort={}, queryTrackingDepth={}",
                params.agentId, parentAbort != null, childQueryTracking.getDepth());

        return builder.build();
    }

    /**
     * 克隆内容替换状态（用于 prompt cache 稳定性）
     */
    private static Object cloneContentReplacementState(Object parentState) {
        if (parentState == null) {
            return null;
        }
        // Java 版本暂时直接返回父状态
        // 完整的深拷贝需要了解具体类型结构
        return parentState;
    }

    /**
     * 克隆文件状态缓存
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cloneFileStateCache(ToolUseContext parentContext) {
        // 从父上下文获取文件状态（如果存在）
        // 注意：Java 版本使用 ConcurrentHashMap 作为文件状态缓存
        return new java.util.concurrent.ConcurrentHashMap<>();
    }

    // =====================
    // 执行参数
    // =====================

    public static class RunAgentParams {
        public AgentDefinition agent;
        public String prompt;
        public String agentType;
        public String agentId;
        public String parentAgentId;
        public String defaultModel;
        public ToolUseContext toolUseContext;
        public boolean background;
        public int maxTurns;

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
