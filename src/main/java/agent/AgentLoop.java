package agent;

import agent.tool.*;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;
import corn.CronService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.ToolCallRequest;
import session.Session;
import session.SessionManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * AgentLoop：核心处理引擎（Agent Loop）。
 *
 * 主要职责：
 * 1) 从 MessageBus 接收入站消息
 * 2) 基于会话历史/记忆/技能构建上下文 messages
 * 3) 调用大模型（LLMProvider.chat）
 * 4) 执行模型返回的工具调用（Tool Calls）
 * 5) 将最终回复发送回 bus
 *
 * 关键说明：
 * - MCP：这里采用“懒连接”（lazy connect），仅在需要时 connectMcp()，提供 closeMcp() 与 Python close_mcp() 对齐。
 * - /stop：取消当前 session 下的活跃任务（包含子代理 subagents）。
 * - 全局 processingLock：保证“同一时刻只处理一条消息”，与 Python 的 _processing_lock 行为一致，避免并发导致上下文串线。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /**
     * 工具返回内容写入 session 时的最大字符数，避免 tool message 太大导致 session 爆炸。
     */
    private static final int TOOL_RESULT_MAX_CHARS = 500;

    /**
     * 用于剥离 <think>...</think> 块（如果你的模型会输出这种思考标签）。
     * 注意：仅做展示/保存时清理，不影响工具调用逻辑。
     */
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>[\\s\\S]*?</think>");

    // =========================
    // 依赖组件
    // =========================

    /**
     * 消息总线：负责收/发消息（入站 consumeInbound，出站 publishOutbound）。
     */
    private final MessageBus bus;

    /**
     * 渠道配置（比如 Telegram/CLI/WhatsApp 等），主要用于外部读取。
     */
    private final ConfigSchema.ChannelsConfig channelsConfig;

    /**
     * 大模型提供方抽象：chat(messages, tools, model, maxTokens, temperature) -> resp
     */
    private final LLMProvider provider;

    /**
     * 工作空间目录：文件工具/exec 工具/记忆存储等都会使用。
     */
    private final java.nio.file.Path workspace;

    // =========================
    // 模型与运行参数
    // =========================

    /**
     * 具体使用的模型名（如果未指定则使用 provider 默认模型）。
     */
    private final String model;

    /**
     * Agent Loop 最大迭代次数：每一轮可能调用模型一次，然后执行工具，再进入下一轮。
     */
    private final int maxIterations;

    /**
     * temperature：随机性。
     */
    private final double temperature;

    /**
     * maxTokens：模型最大输出 token 限制（provider 是否严格遵守取决于实现）。
     */
    private final int maxTokens;

    /**
     * memoryWindow：上下文窗口（取多少条历史消息构建 prompt）。
     */
    private final int memoryWindow;

    /**
     * reasoningEffort：保留字段（与 Python 对齐），provider 可能忽略。
     */
    private final String reasoningEffort;

    /**
     * brave 搜索 API Key（WebSearchTool 使用）。
     */
    private final String braveApiKey;

    // =========================
    // 工具/调度/安全
    // =========================

    /**
     * ExecTool 配置：超时、PATH append 等。
     */
    private final ConfigSchema.ExecToolConfig execConfig;

    /**
     * Cron 服务（可选）：用于 cron 工具调度任务。
     */
    private final CronService cronService;

    /**
     * 是否限制工具只能操作 workspace 范围内（文件读写、exec cwd 等）。
     */
    private final boolean restrictToWorkspace;

    // =========================
    // 内部组件：上下文/会话/工具/子代理
    // =========================

    /**
     * ContextBuilder：负责把 history + 本轮用户输入 + media 等拼成 messages（OpenAI 风格结构）。
     */
    private final ContextBuilder context;

    /**
     * SessionManager：会话存储与加载（持久化）。
     */
    private final SessionManager sessions;

    /**
     * ToolRegistry：工具注册与执行器；也提供工具 definitions 给模型。
     */
    private final ToolRegistry tools;

    /**
     * SubagentManager：子代理管理器（spawn 工具会用到）。
     */
    private final SubagentManager subagents;

    // =========================
    // 并发控制与线程池
    // =========================

    /**
     * 执行线程池：用 cachedThreadPool，任务较多时会扩容。
     * 线程设为 daemon，避免阻塞 JVM 退出。
     */
    private final ExecutorService executor;

    /**
     * 全局处理锁：保证同一时刻只能有一个 dispatch/processMessage 进入核心处理区域，
     * 防止多个消息同时处理导致共享资源（messages/session/tools context）串线。
     *
     * 这里用 Semaphore(1) 等价于互斥锁。
     */
    private final Semaphore processingLock = new Semaphore(1);

    /**
     * run() 循环是否持续运行。
     */
    private volatile boolean running = false;

    // =========================
    // MCP（懒连接）
    // =========================

    /**
     * MCP Server 配置列表（key -> server config）。
     */
    private final Map<String, ConfigSchema.MCPServerConfig> mcpServers;

    /**
     * MCP 是否已连接完成。
     */
    private volatile boolean mcpConnected = false;

    /**
     * MCP 是否正在连接中（防止重复并发连接）。
     */
    private volatile boolean mcpConnecting = false;

    // =========================
    // 记忆压缩/归档（consolidation）
    // =========================

    /**
     * 正在 consolidate 的 sessionKey 集合（避免重复触发）。
     */
    private final Set<String> consolidating = ConcurrentHashMap.newKeySet();

    /**
     * 记录 consolidation 的异步任务，用于管理/清理。
     */
    private final Set<CompletableFuture<?>> consolidationTasks = ConcurrentHashMap.newKeySet();

    /**
     * 每个 sessionKey 一把 ReentrantLock：保证同一个 session 的 consolidation 串行。
     */
    private final ConcurrentHashMap<String, ReentrantLock> consolidationLocks = new ConcurrentHashMap<>();

    // =========================
    // /stop：每个 session 的活跃任务列表
    // =========================

    /**
     * key=sessionKey，value=该 session 当前正在执行的异步任务列表。
     * /stop 会把这些任务 cancel 掉。
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableFuture<?>>> activeTasks = new ConcurrentHashMap<>();

    /**
     * 构造函数：注入所有依赖与配置，并注册默认工具。
     */
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
            ConfigSchema.ChannelsConfig channelsConfig
    ) {
        this.bus = bus;
        this.channelsConfig = channelsConfig;
        this.provider = provider;
        this.workspace = workspace;

        // 如果外部传入 model 为空，则使用 provider 默认模型
        this.model = (model != null && !model.isBlank()) ? model : provider.getDefaultModel();

        // 默认参数（与 Python 版本保持相近）
        this.maxIterations = (maxIterations != null) ? maxIterations : 100;
        this.temperature = (temperature != null) ? temperature : 0.1;
        this.maxTokens = (maxTokens != null) ? maxTokens : 4096;
        this.memoryWindow = (memoryWindow != null) ? memoryWindow : 100;

        this.reasoningEffort = reasoningEffort;
        this.braveApiKey = braveApiKey;

        // execConfig 如果不传，则 new 一个默认配置，避免 NPE
        this.execConfig = (execConfig != null) ? execConfig : new ConfigSchema.ExecToolConfig();

        this.cronService = cronService;
        this.restrictToWorkspace = restrictToWorkspace;

        // 上下文构建器 & session 管理器 & 工具注册器
        this.context = new ContextBuilder(workspace);
        this.sessions = (sessionManager != null) ? sessionManager : new SessionManager(workspace);
        this.tools = new ToolRegistry();

        // 线程池：daemon + 命名，便于排查线程/日志
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("nanobot-agent-" + t.getId());
            return t;
        });

        // 子代理管理器（spawn 工具背后的执行者）
        this.subagents = new SubagentManager(
                provider,
                workspace,
                bus,
                this.model,
                this.temperature,
                this.maxTokens,
                this.reasoningEffort,
                braveApiKey,
                this.execConfig,
                restrictToWorkspace,
                null
        );

        // MCP Server 配置（可能为空）
        this.mcpServers = (mcpServers != null) ? mcpServers : Map.of();

        // 注册默认工具集合
        registerDefaultTools();
    }

    // ---------------------------------------------------------------------
    // Tools：工具注册与上下文设置
    // ---------------------------------------------------------------------

    /**
     * 注册默认工具到 ToolRegistry。
     * 这些工具会出现在 tools.getDefinitions() 中，让模型可选择调用。
     */
    private void registerDefaultTools() {
        // 如果 restrictToWorkspace=true，则 allowedDir=workspace，工具仅允许操作该目录范围
        java.nio.file.Path allowedDir = restrictToWorkspace ? workspace : null;

        // 文件系统工具：读/写/编辑/列目录
        tools.register(new FileSystemTools.ReadFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.WriteFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.EditFileTool(workspace, allowedDir));
        tools.register(new FileSystemTools.ListDirTool(workspace, allowedDir));

        // ExecTool：在本机执行命令（通常危险，所以配合 restrictToWorkspace、timeout、PATH append 等做限制）
        tools.register(new ExecTool(
                execConfig.getTimeout(),
                workspace.toString(),
                null,
                null,
                restrictToWorkspace,
                execConfig.getPathAppend()
        ));

        // WebSearchTool：搜索（比如 Brave API）
        tools.register(new WebSearchTool(braveApiKey, null));

        // WebFetchTool：抓网页内容
        tools.register(new WebFetchTool(null));

        // MessageTool：允许模型“主动发消息到当前 channel/chat”，用于边执行边输出
        tools.register(new MessageTool(bus::publishOutbound, "", "", null));

        // SpawnTool：生成子代理任务
        tools.register(new SpawnTool(subagents));

        // CronTool：如果 cronService 存在，则注册 cron 工具
        if (cronService != null) {
            tools.register(new CronTool(cronService));
        }
    }

    /**
     * 给“需要路由上下文”的工具设置上下文：
     * - message 工具：需要知道往哪个 channel/chatId 发送，以及 messageId（用于引用/线程等）
     * - spawn 工具：需要知道父会话所在 channel/chatId
     * - cron 工具：需要知道在哪个 channel/chatId 回报结果
     *
     * 这相当于 Python 中每轮调用前的 _set_tool_context()。
     */
    private void setToolContext(String channel, String chatId, String messageId) {
        // message/spawn/cron tools need routing context (parity with Python _set_tool_context)
        var mt = tools.get("message");
        if (mt instanceof MessageTool m) {
            m.setContext(channel, chatId, messageId);
        }
        var st = tools.get("spawn");
        if (st instanceof SpawnTool s) {
            s.setContext(channel, chatId);
        }
        var ct = tools.get("cron");
        if (ct instanceof CronTool c) {
            c.setContext(channel, chatId);
        }
    }

    // ---------------------------------------------------------------------
    // MCP：懒连接
    // ---------------------------------------------------------------------

    /**
     * 连接 MCP（懒连接）：
     * - 如果已连接/正在连接/没有 server 配置，则直接返回 completed。
     * - 否则异步连接；连接成功后通常需要把 MCP tools 注册进 ToolRegistry（TODO 部分）。
     */
    private CompletionStage<Void> connectMcp() {
        if (mcpConnected || mcpConnecting || mcpServers.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        mcpConnecting = true;

        return CompletableFuture.runAsync(() -> {
            try {
                // TODO: 连接 MCP servers 并注册 tools 到 ToolRegistry。
                // 例如：McpConnector.connectMcpServers(mcpServers, tools);
                mcpConnected = true;
            } catch (Exception e) {
                // 连接失败：本轮不影响整体运行，下次消息再重试
                log.warn("Failed to connect MCP servers (will retry next message): {}", e.toString());
                mcpConnected = false;
            } finally {
                // 无论成功失败，都要把 connecting 状态清掉
                mcpConnecting = false;
            }
        }, executor);
    }

    /**
     * 与 Python close_mcp() 对齐：
     * - 即便 MCP 没实现，也应安全调用（best-effort）。
     * - 如果你后续加入了 MCP 进程/socket/资源，这里负责释放。
     */
    public CompletionStage<Void> closeMcp() {
        return CompletableFuture.runAsync(() -> {
            try {
                // TODO: close MCP resources if you add them (processes/sockets/exit stacks).
                mcpConnected = false;
                mcpConnecting = false;
            } catch (Exception ignored) {
            }
        }, executor);
    }

    // ---------------------------------------------------------------------
    // 文本格式化辅助方法
    // ---------------------------------------------------------------------

    /**
     * 去除模型输出中的 <think>...</think> 块（如果存在），并 trim。
     * 返回 null 表示清理后是空串。
     */
    private static String stripThink(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = THINK_BLOCK.matcher(text).replaceAll("").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    /**
     * 将工具调用列表转换成“人类可读”的提示文本，用于 progress 输出。
     * 例如：read_file("xxx..."), web_search("iveco ..."), exec("ls ...") 等
     */
    private static String toolHint(List<ToolCallRequest> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var tc : toolCalls) {
            // 从 arguments 中取第一个参数值做预览（不是强约束，只是提示用）
            Object val = null;
            if (tc.getArguments() != null && !tc.getArguments().isEmpty()) {
                val = tc.getArguments().values().iterator().next();
            }
            // 参数不是字符串：只显示工具名
            if (!(val instanceof String s)) {
                parts.add(tc.getName());
            } else {
                // 参数是字符串：截断 40 字符做预览
                parts.add(s.length() > 40
                        ? tc.getName() + "(\"" + s.substring(0, 40) + "…\")"
                        : tc.getName() + "(\"" + s + "\")");
            }
        }
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------------
    // Run loop：长期运行消费入站消息
    // ---------------------------------------------------------------------

    /**
     * 主循环：阻塞运行，直到 stop() 将 running 置为 false。
     *
     * 行为：
     * - 每 1 秒尝试从 bus 拉取一条入站消息
     * - 如果是 /stop：立刻处理 stop 并 continue（不走正常流程）
     * - 否则：将消息 dispatch 放入 executor 异步执行，这样主线程继续 consume（保持响应）
     */
    public CompletableFuture<Void> run() {
        running = true;

        // Python 版本启动时会先 await _connect_mcp()；这里是懒连接，安全调用即可
        connectMcp();

        log.info("Agent loop started");

        while (running) {
            InboundMessage msg = null;
            try {
                // 从 bus 拉取消息，最多等待 1 秒
                msg = bus.consumeInbound(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            if (msg == null) continue;

            // 处理 /stop 指令：取消该会话的任务
            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            if ("/stop".equalsIgnoreCase(content)) {
                handleStop(msg).toCompletableFuture().join();
                continue;
            }

            // 为了保持主循环能继续消费消息（尤其是 stop），这里把 dispatch 异步丢到线程池
            InboundMessage finalMsg = msg;
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> dispatch(finalMsg), executor);

            // 记录 session 下的 active task，用于 /stop cancel
            activeTasks.computeIfAbsent(msg.getSessionKey(), k -> new CopyOnWriteArrayList<>()).add(task);

            // 任务结束后从 activeTasks 移除
            task.whenComplete((v, ex) -> activeTasks
                    .getOrDefault(finalMsg.getSessionKey(), new CopyOnWriteArrayList<>())
                    .remove(task));
        }

        log.info("Agent loop stopped");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 停止主循环：run() 会在下一次 while 判断时退出。
     */
    public void stop() {
        running = false;
        log.info("Agent loop stopping");
    }

    // ---------------------------------------------------------------------
    // /stop：取消当前 session 的任务与子代理
    // ---------------------------------------------------------------------

    /**
     * 处理 /stop：
     * 1) 从 activeTasks 中取出该 session 的任务列表并 cancel
     * 2) best-effort 等待这些任务停止（最多每个等 2 秒）
     * 3) 通知 subagents 取消该 session 的所有子任务
     * 4) 给用户发送停止结果
     */
    private CompletionStage<Void> handleStop(InboundMessage msg) {
        String sessionKey = msg.getSessionKey();

        // 从 map 中移除，避免新任务继续被加入（虽然本 session 的并发仍需外部保证）
        List<CompletableFuture<?>> tasks = activeTasks.remove(sessionKey);
        int cancelled = 0;

        // cancel 所有未完成任务
        if (tasks != null) {
            for (CompletableFuture<?> f : tasks) {
                if (f != null && !f.isDone()) {
                    if (f.cancel(true)) cancelled++;
                }
            }
            // best-effort 等待任务收尾（与 Python await tasks 类似）
            for (CompletableFuture<?> f : tasks) {
                try {
                    f.get(2, TimeUnit.SECONDS);
                } catch (CancellationException ignored) {
                } catch (Exception ignored) {
                }
            }
        }

        int finalCancelled = cancelled;

        // 同时取消子代理任务，并汇总取消数量
        return subagents.cancelBySession(sessionKey)
                .thenCompose(subCancelled -> {
                    int total = finalCancelled + subCancelled;
                    String text = total > 0 ? ("⏹ Stopped " + total + " task(s).") : "No active task to stop.";
                    return bus.publishOutbound(new OutboundMessage(
                            msg.getChannel(),
                            msg.getChatId(),
                            text,
                            List.of(),
                            Map.of()
                    ));
                });
    }

    // ---------------------------------------------------------------------
    // Dispatch：在全局锁下串行处理
    // ---------------------------------------------------------------------

    /**
     * dispatch：真正进入核心处理（processMessage）。
     *
     * 这里会先 acquire processingLock：
     * - 确保“同一时刻只有一个消息被处理”
     * - 避免 session/history/messages/tool context 被并发修改导致错乱
     *
     * 注意：dispatch 本身在 executor 中运行（run() 中已异步派发）。
     */
    private void dispatch(InboundMessage msg) {
        try {
            processingLock.acquire();
            try {
                // 核心：处理消息 -> 得到 OutboundMessage
                OutboundMessage resp = processMessage(msg, null, null).toCompletableFuture().get();

                // 如果 resp!=null：正常发回
                if (resp != null) {
                    bus.publishOutbound(resp).toCompletableFuture().join();
                } else if ("cli".equals(msg.getChannel())) {
                    // 如果 resp==null 且是 CLI：发一个空响应（保持 CLI 的“回合完成”语义）
                    bus.publishOutbound(new OutboundMessage(
                            msg.getChannel(),
                            msg.getChatId(),
                            "",
                            List.of(),
                            msg.getMetadata()
                    )).toCompletableFuture().join();
                }
            } finally {
                processingLock.release();
            }
        } catch (CancellationException ce) {
            // 如果被 cancel（例如 /stop），把取消异常抛出去（上层可感知）
            throw ce;
        } catch (Exception e) {
            // 任何异常：记录日志并返回通用错误提示
            log.warn("Error processing message for session {}: {}", msg.getSessionKey(), e.toString());
            bus.publishOutbound(new OutboundMessage(
                            msg.getChannel(),
                            msg.getChatId(),
                            "Sorry, I encountered an error.",
                            List.of(),
                            Map.of()
                    ))
                    .toCompletableFuture()
                    .join();
        }
    }

    // ---------------------------------------------------------------------
    // Core message processing：核心业务（对应 Python _process_message）
    // ---------------------------------------------------------------------

    /**
     * processMessage：处理一条 InboundMessage，返回 OutboundMessage（异步）。
     *
     * @param msg 入站消息
     * @param sessionKeyOverride 可强制指定 sessionKey（比如 processDirect）
     * @param onProgress 可选的进度回调（如果传 null，会使用默认 busProgress）
     */
    private CompletionStage<OutboundMessage> processMessage(
            InboundMessage msg,
            String sessionKeyOverride,
            ProgressCallback onProgress
    ) {
        // ----------------------------
        // 1) System 消息：子代理回报/后台任务回注（channel="system"）
        // ----------------------------
        // 约定：system 消息的 chat_id 可能是 "channel:chat_id"，用于找到真正的目标会话
        if ("system".equals(msg.getChannel())) {
            String chat = msg.getChatId();
            String channel;
            String chatId;

            // 从 "channel:chatId" 解析出真实路由
            if (chat != null && chat.contains(":")) {
                String[] parts = chat.split(":", 2);
                channel = parts[0];
                chatId = parts[1];
            } else {
                // 兜底：没有 ":" 就默认当作 CLI
                channel = "cli";
                chatId = chat;
            }

            // sessionKey 用 "channel:chatId" 形式
            String key = channel + ":" + chatId;
            Session session = sessions.getOrCreate(key);

            // 用 metadata 里的 message_id（如果有）
            String messageId = extractMessageId(msg.getMetadata());
            setToolContext(channel, chatId, messageId);

            // 取历史记录并构建 prompt messages
            List<Map<String, Object>> history = session.getHistory(memoryWindow);
            List<Map<String, Object>> initial = context.buildMessages(
                    history,
                    msg.getContent(),
                    null,
                    null,
                    channel,
                    chatId
            );

            // 跑 agent loop，然后保存 session，并回一条“后台任务完成”的消息
            return runAgentLoop(initial, null)
                    .thenApply(rr -> {
                        saveTurn(session, rr.messages, 1 + history.size());
                        sessions.save(session);
                        String finalContent = rr.finalContent != null ? rr.finalContent : "Background task completed.";
                        return new OutboundMessage(channel, chatId, finalContent, List.of(), Map.of());
                    });
        }

        // ----------------------------
        // 2) 普通用户消息：按 sessionKey 获取会话
        // ----------------------------
        String key = (sessionKeyOverride != null) ? sessionKeyOverride : msg.getSessionKey();
        Session session = sessions.getOrCreate(key);

        // ----------------------------
        // 3) Slash commands：/new /help 等
        // ----------------------------
        String cmd = msg.getContent() == null ? "" : msg.getContent().trim().toLowerCase(Locale.ROOT);

        if ("/new".equals(cmd)) {
            // /new：归档当前未 consolidate 的历史，然后清空 session
            return handleNewCommand(msg, session);
        }

        if ("/help".equals(cmd)) {
            // /help：返回命令列表
            return CompletableFuture.completedFuture(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    "🐈 nanobot commands:\n/new — Start a new conversation\n/stop — Stop the current task\n/help — Show available commands",
                    List.of(),
                    Map.of()
            ));
        }

        // ----------------------------
        // 4) 后台 consolidation：当未归档消息数超过阈值，异步触发记忆压缩
        // ----------------------------
        int unconsolidated = session.getMessages().size() - session.getLastConsolidated();

        // 当未归档 >= memoryWindow，且该 session 还未在 consolidating 集合中，则触发
        if (unconsolidated >= memoryWindow && !consolidating.contains(session.getKey())) {
            consolidating.add(session.getKey());
            ReentrantLock lock = getConsolidationLock(session.getKey());

            // 注意：consolidateMemory 是耗时的，所以放到后台线程执行，不阻塞当前消息处理
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    lock.lock();
                    // archiveAll=false：只归档到窗口大小，并更新 lastConsolidated 等
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

        // ----------------------------
        // 5) 设置工具上下文（路由信息）
        // ----------------------------
        String messageId = extractMessageId(msg.getMetadata());
        setToolContext(msg.getChannel(), msg.getChatId(), messageId);

        // ----------------------------
        // 6) MessageTool 每回合状态：startTurn()
        // ----------------------------
        // MessageTool 可能在本轮里“主动发送消息”，如果发送了则最终正常 reply 需要 suppress
        var mt = tools.get("message");
        if (mt instanceof MessageTool m) {
            m.startTurn();
        }

        // ----------------------------
        // 7) 构建 initialMessages：history + 本轮用户输入 + media -> messages
        // ----------------------------
        List<Map<String, Object>> history = session.getHistory(memoryWindow);
        List<Map<String, Object>> initialMessages = context.buildMessages(
                history,
                msg.getContent(),
                null,
                msg.getMedia(),
                msg.getChannel(),
                msg.getChatId()
        );

        // ----------------------------
        // 8) 默认 progress：向 bus 发“进度消息”（带 _progress 标记）
        // ----------------------------
        ProgressCallback busProgress = (content1, toolHint) -> {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (msg.getMetadata() != null) meta.putAll(msg.getMetadata());
            meta.put("_progress", true);
            meta.put("_tool_hint", toolHint);

            // 注意：progress 输出也走 publishOutbound，让渠道可以“边执行边输出”
            bus.publishOutbound(new OutboundMessage(
                    msg.getChannel(),
                    msg.getChatId(),
                    content1,
                    List.of(),
                    meta
            ));
        };

        // 外部传入 onProgress 优先，否则使用 busProgress
        ProgressCallback progress = (onProgress != null) ? onProgress : busProgress;

        // ----------------------------
        // 9) 进入 Agent Loop：runAgentLoop
        // ----------------------------
        return runAgentLoop(initialMessages, progress)
                .thenApply(rr -> {
                    String finalContent = rr.finalContent;
                    if (finalContent == null) {
                        finalContent = "I've completed processing but have no response to give.";
                    }

                    // 保存本轮对话到 session（skip = 1 + history.size()，避免重复保存历史部分）
                    saveTurn(session, rr.messages, 1 + history.size());
                    sessions.save(session);

                    // 如果本轮 MessageTool 已经主动发送过内容，则 suppress 正常回复
                    var mtool = tools.get("message");
                    if (mtool instanceof MessageTool m && m.isSentInTurn()) {
                        return null;
                    }

                    Map<String, Object> meta = msg.getMetadata() != null ? msg.getMetadata() : Map.of();
                    return new OutboundMessage(msg.getChannel(), msg.getChatId(), finalContent, List.of(), meta);
                });
    }

    /**
     * 处理 /new：
     * - 将“尚未 consolidate 的消息片段”做一次 archiveAll=true 的归档（用于持久化长期记忆）
     * - 然后清空 session，并 invalidate 缓存
     */
    private CompletionStage<OutboundMessage> handleNewCommand(InboundMessage msg, Session session) {
        ReentrantLock lock = getConsolidationLock(session.getKey());
        consolidating.add(session.getKey());

        CompletableFuture<OutboundMessage> out = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                lock.lock();

                // snapshot：取出 lastConsolidated 之后到当前的消息片段
                List<Map<String, Object>> snapshot = session.getMessages()
                        .subList(session.getLastConsolidated(), session.getMessages().size());

                // 如果有未归档内容，先归档（archiveAll=true）
                if (!snapshot.isEmpty()) {
                    Session temp = new Session(session.getKey());
                    temp.setMessages(new ArrayList<>(snapshot));
                    boolean ok = consolidateMemory(temp, true).toCompletableFuture().join();
                    if (!ok) {
                        // 归档失败则不清空 session，避免用户历史丢失
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

                // 清空会话并保存
                session.clear();
                sessions.save(session);

                // invalidate：让下次 getOrCreate 获取新的 session 实例
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

    /**
     * 从 metadata 中提取 message_id（不同渠道实现可能会带此字段）。
     */
    private static String extractMessageId(Map<String, Object> meta) {
        if (meta == null) return null;
        Object mid = meta.get("message_id");
        return mid == null ? null : String.valueOf(mid);
    }

    // ---------------------------------------------------------------------
    // Agent iteration loop：模型->工具->模型 的迭代（对应 Python _run_agent_loop）
    // ---------------------------------------------------------------------

    /**
     * runAgentLoop：
     * - 输入 initialMessages（已经包含系统/历史/用户输入等）
     * - 每一轮：
     *   1) provider.chat(...) 调用模型
     *   2) 如果模型返回 tool_calls：写入 assistant/tool_calls 到 messages，然后按顺序执行工具
     *   3) 工具结果以 role=tool 写入 messages
     *   4) 进入下一轮，让模型看到工具结果继续推理
     * - 如果模型没有 tool_calls：认为得到最终答案，结束循环
     */
    private CompletionStage<RunResult> runAgentLoop(
            List<Map<String, Object>> initialMessages,
            ProgressCallback onProgress
    ) {
        CompletableFuture<RunResult> out = new CompletableFuture<>();

        // messages：可变上下文，会在每轮追加 assistant/tool 等消息
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);

        // toolsUsed：本次处理过程中使用过的工具列表（用于统计/调试）
        List<String> toolsUsed = new ArrayList<>();

        // 内部状态：迭代次数、最终内容、是否结束
        class State {
            int iteration = 0;
            String finalContent = null;
            final AtomicBoolean done = new AtomicBoolean(false);
        }
        State st = new State();

        // 单步执行器：每执行一次代表一轮模型调用（必要时执行工具，然后再进入下一轮）
        Runnable step = new Runnable() {
            @Override
            public void run() {
                if (st.done.get()) return;

                st.iteration++;
                // 超过最大迭代次数，强制结束，避免死循环（例如模型不断要求工具但无法完成）
                if (st.iteration > maxIterations) {
                    st.finalContent = "I reached the maximum number of tool call iterations (" + maxIterations + ") "
                            + "without completing the task. You can try breaking the task into smaller steps.";
                    st.done.set(true);
                    out.complete(new RunResult(st.finalContent, toolsUsed, messages));
                    return;
                }

                // 调用模型：
                // - messages：上下文
                // - tools.getDefinitions()：工具 schema 列表
                // - model/maxTokens/temperature：参数
                provider.chat(messages, tools.getDefinitions(), model, maxTokens, temperature)
                        .whenComplete((resp, ex) -> {
                            if (st.done.get()) return;

                            if (ex != null) {
                                // 模型调用失败：结束并抛异常
                                st.done.set(true);
                                out.completeExceptionally(ex);
                                return;
                            }

                            // ----------------------------
                            // A) 如果模型返回了 tool_calls：进入工具执行分支
                            // ----------------------------
                            if (resp.hasToolCalls()) {
                                // 进度回调：先输出 clean 内容（如果有），再输出工具提示
                                if (onProgress != null) {
                                    String clean = stripThink(resp.getContent());
                                    if (clean != null) onProgress.onProgress(clean, false);
                                    // toolHint(...) 把工具调用列表变成一个给人看的提示文本（例如：正在调用xxx工具...）
                                    onProgress.onProgress(toolHint(resp.getToolCalls()), true);
                                }

                                // 把 tool_calls 转成 OpenAI messages 结构（assistant 消息里的 tool_calls 字段）
                                // 这样下一轮模型能看到“我刚刚调用了哪些工具”
                                List<Map<String, Object>> toolCallDicts = new ArrayList<>();
                                for (var tc : resp.getToolCalls()) {
                                    // function: { name, arguments }，arguments 在 OpenAI 结构中是 JSON 字符串
                                    Map<String, Object> fn = new LinkedHashMap<>();
                                    fn.put("name", tc.getName());
                                    fn.put("arguments", JsonUtil.toJson(tc.getArguments()));

                                    // tool_call: { id, type=function, function={...} }
                                    Map<String, Object> call = new LinkedHashMap<>();
                                    call.put("id", tc.getId());
                                    call.put("type", "function");
                                    call.put("function", fn);
                                    toolCallDicts.add(call);
                                }

                                // 将 assistant 消息写回 messages：
                                // - content：模型文字（可能为空）
                                // - tool_calls：模型请求的工具调用列表
                                // - reasoningContent：可选的“思考内容”（你这里会写入 messages 里，保存时会丢弃）
                                List<Map<String, Object>> updated = context.addAssistantMessage(
                                        messages,
                                        resp.getContent(),
                                        toolCallDicts,
                                        resp.getReasoningContent()
                                );
                                messages.clear();
                                messages.addAll(updated);

                                // 顺序执行所有工具调用：
                                // - tools.execute(...) 会返回 CompletionStage<String>（或等价）
                                // - 每个工具结果写入 messages 的 role=tool
                                executeToolCallsSequential(resp.getToolCalls(), toolsUsed, messages)
                                        .whenComplete((v, ex2) -> {
                                            if (st.done.get()) return;

                                            if (ex2 != null) {
                                                // 工具执行失败：结束并抛异常
                                                st.done.set(true);
                                                out.completeExceptionally(ex2);
                                            } else {
                                                // 工具执行完毕：进入下一轮
                                                // 关键点：把当前 Runnable(step) 再丢回 executor，
                                                // 让模型看到工具结果后继续推理/输出最终答案。
                                                executor.execute(this);
                                            }
                                        });
                                return;
                            }

                            // ----------------------------
                            // B) 没有 tool_calls：认为是最终答复
                            // ----------------------------
                            String reasoningContent = resp.getReasoningContent();
                            log.info("思考: \n" + reasoningContent);
                            String clean = stripThink(resp.getContent());

                            // NOTE: Python special-cases finish_reason == "error"（不保存到 session）
                            // 你当前 provider 抽象未暴露 finish_reason，这里先直接当最终结果处理

                            log.info("llm回复:\n" + clean);
                            if (onProgress != null && clean != null) {
                                // 也可以在最终输出前再推一次进度（让用户看到最终答案）
                                // by zcw 发现会出现两次最终输出
                                // onProgress.onProgress(clean, false);
                            }

                            // 把最终 assistant message 写入 messages
                            List<Map<String, Object>> updated = context.addAssistantMessage(
                                    messages, clean, null, resp.getReasoningContent()
                            );
                            messages.clear();
                            messages.addAll(updated);

                            // 标记完成并返回 RunResult
                            st.finalContent = clean;
                            st.done.set(true);
                            out.complete(new RunResult(st.finalContent, toolsUsed, messages));
                        });
            }
        };

        // 启动第一轮
        executor.execute(step);
        return out;
    }

    /**
     * 顺序执行工具调用（Sequential）：
     * - 一个工具执行完，再执行下一个
     * - 每个工具结果会写入 messages（role=tool）
     *
     * 这种方式的优点：
     * - 顺序确定，避免并发导致上下文/副作用不可控
     * - 与 Python 串行执行 tool calls 行为一致
     */
    private CompletionStage<Void> executeToolCallsSequential(
            List<ToolCallRequest> toolCalls,
            List<String> toolsUsed,
            List<Map<String, Object>> messages
    ) {
        // 初始链：一个已完成的 future
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);

        // 将每个工具调用串联到 chain 上
        for (var tc : toolCalls) {
            chain = chain.thenCompose(v -> {
                // 记录工具名（统计用途）
                toolsUsed.add(tc.getName());

                // 简短日志：打印工具名 + 参数预览（最多 200 字符）
                try {
                    String argsStr = JsonUtil.toJson(tc.getArguments());
                    String preview = argsStr.length() > 200 ? argsStr.substring(0, 200) : argsStr;
                    log.info("Tool call: {}({})", tc.getName(), preview);
                } catch (Exception ignored) {
                }

                // 执行工具：tools.execute 返回 CompletionStage<String/Result>
                return tools.execute(tc.getName(), tc.getArguments())
                        .thenAccept(result -> {
                            List<Map<String, Object>> updated = context.addToolResult(
                                    messages, tc.getId(), tc.getName(), result
                            );
                            messages.clear();
                            messages.addAll(updated);
                        })
                        .exceptionally(ex -> {
                            String err = formatToolError(tc.getName(), ex);
                            List<Map<String, Object>> updated = context.addToolResult(
                                    messages, tc.getId(), tc.getName(), err
                            );
                            messages.clear();
                            messages.addAll(updated);
                            return null;
                        });
            });
        }
        return chain;
    }

    private static String formatToolError(String toolName, Throwable ex) {
        Throwable root = (ex instanceof CompletionException || ex instanceof ExecutionException)
                ? ex.getCause()
                : ex;

        if (root instanceof TimeoutException) {
            return "{\"error\":\"tool_timeout\",\"tool\":\"" + toolName + "\"}";
        }
        return "{\"error\":\"tool_failed\",\"tool\":\"" + toolName + "\",\"message\":\"" +
                safeOneLine(root.toString()) + "\"}";
    }

    private static String safeOneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ");
    }

    // ---------------------------------------------------------------------
    // Session persistence：保存本轮 messages 到 Session（对应 Python _save_turn）
    // ---------------------------------------------------------------------

    /**
     * 将本轮新增 messages 保存到 session。
     *
     * @param session 会话对象
     * @param messages 本轮完整 messages（包含历史+新增）
     * @param skip 从 messages 的哪个 index 开始保存（用于跳过历史部分，避免重复存）
     */
    private void saveTurn(Session session, List<Map<String, Object>> messages, int skip) {
        for (int i = skip; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);

            // 复制一份 entry，并丢弃 reasoning_content（避免把“思考内容”持久化）
            Map<String, Object> entry = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (!"reasoning_content".equals(e.getKey())) {
                    entry.put(e.getKey(), e.getValue());
                }
            }

            Object role = entry.get("role");
            Object content = entry.get("content");

            // 避免保存“空 assistant 消息”且没有 tool_calls：
            // 有些 provider/模型可能会产生空 content 的 assistant message（会污染会话）
            if ("assistant".equals(String.valueOf(role))) {
                Object toolCalls = entry.get("tool_calls");
                boolean emptyContent = (content == null) || (content instanceof String s && s.isBlank());
                boolean noToolCalls = (toolCalls == null) || (toolCalls instanceof List<?> l && l.isEmpty());
                if (emptyContent && noToolCalls) {
                    continue;
                }
            }

            // tool 结果截断：防止 session 膨胀
            if ("tool".equals(String.valueOf(role)) && content instanceof String s) {
                if (s.length() > TOOL_RESULT_MAX_CHARS) {
                    entry.put("content", s.substring(0, TOOL_RESULT_MAX_CHARS) + "\n... (truncated)");
                }
            }

            // 用户消息中的 base64 图片替换为 [image]：
            // 有的多模态输入会把图片以内联 data:image/... 的形式塞进 content，存到 session 会非常大
            if ("user".equals(String.valueOf(role)) && content instanceof List<?> list) {
                List<Object> replaced = new ArrayList<>();
                for (Object c : list) {
                    if (c instanceof Map<?, ?> cm) {
                        Object type = cm.get("type");
                        Object imageUrl = cm.get("image_url");
                        if ("image_url".equals(String.valueOf(type)) && imageUrl instanceof Map<?, ?> im) {
                            Object url = im.get("url");
                            if (url instanceof String u && u.startsWith("data:image/")) {
                                replaced.add(Map.of("type", "text", "text", "[image]"));
                                continue;
                            }
                        }
                    }
                    replaced.add(c);
                }
                entry.put("content", replaced);
            }

            // 时间戳：如果没有 timestamp，则补上
            entry.putIfAbsent("timestamp", LocalDateTime.now().toString());

            // 写入 session 消息列表
            session.getMessages().add(entry);
        }

        // 更新 session 的更新时间
        session.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 触发记忆压缩/归档：
     * - MemoryStore.consolidate 可能会总结一部分历史，写入 session 的 memory/summary 等字段
     * - archiveAll=true 通常表示“把本次片段全部归档”为长期记忆
     */
    private CompletionStage<Boolean> consolidateMemory(Session session, boolean archiveAll) {
        return new MemoryStore(workspace).consolidate(session, provider, model, archiveAll, memoryWindow);
    }

    /**
     * 获取某个 session 的 consolidation 专用锁（不存在则创建）。
     */
    private ReentrantLock getConsolidationLock(String sessionKey) {
        return consolidationLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
    }

    /**
     * 清理锁：如果锁当前未被占用，则从 map 移除，避免 map 无限增长。
     */
    private void pruneConsolidationLock(String sessionKey, ReentrantLock lock) {
        if (lock != null && !lock.isLocked()) {
            consolidationLocks.remove(sessionKey, lock);
        }
    }

    // ---------------------------------------------------------------------
    // Public direct entry：对外直接调用（对应 Python process_direct）
    // ---------------------------------------------------------------------

    /**
     * 直接处理一条文本（不通过 bus.consumeInbound）：
     * 常用于测试、HTTP 接口、CLI 调用等。
     *
     * @param content 用户输入
     * @param sessionKey 会话 key（可空）
     * @param channel 渠道（可空，默认 cli）
     * @param chatId chatId（可空，默认 direct）
     * @param onProgress 进度回调（可空）
     */
    public CompletionStage<String> processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            ProgressCallback onProgress
    ) {
        return connectMcp()
                .thenCompose(v -> {
                    // 构造一个“伪 InboundMessage”，角色为 user
                    InboundMessage msg = new InboundMessage(
                            channel != null ? channel : "cli",
                            "user",
                            chatId != null ? chatId : "direct",
                            content,
                            null,
                            null
                    );
                    // sessionKey 如果为空则给个默认值
                    String key = (sessionKey != null && !sessionKey.isBlank()) ? sessionKey : "cli:direct";

                    // 走正常 processMessage 流程，最后只返回 content
                    return processMessage(msg, key, onProgress)
                            .thenApply(resp -> resp != null ? resp.getContent() : "");
                });
    }

    /**
     * 供外部读取渠道配置。
     */
    public ConfigSchema.ChannelsConfig getChannelsConfig() {
        return channelsConfig;
    }

    /**
     * 供外部读取当前使用的模型名。
     */
    public String getModel() {
        return model;
    }

    // ---------------------------------------------------------------------
    // 内部类型定义
    // ---------------------------------------------------------------------

    /**
     * 进度回调：
     * @param content 要输出的内容
     * @param toolHint true 表示这是工具提示文本（如“正在调用 xxx...”），false 表示普通内容
     */
    public interface ProgressCallback {
        void onProgress(String content, boolean toolHint);
    }

    /**
     * Agent Loop 执行结果：
     * - finalContent：最终输出（无工具调用时就是模型回复；有工具调用时是最后一轮的回复）
     * - toolsUsed：本次用过的工具名列表
     * - messages：完整 messages（包含历史 + 本轮 assistant/tool 等），可用于调试/保存
     */
    public static class RunResult {
        public final String finalContent;
        public final List<String> toolsUsed;
        public final List<Map<String, Object>> messages;

        public RunResult(String finalContent, List<String> toolsUsed, List<Map<String, Object>> messages) {
            this.finalContent = finalContent;
            this.toolsUsed = toolsUsed;
            this.messages = messages;
        }
    }

    /**
     * JsonUtil：用于把 arguments 转成 JSON 字符串（OpenAI tool_calls 要求 arguments 是 JSON string）
     * 注意：这里 catch 任何异常并 fallback 到 String.valueOf，避免工具参数序列化导致整体崩溃。
     */
    static class JsonUtil {
        private static final com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();

        static String toJson(Object o) {
            try {
                return M.writeValueAsString(o);
            } catch (Exception e) {
                return String.valueOf(o);
            }
        }
    }
}