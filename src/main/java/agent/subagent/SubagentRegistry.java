package agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 子Agent注册表
 *
 * 对应 OpenClaw: src/agents/subagent-registry.ts
 *
 * 核心职责：
 * 1. 管理所有子Agent运行记录（全局单例）
 * 2. 支持按会话查询、按深度查询
 * 3. 支持持久化到磁盘（可选）
 * 4. 支持清理过期记录
 *
 * 线程安全：使用 ConcurrentHashMap 保证并发安全
 */
public class SubagentRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubagentRegistry.class);

    /** 单例实例 */
    private static volatile SubagentRegistry instance;

    /** 运行记录存储：runId -> record */
    private final ConcurrentHashMap<String, SubagentRunRecord> runs = new ConcurrentHashMap<>();

    /** 会话索引：requesterSessionKey -> Set<runId> */
    private final ConcurrentHashMap<String, Set<String>> sessionIndex = new ConcurrentHashMap<>();

    /** 子会话索引：childSessionKey -> runId */
    private final ConcurrentHashMap<String, String> childSessionIndex = new ConcurrentHashMap<>();

    /** 默认最大spawn深度 */
    public static final int DEFAULT_MAX_SPAWN_DEPTH = 3;

    /** 最大spawn深度 */
    private int maxSpawnDepth = DEFAULT_MAX_SPAWN_DEPTH;

    private SubagentRegistry() {}

    /**
     * 获取单例实例
     */
    public static SubagentRegistry getInstance() {
        if (instance == null) {
            synchronized (SubagentRegistry.class) {
                if (instance == null) {
                    instance = new SubagentRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * 注册新的子Agent运行
     *
     * @param record 运行记录
     * @return 注册是否成功
     */
    public boolean register(SubagentRunRecord record) {
        if (record == null || record.getRunId() == null || record.getChildSessionKey() == null) {
            return false;
        }

        String runId = record.getRunId();
        String requesterKey = record.getRequesterSessionKey();
        String childKey = record.getChildSessionKey();

        // 检查是否已存在
        if (runs.containsKey(runId)) {
            log.warn("Run already exists: {}", runId);
            return false;
        }

        // 存储记录
        runs.put(runId, record);

        // 更新会话索引
        sessionIndex.computeIfAbsent(requesterKey, k -> ConcurrentHashMap.newKeySet()).add(runId);

        // 更新子会话索引
        childSessionIndex.put(childKey, runId);

        log.info("Registered subagent run: {} (session: {}, depth: {})",
                runId, childKey, record.getDepth());

        return true;
    }

    /**
     * 注销子Agent运行
     *
     * @param runId 运行ID
     * @return 被移除的记录，或null
     */
    public SubagentRunRecord unregister(String runId) {
        if (runId == null) return null;

        SubagentRunRecord record = runs.remove(runId);
        if (record == null) return null;

        // 清理会话索引
        String requesterKey = record.getRequesterSessionKey();
        if (requesterKey != null) {
            sessionIndex.computeIfPresent(requesterKey, (k, set) -> {
                set.remove(runId);
                return set.isEmpty() ? null : set;
            });
        }

        // 清理子会话索引
        childSessionIndex.remove(record.getChildSessionKey());

        log.info("Unregistered subagent run: {}", runId);
        return record;
    }

    /**
     * 获取运行记录
     */
    public SubagentRunRecord get(String runId) {
        return runId != null ? runs.get(runId) : null;
    }

    /**
     * 通过子会话Key获取运行记录
     */
    public SubagentRunRecord getByChildSessionKey(String childSessionKey) {
        if (childSessionKey == null) return null;
        String runId = childSessionIndex.get(childSessionKey);
        return runId != null ? runs.get(runId) : null;
    }

    /**
     * 列出指定会话的所有子Agent运行
     *
     * @param requesterSessionKey 请求者会话Key
     * @return 运行记录列表（按创建时间排序）
     */
    public List<SubagentRunRecord> listByRequester(String requesterSessionKey) {
        if (requesterSessionKey == null) return List.of();

        Set<String> runIds = sessionIndex.get(requesterSessionKey);
        if (runIds == null || runIds.isEmpty()) return List.of();

        return runIds.stream()
                .map(runs::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(SubagentRunRecord::getCreatedAt))
                .collect(Collectors.toList());
    }

    /**
     * 列出所有活跃的子Agent运行
     */
    public List<SubagentRunRecord> listActive() {
        return runs.values().stream()
                .filter(SubagentRunRecord::isRunning)
                .sorted(Comparator.comparing(SubagentRunRecord::getCreatedAt))
                .collect(Collectors.toList());
    }

    /**
     * 列出指定会话的活跃子Agent运行
     */
    public List<SubagentRunRecord> listActiveByRequester(String requesterSessionKey) {
        return listByRequester(requesterSessionKey).stream()
                .filter(SubagentRunRecord::isRunning)
                .collect(Collectors.toList());
    }

    /**
     * 统计指定会话的活跃子Agent数量
     */
    public int countActive(String requesterSessionKey) {
        return (int) listByRequester(requesterSessionKey).stream()
                .filter(SubagentRunRecord::isRunning)
                .count();
    }

    /**
     * 统计所有活跃子Agent数量
     */
    public int countAllActive() {
        return (int) runs.values().stream()
                .filter(SubagentRunRecord::isRunning)
                .count();
    }

    /**
     * 获取子Agent的当前深度
     *
     * @param sessionKey 会话Key
     * @return 深度（0=主Agent, 1+=子Agent）
     */
    public int getDepth(String sessionKey) {
        if (sessionKey == null) return 0;

        // 检查是否是子会话
        SubagentRunRecord record = getByChildSessionKey(sessionKey);
        if (record != null) {
            return record.getDepth();
        }

        return 0;
    }

    /**
     * 检查是否可以spawn新的子Agent
     *
     * @param requesterSessionKey 请求者会话Key
     * @return 是否可以spawn
     */
    public boolean canSpawn(String requesterSessionKey) {
        int depth = getDepth(requesterSessionKey);
        return depth < maxSpawnDepth;
    }

    /**
     * 计算新子Agent的深度
     *
     * @param requesterSessionKey 请求者会话Key
     * @return 新子Agent的深度
     */
    public int computeChildDepth(String requesterSessionKey) {
        return getDepth(requesterSessionKey) + 1;
    }

    /**
     * 标记运行开始
     */
    public void markStarted(String runId) {
        SubagentRunRecord record = get(runId);
        if (record != null) {
            record.setStartedAt(LocalDateTime.now());
            log.debug("Subagent started: {}", runId);
        }
    }

    /**
     * 标记运行结束
     */
    public void markEnded(String runId, SubagentOutcome outcome) {
        SubagentRunRecord record = get(runId);
        if (record != null) {
            record.setEndedAt(LocalDateTime.now());
            record.setOutcome(outcome);
            log.info("Subagent ended: {} (status: {})", runId,
                    outcome != null ? outcome.getStatus() : "unknown");
        }
    }

    /**
     * 更新冻结结果文本
     */
    public void updateFrozenResult(String runId, String resultText) {
        SubagentRunRecord record = get(runId);
        if (record != null) {
            record.setFrozenResultText(resultText);
        }
    }

    /**
     * 清理已完成的记录（超过指定分钟数）
     *
     * @param maxAgeMinutes 最大保留时间（分钟）
     * @return 清理的记录数
     */
    public int cleanupCompleted(int maxAgeMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(maxAgeMinutes);
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, SubagentRunRecord> entry : runs.entrySet()) {
            SubagentRunRecord record = entry.getValue();
            if (record.isCompleted() && record.getEndedAt() != null) {
                if (record.getEndedAt().isBefore(threshold)) {
                    toRemove.add(entry.getKey());
                }
            }
        }

        for (String runId : toRemove) {
            unregister(runId);
        }

        if (!toRemove.isEmpty()) {
            log.info("Cleaned up {} completed subagent records", toRemove.size());
        }

        return toRemove.size();
    }

    /**
     * 清理所有记录（用于测试）
     */
    public void clear() {
        runs.clear();
        sessionIndex.clear();
        childSessionIndex.clear();
        log.info("Cleared all subagent records");
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", runs.size());
        stats.put("active", countAllActive());
        stats.put("maxDepth", maxSpawnDepth);
        return stats;
    }

    // ==========================
    // Getter & Setter
    // ==========================

    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int maxSpawnDepth) {
        this.maxSpawnDepth = Math.max(1, maxSpawnDepth);
    }
}