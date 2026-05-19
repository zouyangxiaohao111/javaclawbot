package gui.ui;

import agent.AgentLoop;
import agent.UsageAccumulator;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import cli.BuiltinSkillsInstaller;
import cli.RuntimeComponents;
import config.Config;
import config.ConfigIO;
import config.ConfigReloader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import config.mcp.MCPServerConfig;
import corn.CronService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import lombok.extern.slf4j.Slf4j;
import providers.LLMProvider;
import providers.cli.ProjectRegistry;
import session.Session;
import session.SessionManager;
import skills.SkillsLoader;
import utils.Helpers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import agent.tool.db.DataSourceManager;
import config.tool.DbDataSourceConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * BackendBridge — JavaFX GUI 与 javaclawbot 后端的桥接层。
 *
 * 职责：
 * 1. 初始化 Config / SessionManager / LLMProvider / MessageBus / AgentLoop / CronService
 * 2. 启动 bus adapter（busTask + outboundTask）
 * 3. 提供异步消息收发接口（Platform.runLater 回调）
 * 4. 提供各页面所需的后端组件 getter
 */
@Slf4j
public class BackendBridge {


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

    // ── 多会话支持 ──

    /**
     * 单个标签会话的上下文
     */
    static class TabSessionContext {
        final String tabId;
        final String sessionKey; // "cli:{tabId}"
        volatile Session session;
        volatile ProjectRegistry projectRegistry;
        volatile String providerName; // 标签级别提供商名称
        volatile String model;        // 标签级别模型名称
        final AtomicReference<Consumer<ProgressEvent>> progressCallback = new AtomicReference<>();
        final AtomicReference<Consumer<String>> responseCallback = new AtomicReference<>();
        final AtomicInteger userMessageCount = new AtomicInteger(0);
        volatile boolean waitingForResponse = false;
        volatile boolean titleGenerated = false;
        volatile String lastReasoningContent;
        /** 按标签隔离的标题生成标志（修复多标签标题错乱） */
        final AtomicBoolean titleGenerationPending = new AtomicBoolean(false);
        final AtomicBoolean titleRegenerationPending = new AtomicBoolean(false);

        TabSessionContext(String tabId) {
            this.tabId = tabId;
            this.sessionKey = CLI_CHANNEL + ":" + tabId;
        }
    }

    private static final String CLI_CHANNEL = "cli";
    /** 多会话上下文：tabId → TabSessionContext */
    private final ConcurrentHashMap<String, TabSessionContext> tabContexts = new ConcurrentHashMap<>();
    /** 当前激活的标签 ID */
    private volatile String activeTabId = null;

    /**
     * 从 chatId（如 "cli:tab1"）提取 tabId。
     * 格式为 "{channel}:{tabId}"，提取冒号之后的部分。
     */
    private static String getTabIdFromChatId(String chatId) {
        if (chatId == null) return null;
        int idx = chatId.indexOf(':');
        return idx >= 0 ? chatId.substring(idx + 1) : chatId;
    }

    /** 获取或创建指定标签的上下文 */
    private TabSessionContext getOrCreateContext(String tabId) {
        return tabContexts.computeIfAbsent(tabId, TabSessionContext::new);
    }

    /** 获取当前激活标签的上下文，null 表示无活跃标签 */
    private TabSessionContext getActiveContext() {
        String id = activeTabId;
        return id != null ? tabContexts.get(id) : null;
    }

    // ── 标签管理公共 API ──

    /** 为新标签创建会话上下文 */
    public void createTabContext(String tabId) {
        getOrCreateContext(tabId);
    }

    /** 设置当前激活标签 */
    public void setActiveTab(String tabId) {
        this.activeTabId = tabId;
    }

    /** 销毁标签上下文，并清理 SessionManager 中的 key→id 映射 */
    public void destroyTabContext(String tabId) {
        TabSessionContext ctx = tabContexts.remove(tabId);
        // 清理 SessionManager 中的映射，避免 sessions.json 累积废弃条目
        if (ctx != null && sessionManager != null) {
            sessionManager.removeSessionKey(ctx.sessionKey);
        }
    }

    /**
     * 设置标签的模型配置，并持久化到 Session metadata。
     */
    public void setModelForTab(String tabId, String providerName, String model) {
        TabSessionContext ctx = getOrCreateContext(tabId);
        ctx.providerName = providerName;
        ctx.model = model;
        if (ctx.session != null) {
            ctx.session.getMetadata().put("providerName", providerName);
            ctx.session.getMetadata().put("model", model);
            sessionManager.save(ctx.session);
        }
        if (log.isDebugEnabled()) {
            log.debug("setModelForTab tab={} provider={} model={}", tabId, providerName, model);
        }
    }

    /**
     * 获取标签的模型配置。返回 [providerName, model]。
     * 优先使用标签级别配置，否则使用全局默认值。
     */
    public String[] getModelForTab(String tabId) {
        TabSessionContext ctx = getOrCreateContext(tabId);
        String provider = ctx.providerName;
        String model = ctx.model;
        // 如果标签级别没有配置，使用全局默认值
        if (provider == null || provider.isBlank()) {
            provider = config.getAgents().getDefaults().getProvider();
        }
        if (model == null || model.isBlank()) {
            model = config.getAgents().getDefaults().getModel();
        }
        return new String[]{provider, model};
    }

