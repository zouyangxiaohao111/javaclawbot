package agent.subagent.framework;

import agent.subagent.lifecycle.LocalAgentTaskState;
import agent.subagent.types.TaskState;
import agent.subagent.types.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务注册表
 *
 * 管理所有任务状态的注册、查询和清理
 */
public class TaskRegistry {
    private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

    /** 单例实例 */
    private static volatile TaskRegistry instance;

    /** 任务存储：taskId -> TaskState */
    private final ConcurrentHashMap<String, TaskState> tasks = new ConcurrentHashMap<>();

    /** 任务观察者列表 */
    private final CopyOnWriteArrayList<TaskObserver> observers = new CopyOnWriteArrayList<>();

    /** 任务 ID 前缀 */
    private static final String TASK_ID_PREFIX = "a";

    private TaskRegistry() {}

    /**
     * 获取单例实例
     */
    public static TaskRegistry getInstance() {
        if (instance == null) {
            synchronized (TaskRegistry.class) {
                if (instance == null) {
                    instance = new TaskRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * 重置实例（用于测试）
     */
    public static void reset() {
        synchronized (TaskRegistry.class) {
            if (instance != null) {
                instance.tasks.clear();
                instance = null;
            }
        }
    }

    /**
     * 生成任务 ID
     */
    public String generateTaskId() {
        String id;
        do {
            id = TASK_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        } while (tasks.containsKey(id));
        return id;
    }

    /**
     * 注册任务
     */
    public void register(TaskState task) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("Task and task ID cannot be null");
        }
        tasks.put(task.getId(), task);
        notifyObservers(task, TaskEvent.REGISTERED);
        log.debug("Task registered: {} ({})", task.getId(), task.getClass().getSimpleName());
    }

    /**
     * 获取任务
     */
    public TaskState get(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 获取任务（泛型）
     */
    @SuppressWarnings("unchecked")
    public <T extends TaskState> T get(String taskId, Class<T> type) {
        TaskState task = tasks.get(taskId);
        if (task != null && type.isInstance(task)) {
            return (T) task;
        }
        return null;
    }

    /**
     * 移除任务
     */
    public TaskState remove(String taskId) {
        TaskState task = tasks.remove(taskId);
        if (task != null) {
            notifyObservers(task, TaskEvent.REMOVED);
        }
        return task;
    }

    /**
     * 更新任务状态
     */
    public void update(String taskId, Consumer<TaskState> updater) {
        TaskState task = tasks.get(taskId);
        if (task != null) {
            updater.accept(task);
            notifyObservers(task, TaskEvent.UPDATED);
        }
    }

    /**
     * 标记任务开始
     */
    public void markStarted(String taskId) {
        update(taskId, task -> task.setStatus(TaskStatus.RUNNING));
    }

    /**
     * 标记任务完成
     */
    public void markCompleted(String taskId) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.COMPLETED);
            task.setEndTime(Instant.now());
        });
        notifyObservers(tasks.get(taskId), TaskEvent.COMPLETED);
    }

    /**
     * 标记任务失败
     */
    public void markFailed(String taskId, String error) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.FAILED);
            task.setEndTime(Instant.now());
            if (task instanceof LocalAgentTaskState) {
                ((LocalAgentTaskState) task).setError(error);
            }
        });
        notifyObservers(tasks.get(taskId), TaskEvent.FAILED);
    }

    /**
     * 标记任务终止
     */
    public void markKilled(String taskId) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.KILLED);
            task.setEndTime(Instant.now());
            if (task instanceof LocalAgentTaskState) {
                ((LocalAgentTaskState) task).markKilled();
            }
        });
        notifyObservers(tasks.get(taskId), TaskEvent.KILLED);
    }

    /**
     * 获取所有运行中的任务
     */
    public List<TaskState> getRunningTasks() {
        return tasks.values().stream()
                .filter(task -> task.getStatus() == TaskStatus.RUNNING)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有任务
     */
    public Collection<TaskState> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    /**
     * 获取任务数量
     */
    public int size() {
        return tasks.size();
    }

    /**
     * 清空所有任务
     */
    public void clear() {
        tasks.clear();
    }

    /**
     * 清理已完成的任务
     */
    public int cleanupCompleted(long maxAgeMs) {
        Instant cutoff = Instant.now().minusMillis(maxAgeMs);
        List<String> toRemove = new ArrayList<>();

        tasks.forEach((id, task) -> {
            if (task.isTerminal() && task.getEndTime() != null && task.getEndTime().isBefore(cutoff)) {
                toRemove.add(id);
            }
        });

        for (String id : toRemove) {
            remove(id);
        }

        return toRemove.size();
    }

    // =====================
    // 观察者模式
    // =====================

    /**
     * 添加观察者
     */
    public void addObserver(TaskObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    /**
     * 移除观察者
     */
    public void removeObserver(TaskObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(TaskState task, TaskEvent event) {
        for (TaskObserver observer : observers) {
            try {
                observer.onTaskEvent(task, event);
            } catch (Exception e) {
                log.error("Error notifying observer", e);
            }
        }
    }

    /**
     * 任务事件
     */
    public enum TaskEvent {
        REGISTERED,
        UPDATED,
        COMPLETED,
        FAILED,
        KILLED,
        REMOVED
    }

    /**
     * 任务观察者接口
     */
    public interface TaskObserver {
        void onTaskEvent(TaskState task, TaskEvent event);
    }
}
