package agent.subagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Team Task Store - File-based task storage with locking.
 * 对应 Open-ClaudeCode: src/utils/tasks.ts
 *
 * 职责：
 * 1. 文件存储 tasks 在 ~/.javaclawbot/tasks/{taskListId}/
 * 2. 文件锁实现并发安全
 * 3. 实现 listTasks, claimTask, createTask 等核心方法
 */
public class TeamTaskStore {
    private static final Logger log = LoggerFactory.getLogger(TeamTaskStore.class);

    /** 高水位标记文件名 */
    private static final String HIGH_WATER_MARK_FILE = ".highwatermark";

    /** 锁文件扩展名 */
    private static final String LOCK_FILE = ".lock";

    /** 任务文件扩展名 */
    private static final String TASK_FILE_EXT = ".json";

    /** 存储根目录: ~/.javaclawbot/tasks */
    private static final String TASKS_ROOT_DIR = ".javaclawbot/tasks";

    /** 锁选项: 重试次数 */
    private static final int LOCK_RETRIES = 30;

    /** 锁选项: 最小等待时间(ms) */
    private static final int LOCK_MIN_TIMEOUT = 5;

    /** 锁选项: 最大等待时间(ms) */
    private static final int LOCK_MAX_TIMEOUT = 100;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    /** 内存锁存储: taskListId -> ReentrantLock */
    private final Map<String, ReentrantLock> listLocks = new ConcurrentHashMap<>();

    /** 获取任务存储根目录 */
    private Path getTasksRoot() {
        String home = System.getProperty("user.home", "");
        return Paths.get(home, TASKS_ROOT_DIR);
    }

    /**
     * 获取指定任务列表的目录
     * 对应: getTasksDir()
     */
    private Path getTasksDir(String taskListId) {
        return getTasksRoot().resolve(sanitizePathComponent(taskListId));
    }

    /**
     * 获取任务文件路径
     * 对应: getTaskPath()
     */
    private Path getTaskPath(String taskListId, String taskId) {
        return getTasksDir(taskListId).resolve(sanitizePathComponent(taskId) + TASK_FILE_EXT);
    }

    /**
     * 获取任务列表级别的锁文件路径
     * 对应: getTaskListLockPath()
     */
    private Path getTaskListLockPath(String taskListId) {
        return getTasksDir(taskListId).resolve(LOCK_FILE);
    }

    /**
     * 获取高水位标记文件路径
     * 对应: getHighWaterMarkPath()
     */
    private Path getHighWaterMarkPath(String taskListId) {
        return getTasksDir(taskListId).resolve(HIGH_WATER_MARK_FILE);
    }