    /**
     * 获取标签的模型显示名称（用于状态栏）。
     * 优先使用标签级别模型，否则使用全局默认模型。
     */
    public String getModelDisplayNameForTab(String tabId) {
        TabSessionContext ctx = getOrCreateContext(tabId);
        if (ctx.model != null && !ctx.model.isBlank()) {
            return ctx.model;
        }
        return config.getAgents().getDefaults().getModel();
    }

    /** 恢复指定标签的会话 */
    public void resumeSession(String tabId, String sessionId) {
        if (sessionManager == null) return;
        TabSessionContext ctx = getOrCreateContext(tabId);
        sessionManager.resumeSession(ctx.sessionKey, sessionId);
        sessionManager.evictFromCache(ctx.sessionKey);
        ctx.session = sessionManager.getOrCreate(ctx.sessionKey);
        // 从 Session metadata 恢复模型配置
        Object pn = ctx.session.getMetadata().get("providerName");
        Object md = ctx.session.getMetadata().get("model");
        if (pn instanceof String p && md instanceof String m) {
            ctx.providerName = p;
            ctx.model = m;
            log.info("Restored model config for tab {}: provider={} model={}", tabId, p, m);
        }
        // 同步更新 session 文件 metadata 行的 key 字段
        sessionManager.updateSessionFileKey(sessionId, ctx.sessionKey);
        ProjectRegistry sessionRegistry = createProjectRegistry(sessionId);
        ctx.projectRegistry = sessionRegistry;
        this.projectRegistry = sessionRegistry;
        if (agentLoop != null) {
            agentLoop.updateProjectRegistry(sessionRegistry);
        }
        notifyRegistryChanged();
        int count = countUserMessages(ctx.session);
        ctx.userMessageCount.set(count);
        if (count >= 3) {
            ctx.titleGenerationPending.set(true);
            ctx.titleRegenerationPending.set(true);
        } else {
            ctx.titleGenerationPending.set(false);
            ctx.titleRegenerationPending.set(false);
        }
    }

    // ── 标题生成计数器（已移入 TabSessionContext 按标签隔离）──

    /** 标题生成/更新后回调（MainStage 设置用于刷新侧栏） */
    private volatile Runnable onTitleChanged;
    /** ProjectRegistry 变更后回调（MainStage 设置用于刷新右下角项目徽标） */
    private volatile Runnable onRegistryChanged;

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
        this.provider = Helpers.makeHotProvider();

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

        // 8b) 首次启动自动初始化：技能 + zjkycode 插件
        ensureSkillsInitialized();

        // 9) 启动 bus 交互模式
        startBusInteractiveMode();

        // 10) 恢复 plan mode 状态（延迟到有活跃标签时由各 tab 独立恢复）
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
                    if (!isTargetCliOutbound(out)) {
                        log.debug("[Outbound] 消息被过滤: channel={}, chatId={}", out.getChannel(), out.getChatId());
                        continue;
                    }

                    // 解析目标标签上下文
                    String outChatId = out.getChatId();
                    String tabId = getTabIdFromChatId(outChatId);
                    TabSessionContext ctx = tabId != null ? tabContexts.get(tabId) : null;
                    if (ctx == null) {
                        log.warn("[Outbound] 收到未知标签的 outbound 消息，忽略: chatId={}, tabId={}, 当前标签={}",
                            outChatId, tabId, tabContexts.keySet());
                        continue;
                    }

                    log.info("[Outbound] 收到消息: tabId={}, channel={}, chatId={}, content={}",
                        tabId, out.getChannel(), outChatId,
                        out.getContent() != null ? out.getContent().substring(0, Math.min(50, out.getContent().length())) : "null");

                    Map<String, Object> meta = out.getMetadata() != null ? out.getMetadata() : Map.of();
                    boolean isProgress = Boolean.TRUE.equals(meta.get("_progress"));
                    boolean isToolHint = Boolean.TRUE.equals(meta.get("_tool_hint"));
                    boolean isToolResult = Boolean.TRUE.equals(meta.get("_tool_result"));
                    boolean isReasoning = Boolean.TRUE.equals(meta.get("_reasoning"));
                    boolean isSystemCommand = Boolean.TRUE.equals(meta.get("_system_command"));
                    String toolName = meta.get("tool_name") instanceof String s ? s : null;
                    String toolCallId = meta.get("tool_call_id") instanceof String s ? s : null;

                    if (isSystemCommand) {
                        // 系统命令回复（/stop、/help、/init、/memory 等）
                        // 走 responseCallback 路径以确保正确渲染为最终消息（而非流式替换气泡）
                        String content = out.getContent() != null ? out.getContent() : "";
                        Consumer<String> cb = ctx.responseCallback.getAndSet(null);
                        ctx.waitingForResponse = false;
                        Platform.runLater(() -> {
                            if (cb != null) {
                                cb.accept(content);
                            }
                        });
                    } else if (isProgress) {
                        String content = out.getContent() != null ? out.getContent() : "";
                        Consumer<ProgressEvent> cb = ctx.progressCallback.get();
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
                            ctx.lastReasoningContent = s;
                        } else {
                            ctx.lastReasoningContent = null;
                        }
                        Consumer<String> cb = ctx.responseCallback.getAndSet(null);
                        ctx.waitingForResponse = false;

