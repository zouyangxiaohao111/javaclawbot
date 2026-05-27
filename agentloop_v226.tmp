package agent;

import agent.command.CommandQueueManager;
import agent.command.LocalCommand;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskControlService;
import agent.subagent.task.TaskRegistry;
import agent.subagent.fork.ForkAgentExecutor;
import agent.subagent.fork.ForkContext;
import agent.subagent.context.SubagentContext;
import agent.tool.*;
import agent.tool.cli.CliAgentTool;
import agent.tool.cron.CronTool;
import agent.tool.plan.AskUserQuestionTool;
import agent.tool.plan.EnterPlanModeTool;
import agent.tool.plan.ExitPlanModeTool;
import agent.tool.task.TodoWriteTool;
import agent.tool.file.*;
import agent.tool.mcp.McpManager;
import agent.tool.message.MessageTool;
import agent.tool.message.PruneMessagesTool;
import agent.tool.shell.ExecTool;
import agent.tool.shell.Shell;
import agent.tool.skill.SkillTool;
import agent.tool.task.TaskStopTool;
import agent.tool.web.WebFetchTool;
import agent.tool.web.WebSearchTool;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import config.Config;
import config.agent.AgentRuntimeSettings;
import config.agent.SessionMemoryConfig;
import config.provider.model.ModelConfig;

import config.channel.ChannelsConfig;
import config.mcp.MCPServerConfig;
import config.tool.ToolsConfig;
import config.tool.WebFetchConfig;
import config.tool.WebSearchConfig;
import context.ContextBuilder;
import context.ContextOverflowDetector;
import context.ContextWindowDiscovery;
import context.auto.AutoCompactService;
import context.auto.AutoCompactThreshold;
import context.auto.CompactBoundary;
import context.auto.CompactPrompt;
import context.auto.CompactService;
import context.auto.CompactionResult;
import context.auto.MicroCompactService;
import context.auto.PlanFileManager;
import context.auto.PostCompactCleanup;
import context.auto.ReadFileState;
import context.auto.SessionMemoryCompactService;
import corn.CronService;
import memory.MemoryStore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.CancelChecker;
import providers.ToolCallRequest;
import providers.cli.CliAgentCommandHandler;
import context.ContextPruner;
import context.ContextPruningSettings;
import session.Session;
import session.SessionManager;
import session.SessionMemoryService;
import session.SessionMemoryUtils;
import skills.SkillsLoader;
import utils.GsonFactory;
import utils.Helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static utils.Helpers.stripThink;
import static utils.Helpers.toolHint;

