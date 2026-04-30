package gui.ui;

import agent.AgentLoop;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import cli.RuntimeComponents;
import config.Config;
import config.ConfigIO;
import config.ConfigReloader;
import config.channel.ChannelsConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import config.mcp.MCPServerConfig;
import corn.CronService;
import javafx.application.Platform;
import providers.CustomProvider;
import providers.LLMProvider;
import providers.ProviderRegistry;
import providers.cli.ProjectRegistry;
import session.Session;
import session.SessionManager;
import skills.SkillsLoader;
import utils.Helpers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * BackendBridge — JavaFX GUI 与 javaclawbot 后端的桥接层。
 *
 * 职责：
 * 1. 初始化 Config / SessionManager / LLMProvider / MessageBus / AgentLoop / CronService
 * 2. 启动 bus adapter（busTask + outboundTask）
 * 3. 提供异步消息收发接口（Platform.runLater 回调）
 * 4. 提供各页面所需的后端组件 getter
 */
public class BackendBridge {

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(BackendBridge.class.getName());

    /** 进度事件：区分思考内容、工具调用、工具结果 */
    public record ProgressEvent(String content, boolean isToolHint,
                                boolean isToolResult, String toolName, String toolCallId,
                                boolean isReasoning) {
        public ProgressEvent(String content, boolean isToolHint) {
            this(content, isToolHint, false, null, null, false);
        }
    }

    // ── 后端组件 ──
    private Config config;
    private SessionManager sessionManager;
    private LLMProvider provider;
    private MessageBus bus;
    private AgentLoop agentLoop;
    private CronService cron;
    private SkillsLoader skillsLoader;
    private ProjectRegistry projectRegistry;

