package agent;

import agent.command.CommandQueueManager;
import agent.command.LocalCommand;
import agent.subagent.SessionsSpawnTool;
import agent.subagent.SubagentManager;
import agent.subagent.SubagentsControlTool;
import agent.tool.*;
import agent.tool.mcp.McpManager;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import config.Config;
import config.agent.AgentRuntimeSettings;

import config.channel.ChannelsConfig;
import config.mcp.MCPServerConfig;
import config.provider.model.ModelConfig;
import config.tool.ToolsConfig;
import config.tool.WebFetchConfig;
import config.tool.WebSearchConfig;
import context.ContextBuilder;
import context.ContextOverflowDetector;
import context.ContextWindowDiscovery;
import corn.CronService;
import memory.MemoryCompaction;
import memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.ToolCallRequest;
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
import java.util.concurrent.locks.ReentrantLock;

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
     * 正在压缩的会话
     */
    private final Set<String> consolidating = ConcurrentHashMap.newKeySet();
    /**
     * 独立的压缩线程池，不和主 AgentLoop 共用
     */
    private final ExecutorService consolidationExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "memory-consolidation");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<String, ReentrantLock> consolidationLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableFuture<?>>> activeTasks = new ConcurrentHashMap<>();

    /** Usage 累积器（按会话） */
    private final ConcurrentHashMap<String, UsageAccumulator> usageTrackers = new ConcurrentHashMap<>();

    /** 并发队列（对齐 OpenClaw Lane-aware FIFO） */
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
        this.memoryStore = new MemoryStore(workspace, context);
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
                reasoningEffort, null, null, restrictToWorkspace, mcpServers, channelsConfig
        );
    }

    private int currentMemoryWindow() {
        return runtimeSnapshot().memoryWindow();
    }

    private int currentContextWindow() {
        return runtimeSnapshot().contentWindow();
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

        // 文件/命令/网络工具：无会话上下文，可共享
        sharedTools.register(new FileSystemTools.ReadFileTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.WriteFileTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.EditFileTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ListDirTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ReadPptTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ReadPptStructuredTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ReadWordTool(workspace, allowedDir));
        sharedTools.register(new FileSystemTools.ReadWordStructuredTool(workspace, allowedDir));
        sharedTools.register(new ExecTool(
                currentTools().getExec().getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                currentTools().getExec().getPathAppend()
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

        // 记忆读取工具,可复用read_file工具
        // sharedTools.register(new MemoryGetTool(workspace));

    }

    private ToolsConfig currentTools() {
        return currentConfig().getTools();
    }

    /**
     * 构建当前请求的工具视图：
     * - sharedTools：全局共享、无上下文工具
     * - mcpTools：动态 MCP 工具（运行期发现）
     * - localTools：当前请求独有、有上下文工具
     *
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

    public CronTool getCronTool() {
        return cronToolFacade;
    }

    private CompletionStage<Void> connectMcp() {
        return mcpManager.ensureConnected()
                .thenApply(v -> {
                    mcpManager.startAutoRefresh(20, TimeUnit.SECONDS);
                    return (Void)null;
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
            if ("/stop".equalsIgnoreCase(content)) {
                handleStop(msg).toCompletableFuture().join();
                continue;
            }

            // 新任务开始前清理 stop 标记
            clearStopRequested(msg.getSessionKey());

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
            UsageAccumulator usageAcc
    ) {
        if (!isStopRequested(sessionKey)) {
            return false;
        }

        if (st.done.compareAndSet(false, true)) {
            out.complete(new RunResult(
                    "已停止。",
                    toolsUsed,
                    messages,
                    usageAcc.getTotal()
            ));
        }
        return true;
    }

    private CompletionStage<Void> handleStop(InboundMessage msg) {
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

    private CompletionStage<OutboundMessage> processMessage(InboundMessage msg, String sessionKeyOverride, ProgressCallback onProgress) {
        if ("system".equals(msg.getChannel())) {
            // 新任务开始前清理 stop 标记
            clearStopRequested(msg.getSessionKey());

            String chat = msg.getChatId();
            String channel = (chat != null && chat.contains(":")) ? chat.split(":", 2)[0] : "cli";
            String chatId = (chat != null && chat.contains(":")) ? chat.split(":", 2)[1] : chat;
            String sessionKy = channel + ":" + chatId;

            ToolView requestTools = buildRequestToolsAndSetContext(sessionKy, channel, chatId, extractMessageId(msg.getMetadata()));

            Session session = sessions.getOrCreate(sessionKy);
            List<Map<String, Object>> history = session.getHistory(currentMemoryWindow());
            List<Map<String, Object>> initial = context.buildMessages(
                    history, msg.getContent(), null, null, channel, chatId
            );

            return runAgentLoop(msg, initial, requestTools, null).thenApply(rr -> {
                //saveTurn(session, rr.messages, 2 + history.size());
                //sessions.save(session);
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


        if ("/new".equalsIgnoreCase(cmd)) {
            String output = "历史会话完成压缩记忆, 新会话已开始";
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            // 发给用户
            bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), "历史会话正在压缩和记忆,请稍等片刻,等待压缩完成!完成后会通知您 😊",
                    List.of(),
                    Map.of()));
            return handleNewCommand(msg, session);
        }

        if ("/help".equalsIgnoreCase(cmd)) {
            String output = "javaclawbot 命令:\n/new — 开始新对话\n/stop — 停止当前任务\n/help — 显示可用命令\n/project <path> — 设置项目路径（开发者模式读取 CODE-AGENT.md/CLAUDE.md）\n/project clear — 清除项目路径";
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

        // 处理 /project 前缀命令
        if (cmd.trim().startsWith("/project")) {
            Object[] result = null;
            try {
                result = context.handleProjectPrefix(msg.getContent());
            } catch (Exception e) {
                log.error("项目路径处理异常", e);
                bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), "抱歉，处理项目路径时出错。如果是window环境请这样使用: /project {项目路径} 不需要加引号", List.of(), Map.of()));
                return CompletableFuture.completedFuture(null);
            }
            String output = (String) result[0];
            commandManager.addLocalCommand(new LocalCommand(cmd, output));
            bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), output, List.of(), Map.of()));
            return CompletableFuture.completedFuture(null);
        }

        int useMemoryWindow = currentMemoryWindow();

        ToolView requestTools = buildRequestToolsAndSetContext(
                sessionKey, msg.getChannel(),
                msg.getChatId(),
                extractMessageId(msg.getMetadata())
        );

        var mtool = requestTools.get("message");
        if (mtool instanceof MessageTool m) {
            m.startTurn();
        }

        List<Map<String, Object>> history = session.getHistory(useMemoryWindow);
        List<Map<String, Object>> initialMessages = context.buildMessages(
                history,
                msg.getContent(),
                null,
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId()
        );

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

        return runAgentLoop(msg, initialMessages, requestTools, progress).thenApply(rr -> {
            String finalContent = rr.finalContent != null
                    ? rr.finalContent
                    : "处理完成但没有响应内容。";

            // 尝试压缩和保存session
            tryScheduleConsolidation(session, rr.messages, 2 + history.size());

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



    /**
     * 尝试压缩上下文
     * @param session
     * @param skip
     */
    private void tryScheduleConsolidation(Session session, List<Map<String, Object>> messages, int skip) {
        String sessionKey = session.getKey();

        // 保存当前上下文至session中
        saveTurn(session, messages, skip);

        // 计算待压缩的大小
        int unconsolidated = session.getMessages().size() - session.getLastConsolidated();

        // 阈值提高，别太早触发
        int threshold = Math.max(20, (int) Math.ceil(currentMemoryWindow() * 0.9));
        if (unconsolidated < threshold) {
            return;
        }

        // 已经在压缩中就直接跳过
        if (!consolidating.add(sessionKey)) {
            return;
        }

        ReentrantLock lock = getConsolidationLock(sessionKey);

        CompletableFuture
                .runAsync(() -> {
                    boolean locked = false;
                    try {
                        // 不阻塞等锁，拿不到就放弃这次，避免和热路径抢
                        locked = lock.tryLock();
                        if (!locked) {
                            return;
                        }

                        // 双检，避免排队期间消息状态已变化
                        int latestUnconsolidated = session.getMessages().size() - session.getLastConsolidated();
                        int latestThreshold = Math.max(20, (int) Math.ceil(currentMemoryWindow() * 0.9));
                        if (latestUnconsolidated < latestThreshold) {
                            return;
                        }

                        // 这里只在专用线程池中阻塞，不影响主对话线程池
                        consolidateMemory(session, false).toCompletableFuture().join();

                        // 压缩完成后, 保存session
                        //saveTurn(session, messages, skip); // 这里不需要再次保存，上面已经保存一次了
                        sessions.save(session);
                    } catch (Exception e) {
                        log.warn("会话压缩失败, sessionKey={}", sessionKey, e);
                    } finally {
                        consolidating.remove(sessionKey);
                        if (locked) {
                            lock.unlock();
                        }
                        pruneConsolidationLock(sessionKey, lock);
                    }
                }, consolidationExecutor);
    }

    private CompletionStage<OutboundMessage> handleNewCommand(InboundMessage msg, Session session) {
        ReentrantLock lock = getConsolidationLock(session.getKey());
        consolidating.add(session.getKey());
        CompletableFuture<OutboundMessage> out = new CompletableFuture<>();

        consolidationExecutor.execute(() -> {
            try {
                lock.lock();
                List<Map<String, Object>> snapshot =
                        session.getMessages().subList(session.getLastConsolidated(), session.getMessages().size());

                if (!snapshot.isEmpty()) {
                    Session temp = new Session(session.getKey());
                    temp.setMessages(new ArrayList<>(snapshot));
                    if (!consolidateMemory(temp, true).toCompletableFuture().join()) {
                        out.complete(new OutboundMessage(
                                msg.getChannel(),
                                msg.getChatId(),
                                "记忆归档失败，会话未清除。请重试。",
                                List.of(),
                                Map.of()
                        ));
                        return;
                    }
                }

                // 保存旧会话并创建新会话（使用新的 sessionId）
                sessions.save(session);
                Session newSession = sessions.createNew(session.getKey());

                out.complete(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        "新会话已开始。",
                        List.of(),
                        Map.of()
                ));
            } catch (Exception e) {
                out.complete(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        "记忆归档失败，会话未清除。请重试。",
                        List.of(),
                        Map.of()
                ));
            } finally {
                consolidating.remove(session.getKey());
                if (lock.isHeldByCurrentThread()) lock.unlock();
                pruneConsolidationLock(session.getKey(), lock);
            }
        });

        return out;
    }

    private static String extractMessageId(Map<String, Object> meta) {
        return (meta == null) ? null
                : (meta.get("message_id") == null) ? null
                : String.valueOf(meta.get("message_id"));
    }

    private CompletionStage<RunResult> runAgentLoop(
            InboundMessage msg,
            List<Map<String, Object>> initialMessages,
            ToolView tools,
            ProgressCallback onProgress
    ) {
        CompletableFuture<RunResult> out = new CompletableFuture<>();
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);
        List<String> toolsUsed = new ArrayList<>();

        UsageAccumulator usageAcc = usageTrackers.computeIfAbsent(msg.getSessionKey(), k -> new UsageAccumulator());

        ContextPruningSettings pruningSettings = ContextPruningSettings.DEFAULT;
        int contextWindow = ContextWindowDiscovery.resolveContextTokensForModel(
                null, model, null, this.contextWindow, runtimeSettings.getCurrentConfig());

        final int maxOverflowCompactionAttempts = 3;
        int[] overflowCompactionAttempts = {0};

        State st = new State();

        Map<String, Object> userMsg1 = initialMessages.get(initialMessages.size() - 1);

        // 添加原始日志
        memoryStore.appendToToday(GsonFactory.getGson().toJson(userMsg1));

        if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc)) {
            return out;
        }

        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (st.done.get()) {
                    return;
                }

                if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc)) {
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

                // 执行上下文修剪
                List<Map<String, Object>> prunedMessages = ContextPruner.pruneContextMessages(
                        messages, pruningSettings, contextWindow,
                        // 不修剪 skill 工具的结果，因为其中包含技能内容，裁剪后 LLM 不知道该技能
                        toolName -> !"skill".equalsIgnoreCase(toolName)
                );
                int beforeChars = ContextPruner.estimateContextChars(messages);
                int afterChars = ContextPruner.estimateContextChars(prunedMessages);
                log.info("上下文已修剪: {} 字符 -> {} 字符", beforeChars, afterChars);
                messages.clear();
                messages.addAll(prunedMessages);

                // 是否停止
                if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc)) {
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
                    // 通过llm执行上下文压缩

                    if (st.done.get()) {
                        return;
                    }

                    if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc)) {
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
                            // 发送通知给用户
                            bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), "检测到上下文溢出, 正在尝试压缩上下文", List.of(), Map.of()));

                            if (overflowCompactionAttempts[0] < maxOverflowCompactionAttempts) {
                                overflowCompactionAttempts[0]++;
                                String notice = "上下文尝试自动压缩 (第 %s/%s 次)".formatted(overflowCompactionAttempts[0], maxOverflowCompactionAttempts);
                                log.info("尝试自动压缩 (第 {}/{} 次)",
                                        overflowCompactionAttempts[0], maxOverflowCompactionAttempts);

                                // 发送通知给用户
                                bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), notice, List.of(), Map.of()));

                                compactMessages(messages).thenAccept(compacted -> {
                                    if (compacted) {
                                        log.info("自动压缩成功，正在重试...");
                                        // 发送通知给用户
                                        bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), "上下文压缩成功, 尝试重试中!", List.of(), Map.of()));
                                        executor.execute(this);
                                    } else {
                                        log.warn("自动压缩失败");
                                        st.done.set(true);
                                        out.complete(new RunResult(
                                                ContextOverflowDetector.formatOverflowError(),
                                                toolsUsed,
                                                messages,
                                                usageAcc.getTotal()
                                        ));
                                    }
                                }).exceptionally(compactEx -> {
                                    log.warn("自动压缩出错: {}", compactEx.toString());

                                    // 发送通知给用户
                                    bus.publishOutbound(new OutboundMessage(msg.getChannel(), msg.getChatId(), "上下文压缩失败, 请重新执行命令!", List.of(), Map.of()));

                                    st.done.set(true);
                                    out.complete(new RunResult(
                                            ContextOverflowDetector.formatOverflowError(),
                                            toolsUsed,
                                            messages,
                                            usageAcc.getTotal()
                                    ));
                                    return null;
                                });
                                return;
                            }

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

                    if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc)) {
                        return;
                    }

                    usageAcc.accumulate(resp);

                    if (resp.hasToolCalls()) {
                        if (onProgress != null) {
                            // 移除思考标签
                            String clean =  stripThink(resp.getContent());
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

                        executeToolCallsSequential(msg.getSessionKey(), resp.getToolCalls(), toolsUsed, messages, tools)
                                .whenComplete((v, ex2) -> {
                                    if (st.done.get()) {
                                        return;
                                    }

                                    if (completeIfStopped(msg.getSessionKey(), st, out, toolsUsed, messages, usageAcc)) {
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
                                        executor.execute(this);
                                    }
                                });
                        return;
                    }

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
            String sessionKey,
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> messages,
            ToolView tools
    ) {
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
                        .thenAccept(result -> {
                            if (isStopRequested(sessionKey)) {
                                throw new CancellationException("session stopped");
                            }

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
     * saveTurn - 将本轮对话消息保存到 Session 中
     *
     * 整体流程：
     *   1. 从 skip 位置开始遍历 messages（跳过已处理的历史消息）
     *   2. 对每条消息做清洗：
     *      - 去掉 reasoning_content（思维链，不需要持久化）
     *      - 过滤掉空的 assistant 消息（既没有内容也没有 tool_calls）
     *      - 截断过长的 tool 返回结果
     *      - 将 user 消息中的 base64 图片替换为 "[image]" 文本（节省存储）
     *   3. 给每条消息打上时间戳，追加到 session.messages 中
     *   4. 更新 session 的 updatedAt 时间
     *
     * @param session  当前会话对象，最终消息会存入 session.getMessages()
     * @param messages 本轮对话的完整消息列表（包含 system/user/assistant/tool 等角色）
     * @param skip     跳过前 skip 条消息（这些已经在之前的调用中保存过了，避免重复）
     */
    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {

        // 从 skip 位置开始遍历，只处理新增的消息
        for (int i = skip; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);

            // 创建一份新的有序 Map 作为清洗后的消息条目
            // 用 LinkedHashMap 保证字段顺序与原始消息一致
            Map<String, Object> entry = new LinkedHashMap<>();

            // ── Step 1: 复制所有字段 ──
            for (var e : m.entrySet()) {

                entry.put(e.getKey(), e.getValue());
            }

            // 取出 role 和 content，后续判断用
            Object role = entry.get("role");
            Object content = entry.get("content");

            // ── Step 2: 过滤空的 assistant 消息 ──
            // assistant 消息如果既没有文本内容也没有 tool_calls，就是无意义的空消息，跳过不保存
            if ("assistant".equals(String.valueOf(role))) {
                Object toolCalls = entry.get("tool_calls");

                // content 为 null 或者是空白字符串
                boolean emptyContent = (content == null) || (content instanceof String s && s.isBlank());

                // tool_calls 为 null 或者是空列表
                boolean noToolCalls = (toolCalls == null) || (toolCalls instanceof List<?> l && l.isEmpty());

                // 两个条件都满足 → 跳过这条消息
                if (emptyContent && noToolCalls) continue;
            }

            // ── Step 3: 截断过长的 tool 返回结果 ──
            // tool 消息的 content 是工具执行的返回值，可能非常长（比如读取了一个大文件）
            // 超过 TOOL_RESULT_MAX_CHARS 的部分截断，替换为 "... (truncated)"
            if ("tool".equals(String.valueOf(role)) && content instanceof String s && s.length() > TOOL_RESULT_MAX_CHARS) {
                entry.put("content", s.substring(0, TOOL_RESULT_MAX_CHARS) + "\n... (truncated)");
            }

            // ── Step 4: 将 user 消息中的 base64 图片替换为文本占位符 ──
            // user 消息的 content 可能是一个 List（多模态消息，包含 text + image_url）
            // base64 编码的图片体积巨大，不适合持久化，用 "[image]" 文本代替
            if ("user".equals(String.valueOf(role)) && content instanceof List<?> list) {
                List<Object> replaced = new ArrayList<>();

                for (Object c : list) {
                    // 判断是否是 base64 图片块：
                    //   { "type": "image_url", "image_url": { "url": "data:image/xxx;base64,..." } }
                    if (c instanceof Map<?, ?> cm
                            && "image_url".equals(String.valueOf(cm.get("type")))
                            && cm.get("image_url") instanceof Map<?, ?> im
                            && im.get("url") instanceof String u
                            && u.startsWith("data:image/")) {
                        // 是 base64 图片 → 替换为文本
                        replaced.add(Map.of("type", "text", "text", "[image]"));
                        continue;
                    }
                    // 非 base64 图片（普通文本块、URL 图片等）→ 原样保留
                    replaced.add(c);
                }

                // 用替换后的列表覆盖原来的 content
                entry.put("content", replaced);
            }

            // ── Step 5: 打时间戳，追加到 session ──
            // 如果消息本身没有 timestamp 字段，补上当前时间
            entry.putIfAbsent("timestamp", LocalDateTime.now().toString());

            // 追加到 session 的消息列表中
            session.getMessages().add(entry);
        }

        // 更新 session 的最后修改时间
        session.setUpdatedAt(LocalDateTime.now());
    }


    private CompletionStage<Boolean> consolidateMemory(Session session, boolean archiveAll) {
        return memoryStore.consolidate(
                session,
                provider,
                model,
                currentContextWindow(),
                0.5,
                archiveAll,
                currentMemoryWindow()
        );
    }

    private CompletionStage<Boolean> compactMessages(List<Map<String, Object>> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (messages.size() <= 4) {
                    log.warn("消息数量不足，无法压缩 (需要 > 4)");
                    return false;
                }

                int contextWindow = currentContextWindow();
                double maxHistoryShare = 0.5;

                List<Map<String, Object>> historyMessages = new ArrayList<>(
                        messages.subList(1, messages.size())
                );

                MemoryCompaction.PruneResult pruneResult = MemoryCompaction.pruneHistoryForContextShare(
                        historyMessages,
                        contextWindow,
                        maxHistoryShare
                );

                if (pruneResult.droppedChunks == 0 && pruneResult.messages.size() == historyMessages.size()) {
                    log.info("无需修剪，消息在预算范围内, 开始总结");
                    return compactMessagesInternal(messages, contextWindow);
                }

                log.info("已修剪 {} 块 ({} 条消息, {} tokens) 以适应历史预算",
                        pruneResult.droppedChunks,
                        pruneResult.droppedMessages,
                        pruneResult.droppedTokens
                );

                List<Map<String, Object>> compacted = new ArrayList<>();
                compacted.add(messages.get(0));
                compacted.addAll(pruneResult.messages);

                messages.clear();
                messages.addAll(compacted);

                log.info("压缩完成: {} 条消息 -> {} 条消息",
                        historyMessages.size() + 1,
                        compacted.size()
                );

                return true;
            } catch (Exception e) {
                log.error("压缩失败: {}", e.toString());
                return false;
            }
        }, consolidationExecutor);
    }

    private boolean compactMessagesInternal(List<Map<String, Object>> messages, int contextWindow) {
        int keepFirst = 1;
        int keepLast = 2;

        if (messages.size() <= keepFirst + keepLast) {
            log.warn("消息太短，无法压缩");
            return false;
        }

        int fromIndex = keepFirst;
        int toIndex = messages.size() - keepLast;

        if (toIndex <= fromIndex) {
            return false;
        }

        List<Map<String, Object>> toCompact = new ArrayList<>(messages.subList(fromIndex, toIndex));
        if (toCompact.isEmpty()) {
            return false;
        }

        List<Map<String, Object>> smallMessages = new ArrayList<>();
        List<String> oversizedNotes = new ArrayList<>();

        for (Map<String, Object> msg : toCompact) {
            if (MemoryCompaction.isOversizedForSummary(msg, contextWindow)) {
                Object role = msg.get("role");
                String roleStr = role != null ? String.valueOf(role) : "message";
                int tokens = MemoryCompaction.estimateTokens(msg);
                oversizedNotes.add(String.format(
                        "[大型 %s (~%dK tokens) 已从摘要中省略]",
                        roleStr,
                        tokens / 1000
                ));
            } else {
                smallMessages.add(msg);
            }
        }

        if (smallMessages.isEmpty()) {
            log.warn("所有消息都过大，无法压缩");
            return false;
        }

        if (smallMessages.size() < toCompact.size()) {
            log.info("跳过 {} 条过大消息", toCompact.size() - smallMessages.size());
            toCompact = smallMessages;
        }

        log.info("正在压缩 {} 条消息 (保留前 {} 条和后 {} 条)",
                toCompact.size(), keepFirst, keepLast);

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                请简洁地总结以下对话，保留关键信息、决策和上下文：\n\n
                ## 如何总结
                - 按主题语义组织，而非按时间顺序
                
                ## 要总结什么：
                - 在多次交互中确认的稳定模式和惯例
                - 关键架构决策、重要文件路径和项目结构
                - 用户对工作流程、工具和沟通风格的偏好
                - 反复出现问题的解决方案和调试洞见
                ## 哪些是不该总结的：
                - 可能不完整的信息——在撰写前核对项目文档
                - 任何重复或与现有 CODE_AGENT.md 指令相矛盾的内容
                - 通过阅读单一文件得出的推测性或未经验证的结论
                """);
        prompt.append(MemoryCompaction.IDENTIFIER_PRESERVATION_INSTRUCTIONS).append("\n\n");

        for (Map<String, Object> msg : toCompact) {
            String role = String.valueOf(msg.get("role"));
            Object content = msg.get("content");
            String contentStr = "";

            if (content instanceof String s) {
                contentStr = s.length() > 500 ? s.substring(0, 500) + "..." : s;
            } else if (content instanceof List) {
                contentStr = "[multimodal content]";
            }

            prompt.append(role).append(": ").append(contentStr).append("\n\n");
        }

        if (!oversizedNotes.isEmpty()) {
            prompt.append("\n").append(String.join("\n", oversizedNotes)).append("\n");
        }

        // 获取参数
        ModelConfig modelConfig = currentConfig().getModelConfig(model);
        Map<String, Object> think = modelConfig != null ? modelConfig.getThink() : null;
        Map<String, Object> extraBody = modelConfig != null ? modelConfig.getExtraBody() : null;

        String summary = provider.chat(
                List.of(Map.of("role", "user", "content", prompt.toString())),
                List.of(),
                model,
                8912,
                0.3,
                null,
                think,
                extraBody,
                () -> false
        ).toCompletableFuture().join().getContent();

        if (summary == null || summary.isBlank()) {
            log.warn("压缩返回空摘要");
            return false;
        }

        List<Map<String, Object>> compacted = new ArrayList<>();
        compacted.add(messages.get(0));

        Map<String, Object> summaryMsg = new LinkedHashMap<>();
        summaryMsg.put("role", "user");
        summaryMsg.put("content", "[之前的对话摘要]\n" + summary);
        compacted.add(summaryMsg);

        for (int i = toIndex; i < messages.size(); i++) {
            compacted.add(messages.get(i));
        }

        messages.clear();
        messages.addAll(compacted);

        log.info("压缩完成: {} 条消息 -> {} 条消息",
                toCompact.size() + keepFirst + keepLast,
                compacted.size()
        );

        return true;
    }

    private ReentrantLock getConsolidationLock(String sessionKey) {
        return consolidationLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
    }

    private void pruneConsolidationLock(String sessionKey, ReentrantLock lock) {
        if (lock != null && !lock.isLocked()) {
            consolidationLocks.remove(sessionKey, lock);
        }
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
