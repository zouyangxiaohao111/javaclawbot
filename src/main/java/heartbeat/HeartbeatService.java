package heartbeat;

import providers.LLMProvider;
import providers.ToolCallRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 心跳服务：周期性唤醒代理检查是否存在待执行任务
 *
 * 语义严格对齐 Python 源码：
 * - 工具定义：heartbeat(action=skip/run, tasks=摘要)
 * - Phase 1（决策）：读取 HEARTBEAT.md -> 通过“虚拟工具调用”让模型返回 skip/run
 * - Phase 2（执行）：仅当 run 时调用 onExecute 执行；若有返回且 onNotify 存在则推送
 *
 * 循环行为对齐：
 * - Python 先 sleep(interval) 再 tick
 * - Java 使用 scheduleWithFixedDelay，设置 initialDelay=intervalSeconds，保持同样“先等再跑”
 */
public final class HeartbeatService {

    private static final Logger LOG = Logger.getLogger(HeartbeatService.class.getName());

    /**
     * 心跳工具定义（用于模型工具调用）
     *
     * 结构保持与 Python 一致：
     * - 工具名：heartbeat
     * - 参数：action（skip/run），tasks（run 时给出任务摘要）
     */
    private static final List<Map<String, Object>> HEARTBEAT_TOOL = buildHeartbeatTool();

    private final Path workspace;
    private final LLMProvider provider;
    private final String model;

    /**
     * 执行回调：入参为任务摘要，返回执行结果文本
     * Python: on_execute: Callable[[str], Coroutine[..., str]] | None
     */
    private final Function<String, CompletableFuture<String>> onExecute;

    /**
     * 通知回调：入参为需要推送的文本
     * Python: on_notify: Callable[[str], Coroutine[..., None]] | None
     */
    private final Function<String, CompletableFuture<Void>> onNotify;

    /** 间隔秒数（默认 30 分钟） */
    private final int intervalSeconds;

    /** 是否启用心跳 */
    private final boolean enabled;

    /** 运行标记（对应 Python 的 self._running） */
    private volatile boolean running = false;

    /** 定时执行器（对应 Python 的 asyncio.create_task + 循环 sleep） */
    private ScheduledExecutorService scheduler;

    /**
     * @param workspace       工作区目录
     * @param provider        模型提供者
     * @param model           模型名称
     * @param onExecute       执行回调（可为 null）
     * @param onNotify        通知回调（可为 null）
     * @param intervalSeconds 间隔秒数
     * @param enabled         是否启用
     */
    public HeartbeatService(
            Path workspace,
            LLMProvider provider,
            String model,
            Function<String, CompletableFuture<String>> onExecute,
            Function<String, CompletableFuture<Void>> onNotify,
            int intervalSeconds,
            boolean enabled
    ) {
        this.workspace = workspace;
        this.provider = provider;
        this.model = model;
        this.onExecute = onExecute;
        this.onNotify = onNotify;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
    }

    /**
     * 便捷构造：默认每 30 分钟一次
     */
    public HeartbeatService(
            Path workspace,
            LLMProvider provider,
            String model,
            Function<String, CompletableFuture<String>> onExecute,
            Function<String, CompletableFuture<Void>> onNotify
    ) {
        this(workspace, provider, model, onExecute, onNotify, 30 * 60, true);
    }

    /**
     * 心跳文件路径：{workspace}/HEARTBEAT.md
     */
    public Path getHeartbeatFile() {
        return workspace.resolve("HEARTBEAT.md");
    }

