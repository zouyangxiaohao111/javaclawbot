package session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话维护警告
 * 
 * 对齐 OpenClaw 的 session-maintenance-warning.ts
 * 
 * 功能：
 * - 当会话即将被维护清理时发送警告
 * - 支持警告去重（同一会话不重复警告）
 */
public class SessionMaintenanceWarning {

    private static final Logger log = LoggerFactory.getLogger(SessionMaintenanceWarning.class);

    /** 已警告的上下文缓存 */
    private static final Map<String, String> warnedContexts = new ConcurrentHashMap<>();

    /**
     * 检查并发送会话维护警告
     *
     * @param sessionKey    会话键
     * @param pruneAfterMs  修剪时间阈值（毫秒）
     * @param maxEntries    最大条目数
     * @param wouldPrune    是否会被修剪
     * @param wouldCap      是否会被截断
     * @param warningConsumer 警告消费者（接收警告文本）
     * @return 是否发送了警告
     */
    public static boolean checkAndWarn(
            String sessionKey,
            long pruneAfterMs,
            int maxEntries,
            boolean wouldPrune,
            boolean wouldCap,
            java.util.function.Consumer<String> warningConsumer
    ) {
        String contextKey = buildWarningContext(sessionKey, pruneAfterMs, maxEntries, wouldPrune, wouldCap);

        // 检查是否已警告过
        if (contextKey.equals(warnedContexts.get(sessionKey))) {
            return false;
        }

        // 记录已警告
        warnedContexts.put(sessionKey, contextKey);

        // 构建警告文本
        String warningText = buildWarningText(pruneAfterMs, maxEntries, wouldPrune, wouldCap);

        // 发送警告
        if (warningConsumer != null) {
            warningConsumer.accept(warningText);
        } else {
            log.warn("会话维护警告: sessionKey={}, warning={}", sessionKey, warningText);
        }

        return true;
    }

    /**
     * 清除已警告的会话
     */
    public static void clearWarning(String sessionKey) {
        warnedContexts.remove(sessionKey);
    }

    /**
     * 清除所有警告记录
     */
    public static void clearAllWarnings() {
        warnedContexts.clear();
    }

    /**
     * 构建警告上下文键
     */
    private static String buildWarningContext(
            String sessionKey,
            long pruneAfterMs,
            int maxEntries,
            boolean wouldPrune,
            boolean wouldCap
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(sessionKey != null ? sessionKey : "");
        sb.append("|").append(pruneAfterMs);
        sb.append("|").append(maxEntries);
        if (wouldPrune) sb.append("|prune");
        if (wouldCap) sb.append("|cap");
        return sb.toString();
    }

    /**
     * 构建警告文本
     */
    private static String buildWarningText(
            long pruneAfterMs,
            int maxEntries,
            boolean wouldPrune,
            boolean wouldCap
    ) {
        StringBuilder reasons = new StringBuilder();

        if (wouldPrune) {
            reasons.append("older than ").append(formatDuration(pruneAfterMs));
        }
        if (wouldCap) {
            if (reasons.length() > 0) reasons.append(" and ");
            reasons.append("not in the most recent ").append(maxEntries).append(" sessions");
        }

        String reasonText = reasons.length() > 0 ? reasons.toString() : "over maintenance limits";

        return "⚠️ Session maintenance warning: this active session would be evicted (" + reasonText + "). " +
                "Maintenance is set to warn-only, so nothing was reset. " +
                "To enforce cleanup, set `session.maintenance.mode: \"enforce\"` or increase the limits.";
    }

    /**
     * 格式化持续时间
     */
    private static String formatDuration(long ms) {
        if (ms >= 86_400_000) {
            long days = Math.round(ms / 86_400_000.0);
            return days + " day" + (days == 1 ? "" : "s");
        }
        if (ms >= 3_600_000) {
            long hours = Math.round(ms / 3_600_000.0);
            return hours + " hour" + (hours == 1 ? "" : "s");
        }
        if (ms >= 60_000) {
            long mins = Math.round(ms / 60_000.0);
            return mins + " minute" + (mins == 1 ? "" : "s");
        }
        long secs = Math.round(ms / 1000.0);
        return secs + " second" + (secs == 1 ? "" : "s");
    }
}