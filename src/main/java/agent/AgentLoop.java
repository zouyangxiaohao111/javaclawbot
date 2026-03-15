package agent;

import agent.tool.*;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import config.AgentRuntimeSettings;
import config.ConfigSchema;
import corn.CronService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.ToolCallRequest;
import session.ContextPruner;
import session.ContextPruningSettings;
import session.Session;
import session.SessionManager;
import skills.SkillsLoader;

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
        this.context = new ContextBuilder(workspace);
        this.sessions = (sessionManager != null) ? sessionManager : new SessionManager(workspace);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("nanobot-agent-" + t.getId());
            return t;
        });
        this.subagents = new SubagentManager(
                provider, workspace, bus, this.model, this.temperature, this.maxTokens,
                this.reasoningEffort, braveApiKey, this.execConfig, restrictToWorkspace, null
        );
        this.mcpServers = (mcpServers != null) ? mcpServers : Map.of();

        int maxConcurrent = 4;
        if (runtimeSettings != null) {
            var cfg = runtimeSettings.getCurrentConfig();
            maxConcurrent = cfg.getAgents().getDefaults().getMaxConcurrent();
        }
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
     * 每次请求构建一份完整 ToolRegistry：
     * - 带上下文的 MessageTool / CronTool 使用请求级实例，避免串会话
     * - 其它工具直接重建，代码更直观
     */
    private ToolRegistry buildRequestToolRegistry(String channel, String chatId, String messageId) {
        ToolRegistry tools = new ToolRegistry();
        java.nio.file.Path allowedDir = restrictToWorkspace ? workspace : null;

        // 文件 / 命令 / 网络工具
        tools.register(new FileSystemTools.ReadFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.WriteFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.EditFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ListDirTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadPptTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadPptStructuredTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadWordTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ReadWordStructuredTool(workspace, allowedDir));
        tools.register(new ExecTool(
                execConfig.getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                execConfig.getPathAppend()
        ));
        tools.register(new WebSearchTool(braveApiKey, null));
        tools.register(new WebFetchTool(null));

        // 每次请求独立 MessageTool，避免串会话
        tools.register(new MessageTool(bus::publishOutbound, channel, chatId, messageId));

        // 每次请求独立 CronTool，复制 facade 的 cron 上下文
        if (cronService != null) {
            CronTool cronTool = new CronTool(cronService);
            cronTool.setContext(channel, chatId);
            if (cronToolFacade != null) {
                cronTool.setCronContext(cronToolFacade.isInCronContext());
            }
            tools.register(cronTool);
        }

        // 多 Agent / subagent 相关
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
        tools.register(spawnTool);

        agent.subagent.SubagentsTool subagentsTool = new agent.subagent.SubagentsTool(
                subagents.getRegistry(),
                subagents.getController()
        );
        tools.register(subagentsTool);

        SkillsLoader skillsLoader = new SkillsLoader(workspace);
        tools.register(new LoadSkillTool(skillsLoader));
        tools.register(new UninstallSkillTool(skillsLoader));

        return tools;
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

                    ToolRegistry mcpTools = buildRequestToolRegistry("system", "mcp", null);
                    MCPClient client = new MCPClient(name, cfg, mcpTools);
                    client.connect().toCompletableFuture().join();
                    mcpClients.add(client);
                }
                mcpConnected = true;
                log.info("MCP servers connected: {}", mcpClients.size());
            } catch (Exception e) {
                log.warn("Failed to connect MCP servers: {}", e.toString());
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
                    log.warn("Failed to close MCP client: {}", e.toString());
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
        log.info("Agent loop started");

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

        log.info("Agent loop stopped");
        return CompletableFuture.completedFuture(null);
    }

    public void stop() {
        running = false;
        if (queue != null) {
            queue.shutdown();
        }
        log.info("Agent loop stopping");
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
                    total > 0 ? "⏹ Stopped " + total + " task(s)." : "No active task to stop.",
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
                        log.warn("Error processing message for session {}: {}", msg.getSessionKey(), e.toString());
                        try {
                            bus.publishOutbound(new OutboundMessage(
                                    msg.getChannel(),
                                    msg.getChatId(),
                                    "Sorry, I encountered an error.",
                                    List.of(),
                                    Map.of()
                            )).toCompletableFuture().join();
                        } catch (Exception publishEx) {
                            log.warn("Failed to publish error outbound for session {}: {}", msg.getSessionKey(), publishEx.toString());
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

            ToolRegistry requestTools = buildRequestToolRegistry(channel, chatId, extractMessageId(msg.getMetadata()));

            Session session = sessions.getOrCreate(key);
            List<Map<String, Object>> history = session.getHistory(currentMemoryWindow());
            List<Map<String, Object>> initial = context.buildMessages(
                    history, msg.getContent(), null, null, channel, chatId
            );

            return runAgentLoop(initial, requestTools, null).thenApply(rr -> {
                saveTurn(session, rr.messages, 1 + history.size());
                sessions.save(session);
                return new OutboundMessage(
                        channel,
                        chatId,
                        rr.finalContent != null ? rr.finalContent : "Background task completed.",
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
                    "🐈 nanobot commands:\n/new — Start a new conversation\n/stop — Stop the current task\n/help — Show available commands",
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

        ToolRegistry requestTools = buildRequestToolRegistry(
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

        return runAgentLoop(initialMessages, requestTools, progress).thenApply(rr -> {
            String finalContent = rr.finalContent != null
                    ? rr.finalContent
                    : "I've completed processing but have no response to give.";

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
                                "Memory archival failed, session not cleared. Please try again.",
                                List.of(),
                                Map.of()
                        ));
                        return;
                    }
                }

                session.clear();
                sessions.save(session);
                sessions.invalidate(session.getKey());

                out.complete(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        "New session started.",
                        List.of(),
                        Map.of()
                ));
            } catch (Exception e) {
                out.complete(new OutboundMessage(
                        msg.getChannel(),
                        msg.getChatId(),
                        "Memory archival failed, session not cleared. Please try again.",
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
            List<Map<String, Object>> initialMessages,
            ToolRegistry tools,
            ProgressCallback onProgress
    ) {
        CompletableFuture<RunResult> out = new CompletableFuture<>();
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);
        List<String> toolsUsed = new ArrayList<>();

        UsageAccumulator usageAcc = new UsageAccumulator();

        ContextPruningSettings pruningSettings = ContextPruningSettings.DEFAULT;
        int contextWindow = ContextWindowDiscovery.resolveContextTokensForModel(
                null, model, null, currentMaxTokens());

        final int maxOverflowCompactionAttempts = 3;
        int[] overflowCompactionAttempts = {0};

        class State {
            int iteration = 0;
            String finalContent = null;
            final AtomicBoolean done = new AtomicBoolean(false);
        }
        State st = new State();

        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (st.done.get()) {
                    return;
                }

                AgentRuntimeSettings.Snapshot rs = runtimeSnapshot();
                st.iteration++;
                if (st.iteration > rs.maxIterations()) {
                    st.finalContent =
                            "I reached the maximum number of tool call iterations (" + rs.maxIterations() + ") without completing the task. You can try breaking the task into smaller steps.";
                    st.done.set(true);
                    out.complete(new RunResult(st.finalContent, toolsUsed, messages, usageAcc.getTotal()));
                    return;
                }

                List<Map<String, Object>> prunedMessages = ContextPruner.pruneContextMessages(
                        messages, pruningSettings, contextWindow, null
                );
                if (prunedMessages != messages) {
                    int beforeChars = ContextPruner.estimateContextChars(messages);
                    int afterChars = ContextPruner.estimateContextChars(prunedMessages);
                    log.debug("Context pruned: {} chars -> {} chars", beforeChars, afterChars);
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
                    if (st.done.get()) return;

                    if (ex != null) {
                        String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();

                        if (ContextOverflowDetector.isLikelyContextOverflowError(errorMsg)) {
                            log.warn("Context overflow detected: {}", errorMsg);

                            if (overflowCompactionAttempts[0] < maxOverflowCompactionAttempts) {
                                overflowCompactionAttempts[0]++;
                                log.info("Attempting auto-compaction (attempt {}/{})",
                                        overflowCompactionAttempts[0], maxOverflowCompactionAttempts);

                                compactMessages(messages).thenAccept(compacted -> {
                                    if (compacted) {
                                        log.info("Auto-compaction succeeded, retrying...");
                                        executor.execute(this);
                                    } else {
                                        log.warn("Auto-compaction failed");
                                        st.done.set(true);
                                        out.complete(new RunResult(
                                                ContextOverflowDetector.formatOverflowError(),
                                                toolsUsed,
                                                messages,
                                                usageAcc.getTotal()
                                        ));
                                    }
                                }).exceptionally(compactEx -> {
                                    log.warn("Auto-compaction error: {}", compactEx.toString());
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
                                    if (st.done.get()) return;
                                    if (ex2 != null) {
                                        st.done.set(true);
                                        out.completeExceptionally(ex2);
                                    } else {
                                        executor.execute(this);
                                    }
                                });
                        return;
                    }

                    log.info("思考: \n{}", resp.getReasoningContent());
                    String clean = stripThink(resp.getContent());
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

    private CompletionStage<Void> executeToolCallsSequential(
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> messages,
            ToolRegistry tools
    ) {
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);

        for (var tc : toolCalls) {
            chain = chain.thenCompose(v -> {
                toolsUsed.add(tc.getName());
                try {
                    log.info("Tool call: {}({})", tc.getName(), safeTruncate(JsonUtil.toJson(tc.getArguments()), 200));
                } catch (Exception ignored) {
                }

                return tools.execute(tc.getName(), tc.getArguments()).thenAccept(result -> {
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
        return new MemoryStore(workspace).consolidate(
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
                    log.warn("Not enough messages to compact (need > 4)");
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
                    log.info("No pruning needed, messages fit within budget");
                    return compactMessagesInternal(messages, contextWindow);
                }

                log.info("Pruned {} chunks ({} messages, {} tokens) to fit history budget",
                        pruneResult.droppedChunks,
                        pruneResult.droppedMessages,
                        pruneResult.droppedTokens
                );

                List<Map<String, Object>> compacted = new ArrayList<>();
                compacted.add(messages.get(0));
                compacted.addAll(pruneResult.messages);

                messages.clear();
                messages.addAll(compacted);

                log.info("Compaction complete: {} messages -> {} messages",
                        historyMessages.size() + 1,
                        compacted.size()
                );

                return true;
            } catch (Exception e) {
                log.error("Compaction failed: {}", e.toString());
                return false;
            }
        }, executor);
    }

    private boolean compactMessagesInternal(List<Map<String, Object>> messages, int contextWindow) {
        int keepFirst = 1;
        int keepLast = 2;

        if (messages.size() <= keepFirst + keepLast) {
            log.warn("Messages too short to compact");
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
                        "[Large %s (~%dK tokens) omitted from summary]",
                        roleStr,
                        tokens / 1000
                ));
            } else {
                smallMessages.add(msg);
            }
        }

        if (smallMessages.isEmpty()) {
            log.warn("All messages are oversized, cannot compact");
            return false;
        }

        if (smallMessages.size() < toCompact.size()) {
            log.info("Skipping {} oversized messages", toCompact.size() - smallMessages.size());
            toCompact = smallMessages;
        }

        log.info("Compacting {} messages (keeping first {} and last {})",
                toCompact.size(), keepFirst, keepLast);

        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarize the following conversation concisely, preserving key information, decisions, and context:\n\n");
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
            log.warn("Compaction returned empty summary");
            return false;
        }

        List<Map<String, Object>> compacted = new ArrayList<>();
        compacted.add(messages.get(0));

        Map<String, Object> summaryMsg = new LinkedHashMap<>();
        summaryMsg.put("role", "user");
        summaryMsg.put("content", "[Previous conversation summary]\n" + summary);
        compacted.add(summaryMsg);

        for (int i = toIndex; i < messages.size(); i++) {
            compacted.add(messages.get(i));
        }

        messages.clear();
        messages.addAll(compacted);

        log.info("Compaction complete: {} messages -> {} messages",
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