    /**
     * 启动心跳服务（对齐 Python 的 start）
     *
     * Python 行为：
     * - enabled=false：日志提示并返回
     * - 已经 running：warning 并返回
     * - 启动后：后台循环每 interval_s 触发一次 tick（先 sleep 再 tick）
     */
    public synchronized CompletableFuture<Void> start() {
        if (!enabled) {
            LOG.log(Level.INFO, "Heartbeat disabled");
            return CompletableFuture.completedFuture(null);
        }
        if (running) {
            LOG.log(Level.WARNING, "Heartbeat already running");
            return CompletableFuture.completedFuture(null);
        }

        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nanobot-heartbeat");
            t.setDaemon(true);
            return t;
        });

        // 对齐 Python：先等待 intervalSeconds，再执行 tick
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running) return;
            try {
                tick().join();
            } catch (CancellationException ignored) {
                // 对齐 Python：CancelledError 时直接退出/忽略
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Heartbeat error: " + e.getMessage(), e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        LOG.log(Level.INFO, "Heartbeat started (every {0}s)", intervalSeconds);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 停止心跳服务（对齐 Python 的 stop）
     *
     * Python：
     * - self._running = False
     * - if self._task: cancel
     */
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * 手动触发一次心跳（对齐 Python 的 trigger_now）
     *
     * Python：
     * - 读取 HEARTBEAT.md，不存在/空 => None
     * - 决策不是 run 或 on_execute 不存在 => None
     * - 否则执行 on_execute(tasks) 并返回其结果
     */
    public CompletableFuture<String> triggerNow() {
        String content = readHeartbeatFile();
        if (content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        return decide(content).thenCompose(decision -> {
            if (!"run".equals(decision.action()) || onExecute == null) {
                return CompletableFuture.completedFuture(null);
            }
            return onExecute.apply(decision.tasks());
        });
    }

    // -------------------- 内部逻辑 --------------------

    /**
     * 单次心跳 tick（对齐 Python 的 _tick）
     *
     * Python：
     * - content 为空：debug 日志并 return
     * - 调 _decide
     * - action != run：info OK 并 return
     * - run：info tasks found
     *   - 若 on_execute：执行 -> 若 response 非空且 on_notify：推送
     * - 异常：logger.exception
     */
    private CompletableFuture<Void> tick() {
        String content = readHeartbeatFile();
        if (content == null || content.isBlank()) {
            // Python 这里是 debug 级别；java.util.logging 没有 debug，使用 FINE
            LOG.log(Level.FINE, "Heartbeat: HEARTBEAT.md missing or empty");
            return CompletableFuture.completedFuture(null);
        }

        LOG.log(Level.INFO, "Heartbeat: checking for tasks...");

        return decide(content)
                .thenCompose(decision -> {
                    String action = decision.action();
                    String tasks = decision.tasks();

                    if (!"run".equals(action)) {
                        LOG.log(Level.INFO, "Heartbeat: OK (nothing to report)");
                        return CompletableFuture.completedFuture(null);
                    }

                    LOG.log(Level.INFO, "Heartbeat: tasks found, executing...");

                    if (onExecute == null) {
                        // Python：如果 on_execute 不存在则什么都不做
                        return CompletableFuture.completedFuture(null);
                    }

                    return onExecute.apply(tasks)
                            .thenCompose(result -> {
                                // Python：if response and self.on_notify
                                if (result != null && !result.isBlank() && onNotify != null) {
                                    LOG.log(Level.INFO, "Heartbeat: completed, delivering response");
                                    return onNotify.apply(result);
                                }
                                return CompletableFuture.completedFuture(null);
                            });
                })
                .exceptionally(ex -> {
                    // 对齐 Python：异常时记录堆栈
                    LOG.log(Level.SEVERE, "Heartbeat execution failed: " + ex.getMessage(), ex);
                    return null;
                });
    }

    /**
     * 读取 HEARTBEAT.md 内容（读取失败返回 null）
     *
     * Python：
     * - 文件存在则 read_text；异常返回 None
     * - 文件不存在返回 None
     */
    private String readHeartbeatFile() {
        Path file = getHeartbeatFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Phase 1：让模型通过工具调用返回决策（对齐 Python 的 _decide）
     *
     * 返回：
     * - action：skip 或 run
     * - tasks：任务摘要（run 时建议给出）
     *
     * 注意：
     * - Python 若没有工具调用 => ("skip","")
     * - args.get("tasks") 若不存在 => ""
     */
    private CompletableFuture<HeartbeatDecision> decide(String content) {
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put(
                "content",
                "You are a heartbeat agent. Call the heartbeat tool to report your decision."
        );
        messages.add(system);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put(
                "content",
                "Review the following HEARTBEAT.md and decide whether there are active tasks.\n\n"
                        + content
        );
        messages.add(user);

        // Python：provider.chat(messages=[...], tools=_HEARTBEAT_TOOL, model=self.model)
        // Java：按你工程现有签名调用（这里不额外引入 max_tokens / temperature 的语义差异）
        return provider.chat(messages, HEARTBEAT_TOOL, model, 4096, 0.7)
                .thenApply(resp -> {
                    if (resp == null || !resp.hasToolCalls()) {
                        return new HeartbeatDecision("skip", "");
                    }

                    List<ToolCallRequest> toolCalls = resp.getToolCalls();
                    if (toolCalls == null || toolCalls.isEmpty()) {
                        return new HeartbeatDecision("skip", "");
                    }

                    Map<String, Object> args = toolCalls.get(0).getArguments();
                    if (args == null) {
                        return new HeartbeatDecision("skip", "");
                    }

                    // 对齐 Python：缺省值分别是 "skip" 和 ""
                    String action = Objects.toString(args.get("action"), "skip");
                    String tasks = Objects.toString(args.get("tasks"), "");
                    return new HeartbeatDecision(action, tasks);
                })
                .exceptionally(ex -> new HeartbeatDecision("skip", ""));
    }

    /**
     * 心跳决策结果
     */
    private record HeartbeatDecision(String action, String tasks) {}

    /**
     * 构造 heartbeat 工具定义（保持与 Python 常量 _HEARTBEAT_TOOL 一致）
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildHeartbeatTool() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "heartbeat");
        function.put("description", "Report heartbeat decision after reviewing tasks.");

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("skip", "run"));
        action.put("description", "skip = nothing to do, run = has active tasks");
        properties.put("action", action);

        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("type", "string");
        tasks.put("description", "Natural-language summary of active tasks (required for run)");
        properties.put("tasks", tasks);

        parameters.put("properties", properties);
        parameters.put("required", List.of("action"));

        function.put("parameters", parameters);
        tool.put("function", function);

        return List.of(tool);
    }
}