    /**
     * 清理路径组件，移除不安全字符
     * 对应: sanitizePathComponent()
     */
    private String sanitizePathComponent(String input) {
        if (input == null) return "default";
        return input.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    /**
     * 确保任务列表目录存在
     * 对应: ensureTasksDir()
     */
    private void ensureTasksDir(String taskListId) throws IOException {
        Path dir = getTasksDir(taskListId);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 确保锁文件存在（用于 list-level 锁）
     * 对应: ensureTaskListLockFile()
     */
    private Path ensureTaskListLockFile(String taskListId) throws IOException {
        ensureTasksDir(taskListId);
        Path lockPath = getTaskListLockPath(taskListId);
        // 使用 'wx' flag (write-exclusive) 确保只有一个创建者
        try {
            Files.writeString(lockPath, "", StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            // 文件已存在，没关系
        }
        return lockPath;
    }

    /**
     * 读取高水位标记
     * 对应: readHighWaterMark()
     */
    private int readHighWaterMark(String taskListId) {
        Path path = getHighWaterMarkPath(taskListId);
        try {
            if (Files.exists(path)) {
                String content = Files.readString(path).trim();
                int value = Integer.parseInt(content);
                return value;
            }
        } catch (Exception e) {
            log.debug("Failed to read high water mark for {}: {}", taskListId, e.getMessage());
        }
        return 0;
    }

    /**
     * 写入高水位标记
     * 对应: writeHighWaterMark()
     */
    private void writeHighWaterMark(String taskListId, int value) throws IOException {
        Path path = getHighWaterMarkPath(taskListId);
        Files.writeString(path, String.valueOf(value), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * 从任务文件列表中找到最大的任务ID
     * 对应: findHighestTaskIdFromFiles()
     */
    private int findHighestTaskIdFromFiles(String taskListId) {
        Path dir = getTasksDir(taskListId);
        if (!Files.exists(dir)) {
            return 0;
        }
        int highest = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.endsWith(TASK_FILE_EXT) && !fileName.startsWith(".")) {
                    String taskId = fileName.substring(0, fileName.length() - TASK_FILE_EXT.length());
                    try {
                        int id = Integer.parseInt(taskId);
                        if (id > highest) {
                            highest = id;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to read task directory for {}", taskListId, e);
        }
        return highest;
    }

    /**
     * 找到历史上最大的任务ID（考虑文件和高水位标记）
     * 对应: findHighestTaskId()
     */
    private int findHighestTaskId(String taskListId) {
        int fromFiles = findHighestTaskIdFromFiles(taskListId);
        int fromMark = readHighWaterMark(taskListId);
        return Math.max(fromFiles, fromMark);
    }

    /**
     * 获取任务列表级别的锁
     * 对应: list-level locking 机制
     */
    private ReentrantLock getListLock(String taskListId) {
        return listLocks.computeIfAbsent(taskListId, k -> new ReentrantLock());
    }

    /**
     * 尝试获取文件锁，带重试
     * 对应: proper-lockfile with LOCK_OPTIONS
     */
    private boolean acquireFileLock(Path lockPath, ReentrantLock listLock) {
        for (int i = 0; i < LOCK_RETRIES; i++) {
            try {
                // 尝试通过创建锁文件来获取锁
                Files.writeString(lockPath, String.valueOf(System.currentTimeMillis()),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                long timeout = LOCK_MIN_TIMEOUT + (long) (Math.random() * (LOCK_MAX_TIMEOUT - LOCK_MIN_TIMEOUT));
                if (listLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (IOException e) {
                // 锁文件访问失败，继续重试
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(LOCK_MIN_TIMEOUT + (long) (Math.random() * (LOCK_MAX_TIMEOUT - LOCK_MIN_TIMEOUT)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 释放文件锁
     */
    private void releaseFileLock(Path lockPath, ReentrantLock listLock) {
        if (listLock.isHeldByCurrentThread()) {
            listLock.unlock();
        }
        try {
            Files.deleteIfExists(lockPath);
        } catch (IOException e) {
            log.debug("Failed to delete lock file: {}", lockPath);
        }
    }

    /**
     * 重置任务列表（清空所有任务）
     * 对应: resetTaskList()
     */
    public void resetTaskList(String taskListId) throws IOException {
        Path lockPath = ensureTaskListLockFile(taskListId);
        ReentrantLock listLock = getListLock(taskListId);

        listLock.lock();
        try {
            // 找到当前最高的ID并保存到高水位标记
            int currentHighest = findHighestTaskIdFromFiles(taskListId);
            if (currentHighest > 0) {
                int existingMark = readHighWaterMark(taskListId);
                if (currentHighest > existingMark) {
                    writeHighWaterMark(taskListId, currentHighest);
                }
            }

            // 删除所有任务文件
            Path dir = getTasksDir(taskListId);
            if (Files.exists(dir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path entry : stream) {
                        String fileName = entry.getFileName().toString();
                        if (fileName.endsWith(TASK_FILE_EXT) && !fileName.startsWith(".")) {
                            try {
                                Files.delete(entry);
                            } catch (IOException e) {
                                log.debug("Failed to delete task file: {}", entry);
                            }
                        }
                    }
                }
            }
        } finally {
            releaseFileLock(lockPath, listLock);
        }
    }

    /**
     * 创建新任务
     * 对应: createTask()
     */
    public String createTask(String taskListId, TeamTask taskData) throws IOException {
        Path lockPath = ensureTaskListLockFile(taskListId);
        ReentrantLock listLock = getListLock(taskListId);

        listLock.lock();
        try {
            // 在持有锁的情况下读取最高ID
            int highestId = findHighestTaskId(taskListId);
            String id = String.valueOf(highestId + 1);

            TeamTask task = new TeamTask();
            task.setId(id);
            task.setSubject(taskData.getSubject());
            task.setDescription(taskData.getDescription());
            task.setActiveForm(taskData.getActiveForm());
            task.setOwner(taskData.getOwner());
            task.setStatus(taskData.getStatus() != null ? taskData.getStatus() : "pending");
            task.setBlocks(taskData.getBlocks() != null ? taskData.getBlocks() : new ArrayList<>());
            task.setBlockedBy(taskData.getBlockedBy() != null ? taskData.getBlockedBy() : new ArrayList<>());
            task.setMetadata(taskData.getMetadata());

            Path taskPath = getTaskPath(taskListId, id);
            Files.writeString(taskPath, objectMapper.writeValueAsString(task));

            return id;
        } finally {
            releaseFileLock(lockPath, listLock);
        }
    }

    /**
     * 获取单个任务
     * 对应: getTask()
     */
    public TeamTask getTask(String taskListId, String taskId) {
        Path taskPath = getTaskPath(taskListId, taskId);
        if (!Files.exists(taskPath)) {
            return null;
        }
        try {
            String content = Files.readString(taskPath);
            return objectMapper.readValue(content, TeamTask.class);
        } catch (Exception e) {
            log.error("Failed to read task {} from {}", taskId, taskListId, e);
            return null;
        }
    }

    /**
     * 内部更新任务（不获取锁，调用方需持有锁）
     * 对应: updateTaskUnsafe()
     */
    private TeamTask updateTaskUnsafe(String taskListId, String taskId, Map<String, Object> updates) throws IOException {
        TeamTask existing = getTask(taskListId, taskId);
        if (existing == null) {
            return null;
        }

        // 应用更新
        if (updates.containsKey("subject")) {
            existing.setSubject((String) updates.get("subject"));
        }
        if (updates.containsKey("description")) {
            existing.setDescription((String) updates.get("description"));
        }
        if (updates.containsKey("activeForm")) {
            existing.setActiveForm((String) updates.get("activeForm"));
        }
        if (updates.containsKey("owner")) {
            existing.setOwner((String) updates.get("owner"));
        }
        if (updates.containsKey("status")) {
            existing.setStatus((String) updates.get("status"));
        }
        if (updates.containsKey("blocks")) {
            existing.setBlocks((List<String>) updates.get("blocks"));
        }
        if (updates.containsKey("blockedBy")) {
            existing.setBlockedBy((List<String>) updates.get("blockedBy"));
        }
        if (updates.containsKey("metadata")) {
            existing.setMetadata((Map<String, Object>) updates.get("metadata"));
        }

        Path taskPath = getTaskPath(taskListId, taskId);
        Files.writeString(taskPath, objectMapper.writeValueAsString(existing));

        return existing;
    }

    /**
     * 更新任务
     * 对应: updateTask()
     */
    public TeamTask updateTask(String taskListId, String taskId, Map<String, Object> updates) throws IOException {
        Path taskPath = getTaskPath(taskListId, taskId);

        // 先检查任务是否存在
        TeamTask taskBeforeLock = getTask(taskListId, taskId);
        if (taskBeforeLock == null) {
            return null;
        }

        // 确保锁文件存在
        Path lockPath = ensureTaskListLockFile(taskListId);
        ReentrantLock listLock = getListLock(taskListId);

        listLock.lock();
        try {
            return updateTaskUnsafe(taskListId, taskId, updates);
        } finally {
            releaseFileLock(lockPath, listLock);
        }
    }

    /**
     * 删除任务
     * 对应: deleteTask()
     */
    public boolean deleteTask(String taskListId, String taskId) throws IOException {
        Path taskPath = getTaskPath(taskListId, taskId);

        if (!Files.exists(taskPath)) {
            return false;
        }

        // 更新高水位标记
        try {
            int numericId = Integer.parseInt(taskId);
            int currentMark = readHighWaterMark(taskListId);
            if (numericId > currentMark) {
                writeHighWaterMark(taskListId, numericId);
            }
        } catch (NumberFormatException ignored) {
        }

        // 删除任务文件
        try {
            Files.delete(taskPath);
        } catch (NoSuchFileException e) {
            return false;
        }

        // 从其他任务中移除对这个任务的引用
        List<TeamTask> allTasks = listTasks(taskListId);
        for (TeamTask task : allTasks) {
            boolean modified = false;
            List<String> newBlocks = task.getBlocks().stream()
                    .filter(id -> !id.equals(taskId))
                    .collect(Collectors.toList());
            if (newBlocks.size() != task.getBlocks().size()) {
                task.setBlocks(newBlocks);
                modified = true;
            }
            List<String> newBlockedBy = task.getBlockedBy().stream()
                    .filter(id -> !id.equals(taskId))
                    .collect(Collectors.toList());
            if (newBlockedBy.size() != task.getBlockedBy().size()) {
                task.setBlockedBy(newBlockedBy);
                modified = true;
            }
            if (modified) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("blocks", task.getBlocks());
                updates.put("blockedBy", task.getBlockedBy());
                updateTask(taskListId, task.getId(), updates);
            }
        }

        return true;
    }

    /**
     * 列出所有任务
     * 对应: listTasks()
     */
    public List<TeamTask> listTasks(String taskListId) {
        Path dir = getTasksDir(taskListId);
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }

        List<TeamTask> tasks = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (fileName.endsWith(TASK_FILE_EXT) && !fileName.startsWith(".")) {
                    String taskId = fileName.substring(0, fileName.length() - TASK_FILE_EXT.length());
                    TeamTask task = getTask(taskListId, taskId);
                    if (task != null) {
                        tasks.add(task);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to list tasks for {}", taskListId, e);
        }

        return tasks;
    }

    /**
     * 阻塞任务（创建任务间的阻塞关系）
     * 对应: blockTask()
     */
    public boolean blockTask(String taskListId, String fromTaskId, String toTaskId) throws IOException {
        TeamTask fromTask = getTask(taskListId, fromTaskId);
        TeamTask toTask = getTask(taskListId, toTaskId);
        if (fromTask == null || toTask == null) {
            return false;
        }

        // 更新源任务: A blocks B
        if (!fromTask.getBlocks().contains(toTaskId)) {
            List<String> newBlocks = new ArrayList<>(fromTask.getBlocks());
            newBlocks.add(toTaskId);
            Map<String, Object> updates = new HashMap<>();
            updates.put("blocks", newBlocks);
            updateTask(taskListId, fromTaskId, updates);
        }

        // 更新目标任务: B is blockedBy A
        if (!toTask.getBlockedBy().contains(fromTaskId)) {
            List<String> newBlockedBy = new ArrayList<>(toTask.getBlockedBy());
            newBlockedBy.add(fromTaskId);
            Map<String, Object> updates = new HashMap<>();
            updates.put("blockedBy", newBlockedBy);
            updateTask(taskListId, toTaskId, updates);
        }

        return true;
    }

    /**
     * 认领任务
     * 对应: claimTask()
     */
    public ClaimTaskResult claimTask(String taskListId, String taskId, String claimantAgentId, boolean checkAgentBusy) throws IOException {
        TeamTask taskBeforeLock = getTask(taskListId, taskId);
        if (taskBeforeLock == null) {
            return ClaimTaskResult.taskNotFound();
        }

        // 如果需要检查 agent busy，使用 list-level 锁
        if (checkAgentBusy) {
            return claimTaskWithBusyCheck(taskListId, taskId, claimantAgentId);
        }

        // 否则使用 task-level 锁
        Path taskPath = getTaskPath(taskListId, taskId);
        Path lockPath = ensureTaskListLockFile(taskListId);
        ReentrantLock listLock = getListLock(taskListId);

        listLock.lock();
        try {
            TeamTask task = getTask(taskListId, taskId);
            if (task == null) {
                return ClaimTaskResult.taskNotFound();
            }

            // 检查是否已被其他 agent 认领
            if (task.getOwner() != null && !task.getOwner().equals(claimantAgentId)) {
                return ClaimTaskResult.alreadyClaimed(task);
            }

            // 检查是否已完成
            if ("completed".equals(task.getStatus())) {
                return ClaimTaskResult.alreadyResolved(task);
            }

            // 检查是否有未解决的阻塞任务
            List<TeamTask> allTasks = listTasks(taskListId);
            Set<String> unresolvedTaskIds = allTasks.stream()
                    .filter(t -> !"completed".equals(t.getStatus()))
                    .map(TeamTask::getId)
                    .collect(Collectors.toSet());
            List<String> blockedByTasks = task.getBlockedBy().stream()
                    .filter(unresolvedTaskIds::contains)
                    .collect(Collectors.toList());
            if (!blockedByTasks.isEmpty()) {
                return ClaimTaskResult.blocked(task, blockedByTasks);
            }

            // 认领任务（持有锁，使用 unsafe 更新）
            Map<String, Object> updates = new HashMap<>();
            updates.put("owner", claimantAgentId);
            TeamTask updated = updateTaskUnsafe(taskListId, taskId, updates);
            return ClaimTaskResult.success(updated);
        } finally {
            releaseFileLock(lockPath, listLock);
        }
    }

    /**
     * 带 busy check 的任务认领
     * 对应: claimTaskWithBusyCheck()
     */
    private ClaimTaskResult claimTaskWithBusyCheck(String taskListId, String taskId, String claimantAgentId) throws IOException {
        Path lockPath = ensureTaskListLockFile(taskListId);
        ReentrantLock listLock = getListLock(taskListId);

        listLock.lock();
        try {
            // 读取所有任务以原子性检查 agent 状态和任务状态
            List<TeamTask> allTasks = listTasks(taskListId);

            // 找到要认领的任务
            TeamTask task = allTasks.stream()
                    .filter(t -> t.getId().equals(taskId))
                    .findFirst()
                    .orElse(null);
            if (task == null) {
                return ClaimTaskResult.taskNotFound();
            }

            // 检查是否已被其他 agent 认领
            if (task.getOwner() != null && !task.getOwner().equals(claimantAgentId)) {
                return ClaimTaskResult.alreadyClaimed(task);
            }

            // 检查是否已完成
            if ("completed".equals(task.getStatus())) {
                return ClaimTaskResult.alreadyResolved(task);
            }

            // 检查是否有未解决的阻塞任务
            Set<String> unresolvedTaskIds = allTasks.stream()
                    .filter(t -> !"completed".equals(t.getStatus()))
                    .map(TeamTask::getId)
                    .collect(Collectors.toSet());
            List<String> blockedByTasks = task.getBlockedBy().stream()
                    .filter(unresolvedTaskIds::contains)
                    .collect(Collectors.toList());
            if (!blockedByTasks.isEmpty()) {
                return ClaimTaskResult.blocked(task, blockedByTasks);
            }

            // 检查 agent 是否忙于其他未完成的任务
            List<TeamTask> agentOpenTasks = allTasks.stream()
                    .filter(t -> !"completed".equals(t.getStatus())
                            && claimantAgentId.equals(t.getOwner())
                            && !t.getId().equals(taskId))
                    .collect(Collectors.toList());
            if (!agentOpenTasks.isEmpty()) {
                List<String> busyTaskIds = agentOpenTasks.stream()
                        .map(TeamTask::getId)
                        .collect(Collectors.toList());
                return ClaimTaskResult.agentBusy(task, busyTaskIds);
            }

            // 认领任务
            Map<String, Object> updates = new HashMap<>();
            updates.put("owner", claimantAgentId);
            TeamTask updated = updateTask(taskListId, taskId, updates);
            return ClaimTaskResult.success(updated);
        } finally {
            releaseFileLock(lockPath, listLock);
        }
    }

    /**
     * 尝试认领下一个可用任务
     * 对应: tryClaimNextTask (在 InProcessBackend 中使用)
     *
     * @param taskListId 任务列表ID
     * @param claimantAgentId 认领者 agent ID
     * @return 认领结果
     */
    public ClaimTaskResult tryClaimNextTask(String taskListId, String claimantAgentId) {
        try {
            List<TeamTask> tasks = listTasks(taskListId);

            // 按 ID 排序，选择第一个未被阻塞且未被认领的任务
            for (TeamTask task : tasks) {
                if ("pending".equals(task.getStatus()) && task.getOwner() == null) {
                    ClaimTaskResult result = claimTask(taskListId, task.getId(), claimantAgentId, true);
                    if (result.isSuccess()) {
                        return result;
                    }
                }
            }

            // 没有可用任务
            TeamTask placeholder = new TeamTask();
            placeholder.setId("");
            return ClaimTaskResult.alreadyResolved(placeholder);
        } catch (IOException e) {
            log.error("Failed to claim next task", e);
            TeamTask errorTask = new TeamTask();
            errorTask.setId("");
            return ClaimTaskResult.alreadyResolved(errorTask);
        }
    }
}