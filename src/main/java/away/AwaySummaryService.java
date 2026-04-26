package away;

import config.agent.AwaySummaryConfig;
import config.agent.SessionMemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import session.SessionMemoryService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Away Summary Service
 * 对齐 Open-ClaudeCode: src/hooks/useAwaySummary.ts
 *
 * 用户离开后返回时生成"离开时发生了什么"摘要。
 *
 * 状态机：
 * - 用户离开 → 启动定时器
 * - 任务完成 → 检查离开时间
 * - 定时器触发 → 生成摘要
 * - 用户回来 → 显示摘要
 */
public class AwaySummaryService {

    private static final Logger log = LoggerFactory.getLogger(AwaySummaryService.class);

    private final AwaySummaryConfig config;
    private final SessionMemoryConfig sessionMemoryConfig;
    private final SessionMemoryService sessionMemoryService;
    private final AwaySummaryGenerator generator;
    private final ScheduledExecutorService scheduler;

    /**
     * 状态（按 session 隔离）
     */
    private final ConcurrentHashMap<String, AwaySummaryState> states = new ConcurrentHashMap<>();

    /**
     * 待处理的定时器
     */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingTimers = new ConcurrentHashMap<>();

    public AwaySummaryService(
            AwaySummaryConfig config,
            SessionMemoryConfig sessionMemoryConfig,
            SessionMemoryService sessionMemoryService,
            providers.LLMProvider provider) {
        this.config = config;
        this.sessionMemoryConfig = sessionMemoryConfig;
        this.sessionMemoryService = sessionMemoryService;
        this.generator = new AwaySummaryGenerator(provider);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("away-summary-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 检查功能是否启用
     */
    public boolean isEnabled() {
        if (!config.isEffectivelyEnabled()) {
            return false;
        }

        // 如果强依赖 Session Memory，检查其是否启用
        if (config.isRequireSessionMemory() && !sessionMemoryConfig.isEffectivelyEnabled()) {
            return false;
        }

        return true;
    }

    /**
     * 获取或创建状态
     */
    private AwaySummaryState getOrCreateState(String sessionKey) {
        return states.computeIfAbsent(sessionKey, k -> new AwaySummaryState());
    }

    /**
     * 用户离开
     * 对齐: onBlurTimerFire() in useAwaySummary.ts
     */
    public void onUserAway(String sessionKey) {
        if (!isEnabled()) {
            return;
        }

        AwaySummaryState state = getOrCreateState(sessionKey);
        state.setAway(true);
        state.setAwayStartTime(System.currentTimeMillis());

        // 启动定时器
        int idleMinutes = config.getEffectiveIdleMinutes();
        long delayMs = idleMinutes * 60 * 1000L;

        log.debug("User away for session {}, starting {} minute timer", sessionKey, idleMinutes);

        // 取消之前的定时器
        ScheduledFuture<?> existing = pendingTimers.get(sessionKey);
        if (existing != null) {
            existing.cancel(false);
        }

        // 启动新定时器
        ScheduledFuture<?> future = scheduler.schedule(
                () -> onAwayTimerExpired(sessionKey),
                delayMs,
                TimeUnit.MILLISECONDS
        );
        pendingTimers.put(sessionKey, future);
    }

    /**
     * 定时器触发
     */
    private void onAwayTimerExpired(String sessionKey) {
        AwaySummaryState state = states.get(sessionKey);
        if (state == null || !state.isAway()) {
            return;
        }

        // 生成摘要
        generateSummary(sessionKey, state);

        pendingTimers.remove(sessionKey);
    }

    /**
     * 生成摘要
     */
    private void generateSummary(String sessionKey, AwaySummaryState state) {
        // 如果用户已经回来，取消
        if (!state.isAway()) {
            return;
        }

        // 获取 Session Memory 内容（可选）
        String sessionMemory = null;
        if (sessionMemoryService != null && sessionMemoryConfig.isEffectivelyEnabled()) {
            sessionMemory = sessionMemoryService.getContent(sessionKey);
        }

        // 生成摘要（同步执行）
        String summary = generator.generate(
                getRecentMessages(sessionKey),
                sessionMemory,
                () -> state.isAway()  // 取消检查：用户回来则取消
        );

        if (summary != null && !summary.isBlank()) {
            state.setGeneratedSummary(summary);
            state.setSummaryReady(true);
            log.info("Away summary generated for session {}", sessionKey);
        }
    }

    /**
     * 用户回来
     * 对齐: onFocusChange() in useAwaySummary.ts
     */
    public void onUserBack(String sessionKey) {
        if (!isEnabled()) {
            return;
        }

        AwaySummaryState state = states.get(sessionKey);
        if (state == null) {
            return;
        }

        // 取消待处理的定时器
        ScheduledFuture<?> future = pendingTimers.remove(sessionKey);
        if (future != null) {
            future.cancel(false);
        }

        // 如果有已生成的摘要，触发显示
        if (state.isSummaryReady()) {
            showSummary(sessionKey, state);
        }

        // 重置离开状态
        state.setAway(false);
        state.setAwayStartTime(0);
    }

    /**
     * 任务开始
     */
    public void onTaskStart(String sessionKey) {
        AwaySummaryState state = getOrCreateState(sessionKey);
        state.setLoading(true);
    }

    /**
     * 任务完成
     * 对齐: useAwaySummary.ts 中的任务完成处理
     */
    public void onTaskComplete(String sessionKey) {
        AwaySummaryState state = states.get(sessionKey);
        if (state == null) {
            return;
        }

        state.setLoading(false);
        state.setTaskCompleteTime(System.currentTimeMillis());

        // 如果用户已离开，检查是否需要生成摘要
        if (state.isAway()) {
            long awayDuration = System.currentTimeMillis() - state.getAwayStartTime();
            int idleMinutes = config.getEffectiveIdleMinutes();
            long idleMs = idleMinutes * 60 * 1000L;

            if (awayDuration >= idleMs) {
                // 用户离开超过阈值，标记待处理摘要
                state.setPendingAwaySummary(true);
                log.debug("Task completed while user away for {} minutes, pending summary",
                        awayDuration / 60000);
            } else {
                // 用户只是短暂离开，取消定时器
                ScheduledFuture<?> future = pendingTimers.remove(sessionKey);
                if (future != null) {
                    future.cancel(false);
                    log.debug("User briefly away ({}) minutes, cancelling timer",
                            awayDuration / 60000);
                }
            }
        }
    }

    /**
     * 显示摘要给用户
     */
    private void showSummary(String sessionKey, AwaySummaryState state) {
        // 通知用户
        if (summaryCallback != null && state.getGeneratedSummary() != null) {
            summaryCallback.onSummaryReady(sessionKey, state.getGeneratedSummary());
        }

        // 重置摘要状态
        state.setSummaryReady(false);
    }

    /**
     * 获取当前摘要（如果已准备好）
     */
    public String getPendingSummary(String sessionKey) {
        AwaySummaryState state = states.get(sessionKey);
        if (state != null && state.isSummaryReady()) {
            return state.getGeneratedSummary();
        }
        return null;
    }

    /**
     * 消费摘要（获取并清除）
     */
    public String consumePendingSummary(String sessionKey) {
        AwaySummaryState state = states.get(sessionKey);
        if (state != null && state.isSummaryReady()) {
            String summary = state.getGeneratedSummary();
            state.setSummaryReady(false);
            return summary;
        }
        return null;
    }

    /**
     * 检查是否有待显示的摘要
     */
    public boolean hasPendingSummary(String sessionKey) {
        AwaySummaryState state = states.get(sessionKey);
        return state != null && state.isSummaryReady();
    }

    /**
     * 清除状态
     */
    public void clearState(String sessionKey) {
        ScheduledFuture<?> future = pendingTimers.remove(sessionKey);
        if (future != null) {
            future.cancel(false);
        }
        states.remove(sessionKey);
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    // ========== 回调接口 ==========

    /**
     * 摘要准备好时的回调
     */
    public interface SummaryCallback {
        void onSummaryReady(String sessionKey, String summary);
    }

    private SummaryCallback summaryCallback;

    public void setSummaryCallback(SummaryCallback callback) {
        this.summaryCallback = callback;
    }

    /**
     * 获取最近消息（子类实现或从外部注入）
     */
    protected List<Map<String, Object>> getRecentMessages(String sessionKey) {
        // 由子类或外部提供
        return List.of();
    }

    // ========== 快捷方法 ==========

    /**
     * 创建 AwaySummaryService（使用配置默认值）
     */
    public static AwaySummaryService create(
            providers.LLMProvider provider,
            SessionMemoryService sessionMemoryService) {
        return new AwaySummaryService(
                new AwaySummaryConfig(),
                new SessionMemoryConfig(),
                sessionMemoryService,
                provider
        );
    }
}