    // ── Bus 模式 ──
    private final AtomicBoolean busLoopRunning = new AtomicBoolean(false);
    private CompletableFuture<Void> busTask;
    private CompletableFuture<Void> outboundTask;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "javaclawbot-fx-bridge");
        t.setDaemon(true);
        return t;
    });

    // ── 会话 ──
    private static final String CLI_CHANNEL = "cli";
    private static final String CLI_CHAT_ID = "direct";
    private final String sessionKey = CLI_CHANNEL + ":" + CLI_CHAT_ID;

    // ── 当前消息回调（一次只处理一条消息）──
    private final AtomicReference<Consumer<ProgressEvent>> currentProgressCallback = new AtomicReference<>();
    private final AtomicReference<Consumer<String>> currentResponseCallback = new AtomicReference<>();
    private volatile boolean waitingForResponse = false;
    /** 最近一次回复的推理内容 */
    private volatile String lastReasoningContent;

    // ── 标题生成计数器 ──
    private final AtomicBoolean titleGenerationPending = new AtomicBoolean(false);
    private final AtomicBoolean titleRegenerationPending = new AtomicBoolean(false);
    private int userMessageCount = 0;

    /** 标题生成/更新后回调（MainStage 设置用于刷新侧栏） */
    private volatile Runnable onTitleChanged;

    /**
     * 初始化所有后端组件（阻塞调用，需在后台线程执行）。
     */
    public void initialize() {
        // 1) 加载配置
        RuntimeComponents rt = ConfigReloader.createRuntimeComponents();
        this.config = rt.getConfig();

        // 2) SessionManager
        this.sessionManager = new SessionManager(this.config.getWorkspacePath());

        // 3) LLMProvider
        this.provider = makeProvider(this.config);

        // 4) CronService
        Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
        this.cron = new CronService(cronStorePath, null);

        // 5) ProjectRegistry（按 sessionId 隔离，避免上一轮绑定的项目遗留到本轮）
        String sessionId = getCurrentSession() != null ? getCurrentSession().getSessionId() : null;
        if (sessionId == null) {
            sessionId = Session.generateSessionId();
        }
        this.projectRegistry = createProjectRegistry(sessionId);

        // 6) MessageBus
        this.bus = new MessageBus();

        // 7) AgentLoop
        this.agentLoop = new AgentLoop(
                this.bus,
                this.provider,
                this.config.getWorkspacePath(),
                this.config.getAgents().getDefaults().getModel(),
                this.config.getAgents().getDefaults().getMaxToolIterations(),
                this.config.obtainTemperature(this.provider.getDefaultModel()),
                this.config.obtainMaxTokens(this.provider.getDefaultModel()),
                this.config.obtainContextWindow(this.provider.getDefaultModel()),
                this.config.getAgents().getDefaults().getMemoryWindow(),
                this.config.getAgents().getDefaults().getReasoningEffort(),
                this.cron,
                this.config.getTools().isRestrictToWorkspace(),
                this.sessionManager,
                this.config.getTools().getMcpServers(),
                this.config.getChannels(),
                rt.getRuntimeSettings(),
                this.projectRegistry
        );

        // 8) SkillsLoader
        this.skillsLoader = new SkillsLoader(this.config.getWorkspacePath());

        // 9) 启动 bus 交互模式
        startBusInteractiveMode();

        // 10) 恢复 plan mode 状态
        try {
            Session session = sessionManager.getOrCreate(sessionKey);
            agentLoop.ensurePlanModeState(sessionKey, session);
        } catch (Exception ignored) {
        }
    }

    /**
     * 启动 bus 适配器（busTask + outboundTask）
     */
    private void startBusInteractiveMode() {
        if (busLoopRunning.get()) return;
        busLoopRunning.set(true);

        // busTask: 运行 AgentLoop 消费 inbound
        busTask = CompletableFuture.runAsync(() -> {
            try {
                agentLoop.run();
            } catch (Exception e) {
                Platform.runLater(() -> System.err.println("AgentLoop 异常: " + e.getMessage()));
            }
        }, executor);

        // outboundTask: 轮询 outbound 并回调 JavaFX UI
        outboundTask = CompletableFuture.runAsync(() -> {
            while (busLoopRunning.get()) {
                try {
                    OutboundMessage out = bus.consumeOutbound(1, TimeUnit.SECONDS);
                    if (out == null) continue;

                    // 过滤非本会话消息
                    if (!isTargetCliOutbound(out)) continue;

                    Map<String, Object> meta = out.getMetadata() != null ? out.getMetadata() : Map.of();
                    boolean isProgress = Boolean.TRUE.equals(meta.get("_progress"));
                    boolean isToolHint = Boolean.TRUE.equals(meta.get("_tool_hint"));
                    boolean isToolResult = Boolean.TRUE.equals(meta.get("_tool_result"));
                    boolean isReasoning = Boolean.TRUE.equals(meta.get("_reasoning"));
                    String toolName = meta.get("tool_name") instanceof String s ? s : null;
                    String toolCallId = meta.get("tool_call_id") instanceof String s ? s : null;

                    if (isProgress) {
                        String content = out.getContent() != null ? out.getContent() : "";
                        Consumer<ProgressEvent> cb = currentProgressCallback.get();
                        if (cb != null) {
                            Platform.runLater(() -> cb.accept(
                                new ProgressEvent(content, isToolHint, isToolResult, toolName, toolCallId, isReasoning)));
                        }
                    } else {
                        // 最终回复
                        String content = out.getContent() != null ? out.getContent() : "";
                        // 提取推理内容
                        Object rcObj = meta.get("_reasoning_content");
                        if (rcObj instanceof String s && !s.isBlank()) {
                            lastReasoningContent = s;
                        } else {
                            lastReasoningContent = null;
                        }
                        Consumer<String> cb = currentResponseCallback.getAndSet(null);
                        waitingForResponse = false;

                        // 标题生成：回复完成后触发，确保 session 已包含本轮完整对话
                        if (userMessageCount >= 1 && titleGenerationPending.compareAndSet(false, true)) {
                            triggerTitleGeneration(false);
                        }
                        if (userMessageCount >= 3 && titleRegenerationPending.compareAndSet(false, true)) {
                            triggerTitleGeneration(true);
                        }

                        Platform.runLater(() -> {
                            if (cb != null) {
                                cb.accept(content);
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, executor);
    }

    /**
     * 判断 outbound 消息是否属于当前 CLI 会话
     */
    private boolean isTargetCliOutbound(OutboundMessage out) {
        try {
            String ch = out.getChannel();
            String cid = out.getChatId();
            return CLI_CHANNEL.equals(ch) && CLI_CHAT_ID.equals(cid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 异步发送消息。
     *
     * @param text         用户输入文本
     * @param onProgress   进度回调（工具调用、中间步骤），在 JavaFX 线程中执行
     * @param onResponse   最终回复回调，在 JavaFX 线程中执行
     * @param onError      错误回调，在 JavaFX 线程中执行
     */
    public void sendMessage(String text,
                            Consumer<ProgressEvent> onProgress,
                            Consumer<String> onResponse,
                            Consumer<String> onError) {
        sendMessage(text, null, onProgress, onResponse, onError);
    }

    public void sendMessage(String text,
                            List<String> mediaPaths,
                            Consumer<ProgressEvent> onProgress,
                            Consumer<String> onResponse,
                            Consumer<String> onError) {
        if (text == null || text.isBlank()) return;
        if (bus == null || agentLoop == null) {
            if (onError != null) Platform.runLater(() -> onError.accept("bus 或 agentLoop 未初始化"));
            return;
        }

        currentProgressCallback.set(onProgress);
        currentResponseCallback.set(onResponse);
        waitingForResponse = true;

        CompletableFuture.runAsync(() -> {
            try {
                InboundMessage in = new InboundMessage(
                        CLI_CHANNEL, "user", CLI_CHAT_ID, text, mediaPaths, null);
                bus.publishInbound(in).toCompletableFuture().join();
            } catch (Exception e) {
                waitingForResponse = false;
                currentResponseCallback.set(null);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e.getMessage()));
                }
            }
        }, executor);

        // 标题生成计数器（实际触发在收到回复后，确保 session 已包含本轮对话）
        userMessageCount++;
    }

    /**
     * 提交 AskUserQuestion 的用户答案，由 UI 在弹窗确认后调用。
     */
    public void answerUserQuestion(String toolCallId, java.util.Map<String, String> answers) {
        if (agentLoop != null) {
            agentLoop.answerUserQuestion(toolCallId, answers);
        }
    }

    /**
     * 发送 /stop 命令
     */
    public void stopMessage() {
        if (!waitingForResponse) return;

        CompletableFuture.runAsync(() -> {
            try {
                InboundMessage stopMsg = new InboundMessage(
                        CLI_CHANNEL, "user", CLI_CHAT_ID, "/stop", null, null);
                bus.publishInbound(stopMsg).toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }, executor);
    }

    public boolean isReady() {
        return sessionManager != null && bus != null && agentLoop != null;
    }

    /**
     * 获取当前会话
     */
    public Session getCurrentSession() {
        if (sessionManager == null) return null;
        return sessionManager.getOrCreate(sessionKey);
    }

    /**
     * 确保当前为全新会话（用于欢迎页启动），不通过 bus 发送 /clear，直接操作 SessionManager。
     */
    public void ensureFreshSession() {
        if (sessionManager == null) return;
        userMessageCount = 0;
        titleGenerationPending.set(false);
        titleRegenerationPending.set(false);

        Session newSession = sessionManager.createNew(sessionKey);
        ProjectRegistry newRegistry = createProjectRegistry(newSession.getSessionId());
        this.projectRegistry = newRegistry;
        if (agentLoop != null) {
            agentLoop.updateProjectRegistry(newRegistry);
        }
    }

    /**
     * 创建新会话：发送 /clear 命令让 AgentLoop 重置上下文
     */
    public Session newSession() {
        if (sessionManager == null || bus == null) return null;
        userMessageCount = 0;
        titleGenerationPending.set(false);
        titleRegenerationPending.set(false);

        Session newSession = sessionManager.createNew(sessionKey);

        // 为新会话创建独立的 ProjectRegistry，避免上一轮绑定遗留
        ProjectRegistry newRegistry = createProjectRegistry(newSession.getSessionId());
        this.projectRegistry = newRegistry;
        if (agentLoop != null) {
            agentLoop.updateProjectRegistry(newRegistry);
        }

        // 发送 /clear 命令，让 AgentLoop 同时重置 session 和内部状态
        CompletableFuture.runAsync(() -> {
            try {
                InboundMessage clearMsg = new InboundMessage(
                        CLI_CHANNEL, "user", CLI_CHAT_ID, "/clear", null, null);
                bus.publishInbound(clearMsg).toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }, executor);
        return newSession;
    }

    /**
     * 恢复到指定会话
     */
    public void resumeSession(String sessionId) {
        if (sessionManager == null) return;
        userMessageCount = 0;
        titleGenerationPending.set(false);
        titleRegenerationPending.set(false);
        sessionManager.resumeSession(sessionKey, sessionId);
        // 清除缓存，强制下次 getOrCreate 从磁盘加载
        sessionManager.evictFromCache(sessionKey);
    }

    /**
     * 获取会话历史消息（直接从磁盘加载，不经缓存）
     */
    public List<Map<String, Object>> getSessionHistory(String sessionId) {
        if (sessionManager == null) return List.of();
        sessionManager.resumeSession(sessionKey, sessionId);
        sessionManager.evictFromCache(sessionKey);
        Session session = sessionManager.getOrCreate(sessionKey);
        return session.getHistory();
    }

    /**
     * 异步生成/更新会话标题
     * @param force 为 true 时即使已有标题也重新生成（对话深入后更新）
     */
    private void triggerTitleGeneration(boolean force) {
        if (provider == null || sessionManager == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                Session session = getCurrentSession();
                if (session == null) return;
                String sessionId = session.getSessionId();
                log.info("开始生成标题: sessionId=" + sessionId + ", force=" + force);
                String title = TitleGenerator.generateTitle(provider, session, force);
                if (title != null && !title.isBlank()) {
                    sessionManager.save(session);
                    log.info("标题生成成功: sessionId=" + sessionId + ", title=" + title);
                } else if (!force) {
                    // 首次生成失败，使用默认标题
                    String defaultTitle = "新对话-" + java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd"));
                    session.getMetadata().put("title", defaultTitle);
                    sessionManager.save(session);
                    log.info("标题生成失败，使用默认标题: sessionId=" + sessionId + ", title=" + defaultTitle);
                } else {
                    log.info("标题更新跳过（已有标题或生成失败）: sessionId=" + sessionId);
                }
            } catch (Exception e) {
                log.warning("标题生成异常: " + e.getMessage());
            }
            // 通知 UI 刷新侧栏标题
            if (onTitleChanged != null) {
                Platform.runLater(onTitleChanged);
            }
        }, executor);
    }

    public void setOnTitleChanged(Runnable callback) {
        this.onTitleChanged = callback;
    }

    /**
     * 从磁盘重新加载配置（解决 GUI 页面缓存问题）
     */
    public void reloadConfigFromDisk() {
        try {
            this.config = ConfigIO.loadConfig(null);
            log.fine("配置已从磁盘重新加载");
        } catch (Exception e) {
            log.warning("重新加载配置失败: " + e.getMessage());
        }
    }

    /**
     * 通过表单模式添加 MCP 服务器
     */
    public boolean addMcpServer(String name, String command) {
        if (config.getTools().getMcpServers().containsKey(name)) {
            throw new IllegalArgumentException("服务器名称已存在: " + name);
        }
        MCPServerConfig cfg = new MCPServerConfig();
        cfg.setCommand(command);
        config.getTools().getMcpServers().put(name, cfg);
        try {
            ConfigIO.saveConfig(config, null);
            return true;
        } catch (IOException e) {
            config.getTools().getMcpServers().remove(name);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过 RAW JSON 模式添加 MCP 服务器
     */
    public boolean addMcpServerRaw(String name, String jsonStr) {
        if (config.getTools().getMcpServers().containsKey(name)) {
            throw new IllegalArgumentException("服务器名称已存在: " + name);
        }
        // 使用与 ConfigIO 一致的 ObjectMapper 配置（SNAKE_CASE）
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        MCPServerConfig cfg;
        try {
            cfg = mapper.readValue(jsonStr, MCPServerConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }

        // 验证：command 或 url 至少一个非空
        boolean hasCommand = cfg.getCommand() != null && !cfg.getCommand().isBlank();
        boolean hasUrl = cfg.getUrl() != null && !cfg.getUrl().isBlank();
        if (!hasCommand && !hasUrl) {
            throw new IllegalArgumentException("command 或 url 至少需要配置一个");
        }

        config.getTools().getMcpServers().put(name, cfg);
        try {
            ConfigIO.saveConfig(config, null);
            return true;
        } catch (IOException e) {
            config.getTools().getMcpServers().remove(name);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除指定 sessionId 的会话
     */
    public boolean deleteSession(String sessionId) {
        if (sessionManager == null) return false;
        return sessionManager.deleteSession(sessionId);
    }

    /**
     * 重置标题生成计数器（切换会话时调用）
     */
    public void resetTitleCounter() {
        userMessageCount = 0;
        titleGenerationPending.set(false);
        titleRegenerationPending.set(false);
    }

    /**
     * 热刷新 LLMProvider 和模型配置（模型/API Key 变更时调用）
     */
    public void refreshProvider() {
        String defaultModel = this.config.getAgents().getDefaults().getModel();
        LLMProvider newProvider = makeProvider(this.config);
        this.provider = newProvider;
        if (this.agentLoop != null) {
            this.agentLoop.updateProvider(newProvider);
            this.agentLoop.updateModelConfig(
                defaultModel,
                this.config.obtainMaxTokens(defaultModel),
                this.config.obtainContextWindow(defaultModel),
                this.config.obtainTemperature(defaultModel),
                this.config.getAgents().getDefaults().getReasoningEffort()
            );
        }
    }

    // ── Getters ──

    public Config getConfig() {
        return config;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public LLMProvider getProvider() {
        return provider;
    }

    public AgentLoop getAgentLoop() {
        return agentLoop;
    }

    public CronService getCronService() {
        return cron;
    }

    public SkillsLoader getSkillsLoader() {
        return skillsLoader;
    }

    public ProjectRegistry getProjectRegistry() {
        return projectRegistry;
    }

    /**
     * 返回当前绑定的项目目录（用于 @file 提示），
     * 优先主项目路径 → 其次工作区。
     */
    public Path getProjectDir() {
        if (projectRegistry != null) {
            String mainPath = projectRegistry.getMainProjectPath();
            if (mainPath != null && !mainPath.isBlank()) {
                Path p = Path.of(mainPath);
                if (java.nio.file.Files.exists(p)) return p;
            }
        }
        return config != null ? config.getWorkspacePath() : Path.of(System.getProperty("user.dir"));
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public boolean isWaitingForResponse() {
        return waitingForResponse;
    }

    /** 获取最近一次回复的推理内容（可能为 null） */
    public String getLastReasoningContent() {
        return lastReasoningContent;
    }

    // ── 资源清理 ──

    /** 非阻塞停止所有循环（供窗口关闭调用，设置标志后由 System.exit 兜底） */
    public void stopAllLoops() {
        busLoopRunning.set(false);
        if (agentLoop != null) {
            try {
                agentLoop.stop();
            } catch (Exception ignored) {}
        }
        if (cron != null) {
            try { cron.stop(); } catch (Exception ignored) {}
        }
        executor.shutdown();
    }

    public void shutdown() {
        busLoopRunning.set(false);

        if (outboundTask != null) outboundTask.cancel(true);
        if (busTask != null) busTask.cancel(true);

        if (agentLoop != null) {
            try { agentLoop.stop(); } catch (Exception ignored) {}
            try { agentLoop.closeMcp().toCompletableFuture().join(); } catch (Exception ignored) {}
        }

        if (cron != null) {
            try { cron.stop(); } catch (Exception ignored) {}
        }

        executor.shutdown();
        try { executor.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    // ── Private helpers ──

    /**
     * 创建按 sessionId 隔离的 ProjectRegistry
     */
    private ProjectRegistry createProjectRegistry(String sessionId) {
        Path projectStorePath = Helpers.getDataPath()
                .resolve("projects")
                .resolve(sessionId)
                .resolve("projects.json");
        ProjectRegistry registry = new ProjectRegistry(projectStorePath);
        registry.load();
        // 自动绑定当前工作目录为主项目
        String cwd = System.getProperty("user.dir");
        if (cwd != null && !cwd.isBlank() && registry.getMainProject() == null) {
            registry.bind("main", cwd, true);
        }
        return registry;
    }

    private static LLMProvider makeProvider(Config config) {
        String model = config.getAgents().getDefaults().getModel();
        String providerName = config.getProviderName(model);
        var p = config.getProvider(model);

        if ("openai_codex".equals(providerName) || (model != null && model.startsWith("openai-codex/"))) {
            throw new RuntimeException("Error: OpenAI Codex is not supported in this Java build.");
        }

        String apiKey = (p != null && p.getApiKey() != null) ? p.getApiKey() : null;
        String apiBase = config.getApiBase(model);

        if ("custom".equals(providerName)) {
            if (apiBase == null || apiBase.isBlank()) apiBase = "http://localhost:8000/v1";
            if (apiKey == null || apiKey.isBlank()) apiKey = "no-key";
            return new CustomProvider(apiKey, apiBase, model);
        }

        if (apiBase != null && !apiBase.isBlank()) {
            if (apiKey == null || apiKey.isBlank()) apiKey = "no-key";
            return new CustomProvider(apiKey, apiBase, model);
        }

        ProviderRegistry.ProviderSpec spec = ProviderRegistry.findByName(providerName);
        boolean isOauth = spec != null && spec.isOauth();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        if (!hasKey && !isOauth) {
            throw new RuntimeException("未找到模型 " + model + " 的 API Key。请配置 providers." + providerName + ".api_key");
        }

        String effectiveBase = apiBase;
        if (effectiveBase == null || effectiveBase.isBlank()) {
            if (spec != null && spec.getDefaultApiBase() != null && !spec.getDefaultApiBase().isBlank()) {
                effectiveBase = spec.getDefaultApiBase();
            }
        }

        return new CustomProvider(hasKey ? apiKey : "no-key", effectiveBase, model);
    }
}
