package agent.subagent.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 拒绝追踪状态
 *
 * 对应 Open-ClaudeCode: src/permissions/denialTracking.ts - createDenialTrackingState()
 *
 * 用于追踪子代理的权限拒绝次数。
 * 当 setAppState 被隔离为空操作时，子代理需要本地追踪拒绝计数，
 * 以便在重试期间正确累积。
 */
public class DenialTrackingState {

    /** 工具名称 -> 拒绝次数 */
    private final Map<String, Integer> denialCounts = new ConcurrentHashMap<>();

    /** 工具名称 -> 最后一次拒绝时间戳 */
    private final Map<String, Long> lastDenialTimes = new ConcurrentHashMap<>();

    /**
     * 记录一次拒绝
     */
    public void recordDenial(String toolName) {
        denialCounts.merge(toolName, 1, Integer::sum);
        lastDenialTimes.put(toolName, System.currentTimeMillis());
    }

    /**
     * 获取工具的拒绝次数
     */
    public int getDenialCount(String toolName) {
        return denialCounts.getOrDefault(toolName, 0);
    }

    /**
     * 获取工具的最后拒绝时间
     */
    public long getLastDenialTime(String toolName) {
        return lastDenialTimes.getOrDefault(toolName, 0L);
    }

    /**
     * 获取所有被拒绝过的工具
     */
    public Map<String, Integer> getAllDenials() {
        return new ConcurrentHashMap<>(denialCounts);
    }

    /**
     * 重置所有计数
     */
    public void reset() {
        denialCounts.clear();
        lastDenialTimes.clear();
    }

    /**
     * 创建新的拒绝追踪状态（用于隔离的子代理）
     */
    public static DenialTrackingState create() {
        return new DenialTrackingState();
    }
}
