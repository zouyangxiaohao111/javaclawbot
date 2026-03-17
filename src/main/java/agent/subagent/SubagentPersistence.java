package agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子Agent运行记录持久化
 *
 * 对齐 OpenClaw 的 subagent-registry.store.ts
 *
 * 功能：
 * - 子Agent运行记录的持久化
 * - 运行历史追踪
 * - 状态恢复
 */
public class SubagentPersistence {

    private static final Logger log = LoggerFactory.getLogger(SubagentPersistence.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 持久化运行记录
     */
    public static class PersistedRunRecord {
        public String runId;
        public String childSessionKey;
        public String requesterSessionKey;
        public String label;
        public String task;
        public String status;       // "pending", "running", "completed", "failed", "killed"
        public String createdAt;
        public String startedAt;
        public String endedAt;
        public String frozenResultText;
        public int depth;
        public String outcomeStatus;
        public String outcomeError;
        public String cleanup;
        public String spawnMode;
        public String workspaceDir;
        public String model;
        public Integer runTimeoutSeconds;

        // 用于JSON序列化
        public PersistedRunRecord() {}

        public PersistedRunRecord(SubagentRunRecord record) {
            this.runId = record.getRunId();
            this.childSessionKey = record.getChildSessionKey();
            this.requesterSessionKey = record.getRequesterSessionKey();
            this.label = record.getLabel();
            this.task = record.getTask();
            this.status = record.isRunning() ? "running" : (record.isCompleted() ? "completed" : "pending");
            this.createdAt = formatDateTime(record.getCreatedAt());
            this.startedAt = formatDateTime(record.getStartedAt());
            this.endedAt = formatDateTime(record.getEndedAt());
            this.frozenResultText = truncateText(record.getFrozenResultText(), 10000);
            this.depth = record.getDepth();
            if (record.getOutcome() != null) {
                this.outcomeStatus = record.getOutcome().getStatus().name().toLowerCase();
                this.outcomeError = record.getOutcome().getError();
            }
            this.cleanup = record.getCleanup() != null ? record.getCleanup().name().toLowerCase() : null;
            this.spawnMode = record.getSpawnMode() != null ? record.getSpawnMode().name().toLowerCase() : null;
            this.workspaceDir = record.getWorkspaceDir();
            this.model = record.getModel();
            this.runTimeoutSeconds = record.getRunTimeoutSeconds();
        }

        public SubagentRunRecord toRunRecord() {
            SubagentRunRecord record = new SubagentRunRecord(
                    runId,
                    childSessionKey,
                    requesterSessionKey,
                    label,
                    task,
                    cleanup != null ? SubagentRunRecord.CleanupPolicy.valueOf(cleanup.toUpperCase()) : SubagentRunRecord.CleanupPolicy.DELETE,
                    spawnMode != null ? SubagentRunRecord.SpawnMode.valueOf(spawnMode.toUpperCase()) : SubagentRunRecord.SpawnMode.RUN,
                    depth
            );
            if (startedAt != null) {
                record.setStartedAt(LocalDateTime.now()); // 简化处理
            }
            if ("completed".equals(status) || "failed".equals(status) || "killed".equals(status)) {
                record.setEndedAt(LocalDateTime.now()); // 简化处理
            }
            if (frozenResultText != null) {
                record.setFrozenResultText(frozenResultText);
            }
            if (outcomeStatus != null) {
                try {
                    SubagentOutcome.Status status = SubagentOutcome.Status.valueOf(outcomeStatus.toUpperCase());
                    record.setOutcome(new SubagentOutcome(status, outcomeError));
                } catch (IllegalArgumentException ignored) {}
            }
            if (workspaceDir != null) {
                record.setWorkspaceDir(workspaceDir);
            }
            if (model != null) {
                record.setModel(model);
            }
            if (runTimeoutSeconds != null) {
                record.setRunTimeoutSeconds(runTimeoutSeconds);
            }
            return record;
        }
    }

    /**
     * 持久化存储
     */
    public static class PersistedStore {
        public String version = "1.0";
        public String lastUpdated;
        public List<PersistedRunRecord> runs = new ArrayList<>();
        public Map<String, Object> metadata = new HashMap<>();
    }

    private final Path storePath;
    private final Map<String, SubagentRunRecord> memoryCache = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public SubagentPersistence(Path workspace) {
        this.storePath = workspace.resolve(".javaclawbot").resolve("subagent-store.json");
        load();
    }

    /**
     * 加载持久化数据
     */
    public synchronized void load() {
        if (!Files.exists(storePath)) {
            log.debug("存储文件不存在: {}", storePath);
            return;
        }

        try {
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            PersistedStore store = MAPPER.readValue(json, PersistedStore.class);

            memoryCache.clear();
            if (store.runs != null) {
                for (PersistedRunRecord record : store.runs) {
                    if (record.runId != null) {
                        memoryCache.put(record.runId, record.toRunRecord());
                    }
                }
            }

            log.info("Loaded {} persisted run records", memoryCache.size());
        } catch (IOException e) {
            log.warn("加载持久化存储失败: {}", e.getMessage());
        }
    }

    /**
     * 保存持久化数据
     */
    public synchronized void save() {
        if (!dirty) {
            return;
        }

        try {
            Files.createDirectories(storePath.getParent());

            PersistedStore store = new PersistedStore();
            store.lastUpdated = LocalDateTime.now().format(FORMATTER);

            for (SubagentRunRecord record : memoryCache.values()) {
                store.runs.add(new PersistedRunRecord(record));
            }

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(store);
            Files.writeString(storePath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            dirty = false;
            log.debug("Saved {} run records to {}", store.runs.size(), storePath);
        } catch (IOException e) {
            log.warn("保存持久化存储失败: {}", e.getMessage());
        }
    }

    /**
     * 添加运行记录
     */
    public void addRecord(SubagentRunRecord record) {
        if (record == null || record.getRunId() == null) {
            return;
        }
        memoryCache.put(record.getRunId(), record);
        dirty = true;
    }

    /**
     * 更新运行记录
     */
    public void updateRecord(SubagentRunRecord record) {
        if (record == null || record.getRunId() == null) {
            return;
        }
        memoryCache.put(record.getRunId(), record);
        dirty = true;
    }

    /**
     * 获取运行记录
     */
    public SubagentRunRecord getRecord(String runId) {
        return memoryCache.get(runId);
    }

    /**
     * 移除运行记录
     */
    public void removeRecord(String runId) {
        memoryCache.remove(runId);
        dirty = true;
    }

    /**
     * 获取所有运行记录
     */
    public Collection<SubagentRunRecord> getAllRecords() {
        return Collections.unmodifiableCollection(memoryCache.values());
    }

    /**
     * 获取会话的运行记录
     */
    public List<SubagentRunRecord> getRecordsBySession(String sessionKey) {
        List<SubagentRunRecord> result = new ArrayList<>();
        for (SubagentRunRecord record : memoryCache.values()) {
            if (sessionKey.equals(record.getChildSessionKey())) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * 获取父会话的子运行记录
     */
    public List<SubagentRunRecord> getRecordsByRequester(String requesterSessionKey) {
        List<SubagentRunRecord> result = new ArrayList<>();
        for (SubagentRunRecord record : memoryCache.values()) {
            if (requesterSessionKey.equals(record.getRequesterSessionKey())) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * 清理过期记录
     */
    public int cleanupExpiredRecords(long maxAgeMs) {
        long now = System.currentTimeMillis();
        int removed = 0;

        Iterator<Map.Entry<String, SubagentRunRecord>> it = memoryCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SubagentRunRecord> entry = it.next();
            SubagentRunRecord record = entry.getValue();

            // 只清理已完成的记录
            if (record.isCompleted()) {
                LocalDateTime endedTime = record.getEndedAt();
                if (endedTime != null) {
                    long endedMs = endedTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (now - endedMs > maxAgeMs) {
                        it.remove();
                        removed++;
                    }
                }
            }
        }

        if (removed > 0) {
            dirty = true;
            log.info("已清理 {} 条过期运行记录", removed);
        }

        return removed;
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", memoryCache.size());

        int pending = 0, running = 0, completed = 0;
        for (SubagentRunRecord record : memoryCache.values()) {
            if (record.isCompleted()) {
                completed++;
            } else if (record.isRunning()) {
                running++;
            } else {
                pending++;
            }
        }

        stats.put("pending", pending);
        stats.put("running", running);
        stats.put("completed", completed);

        return stats;
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(FORMATTER);
    }

    private static String truncateText(String text, int maxLength) {
        if (text == null) return null;
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }
}