public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /**
     * 工具返回结果最大字符数
     */
    private static final int TOOL_RESULT_MAX_CHARS = 10_000;

    private final MessageBus bus;
    private final ChannelsConfig channelsConfig;
    private volatile LLMProvider provider;
    private final java.nio.file.Path workspace;
    private volatile String model;
    private final int maxIterations;
    private volatile double temperature;
    private volatile int maxTokens;
    private volatile int contextWindow;
    private final int memoryWindow;
    private volatile String reasoningEffort;
    private final CronService cronService;
    private final boolean restrictToWorkspace;
    /**
     * 是否启用思考模式（保留推理内容）
     */
    private ContextBuilder context;
    private final SessionManager sessions;
    /**
     * 任务系统状态（Phase 4 集成）
     */
    private final AppState appState;
    /**
     * 任务注册表（Phase 4 集成）
     */
    private final TaskRegistry taskRegistry;
    /**
     * 任务控制服务（Phase 4 集成）
     */
    private final TaskControlService taskControl;
    /**
     * Fork 代理执行器（Phase 4 集成）
     */
    private final ForkAgentExecutor forkAgentExecutor;
    /**
     * Session Memory 服务
     */
    private final SessionMemoryService sessionMemoryService;
    /**
     * Read file state tracking (for post-compact file restoration)
     * Aligned with Open-ClaudeCode readFileState in ToolUseContext
     */
    private final ReadFileState readFileState = new ReadFileState();
    private final AgentRuntimeSettings runtimeSettings;
    private final ExecutorService executor;
    private final MemoryStore memoryStore;
    private final CommandQueueManager commandManager;
    private final SkillsLoader skillsLoader;
    /**
     * 全局共享工具
     */
    private final ToolRegistry sharedTools;
    /**
     * 共享 FileStateCache：Read 读入缓存 → Edit/Write 校验通过
     */
    private final FileStateCache sharedFileCache;
    private volatile AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, MCPServerConfig> mcpServers;
    /**
     * MCP 动态工具管理器
     */
    private final McpManager mcpManager;
    /**
     * CLI Agent 命令处理器
     */
    private CliAgentCommandHandler cliAgentHandler;

    // 跟踪已记录的 CLI session ID，避免重复添加 reference marker
    private final Set<String> recordedCliSessions = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableFuture<?>>> activeTasks = new ConcurrentHashMap<>();

    /**
     * Usage 累积器（按会话）
     */
    private final ConcurrentHashMap<String, UsageAccumulator> usageTrackers = new ConcurrentHashMap<>();

    /**
     * 并发队列（对齐 OpenClaw Lane-aware FIFO）
     */
    private final AgentLoopQueue queue;

    /**
     * 提供给外部（如 Commands / CronService 回调）设置 cron 执行上下文的稳定实例。
     * 真正执行时不会直接复用它，而是在每次请求构建请求级 CronTool，并复制其 cronContext。
     */
    private final CronTool cronToolFacade;

    /**
     * 会话级停止标记
     * key = sessionKey
     * value = true 表示该会话当前被请求停止
     */
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    /**
     * 当前会话挂起中的 LLM 请求/重试任务
     * stop 时统一 cancel
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableFuture<?>>> activeLlmCalls =
            new ConcurrentHashMap<>();

    /** AskUserQuestion 暂停的工具链：toolCallId → 等待用户答案的 Future */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingUserQuestions =
            new ConcurrentHashMap<>();

    public AgentLoop(
            MessageBus bus,
            LLMProvider provider,
            java.nio.file.Path workspace,
            String model,
            Integer maxIterations,
            Double temperature,
            Integer maxTokens,
            Integer contextWindow,
            Integer memoryWindow,
            String reasoningEffort,
            CronService cronService,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            Map<String, MCPServerConfig> mcpServers,
            ChannelsConfig channelsConfig,
            AgentRuntimeSettings runtimeSettings
    ) {
        this.bus = bus;
        this.channelsConfig = channelsConfig;
        this.provider = provider;
        this.workspace = workspace;
        this.model = (model != null && !model.isBlank()) ? model : provider.getDefaultModel();
        this.maxIterations = (maxIterations != null) ? maxIterations : 100;
        this.temperature = (temperature != null) ? temperature : 0.1;
        this.maxTokens = (maxTokens != null) ? maxTokens : 4096;
        this.memoryWindow = (memoryWindow != null) ? memoryWindow : 100;
        this.reasoningEffort = reasoningEffort;
        this.contextWindow = contextWindow;
        this.cronService = cronService;
        this.restrictToWorkspace = restrictToWorkspace;
        this.runtimeSettings = runtimeSettings;

        this.sessions = (sessionManager != null) ? sessionManager : new SessionManager(workspace);
        this.executor = new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors(),
                Math.max(4, Runtime.getRuntime().availableProcessors()),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    private final ThreadFactory delegate = Executors.defaultThreadFactory();
                    private final AtomicInteger idx = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = delegate.newThread(r);
                        t.setName("agent-" + idx.getAndIncrement());
                        t.setDaemon(false); // 关键：不要是 daemon
                        return t;
                    }
                }
        );

        // Phase 4: 初始化任务系统
        this.appState = new AppState();
        this.taskRegistry = TaskRegistry.getInstance();
        this.taskControl = new TaskControlService(taskRegistry);

        this.mcpServers = (mcpServers != null) ? mcpServers : Map.of();
        this.mcpManager = new McpManager(workspace, mcpServers, executor);

        // 初始化 CLI Agent 命令处理器（内部管理 defaultRegistry + per-session 注册表）
        this.cliAgentHandler = new CliAgentCommandHandler(workspace);
        this.cliAgentHandler.setSendToChannelWithMeta((message, sessionKey, metadata) -> {
            // sessionKey 可能为 null（如 projectSessionKeys 没有映射时）
            // 这种情况下，使用 CLI 通道作为默认输出
            if (sessionKey == null || sessionKey.isBlank()) {
                // 尝试从 metadata 获取 session_key
                if (metadata != null && metadata.containsKey("cli_session_id")) {
                    // 使用 CLI 直接输出通道
                    sessionKey = "cli:direct";
                } else {
                    log.debug("CLI Agent output without sessionKey, skipping: {}", message);
                    return;
                }
            }

            // sessionKey 格式: "channel:chatId"
            String[] parts = sessionKey.split(":", 2);
            String channel = parts[0];
            String chatId = parts.length > 1 ? parts[1] : "";

            // 发送到渠道
            bus.publishOutbound(new OutboundMessage(
                    channel,
                    chatId,
                    message,
                    List.of(),
                    metadata != null ? metadata : Map.of()
            ));

            // 如果是 CLI 子代理输出（仅 TEXT、RESULT、ERROR 等带 cliAgentOutput=true 的消息）
            // 通道通知保留，但主 session 只记录一次引用
            if (metadata != null && Boolean.TRUE.equals(metadata.get("cliAgentOutput"))) {
                String project = (String) metadata.get("project");
                String agentType = (String) metadata.get("agent_type");
                String cliSessionId = (String) metadata.get("cli_session_id");

                // 去重：每个 CLI session 只记录一次 reference marker
                String sessionFileKey = sessionKey + ":" + cliSessionId;
                if (recordedCliSessions.add(sessionFileKey)) {
                    Session session = sessions.getOrCreate(sessionKey);
                    if (session != null) {
                        String referenceMarker = "[CLI Session: " + (agentType != null ? agentType : "unknown") + "/" + project + "]";

                        String safeChannel = channel.replaceAll("[^a-zA-Z0-9_-]", "_");
                        String safeChatId = chatId.replaceAll("[^a-zA-Z0-9_-]", "_");

                        String cliSessionFile = safeChannel + "_" + safeChatId + "_" +
                                (project != null ? project : "unknown") + "_" +
                                (agentType != null ? agentType : "unknown") + "_" +
                                (cliSessionId != null ? cliSessionId : "default") + ".jsonl";

                        session.addMessage("assistant", referenceMarker, Map.of(
                                "source", "cli_session_ref",
                                "project", project,
                                "agent_type", agentType,
                                "cli_session_id", cliSessionId,
                                "cli_session_file", cliSessionFile,
                                "session_key", sessionKey
                        ));

                        log.debug("Recorded CLI session reference: project={}, agentType={}, sessionId={}, file={}",
                                project, agentType, cliSessionId, cliSessionFile);
                    }
                }
            }
        });

        // 设置 CLI Agent 完成回调：通知主代理 CLI Agent 已完成
        this.cliAgentHandler.getAgentPool().setCompletionCallback(completionEvent -> {
            handleCliAgentCompletion(completionEvent);
        });

        // 创建共享工具和文件缓存（需要在 registerSharedTools 之前）
        this.sharedTools = new ToolRegistry();
        this.sharedFileCache = new FileStateCache();

        // 注册工具
        registerSharedTools();

        // 技能工具
        this.skillsLoader = new SkillsLoader(workspace);
        this.commandManager = new CommandQueueManager(skillsLoader);

        registerSharedTools();

        // Phase 4: 初始化 ForkAgentExecutor（传入 sharedTools 作为 ToolView）
        java.nio.file.Path sessionsDir = workspace.resolve("sessions");
        this.forkAgentExecutor = new ForkAgentExecutor(provider, workspace, sessionsDir,
            (runId, directive, result) -> {
                // Fork 完成回调 - 通知任务完成
                log.info("Fork [{}] completed: {}", runId, directive);
            },
            appState,
            appState.setter(),
            new agent.tool.CompositeToolView(sharedTools));

        // 初始化 Session Memory 服务
        config.agent.SessionMemoryConfig smConfig = currentConfig().getAgents().getDefaults().getSessionMemory();
        this.sessionMemoryService = new SessionMemoryService(smConfig, workspace, provider, forkAgentExecutor);

        int maxConcurrent = 4;
        if (runtimeSettings != null) {
            var cfg = runtimeSettings.getCurrentConfig();
            maxConcurrent = cfg.getAgents().getDefaults().getMaxConcurrent();
        }
        this.context = new ContextBuilder(workspace, currentConfig().getAgents().getDefaults().getBootstrapConfig(), null, cliAgentHandler::getProjectRegistry);
        this.memoryStore = new MemoryStore(workspace);
        // AgentLoopQueue 使用独立线程池，避免 executeImmediately 中的 join() 阻塞共享线程池导致死锁
        this.queue = new AgentLoopQueue(maxConcurrent);
        // 设置任务开始回调，清除停止标记，确保队列任务不受之前 /stop 命令影响
        this.queue.setOnTaskStart(sessionKey -> clearStopRequested(sessionKey));
        this.cronToolFacade = (cronService != null) ? new CronTool(cronService) : null;
    }

    /** 带 ProjectRegistry 的构造器（GUI 使用，按 session 隔离项目绑定） */
    public AgentLoop(
            MessageBus bus,
            LLMProvider provider,
            java.nio.file.Path workspace,
            String model,
            Integer maxIterations,
            Double temperature,
            Integer maxTokens,
            Integer contextWindow,
            Integer memoryWindow,
            String reasoningEffort,
            CronService cronService,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            Map<String, MCPServerConfig> mcpServers,
            ChannelsConfig channelsConfig,
            AgentRuntimeSettings runtimeSettings,
            providers.cli.ProjectRegistry projectRegistry
    ) {
        this(bus, provider, workspace, model, maxIterations, temperature, maxTokens,
            contextWindow, memoryWindow, reasoningEffort, cronService, restrictToWorkspace,
            sessionManager, mcpServers, channelsConfig, runtimeSettings);
        // 将外部 registry 注册为 "cli:direct" session 的 ProjectRegistry
        if (projectRegistry != null) {
            this.cliAgentHandler.registerSessionRegistry("cli:direct", projectRegistry);
        }
    }

    /**
     * 更新当前会话的 ProjectRegistry（新建会话时调用）
     */
    public void updateProjectRegistry(providers.cli.ProjectRegistry registry) {
        if (registry != null) {
            this.cliAgentHandler.registerSessionRegistry("cli:direct", registry);
        }
    }

    /**
     * 热更新 LLMProvider（模型/API Key 变更时调用）
     */
    public void updateProvider(LLMProvider newProvider) {
        if (newProvider != null) {
            this.provider = newProvider;
        }
    }

    /**
     * 热更新模型配置（模型切换时同步更新 context window 等参数）
     */
    public void updateModelConfig(String newModel, int newMaxTokens, int newContextWindow, double newTemperature, String newReasoningEffort) {
        this.model = newModel;
        this.maxTokens = newMaxTokens;
        this.contextWindow = newContextWindow;
        this.temperature = newTemperature;
        this.reasoningEffort = newReasoningEffort;
    }

    private AgentRuntimeSettings.Snapshot runtimeSnapshot() {
        if (runtimeSettings != null) {
            return runtimeSettings.snapshot();
        }
        return new AgentRuntimeSettings.Snapshot(
                workspace, model, maxIterations, temperature, maxTokens, contextWindow, memoryWindow,
                reasoningEffort, ModelConfig.ModelType.CHAT, null, null, restrictToWorkspace, mcpServers, channelsConfig, null
        );
    }

    private int currentMemoryWindow() {
        return runtimeSnapshot().memoryWindow();
    }

    private double currentConsolidateThreshold() {
        if (runtimeSettings != null) {
            return runtimeSettings.getCurrentConfig()
                    .getAgents().getDefaults().getConsolidateThreshold();
        }
        return 0.90; // 默认值
    }

    private double currentSoftTrimThreshold() {
        if (runtimeSettings != null) {
            return runtimeSettings.getCurrentConfig()
                    .getAgents().getDefaults().getSoftTrimThreshold();
        }
        return 0.7; // 默认值
    }

    /**
     * 从 usage map 中获取整数值（支持多种 key 名称，对齐 Claude Code）
     */
    private int getUsageInt(Map<String, Integer> usage, String... keys) {
        for (String key : keys) {
            Integer value = usage.get(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        return 0;
    }

    /**
     * 从配置创建 ContextPruningSettings
     */
    private ContextPruningSettings createPruningSettings() {
        ContextPruningSettings settings = new ContextPruningSettings();
        settings.setSoftTrimRatio(currentSoftTrimThreshold());
        // 其他设置保持默认值
        return settings;
    }

    private int currentContextWindow() {
        return runtimeSnapshot().contentWindow();
    }

    private int currentContextWindowChars() {
        return (int) (runtimeSnapshot().contentWindow() * ContextPruningSettings.CHARS_PER_TOKEN_ESTIMATE);
    }

    private int currentMaxTokens() {
        return runtimeSnapshot().maxTokens();
    }

    // ==================== New Auto-Compact Methods (aligned with Open-ClaudeCode) ====================

    /**
     * 计算有效的上下文窗口（对齐 Open-ClaudeCode）
     * effectiveContextWindow = contextWindow - maxOutputTokens - reservedForSummary
     */
    private int currentEffectiveContextWindow() {
        int maxOutput = currentMaxTokens();
        return AutoCompactThreshold.calculateEffectiveContextWindow(contextWindow, maxOutput);
    }

    /**
     * 获取自动压缩触发阈值（对齐 Open-ClaudeCode）
     * 触发条件: tokenCount >= (effectiveContextWindow - 13,000)
     */
    private int currentAutoCompactThreshold() {
        return AutoCompactThreshold.getAutoCompactThreshold(currentEffectiveContextWindow());
    }

    /**
     * 检查是否应该触发自动压缩（对齐 Open-ClaudeCode）
     */
    private boolean shouldAutoCompact(long tokenUsage, String querySource, AutoCompactService.AutoCompactTrackingState tracking) {
        return shouldAutoCompact(tokenUsage, querySource, tracking, 0);
    }

    /**
     * 检查是否应该触发自动压缩（带 snipTokensFreed 参数）
     */
    private boolean shouldAutoCompact(long tokenUsage, String querySource, AutoCompactService.AutoCompactTrackingState tracking, long snipTokensFreed) {
        return AutoCompactService.shouldAutoCompact(
                null, // messages not needed with token usage
                tokenUsage,
                currentEffectiveContextWindow(),
                isAutoCompactEnabled(),
                querySource,
                tracking,
                snipTokensFreed
        );
    }

    /**
     * 检查 autoCompact 是否启用
     * 对齐: Open-ClaudeCode/src/services/compact/autoCompact.ts:147-158
     *
     * 检查顺序:
     * 1. DISABLE_COMPACT 环境变量 — 完全禁用所有压缩
     * 2. DISABLE_AUTO_COMPACT 环境变量 — 仅禁用自动压缩（手动 /compact 仍可用）
     * 3. config.agents.defaults.autoCompactEnabled — 用户配置
     */
    private boolean isAutoCompactEnabled() {
        // 1. Check DISABLE_COMPACT — completely disables all compaction
        String disableCompact = System.getenv("DISABLE_COMPACT");
        if ("1".equals(disableCompact) || "true".equalsIgnoreCase(disableCompact)) {
            return false;
        }
        // 2. Check DISABLE_AUTO_COMPACT — disables only auto-compact
        String disableAutoCompact = System.getenv("DISABLE_AUTO_COMPACT");
        if ("1".equals(disableAutoCompact) || "true".equalsIgnoreCase(disableAutoCompact)) {
            return false;
        }
        // 3. Check user config — defaults to true
        return currentConfig().getAgents().getDefaults().isAutoCompactEnabled();
    }

    /**
     * 获取 token 使用警告状态
     */
    private AutoCompactThreshold.TokenWarningState getTokenWarningState(long tokenUsage) {
        return AutoCompactService.getTokenWarningState(
                tokenUsage,
                currentEffectiveContextWindow(),
                isAutoCompactEnabled()
        );
    }

    /**
     * 计算当前上下文比例（使用真实 token 数据，不再估算）
     *
     * @param usageAcc   Usage 累积器
     * @param messages   当前消息列表（仅在第一轮无真实数据时使用）
     * @return 上下文比例 (0.0 ~ 1.0)
     */
    private double getContextRatioByUsage(UsageAccumulator usageAcc, List<Map<String, Object>> messages) {
        // 如果有真实数据（后续轮次），用真实的 prompt_tokens，不再乘系数
        if (usageAcc != null && usageAcc.hasData()) {
            long promptTokens = usageAcc.getContextSize();
            return contextWindow > 0 ? (double) promptTokens / (double) contextWindow : 0;
        }
        // 第一轮没有数据，用字符估算（只是粗估，用于第一次发送前判断）
        int estimatedChars = ContextPruner.estimateContextChars(messages);
        return currentContextWindowChars() > 0 ? (double) estimatedChars / currentContextWindowChars() : 0;
    }


    private ChannelsConfig currentChannelsConfig() {
        return runtimeSettings.getCurrentConfig().getChannels();
    }

    private Config currentConfig() {
        return runtimeSettings.getCurrentConfig();
    }


    /**
     * 注册“全局共享、无请求上下文”的工具。
     */
    private void registerSharedTools() {
        java.nio.file.Path allowedDir = restrictToWorkspace ? workspace : null;

        // 共享 FileStateCache：Read 读入缓存 → Edit/Write 校验通过

        // 文件/命令/网络工具：无会话上下文，可共享
        sharedTools.register(new ReadFileTool(workspace, allowedDir, sharedFileCache));
        sharedTools.register(new WriteTool(workspace, allowedDir, sharedFileCache));
        sharedTools.register(new EditTool(workspace, allowedDir, sharedFileCache));
        sharedTools.register(new FileSystemTools.ReadWordTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ReadWordStructuredTool(workspace, allowedDir));

        sharedTools.register(new ListFilesTool(workspace, allowedDir, cliAgentHandler::getProjectRegistry));
        // 校验git环境
        if (Shell.isWindows()) {
            Shell.setWindowsBashPath(runtimeSnapshot().windowsBashPath());
            Shell.getShellConfig();
        }
        sharedTools.register(new ExecTool(
                currentTools().getExec().getTimeout() * 1000,
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                currentTools().getExec().getPathAppend(),
                runtimeSnapshot().windowsBashPath()
        ));

        // 注册web工具
        WebSearchConfig search = currentTools().getWeb().getSearch();
        WebFetchConfig fetch = currentTools().getWeb().getFetch();
        sharedTools.register(new WebSearchTool(search.getApiKey(), search.getMaxResults(), search.getProxy()));
        sharedTools.register(new WebFetchTool(fetch.getMaxChars(), fetch.getProxy()));

        sharedTools.register(new SkillTool(commandManager, skillsLoader));
        //sharedTools.register(new UninstallSkillTool(skillsLoader));

        // 记忆搜索工具
        sharedTools.register(new MemorySearchTool(workspace));

        // 搜索工具（对齐 Claude Code 的 Grep/Glob 工具）
        sharedTools.register(new GrepTool(workspace, allowedDir, cliAgentHandler::getProjectRegistry));
        sharedTools.register(new GlobTool(workspace, allowedDir, cliAgentHandler::getProjectRegistry));

        // CLI Agent 工具（开发者模式下可用）
        if (currentConfig().getAgents().getDefaults().isDevelopment()) {
            sharedTools.register(new CliAgentTool(cliAgentHandler));
        }

        // Subagent 工具（AgentTool - 新系统）
        agent.subagent.execution.AgentTool agentTool = new agent.subagent.execution.AgentTool();
        agentTool.setForkExecutor(forkAgentExecutor);
        agentTool.setSessionsDir(workspace.resolve("sessions"));
        agentTool.setSessions(sessions);
        // Background agent executor for built-in agents (Explore, Plan, general-purpose)
        agent.subagent.execution.BackgroundAgentExecutor bgExecutor =
            new agent.subagent.execution.BackgroundAgentExecutor(appState, appState.setter(), bus, new agent.tool.CompositeToolView(sharedTools));
        agentTool.setBackgroundExecutor(bgExecutor);
        sharedTools.register(agentTool);

        // MCP 重载工具：按名称刷新指定 MCP server
        sharedTools.register(new agent.tool.mcp.McpReloadTool(mcpManager));

        // Plan Mode 工具
        sharedTools.register(new agent.tool.plan.EnterPlanModeTool());
        sharedTools.register(new agent.tool.plan.ExitPlanModeTool());
        sharedTools.register(new agent.tool.plan.AskUserQuestionTool());
    }

    private ToolsConfig currentTools() {
        return currentConfig().getTools();
    }

    /**
     * 构建当前请求的工具视图：
     * - sharedTools：全局共享、无上下文工具
     * - mcpTools：动态 MCP 工具（运行期发现）
     * - localTools：当前请求独有、有上下文工具
     * <p>
     * 优先级：
     * local > mcp > shared
     */
    private ToolView buildRequestToolsAndSetContext(String sessionKy, String channel, String chatId, String messageId) {
        ToolRegistry localTools = new ToolRegistry();

        // 每次请求独立创建 MessageTool，避免串会话
        localTools.register(new MessageTool(bus::publishOutbound, channel, chatId, messageId));

        // TaskStopTool: 允许 Agent 停止任务
        localTools.register(new TaskStopTool(
                taskControl,
                () -> appState,
                appState.setter()
        ));


        // CronTool 带 channel/chatId 上下文，也做成每请求独立
        if (cronService != null) {
            CronTool cronTool = new CronTool(cronService);
            cronTool.setContext(channel, chatId);
            localTools.register(cronTool);
        }

        // TodoWriteTool - 任务列表管理
        TodoWriteTool todoWriteTool = new TodoWriteTool(
                () -> appState,
                appState.setter()
        );
        todoWriteTool.setAgentId(sessionKy);
        localTools.register(todoWriteTool);

        // MCP 动态工具快照
        ToolRegistry mcpTools = mcpManager.snapshotRegistry();

        ToolView toolView = new CompositeToolView(sharedTools, mcpTools, localTools);

        // Plan mode: filter out write/edit tools so the LLM can only explore
        if (agent.tool.plan.PlanModeState.isPlanMode(sessionKy)) {
            toolView = new PlanModeToolView(toolView);
        }

        return toolView;
    }

    /**
     * ToolView wrapper that filters out write/edit tools during plan mode.
     * The wrapped view retains all tools for execution (so ExitPlanMode etc. work),
     * but getDefinitions() hides write_file and edit_file from the LLM.
     */
    private static class PlanModeToolView implements ToolView {
        private static final java.util.Set<String> PLAN_MODE_DISALLOWED = java.util.Set.of(
            "write_file", "edit_file"
        );

        private final ToolView delegate;

        PlanModeToolView(ToolView delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.List<java.util.Map<String, Object>> getDefinitions() {
            java.util.List<java.util.Map<String, Object>> all = delegate.getDefinitions();
            java.util.List<java.util.Map<String, Object>> filtered = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> def : all) {
                String name = extractToolName(def);
                if (name != null && PLAN_MODE_DISALLOWED.contains(name)) {
                    continue;
                }
                filtered.add(def);
            }
            return filtered;
        }

        @SuppressWarnings("unchecked")
        private String extractToolName(java.util.Map<String, Object> def) {
            Object fn = def.get("function");
            if (fn instanceof java.util.Map<?, ?> map) {
                Object name = map.get("name");
                return name == null ? null : String.valueOf(name);
            }
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<String> execute(String name, java.util.Map<String, Object> args, ToolUseContext parentUseContext) {
            return delegate.execute(name, args, parentUseContext);
        }

        @Override
        public Tool get(String name) { return delegate.get(name); }

        @Override
        public java.util.List<Tool> getTools() { return delegate.getTools(); }

        @Override
        public void addTool(Tool tool) { delegate.addTool(tool); }
    }

    /**
     * 构建当前请求的工具视图：
     * - sharedTools：全局共享、无上下文工具
     * - mcpTools：动态 MCP 工具（运行期发现）
     * - localTools：当前请求独有、有上下文工具
     * <p>
     * 优先级：
     * local > mcp > shared
     */
    private ToolView buildMemoryRequestTools(String channel, String chatId, String messageId) {
        ToolRegistry localTools = new ToolRegistry();

        // 每次请求独立创建 MessageTool，避免串会话
        localTools.register(new MessageTool(bus::publishOutbound, channel, chatId, messageId));
        localTools.register(new ReadFileTool(workspace, null, sharedFileCache));
        localTools.register(new GlobTool(workspace, null, cliAgentHandler::getProjectRegistry));
        localTools.register(new GrepTool(workspace, null, cliAgentHandler::getProjectRegistry));
        localTools.register(new EditTool(workspace, null, sharedFileCache));
        localTools.register(new WriteTool(workspace, null, sharedFileCache));
        localTools.register(new SkillTool(commandManager, skillsLoader));

        return new CompositeToolView(localTools, sharedTools);
    }

    /**
     * 构建当前请求的工具视图：
     * - sharedTools：全局共享、无上下文工具
     * - mcpTools：动态 MCP 工具（运行期发现）
     * - localTools：当前请求独有、有上下文工具
     * <p>
     * 优先级：
     * local > mcp > shared
     */
    private ToolView buildContextCompressRequestTools(String sessionKey, String channel, String chatId, String messageId) {
        ToolRegistry localTools = new ToolRegistry();

        // 每次请求独立创建 MessageTool，避免串会话
        localTools.register(new MessageTool(bus::publishOutbound, channel, chatId, messageId));
        localTools.register(new ReadFileTool(workspace, null, sharedFileCache));
        localTools.register(new EditTool(workspace, null, sharedFileCache));
        localTools.register(new WriteTool(workspace, null, sharedFileCache));
        localTools.register(new GlobTool(workspace, null, cliAgentHandler::getProjectRegistry));
        localTools.register(new GrepTool(workspace, null, cliAgentHandler::getProjectRegistry));

        // 添加记忆压缩工具
        localTools.register(new PruneMessagesTool(sessions, sessionKey));

        return new CompositeToolView(localTools, sharedTools);
    }

    public CronTool getCronTool() {
        return cronToolFacade;
    }

    private CompletionStage<Void> connectMcp() {
        return mcpManager.ensureConnected()
                .thenApply(v -> {
                    mcpManager.startAutoRefresh(20, TimeUnit.SECONDS);
                    return (Void) null;
                }).exceptionally(ex -> {
                    log.warn("MCP 初始化失败，本轮将继续但不包含 MCP 工具: {}", ex.toString());
                    return null;
                });
    }

    public CompletionStage<Void> closeMcp() {
        return mcpManager.closeAll();
    }

    public CompletableFuture<Void> run() {
        running.compareAndSet(false, true);
        connectMcp().toCompletableFuture().join();
        log.info("Agent 循环已启动");

        while (running.get()) {
            InboundMessage msg = null;
            try {
                msg = bus.consumeInbound(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            if (msg == null) continue;

            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            log.info("AgentLoop收到消息:{}", content);
            // 新任务开始前清理 stop 标记
            clearStopRequested(msg.getSessionKey());

            // 系统主代理停止
            if (handleSystemCommand(msg, content)) {
                continue;
            }


            CompletableFuture<Void> task = dispatch(msg);
            activeTasks.computeIfAbsent(msg.getSessionKey(), k -> new CopyOnWriteArrayList<>()).add(task);

            InboundMessage finalMsg = msg;
            task.whenComplete((v, ex) -> {
                CopyOnWriteArrayList<CompletableFuture<?>> list = activeTasks.get(finalMsg.getSessionKey());
                if (list != null) {
                    list.remove(task);
                    if (list.isEmpty()) {
                        activeTasks.remove(finalMsg.getSessionKey(), list);
                    }
                }
            });
        }

        log.info("Agent 循环已停止");
        return CompletableFuture.completedFuture(null);
    }

    public void stop() {
        running.compareAndSet(true, false);
        if (queue != null) {
            queue.shutdown();
        }
        // 取消所有等待用户输入的问题
        pendingUserQuestions.values().forEach(f ->
            f.completeExceptionally(new CancellationException("session stopped")));
        pendingUserQuestions.clear();
        log.info("Agent 循环正在停止");
    }


    private boolean completeIfStopped(
            String sessionKey,
            State st,
            CompletableFuture<RunResult> out,
            List<String> toolsUsed,
            List<Map<String, Object>> messages,
            UsageAccumulator usageAcc,
            boolean saveToSession
    ) {
        if (!isStopRequested(sessionKey)) {
            return false;
        }

        if (st.done.compareAndSet(false, true)) {
            // 停止时也保存已处理的消息到 session
            if (saveToSession) {
                var sess = sessions.getOrCreate(sessionKey);
                List<Map<String, Object>> hist = sess.getHistory();
                int startIdx = 2 + hist.size();
                if (messages.size() > startIdx) {
                    saveTurn(sess, new ArrayList<>(messages.subList(startIdx, messages.size())));
                }
            }

            out.complete(new RunResult(
                    "已停止。",
                    toolsUsed,
                    messages,
                    usageAcc.getTotal()
            ));
        }
        return true;
    }

    /**
     * 检测并处理系统命令（如 /stop）。
     * 此方法用于在消息入队前拦截系统命令，避免排队等待。
     *
     * @param msg 消息
     * @param content 消息内容
     * @return true 如果是系统命令并已处理，false 如果不是系统命令
     */
    public boolean handleSystemCommand(InboundMessage msg, String content) {
        if (content == null || !content.startsWith("/")) {
            return false;
        }

        String trimmed = content.trim();
        log.info("检测到系统命令: {}", trimmed);

        // 处理 /stop 命令
        if ("/stop".equalsIgnoreCase(trimmed)) {
            log.info("直接处理 /stop 命令");
            handleStopCommand(msg).toCompletableFuture().join();
            return true;
        }

        // 处理开发者模式命令
        if (currentConfig().getAgents().getDefaults().isDevelopment()) {
            if (cliAgentHandler.handleCommand(msg, content)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 处理 /stop 命令（公开方法，供外部直接调用）
     * 对应 Open-ClaudeCode: useCancelRequest.ts - handleCancel()
     */
    public CompletionStage<Void> handleStopCommand(InboundMessage msg) {
        String sessionKey = msg.getSessionKey();

        // 1) 标记会话停止，并取消底层 LLM 请求 future
        requestStop(sessionKey);

        // 2) 取消外层任务（不等待完成，避免阻塞）
        List<CompletableFuture<?>> tasks = activeTasks.remove(sessionKey);
        int cancelled = 0;

        if (tasks != null) {
            for (CompletableFuture<?> f : tasks) {
                if (f != null && !f.isDone() && f.cancel(true)) {
                    cancelled++;
                }
            }
            // 移除同步等待，直接继续
        }

        // 3) 重置会话的 token 计数，避免后续对话误触发压缩检测
        Session sess = sessions.getOrCreate(sessionKey);
        sess.setTotalTokens(0);
        sess.setLastCallInput(0);
        sess.setLastCallOutput(0);
        sess.setLastCallCacheRead(0);
        sess.setLastCallCacheWrite(0);

        // 4) 使用 TaskControlService 杀死所有 Agent 任务（Phase 4 集成）
        int agentTasksKilled = 0;
        if (taskControl != null && appState != null) {
            agentTasksKilled = taskControl.killAllAgentTasks(appState, appState.setter());
            log.info("handleStopCommand: killed {} agent tasks via TaskControlService", agentTasksKilled);
        }

        int total = cancelled + agentTasksKilled;
        return bus.publishOutbound(new OutboundMessage(
                msg.getChannel(),
                msg.getChatId(),
                total > 0 ? "⏹ 已停止 " + total + " 个任务。" : "没有活动任务可停止。",
                List.of(),
                Map.of()
        ));
    }

    /**
     * 不再使用全局 processingLock 包整条消息链。
     * queue 负责 session 级顺序，请求级 ToolRegistry 负责隔离工具上下文。
     */
    private CompletableFuture<Void> dispatch(InboundMessage msg) {
        String sessionKey = msg.getSessionKey();

        return queue.enqueue(
                sessionKey,
                () -> {
                    try {
                        // 先处理系统命令（这些命令不需要上下文压缩检测）
                        CompletionStage<OutboundMessage> commandResult = preprocessSystemCommand(msg);
                        if (commandResult != null) {
                            OutboundMessage resp = commandResult.toCompletableFuture().get();
                            if (resp != null) {
                                bus.publishOutbound(resp).toCompletableFuture().join();
                            }
                            return null;
                        }

                        // 入口处检测是否需要上下文压缩
                        checkAndExecuteContextCompress(msg);

                        OutboundMessage resp = processMessage(msg, null, null).toCompletableFuture().get();

                        if (resp != null) {
                            bus.publishOutbound(resp).toCompletableFuture().join();
                        } else if ("cli".equals(msg.getChannel())) {
                            bus.publishOutbound(new OutboundMessage(
                                    msg.getChannel(),
                                    msg.getChatId(),
                                    "",
                                    List.of(),
                                    msg.getMetadata()
                            )).toCompletableFuture().join();
                        }

                        return null;
                    } catch (CancellationException ce) {
                        throw ce;
                    } catch (Exception e) {
                        log.warn("处理会话 {} 的消息时出错: {}", msg.getSessionKey(), e);
                        try {
                            bus.publishOutbound(new OutboundMessage(
                                    msg.getChannel(),
                                    msg.getChatId(),
                                    "抱歉，处理时遇到了错误。",
                                    List.of(),
                                    Map.of()
                            )).toCompletableFuture().join();
                        } catch (Exception publishEx) {
                            log.warn("发布错误消息到会话 {} 失败: {}", msg.getSessionKey(), publishEx.toString());
                        }
                        throw new CompletionException(e);
                    }
                },
                "dispatch"
        );
    }

    /**
     * 预处理系统命令，在上下文压缩检测之前处理
     * @return 如果是系统命令则返回处理结果，否则返回 null
     */
    private CompletionStage<OutboundMessage> preprocessSystemCommand(InboundMessage msg) {
        if (msg.getContent() == null) {
            return null;
        }
        String cmd = msg.getContent().trim().toLowerCase(Locale.ROOT);

        // /new 和 /clear 需要特殊处理，因为 system_cmd 不包含 /new
        if ("/new".equalsIgnoreCase(cmd) || "/clear".equalsIgnoreCase(cmd)) {
            String sessionKey = msg.getSessionKey();
            Session session = sessions.getOrCreate(sessionKey);
            commandManager.addLocalCommand(new LocalCommand(cmd, "新会话已开始"));
            sessions.createNew(session.getKey());
            return CompletableFuture.completedFuture(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "新会话已开始。",
                    List.of(),
                    Map.of()
            ));
        }

        if ("/context-press".equalsIgnoreCase(cmd)) {
            String output = "上下文压缩命令已触发";
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            return handleContextPress(msg, sessions.getOrCreate(msg.getSessionKey()));
        }

        if ("/memory".equalsIgnoreCase(cmd)) {
            String output = "记忆整理命令已触发";
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            return handleMemoryCommand(msg, sessions.getOrCreate(msg.getSessionKey()));
        }

        // ========== /fork 命令 ==========
        if (cmd.startsWith("/fork")) {
            return handleForkCommand(msg);
        }

        if ("/help".equalsIgnoreCase(cmd)) {
            String output = "🐱 javaclawbot 命令:\n\n" +
                    "对话管理:\n" +
                    "  /new — 开始新会话\n" +
                    "  /clear — 清空当前会话\n" +
                    "  /resume <sessionId> — 恢复指定会话\n" +
                    "  /init — 初始化项目\n" +
                    "  /memory — 整理当前对话记忆\n" +
                    "  /context-press — 触发上下文压缩\n" +
                    "  /stop — 停止当前任务\n" +
                    "  /help — 显示本帮助\n\n" +
                    "Fork 子代理:\n" +
                    "  /fork <指令> — Fork 并行子代理执行任务（fire-and-forget）\n\n" +
                    "MCP:\n" +
                    "  /mcp-reload — 重新加载 MCP 插件\n\n" +
                    "项目绑定 (开发模式):\n" +
                    "  /bind <名称>=<路径> — 绑定项目\n" +
                    "  /bind --main <路径> — 直接设为主代理项目\n" +
                    "  /unbind <名称> — 解绑项目\n" +
                    "  /projects — 列出所有已绑定项目\n\n" +
                    "CLI Agent (开发模式):\n" +
                    "  /cc <项目> <提示词> — 使用 Claude Code 执行任务\n" +
                    "  /oc <项目> <提示词> — 使用 OpenCode 执行任务\n" +
                    "  /status [项目] — 查看 Agent 状态\n" +
                    "  /stop <项目> [类型] — 停止指定 Agent\n" +
                    "  /stopall — 停止所有 CLI Agent";
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            return CompletableFuture.completedFuture(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    output,
                    List.of(),
                    Map.of()
            ));
        }

        if ("/mcp-reload".equalsIgnoreCase(cmd) || "/mcp-init".equalsIgnoreCase(cmd)) {
            String cmdResp = mcpManager.refreshTools().toCompletableFuture().join();
            String output = StrUtil.isBlank(cmdResp) ? "🐱 MCP 插件已重新加载。" : cmdResp;
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            return CompletableFuture.completedFuture(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    output,
                    List.of(),
                    Map.of()
            ));
        }

        return null;
    }

    public static Set<String> system_cmd = Set.of("/context-press", "/help", "/init", "/clear", "/memory", "/mcp-reload", "/mcp-init", "/resume", "/fork");

    /**
     * Auto-compact tracking state (circuit breaker)
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AutoCompactService.AutoCompactTrackingState> autoCompactTracking =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 入口处检测并执行上下文压缩（对齐 Open-ClaudeCode）
     *
     * Key changes from old implementation:
     * - Uses token-based threshold: tokenCount >= (effectiveContextWindow - 13,000)
     * - Circuit breaker: stops after 3 consecutive failures
     * - Uses session.getTotalTokens() for accurate token count
     */
    private void checkAndExecuteContextCompress(InboundMessage msg) {
        // 跳过系统消息和命令
        if ("system".equalsIgnoreCase(msg.getChannel())) {
            return;
        }
        if (system_cmd.contains(msg.getContent().trim())) {
            return;
        }

        String sessionKey = msg.getSessionKey();
        Session session = sessions.getOrCreate(sessionKey);

        // 如果 lastCall 为 0（刚完成压缩或首次对话），跳过检测
        if (session.getLastCallInput() == 0 && session.getLastCallOutput() == 0
                && session.getLastCallCacheRead() == 0 && session.getLastCallCacheWrite() == 0) {
            return;
        }

        // 使用 Session 中持久化的 usage 数据
        // 注意：这里用 totalTokens 而不是 lastCallPromptTokens，因为需要累积的上下文大小
        long totalUsedTokens = session.getTotalTokens();
        long currentUsedTokens = session.getLastCallInput() + session.getLastCallOutput();

        // 获取/创建 tracking state
        AutoCompactService.AutoCompactTrackingState tracking = autoCompactTracking.computeIfAbsent(
                sessionKey, k -> new AutoCompactService.AutoCompactTrackingState()
        );

        // 检查是否应该触发压缩
        int threshold = currentAutoCompactThreshold();

        String totalUsedStr = totalUsedTokens >= 1_000_000
                ? String.format("%.1fM", totalUsedTokens / 1_000_000.0)
                : totalUsedTokens >= 1_000
                        ? String.format("%.1fK", totalUsedTokens / 1_000.0)
                        : String.valueOf(totalUsedTokens);

        String currentUsageTotalUsedStr = currentUsedTokens >= 1_000_000
                ? String.format("%.1fM", currentUsedTokens / 1_000_000.0)
                : currentUsedTokens >= 1_000
                        ? String.format("%.1fK", currentUsedTokens / 1_000.0)
                        : String.valueOf(currentUsedTokens);

        // 使用新的 token-based 检查
        if (currentUsedTokens >= threshold) {
            // 检查 circuit breaker
            if (tracking.consecutiveFailures >= AutoCompactThreshold.MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES) {
                log.info("入口检测：跳过压缩（circuit breaker 已触发，{} 次连续失败）",
                        tracking.consecutiveFailures);
                return;
            }

            log.info("入口检测：当前上下文使用 {} tokens >= 阈值 {}，执行压缩",
                    currentUsageTotalUsedStr, threshold);

            // 通知用户
            bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "⏳ 上下文已满，正在自动压缩...",
                    List.of(),
                    Map.of()
            )).toCompletableFuture().join();

            // 执行上下文压缩
            boolean success = executeContextCompress(msg);

            if (success) {
                // 成功，重置失败计数
                tracking.consecutiveFailures = 0;
                tracking.compacted = true;
                tracking.turnCounter = 0;
            } else {
                // 失败，增加失败计数
                tracking.consecutiveFailures++;
                log.warn("上下文压缩失败，连续失败次数: {}", tracking.consecutiveFailures);
            }

            // 通知用户
            bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    success ? "上下文压缩完成, 请继续对话" : "上下文压缩失败",
                    List.of(),
                    Map.of()
            )).toCompletableFuture().join();
            log.info("上下文压缩完成: success={}", success);
        }
    }

    /**
     * 执行上下文压缩（对齐 Open-ClaudeCode）
     *
     * 使用新的 CompactService 进行压缩，支持:
     * - Direct LLM API call (streamCompactSummary) without tools
     * - Proper boundary markers
     * - Post-compact cleanup
     *
     * @return true if compaction succeeded, false otherwise
     */
    private boolean executeContextCompress(InboundMessage message) {
        var channel = message.getChannel();
        var sessionKey = message.getSessionKey();
        var chatId = message.getChatId();

        Session sess = sessions.getOrCreate(sessionKey);

        try {
            // 获取当前消息历史
            List<Map<String, Object>> messages = sess.getHistory();

            if (messages.isEmpty()) {
                log.warn("executeContextCompress: no messages to compress");
                return false;
            }

            // Snapshot read file state before compaction (for post-compact restoration)
            Map<String, Long> preCompactReadFileSnapshot = readFileState.snapshot();

            // 估计压缩前的 token 数
            long preCompactTokenCount = CompactService.estimatePreCompactTokenCount(messages);

            // 尝试快速压缩路径：使用 session memory（无需 LLM 调用）
            // 对齐 Open-ClaudeCode: shouldUseSessionMemoryCompaction() 检查两个 flag
            config.agent.SessionMemoryConfig smConfig = currentConfig().getAgents().getDefaults().getSessionMemory();
            boolean sessionMemoryEnabled = smConfig != null && smConfig.isEffectivelyEnabled();
            boolean smCompactEnabled = smConfig != null && smConfig.isSmCompactEnabled();

            if (SessionMemoryCompactService.shouldUseSessionMemoryCompaction(sessionMemoryEnabled, smCompactEnabled)) {
                SessionMemoryCompactService.CompactionResult smResult =
                        SessionMemoryCompactService.trySessionMemoryCompaction(
                                sessionMemoryService,
                                sessionKey,
                                messages,
                                (long) currentAutoCompactThreshold()
                        );

                if (smResult != null) {
                    // 快速路径成功：使用 session memory 作为摘要
                    log.info("executeContextCompress: using session memory fast path, {} messages to keep",
                            smResult.messagesToKeep.size());

                    // 收集 post-compact attachments
                    List<Map<String, Object>> attachments = collectPostCompactAttachments(
                            sessionKey, preCompactReadFileSnapshot, smResult.messagesToKeep);

                    // 构建压缩后的消息列表（boundary 不存消息，写入 session metadata）
                    List<Map<String, Object>> compactedMessages = new java.util.ArrayList<>();
                    compactedMessages.addAll(smResult.summaryMessages);
                    compactedMessages.addAll(smResult.messagesToKeep);
                    compactedMessages.addAll(attachments);

                    // 将 compactMetadata 存入 session metadata（而非作为消息）
                    Map<String, Object> sessMeta = sess.getMetadata();
                    Object bMeta = smResult.boundaryMarker.get("compactMetadata");
                    if (bMeta instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cm = (Map<String, Object>) bMeta;
                        sessMeta.put("compactMetadata", new java.util.LinkedHashMap<>(cm));
                    }

                    // 更新 session
                    sess.setMessages(compactedMessages);
                    sess.setUpdatedAt(java.time.LocalDateTime.now());
                    // 重置当前结果
                    sess.setLastCallCacheRead(0);
                    sess.setLastCallInput(0);
                    sess.setLastConsolidated(0);
                    sess.setLastCallOutput(0);

                    sessions.save(sess);

                    // Clear read file state after compaction (files will be re-tracked on next read)
                    readFileState.clear();

                    PostCompactCleanup.runPostCompactCleanup();
                    PostCompactCleanup.notifyCompaction("auto", null);
                    PostCompactCleanup.markPostCompaction();

                    return true;
                }
            }

            // Fallback: 传统 LLM 压缩

            // 准备要摘要的消息（去掉 images）
            List<Map<String, Object>> messagesToSummarize =
                    MicroCompactService.stripImagesFromMessages(messages);

            // 创建 cancel checker
            CancelChecker cancelChecker = () -> isStopRequested(sessionKey);

            // 创建进度回调
            Consumer<String> progressCallback = (delta) -> {
                if (delta != null && !delta.isEmpty()) {
                    bus.publishOutbound(new OutboundMessage(
                            message.getChannel(),
                            message.getChatId(),
                            "(上下文压缩) " + delta,
                            List.of(),
                            Map.of()
                    ));
                }
            };

            // 使用 streamCompactSummary 直接调用 LLM API 生成摘要
            CompactService.CompactSummaryResult result =
                    CompactService.streamCompactSummary(
                            provider,
                            messagesToSummarize,
                            null,  // customInstructions
                            progressCallback,
                            cancelChecker
                    );

            if (!result.isSuccess()) {
                log.warn("executeContextCompress: streamCompactSummary failed: {}", result.getError());
                return false;
            }

            // 格式化摘要（去除 analysis 标签等）
            String formattedSummary = CompactPrompt.formatCompactSummary(result.getSummary());

            // 创建摘要消息
            Map<String, Object> summaryMsg = new java.util.LinkedHashMap<>();
            summaryMsg.put("role", "user");
            summaryMsg.put("content", CompactPrompt.getCompactUserSummaryMessage(
                    formattedSummary, true, null, true));
            summaryMsg.put("isCompactSummary", true);
            summaryMsg.put("timestamp", java.time.LocalDateTime.now().toString());

            // 获取最后一条消息的 UUID
            String lastUuid = null;
            if (!messages.isEmpty()) {
                Object uuid = messages.get(messages.size() - 1).get("uuid");
                if (uuid instanceof String s) lastUuid = s;
            }

            // 创建边界标记
            Map<String, Object> boundary = CompactBoundary.createCompactBoundaryMessage(
                    "auto",
                    preCompactTokenCount,
                    lastUuid
            );

            // 添加摘要消息
            List<Map<String, Object>> summaryMessages = List.of(summaryMsg);

            // 收集 post-compact attachments
            List<Map<String, Object>> attachments = collectPostCompactAttachments(
                    sessionKey, preCompactReadFileSnapshot, List.of());

            // 构建压缩后的消息列表（boundary 不存消息，写入 session metadata）
            List<Map<String, Object>> compactedMessages = new java.util.ArrayList<>();
            compactedMessages.addAll(summaryMessages);
            compactedMessages.addAll(attachments);

            // 将 compactMetadata 存入 session metadata（而非作为消息）
            Map<String, Object> sessMeta2 = sess.getMetadata();
            Object bMeta2 = boundary.get("compactMetadata");
            if (bMeta2 instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cm = (Map<String, Object>) bMeta2;
                sessMeta2.put("compactMetadata", new java.util.LinkedHashMap<>(cm));
            }

            // 更新 session
            sess.setMessages(compactedMessages);
            sess.setUpdatedAt(java.time.LocalDateTime.now());

            // 重置当前结果
            sess.setLastCallCacheRead(0);
            sess.setLastCallInput(0);
            sess.setLastConsolidated(0);
            sess.setLastCallOutput(0);
            sessions.save(sess);

            // Clear read file state after compaction
            readFileState.clear();

            // 运行 post-compact cleanup
            PostCompactCleanup.runPostCompactCleanup();
            PostCompactCleanup.notifyCompaction("auto", null);
            PostCompactCleanup.markPostCompaction();

            log.info("executeContextCompress: compressed {} messages into summary, boundary added, attachments={}, ptlRetryCount={}",
                    messages.size(), attachments.size(), result.getPtlRetryCount());

            return true;
        } catch (Exception e) {
            log.error("executeContextCompress failed", e);
            return false;
        } finally {
            // 压缩后重置 usage（消息已被修剪，上下文大小已改变）
            // 重置后，下一次计算上下文比例时会用字符估算来反映实际的压缩后大小
            /*sess.setLastCallInput(0);
            sess.setLastCallOutput(0);
            sess.setLastCallCacheRead(0);
            sess.setLastCallCacheWrite(0);
            sess.setTotalTokens(0);

            // 重置后需要保存，否则重置值不会被持久化
            sessions.save(sess);*/

            // 重置 usageAcc（压缩的 usage 不应混入后续实际消息的 usage）
            UsageAccumulator compressUsageAcc = usageTrackers.get(sessionKey);
            if (compressUsageAcc != null) {
                compressUsageAcc.reset();
            }
        }
    }

    private CompletionStage<OutboundMessage> processMessage(InboundMessage msg, String sessionKeyOverride, ProgressCallback onProgress) {
        if ("system".equalsIgnoreCase(msg.getChannel())) {
            // 新任务开始前清理 stop 标记
            clearStopRequested(msg.getSessionKey());

            String chat = msg.getChatId();
            String channel = (chat != null && chat.contains(":")) ? chat.split(":", 2)[0] : "cli";
            String chatId = (chat != null && chat.contains(":")) ? chat.split(":", 2)[1] : chat;
            String sessionKy = channel + ":" + chatId;

            ToolView requestTools = buildRequestToolsAndSetContext(sessionKy, channel, chatId, extractMessageId(msg.getMetadata()));

            Session session = sessions.getOrCreate(sessionKy);
            lazyRestorePlanMode(sessionKy, session);
            List<Map<String, Object>> history = session.getHistory();
            List<Map<String, Object>> initial = context.buildMessages(
                    history, msg.getContent(), null, channel, chatId
            );

            return runAgentLoop(msg, initial, requestTools, true, onProgress).thenApply(rr -> {
                return new OutboundMessage(
                        channel,
                        chatId,
                        rr.finalContent != null ? rr.finalContent : "后台任务已完成。",
                        List.of(),
                        Map.of()
                );
            });
        }

        // 获取命令
        String cmd = msg.getContent() == null ? "" : msg.getContent().trim().toLowerCase(Locale.ROOT);

        // init初始化命令
        if ("/init".equalsIgnoreCase(cmd)) {
            String initPrompt;
            try (var in = AgentLoop.class.getResourceAsStream("/templates/init/INIT.md")) {
                if (in == null) {
                    throw new RuntimeException("Resource not found: templates/init/INIT.md");
                }
                initPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                bus.publishInbound(new InboundMessage("system", msg.getSenderId(), msg.getChatId(), "系统异常，无法读取模板：/templates/init/INIT.md"));
                return CompletableFuture.completedFuture(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        "init命令执行异常",
                        List.of(),
                        Map.of()
                ));
            }
            commandManager.addLocalCommand(new LocalCommand(cmd, ""));
            bus.publishInbound(new InboundMessage("system", msg.getSenderId(), msg.getChatId(), initPrompt));
            return CompletableFuture.completedFuture(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "init命令已执行",
                    List.of(),
                    Map.of()
            ));
        }

        String sessionKey = (sessionKeyOverride != null) ? sessionKeyOverride : msg.getSessionKey();
        // 新任务开始前清理 stop 标记
        clearStopRequested(sessionKey);

        Session session = sessions.getOrCreate(sessionKey);

        // 惰性恢复 plan mode 状态（处理进程重启后继续对话的场景）
        lazyRestorePlanMode(sessionKey, session);

        ToolView requestTools = buildRequestToolsAndSetContext(
                sessionKey, msg.getChannel(),
                msg.getChatId(),
                extractMessageId(msg.getMetadata())
        );

        var mtool = requestTools.get("message");
        if (mtool instanceof MessageTool m) {
            m.startTurn();
        }

        context.setCurrentModelType(runtimeSnapshot().modelType());
        List<Map<String, Object>> history = session.getHistory();
        List<Map<String, Object>> initialMessages = context.buildMessages(
                history,
                msg.getContent(),
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId()
        );

        ProgressCallback progress = getBusProgressCallback(msg, onProgress);

        return runAgentLoop(msg, initialMessages, requestTools, true, progress).thenApply(rr -> {
            String finalContent = rr.finalContent != null
                    ? rr.finalContent
                    : "处理完成但没有响应内容。";

            var msgTool = requestTools.get("message");
            if (msgTool instanceof MessageTool m && m.isSentInTurn()
                    && (finalContent == null || finalContent.isBlank()
                        || !finalContent.startsWith("Error:"))) {
                return null;
            }

            // 提取推理内容
            String reasoningContent = null;
            for (int i = rr.messages.size() - 1; i >= 0; i--) {
                Map<String, Object> am = rr.messages.get(i);
                if ("assistant".equals(am.get("role")) && am.containsKey("reasoning_content")) {
                    Object rc = am.get("reasoning_content");
                    if (rc instanceof String s && !s.isBlank()) {
                        reasoningContent = s;
                    }
                    break;
                }
            }
            Map<String, Object> meta = msg.getMetadata() != null
                ? new LinkedHashMap<>(msg.getMetadata()) : new LinkedHashMap<>();
            if (reasoningContent != null) {
                meta.put("_reasoning_content", reasoningContent);
            }

            return new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    finalContent,
                    List.of(),
                    meta
            );
        });
    }

    private CompletionStage<OutboundMessage> handleContextPress(InboundMessage msg, Session session) {
        executeContextCompress(msg);
        return CompletableFuture.completedFuture(new OutboundMessage(
                msg.getChannel(), msg.getChatId(), "✅ 上下文压缩完成", List.of(), Map.of()
        ));
    }

    /**
     * 处理 /memory 命令：用户手动触发记忆整理
     */
    private CompletionStage<OutboundMessage> handleMemoryCommand(InboundMessage msg, Session session) {
        String channel = msg.getChannel();
        String chatId = msg.getChatId();
        String sessionKey = session.getKey();

        // 通知用户
        bus.publishOutbound(new OutboundMessage(
                channel, chatId, "⏳ 正在整理记忆...", List.of(), Map.of()
        ));

        try {
            // 构建记忆整理消息
            List<Map<String, Object>> initial = context.buildMemoryMessages(
                    List.of(), MemoryStore.UPDATE_MEMORY_SYSTEM_PROMPT.replaceAll("\\{workspace}", workspace.toString()), null, channel, chatId
            );
            ToolView tools = buildMemoryRequestTools(channel, chatId, null);

            // 同步执行记忆整理, memory命令执行完成后,清理session中上下文（不需要保存到 session）
            RunResult rr = runAgentLoop(msg, initial, tools, false, (context1, toolHit) -> {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("_progress", true);
                meta.put("_tool_hint", toolHit);
                bus.publishOutbound(new OutboundMessage(
                        msg.getChannel()
                        , msg.getChatId()
                        , "(记忆处理进程) " + context1
                        , List.of()
                        , meta
                ));
            }, false).toCompletableFuture().join();

            String finalContent = (rr != null && rr.finalContent != null && !rr.finalContent.isBlank())
                    ? rr.finalContent
                    : "✅ 记忆整理完成";
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, finalContent, List.of(), Map.of()
            ));
        } catch (Exception e) {
            log.warn("记忆整理失败", e);
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "❌ 记忆整理失败，请重试", List.of(), Map.of()
            ));
        }
    }

    /**
     * 处理 /resume <sessionId> 命令：恢复指定会话
     *
     * 流程：
     * 1. 解析 sessionId
     * 2. 检查 session 文件是否存在
     * 3. 不存在则通知用户
     * 4. 存在则更新 sessions.json 映射，清除缓存，加载 todos
     */
    private CompletionStage<OutboundMessage> handleResumeCommand(InboundMessage msg) {
        String channel = msg.getChannel();
        String chatId = msg.getChatId();
        String sessionKey = msg.getSessionKey();
        String content = msg.getContent();

        // 解析 sessionId
        String sessionId = content != null ? content.replaceFirst("(?i)/resume\\s*", "").trim() : "";

        if (sessionId.isEmpty()) {
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "❌ 请提供要恢复的 sessionId，如：/resume amber-atlas", List.of(), Map.of()
            ));
        }

        // 检查 session 文件是否存在
        java.nio.file.Path sessionsDir = workspace.resolve("sessions");
        java.nio.file.Path sessionPath = sessionsDir.resolve(sessionId + ".jsonl");

        if (!Files.exists(sessionPath)) {
            log.info("Resume session not found: {} at {}", sessionId, sessionPath);
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "❌ 会话不存在或已过期: " + sessionId, List.of(), Map.of()
            ));
        }

        try {
            // 更新 sessions.json 映射 (sessionKey -> sessionId)
            sessions.resumeSession(sessionKey, sessionId);

            // 清除缓存，强制重新加载
            sessions.invalidate(sessionKey);

            // 加载 todos
            loadTodosForSession(sessionsDir, sessionId);

            // 恢复计划模式状态
            restorePlanModeFromSession(sessionKey, sessionsDir, sessionId);

            // 通知用户
            String output = "✅ 已恢复会话: " + sessionId + "\n之前的上下文已加载。";
            commandManager.addLocalCommand(new LocalCommand(content, output));

            log.info("Session resumed: sessionKey={}, sessionId={}", sessionKey, sessionId);

            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, output, List.of(), Map.of()
            ));
        } catch (Exception e) {
            log.warn("Resume session failed: {}", sessionId, e);
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "❌ 恢复会话失败: " + e.getMessage(), List.of(), Map.of()
            ));
        }
    }

    /**
     * 处理 /fork <指令> 命令：Fork 并行子代理执行任务
     *
     * 对齐 Open-ClaudeCode: /fork 是 fire-and-forget，结果通过 REPL 显示
     *
     * 流程：
     * 1. 解析指令
     * 2. 获取当前会话消息作为父上下文
     * 3. 后台执行 fork
     * 4. 立即返回"已启动"给用户
     * 5. fork 完成后发送结果给用户
     */
    private CompletionStage<OutboundMessage> handleForkCommand(InboundMessage msg) {
        String channel = msg.getChannel();
        String chatId = msg.getChatId();
        String sessionKey = msg.getSessionKey();
        String content = msg.getContent();

        // 解析指令
        String directive = content != null ? content.replaceFirst("(?i)/fork\\s*", "").trim() : "";

        if (directive.isEmpty()) {
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "❌ 请提供 Fork 指令，如：/fork 帮我搜索这个问题的解决方案", List.of(), Map.of()
            ));
        }

        // 获取当前会话
        Session session = sessions.getOrCreate(sessionKey);
        List<Map<String, Object>> parentMessages = session != null ? session.getMessages() : List.of();

        // 获取最后一条 assistant 消息
        Map<String, Object> lastAssistantMessage = null;
        for (int i = parentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = parentMessages.get(i);
            if ("assistant".equals(m.get("role"))) {
                lastAssistantMessage = m;
                break;
            }
        }

        // 构建 ForkContext
        ForkContext forkContext = ForkContext.builder()
                .parentAgentId("main")
                .directive(directive)
                .parentMessages(parentMessages)
                .parentAssistantMessage(lastAssistantMessage)
                .build();

        // 创建 SubagentContext
        SubagentContext subagentContext = SubagentContext.builder().build();

        // 生成 fork ID
        String forkId = UUID.randomUUID().toString().substring(0, 8);

        // 获取 sessionId
        String sessionId = session != null ? session.getSessionId() : sessionKey;

        log.info("Fork command: forkId={}, directive={}", forkId, directive);

        // Fire-and-forget: 后台执行
        CompletableFuture.runAsync(() -> {
            try {
                // 执行 fork
                CompletableFuture<ForkAgentExecutor.ForkResult> future =
                        forkAgentExecutor.execute(sessionId, forkContext, subagentContext);

                // 设置完成回调 - 发送结果给用户
                future.whenComplete((result, ex) -> {
                    String output;
                    if (result != null && result.success) {
                        output = String.format("✅ Fork [%s] 完成:\n\n%s", forkId, result.result);
                    } else if (result != null && result.killed) {
                        output = String.format("⚠️ Fork [%s] 被终止", forkId);
                    } else {
                        String error = result != null ? result.error : (ex != null ? ex.getMessage() : "unknown");
                        output = String.format("❌ Fork [%s] 失败:\n\n%s", forkId, error);
                    }

                    // 发送到渠道
                    bus.publishOutbound(new OutboundMessage(
                            channel,
                            chatId,
                            output,
                            List.of(),
                            Map.of()
                    ));
                });
            } catch (Exception e) {
                log.error("Fork execution failed", e);
                bus.publishOutbound(new OutboundMessage(
                        channel,
                        chatId,
                        "❌ Fork 执行失败: " + e.getMessage(),
                        List.of(),
                        Map.of()
                ));
            }
        });

        // 立即返回"已启动"
        String startMsg = String.format("🚀 Fork 已启动: [%s]\n指令: %s\n\n结果将完成后发送...", forkId, truncate(directive, 50));
        commandManager.addLocalCommand(new LocalCommand(content, startMsg));

        return CompletableFuture.completedFuture(new OutboundMessage(
                channel, chatId, startMsg, List.of(), Map.of()
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    @NotNull
    private ProgressCallback getBusProgressCallback(InboundMessage msg, ProgressCallback onProgress) {
        ProgressCallback busProgress = new ProgressCallback() {
            @Override
            public void onProgress(String content1, boolean toolHint) {
                Map<String, Object> meta = new LinkedHashMap<>();
                if (msg.getMetadata() != null) meta.putAll(msg.getMetadata());
                meta.put("_progress", true);
                meta.put("_tool_hint", toolHint);
                bus.publishOutbound(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        content1,
                        List.of(),
                        meta
                ));
            }

            @Override
            public void onReasoning(String content) {
                Map<String, Object> meta = new LinkedHashMap<>();
                if (msg.getMetadata() != null) meta.putAll(msg.getMetadata());
                meta.put("_progress", true);
                meta.put("_tool_hint", false);
                meta.put("_reasoning", true);
                bus.publishOutbound(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        content,
                        List.of(),
                        meta
                ));
            }
        };
        ProgressCallback progress = (onProgress != null) ? onProgress : busProgress;
        return progress;
    }


    private static String extractMessageId(Map<String, Object> meta) {
        if (CollUtil.isEmpty(meta)) {
            return null;
        }
        Object msgId = meta.get("messageId");
        if (msgId == null) {
            msgId = meta.get("message_id");
            return msgId == null ? null
                    : String.valueOf(msgId);
        } else {
            return String.valueOf(msgId);
        }

    }

    private CompletionStage<RunResult> runAgentLoop(
            InboundMessage msg,
            List<Map<String, Object>> initialMessages,
            ToolView tools,
            boolean isContextPress,
            ProgressCallback onProgress
    ) {
        return runAgentLoop(msg, initialMessages, tools, isContextPress, onProgress, true);
    }

    private CompletionStage<RunResult> runAgentLoop(
            InboundMessage msg,
            List<Map<String, Object>> initialMessages,
            ToolView tools,
            boolean isContextPress,
            ProgressCallback onProgress,
            boolean saveToSession
    ) {
        CompletableFuture<RunResult> out = new CompletableFuture<>();
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);
        List<String> toolsUsed = new ArrayList<>();

        Session sess = sessions.getOrCreate(msg.getSessionKey());
        UsageAccumulator usageAcc = usageTrackers.computeIfAbsent(msg.getSessionKey(), k -> sess.obtainLastUsage());

        // 从配置创建 ContextPruningSettings
        ContextPruningSettings pruningSettings = createPruningSettings();
        int contextWindow = ContextWindowDiscovery.resolveContextTokensForModel(
                null, model, null, this.contextWindow, runtimeSettings.getCurrentConfig());


        State st = new State();

        Map<String, Object> userMsg1 = initialMessages.get(initialMessages.size() - 1);

        // 添加原始日志
        memoryStore.appendToToday(GsonFactory.getGson().toJson(userMsg1));

        if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc, saveToSession)) {
            return out;
        }

        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (st.done.get()) {
                    return;
                }

                if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc, saveToSession)) {
                    return;
                }

                AgentRuntimeSettings.Snapshot rs = runtimeSnapshot();
                st.iteration++;
                if (st.iteration > rs.maxIterations()) {
                    st.finalContent =
                            "错误，已达到最大工具调用迭代次数 (" + rs.maxIterations() + ")，任务未完成。请尝试将任务拆分为更小的步骤。";
                    st.done.set(true);

                    // 添加原始日志
                    Map<String, Object> systemMsg = new HashMap<>();
                    systemMsg.put("role", "agent_system");
                    systemMsg.put("content", st.finalContent);
                    systemMsg.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    memoryStore.appendToToday(GsonFactory.getGson().toJson(systemMsg));

                    out.complete(new RunResult(st.finalContent, toolsUsed, messages, usageAcc.getTotal()));
                    return;
                }


                // 检查上下文使用情况
                double contextRatio = getContextRatioByUsage(usageAcc, messages);
                double consolidateThreshold = currentConsolidateThreshold();
                double softTrimThreshold = currentSoftTrimThreshold();
                // 获取会话累积已使用 token 总数
                Session sessForUsage = sessions.getOrCreate(msg.getSessionKey());
                long totalUsedTokens = sessForUsage.getTotalTokens();
                String totalUsedStr = totalUsedTokens >= 1_000_000
                        ? String.format("%.1fM", totalUsedTokens / 1_000_000.0)
                        : totalUsedTokens >= 1_000
                                ? String.format("%.1fK", totalUsedTokens / 1_000.0)
                                : String.valueOf(totalUsedTokens);
                long currentTokens = usageAcc.hasData() ? usageAcc.getContextSize() : 0;
                String currentStr = currentTokens >= 1_000_000
                        ? String.format("%.1fM", currentTokens / 1_000_000.0)
                        : currentTokens >= 1_000
                                ? String.format("%.1fK", currentTokens / 1_000.0)
                                : String.valueOf(currentTokens);
                String thresholdStr = contextWindow * consolidateThreshold >= 1_000_000
                        ? String.format("%.1fM", contextWindow * consolidateThreshold / 1_000_000.0)
                        : contextWindow * consolidateThreshold >= 1_000
                                ? String.format("%.1fK", contextWindow * consolidateThreshold / 1_000.0)
                                : String.valueOf(contextWindow * consolidateThreshold);
                String softThresholdStr = contextWindow * softTrimThreshold >= 1_000_000
                        ? String.format("%.1fM", contextWindow * softTrimThreshold / 1_000_000.0)
                        : contextWindow * softTrimThreshold >= 1_000
                                ? String.format("%.1fK", contextWindow * softTrimThreshold / 1_000.0)
                                : String.valueOf(contextWindow * softTrimThreshold);
                // 打印上下文统计
                log.info("上下文统计：已用 {} tokens，上下文使用率 {}% ({}{})，压缩阈值 {}% ({}),软裁剪阈值: {}% ({})",
                        totalUsedStr,
                        String.format("%.1f", contextRatio * 100),
                        currentStr,
                        contextRatio > consolidateThreshold ? " ⚠️" : "",
                        String.format("%.1f", consolidateThreshold * 100),
                        thresholdStr,
                        String.format("%.1f", softTrimThreshold * 100), softThresholdStr);

                // 执行上下文修剪（软裁剪，只裁剪过大的内容）
                // 如果 usageAcc 没有数据（刚完成压缩），跳过软修剪
                if (isContextPress && usageAcc.hasData() && (contextRatio > consolidateThreshold || contextRatio > softTrimThreshold )) {
                    var session = sessions.getOrCreate(msg.getSessionKey());
                    List<Map<String, Object>> prunedMessages = ContextPruner.pruneContextMessages(
                            messages, pruningSettings, contextWindow,
                            // 不修剪 skill 工具的结果，因为其中包含技能内容，裁剪后 LLM 不知道该技能
                            toolName -> !"skill".equalsIgnoreCase(toolName)
                    );

                    messages.clear();
                    messages.addAll(prunedMessages);
                    // 修剪场景：过滤后替换 session 的消息列表
                    // 跳过 system 和常驻技能/本地命令描述消息
                    session.setMessages(filterSessionMessages(prunedMessages));
                    session.setUpdatedAt(LocalDateTime.now());
                    sessions.save(session);
                }

                // 是否停止
                if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc, saveToSession)) {
                    return;
                }

                // 发起调用
                CompletableFuture<providers.LLMResponse> llmFuture = provider.chatWithRetry(
                        messages,
                        tools.getDefinitions(),
                        rs.model(),
                        rs.maxTokens(),
                        rs.temperature(),
                        rs.reasoningEffort(),
                        rs.think(),
                        rs.extraBody(),
                        () -> isStopRequested(msg.getSessionKey())
                ).toCompletableFuture();

                registerLlmCall(msg.getSessionKey(), llmFuture);

                llmFuture.whenComplete((resp, ex) -> {
                    if (st.done.get()) {
                        return;
                    }

                    if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc, saveToSession)) {
                        return;
                    }

                    if (ex != null) {
                        Throwable root = (ex instanceof CompletionException && ex.getCause() != null)
                                ? ex.getCause()
                                : ex;

                        if (root instanceof CancellationException || isStopRequested(msg.getSessionKey())) {
                            if (st.done.compareAndSet(false, true)) {
                                out.complete(new RunResult("已停止。", toolsUsed, messages, usageAcc.getTotal()));
                            }
                            return;
                        }

                        String errorMsg = root.getMessage() != null ? root.getMessage() : root.toString();

                        if (ContextOverflowDetector.isLikelyContextOverflowError(errorMsg)) {
                            log.warn("检测到上下文溢出: {}", errorMsg);
                            st.done.set(true);
                            out.complete(new RunResult(
                                    ContextOverflowDetector.formatOverflowError(),
                                    toolsUsed,
                                    messages,
                                    usageAcc.getTotal()
                            ));
                            return;
                        }

                        st.done.set(true);

                        // 添加原始日志
                        Map<String, Object> systemMsg = new HashMap<>();
                        systemMsg.put("role", "agent_system");
                        systemMsg.put("content", errorMsg);
                        systemMsg.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                        memoryStore.appendToToday(GsonFactory.getGson().toJson(systemMsg));

                        out.completeExceptionally(root);
                        return;
                    }

                    if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc, saveToSession)) {
                        return;
                    }


                    // =================== 以下代表执行成功 ===================
                    // 如果推理为空，从 content 中提取 think 标签并设置到 ReasoningContent
                    if(StrUtil.isBlank(resp.getReasoningContent())) {
                        String thinkBlock = Helpers.obtainThinkBlock(resp.getContent());
                        if(StrUtil.isNotBlank(thinkBlock)) {
                            resp.setReasoningContent(thinkBlock);
                        }
                    }

                    // 记录思考内容
                    if(StrUtil.isNotBlank(resp.getReasoningContent())) {
                        log.info("LLM 思考: \n{}", resp.getReasoningContent());
                    }

                    // 移除思考标签，获取干净的内容
                    String clean = stripThink(resp.getContent());
                    if(StrUtil.isNotBlank(clean)) {
                        log.info("LLM 回复:\n{} \n\n", clean);
                    }

                    usageAcc.accumulate(resp);

                    // 更新 Session 的 usage 汇总（持久化）
                    Map<String, Integer> usageMap = resp.getUsage();
                    if (usageMap != null && !usageMap.isEmpty()) {
                        Session sess = sessions.getOrCreate(msg.getSessionKey());
                        int input = getUsageInt(usageMap, "prompt_tokens", "input_tokens", "input");
                        int output = getUsageInt(usageMap, "completion_tokens", "output_tokens", "output");
                        int cacheRead = getUsageInt(usageMap, "cache_read_input_tokens", "cached_tokens", "cache_read");
                        int cacheWrite = getUsageInt(usageMap, "cache_creation_input_tokens", "cache_write");
                        sess.addUsage(input, output, cacheRead, cacheWrite);
                        // 记录最后一次对话的上下文大小（用于判断压缩必要性）
                        sess.setLastCallInput(input);
                        sess.setLastCallOutput(output);
                        sess.setLastCallCacheRead(cacheRead);
                        sess.setLastCallCacheWrite(cacheWrite);
                    }

                    if (resp.hasToolCalls()) {
                        if (onProgress != null) {
                            // 先发送推理/思考内容，让 UI 在工具调用前展示
                            if (StrUtil.isNotBlank(resp.getReasoningContent())) {
                                onProgress.onReasoning(resp.getReasoningContent());
                            }
                            // 再发送伴随工具调用的文本（如有）
                            if (clean != null) {
                                onProgress.onProgress(clean, false);
                            }
                            onProgress.onProgress(toolHint(resp.getToolCalls()), true);
                        }

                        List<Map<String, Object>> toolCallDicts = Helpers.buildToolCallDicts(resp.getToolCalls());

                        List<Map<String, Object>> updated = Helpers.addAssistantMessage(
                                messages,
                                clean,
                                toolCallDicts,
                                resp.getReasoningContent(),
                                resp.getThinkingBlocks()
                        );
                        messages.clear();
                        messages.addAll(updated);

                        executeToolCallsSequential(msg, resp.getToolCalls(), toolsUsed, messages, tools)
                                .whenComplete((v, ex2) -> {
                                    if (st.done.get()) {
                                        return;
                                    }

                                    if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc, saveToSession)) {
                                        return;
                                    }

                                    if (ex2 != null) {
                                        Throwable root2 = (ex2 instanceof CompletionException && ex2.getCause() != null)
                                                ? ex2.getCause()
                                                : ex2;

                                        if (root2 instanceof CancellationException || isStopRequested(msg.getSessionKey())) {
                                            if (st.done.compareAndSet(false, true)) {
                                                out.complete(new RunResult("已停止。", toolsUsed, messages, usageAcc.getTotal()));
                                            }
                                            return;
                                        }

                                        st.done.set(true);


                                        // 添加原始日志
                                        Map<String, Object> systemMsg = new HashMap<>();
                                        systemMsg.put("role", "agent_system");
                                        systemMsg.put("content", "工具调用异常：\n" + root2.getMessage());
                                        systemMsg.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                                        memoryStore.appendToToday(GsonFactory.getGson().toJson(systemMsg));

                                        out.completeExceptionally(root2);
                                    } else {
                                        // 保存本轮新增的消息到 session（防止中断丢失）
                                        if (saveToSession) {
                                            var sess = sessions.getOrCreate(msg.getSessionKey());
                                            List<Map<String, Object>> hist = sess.getHistory();
                                            int startIdx = 2 + hist.size();
                                            if (messages.size() > startIdx) {
                                                saveTurn(sess, new ArrayList<>(messages.subList(startIdx, messages.size())));
                                            }
                                        }

                                        executor.execute(this);
                                    }
                                });
                        return;
                    }

                    // 添加原始日志（包含 usage，对齐 Claude Code）
                    Map<String, Object> assistant = new HashMap<>();
                    assistant.put("role", "assistant");
                    assistant.put("content", clean);
                    assistant.put("reasoning_content", resp.getReasoningContent());
                    assistant.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    // 添加 usage 字段（对齐 Claude Code 每条消息记录 usage）
                    if (usageMap != null && !usageMap.isEmpty()) {
                        Map<String, Object> usageEntry = new HashMap<>();
                        usageEntry.put("input_tokens", getUsageInt(usageMap, "prompt_tokens", "input_tokens", "input"));
                        usageEntry.put("output_tokens", getUsageInt(usageMap, "completion_tokens", "output_tokens", "output"));
                        usageEntry.put("cache_creation_input_tokens", getUsageInt(usageMap, "cache_creation_input_tokens", "cache_write"));
                        usageEntry.put("cache_read_input_tokens", getUsageInt(usageMap, "cache_read_input_tokens", "cached_tokens", "cache_read"));
                        assistant.put("usage", usageEntry);
                    }
                    memoryStore.appendToToday(GsonFactory.getGson().toJson(assistant));



                    List<Map<String, Object>> updated = Helpers.addAssistantMessage(
                            messages,
                            clean,
                            null,
                            resp.getReasoningContent(),
                            resp.getThinkingBlocks()
                    );
                    messages.clear();
                    messages.addAll(updated);

                    // 保存本轮消息到 session
                    if (saveToSession) {
                        var sess = sessions.getOrCreate(msg.getSessionKey());
                        List<Map<String, Object>> hist = sess.getHistory();
                        int startIdx = 2 + hist.size();
                        if (messages.size() > startIdx) {
                            saveTurn(sess, new ArrayList<>(messages.subList(startIdx, messages.size())));
                        }
                    }

                    // PostSamplingHook: 在 LLM 响应后触发（如 Session Memory 提取）
                    runPostSamplingHooks(
                            msg.getSessionKey(),
                            messages,
                            hasToolCallsInLastAssistantMessage(messages),
                            context.buildSystemPrompt()
                    );

                    st.finalContent = clean;
                    st.done.set(true);
                    out.complete(new RunResult(st.finalContent, toolsUsed, messages, usageAcc.getTotal()));
                });
            }
        };

        executor.execute(step);
        return out;
    }

    private CompletionStage<Void> executeToolCallsSequential(
            InboundMessage msg,
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> messages,
            ToolView tools
    ) {
        String sessionKey = msg.getSessionKey();
        if (isStopRequested(sessionKey)) {
            return CompletableFuture.failedFuture(new CancellationException("session stopped"));
        }

        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);

        // 构建子agent的上下文
        ToolUseContext toolContext = ToolUseContext.builder()
                .sessionId(sessionKey)
                .workspace(workspace.toString())
                .tools(tools.getDefinitions())
                .toolView(tools)
                .messages(messages)
                .queryTracking(new ToolUseContext.QueryTracking())
                .build();

        for (var tc : toolCalls) {
            chain = chain.thenCompose(v -> {
                if (isStopRequested(sessionKey)) {
                    return CompletableFuture.failedFuture(new CancellationException("session stopped"));
                }

                toolsUsed.add(tc.getName());

                try {
                    log.info("工具调用: {}({})", tc.getName(), safeTruncate(GsonFactory.toJson(tc.getArguments()), 200));
                } catch (Exception ignored) {
                }

                // 设置当前 sessionKey 供 Tool 使用
                cliAgentHandler.setCurrentSessionKey(sessionKey);

                return tools.execute(tc.getName(), tc.getArguments(), toolContext)
                        .whenComplete((result, ex) -> {
                            // 清除 sessionKey 和 ToolUseContext
                            cliAgentHandler.clearCurrentSessionKey();
                        })
                        .thenCompose(rawResult -> {
                            // AskUserQuestion 特殊处理：第一次返回 questions 时暂停等待用户输入
                            if (AskUserQuestionTool.NAME.equals(tc.getName())
                                && rawResult != null && rawResult.contains("awaiting_response")) {
                                return handleAskUserQuestionPause(tc, rawResult, msg, messages, toolContext, tools);
                            }

                            handleToolResult(tc, rawResult, sessionKey, msg, messages, tools);
                            return CompletableFuture.<Void>completedFuture(null);
                        }).exceptionally(ex -> {
                            Throwable root = (ex instanceof CompletionException && ex.getCause() != null)
                                    ? ex.getCause()
                                    : ex;

                            if (root instanceof CancellationException || isStopRequested(sessionKey)) {
                                throw new CompletionException(root);
                            }

                            // 通知用户工具执行失败
                            String errMsg = root.toString();
                            if (errMsg.length() > 200) {
                                errMsg = errMsg.substring(0, 200) + "...";
                            }
                            bus.publishOutbound(new OutboundMessage(
                                    msg.getChannel(),
                                    msg.getChatId(),
                                    "❌ 工具执行失败: " + tc.getName() + " - " + errMsg,
                                    List.of(),
                                    Map.of("_progress", true)
                            ));

                            String err = formatToolError(tc.getName(), ex);
                            List<Map<String, Object>> updated =
                                    Helpers.addToolResult(messages, tc.getId(), tc.getName(), err);
                            messages.clear();
                            messages.addAll(updated);
                            return null;
                        });
            });
        }

        return chain;
    }

    private static String formatToolError(String toolName, Throwable ex) {
        Throwable root = (ex instanceof CompletionException || ex instanceof ExecutionException) ? ex.getCause() : ex;
        if (root instanceof TimeoutException) {
            return "{\"error\":\"tool_timeout\",\"tool\":\"" + toolName + "\"}";
        }
        return "{\"error\":\"tool_failed\",\"tool\":\"" + toolName + "\",\"message\":\"" + safeOneLine(root.toString()) + "\"}";
    }

    private static String safeOneLine(String s) {
        return (s == null) ? "" : s.replace("\n", " ").replace("\r", " ");
    }

    /** 正常工具结果处理：发布到 bus + 添加到消息上下文 */
    private void handleToolResult(ToolCallRequest tc, String rawResult, String sessionKey,
                                   InboundMessage msg, List<Map<String, Object>> messages,
                                   ToolView tools) {
        if (isStopRequested(sessionKey)) {
            throw new CancellationException("session stopped");
        }

        if (rawResult != null && rawResult.contains("Command timed out")) {
            bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(), msg.getChatId(),
                    "⏱️ 命令执行超时，已中断", List.of(),
                    Map.of("_progress", true)));
        }

        String result = maybePersistToolResult(tools, tc.getName(), tc.getId(), rawResult);
        trackFileReadIfNeeded(tc, result);

        List<Map<String, Object>> updated =
                Helpers.addToolResult(messages, tc.getId(), tc.getName(), result);

        bus.publishOutbound(new OutboundMessage(
                msg.getChannel(), msg.getChatId(),
                result != null ? result : "", List.of(),
                Map.of("_progress", true, "_tool_result", true,
                       "tool_name", tc.getName(), "tool_call_id", tc.getId())));

        memoryStore.appendToToday(GsonFactory.getGson().toJson(updated.get(updated.size() - 1)));

        messages.clear();
        messages.addAll(updated);
    }

    /** AskUserQuestion 第一次调用：发布问题到 UI 并暂停等待用户输入 */
    private CompletionStage<Void> handleAskUserQuestionPause(
            ToolCallRequest tc, String rawResult,
            InboundMessage msg, List<Map<String, Object>> messages,
            ToolUseContext toolContext, ToolView tools) {
        // 发布问题到 bus 供 UI 展示（不添加到 messages）
        bus.publishOutbound(new OutboundMessage(
                msg.getChannel(), msg.getChatId(),
                rawResult, List.of(),
                Map.of("_progress", true, "_tool_result", true,
                       "tool_name", tc.getName(), "tool_call_id", tc.getId(),
                       "_needs_user_input", true)));

        CompletableFuture<String> answerFuture = new CompletableFuture<>();
        pendingUserQuestions.put(tc.getId(), answerFuture);

        return answerFuture.thenCompose(answersJson -> {
            pendingUserQuestions.remove(tc.getId());
            if (isStopRequested(msg.getSessionKey())) {
                return CompletableFuture.<Void>failedFuture(
                        new CancellationException("session stopped"));
            }

            // 用 answers 再次调用 AskUserQuestion
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions =
                (List<Map<String, Object>>) tc.getArguments().get("questions");
            Map<String, Object> argsWithAnswers = new LinkedHashMap<>(tc.getArguments());
            @SuppressWarnings("unchecked")
            Map<String, String> answers = GsonFactory.getGson().fromJson(answersJson, Map.class);
            argsWithAnswers.put("answers", answers);

            return tools.execute(tc.getName(), argsWithAnswers, toolContext)
                    .thenAccept(finalResult -> {
                        // 构建结构化结果：text 供 LLM 阅读，questions/answers 供 UI 渲染
                        Map<String, Object> structured = new LinkedHashMap<>();
                        structured.put("text", finalResult != null ? finalResult : "");
                        structured.put("questions", questions != null ? questions : List.of());
                        structured.put("answers", answers != null ? answers : Map.of());
                        String structuredJson = GsonFactory.toJson(structured);

                        String r = maybePersistToolResult(tools, tc.getName(), tc.getId(), structuredJson);
                        trackFileReadIfNeeded(tc, r);

                        List<Map<String, Object>> updated =
                                Helpers.addToolResult(messages, tc.getId(), tc.getName(), r);

                        bus.publishOutbound(new OutboundMessage(
                                msg.getChannel(), msg.getChatId(),
                                structuredJson, List.of(),
                                Map.of("_progress", true, "_tool_result", true,
                                       "tool_name", tc.getName(), "tool_call_id", tc.getId())));

                        memoryStore.appendToToday(GsonFactory.getGson().toJson(
                                updated.get(updated.size() - 1)));

                        messages.clear();
                        messages.addAll(updated);
                    });
        });
    }

    /**
     * 由 UI 调用：用户回答了 AskUserQuestion 的问题。
     * @param toolCallId 工具调用 ID
     * @param answers 问题文本 → 答案的映射
     */
    public void answerUserQuestion(String toolCallId, Map<String, String> answers) {
        CompletableFuture<String> future = pendingUserQuestions.remove(toolCallId);
        if (future != null) {
            future.complete(GsonFactory.getGson().toJson(answers));
        } else {
            log.warn("answerUserQuestion: 未找到 toolCallId={} 的待处理问题", toolCallId);
        }
    }

    /**
     * 处理 CLI Agent 完成事件
     * 发送消息给主代理，让其感知并决定下一步操作
     */
    private void handleCliAgentCompletion(providers.cli.CliAgentPool.CliAgentCompletionEvent event) {
        if (event.sessionKey() == null || event.sessionKey().isBlank()) {
            log.warn("CLI Agent completion event without sessionKey, skipping notification");
            return;
        }

        // 解析 sessionKey
        String[] parts = event.sessionKey().split(":", 2);
        String channel = parts[0];
        String chatId = parts.length > 1 ? parts[1] : "";

        // 构建通知消息内容
        StringBuilder content = new StringBuilder();
        content.append("🔔 CLI Agent 执行完成\n\n");
        content.append("- **项目**: ").append(event.project()).append("\n");
        content.append("- **类型**: ").append(event.agentType()).append("\n");
        content.append("- **状态**: ").append(event.success() ? "✅ 成功" : "❌ 失败").append("\n");
        content.append("- **耗时**: ").append(event.durationMs() / 1000.0).append("秒\n");
        content.append("- **Token**: ").append(event.inputTokens()).append("/").append(event.outputTokens()).append("\n");

        if (!event.success() && event.errorMessage() != null) {
            content.append("- **错误**: ").append(event.errorMessage()).append("\n");
        }

        content.append("- **会话文件**: `").append(workspace.resolve("sessions").resolve("cliagent").resolve(event.sessionFile()).toAbsolutePath().toString()).append("`\n");
        content.append("\n如需了解详细执行过程，请读取上述会话文件。");

        // 构建元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("cli_agent_complete", true);
        metadata.put("project", event.project());
        metadata.put("agent_type", event.agentType());
        metadata.put("session_id", event.sessionId());
        metadata.put("session_file", event.sessionFile());
        metadata.put("success", event.success());
        metadata.put("duration_ms", event.durationMs());
        metadata.put("input_tokens", event.inputTokens());
        metadata.put("output_tokens", event.outputTokens());

        // 发送 InboundMessage 到主代理
        // 使用特殊的 senderId 标识这是系统通知
        InboundMessage notification = new InboundMessage(
                channel,
                "cli-agent-system",
                chatId,
                content.toString(),
                List.of(),
                metadata
        );

        // 发布到 MessageBus，主代理会收到并处理
        bus.publishInbound(notification);

        log.info("Sent CLI Agent completion notification to main agent: sessionKey={}, project={}, success={}",
                event.sessionKey(), event.project(), event.success());
    }

    private static String safeTruncate(String s, int maxLen) {
        return (s == null) ? "" : (s.length() > maxLen ? s.substring(0, maxLen) : s);
    }

    /**
     * 工具结果预览大小（字符数）
     */
    private static final int TOOL_RESULT_PREVIEW_CHARS = 2_000;

    /**
     * Collect post-compact attachments to preserve context across compaction.
     * Aligned with Open-ClaudeCode: compactConversation() post-compact attachment collection.
     *
     * Attachments collected:
     * 1. File attachments: re-read recently accessed files
     * 2. Plan attachment: if a plan file exists
     * 3. Plan mode attachment: if currently in plan mode
     * 4. Skill attachment: if skills were invoked
     * 5. Async agent attachments: if background agents are running
     *
     * @param sessionKey Session identifier
     * @param readFileSnapshot Pre-compact read file state snapshot
     * @param messagesToKeep Messages preserved after compaction
     * @return List of attachment messages
     */
    private List<Map<String, Object>> collectPostCompactAttachments(
            String sessionKey,
            Map<String, Long> readFileSnapshot,
            List<Map<String, Object>> messagesToKeep) {

        List<Map<String, Object>> attachments = new java.util.ArrayList<>();

        // 1. File attachments: restore recently read files
        String planFilePath = PlanFileManager.getPlanFilePath(sessionKey) != null
                ? PlanFileManager.getPlanFilePath(sessionKey).toString()
                : null;

        List<Map<String, Object>> fileAttachments =
                CompactService.createPostCompactFileAttachments(
                        readFileSnapshot, planFilePath, messagesToKeep);
        attachments.addAll(fileAttachments);

        // 2. Plan attachment: if a plan file exists
        Map<String, Object> planAttachment = CompactService.createPlanAttachmentIfNeeded(sessionKey, null);
        if (planAttachment != null) {
            attachments.add(planAttachment);
        }

        // 3. Plan mode attachment: if currently in plan mode
        // TODO: wire up to actual plan mode state when plan mode feature is implemented.
        // Open-ClaudeCode checks: appState.toolPermissionContext.mode === 'plan'
        // (see compact.ts:1542-1548). javaclawbot needs a PermissionMode tracking
        // equivalent — likely in ToolUseContext or AppState.
        // Map<String, Object> planModeAttachment =
        //         CompactService.createPlanModeAttachmentIfNeeded(sessionKey, null, isPlanMode);
        // if (planModeAttachment != null) {
        //     attachments.add(planModeAttachment);
        // }

        // 4. Skill attachment: if skills were invoked
        Map<String, String> invokedSkills = getInvokedSkillsForSession(sessionKey);
        Map<String, Object> skillAttachment =
                CompactService.createSkillAttachmentIfNeeded(invokedSkills);
        if (skillAttachment != null) {
            attachments.add(skillAttachment);
        }

        // 5. Async agent attachments: if background agents are running
        Map<String, ?> tasks = getRunningTasks(sessionKey);
        List<Map<String, Object>> agentAttachments =
                CompactService.createAsyncAgentAttachmentsIfNeeded(tasks, null);
        attachments.addAll(agentAttachments);

        return attachments;
    }

    /**
     * Get invoked skills for a session.
     * Returns a map of skill name to skill content, combining:
     * - Always-loaded (resident) skills: loaded at conversation start
     * - User-loaded skills: loaded via the skill tool or /prefix during conversation
     *
     * Aligned with Open-ClaudeCode: getInvokedSkillsForAgent() in bootstrap/state.ts:1530-1541
     */
    private Map<String, String> getInvokedSkillsForSession(String sessionKey) {
        Map<String, String> skills = new java.util.LinkedHashMap<>();
        if (skillsLoader == null) return skills;

        // 1. Always-loaded (resident) skills — loaded at conversation start
        for (String skillName : skillsLoader.getAlwaysSkills()) {
            String content = skillsLoader.loadSkill(skillName);
            if (content != null && !content.isBlank()) {
                skills.put(skillName, content);
            }
        }

        // 2. User-loaded skills — loaded via skill tool or /prefix during conversation
        if (commandManager != null) {
            for (String skillName : commandManager.getUserLoadedSkills()) {
                // Skip if already added from always skills
                if (skills.containsKey(skillName)) continue;
                String content = skillsLoader.loadSkill(skillName);
                if (content != null && !content.isBlank()) {
                    skills.put(skillName, content);
                }
            }
        }

        return skills;
    }

    /**
     * Get currently running background tasks.
     */
    private Map<String, ?> getRunningTasks(String sessionKey) {
        // Return task state from the TaskFramework if available
        return Map.of();
    }

    /**
     * Track file reads for post-compact file restoration.
     * Called after each successful ReadFileTool execution.
     *
     * @param tc Tool call request
     * @param result Tool execution result
     */
    private void trackFileReadIfNeeded(ToolCallRequest tc, String result) {
        if (!"Read".equals(tc.getName()) || result == null) return;
        // Only track successful reads (not errors or file-not-found)
        if (result.startsWith("Error:")) return;

        Map<String, Object> args = tc.getArguments();
        if (args == null) return;

        String filePath = args.get("file_path") instanceof String fp ? fp : null;
        if (filePath == null) filePath = args.get("path") instanceof String p ? p : null;
        if (filePath == null) return;

        try {
            Path resolvedPath = workspace.resolve(filePath).normalize();
            readFileState.track(resolvedPath.toString());
        } catch (Exception e) {
            // Ignore tracking failures
        }
    }

    /**
     * 如果工具结果过大，持久化到磁盘并返回预览。
     * 对齐 Claude Code 的 toolResultStorage.maybePersistLargeToolResult()。
     */
    private String maybePersistToolResult(ToolView toolView, String toolName, String toolCallId, String result) {
        if (result == null) return result;

        // 获取工具实例
        Object toolObj = toolView.get(toolName);
        if (!(toolObj instanceof Tool tool)) return result;

        int toolMaxChars = tool.maxResultSizeChars();
        if (toolMaxChars <= 0) {
            return result;
        }

        // 动态上限：根据上下文窗口按比例限制单个工具结果的最大字符数
        int contextChars = currentContextWindowChars();
        int dynamicCap = Math.max(TOOL_RESULT_PREVIEW_CHARS, contextChars);
        int effectiveMax = Math.min(toolMaxChars, dynamicCap);

        if (result.length() <= effectiveMax) {
            return result; // 不需要持久化
        }

        // 持久化到磁盘
        // Aligned with CC's toolResultStorage.persistToolResult()
        try {
            java.nio.file.Path resultDir = workspace.resolve(".tool-results");
            Files.createDirectories(resultDir);
            String fileName = (toolCallId != null && !toolCallId.isBlank())
                    ? toolCallId
                    : (toolName + "_" + System.currentTimeMillis());
            java.nio.file.Path resultFile = resultDir.resolve(fileName + ".txt");
            Files.writeString(resultFile, result);

            // 生成预览（在最后一个换行符处截断，对齐 CC 的 generatePreview()）
            String preview = generatePreview(result, TOOL_RESULT_PREVIEW_CHARS);

            return String.format(
                    "<persisted-output>\n" +
                            "Output too large (%d chars). Full output saved to: %s\n\n" +
                            "Preview (first %d chars):\n%s\n...\n" +
                            "</persisted-output>",
                    result.length(), resultFile, TOOL_RESULT_PREVIEW_CHARS, preview);
        } catch (Exception e) {
            log.warn("持久化工具结果失败: {}", e.getMessage());
            // 持久化失败，直接截断
            int maxLen = Math.min(effectiveMax, result.length());
            return result.substring(0, maxLen) + "\n... (truncated, " + (result.length() - maxLen) + " more chars)";
        }
    }

    /**
     * 生成预览文本，在最后一个换行符处截断。
     * 对齐 Claude Code 的 toolResultStorage.generatePreview()。
     */
    private static String generatePreview(String content, int maxChars) {
        if (content.length() <= maxChars) return content;

        // Truncate at maxChars, then walk back to last newline
        String truncated = content.substring(0, maxChars);
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > maxChars / 2) {
            return truncated.substring(0, lastNewline);
        }
        return truncated;
    }


    /**
     * saveTurn - 将本轮新增的消息清洗后追加到 Session
     * <p>
     * 只做清洗工作：
     * - 跳过 system 消息（每次构建都会重新生成）
     * - 跳过常驻技能/本地命令描述消息（每次构建都会重新生成）
     * - 过滤空的 assistant 消息
     * - 将 base64 图片替换为 [image]
     *
     * @param session 当前会话
     * @param newMessages 本轮新增的消息（不含已有历史）
     */
    private void saveTurn(Session session, List<Map<String, Object>> newMessages) {
        for (Map<String, Object> m : newMessages) {
            Map<String, Object> entry = new LinkedHashMap<>(m);
            Object role = entry.get("role");
            Object content = entry.get("content");

            // 跳过 system 消息（每次构建都会重新生成）
            if ("system".equals(String.valueOf(role))) {
                continue;
            }

            // 跳过常驻技能/本地命令描述的 user 消息（每次构建都会重新生成）
            if ("user".equals(String.valueOf(role)) && isContextPreamble(content)) {
                continue;
            }

            // 过滤空的 assistant 消息
            if ("assistant".equals(String.valueOf(role))) {
                Object toolCalls = entry.get("tool_calls");
                boolean emptyContent = (content == null) || (content instanceof String s && s.isBlank());
                boolean noToolCalls = (toolCalls == null) || (toolCalls instanceof List<?> l && l.isEmpty());
                if (emptyContent && noToolCalls) continue;
            }

            // base64 图片替换为 [image]
            if ("user".equals(String.valueOf(role)) && content instanceof List<?> list) {
                List<Object> replaced = new ArrayList<>();
                for (Object c : list) {
                    if (c instanceof Map<?, ?> cm
                            && "image_url".equals(String.valueOf(cm.get("type")))
                            && cm.get("image_url") instanceof Map<?, ?> im
                            && im.get("url") instanceof String u
                            && u.startsWith("data:image/")) {
                        replaced.add(Map.of("type", "text", "text", "[image]"));
                        continue;
                    }
                    replaced.add(c);
                }
                entry.put("content", replaced);
            }

            entry.putIfAbsent("timestamp", LocalDateTime.now().toString());
            session.getMessages().add(entry);
        }

        session.setUpdatedAt(LocalDateTime.now());
        sessions.save(session);
    }

    /**
     * 判断是否是上下文前置消息（常驻技能或本地命令描述）
     * 这些消息每次构建都会重新生成，不需要保存到 session
     */
    private boolean isContextPreamble(Object content) {
        if (content instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof Map<?, ?> cm
                        && "text".equals(String.valueOf(cm.get("type")))
                        && cm.get("text") instanceof String t) {
                    if (t.contains("<resident-skill>") || t.contains("<local-command-caveat>")) {
                        return true;
                    }
                }
            }
        } else if (content instanceof String s) {
            return s.contains("<resident-skill>") || s.contains("<local-command-caveat>");
        }
        return false;
    }

    /**
     * 过滤消息列表，跳过每次构建都会重新生成的消息
     * 用于修剪场景，直接设置 session 的消息列表
     */
    private List<Map<String, Object>> filterSessionMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            Object role = m.get("role");
            Object content = m.get("content");

            // 跳过 system 消息
            if ("system".equals(String.valueOf(role))) {
                continue;
            }

            // 跳过常驻技能/本地命令描述的 user 消息
            if ("user".equals(String.valueOf(role)) && isContextPreamble(content)) {
                continue;
            }

            result.add(m);
        }
        return result;
    }

    public CompletionStage<String> processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            ProgressCallback onProgress
    ) {
        String effectiveSessionKey = (sessionKey != null && !sessionKey.isBlank()) ? sessionKey : "cli:direct";
        String effectiveChannel = channel != null ? channel : "cli";
        String effectiveChatId = chatId != null ? chatId : "direct";

        // 清除停止标志
        clearStopRequested(effectiveSessionKey);

        return queue.enqueue(
                effectiveSessionKey,
                () -> connectMcp()
                        .thenCompose(v -> {
                            InboundMessage msg = new InboundMessage(
                                    effectiveChannel,
                                    "user",
                                    effectiveChatId,
                                    content,
                                    null,
                                    null
                            );
                            return processMessage(msg, effectiveSessionKey, onProgress)
                                    .thenApply(resp -> resp != null ? resp.getContent() : "");
                        })
                        .toCompletableFuture(),
                "processDirect"
        );
    }

    public ChannelsConfig getChannelsConfig() {
        return currentChannelsConfig();
    }

    public String getModel() {
        return runtimeSnapshot().model();
    }

    /**
     * 获取 MCP 管理器
     */
    public McpManager getMcpManager() {
        return mcpManager;
    }

    private AtomicBoolean stopFlag(String sessionKey) {
        return stopFlags.computeIfAbsent(sessionKey, k -> new AtomicBoolean(false));
    }

    private boolean isStopRequested(String sessionKey) {
        AtomicBoolean f = stopFlags.get(sessionKey);
        return f != null && f.get();
    }

    private void clearStopRequested(String sessionKey) {
        AtomicBoolean f = stopFlags.get(sessionKey);
        if (f != null) {
            f.set(false);
        }
    }

    private void requestStop(String sessionKey) {
        stopFlag(sessionKey).set(true);

        List<CompletableFuture<?>> calls = activeLlmCalls.remove(sessionKey);
        if (calls != null) {
            for (CompletableFuture<?> f : calls) {
                try {
                    if (f != null && !f.isDone()) {
                        f.cancel(true);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void registerLlmCall(String sessionKey, CompletableFuture<?> future) {
        activeLlmCalls
                .computeIfAbsent(sessionKey, k -> new CopyOnWriteArrayList<>())
                .add(future);

        future.whenComplete((v, ex) -> {
            CopyOnWriteArrayList<CompletableFuture<?>> list = activeLlmCalls.get(sessionKey);
            if (list != null) {
                list.remove(future);
                if (list.isEmpty()) {
                    activeLlmCalls.remove(sessionKey, list);
                }
            }
        });
    }

    // =================== Phase 4: 任务系统取消处理方法 ===================
    
    /**
     * 处理取消请求（对应 useCancelRequest.ts - handleCancel）
     * 使用 TaskControlService 终止所有 Agent 任务
     */
    private void handleCancel() {
        if (taskControl != null && appState != null) {
            taskControl.killAllAgentTasks(appState, appState.setter());
            log.info("handleCancel: killed all agent tasks via TaskControlService");
        }
    }

    /**
     * 处理终止所有 Agent 请求（对应 useCancelRequest.ts - handleKillAgents）
     * 使用 TaskControlService 终止所有 Agent 任务
     */
    private void handleKillAgents() {
        if (taskControl != null && appState != null) {
            taskControl.killAllAgentTasks(appState, appState.setter());
            log.info("handleKillAgents: killed all agent tasks via TaskControlService");
        }
    }

    /**
     * 获取任务控制服务（供外部使用）
     */
    public TaskControlService getTaskControl() {
        return taskControl;
    }

    /**
     * 获取应用状态（供外部使用）
     */
    public AppState getAppState() {
        return appState;
    }

    /**
     * 加载指定 session 的 todos 到 AppState
     * 对应 Open-ClaudeCode: extractTodosFromTranscript() - 从持久化文件恢复 todos
     *
     * @param sessionsDir sessions 根目录 (workspace/sessions)
     * @param sessionId session ID
     */
    public void loadTodosForSession(java.nio.file.Path sessionsDir, String sessionId) {
        if (sessionsDir == null || sessionId == null || sessionId.isEmpty()) {
            log.debug("Cannot load todos: sessionsDir or sessionId is null or empty");
            return;
        }
        agent.tool.task.TodoWriteTool.loadTodosIntoAppState(sessionsDir, sessionId, appState.setter());
    }

    /**
     * 从已恢复的 Session 消息中还原 Plan Mode 状态。
     *
     * 对齐 Open-ClaudeCode: copyPlanForResume() + getSlugFromLog()
     * - 扫描消息中是否有 EnterPlanMode 的 tool_result
     * - 从 tool_result content 中提取 wordSlug（格式：Plan file: /path/plans/{slug}.md）
     * - 检查 plan 文件是否还存在
     * - 存在则调用 PlanModeState.restorePlanMode() 恢复内存状态
     */
    private void restorePlanModeFromSession(String sessionKey, java.nio.file.Path sessionsDir, String sessionId) {
        try {
            java.nio.file.Path sessionPath = sessionsDir.resolve(sessionId + ".jsonl");
            if (!Files.exists(sessionPath)) return;

            // 读取 session JSONL，扫描消息
            java.util.List<String> lines = Files.readAllLines(sessionPath);
            String enterPlanResult = null;

            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> obj = GsonFactory.getGson().fromJson(line, java.util.Map.class);
                    if (obj == null) continue;

                    // 跳过 metadata 行
                    if ("metadata".equals(obj.get("_type"))) continue;

                    // 查找 role=tool + name=EnterPlanMode 的消息
                    String role = obj.get("role") instanceof String ? (String) obj.get("role") : null;
                    String name = obj.get("name") instanceof String ? (String) obj.get("name") : null;
                    if ("tool".equals(role) && "EnterPlanMode".equals(name)) {
                        Object content = obj.get("content");
                        if (content instanceof String) {
                            enterPlanResult = (String) content;
                        }
                        // 继续扫描，取最后一个 EnterPlanMode tool result（覆盖可能的重试）
                    }

                    // 检查是否已经 ExitPlanMode（说明 plan mode 已结束）
                    if ("tool".equals(role) && "ExitPlanMode".equals(name)) {
                        // 有 ExitPlanMode 调用，说明已经退出了 plan mode
                        // 只有当 ExitPlanMode 返回错误时才继续查找
                        Object content = obj.get("content");
                        if (content instanceof String && !((String) content).startsWith("{\"error\"")) {
                            enterPlanResult = null; // 成功退出，清除标记
                        }
                    }
                } catch (Exception ignored) {
                    // 跳过无法解析的行
                }
            }

            if (enterPlanResult == null || enterPlanResult.startsWith("{\"error\"")) {
                return; // 未在 plan mode 中
            }

            // 从 tool_result content 提取 wordSlug
            // 格式: "...Plan file: /path/plans/{slug}.md..."
            String wordSlug = extractWordSlugFromMessage(enterPlanResult);
            if (wordSlug == null) return;

            // 检查 plan 文件是否存在
            if (!PlanFileManager.hasPlanBySlug(wordSlug)) {
                // 对齐 Open-ClaudeCode: 从 session 消息中恢复 plan 内容
                String recovered = recoverPlanContentFromMessages(lines, wordSlug);
                if (recovered != null) {
                    PlanFileManager.writePlanBySlug(wordSlug, recovered);
                    log.info("Recovered plan content from session messages: slug={}", wordSlug);
                } else {
                    log.warn("Plan file not found for slug={} and unable to recover from messages, skipping plan mode restore", wordSlug);
                    return;
                }
            }

            // 恢复 plan mode 状态
            agent.tool.plan.PlanModeState.restorePlanMode(sessionKey, wordSlug);
            log.info("Restored plan mode on resume: sessionKey={}, wordSlug={}", sessionKey, wordSlug);
        } catch (Exception e) {
            log.warn("Failed to restore plan mode on resume: {}", e.toString());
        }
    }

    /**
     * 从 session 消息中恢复 plan 内容（当 plan 文件丢失时）。
     *
     * 对齐 Open-ClaudeCode: src/utils/plans.ts → recoverPlanFromMessages()
     *
     * 按优先级（从后往前扫描，取最近一次出现）：
     * 1. ExitPlanMode tool_use 的 input.plan 字段
     * 2. user 消息的 planContent 字段
     * 3. attachment 消息的 plan_file_reference 类型
     */
    private String recoverPlanContentFromMessages(java.util.List<String> lines, String wordSlug) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> obj = GsonFactory.getGson().fromJson(line, java.util.Map.class);
                if (obj == null) continue;
                if ("metadata".equals(obj.get("_type"))) continue;

                String role = obj.get("role") instanceof String ? (String) obj.get("role") : null;

                // 1) Assistant 消息：检查 tool_calls 中的 ExitPlanMode tool_use input.plan
                if ("assistant".equals(role)) {
                    Object toolCalls = obj.get("tool_calls");
                    if (toolCalls instanceof java.util.List<?> list) {
                        for (Object tc : list) {
                            if (tc instanceof java.util.Map<?, ?> tcMap) {
                                Object fn = tcMap.get("function");
                                if (fn instanceof java.util.Map<?, ?> fnMap) {
                                    String name = fnMap.get("name") instanceof String ? (String) fnMap.get("name") : null;
                                    if ("ExitPlanMode".equals(name)) {
                                        Object args = fnMap.get("arguments");
                                        // arguments 可能是 JSON 字符串或 Map
                                        if (args instanceof String argsStr && !argsStr.isBlank()) {
                                            try {
                                                java.util.Map<?, ?> argsMap = GsonFactory.getGson().fromJson(argsStr, java.util.Map.class);
                                                Object plan = argsMap.get("plan");
                                                if (plan instanceof String planStr && !planStr.isBlank()) {
                                                    return planStr;
                                                }
                                            } catch (Exception ignored) {}
                                        } else if (args instanceof java.util.Map<?, ?> argsMap) {
                                            Object plan = argsMap.get("plan");
                                            if (plan instanceof String planStr && !planStr.isBlank()) {
                                                return planStr;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2) User 消息：planContent 字段
                if ("user".equals(role)) {
                    Object planContent = obj.get("planContent");
                    if (planContent instanceof String pc && !pc.isBlank()) {
                        return pc;
                    }
                }

                // 3) Attachment 消息：plan_file_reference
                String type = obj.get("type") instanceof String ? (String) obj.get("type") : null;
                if ("attachment".equals(type)) {
                    Object attachment = obj.get("attachment");
                    if (attachment instanceof java.util.Map<?, ?> attMap) {
                        String attType = attMap.get("type") instanceof String ? (String) attMap.get("type") : null;
                        if ("plan_file_reference".equals(attType)) {
                            Object plan = attMap.get("planContent");
                            if (plan instanceof String planStr && !planStr.isBlank()) {
                                return planStr;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 跳过无法解析的行
            }
        }
        return null;
    }

    /**
     * 公开方法：在 Agent 启动时确保 plan mode 状态已从 session 恢复。
     * 供 GUI 和 CLI 在初始化时调用，确保 tool 过滤在首条消息处理前生效。
     */
    public void ensurePlanModeState(String sessionKey, Session session) {
        lazyRestorePlanMode(sessionKey, session);
    }

    /**
     * 惰性恢复 plan mode 状态（每次请求时检查，仅首次执行）。
     * 解决进程重启后 PlanModeState 为空但 session 消息中仍处于 plan mode 的问题。
     *
     * 对齐 Open-ClaudeCode: lazy restoration via copyPlanForResume on first request
     */
    private void lazyRestorePlanMode(String sessionKey, Session session) {
        if (sessionKey == null || session == null) return;
        // 已有缓存，说明已经恢复过
        if (agent.tool.plan.PlanModeState.getWordSlug(sessionKey) != null) return;

        java.nio.file.Path sessionsDir = workspace.resolve("sessions");
        String sessionId = session.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) return;

        restorePlanModeFromSession(sessionKey, sessionsDir, sessionId);
    }

    /**
     * 从 EnterPlanMode tool_result 内容中提取 wordSlug。
     * tool_result 格式: "...Plan file: /Users/.../plans/calm-brewing-tiger.md..."
     */
    private String extractWordSlugFromMessage(String content) {
        if (content == null) return null;
        // 匹配 "Plan file: /path/plans/{slug}.md"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("plans/([\\w-]+)\\.md").matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ==================== PostSamplingHook Integration ====================

    /**
     * PostSamplingHook: 在每次 LLM 响应后触发
     *
     * 对齐 Open-ClaudeCode: PostSamplingHooks 在每次 assistant 消息后执行
     * 用于 Session Memory 自动提取等功能
     *
     * @param sessionId 会话 ID
     * @param messages 当前消息列表
     * @param hasToolCallsInLastTurn 最后一次 Assistant 消息是否有工具调用
     * @param systemPrompt 系统提示词
     */
    private void runPostSamplingHooks(
            String sessionId,
            List<Map<String, Object>> messages,
            boolean hasToolCallsInLastTurn,
            String systemPrompt) {

        if (sessionMemoryService == null || !sessionMemoryService.isEnabled()) {
            return;
        }

        // 估算当前 token 数
        int currentTokenCount = estimateTokenCount(messages);

        // 检查是否满足提取条件
        if (sessionMemoryService.shouldExtract(currentTokenCount, hasToolCallsInLastTurn)) {
            log.info("PostSamplingHook triggered: sessionId={}, tokenCount={}", sessionId, currentTokenCount);

            // 异步触发提取，不阻塞主对话
            CompletableFuture.runAsync(() -> {
                try {
                    // 等待之前的提取完成
                    SessionMemoryUtils.waitForExtraction();

                    // 触发提取
                    sessionMemoryService.extract(sessionId, messages, systemPrompt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("PostSamplingHook interrupted while waiting for extraction");
                }
            });
        }
    }

    /**
     * 估算消息列表的 token 数
     */
    private int estimateTokenCount(List<Map<String, Object>> messages) {
        if (messages == null) return 0;

        int total = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += (s.length() / 4) + 1;
            }
        }
        return total;
    }

    /**
     * 检查最后一次 Assistant 消息是否有工具调用
     */
    private boolean hasToolCallsInLastAssistantMessage(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        // 从后往前找最后一个 assistant 消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            Object toolCalls = msg.get("tool_calls");
            return toolCalls instanceof List<?> list && !list.isEmpty();
        }
        return false;
    }
}
