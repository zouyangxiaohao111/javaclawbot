package away;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Feishu/Gateway AwaySummary Handler
 *
 * 处理飞书渠道的 AwaySummary：
 * - 用户 N 分钟无消息 → 视为离开
 * - 用户再次发消息 → 视为回来
 */
public class FeishuAwaySummaryHandler {

    private static final Logger log = LoggerFactory.getLogger(FeishuAwaySummaryHandler.class);

    private final AwaySummaryService service;
    private final int idleMinutes;

    /**
     * 用户最后活跃时间追踪
     */
    private final ConcurrentHashMap<String, Long> userLastActiveTime = new ConcurrentHashMap<>();

    /**
     * 定时检查线程
     */
    private final ScheduledExecutorService checker;
    private ScheduledFuture<?> checkerTask;

    public FeishuAwaySummaryHandler(AwaySummaryService service, int idleMinutes) {
        this.service = service;
        this.idleMinutes = idleMinutes;
        this.checker = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r);
            t.setName("feishu-away-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动检查
     */
    public void start() {
        // 每分钟检查一次用户活跃状态
        checkerTask = checker.scheduleAtFixedRate(
                this::checkIdleUsers,
                1, 1, TimeUnit.MINUTES
        );
        log.info("FeishuAwaySummaryHandler started with {} minute idle threshold", idleMinutes);
    }

    /**
     * 停止检查
     */
    public void stop() {
        if (checkerTask != null) {
            checkerTask.cancel(false);
        }
        checker.shutdown();
    }

    /**
     * 处理用户消息
     * 调用此方法表示用户有活动
     */
    public void onUserMessage(String sessionKey) {
        long now = System.currentTimeMillis();
        Long lastActive = userLastActiveTime.get(sessionKey);

        if (lastActive != null) {
            long idleTime = now - lastActive;
            long idleMs = idleMinutes * 60 * 1000L;

            if (idleTime >= idleMs) {
                // 用户从空闲回来
                log.debug("User back from idle for session {}, idle for {} minutes",
                        sessionKey, idleTime / 60000);

                // 如果有待显示的摘要，触发显示
                if (service.hasPendingSummary(sessionKey)) {
                    String summary = service.consumePendingSummary(sessionKey);
                    if (summary != null && summaryCallback != null) {
                        summaryCallback.onSummaryReady(sessionKey, summary);
                    }
                }

                // 清除离开状态
                service.onUserBack(sessionKey);
            }
        }

        // 更新最后活跃时间
        userLastActiveTime.put(sessionKey, now);
    }

    /**
     * 用户离开（被动检测）
     */
    public void onUserAway(String sessionKey) {
        service.onUserAway(sessionKey);
    }

    /**
     * 检查空闲用户
     */
    private void checkIdleUsers() {
        long now = System.currentTimeMillis();
        long idleMs = idleMinutes * 60 * 1000L;

        for (var entry : userLastActiveTime.entrySet()) {
            long idleTime = now - entry.getValue();
            if (idleTime >= idleMs) {
                String sessionKey = entry.getKey();
                // 用户空闲超过阈值，标记为离开
                service.onUserAway(sessionKey);
            }
        }
    }

    /**
     * 获取用户空闲时间（分钟）
     */
    public long getUserIdleMinutes(String sessionKey) {
        Long lastActive = userLastActiveTime.get(sessionKey);
        if (lastActive == null) {
            return 0;
        }
        return (System.currentTimeMillis() - lastActive) / 60000;
    }

    // ========== 回调接口 ==========

    public interface SummaryCallback {
        void onSummaryReady(String sessionKey, String summary);
    }

    private SummaryCallback summaryCallback;

    public void setSummaryCallback(SummaryCallback callback) {
        this.summaryCallback = callback;
    }

    /**
     * 创建默认实例
     */
    public static FeishuAwaySummaryHandler create(AwaySummaryService service) {
        return new FeishuAwaySummaryHandler(service, 10); // 默认 10 分钟
    }
}
