package agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
 * 3. 支持持久化到磁盘（可选，通过 SubagentPersistence）
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

    /** 持久化层（可选） */
    private volatile SubagentPersistence persistence;

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
     * 初始化持久化层并恢复历史记录
     *
     * @param workspace 工作目录（持久化文件存储在 workspace/.javaclawbot/ 下）
     */
    public synchronized void initPersistence(Path workspace) {
        if (persistence != null) {
            log.warn("Persistence already initialized, skipping");
            return;
        }

        this.persistence = new SubagentPersistence(workspace);

        // 从持久化恢复历史记录（仅恢复已完成的，运行中的视为已中断）
        Collection<SubagentRunRecord> persisted = persistence.getAllRecords();
        int restored = 0;
        int interrupted = 0;

        for (SubagentRunRecord record : persisted) {
            if (record.isRunning()) {
                // 进程重启后，之前 running 的记录视为已中断
                record.setOutcome(SubagentOutcome.error("interrupted: process restart"));
                record.setEndedAt(LocalDateTime.now());
                interrupted++;
            }

            // 恢复到内存
            runs.put(record.getRunId(), record);

            String requesterKey = record.getRequesterSessionKey();
            if (requesterKey != null) {
                sessionIndex.computeIfAbsent(requesterKey, k -> ConcurrentHashMap.newKeySet()).add(record.getRunId());
            }
            childSessionIndex.put(record.getChildSessionKey(), record.getRunId());

            restored++;
        }

        if (restored > 0) {
            log.info("Restored {} run records from persistence ({} interrupted)", restored, interrupted);
        }
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

        // 同步持久化
        if (persistence != null) {
            persistence.addRecord(record);
        }

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

        // 同步持久化
        if (persistence != null) {
            persistence.removeRecord(runId);
        }

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
     * 列出指定会话的活跃子Agent运行
     */
    public List<SubagentRunRecord> listActiveByRequester(String requesterSessionKey) {
        return listByRequester(requesterSessionKey).stream()
                .filter(SubagentRunRecord::isRunning)
                .collect(Collectors.toList());
    }

    /**
     * 计算新子Agent的深度
     *
     * @param requesterSessionKey 请求者会话Key
     * @return 新子Agent的深度
     */
    public int computeChildDepth(String requesterSessionKey) {
        if (requesterSessionKey == null) return 1;

        String runId = childSessionIndex.get(requesterSessionKey);
        if (runId != null) {
            SubagentRunRecord record = runs.get(runId);
            if (record != null) {
                return record.getDepth() + 1;
            }
        }

        return 1;
    }

    /**
     * 标记运行开始
     */
    public void markStarted(String runId) {
        SubagentRunRecord record = get(runId);
        if (record != null) {
            record.setStartedAt(LocalDateTime.now());
            if (persistence != null) {
                persistence.updateRecord(record);
            }
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
            if (persistence != null) {
                persistence.updateRecord(record);
            }
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
            if (persistence != null) {
                persistence.updateRecord(record);
            }
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
            log.info("已清理 {} 条完成的子代理记录", toRemove.size());
            // 清理后立即持久化
            if (persistence != null) {
                persistence.save();
            }
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
        if (persistence != null) {
            persistence.clearAll();
            persistence.save();
        }
        log.info("已清除所有子代理记录");
    }

    /**
     * 触发持久化保存（立即写入磁盘）
     */
    public void flushPersistence() {
        if (persistence != null) {
            persistence.save();
        }
    }

    // ==========================
    // Getter
    // ==========================

    public int getMaxSpawnDepth() { return maxSpawnDepth; }
}
