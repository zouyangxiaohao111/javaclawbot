package agent.subagent.framework;

import agent.subagent.types.TaskState;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 任务持久化
 *
 * 将任务状态保存到磁盘，支持启动时恢复
 */
public class TaskPersistence {
    private static final Logger log = LoggerFactory.getLogger(TaskPersistence.class);

    private static final String PERSISTENCE_DIR = ".javaclawbot";
    private static final String TASKS_FILE = "tasks.json";

    private final Path persistenceDir;
    private final Path tasksFile;
    private final ObjectMapper objectMapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 创建任务持久化
     */
    public TaskPersistence(Path workspace) {
        this.persistenceDir = workspace.resolve(PERSISTENCE_DIR);
        this.tasksFile = persistenceDir.resolve(TASKS_FILE);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 注册子类型
        objectMapper.registerSubtypes(
            agent.subagent.lifecycle.LocalAgentTaskState.class
        );
    }

    /**
     * 初始化持久化目录
     */
    public void init() throws IOException {
        if (!Files.exists(persistenceDir)) {
            Files.createDirectories(persistenceDir);
            log.info("Created persistence directory: {}", persistenceDir);
        }
    }

    /**
     * 保存所有任务
     */
    public void saveAll(List<TaskState> tasks) {
        lock.writeLock().lock();
        try {
            init();

            TaskPersistenceData data = new TaskPersistenceData();
            data.version = 1;
            data.savedAt = Instant.now().toEpochMilli();
            data.tasks = tasks;

            String json = objectMapper.writeValueAsString(data);
            Files.writeString(tasksFile, json);

            log.debug("Saved {} tasks to {}", tasks.size(), tasksFile);
        } catch (IOException e) {
            log.error("Failed to save tasks", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 加载所有任务
     */
    public List<TaskState> loadAll() {
        lock.readLock().lock();
        try {
            if (!Files.exists(tasksFile)) {
                log.debug("No tasks file found at {}", tasksFile);
                return new ArrayList<>();
            }

            String json = Files.readString(tasksFile);
            TaskPersistenceData data = objectMapper.readValue(json, TaskPersistenceData.class);

            log.info("Loaded {} tasks from {}", data.tasks.size(), tasksFile);
            return data.tasks;
        } catch (IOException e) {
            log.error("Failed to load tasks", e);
            return new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 添加或更新单个任务
     */
    public void saveTask(TaskState task) {
        List<TaskState> tasks = loadAll();

        // 找到并更新或添加
        boolean found = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(task.getId())) {
                tasks.set(i, task);
                found = true;
                break;
            }
        }
        if (!found) {
            tasks.add(task);
        }

        saveAll(tasks);
    }

    /**
     * 删除单个任务
     */
    public void deleteTask(String taskId) {
        List<TaskState> tasks = loadAll();
        tasks.removeIf(t -> t.getId().equals(taskId));
        saveAll(tasks);
    }

    /**
     * 清空所有任务
     */
    public void clearAll() {
        lock.writeLock().lock();
        try {
            if (Files.exists(tasksFile)) {
                Files.delete(tasksFile);
            }
            log.info("Cleared all persisted tasks");
        } catch (IOException e) {
            log.error("Failed to clear tasks", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取持久化目录路径
     */
    public Path getPersistenceDir() {
        return persistenceDir;
    }

    /**
     * 持久化数据结构
     */
    public static class TaskPersistenceData {
        public int version;
        public long savedAt;
        public List<TaskState> tasks;
    }
}
