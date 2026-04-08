package agent;

import agent.command.CommandQueueManager;
import agent.command.LocalCommand;
import agent.subagent.SessionsSpawnTool;
import agent.subagent.SubagentManager;
import agent.subagent.SubagentsControlTool;
import agent.tool.*;
import agent.tool.cli.CliAgentTool;
import agent.tool.cron.CronTool;
import agent.tool.file.*;
import agent.tool.mcp.McpManager;
import agent.tool.message.MessageTool;
import agent.tool.message.PruneMessagesTool;
import agent.tool.shell.ExecTool;
import agent.tool.shell.Shell;
import agent.tool.skill.SkillTool;
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

import config.channel.ChannelsConfig;
import config.mcp.MCPServerConfig;
import config.tool.ToolsConfig;
import config.tool.WebFetchConfig;
import config.tool.WebSearchConfig;
import context.ContextBuilder;
import context.ContextOverflowDetector;
import context.ContextWindowDiscovery;
import corn.CronService;
import memory.MemoryStore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.ToolCallRequest;
import providers.cli.CliAgentCommandHandler;
import context.ContextPruner;
import context.ContextPruningSettings;
import session.Session;
import session.SessionManager;
import skills.SkillsLoader;
import utils.GsonFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;

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
    private final LLMProvider provider;
    private final java.nio.file.Path workspace;
    private final String model;
    private final int maxIterations;
    private final double temperature;
    private final int maxTokens;
    private final int contextWindow;
    private final int memoryWindow;
    private final String reasoningEffort;
    private final CronService cronService;
    private final boolean restrictToWorkspace;
    /**
     * 是否启用思考模式（保留推理内容）
     */
    private final ContextBuilder context;
    private final SessionManager sessions;
    private final SubagentManager subagents;
    private final AgentRuntimeSettings runtimeSettings;
    private final ExecutorService executor;
    private final MemoryStore memoryStore;
    private final CommandQueueManager commandManager;
    private final SkillsLoader skillsLoader;
    /**
     * 全局共享工具
     */
    private final ToolRegistry sharedTools;
    private volatile boolean running = false;
    private final Map<String, MCPServerConfig> mcpServers;
    /**
     * MCP 动态工具管理器
     */
    private final McpManager mcpManager;
    /**
     * CLI Agent 命令处理器
     */
    private final CliAgentCommandHandler cliAgentHandler;

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
        this.subagents = new SubagentManager(
                provider, workspace, bus, this.model, this.temperature, this.maxTokens,
                this.reasoningEffort, currentTools(), restrictToWorkspace, null
        );
        this.mcpServers = (mcpServers != null) ? mcpServers : Map.of();
        this.mcpManager = new McpManager(workspace, mcpServers, executor);

        // 初始化 CLI Agent 命令处理器
        this.cliAgentHandler = new CliAgentCommandHandler(workspace);
        this.cliAgentHandler.setSendToChannelWithMeta((message, sessionKey, metadata) -> {
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

            // 如果是 CLI 子代理输出, 记录到主代理对话历史
            if (metadata != null && Boolean.TRUE.equals(metadata.get("cliAgentOutput"))) {
                String project = (String) metadata.get("project");
                // 格式: [CC/p1] 或 [OpenCode/p2]
                String historyContent = message;  // 原始消息已经包含项目信息

                // 记录到会话历史
                Session session = sessions.getOrCreate(sessionKey);
                if (session != null) {
                    session.addMessage("assistant", historyContent, Map.of(
                            "source", "cli_subagent",
                            "project", project,
                            "agent_type", project != null && project.contains("opencode") ? "opencode" : "claude"
                    ));
                    log.debug("Recorded CLI subagent output to session history: project={}, chars={}", project, historyContent.length());
                }
            }
        });

        // 注册工具
        this.sharedTools = new ToolRegistry();

        // 技能工具
        this.skillsLoader = new SkillsLoader(workspace);
        this.commandManager = new CommandQueueManager(skillsLoader);

        registerSharedTools();

        int maxConcurrent = 4;
        if (runtimeSettings != null) {
            var cfg = runtimeSettings.getCurrentConfig();
            maxConcurrent = cfg.getAgents().getDefaults().getMaxConcurrent();
        }
        this.context = new ContextBuilder(workspace, currentConfig().getAgents().getDefaults().getBootstrapConfig());
        this.memoryStore = new MemoryStore(workspace);
        // AgentLoopQueue 使用独立线程池，避免 executeImmediately 中的 join() 阻塞共享线程池导致死锁
        this.queue = new AgentLoopQueue(maxConcurrent);
        this.cronToolFacade = (cronService != null) ? new CronTool(cronService) : null;
    }

    private AgentRuntimeSettings.Snapshot runtimeSnapshot() {
        if (runtimeSettings != null) {
            return runtimeSettings.snapshot();
        }
        return new AgentRuntimeSettings.Snapshot(
                workspace, model, maxIterations, temperature, maxTokens, contextWindow, memoryWindow,
                reasoningEffort, null, null, restrictToWorkspace, mcpServers, channelsConfig, null
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
        FileStateCache sharedFileCache = new FileStateCache();

        // 文件/命令/网络工具：无会话上下文，可共享
        sharedTools.register(new ReadFileTool(workspace, allowedDir, sharedFileCache));
        sharedTools.register(new WriteTool(workspace, allowedDir, sharedFileCache));
        sharedTools.register(new EditTool(workspace, allowedDir, sharedFileCache));
        sharedTools.register(new FileSystemTools.ReadWordTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ReadWordStructuredTool(workspace, allowedDir));

        sharedTools.register(new ListDirTool(workspace, allowedDir));
//        sharedTools.register(new FileSystemTools.ReadPptTool(workspace, allowedDir));
//        sharedTools.register(new FileSystemTools.ReadPptStructuredTool(workspace, allowedDir));
//        sharedTools.register(new FileSystemTools.ReadWordTool(workspace, allowedDir));
//        sharedTools.register(new FileSystemTools.ReadWordStructuredTool(workspace, allowedDir));
        // 校验git环境
        Shell.getShellConfig();
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
        sharedTools.register(new GrepTool(workspace, allowedDir));
        sharedTools.register(new GlobTool(workspace, allowedDir));

        // CLI Agent 工具（开发者模式下可用）
        if (currentConfig().getAgents().getDefaults().isDevelopment()) {
            sharedTools.register(new CliAgentTool(cliAgentHandler));
        }
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

        // ========== 多 Agent / subagent 相关 ==========
        SessionsSpawnTool spawnTool = new agent.subagent.SessionsSpawnTool(subagents);
        spawnTool.setContext(sessionKy, channel, chatId);
        localTools.register(spawnTool);

        SubagentsControlTool subagentsControlTool = new SubagentsControlTool(subagents);
        subagentsControlTool.setAgentSessionKey(sessionKy);  // 关键：设置会话Key以便查询子代理
        localTools.register(subagentsControlTool);


        // CronTool 带 channel/chatId 上下文，也做成每请求独立
        if (cronService != null) {
            CronTool cronTool = new CronTool(cronService);
            cronTool.setContext(channel, chatId);
            localTools.register(cronTool);
        }

        // MCP 动态工具快照
        ToolRegistry mcpTools = mcpManager.snapshotRegistry();

        return new CompositeToolView(sharedTools, mcpTools, localTools);
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
        localTools.register(new ReadFileTool(workspace, null));
        localTools.register(new GlobTool(workspace, null));
        localTools.register(new GrepTool(workspace, null));
        localTools.register(new EditTool(workspace, null));
        localTools.register(new WriteTool(workspace, null));
        localTools.register(new SkillTool(commandManager, skillsLoader));

        return new CompositeToolView(localTools);
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
        localTools.register(new ReadFileTool(workspace, null));
        localTools.register(new EditTool(workspace, null));
        localTools.register(new WriteTool(workspace, null));
        localTools.register(new GlobTool(workspace, null));
        localTools.register(new GrepTool(workspace, null));

        // 添加记忆压缩工具
        localTools.register(new PruneMessagesTool(sessions, sessionKey));

        return new CompositeToolView(localTools);
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
        running = true;
        connectMcp().toCompletableFuture().join();
        log.info("Agent 循环已启动");

        while (running) {
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
        running = false;
        if (queue != null) {
            queue.shutdown();
        }
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

        int finalCancelled = cancelled;
        return subagents.cancelBySession(sessionKey).thenCompose(subCancelled -> {
            int total = finalCancelled + subCancelled;
            return bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    total > 0 ? "⏹ 已停止 " + total + " 个任务。" : "没有活动任务可停止。",
                    List.of(),
                    Map.of()
            ));
        });
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
     * 入口处检测并执行上下文压缩
     */
    private void checkAndExecuteContextCompress(InboundMessage msg) {
        // 跳过系统消息和命令
        if ("system".equalsIgnoreCase(msg.getChannel())) {
            return;
        }

        String sessionKey = msg.getSessionKey();
        Session session = sessions.getOrCreate(sessionKey);
        List<Map<String, Object>> history = session.getHistory();

        int estimatedChars = ContextPruner.estimateContextChars(history);
        double contextRatio = currentContextWindowChars() > 0
                ? (double) estimatedChars / currentContextWindowChars() : 0;
        double threshold = currentConsolidateThreshold();

        if (contextRatio > threshold) {
            log.info("入口检测：上下文使用率 {}% > 阈值 {}%，执行压缩",
                    String.format("%.1f", contextRatio * 100),
                    String.format("%.0f", threshold * 100));

            // 通知用户
            bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "⏳ 上下文已满，正在自动压缩...",
                    List.of(),
                    Map.of()
            )).toCompletableFuture().join();

            // 执行上下文压缩
            executeContextCompress(msg);

            // 通知用户
            bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "上下文压缩完成, 请继续对话",
                    List.of(),
                    Map.of()
            )).toCompletableFuture().join();
            log.info("上下文压缩完成");
        }
    }

    /**
     * 执行上下文压缩（只压缩消息，不整理记忆）
     */
    private void executeContextCompress(InboundMessage message) {
        var channel = message.getChannel();
        var sessionKey = message.getSessionKey();
        var chatId = message.getChatId();

        // 构建压缩消息系统提示词
        List<Map<String, Object>> initial = context.buildContextCompressMessages(
                sessions.getOrCreate(sessionKey).getHistory(),
                MemoryStore.PRUNE_SYSTEM_PROMPT.replaceAll("\\{workspace}", workspace.toString()),
                null, channel, chatId
        );
        ToolView tools = buildContextCompressRequestTools(sessionKey, channel, chatId, null);

        // 创建临时消息用于 runAgentLoop
        InboundMessage compressMsg = new InboundMessage();
        compressMsg.setChannel(channel);
        compressMsg.setSenderId(message.getSenderId());
        compressMsg.setContent("");
        compressMsg.setSessionKeyOverride(sessionKey);

        // 同步执行（不需要保存到 session）
        runAgentLoop(compressMsg, initial
                , tools, false
                , (context, toolHit) -> bus.publishOutbound(new OutboundMessage(
                message.getChannel()
                , message.getChatId()
                , "(上下文压缩进程) " + context
                , List.of()
                , Map.of()
                )
        ), false).toCompletableFuture().join();
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
            String initPrompt = ResourceUtil.readUtf8Str("templates/init/INIT.md");
            commandManager.addLocalCommand(new LocalCommand(cmd, ""));
            bus.publishInbound(new InboundMessage(msg.getChannel(), msg.getSenderId(), msg.getChatId(), initPrompt));
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


        if ("/new".equalsIgnoreCase(cmd) || "/clear".equalsIgnoreCase(cmd)) {
            commandManager.addLocalCommand(new LocalCommand(cmd, "新会话已开始"));
            //sessions.save(session);
            Session newSession = sessions.createNew(session.getKey());
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
            return handleContextPress(msg, session);
        }
        // 触发记忆命令
        if ("/memory".equalsIgnoreCase(cmd)) {
            String output = "记忆整理命令已触发";
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            return handleMemoryCommand(msg, session);
        }

        if ("/help".equalsIgnoreCase(cmd)) {
            String output = "🐱 javaclawbot 命令:\n\n" +
                    "对话管理:\n" +
                    "  /new — 开始新对话\n" +
                    "  /clear — 清空当前对话\n" +
                    "  /memory — 整理当前对话记忆\n" +
                    "  /stop — 停止当前任务\n" +
                    "  /help — 显示可用命令\n\n" +
                    "项目绑定:\n" +
                    "  /bind <名称>=<路径> [--main] — 绑定项目\n" +
                    "  /bind --main <路径> — 直接设为主代理项目\n" +
                    "  /unbind <名称> — 解绑项目\n" +
                    "  /projects — 列出所有项目\n\n" +
                    "CLI Agent:\n" +
                    "  /cc <项目> <提示词> — 使用 Claude Code\n" +
                    "  /oc <项目> <提示词> — 使用 OpenCode\n" +
                    "  /status [项目] — 查看状态\n" +
                    "  /stop <项目> [类型] — 停止 Agent\n" +
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

        ToolView requestTools = buildRequestToolsAndSetContext(
                sessionKey, msg.getChannel(),
                msg.getChatId(),
                extractMessageId(msg.getMetadata())
        );

        var mtool = requestTools.get("message");
        if (mtool instanceof MessageTool m) {
            m.startTurn();
        }

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
            if (msgTool instanceof MessageTool m && m.isSentInTurn()) {
                return null;
            }

            return new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    finalContent,
                    List.of(),
                    msg.getMetadata() != null ? msg.getMetadata() : Map.of()
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
            runAgentLoop(msg, initial, tools, false, (context, toolHit) -> bus.publishOutbound(new OutboundMessage(
                    msg.getChannel()
                    , msg.getChatId()
                    , "(记忆处理进程) " + context
                    , List.of()
                    , Map.of()
            )), false).toCompletableFuture().join();

            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "✅ 记忆整理完成", List.of(), Map.of()
            ));
        } catch (Exception e) {
            log.warn("记忆整理失败", e);
            return CompletableFuture.completedFuture(new OutboundMessage(
                    channel, chatId, "❌ 记忆整理失败，请重试", List.of(), Map.of()
            ));
        }
    }

    @NotNull
    private ProgressCallback getBusProgressCallback(InboundMessage msg, ProgressCallback onProgress) {
        ProgressCallback busProgress = (content1, toolHint) -> {
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

        UsageAccumulator usageAcc = usageTrackers.computeIfAbsent(msg.getSessionKey(), k -> new UsageAccumulator());

        // 从配置创建 ContextPruningSettings
        ContextPruningSettings pruningSettings = createPruningSettings();
        int contextWindow = ContextWindowDiscovery.resolveContextTokensForModel(
                null, model, null, this.contextWindow, runtimeSettings.getCurrentConfig());

        final int maxOverflowCompactionAttempts = 3;

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


                // 检查是否需要执行硬压缩（阻塞 + 通知用户）
                int estimatedChars = ContextPruner.estimateContextChars(messages);
                // 当前上下文比例
                double contextRatio = contextWindow > 0 ? (double) estimatedChars / currentContextWindowChars() : 0;
                double consolidateThreshold = currentConsolidateThreshold();
                if (contextRatio > consolidateThreshold) {
                    // 通知用户正在压缩
                    log.info("上下文使用率 {}% > 阈值 {}%",
                            String.format("%.1f", contextRatio * 100),
                            String.format("%.0f", consolidateThreshold * 100));
                }
                // 执行上下文修剪（软裁剪，只裁剪过大的内容）
                if (isContextPress) {
                    var session = sessions.getOrCreate(msg.getSessionKey());
                    List<Map<String, Object>> prunedMessages = ContextPruner.pruneContextMessages(
                            messages, consolidateThreshold,  currentSoftTrimThreshold(), pruningSettings, contextWindow,
                            // 不修剪 skill 工具的结果，因为其中包含技能内容，裁剪后 LLM 不知道该技能
                            toolName -> !"skill".equalsIgnoreCase(toolName)
                    );
                    int beforeChars = ContextPruner.estimateContextChars(messages);
                    int afterChars = ContextPruner.estimateContextChars(prunedMessages);
                    log.info("上下文已修剪: {} 字符 -> {} 字符", beforeChars, afterChars);
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

                    usageAcc.accumulate(resp);

                    if (resp.hasToolCalls()) {
                        if (onProgress != null) {
                            // 移除思考标签
                            String clean = stripThink(resp.getContent());
                            if (clean != null) onProgress.onProgress(clean, false);
                            onProgress.onProgress(toolHint(resp.getToolCalls()), true);
                        }

                        List<Map<String, Object>> toolCallDicts = new ArrayList<>();
                        for (var tc : resp.getToolCalls()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", tc.getName());
                            fn.put("arguments", GsonFactory.toJson(tc.getArguments()));

                            Map<String, Object> call = new LinkedHashMap<>();
                            call.put("id", tc.getId());
                            call.put("type", "function");
                            call.put("function", fn);
                            toolCallDicts.add(call);
                        }

                        List<Map<String, Object>> updated = context.addAssistantMessage(
                                messages,
                                resp.getContent(),
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

                    // =================== 以下代表执行成功 ===================

                    log.info("思考: \n{}", resp.getReasoningContent());
                    // 移除思考标签
                    String clean = stripThink(resp.getContent());

                    // 添加原始日志
                    Map<String, Object> assistant = new HashMap<>();
                    assistant.put("role", "assistant");
                    assistant.put("content", clean);
                    assistant.put("reasoning_content", resp.getReasoningContent());
                    assistant.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    memoryStore.appendToToday(GsonFactory.getGson().toJson(assistant));

                    log.info("LLM 回复:\n{} \n\n", clean);

                    List<Map<String, Object>> updated = context.addAssistantMessage(
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

                return tools.execute(tc.getName(), tc.getArguments())
                        .thenAccept(rawResult -> {
                            if (isStopRequested(sessionKey)) {
                                throw new CancellationException("session stopped");
                            }

                            // 检测超时错误并通过 bus 发送提醒
                            if (rawResult != null && rawResult.contains("Command timed out")) {
                                bus.publishOutbound(new bus.OutboundMessage(
                                        msg.getChannel(),
                                        msg.getChatId(),
                                        "⏱️ 命令执行超时，已中断",
                                        List.of(),
                                        Map.of("_progress", true)
                                ));
                            }

                            // 检查工具结果大小，过大则持久化到磁盘
                            String result = maybePersistToolResult(tools, tc.getName(), tc.getId(), rawResult);

                            List<Map<String, Object>> updated =
                                    context.addToolResult(messages, tc.getId(), tc.getName(), result);

                            memoryStore.appendToToday(GsonFactory.getGson().toJson(updated.get(updated.size() - 1)));

                            messages.clear();
                            messages.addAll(updated);
                        }).exceptionally(ex -> {
                            Throwable root = (ex instanceof CompletionException && ex.getCause() != null)
                                    ? ex.getCause()
                                    : ex;

                            if (root instanceof CancellationException || isStopRequested(sessionKey)) {
                                throw new CompletionException(root);
                            }

                            String err = formatToolError(tc.getName(), ex);
                            List<Map<String, Object>> updated =
                                    context.addToolResult(messages, tc.getId(), tc.getName(), err);
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

    private static String safeTruncate(String s, int maxLen) {
        return (s == null) ? "" : (s.length() > maxLen ? s.substring(0, maxLen) : s);
    }

    /**
     * 工具结果预览大小（字符数）
     */
    private static final int TOOL_RESULT_PREVIEW_CHARS = 2_000;

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
}
