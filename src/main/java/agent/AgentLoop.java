package agent;

import agent.subagent.SubagentManager;
import agent.tool.*;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import com.google.gson.Gson;
import config.AgentRuntimeSettings;
import config.ConfigIO;
import config.ConfigSchema;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final Pattern THINK_BLOCK = Pattern.compile("\\<think\\>.*?\\<\\/think\\>", Pattern.DOTALL);
    private static final int TOOL_RESULT_MAX_CHARS = 100_000;

    private final MessageBus bus;
    private final ConfigSchema.ChannelsConfig channelsConfig;
    private final LLMProvider provider;
    private final java.nio.file.Path workspace;
    private final String model;
    private final int maxIterations;
    private final double temperature;
    private final int maxTokens;
    private final int memoryWindow;
    private final String reasoningEffort;
    private final String braveApiKey;
    private final ConfigSchema.ExecToolConfig execConfig;
    private final CronService cronService;
    private final boolean restrictToWorkspace;
    private final ContextBuilder context;
    private final SessionManager sessions;
    private final SubagentManager subagents;
    private final AgentRuntimeSettings runtimeSettings;
    private final ExecutorService executor;
    private final MemoryStore memoryStore;
    /**
     * 全局共享工具
     */
    private final ToolRegistry sharedTools;
    private volatile boolean running = false;
    private final Map<String, ConfigSchema.MCPServerConfig> mcpServers;
    private volatile boolean mcpConnected = false;
    private volatile boolean mcpConnecting = false;
    private final List<MCPClient> mcpClients = new CopyOnWriteArrayList<>();
    private final Set<String> consolidating = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> consolidationTasks = ConcurrentHashMap.newKeySet();
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

    public AgentLoop(
            MessageBus bus,
            LLMProvider provider,
            java.nio.file.Path workspace,
            String model,
            Integer maxIterations,
            Double temperature,
            Integer maxTokens,
            Integer memoryWindow,
            String reasoningEffort,
            String braveApiKey,
            ConfigSchema.ExecToolConfig execConfig,
            CronService cronService,
            boolean restrictToWorkspace,
            SessionManager sessionManager,
            Map<String, ConfigSchema.MCPServerConfig> mcpServers,
            ConfigSchema.ChannelsConfig channelsConfig,
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
        this.braveApiKey = braveApiKey;
        this.execConfig = (execConfig != null) ? execConfig : new ConfigSchema.ExecToolConfig();
        this.cronService = cronService;
        this.restrictToWorkspace = restrictToWorkspace;
        this.runtimeSettings = runtimeSettings;

        this.sessions = (sessionManager != null) ? sessionManager : new SessionManager(workspace);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("javaclawbot-agent-" + t.getId());
            return t;
        });
        this.subagents = new SubagentManager(
                provider, workspace, bus, this.model, this.temperature, this.maxTokens,
                this.reasoningEffort, braveApiKey, this.execConfig, restrictToWorkspace, null
        );
        this.mcpServers = (mcpServers != null) ? mcpServers : Map.of();

        this.memoryStore = new MemoryStore(workspace);
        // 注册工具
        this.sharedTools = new ToolRegistry();
        registerSharedTools();

        int maxConcurrent = 4;
        if (runtimeSettings != null) {
            var cfg = runtimeSettings.getCurrentConfig();
            maxConcurrent = cfg.getAgents().getDefaults().getMaxConcurrent();
        }
        this.context = new ContextBuilder(workspace, runtimeSettings.getCurrentConfig().getAgents().getDefaults().getBootstrapConfig());
        this.queue = new AgentLoopQueue(maxConcurrent);
        this.cronToolFacade = (cronService != null) ? new CronTool(cronService) : null;
    }

    private AgentRuntimeSettings.Snapshot runtimeSnapshot() {
        if (runtimeSettings != null) {
            return runtimeSettings.snapshot();
        }
        return new AgentRuntimeSettings.Snapshot(
                workspace, model, maxIterations, temperature, maxTokens, memoryWindow,
                reasoningEffort, braveApiKey, execConfig, restrictToWorkspace, mcpServers, channelsConfig
        );
    }

    private int currentMemoryWindow() {
        return runtimeSnapshot().memoryWindow();
    }

    private int currentMaxTokens() {
        return runtimeSnapshot().maxTokens();
    }

    private ConfigSchema.ChannelsConfig currentChannelsConfig() {
        return runtimeSettings.getCurrentConfig().getChannels();
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
                execConfig.getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                execConfig.getPathAppend()
        ));
        sharedTools.register(new WebSearchTool(braveApiKey, null));
        sharedTools.register(new WebFetchTool(null));

        // ========== 多 Agent / subagent 相关 ==========
        agent.subagent.LocalSubagentExecutor localExec = new agent.subagent.LocalSubagentExecutor(
                provider, workspace, execConfig, braveApiKey, restrictToWorkspace,
                subagents.getRegistry(), bus
        );
        agent.subagent.SessionsSpawnTool spawnTool = new agent.subagent.SessionsSpawnTool(
                subagents.getRegistry(),
                subagents.getAnnounceService(),
                subagents.getPromptBuilder(),
                localExec,
                workspace,
                bus
        );
        sharedTools.register(spawnTool);

        agent.subagent.SubagentsTool subagentsTool = new agent.subagent.SubagentsTool(
                subagents.getRegistry(),
                subagents.getController()
        );
        sharedTools.register(subagentsTool);

        // 技能工具
        SkillsLoader skillsLoader = new SkillsLoader(workspace);
        sharedTools.register(new LoadSkillTool(skillsLoader));
        sharedTools.register(new UninstallSkillTool(skillsLoader));

        // 记忆搜索工具
        sharedTools.register(new MemorySearchTool(workspace));

        // 记忆读取工具
        sharedTools.register(new MemoryGetTool(workspace));

        // 注意：
        // MessageTool / CronTool 不在这里注册，改为“每请求单独创建”
    }
    /**
     * 构建当前请求的工具视图：
     * - sharedTools：全局共享、无上下文工具
     * - localTools：当前请求独有、有上下文工具
     */
    private ToolView buildRequestToolsAndSetContext(String channel, String chatId, String messageId) {
        ToolRegistry localTools = new ToolRegistry();

        // 每次请求独立创建 MessageTool，避免串会话
        localTools.register(new MessageTool(bus::publishOutbound, channel, chatId, messageId));

        // CronTool 带 channel/chatId 上下文，也做成每请求独立
        if (cronService != null) {
            CronTool cronTool = new CronTool(cronService);
            cronTool.setContext(channel, chatId);
            localTools.register(cronTool);
        }

        // 如果你后续有旧版 SpawnTool 需要带上下文，也在这里按请求新建
        // var spawn = new SpawnTool(subagents);
        // spawn.setContext(channel, chatId);
        // localTools.register(spawn);

        return new CompositeToolView(sharedTools, localTools);
    }

    public CronTool getCronTool() {
        return cronToolFacade;
    }

    private CompletionStage<Void> connectMcp() {
        if (mcpConnected || mcpConnecting || mcpServers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        mcpConnecting = true;
        return CompletableFuture.runAsync(() -> {
            try {
                for (var entry : mcpServers.entrySet()) {
                    String name = entry.getKey();
                    ConfigSchema.MCPServerConfig cfg = entry.getValue();

                    MCPClient client = new MCPClient(name, cfg, sharedTools);
                    client.connect().toCompletableFuture().join();
                    mcpClients.add(client);
                }
                mcpConnected = true;
                log.info("MCP 服务器已连接: {}", mcpClients.size());
            } catch (Exception e) {
                log.warn("连接 MCP 服务器失败: {}", e.toString());
                mcpConnected = false;
            } finally {
                mcpConnecting = false;
            }
        }, executor);
    }

    public CompletionStage<Void> closeMcp() {
        return CompletableFuture.runAsync(() -> {
            for (MCPClient client : mcpClients) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("关闭 MCP 客户端失败: {}", e.toString());
                }
            }
            mcpClients.clear();
            mcpConnected = false;
            mcpConnecting = false;
        }, executor);
    }

    private static String stripThink(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = THINK_BLOCK.matcher(text).replaceAll("").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String toolHint(List<ToolCallRequest> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var tc : toolCalls) {
            Object val = (tc.getArguments() != null && !tc.getArguments().isEmpty())
                    ? tc.getArguments().values().iterator().next()
                    : null;
            if (!(val instanceof String s)) {
                parts.add(tc.getName());
            } else {
                parts.add(s.length() > 40
                        ? tc.getName() + "(\"" + s.substring(0, 40) + "…\")"
                        : tc.getName() + "(\"" + s + "\")");
            }
        }
        return String.join(", ", parts);
    }

    public CompletableFuture<Void> run() {
        running = true;
        connectMcp();
        log.info("Agent 循环已启动");

        while (running) {
            InboundMessage msg = null;
            try {
                msg = bus.consumeInbound(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            if (msg == null) continue;

            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            if ("/stop".equalsIgnoreCase(content)) {
                handleStop(msg).toCompletableFuture().join();
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

    private CompletionStage<Void> handleStop(InboundMessage msg) {
        String sessionKey = msg.getSessionKey();
        List<CompletableFuture<?>> tasks = activeTasks.remove(sessionKey);
        int cancelled = 0;

        if (tasks != null) {
            for (CompletableFuture<?> f : tasks) {
                if (f != null && !f.isDone() && f.cancel(true)) {
                    cancelled++;
                }
            }
            for (CompletableFuture<?> f : tasks) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
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
                        log.warn("处理会话 {} 的消息时出错: {}", msg.getSessionKey(), e.toString());
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
            String chat = msg.getChatId();
            String channel = (chat != null && chat.contains(":")) ? chat.split(":", 2)[0] : "cli";
            String chatId = (chat != null && chat.contains(":")) ? chat.split(":", 2)[1] : chat;
            String key = channel + ":" + chatId;

            ToolView requestTools = buildRequestToolsAndSetContext(channel, chatId, extractMessageId(msg.getMetadata()));

            Session session = sessions.getOrCreate(key);
            List<Map<String, Object>> history = session.getHistory(currentMemoryWindow());
            List<Map<String, Object>> initial = context.buildMessages(
                    history, msg.getContent(), null, null, channel, chatId
            );

            return runAgentLoop(key, initial, requestTools, null).thenApply(rr -> {
                saveTurn(session, rr.messages, 1 + history.size());
                sessions.save(session);
                return new OutboundMessage(
                        channel,
                        chatId,
                        rr.finalContent != null ? rr.finalContent : "后台任务已完成。",
                        List.of(),
                        Map.of()
                );
            });
        }

        String key = (sessionKeyOverride != null) ? sessionKeyOverride : msg.getSessionKey();
        Session session = sessions.getOrCreate(key);
        String cmd = msg.getContent() == null ? "" : msg.getContent().trim().toLowerCase(Locale.ROOT);

        if ("/new".equals(cmd)) {
            return handleNewCommand(msg, session);
        }

        if ("/help".equals(cmd)) {
            return CompletableFuture.completedFuture(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "🐈 javaclawbot 命令:\n/new — 开始新对话\n/stop — 停止当前任务\n/help — 显示可用命令",
                    List.of(),
                    Map.of()
            ));
        }

        int useMemoryWindow = currentMemoryWindow();
        int unconsolidated = session.getMessages().size() - session.getLastConsolidated();
        if (unconsolidated >= useMemoryWindow && !consolidating.contains(session.getKey())) {
            consolidating.add(session.getKey());
            ReentrantLock lock = getConsolidationLock(session.getKey());
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    lock.lock();
                    consolidateMemory(session, false).toCompletableFuture().join();
                } finally {
                    consolidating.remove(session.getKey());
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                    pruneConsolidationLock(session.getKey(), lock);
                }
            }, executor);
            consolidationTasks.add(f);
            f.whenComplete((v, ex) -> consolidationTasks.remove(f));
        }

        ToolView requestTools = buildRequestToolsAndSetContext(
                msg.getChannel(),
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

        return runAgentLoop(key, initialMessages, requestTools, progress).thenApply(rr -> {
            String finalContent = rr.finalContent != null
                    ? rr.finalContent
                    : "处理完成但没有响应内容。";

            saveTurn(session, rr.messages, 1 + history.size());
            sessions.save(session);

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

    private CompletionStage<OutboundMessage> handleNewCommand(InboundMessage msg, Session session) {
        ReentrantLock lock = getConsolidationLock(session.getKey());
        consolidating.add(session.getKey());
        CompletableFuture<OutboundMessage> out = new CompletableFuture<>();

        executor.execute(() -> {
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
            String sessionKey,
            List<Map<String, Object>> initialMessages,
            ToolView tools,
            ProgressCallback onProgress
    ) {
        CompletableFuture<RunResult> out = new CompletableFuture<>();
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);
        List<String> toolsUsed = new ArrayList<>();

        // 使用 usageTrackers 跟踪每个会话的 usage（对齐 OpenClaw）
        UsageAccumulator usageAcc = usageTrackers.computeIfAbsent(sessionKey, k -> new UsageAccumulator());

        ContextPruningSettings pruningSettings = ContextPruningSettings.DEFAULT;
        int contextWindow = ContextWindowDiscovery.resolveContextTokensForModel(
                null, model, null, currentMaxTokens(), runtimeSettings.getCurrentConfig());

        final int maxOverflowCompactionAttempts = 3;
        int[] overflowCompactionAttempts = {0};

        class State {
            int iteration = 0;
            String finalContent = null;
            final AtomicBoolean done = new AtomicBoolean(false);
        }
        State st = new State();

        // 每次对花钱最后2个必定是用户消息
        Map<String, Object> userMsg1 = initialMessages.get(initialMessages.size() - 1);
        Map<String, Object> userMsg2 = initialMessages.get(initialMessages.size() - 2);
        // 添加用户消息至历史的memory 形式为 YYYY-mm-dd.md
        String msg = getContextFromMap(userMsg1, userMsg2);
        memoryStore.appendToToday("用户: " + msg);
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (st.done.get()) {
                    return;
                }

                String appendToHisMemory;

                AgentRuntimeSettings.Snapshot rs = runtimeSnapshot();
                st.iteration++;
                if (st.iteration > rs.maxIterations()) {
                    st.finalContent =
                            "错误，已达到最大工具调用迭代次数 (" + rs.maxIterations() + ")，任务未完成。请尝试将任务拆分为更小的步骤。";
                    st.done.set(true);
                    appendToHisMemory = "系统错误：" + st.finalContent;
                    // 添加至记忆
                    memoryStore.appendToToday(appendToHisMemory);

                    out.complete(new RunResult(st.finalContent, toolsUsed, messages, usageAcc.getTotal()));
                    return;
                }

                List<Map<String, Object>> prunedMessages = ContextPruner.pruneContextMessages(
                        messages, pruningSettings, contextWindow, null
                );
                if (prunedMessages != messages) {
                    int beforeChars = ContextPruner.estimateContextChars(messages);
                    int afterChars = ContextPruner.estimateContextChars(prunedMessages);
                    log.debug("上下文已修剪: {} 字符 -> {} 字符", beforeChars, afterChars);
                    messages.clear();
                    messages.addAll(prunedMessages);
                }

                provider.chatWithRetry(
                        messages,
                        tools.getDefinitions(),
                        rs.model(),
                        rs.maxTokens(),
                        rs.temperature(),
                        rs.reasoningEffort()
                ).whenComplete((resp, ex) -> {
                    if (st.done.get()) {
                        return;
                    }

                    if (ex != null) {
                        String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();

                        if (ContextOverflowDetector.isLikelyContextOverflowError(errorMsg)) {
                            log.warn("检测到上下文溢出: {}", errorMsg);

                            if (overflowCompactionAttempts[0] < maxOverflowCompactionAttempts) {
                                overflowCompactionAttempts[0]++;
                                log.info("尝试自动压缩 (第 {}/{} 次)",
                                        overflowCompactionAttempts[0], maxOverflowCompactionAttempts);

                                compactMessages(messages).thenAccept(compacted -> {
                                    if (compacted) {
                                        log.info("自动压缩成功，正在重试...");
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
                        // 添加至记忆
                        memoryStore.appendToToday("系统错误：" + errorMsg);
                        out.completeExceptionally(ex);
                        return;
                    }

                    usageAcc.accumulate(resp);

                    if (resp.hasToolCalls()) {
                        if (onProgress != null) {
                            String clean = stripThink(resp.getContent());
                            if (clean != null) onProgress.onProgress(clean, false);
                            onProgress.onProgress(toolHint(resp.getToolCalls()), true);
                        }

                        List<Map<String, Object>> toolCallDicts = new ArrayList<>();
                        for (var tc : resp.getToolCalls()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", tc.getName());
                            fn.put("arguments", JsonUtil.toJson(tc.getArguments()));

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

                        executeToolCallsSequential(resp.getToolCalls(), toolsUsed, messages, tools)
                                .whenComplete((v, ex2) -> {
                                    if (st.done.get()) {
                                        return;
                                    }
                                    if (ex2 != null) {
                                        st.done.set(true);
                                        memoryStore.appendToToday("工具调用异常：" + ex2.getMessage());
                                        out.completeExceptionally(ex2);
                                    } else {
                                        executor.execute(this);
                                    }
                                });
                        return;
                    }

                    // 如果未引导
                    if (!context.isBootstrap()) {
                        // 设置为已引导
                        context.getBootstrapConfig().setIsBootstrap(1);
                        try {
                            ConfigIO.saveConfig(runtimeSettings.getCurrentConfig(), ConfigIO.getConfigPath(workspace));
                        } catch (Exception e) {
                            log.error("修改引导程序异常！", e);
                        }
                    }

                    log.info("思考: \n{}", resp.getReasoningContent());
                    String clean = stripThink(resp.getContent());

                    // 添加至每日记忆
                    memoryStore.appendToToday("助手：" + "<思考>" + resp.getReasoningContent() + "</思考>" + "\t" + clean);

                    log.info("LLM 回复:\n{}", clean);

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

    private static String getContextFromMap(Map<String, Object> ... userMsgs) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> userMsg : userMsgs) {
            if (!userMsg.get("role").equals("user")) {
                continue;
            }
            Object content = userMsg.get("content");
            String msg;
            if (content instanceof String str) {
                msg = str;
            }else {
                try {
                    msg = new Gson().toJson(content);
                }catch (Exception e) {
                    msg = content.toString();
                }
            }
            sb.append(msg).append("\n");
        }
        return sb.toString();
    }

    private CompletionStage<Void> executeToolCallsSequential(
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> messages,
            ToolView tools
    ) {
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);

        for (var tc : toolCalls) {
            chain = chain.thenCompose(v -> {
                toolsUsed.add(tc.getName());
                try {
                    log.info("工具调用: {}({})", tc.getName(), safeTruncate(JsonUtil.toJson(tc.getArguments()), 200));
                } catch (Exception ignored) {
                }

                return tools.execute(tc.getName(), tc.getArguments())
                        .thenAccept(result -> {
                            memoryStore.appendToToday("助手：工具调用：" + new Gson().toJson(tc) + "，结果：" + result);
                            List<Map<String, Object>> updated = context.addToolResult(messages, tc.getId(), tc.getName(), result);
                            messages.clear();
                            messages.addAll(updated);
                }).exceptionally(ex -> {
                    String err = formatToolError(tc.getName(), ex);
                    List<Map<String, Object>> updated = context.addToolResult(messages, tc.getId(), tc.getName(), err);
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

    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        for (int i = skip; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (!"reasoning_content".equals(e.getKey())) {
                    entry.put(e.getKey(), e.getValue());
                }
            }

            Object role = entry.get("role");
            Object content = entry.get("content");

            if ("assistant".equals(String.valueOf(role))) {
                Object toolCalls = entry.get("tool_calls");
                boolean emptyContent = (content == null) || (content instanceof String s && s.isBlank());
                boolean noToolCalls = (toolCalls == null) || (toolCalls instanceof List<?> l && l.isEmpty());
                if (emptyContent && noToolCalls) continue;
            }

            if ("tool".equals(String.valueOf(role)) && content instanceof String s && s.length() > TOOL_RESULT_MAX_CHARS) {
                entry.put("content", s.substring(0, TOOL_RESULT_MAX_CHARS) + "\n... (truncated)");
            }

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
    }

    private CompletionStage<Boolean> consolidateMemory(Session session, boolean archiveAll) {
        return memoryStore.consolidate(
                session,
                provider,
                model,
                currentMaxTokens(),
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

                int contextWindow = currentMaxTokens();
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
                    log.info("无需修剪，消息在预算范围内");
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
        }, executor);
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
        prompt.append("请简洁地总结以下对话，保留关键信息、决策和上下文：\n\n");
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

        String summary = provider.chat(
                List.of(Map.of("role", "user", "content", prompt.toString())),
                List.of(),
                model,
                1024,
                0.3,
                null
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

    public ConfigSchema.ChannelsConfig getChannelsConfig() {
        return currentChannelsConfig();
    }

    public String getModel() {
        return runtimeSnapshot().model();
    }

    public interface ProgressCallback {
        void onProgress(String content, boolean toolHint);
    }

    public static class RunResult {
        public final String finalContent;
        public final List<String> toolsUsed;
        public final List<Map<String, Object>> messages;
        public final Usage usage;

        public RunResult(String finalContent, List<String> toolsUsed, List<Map<String, Object>> messages) {
            this.finalContent = finalContent;
            this.toolsUsed = toolsUsed;
            this.messages = messages;
            this.usage = new Usage();
        }

        public RunResult(String finalContent, List<String> toolsUsed, List<Map<String, Object>> messages, Usage usage) {
            this.finalContent = finalContent;
            this.toolsUsed = toolsUsed;
            this.messages = messages;
            this.usage = usage != null ? usage : new Usage();
        }
    }
    /**
     * 对 sharedTools + request-local tools 的组合视图。
     * local 同名工具优先覆盖 shared。
     */
    private interface ToolView {
        List<Map<String, Object>> getDefinitions();
        CompletionStage<String> execute(String name, Map<String, Object> args);
        Object get(String name);
    }

    private static final class CompositeToolView implements ToolView {
        private final ToolRegistry shared;
        private final ToolRegistry local;

        private CompositeToolView(ToolRegistry shared, ToolRegistry local) {
            this.shared = shared;
            this.local = local;
        }

        @Override
        public List<Map<String, Object>> getDefinitions() {
            List<Map<String, Object>> defs = new ArrayList<>();
            defs.addAll(shared.getDefinitions());

            // local 覆盖 shared 中同名工具定义
            Map<String, Integer> sharedIndexes = new LinkedHashMap<>();
            for (int i = 0; i < defs.size(); i++) {
                Object fn = defs.get(i).get("function");
                if (fn instanceof Map<?, ?> fnMap) {
                    Object name = fnMap.get("name");
                    if (name != null) {
                        sharedIndexes.put(String.valueOf(name), i);
                    }
                }
            }

            for (Map<String, Object> localDef : local.getDefinitions()) {
                String localName = null;
                Object fn = localDef.get("function");
                if (fn instanceof Map<?, ?> fnMap) {
                    Object name = fnMap.get("name");
                    if (name != null) {
                        localName = String.valueOf(name);
                    }
                }

                if (localName != null && sharedIndexes.containsKey(localName)) {
                    defs.set(sharedIndexes.get(localName), localDef);
                } else {
                    defs.add(localDef);
                }
            }

            return defs;
        }

        @Override
        public CompletionStage<String> execute(String name, Map<String, Object> args) {
            Object localTool = local.get(name);
            if (localTool != null) {
                return local.execute(name, args);
            }
            return shared.execute(name, args);
        }

        @Override
        public Object get(String name) {
            Object localTool = local.get(name);
            return localTool != null ? localTool : shared.get(name);
        }
    }

    static class JsonUtil {
        private static final com.fasterxml.jackson.databind.ObjectMapper M =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static String toJson(Object o) {
            try {
                return M.writeValueAsString(o);
            } catch (Exception e) {
                return String.valueOf(o);
            }
        }
    }
}