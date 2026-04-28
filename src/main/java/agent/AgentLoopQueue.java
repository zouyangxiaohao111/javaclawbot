package agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Agent 运行队列：对齐 OpenClaw 的 Lane-aware FIFO 队列
 *
 * 核心机制：
 * - 全局 Lane：限制总并发数（maxConcurrent）
 * - 会话 Lane：保证单会话串行执行
 * - 队列模式：collect/steer/followup/interrupt
 */
public class AgentLoopQueue {

    private static final Logger LOG = LoggerFactory.getLogger(AgentLoopQueue.class);

    /** 默认全局最大并发数 */
    public static final int DEFAULT_MAX_CONCURRENT = 4;

    /** 默认队列容量 */
    public static final int DEFAULT_QUEUE_CAP = 20;

    /** 默认去抖动时间（毫秒） */
    public static final int DEFAULT_DEBOUNCE_MS = 1000;

    /** 队列模式 */
    public enum QueueMode {
        /** 合并消息，等待当前运行结束后处理（默认） */
        COLLECT,
        /** 立即注入到当前运行中 */
        STEER,
        /** 排队等待下一轮 */
        FOLLOWUP,
        /** 中断当前运行，处理新消息 */
        INTERRUPT
    }

    /** 队列配置 */
    public record QueueConfig(
        QueueMode mode,
        int debounceMs,
        int cap,
        DropPolicy drop
    ) {
        public enum DropPolicy {
            /** 丢弃旧消息 */
            OLD,
            /** 丢弃新消息 */
            NEW,
            /** 汇总丢弃的消息 */
            SUMMARIZE
        }

        public static QueueConfig defaultConfig() {
            return new QueueConfig(QueueMode.COLLECT, DEFAULT_DEBOUNCE_MS, DEFAULT_QUEUE_CAP, DropPolicy.SUMMARIZE);
        }
    }

    /** 排队任务 */
    public record QueuedTask<T>(
        String sessionId,
        String sessionKey,
        Supplier<CompletableFuture<T>> task,
        CompletableFuture<T> future,
        long enqueuedAt,
        String description
    ) {}

    /** 会话 Lane 状态 */
    private static class SessionLane {
        final Semaphore semaphore = new Semaphore(1);
        final BlockingQueue<QueuedTask<?>> queue = new LinkedBlockingQueue<>();
        volatile CompletableFuture<?> currentRun = null;
        volatile Thread currentThread = null;
        final AtomicInteger queueDepth = new AtomicInteger(0);
    }

    private final int maxConcurrent;
    private final Semaphore globalLane;
    private final Map<String, SessionLane> sessionLanes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Map<String, QueueConfig> sessionConfigs = new ConcurrentHashMap<>();
    private final QueueConfig defaultConfig;

    /**
     * 任务开始执行前的回调，用于清除会话的停止标记
     * 由 AgentLoop 设置，确保每次任务开始时清除之前的停止状态
     */
    private java.util.function.Consumer<String> onTaskStart;

    /**
     * 队列执行器（独立线程池，避免与 AgentLoop 共享导致死锁）
     *
     * 为什么需要独立线程池：
     * - executeImmediately 中使用 join() 等待任务完成
     * - 如果共享 AgentLoop 的 executor，线程会被 join() 阻塞
     * - AgentLoop 的 step 无法执行，造成死锁
     */
    private final ExecutorService queueExecutor;

