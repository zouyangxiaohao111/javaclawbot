package agent.subagent;

import bus.InboundMessage;
import bus.MessageBus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.ConfigSchema;
import config.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 子代理管理器（统一门面）
 *
 * 职责：
 * 1) spawn：启动后台子任务（唯一入口）
 * 2) kill：终止子Agent（真正取消线程）
 * 3) steer：向子Agent发送指导消息
 * 4) list：列出子Agent
 * 5) cancelBySession：按会话批量取消
 *
 * 所有 Tool（SessionsSpawnTool、SubagentsControlTool）必须通过此类操作子Agent，
 * 不得直接操作 LocalSubagentExecutor 或 SubagentRegistry。
 *
 * @author zcw
 */
public class SubagentManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMProvider provider;
    private final Path workspace;
    private final MessageBus bus;

    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String reasoningEffort;
    private final ToolsConfig toolsConfig;
    private final boolean restrictToWorkspace;

    // ========== 核心组件 ==========
    private final SubagentRegistry registry;
    private final SubagentSystemPromptBuilder promptBuilder;
    private final SubagentAnnounceService announceService;
    private final LocalSubagentExecutor localExecutor;

    /** runId -> Future（后台任务，用于 cancel） */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> runningTasks = new ConcurrentHashMap<>();
    /** sessionKey -> {runId...}（用于按会话批量取消） */
    private final ConcurrentHashMap<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();

    /** steer操作限流：每个runId的上次操作时间 */
    private final ConcurrentHashMap<String, Long> steerRateLimit = new ConcurrentHashMap<>();
    private static final long STEER_RATE_LIMIT_MS = 2_000;

    private final Executor executor;

    public SubagentManager(
            LLMProvider provider,
            Path workspace,
            MessageBus bus,
            String model,
            Double temperature,
            Integer maxTokens,
            String reasoningEffort,
            ToolsConfig toolsConfig,
            boolean restrictToWorkspace,
            Executor executor
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.toolsConfig = toolsConfig;
        this.model = (model == null || model.isBlank()) ? provider.getDefaultModel() : model;
        this.temperature = (temperature == null) ? 0.7 : temperature;
        this.maxTokens = (maxTokens == null) ? 8192 : maxTokens;
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
        this.restrictToWorkspace = restrictToWorkspace;

        if (executor instanceof ExecutorService es) {
            this.executor = es;
        } else if (executor != null) {
            this.executor = executor;
        } else {
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
                            t.setName("subagent-" + idx.getAndIncrement());
                            t.setDaemon(false); // 关键：不要是 daemon
                            return t;
                        }
                    }
            );
        }

        // 初始化核心组件
        this.registry = SubagentRegistry.getInstance();
        this.registry.initPersistence(workspace);
        this.promptBuilder = new SubagentSystemPromptBuilder(workspace);
        this.announceService = new SubagentAnnounceService(bus, promptBuilder);
        this.localExecutor = new LocalSubagentExecutor(
                provider, workspace, toolsConfig, restrictToWorkspace,
                registry, bus
        );
        // 设置回引用，让嵌套 spawn 也走 Manager 统一入口
        this.localExecutor.setSubagentManager(this);
    }

    // ==========================
    // Spawn（唯一入口）
    // ==========================

    /**
     * 启动一个子代理后台任务
     *
     * @param task          要执行的任务（自然语言）
     * @param label         可选：展示用标签
     * @param originChannel 任务来源渠道
     * @param originChatId  任务来源 chat_id
     * @param sessionKey    会话键
     * @param mode          运行模式（run/session）
     * @param cleanup       清理策略（delete/keep）
     * @param timeoutSeconds 超时秒数
     * @return JSON 格式的启动结果
     */
    public CompletionStage<String> spawn(
            String task,
            String label,
            String originChannel,
            String originChatId,
            String sessionKey,
            SubagentRunRecord.SpawnMode mode,
            SubagentRunRecord.CleanupPolicy cleanup,
            int timeoutSeconds
    ) {
        // 解析请求者会话Key
        String requesterKey = (sessionKey != null && !sessionKey.isBlank())
                ? sessionKey
                : ((originChannel != null ? originChannel : "cli") + ":" + (originChatId != null ? originChatId : "direct"));

        // 计算子Agent深度
        int childDepth = registry.computeChildDepth(requesterKey);
        int maxDepth = registry.getMaxSpawnDepth();

        // 检查深度限制
        if (childDepth > maxDepth) {
            return CompletableFuture.completedFuture(
                    String.format("{\"status\":\"forbidden\",\"error\":\"Maximum spawn depth (%d) exceeded\"}", maxDepth)
            );
        }

        // 生成运行ID和子会话Key
        final String runId = uuid8();
        final String childSessionKey = requesterKey + ":" + runId;

        // 展示标签
        final String displayLabel = (label != null && !label.isBlank())
                ? label
                : shortLabel(task, 30);

        // 创建运行记录
        SubagentRunRecord record = new SubagentRunRecord(
                runId,
                childSessionKey,
                originChannel,
                originChatId,
                requesterKey,
                displayLabel,
                task,
                cleanup != null ? cleanup : SubagentRunRecord.CleanupPolicy.KEEP,
                mode != null ? mode : SubagentRunRecord.SpawnMode.RUN,
                childDepth
        );
        record.setModel(this.model);
        record.setWorkspaceDir(workspace.toString());
        record.setRunTimeoutSeconds(timeoutSeconds > 0 ? timeoutSeconds : 300);
        record.setExpectsCompletionMessage(true);

        // 注册到Registry
        registry.register(record);

        // 构建子Agent系统提示词
        SubagentSystemPromptBuilder.Params promptParams = new SubagentSystemPromptBuilder.Params()
                .task(task)
                .label(displayLabel)
                .requesterSessionKey(requesterKey)
                .requesterChannel(originChannel)
                .childSessionKey(childSessionKey)
                .childDepth(childDepth)
                .maxSpawnDepth(maxDepth);

        String systemPrompt = promptBuilder.build(promptParams);

        // 后台执行
        CompletableFuture<Void> bg = CompletableFuture.runAsync(() -> {
            try {
                // 标记开始
                registry.markStarted(runId);

                // 执行子Agent
                SubagentExecutionResult result = localExecutor.execute(record, systemPrompt)
                        .toCompletableFuture()
                        .join();

                // 更新记录
                record.setOutcome(result.getOutcome());
                record.setFrozenResultText(result.getResultText());
                registry.markEnded(runId, result.getOutcome());
                registry.updateFrozenResult(runId, result.getResultText());

                // 发送完成公告
                announceService.announceWithRetry(record).toCompletableFuture().join();

                log.info("Subagent [{}] completed: {}", runId, result.getOutcome().getStatusText());

            } catch (CancellationException ce) {
                log.info("Subagent [{}] cancelled", runId);
                record.setOutcome(SubagentOutcome.error("cancelled"));
                registry.markEnded(runId, SubagentOutcome.error("cancelled"));
            } catch (Throwable t) {
                log.error("Subagent [{}] crashed", runId, t);
                record.setOutcome(SubagentOutcome.error(t.getMessage()));
                registry.markEnded(runId, SubagentOutcome.error(t.getMessage()));
            }
        }, executor);

        // 维护 runningTasks 和 sessionTasks
        runningTasks.put(runId, bg);

        if (sessionKey != null && !sessionKey.isBlank()) {
            sessionTasks.compute(sessionKey, (k, set) -> {
                if (set == null) set = ConcurrentHashMap.newKeySet();
                set.add(runId);
                return set;
            });
        }

        bg.whenComplete((v, ex) -> {
            runningTasks.remove(runId);
            if (sessionKey != null && !sessionKey.isBlank()) {
                sessionTasks.computeIfPresent(sessionKey, (k, set) -> {
                    set.remove(runId);
                    return set.isEmpty() ? null : set;
                });
            }
        });

        log.info("Spawned subagent [{}]: {} (depth: {}, mode: {})", runId, displayLabel, childDepth, mode);

        // 返回JSON结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "accepted");
        result.put("runId", runId);
        result.put("childSessionKey", childSessionKey);
        result.put("mode", (mode != null ? mode : SubagentRunRecord.SpawnMode.RUN).name().toLowerCase());
        result.put("depth", childDepth);
        result.put("note", "自动公告是推送模式。启动子代理后，不要调用list、 sessions_list、sessions_history、exec sleep 或任何查询子代理状态工具。等待完成事件作为用户消息到达。");
        result.put("text", String.format("子代理 [%s] 已启动 (id: %s)。完成时会通知您。", displayLabel, runId));

        log.info("子代理已启动, 结果:{}", result);
        return CompletableFuture.completedFuture(toJson(result));
    }

    // ==========================
    // Kill（真正终止线程）
    // ==========================

    /**
     * 终止指定子Agent
     *
     * 通过 terminateSignal + cancel Future 真正停止子Agent线程，
     * 而不是仅发送消息。
     *
     * @param runId 运行ID
     * @return 是否成功
     */
    public CompletionStage<Boolean> killSubagent(String runId) {
        SubagentRunRecord record = registry.get(runId);
        if (record == null) {
            log.warn("Cannot kill: run not found: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        if (!record.isRunning()) {
            log.warn("Cannot kill: run not active: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        // 1. 设置终止信号（agent loop 检查此信号退出）
        localExecutor.terminate(runId);

        // 2. 取消 Future
        CompletableFuture<Void> f = runningTasks.get(runId);
        if (f != null && !f.isDone()) {
            f.cancel(true);
        }

        // 3. 更新状态
        record.setOutcome(SubagentOutcome.error("killed"));
        record.setEndedAt(LocalDateTime.now());

        log.info("Killed subagent: {}", runId);
        return CompletableFuture.completedFuture(true);
    }

    /**
     * 终止指定会话的所有子Agent
     *
     * @param requesterSessionKey 请求者会话Key
     * @return 终止的数量
     */
    public CompletionStage<Integer> killAllBySession(String requesterSessionKey) {
        List<SubagentRunRecord> activeRuns = registry.listActiveByRequester(requesterSessionKey);

        if (activeRuns.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        int[] count = {0};
        for (SubagentRunRecord record : activeRuns) {
            boolean killed = killSubagent(record.getRunId())
                    .toCompletableFuture().join();
            if (killed) count[0]++;
        }

        return CompletableFuture.completedFuture(count[0]);
    }

    /**
     * 取消某个 session 下的所有子代理（内部使用，由 AgentLoop 调用）
     */
    public CompletionStage<Integer> cancelBySession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }

        Set<String> ids = sessionTasks.getOrDefault(sessionKey, Set.of());
        if (ids.isEmpty()) return CompletableFuture.completedFuture(0);

        int[] count = {0};

        for (String tid : ids) {
            CompletableFuture<Void> f = runningTasks.get(tid);
            if (f != null && !f.isDone()) {
                f.cancel(true);
                count[0]++;
            }
            localExecutor.terminate(tid);
        }

        // 直接返回，不等待任务完成（避免阻塞）
        return CompletableFuture.completedFuture(count[0]);
    }

    // ==========================
    // Steer（向子Agent发送指导消息）
    // ==========================

    /**
     * 向运行中的子Agent发送指导消息
     *
     * 通过 MessageBus 注入控制消息到子Agent的会话。
     *
     * @param runId   运行ID
     * @param message 指导消息
     * @return 是否成功
     */
    public CompletionStage<Boolean> steerSubagent(String runId, String message) {
        SubagentRunRecord record = registry.get(runId);
        if (record == null) {
            log.warn("Cannot steer: run not found: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        if (!record.isRunning()) {
            log.warn("Cannot steer: run not active: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        // 限流检查
        Long lastSteer = steerRateLimit.get(runId);
        long now = System.currentTimeMillis();
        if (lastSteer != null && (now - lastSteer) < STEER_RATE_LIMIT_MS) {
            log.warn("Steer rate limited for run: {}", runId);
            return CompletableFuture.completedFuture(false);
        }
        steerRateLimit.put(runId, now);

        // 发送控制消息到子Agent会话
        String childSessionKey = record.getChildSessionKey();
        String channel = "cli";
        String chatId = childSessionKey;

        if (childSessionKey.contains(":")) {
            String[] parts = childSessionKey.split(":", 2);
            channel = parts[0];
            chatId = parts.length > 1 ? parts[1] : childSessionKey;
        }

        String content = "[Steer] " + message;

        InboundMessage msg = new InboundMessage(
                "system",
                "subagent_control",
                channel + ":" + chatId,
                content,
                null,
                Map.of(
                        "_subagent_control", true,
                        "_action", "steer",
                        "_run_id", record.getRunId()
                )
        );

        return bus.publishInbound(msg)
                .thenApply(v -> {
                    log.info("Steered subagent: {} with message: {}", runId, SubagentUtils.truncate(message, 50));
                    return true;
                })
                .exceptionally(ex -> {
                    log.error("Failed to steer subagent: {}", runId, ex);
                    return false;
                });
    }

    // ==========================
    // List
    // ==========================

    /**
     * 列出指定会话的子Agent
     */
    public List<SubagentRunRecord> listSubagents(String sessionKey) {
        return registry.listByRequester(sessionKey);
    }

    // ==========================
    // 工具方法
    // ==========================

    private static String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String shortLabel(String task, int max) {
        if (task == null) return "";
        String t = task;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }


    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    /**
     * 关闭管理器
     */
    public void shutdown() {
        localExecutor.shutdown();
        registry.flushPersistence();
    }
}