                        // 标题生成：回复完成后触发，确保 session 已包含本轮完整对话
                        // force=true 优先：深度对话后的标题再生成，此时不再触发普通生成
                        int msgCount = ctx.userMessageCount.get();
                        if (msgCount >= 3 && ctx.titleRegenerationPending.compareAndSet(false, true)) {
                            ctx.titleGenerationPending.set(true);  // 阻止后续 force=false 触发
                            triggerTitleGeneration(ctx, true);
                        } else if (msgCount >= 1 && ctx.titleGenerationPending.compareAndSet(false, true)) {
                            triggerTitleGeneration(ctx, false);
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
            return CLI_CHANNEL.equals(ch) && tabContexts.containsKey(getTabIdFromChatId(cid));
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

        // 懒创建：如果当前无活跃标签，先创建
        ensureSession();

        TabSessionContext ctx = getActiveContext();
        if (ctx == null) {
            if (onError != null) Platform.runLater(() -> onError.accept("无活跃标签上下文"));
            return;
        }

        ctx.progressCallback.set(onProgress);
        ctx.responseCallback.set(onResponse);
        ctx.waitingForResponse = true;

        CompletableFuture.runAsync(() -> {
            try {
                // 使用 tabId 作为 chatId，让 getSessionKey() 自动计算 "cli:{tabId}"
                InboundMessage in = new InboundMessage(
                        CLI_CHANNEL, "user", ctx.tabId, text, mediaPaths, null);
                // 携带标签级别模型配置
                in.setProviderName(ctx.providerName);
                in.setModel(ctx.model);
                // 如果标签有自定义模型，创建临时 provider
                if (ctx.providerName != null && !ctx.providerName.isBlank()
                        && ctx.model != null && !ctx.model.isBlank()) {
                    try {
                        LLMProvider customProvider = providers.ProviderFactory.createProvider(
                            config, ctx.providerName, ctx.model);
                        in.setCustomProvider(customProvider);
                    } catch (Exception pe) {
                        log.warn("Failed to create custom provider for {}/{}: {}",
                            ctx.providerName, ctx.model, pe.getMessage());
                    }
                }
                bus.publishInbound(in).toCompletableFuture().join();
            } catch (Exception e) {
                ctx.waitingForResponse = false;
                ctx.responseCallback.set(null);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e.getMessage()));
                }
            }
        }, executor);

        // 标题生成计数器（实际触发在收到回复后，确保 session 已包含本轮对话）
        ctx.userMessageCount.incrementAndGet();
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
        TabSessionContext ctx = getActiveContext();
        if (ctx == null || !ctx.waitingForResponse) return;

        // 立即重置等待状态，避免 stop 后 always-waiting 导致无法继续对话
        ctx.waitingForResponse = false;
        ctx.responseCallback.set(null);

        CompletableFuture.runAsync(() -> {
            try {
                // 使用 tabId 作为 chatId，让 getSessionKey() 自动计算 "cli:{tabId}"
                InboundMessage stopMsg = new InboundMessage(
                        CLI_CHANNEL, "user", ctx.tabId, "/stop", null, null);
                bus.publishInbound(stopMsg).toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }, executor);
    }

    public boolean isReady() {
        return sessionManager != null && bus != null && agentLoop != null;
    }

    /**
     * 获取当前活跃会话；null 表示无会话（欢迎页状态）。
     */
    public Session getCurrentSession() {
        TabSessionContext ctx = getActiveContext();
        return ctx != null ? ctx.session : null;
    }

    /**
     * 确保当前有一个活跃会话（懒创建）。
     * 如果当前标签无活跃会话（欢迎页状态），则创建新会话。
     * 在用户发送首条消息时自动调用。
     */
    public void ensureSession() {
        if (activeTabId == null) {
            // 没有活跃标签时自动创建默认标签
            activeTabId = "default";
        }
        TabSessionContext ctx = getOrCreateContext(activeTabId);
        if (ctx.session != null) return;
        if (sessionManager == null) return;

        ctx.session = sessionManager.createNew(ctx.sessionKey);

        // 从 Session metadata 恢复模型配置
        Object pn = ctx.session.getMetadata().get("providerName");
        Object md = ctx.session.getMetadata().get("model");
        if (pn instanceof String p && md instanceof String m) {
            ctx.providerName = p;
            ctx.model = m;
            log.info("Restored model config for tab {}: provider={} model={}", activeTabId, p, m);
        }

        log.info("[Session创建] tabId={}, sessionId={}, sessionKey={}", activeTabId, ctx.session.getSessionId(), ctx.sessionKey);

        // 为新会话创建独立的 ProjectRegistry
        ProjectRegistry newRegistry = createProjectRegistry(ctx.session.getSessionId());
        ctx.projectRegistry = newRegistry;
        this.projectRegistry = newRegistry; // 更新全局引用供 AgentLoop 使用
        if (agentLoop != null) {
            agentLoop.updateProjectRegistry(newRegistry);
        }
        notifyRegistryChanged();
    }

    /**
     * 获取当前活跃标签的 sessionId
     */
    public String getActiveSessionId() {
        TabSessionContext ctx = getActiveContext();
        return ctx != null && ctx.session != null ? ctx.session.getSessionId() : null;
    }

    /**
     * 进入"新对话"状态：清空当前会话引用，显示欢迎页。
     * 不创建新会话，不发送 /clear —— 会话在用户发送消息时懒创建。
     */
    public void newSession() {
        // 清空当前活跃标签上下文
        TabSessionContext ctx = getActiveContext();
        if (ctx != null) {
            ctx.session = null;
            ctx.userMessageCount.set(0);
            ctx.projectRegistry = null;
            ctx.titleGenerationPending.set(false);
            ctx.titleRegenerationPending.set(false);
        }

        // 清空 ProjectRegistry，避免徽标/Popover 残留旧会话的项目绑定
        this.projectRegistry = new ProjectRegistry(null);
        notifyRegistryChanged();
    }

    /**
     * 恢复到指定会话
     */
    public void resumeSession(String sessionId) {
        if (sessionManager == null) return;

        TabSessionContext ctx = getActiveContext();
        if (ctx == null) return;

        sessionManager.resumeSession(ctx.sessionKey, sessionId);
        // 清除缓存，强制下次 getOrCreate 从磁盘加载
        sessionManager.evictFromCache(ctx.sessionKey);

        // 将 session 指向恢复的会话
        ctx.session = sessionManager.getOrCreate(ctx.sessionKey);

        // 为恢复的会话加载对应的 ProjectRegistry，避免上一轮绑定遗留
        ProjectRegistry sessionRegistry = createProjectRegistry(sessionId);
        ctx.projectRegistry = sessionRegistry;
        this.projectRegistry = sessionRegistry;
        if (agentLoop != null) {
            agentLoop.updateProjectRegistry(sessionRegistry);
        }
        notifyRegistryChanged();

        // 根据会话已有消息数初始化标题生成计数器，避免恢复历史后重复触发
        int count = countUserMessages(ctx.session);
        ctx.userMessageCount.set(count);
        if (count >= 3) {
            // 已有足够对话轮次，不再触发标题生成/更新
            ctx.titleGenerationPending.set(true);
            ctx.titleRegenerationPending.set(true);
        } else {
            ctx.titleGenerationPending.set(false);
            ctx.titleRegenerationPending.set(false);
        }
    }

    /** 统计会话中 user 角色的消息数 */
    private static int countUserMessages(Session session) {
        if (session == null) return 0;
        int count = 0;
        for (Map<String, Object> msg : session.getMessages()) {
            if ("user".equals(msg.get("role"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取会话历史消息（直接从磁盘加载，不经缓存）
     */
    public List<Map<String, Object>> getSessionHistory(String sessionId) {
        if (sessionManager == null) return List.of();
        TabSessionContext ctx = getActiveContext();
        if (ctx == null) return List.of();
        sessionManager.resumeSession(ctx.sessionKey, sessionId);
        sessionManager.evictFromCache(ctx.sessionKey);
        Session session = sessionManager.getOrCreate(ctx.sessionKey);
        return session.getHistory();
    }

    /**
     * 异步生成/更新会话标题
     * @param force 为 true 时即使已有标题也重新生成（对话深入后更新）
     */
    private void triggerTitleGeneration(TabSessionContext ctx, boolean force) {
        // 使用传入的 ctx.session 而不是 getCurrentSession()，避免标签切换导致的竞态条件
        if (provider == null || sessionManager == null) return;
        if (ctx == null || ctx.session == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                Session session = ctx.session;
                if (session == null) {
                    return;
                }
                String sessionId = session.getSessionId();

                // force=false 时若已有标题则直接跳过
                if (!force) {
                    Map<String, Object> meta = session.getMetadata();
                    if (meta != null && meta.containsKey("title")
                            && meta.get("title") instanceof String s && !s.isBlank()) {
                        log.info("标题已存在，跳过初始生成: sessionId=" + sessionId);
                        return;
                    }
                }

                if (!force) {
                    // ── 首次生成：直接截取首条用户消息，不调用 LLM，不阻塞 ──
                    String fallback = extractFirstUserMessage(session);
                    if (fallback == null || fallback.isBlank()) {
                        fallback = "新对话-" + java.time.LocalDate.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd"));
                    }
                    session.getMetadata().put("title", fallback);
                    sessionManager.save(session);
                    log.info("标题生成(首条消息截断): sessionId={}, title={}", sessionId, fallback);
                } else {
                    // ── 深度总结（第三次对话后）：用 LLM 生成更精确的标题 ──
                    String fastModel = config.getAgents().getDefaults().getFastModel();
                    String defaultModel = provider.getDefaultModel();
                    String effectiveModel = (fastModel != null && !fastModel.isBlank()) ? fastModel : defaultModel;
                    log.info("[标题诊断] 开始LLM深度总结: sessionId={}, force={}, model={}, sessionMsgs={}",
                        sessionId, force, effectiveModel, session.getMessages().size());

                    String title = TitleGenerator.generateTitle(
                        provider, session,
                        fastModel,
                        force
                    );
                    if (title != null && !title.isBlank()) {
                        session.getMetadata().put("title", title);
                        sessionManager.save(session);
                        log.info("标题更新成功(LLM): sessionId={}, title={}", sessionId, title);
                    } else {
                        // LLM 失败，保留已有标题
                        log.info("标题更新跳过（LLM 失败，保留已有标题）: sessionId={}", sessionId);
                    }
                }
            } catch (Exception e) {
                log.warn("标题生成异常: " + e.getMessage());
            } finally {
                // 重置标志位，允许下次消息重新尝试标题生成/更新
                resetTitleFlags(ctx);
            }
            // 通知 UI 刷新侧栏标题
            log.info("[标题回调] 准备触发 onTitleChanged, isNull={}", onTitleChanged == null);
            if (onTitleChanged != null) {
                log.info("[标题回调] 触发 onTitleChanged 回调");
                Platform.runLater(onTitleChanged);
            }
        }, executor);
    }

    /**
     * 重置标题生成标志位（按标签隔离）。
     * 始终重置两个标志位，避免 force=true 后 titleGenerationPending 永久卡死。
     */
    private void resetTitleFlags(TabSessionContext ctx) {
        if (ctx != null) {
            ctx.titleRegenerationPending.set(false);
            ctx.titleGenerationPending.set(false);
        }
    }

    /** 从会话历史中提取首条用户消息（截取 20 字）作为标题回退 */
    private static String extractFirstUserMessage(Session session) {
        if (session == null) return null;
        for (Map<String, Object> msg : session.getMessages()) {
            if ("user".equals(msg.get("role"))) {
                Object content = msg.get("content");
                String text = null;
                if (content instanceof String s) {
                    text = s;
                } else if (content instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                            text = (String) m.get("text");
                            break;
                        }
                    }
                }
                if (text != null && !text.isBlank()) {
                    text = text.replaceAll("\\s+", " ").trim();
                    if (text.length() > 20) text = text.substring(0, 20);
                    return text;
                }
            }
        }
        return null;
    }

    public void setOnTitleChanged(Runnable callback) {
        this.onTitleChanged = callback;
    }

    /** 设置 ProjectRegistry 变更回调（用于 GUI 刷新项目徽标） */
    public void setOnRegistryChanged(Runnable callback) {
        this.onRegistryChanged = callback;
    }

    private void notifyRegistryChanged() {
        if (onRegistryChanged != null) {
            Platform.runLater(onRegistryChanged);
        }
    }

    /**
     * 从磁盘重新加载配置（解决 GUI 页面缓存问题）
     */
    public void reloadConfigFromDisk() {
        try {
            this.config = ConfigIO.loadConfig(null);
            log.debug("配置已从磁盘重新加载");
        } catch (Exception e) {
            log.warn("重新加载配置失败: " + e.getMessage());
        }
    }

    /** MCP 服务器实时状态 */
    public enum McpStatus { CONNECTED, DISABLED, DISCONNECTED }

    /** 数据源实时状态 */
    public enum DataSourceStatus { CONNECTED, DISABLED, DISCONNECTED }

    /** 获取单个 MCP 服务器的实时状态 */
    public McpStatus getMcpStatus(String serverName) {
        MCPServerConfig cfg = config.getTools().getMcpServers().get(serverName);
        if (cfg == null) return McpStatus.DISCONNECTED;
        if (!cfg.isEnable()) return McpStatus.DISABLED;
        if (agentLoop != null && agentLoop.getMcpManager() != null
                && agentLoop.getMcpManager().isServerConnected(serverName)) {
            return McpStatus.CONNECTED;
        }
        return McpStatus.DISCONNECTED;
    }

    /** 获取某个 MCP 服务器已注册的工具名称列表 */
    public List<String> getMcpServerTools(String serverName) {
        if (agentLoop != null && agentLoop.getMcpManager() != null) {
            return agentLoop.getMcpManager().getServerToolNames(serverName);
        }
        return List.of();
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
        MCPServerConfig cfg = parseMcpJson(jsonStr);
        config.getTools().getMcpServers().put(name, cfg);
        try {
            ConfigIO.saveConfig(config, null);
            return true;
        } catch (IOException e) {
            config.getTools().getMcpServers().remove(name);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    /** 编辑已有 MCP 服务器（表单模式） */
    public boolean updateMcpServer(String oldName, String newName, String command) {
        Map<String, MCPServerConfig> servers = config.getTools().getMcpServers();
        if (!servers.containsKey(oldName)) {
            throw new IllegalArgumentException("服务器不存在: " + oldName);
        }
        MCPServerConfig cfg = servers.remove(oldName);
        cfg.setCommand(command);
        servers.put(newName, cfg);
        try {
            ConfigIO.saveConfig(config, null);
            return true;
        } catch (IOException e) {
            servers.remove(newName);
            servers.put(oldName, cfg);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    /** 编辑已有 MCP 服务器（RAW JSON 模式） */
    public boolean updateMcpServerRaw(String oldName, String newName, String jsonStr) {
        Map<String, MCPServerConfig> servers = config.getTools().getMcpServers();
        if (!servers.containsKey(oldName)) {
            throw new IllegalArgumentException("服务器不存在: " + oldName);
        }
        MCPServerConfig newCfg = parseMcpJson(jsonStr);
        MCPServerConfig oldCfg = servers.remove(oldName);
        servers.put(newName, newCfg);
        try {
            ConfigIO.saveConfig(config, null);
            return true;
        } catch (IOException e) {
            servers.remove(newName);
            servers.put(oldName, oldCfg);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    private MCPServerConfig parseMcpJson(String jsonStr) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MCPServerConfig cfg;
        try {
            cfg = mapper.readValue(jsonStr, MCPServerConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
        boolean hasCommand = cfg.getCommand() != null && !cfg.getCommand().isBlank();
        boolean hasUrl = cfg.getUrl() != null && !cfg.getUrl().isBlank();
        if (!hasCommand && !hasUrl) {
            throw new IllegalArgumentException("command 或 url 至少需要配置一个");
        }
        return cfg;
    }

    /** 删除 MCP 服务器 */
    public boolean deleteMcpServer(String name) {
        if (config.getTools().getMcpServers().remove(name) != null) {
            try {
                ConfigIO.saveConfig(config, null);
                return true;
            } catch (IOException e) {
                throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
            }
        }
        return false;
    }

    /** 触发 MCP 工具刷新（重新连接并拉取 tools/list） */
    public CompletableFuture<String> refreshMcpTools() {
        if (agentLoop != null && agentLoop.getMcpManager() != null) {
            return agentLoop.getMcpManager().refreshTools().toCompletableFuture();
        }
        return CompletableFuture.completedFuture(null);
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
        TabSessionContext ctx = getActiveContext();
        if (ctx != null) {
            ctx.userMessageCount.set(0);
            ctx.titleGenerationPending.set(false);
            ctx.titleRegenerationPending.set(false);
        }
    }

    /**
     * 热刷新 LLMProvider 和模型配置（模型/API Key 变更时调用）
     */
    public void refreshProvider() {
        String defaultModel = this.config.getAgents().getDefaults().getModel();
        LLMProvider newProvider = Helpers.makeHotProvider();
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

    private DataSourceManager getDataSourceManager() {
        return agentLoop != null ? agentLoop.getDataSourceManager() : null;
    }

    public boolean addDataSource(String name, String jdbcUrl, String username, String password,
                                  String driverClass, int maxPoolSize, long connectionTimeout) {
        if (config.getTools().getDb().getDatasources().containsKey(name)) {
            throw new IllegalArgumentException("数据源名称已存在: " + name);
        }
        DbDataSourceConfig cfg = new DbDataSourceConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClass(driverClass);
        cfg.setMaxPoolSize(maxPoolSize);
        cfg.setConnectionTimeout(connectionTimeout);
        cfg.setEnable(true);
        config.getTools().getDb().getDatasources().put(name, cfg);
        try {
            ConfigIO.saveConfig(config, null);
            DataSourceManager mgr = getDataSourceManager();
            if (mgr != null) {
                mgr.addDataSource(name, cfg);
            }
            return true;
        } catch (IOException e) {
            config.getTools().getDb().getDatasources().remove(name);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    public boolean updateDataSource(String oldName, String newName, String jdbcUrl, String username,
                                     String password, String driverClass, int maxPoolSize,
                                     long connectionTimeout) {
        Map<String, DbDataSourceConfig> dbs = config.getTools().getDb().getDatasources();
        if (!dbs.containsKey(oldName)) {
            throw new IllegalArgumentException("数据源不存在: " + oldName);
        }
        DbDataSourceConfig oldCfg = dbs.remove(oldName);
        DataSourceManager mgr = getDataSourceManager();
        if (mgr != null) {
            mgr.removeDataSource(oldName);
        }

        DbDataSourceConfig cfg = new DbDataSourceConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        // If password is the placeholder "******", keep the old one
        if ("******".equals(password)) {
            cfg.setPassword(oldCfg.getPassword());
        } else {
            cfg.setPassword(password);
        }
        cfg.setDriverClass(driverClass);
        cfg.setMaxPoolSize(maxPoolSize);
        cfg.setConnectionTimeout(connectionTimeout);
        cfg.setEnable(oldCfg.isEnable());
        dbs.put(newName, cfg);
        try {
            ConfigIO.saveConfig(config, null);
            if (mgr != null && cfg.isEnable()) {
                mgr.addDataSource(newName, cfg);
            }
            return true;
        } catch (IOException e) {
            dbs.remove(newName);
            dbs.put(oldName, oldCfg);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    public boolean deleteDataSource(String name) {
        Map<String, DbDataSourceConfig> dbs = config.getTools().getDb().getDatasources();
        if (dbs.remove(name) != null) {
            DataSourceManager mgr = getDataSourceManager();
            if (mgr != null) {
                mgr.removeDataSource(name);
            }
            try {
                ConfigIO.saveConfig(config, null);
                return true;
            } catch (IOException e) {
                throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
            }
        }
        return false;
    }

    public String testDataSourceConnection(String jdbcUrl, String username, String password,
                                            String driverClass) {
        try {
            if (driverClass != null && !driverClass.isBlank()) {
                Class.forName(driverClass);
            } else {
                String inferred = DataSourceManager.inferDriverClass(jdbcUrl);
                if (inferred != null) {
                    Class.forName(inferred);
                }
            }
            Properties props = new Properties();
            props.setProperty("user", username != null ? username : "");
            props.setProperty("password", password != null ? password : "");
            DriverManager.setLoginTimeout(5);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
                return null; // success
            }
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "连接失败（未知错误）";
        }
    }

    public DataSourceStatus getDataSourceStatus(String name) {
        Map<String, DbDataSourceConfig> dbs = config.getTools().getDb().getDatasources();
        DbDataSourceConfig cfg = dbs.get(name);
        if (cfg == null) return DataSourceStatus.DISCONNECTED;
        if (!cfg.isEnable()) return DataSourceStatus.DISABLED;
        DataSourceManager mgr = getDataSourceManager();
        if (mgr != null && mgr.getDataSource(name) != null) {
            return DataSourceStatus.CONNECTED;
        }
        return DataSourceStatus.DISCONNECTED;
    }

    public boolean reconnectDataSource(String name) {
        Map<String, DbDataSourceConfig> dbs = config.getTools().getDb().getDatasources();
        DbDataSourceConfig cfg = dbs.get(name);
        if (cfg == null) return false;
        if (!cfg.isEnable()) return false;
        DataSourceManager mgr = getDataSourceManager();
        if (mgr != null) {
            mgr.removeDataSource(name);
            mgr.addDataSource(name, cfg);
            return true;
        }
        return false;
    }

    public boolean toggleDataSource(String name, boolean enable) {
        Map<String, DbDataSourceConfig> dbs = config.getTools().getDb().getDatasources();
        DbDataSourceConfig cfg = dbs.get(name);
        if (cfg == null) return false;
        cfg.setEnable(enable);
        DataSourceManager mgr = getDataSourceManager();
        try {
            if (enable) {
                if (mgr != null) mgr.addDataSource(name, cfg);
            } else {
                if (mgr != null) mgr.removeDataSource(name);
            }
            ConfigIO.saveConfig(config, null);
            return true;
        } catch (IOException e) {
            cfg.setEnable(!enable);
            throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
        }
    }

    // ── Getters ──

    public Config getConfig() {
        // 检测配置文件是否被外部修改（手动编辑等），自动重新加载
        try {
            if (config != null && ConfigIO.isConfigChanged(config.getWorkspacePath())) {
                log.info("检测到 config.json 外部修改，自动重新加载");
                reloadConfigFromDisk();
            }
        } catch (Exception ignored) {}
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

    /** 获取当前会话的 FileBackupManager（用于 UI 层 diff/回滚操作，按 sessionId 隔离） */
    public agent.tool.file.FileBackupManager getFileBackupManager() {
        if (agentLoop == null) return null;
        TabSessionContext ctx = getActiveContext();
        if (ctx != null && ctx.session != null) {
            return agentLoop.getOrCreateBackupManager(ctx.session.getSessionId());
        }
        return agentLoop.getOrCreateBackupManager("cli:default");
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
        TabSessionContext ctx = getActiveContext();
        return ctx != null ? ctx.sessionKey : "cli:default";
    }

    public boolean isWaitingForResponse() {
        TabSessionContext ctx = getActiveContext();
        return ctx != null && ctx.waitingForResponse;
    }

    /** 获取最近一次回复的推理内容（可能为 null） */
    public String getLastReasoningContent() {
        TabSessionContext ctx = getActiveContext();
        return ctx != null ? ctx.lastReasoningContent : null;
    }

    /**
     * 获取当前会话的上下文使用率 (0.0 ~ 1.0)。
     *
     * 用于状态栏展示。有真实 usage 数据时使用 lastCall 的 prompt tokens；
     * 首轮无数据时回退到消息字符估算。
     */
    public double getContextUsageRatio() {
        if (agentLoop == null || sessionManager == null) return 0.0;
        TabSessionContext ctx = getActiveContext();
        if (ctx == null) return 0.0;
        Session session = sessionManager.getOrCreate(ctx.sessionKey);
        if (session == null) return 0.0;
        UsageAccumulator usageAcc = session.obtainLastUsage();
        List<Map<String, Object>> messages = session.getMessages();
        return agentLoop.getContextRatioByUsage(usageAcc, messages);
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
     * 首次 GUI 启动时自动初始化内置技能到工作区。
     * 仅在 workspace/skills 目录为空或不存在时安装技能。
     * 脚本和插件同步独立于技能安装，每次启动都执行。
     */
    private void ensureSkillsInitialized() {
        Path workspacePath = this.config.getWorkspacePath();

        // ── 技能安装：仅在 skills 目录为空时执行 ──
        installBuiltinSkillsIfNeeded(workspacePath);

        // ── 插件和脚本同步：每次启动都执行（不依赖技能状态）──
        syncBuiltinPlugins(workspacePath);
        syncBuiltinScripts(workspacePath);
    }

    /**
     * 增量同步内置技能到工作区。
     *
     * - 首次启动（skills 目录为空）：直接安装全部内置技能，无需弹窗。
     * - 后续启动：检测内置技能是否有更新（新增文件/文件夹），
     *   如有则弹窗询问用户是否覆盖更新。
     */
    private void installBuiltinSkillsIfNeeded(Path workspacePath) {
        Path skillsDir = workspacePath.resolve("skills");

        // 发现所有内置技能
        List<BuiltinSkillsInstaller.SkillResource> allSkills =
            BuiltinSkillsInstaller.discoverBuiltinSkills();
        if (allSkills.isEmpty()) return;

        // 判断是否首次启动（skills 目录为空）
        boolean skillsEmpty = !Files.exists(skillsDir) || !Files.isDirectory(skillsDir);
        if (!skillsEmpty) {
            try (var ds = Files.newDirectoryStream(skillsDir)) {
                skillsEmpty = !ds.iterator().hasNext();
            } catch (IOException e) {
                // 读取失败视为非空，继续走增量检测逻辑
            }
        }

        if (skillsEmpty) {
            // ── 首次启动：直接安装全部内置技能 ──
            log.info("首次启动，初始化 " + allSkills.size() + " 个内置技能到工作区...");
            BuiltinSkillsInstaller.InstallSummary summary =
                BuiltinSkillsInstaller.installSelectedSkills(workspacePath, allSkills, false);
            if (!summary.getInstalled().isEmpty()) {
                log.info("已安装技能: " + String.join(", ", summary.getInstalled()));
            }
            if (!summary.getFailed().isEmpty()) {
                log.warn("技能安装失败: " + String.join(", ", summary.getFailed()));
            }
            return;
        }

        // ── 后续启动：检测技能更新 ──
        List<String> updatedSkills = BuiltinSkillsInstaller.detectSkillUpdates(workspacePath);
        if (updatedSkills.isEmpty()) {
            return; // 无更新，静默跳过
        }

        log.info("检测到 " + updatedSkills.size() + " 个内置技能有更新: "
            + String.join(", ", updatedSkills));

        // 弹窗询问用户是否覆盖更新
        boolean userConfirmed = showSkillUpdateDialog(updatedSkills);
        if (!userConfirmed) {
            log.info("用户取消技能更新");
            return;
        }

        // 用户确认：覆盖更新有变动的技能
        List<BuiltinSkillsInstaller.SkillResource> toUpdate = allSkills.stream()
            .filter(s -> updatedSkills.contains(s.getName()))
            .collect(java.util.stream.Collectors.toList());

        BuiltinSkillsInstaller.InstallSummary summary =
            BuiltinSkillsInstaller.installSelectedSkills(workspacePath, toUpdate, true); // overwrite=true
        if (!summary.getOverwritten().isEmpty()) {
            log.info("已覆盖更新技能: " + String.join(", ", summary.getOverwritten()));
        }
        if (!summary.getFailed().isEmpty()) {
            log.warn("技能更新失败: " + String.join(", ", summary.getFailed()));
        }
    }

    /**
     * 在 JavaFX 线程中弹出确认对话框，询问用户是否覆盖更新技能。
     *
     * @return true 表示用户同意更新
     */
    private boolean showSkillUpdateDialog(List<String> updatedSkills) {
        // 如果已是 JavaFX 线程，直接显示
        if (Platform.isFxApplicationThread()) {
            return showUpdateDialogDirectly(updatedSkills);
        }

        // 否则派发到 JavaFX 线程并等待结果
        java.util.concurrent.FutureTask<Boolean> task =
            new java.util.concurrent.FutureTask<>(() -> showUpdateDialogDirectly(updatedSkills));
        Platform.runLater(task);
        try {
            return task.get();
        } catch (Exception e) {
            log.warn("技能更新弹窗异常: " + e.getMessage());
            return false;
        }
    }

    private boolean showUpdateDialogDirectly(List<String> updatedSkills) {
        String skillList = String.join("\n  • ", updatedSkills);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("内置技能更新");
        alert.setHeaderText("检测到以下内置技能有更新：");
        alert.setContentText("  \u2022 " + skillList + "\n\n是否覆盖更新？\n（选择「否」将保留当前版本，下次启动仍会提示）");

        ButtonType btnYes = new ButtonType("是，覆盖更新");
        ButtonType btnNo = new ButtonType("否，保留当前版本");
        alert.getButtonTypes().setAll(btnYes, btnNo);

        return alert.showAndWait().orElse(btnNo) == btnYes;
    }

    /**
     * 同步内置插件到 workspace/plugins/（全量，排除 example.*）。
     * 每次启动都执行，确保新增插件被部署。
     */
    private void syncBuiltinPlugins(Path workspacePath) {
        BuiltinSkillsInstaller.SyncResult pluginsResult =
            BuiltinSkillsInstaller.syncPlugins(workspacePath);
        if (pluginsResult.hasInstalled()) {
            log.info("已同步插件: " + String.join(", ", pluginsResult.getInstalled()));
        }
    }

    /**
     * 同步内置脚本到 workspace/scripts/。
     * 每次启动都执行，确保新增脚本（如 install-gitnexus.js）被部署。
     */
    private void syncBuiltinScripts(Path workspacePath) {
        BuiltinSkillsInstaller.SyncScriptsResult scriptsResult =
            BuiltinSkillsInstaller.syncScripts(workspacePath);
        if (scriptsResult.hasInstalled()) {
            log.info("已同步脚本: " + String.join(", ", scriptsResult.getInstalled()));
        }
    }

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

}