    private volatile AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 完整构造函数
     *
     * @param maxConcurrent 最大并发数
     * @param defaultConfig 默认队列配置
     */
    public AgentLoopQueue(int maxConcurrent, QueueConfig defaultConfig) {
        this.maxConcurrent = maxConcurrent;
        this.globalLane = new Semaphore(maxConcurrent);
        this.defaultConfig = defaultConfig != null ? defaultConfig : QueueConfig.defaultConfig();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "agent-queue-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 独立线程池，专门用于队列调度
        this.queueExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-queue-executor");
            t.setDaemon(true);
            return t;
        });

        LOG.info("AgentLoopQueue initialized: maxConcurrent={}, defaultMode={}",
            maxConcurrent, this.defaultConfig.mode());
    }

    public AgentLoopQueue(int maxConcurrent) {
        this(maxConcurrent, null);
    }

    public AgentLoopQueue() {
        this(DEFAULT_MAX_CONCURRENT, null);
    }

    /**
     * 设置任务开始执行前的回调
     * @param onTaskStart 回调函数，参数为 sessionKey
     */
    public void setOnTaskStart(java.util.function.Consumer<String> onTaskStart) {
        this.onTaskStart = onTaskStart;
    }

    /**
     * 获取会话 Lane
     */
    private SessionLane getOrCreateLane(String sessionKey) {
        return sessionLanes.computeIfAbsent(sessionKey, k -> new SessionLane());
    }

    /**
     * 获取队列深度
     */
    public int getQueueDepth(String sessionKey) {
        SessionLane lane = sessionLanes.get(sessionKey);
        return lane != null ? lane.queueDepth.get() : 0;
    }

    /**
     * 获取全局并发数
     */
    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    /**
     * 获取当前活跃会话数
     */
    public int getActiveSessionCount() {
        return (int) sessionLanes.values().stream()
            .filter(lane -> lane.currentRun != null && !lane.currentRun.isDone())
            .count();
    }

    /**
     * 设置会话队列配置
     */
    public void setSessionConfig(String sessionKey, QueueConfig config) {
        if (config == null) {
            sessionConfigs.remove(sessionKey);
        } else {
            sessionConfigs.put(sessionKey, config);
        }
    }

    /**
     * 获取会话队列配置
     */
    public QueueConfig getSessionConfig(String sessionKey) {
        return sessionConfigs.getOrDefault(sessionKey, defaultConfig);
    }

    /**
     * 入队任务（使用默认配置）
     */
    public <T> CompletableFuture<T> enqueue(
            String sessionKey,
            Supplier<CompletableFuture<T>> task,
            String description
    ) {
        return enqueue(sessionKey, sessionKey, task, description);
    }

    /**
     * 入队任务
     */
    public <T> CompletableFuture<T> enqueue(
            String sessionId,
            String sessionKey,
            Supplier<CompletableFuture<T>> task,
            String description
    ) {
        if (!running.get()) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException("Queue is shutting down"));
            return failed;
        }

        SessionLane lane = getOrCreateLane(sessionKey);
        QueueConfig config = getSessionConfig(sessionKey);

        // 检查是否有正在运行的任务
        CompletableFuture<?> currentRun = lane.currentRun;
        if (currentRun != null && !currentRun.isDone()) {
            // 有正在运行的任务，根据模式处理
            switch (config.mode()) {
                case INTERRUPT:
                    return handleInterrupt(lane, sessionId, sessionKey, task, description);
                case STEER:
                    // TODO: 实现 steer 模式（注入到当前运行）
                    // 暂时降级为 FOLLOWUP
                    LOG.debug("STEER mode not implemented, falling back to FOLLOWUP for session={}", sessionKey);
                    return enqueueFollowup(lane, sessionId, sessionKey, task, description, config);
                case COLLECT:
                case FOLLOWUP:
                default:
                    return enqueueFollowup(lane, sessionId, sessionKey, task, description, config);
            }
        }

        // 没有正在运行的任务，直接执行
        return executeImmediately(lane, sessionId, sessionKey, task, description);
    }

    /**
     * 处理 INTERRUPT 模式
     */
    private <T> CompletableFuture<T> handleInterrupt(
            SessionLane lane,
            String sessionId,
            String sessionKey,
            Supplier<CompletableFuture<T>> task,
            String description
    ) {
        CompletableFuture<?> currentRun = lane.currentRun;
        if (currentRun != null && !currentRun.isDone()) {
            LOG.info("Interrupting current run for session={}", sessionKey);
            
            // 取消当前运行
            currentRun.cancel(true);
            if (lane.currentThread != null) {
                lane.currentThread.interrupt();
            }
        }

        // 执行新任务
        return executeImmediately(lane, sessionId, sessionKey, task, description);
    }

    /**
     * 入队等待后续处理
     */
    private <T> CompletableFuture<T> enqueueFollowup(
            SessionLane lane,
            String sessionId,
            String sessionKey,
            Supplier<CompletableFuture<T>> task,
            String description,
            QueueConfig config
    ) {
        int depth = lane.queueDepth.incrementAndGet();
        
        // 检查队列容量
        if (depth > config.cap()) {
            lane.queueDepth.decrementAndGet();
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException(
                "Queue capacity exceeded for session: " + sessionKey));
            return failed;
        }

        QueuedTask<T> queuedTask = new QueuedTask<>(
            sessionId, sessionKey, task, new CompletableFuture<>(), 
            System.currentTimeMillis(), description
        );

        lane.queue.offer(queuedTask);
        LOG.debug("Enqueued task for session={}, depth={}, mode={}", 
            sessionKey, depth, config.mode());

        // 安排执行
        scheduleExecution(lane, sessionKey, config);

        return queuedTask.future();
    }

    /**
     * 立即执行
     */
    private <T> CompletableFuture<T> executeImmediately(
            SessionLane lane,
            String sessionId,
            String sessionKey,
            Supplier<CompletableFuture<T>> task,
            String description
    ) {
        CompletableFuture<T> result = new CompletableFuture<>();
        lane.currentRun = result;

        // 使用共享的 executor，避免 ForkJoinPool.commonPool() 被阻塞
        CompletableFuture.runAsync(() -> {
            lane.currentThread = Thread.currentThread();

            // 任务开始前回调，清除该会话的停止标记
            // 确保新任务不受之前 /stop 命令影响
            // 注意：dispatch 中也会清除，但通过 queue 排队的任务需要在这里清除
            if (onTaskStart != null) {
                onTaskStart.accept(sessionKey);
            }
            long startedAt = System.currentTimeMillis();

            try {
                // 获取全局 Lane 许可
                globalLane.acquire();
                try {
                    // 获取会话 Lane 许可
                    lane.semaphore.acquire();
                    try {
                        long waitedMs = System.currentTimeMillis() - startedAt;
                        if (waitedMs > 2000) {
                            LOG.info("Task started after {}ms wait: session={}, desc={}",
                                waitedMs, sessionKey, description);
                        }

                        // 执行任务
                        CompletableFuture<T> taskFuture = task.get();
                        T value = taskFuture.join();
                        result.complete(value);

                    } finally {
                        lane.semaphore.release();
                    }
                } finally {
                    globalLane.release();
                }
            } catch (Throwable e) {
                if (e instanceof InterruptedException || e instanceof CancellationException) {
                    result.cancel(true);
                } else {
                    result.completeExceptionally(e);
                }
            } finally {
                lane.currentThread = null;
                lane.currentRun = null;

                // 检查是否有排队任务
                processNextInQueue(lane, sessionKey);
            }
        }, this.queueExecutor);  // 使用独立线程池，避免与 AgentLoop 共享导致死锁

        return result;
    }

    /**
     * 安排执行（带去抖动）
     */
    private void scheduleExecution(SessionLane lane, String sessionKey, QueueConfig config) {
        if (config.debounceMs() > 0) {
            scheduler.schedule(() -> {
                processNextInQueue(lane, sessionKey);
            }, config.debounceMs(), TimeUnit.MILLISECONDS);
        } else {
            scheduler.submit(() -> processNextInQueue(lane, sessionKey));
        }
    }

    /**
     * 处理队列中的下一个任务
     */
    private void processNextInQueue(SessionLane lane, String sessionKey) {
        if (!running.get()) return;
        
        // 检查是否有正在运行的任务
        if (lane.currentRun != null && !lane.currentRun.isDone()) {
            return;
        }

        // 从队列取出任务
        QueuedTask<?> task = lane.queue.poll();
        if (task == null) {
            return;
        }

        lane.queueDepth.decrementAndGet();
        QueueConfig config = getSessionConfig(sessionKey);

        // 合并模式：收集所有排队任务
        if (config.mode() == QueueMode.COLLECT) {
            collectAndExecute(lane, task, sessionKey, config);
        } else {
            // 单独执行
            executeQueuedTask(lane, task);
        }
    }

    /**
     * 收集模式：合并所有排队任务
     */
    @SuppressWarnings("unchecked")
    private void collectAndExecute(
            SessionLane lane,
            QueuedTask<?> firstTask,
            String sessionKey,
            QueueConfig config
    ) {
        // 收集所有排队任务
        java.util.List<QueuedTask<?>> tasks = new java.util.ArrayList<>();
        tasks.add(firstTask);

        QueuedTask<?> next;
        while ((next = lane.queue.poll()) != null) {
            tasks.add(next);
            lane.queueDepth.decrementAndGet();
        }

        if (tasks.size() > 1) {
            LOG.debug("Collected {} tasks for session={}", tasks.size(), sessionKey);
        }

        // 执行第一个任务，其他任务等待结果
        executeQueuedTask(lane, tasks.get(0));

        // 其他任务共享结果
        if (tasks.size() > 1) {
            CompletableFuture<?> firstFuture = tasks.get(0).future();
            for (int i = 1; i < tasks.size(); i++) {
                CompletableFuture<Object> otherFuture = (CompletableFuture<Object>) tasks.get(i).future();
                firstFuture.whenComplete((result, error) -> {
                    if (error != null) {
                        otherFuture.completeExceptionally(error);
                    } else {
                        otherFuture.complete(result);
                    }
                });
            }
        }
    }

    /**
     * 执行排队的任务
     */
    @SuppressWarnings("unchecked")
    private <T> void executeQueuedTask(SessionLane lane, QueuedTask<T> task) {
        executeImmediately(lane, task.sessionId(), task.sessionKey(), task.task(), task.description())
            .whenComplete((result, error) -> {
                if (error != null) {
                    task.future().completeExceptionally(error);
                } else {
                    task.future().complete(result);
                }
            });
    }

    /**
     * 停止队列
     */
    public void shutdown() {
        running.compareAndSet(true, false);
        scheduler.shutdownNow();
        queueExecutor.shutdownNow();  // 关闭独立线程池

        // 取消所有排队任务
        for (SessionLane lane : sessionLanes.values()) {
            if (lane.currentRun != null && !lane.currentRun.isDone()) {
                lane.currentRun.cancel(true);
            }
            QueuedTask<?> task;
            while ((task = lane.queue.poll()) != null) {
                task.future().cancel(true);
            }
        }

        LOG.info("AgentLoopQueue shutdown");
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("maxConcurrent", maxConcurrent);
        stats.put("availablePermits", globalLane.availablePermits());
        stats.put("activeSessions", getActiveSessionCount());
        stats.put("totalSessions", sessionLanes.size());

        Map<String, Integer> queueDepths = new ConcurrentHashMap<>();
        sessionLanes.forEach((key, lane) -> {
            if (lane.queueDepth.get() > 0) {
                queueDepths.put(key, lane.queueDepth.get());
            }
        });
        stats.put("queueDepths", queueDepths);

        return stats;
    }
}