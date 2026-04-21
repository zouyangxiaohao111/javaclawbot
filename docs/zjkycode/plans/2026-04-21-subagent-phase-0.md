# Phase 0: 基础设施 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标：**建立任务框架和类型系统

**架构：**基于 TaskFramework 的统一任务管理，支持注册、轮询、通知

**技术栈：**Java 17+, picocli/JLine, Jackson

---

## 文件结构

```
src/main/java/agent/subagent/
├── types/
│   ├── TaskType.java           # 任务类型枚举
│   ├── TaskStatus.java         # 任务状态枚举
│   ├── Task.java               # Task 接口
│   ├── SetAppState.java        # 状态更新函数式接口
│   └── TaskState.java          # TaskState 基类
├── framework/
│   ├── TaskFramework.java      # 任务框架核心
│   ├── TaskRegistry.java       # 任务注册表
│   ├── ProgressTracker.java     # 进度追踪器
│   └── TaskPersistence.java     # 持久化
└── lifecycle/
    └── LocalAgentTaskState.java # 本地代理任务状态
```

---

## 任务 1：类型定义

**文件：**
- 创建：`src/main/java/agent/subagent/types/TaskType.java`
- 创建：`src/main/java/agent/subagent/types/TaskStatus.java`
- 创建：`src/main/java/agent/subagent/types/SetAppState.java`
- 创建：`src/main/java/agent/subagent/types/TaskState.java`
- 创建：`src/main/java/agent/subagent/types/Task.java`

- [ ] **步骤 1：创建 TaskType.java**

```java
package agent.subagent.types;

/**
 * 任务类型枚举
 */
public enum TaskType {
    LOCAL_AGENT("local_agent"),       // 本地代理任务
    REMOTE_AGENT("remote_agent"),     // 远程代理任务
    IN_PROCESS_TEAMMATE("in_process_teammate"),  // 进程内 Teammate
    LOCAL_WORKFLOW("local_workflow");  // 本地工作流

    private final String value;

    TaskType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static TaskType fromValue(String value) {
        for (TaskType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown TaskType: " + value);
    }
}
```

- [ ] **步骤 2：创建 TaskStatus.java**

```java
package agent.subagent.types;

/**
 * 任务状态枚举
 */
public enum TaskStatus {
    PENDING("pending"),      // 等待中
    RUNNING("running"),      // 运行中
    COMPLETED("completed"),  // 已完成
    FAILED("failed"),       // 失败
    KILLED("killed");       // 被终止

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 判断是否为终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
```

- [ ] **步骤 3：创建 SetAppState.java**

```java
package agent.subagent.types;

import java.util.function.Function;

/**
 * 状态更新函数式接口
 *
 * 用于在 TaskFramework 中更新任务状态
 */
@FunctionalInterface
public interface SetAppState {
    /**
     * 应用状态更新
     * @param updater 状态更新函数
     */
    void accept(Function<AppState, AppState> updater);
}
```

- [ ] **步骤 4：创建 TaskState.java**

```java
package agent.subagent.types;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * 任务状态基类
 */
public abstract class TaskState {
    /** 任务 ID */
    protected String id;

    /** 任务类型 */
    protected TaskType type;

    /** 任务状态 */
    protected TaskStatus status;

    /** 任务描述 */
    protected String description;

    /** 关联的 tool_use ID */
    protected String toolUseId;

    /** 开始时间 */
    protected long startTime;

    /** 结束时间 */
    protected long endTime;

    /** 输出文件路径 */
    protected String outputFile;

    /** 输出偏移量 */
    protected long outputOffset;

    /** 是否已通知 */
    protected boolean notified;

    // Getters
    public String getId() { return id; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public String getToolUseId() { return toolUseId; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public String getOutputFile() { return outputFile; }
    public long getOutputOffset() { return outputOffset; }
    public boolean isNotified() { return notified; }

    // Setters
    public void setStatus(TaskStatus status) { this.status = status; }
    public void setEndTime(long endTime) { this.endTime = endTime; }
    public void setNotified(boolean notified) { this.notified = notified; }

    /**
     * 判断是否为终态
     */
    @JsonIgnore
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * 获取运行时长（毫秒）
     */
    @JsonIgnore
    public long getRuntimeMs() {
        if (startTime == 0) return 0;
        long end = endTime > 0 ? endTime : Instant.now().toEpochMilli();
        return end - startTime;
    }
}
```

- [ ] **步骤 5：创建 Task.java**

```java
package agent.subagent.types;

/**
 * Task 接口 - 任务定义
 */
public interface Task {
    /**
     * 任务名称
     */
    String name();

    /**
     * 任务类型
     */
    TaskType type();

    /**
     * 终止任务
     * @param taskId 任务 ID
     * @param setAppState 状态更新函数
     */
    void kill(String taskId, SetAppState setAppState);
}
```

- [ ] **步骤 6：提交**

```bash
git add src/main/java/agent/subagent/types/
git commit -m "feat(subagent): add type definitions for task framework"
```

---

## 任务 2：LocalAgentTaskState

**文件：**
- 创建：`src/main/java/agent/subagent/lifecycle/LocalAgentTaskState.java`

- [ ] **步骤 1：创建 LocalAgentTaskState.java**

```java
package agent.subagent.lifecycle;

import agent.subagent.types.TaskState;
import agent.subagent.types.TaskType;
import agent.subagent.types.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地代理任务状态
 */
public class LocalAgentTaskState extends TaskState {
    /** Agent ID */
    private String agentId;

    /** 任务描述 */
    private String prompt;

    /** 选中的代理类型 */
    private String selectedAgentType;

    /** 模型 */
    private String model;

    /** 中止控制器 */
    private AtomicBoolean abortSignal;

    /** 进度追踪器引用 */
    private ProgressTracker progressTracker;

    /** 消息历史 */
    private List<Message> messages;

    /** 是否后台运行 */
    private boolean isBackgrounded;

    /** 待处理消息 */
    private Map<String, String> pendingMessages;

    /** 错误信息 */
    private String error;

    /** 执行结果 */
    private String result;

    public LocalAgentTaskState() {
        this.type = TaskType.LOCAL_AGENT;
        this.status = TaskStatus.PENDING;
        this.startTime = Instant.now().toEpochMilli();
        this.abortSignal = new AtomicBoolean(false);
        this.messages = new ArrayList<>();
        this.pendingMessages = new ConcurrentHashMap<>();
    }

    // 静态工厂方法
    public static LocalAgentTaskState create(String id, String description, String toolUseId) {
        LocalAgentTaskState state = new LocalAgentTaskState();
        state.id = id;
        state.description = description;
        state.toolUseId = toolUseId;
        return state;
    }

    // Getters
    public String getAgentId() { return agentId; }
    public String getPrompt() { return prompt; }
    public String getSelectedAgentType() { return selectedAgentType; }
    public String getModel() { return model; }
    public AtomicBoolean getAbortSignal() { return abortSignal; }
    public ProgressTracker getProgressTracker() { return progressTracker; }
    public List<Message> getMessages() { return messages; }
    public boolean isBackgrounded() { return isBackgrounded; }
    public Map<String, String> getPendingMessages() { return pendingMessages; }
    public String getError() { return error; }
    public String getResult() { return result; }

    // Setters
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public void setSelectedAgentType(String selectedAgentType) { this.selectedAgentType = selectedAgentType; }
    public void setModel(String model) { this.model = model; }
    public void setProgressTracker(ProgressTracker progressTracker) { this.progressTracker = progressTracker; }
    public void setBackgrounded(boolean backgrounded) { isBackgrounded = backgrounded; }
    public void setError(String error) { this.error = error; }
    public void setResult(String result) { this.result = result; }

    /**
     * 添加待处理消息
     */
    public void addPendingMessage(String from, String content) {
        pendingMessages.put(from, content);
    }

    /**
     * 清除并获取所有待处理消息
     */
    public String drainPendingMessages() {
        StringBuilder sb = new StringBuilder();
        pendingMessages.forEach((from, content) -> {
            sb.append("[").append(from).append("]: ").append(content).append("\n");
        });
        pendingMessages.clear();
        return sb.toString();
    }

    /**
     * 标记任务开始
     */
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = Instant.now().toEpochMilli();
    }

    /**
     * 标记任务完成
     */
    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.endTime = Instant.now().toEpochMilli();
        this.result = result;
    }

    /**
     * 标记任务失败
     */
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.endTime = Instant.now().toEpochMilli();
        this.error = error;
    }

    /**
     * 标记任务终止
     */
    public void markKilled() {
        this.status = TaskStatus.KILLED;
        this.endTime = Instant.now().toEpochMilli();
        this.abortSignal.set(true);
    }

    /**
     * 请求中止
     */
    public void requestAbort() {
        abortSignal.set(true);
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/lifecycle/LocalAgentTaskState.java
git commit -m "feat(subagent): add LocalAgentTaskState"
```

---

## 任务 3：ProgressTracker

**文件：**
- 创建：`src/main/java/agent/subagent/framework/ProgressTracker.java`

- [ ] **步骤 1：创建 ProgressTracker.java**

```java
package agent.subagent.framework;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进度追踪器
 *
 * 追踪工具调用次数、Token 使用量和最近活动
 */
public class ProgressTracker {
    private static final int MAX_RECENT_ACTIVITIES = 5;

    /** 工具调用次数 */
    private final AtomicLong toolUseCount = new AtomicLong(0);

    /** 最新输入 token 数 */
    private volatile long latestInputTokens;

    /** 累计输出 token 数 */
    private final AtomicLong cumulativeOutputTokens = new AtomicLong(0);

    /** 最近活动列表 */
    private final List<ToolActivity> recentActivities = new ArrayList<>();

    /**
     * 添加工具调用
     */
    public void addToolUse(String toolName, Map<String, Object> input) {
        toolUseCount.incrementAndGet();
        ToolActivity activity = new ToolActivity(toolName, input);
        synchronized (recentActivities) {
            recentActivities.add(activity);
            // 保持最多 MAX_RECENT_ACTIVITIES 条
            while (recentActivities.size() > MAX_RECENT_ACTIVITIES) {
                recentActivities.remove(0);
            }
        }
    }

    /**
     * 更新 token 使用量
     */
    public void updateTokens(long inputTokens, long outputTokens) {
        this.latestInputTokens = inputTokens;
        if (outputTokens > 0) {
            cumulativeOutputTokens.addAndGet(outputTokens);
        }
    }

    /**
     * 获取总 token 数
     */
    public long getTotalTokens() {
        return latestInputTokens + cumulativeOutputTokens.get();
    }

    /**
     * 获取工具调用次数
     */
    public long getToolUseCount() {
        return toolUseCount.get();
    }

    /**
     * 获取最近活动
     */
    public List<ToolActivity> getRecentActivities() {
        synchronized (recentActivities) {
            return new ArrayList<>(recentActivities);
        }
    }

    /**
     * 获取最后活动
     */
    public ToolActivity getLastActivity() {
        synchronized (recentActivities) {
            return recentActivities.isEmpty() ? null : recentActivities.get(recentActivities.size() - 1);
        }
    }

    /**
     * 工具活动记录
     */
    public static class ToolActivity {
        private final String toolName;
        private final Map<String, Object> input;
        private final String activityDescription;
        private final boolean isSearch;
        private final boolean isRead;
        private final long timestamp;

        public ToolActivity(String toolName, Map<String, Object> input) {
            this(toolName, input, null, false, false);
        }

        public ToolActivity(String toolName, Map<String, Object> input, String description,
                          boolean isSearch, boolean isRead) {
            this.toolName = toolName;
            this.input = input;
            this.activityDescription = description;
            this.isSearch = isSearch;
            this.isRead = isRead;
            this.timestamp = Instant.now().toEpochMilli();
        }

        public String getToolName() { return toolName; }
        public Map<String, Object> getInput() { return input; }
        public String getActivityDescription() { return activityDescription; }
        public boolean isSearch() { return isSearch; }
        public boolean isRead() { return isRead; }
        public long getTimestamp() { return timestamp; }
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/framework/ProgressTracker.java
git commit -m "feat(subagent): add ProgressTracker"
```

---

## 任务 4：TaskRegistry

**文件：**
- 创建：`src/main/java/agent/subagent/framework/TaskRegistry.java`

- [ ] **步骤 1：创建 TaskRegistry.java**

```java
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
    private static final String TASK_ID_PREFIX = "a";  // agent task prefix

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
        update(taskId, task -> {
            task.setStatus(TaskStatus.RUNNING);
        });
    }

    /**
     * 标记任务完成
     */
    public void markCompleted(String taskId) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.COMPLETED);
            task.setEndTime(Instant.now().toEpochMilli());
        });
        notifyObservers(tasks.get(taskId), TaskEvent.COMPLETED);
    }

    /**
     * 标记任务失败
     */
    public void markFailed(String taskId, String error) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.FAILED);
            task.setEndTime(Instant.now().toEpochMilli());
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
            task.setEndTime(Instant.now().toEpochMilli());
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
        long cutoff = Instant.now().toEpochMilli() - maxAgeMs;
        List<String> toRemove = new ArrayList<>();

        tasks.forEach((id, task) -> {
            if (task.isTerminal() && task.getEndTime() > 0 && task.getEndTime() < cutoff) {
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
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/framework/TaskRegistry.java
git commit -m "feat(subagent): add TaskRegistry"
```

---

## 任务 5：TaskFramework

**文件：**
- 创建：`src/main/java/agent/subagent/framework/TaskFramework.java`

- [ ] **步骤 1：创建 TaskFramework.java**

```java
package agent.subagent.framework;

import agent.subagent.types.AppState;
import agent.subagent.types.SetAppState;
import agent.subagent.types.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 任务框架核心
 *
 * 负责：
 * 1. 任务注册和生命周期管理
 * 2. 任务轮询和状态更新
 * 3. 生成任务附件（通知）
 * 4. 清理过期任务
 */
public class TaskFramework {
    private static final Logger log = LoggerFactory.getLogger(TaskFramework.class);

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 1000;

    /** 任务过期时间（毫秒），默认 5 分钟 */
    private static final long TASK_EXPIRY_MS = 5 * 60 * 1000;

    /** 清理间隔（毫秒），默认 1 分钟 */
    private static final long CLEANUP_INTERVAL_MS = 60 * 1000;

    private final TaskRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final SetAppState setAppState;
    private final AppStateGetter getAppState;

    private volatile boolean running = false;

    /**
     * 创建任务框架
     */
    public TaskFramework(TaskRegistry registry, SetAppState setAppState, AppStateGetter getAppState) {
        this.registry = registry;
        this.setAppState = setAppState;
        this.getAppState = getAppState;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("task-framework-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动任务框架
     */
    public void start() {
        if (running) return;
        running = true;

        // 启动轮询任务
        scheduler.scheduleAtFixedRate(
                this::pollTasks,
                0,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        // 启动清理任务
        scheduler.scheduleAtFixedRate(
                this::cleanupTasks,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        log.info("TaskFramework started");
    }

    /**
     * 停止任务框架
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        log.info("TaskFramework stopped");
    }

    /**
     * 轮询任务
     */
    private void pollTasks() {
        if (!running) return;

        try {
            List<TaskState> runningTasks = registry.getRunningTasks();
            for (TaskState task : runningTasks) {
                pollTask(task);
            }
        } catch (Exception e) {
            log.error("Error polling tasks", e);
        }
    }

    /**
     * 轮询单个任务
     *
     * 子类可以重写此方法来实现自定义轮询逻辑
     */
    protected void pollTask(TaskState task) {
        // 默认实现：检查任务超时
        if (task.getStartTime() > 0) {
            long elapsed = Instant.now().toEpochMilli() - task.getStartTime();
            if (elapsed > TASK_EXPIRY_MS) {
                log.warn("Task {} has been running for too long, marking as failed", task.getId());
                registry.markFailed(task.getId(), "Task timed out");
            }
        }
    }

    /**
     * 清理过期任务
     */
    private void cleanupTasks() {
        if (!running) return;

        try {
            int cleaned = registry.cleanupCompleted(TASK_EXPIRY_MS);
            if (cleaned > 0) {
                log.info("Cleaned up {} completed tasks", cleaned);
            }
        } catch (Exception e) {
            log.error("Error cleaning up tasks", e);
        }
    }

    /**
     * 生成任务附件（用于通知）
     */
    public List<TaskAttachment> generateAttachments() {
        return registry.getRunningTasks().stream()
                .map(this::createAttachment)
                .collect(Collectors.toList());
    }

    /**
     * 为单个任务创建附件
     */
    protected TaskAttachment createAttachment(TaskState task) {
        TaskAttachment attachment = new TaskAttachment();
        attachment.taskId = task.getId();
        attachment.taskType = task.getType().getValue();
        attachment.status = task.getStatus().getValue();
        attachment.description = task.getDescription();
        attachment.deltaSummary = null;
        return attachment;
    }

    /**
     * 获取运行中的任务数
     */
    public int getRunningTaskCount() {
        return registry.getRunningTasks().size();
    }

    /**
     * 获取任务注册表
     */
    public TaskRegistry getRegistry() {
        return registry;
    }

    /**
     * AppState 获取器接口
     */
    @FunctionalInterface
    public interface AppStateGetter {
        AppState get();
    }

    /**
     * 任务附件（用于通知）
     */
    public static class TaskAttachment {
        public String taskId;
        public String taskType;
        public String status;
        public String description;
        public String deltaSummary;
        public String toolUseId;
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/framework/TaskFramework.java
git commit -m "feat(subagent): add TaskFramework core"
```

---

## 任务 6：TaskPersistence

**文件：**
- 创建：`src/main/java/agent/subagent/framework/TaskPersistence.java`

- [ ] **步骤 1：创建 TaskPersistence.java**

```java
package agent.subagent.framework;

import agent.subagent.lifecycle.LocalAgentTaskState;
import agent.subagent.types.TaskState;
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
     * 持久化数据结构
     */
    private static class TaskPersistenceData {
        public int version;
        public long savedAt;
        public List<TaskState> tasks;
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/framework/TaskPersistence.java
git commit -m "feat(subagent): add TaskPersistence"
```

---

## 任务 7：创建阶段总结

**文件：**
- 创建：`docs/subagent/phase-0-summary.md`

- [ ] **步骤 1：创建阶段总结**

```markdown
# Phase 0: 基础设施 完成总结

## 交付物

### 类型定义
- `TaskType.java` - 任务类型枚举（LOCAL_AGENT, REMOTE_AGENT, IN_PROCESS_TEAMMATE, LOCAL_WORKFLOW）
- `TaskStatus.java` - 任务状态枚举（PENDING, RUNNING, COMPLETED, FAILED, KILLED）
- `SetAppState.java` - 状态更新函数式接口
- `TaskState.java` - 任务状态基类
- `Task.java` - Task 接口

### 生命周期
- `LocalAgentTaskState.java` - 本地代理任务状态实现

### 框架
- `ProgressTracker.java` - 进度追踪器
- `TaskRegistry.java` - 任务注册表（单例）
- `TaskFramework.java` - 任务框架核心
- `TaskPersistence.java` - 任务持久化

## 关键设计决策

1. **单例 Registry** - TaskRegistry 使用单例模式，与 Open-ClaudeCode 的设计一致
2. **观察者模式** - 通过 TaskObserver 接口支持任务事件监听
3. **ReadWriteLock** - TaskPersistence 使用读写锁提高并发性能
4. **线程池调度** - TaskFramework 使用 ScheduledExecutorService 进行轮询和清理

## 如何继续

Phase 1（Fork 核心）依赖此阶段的内容：

1. `TaskState` 基类 - 所有任务状态都继承它
2. `TaskRegistry` - Fork 执行器需要注册任务
3. `ProgressTracker` - Fork 执行器需要追踪进度

## 下一阶段

**Phase 1: Fork 核心**

- `ForkContext` - Fork 上下文
- `CacheSafeParams` - Cache 共享参数
- `ForkSubagentTool` - Fork 入口 Tool
- `ForkAgentExecutor` - Fork 执行器
- `SubagentContext` - 子代理上下文隔离

## 已知限制

1. 当前 TaskFramework 的轮询是简单超时检测，尚未实现真正的任务输出检测
2. 尚未实现与消息总线的集成
3. 尚未实现任务通知的 XML 格式生成

## 测试

运行以下命令验证：

```bash
mvn test -Dtest=TaskRegistryTest
mvn test -Dtest=TaskFrameworkTest
```
```

- [ ] **步骤 2：提交总结**

```bash
git add docs/subagent/phase-0-summary.md
git commit -m "docs: add Phase 0 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应任务 |
|---------|---------|
| Task 接口 | 任务 1 |
| TaskState 基类 | 任务 1 |
| TaskRegistry | 任务 4 |
| TaskFramework | 任务 5 |
| 进度追踪 | 任务 3 |
| 持久化 | 任务 6 |

### 类型一致性检查

- `TaskState.status` 类型是 `TaskStatus` ✓
- `TaskState.type` 类型是 `TaskType` ✓
- `LocalAgentTaskState` 继承 `TaskState` ✓

### 占位符扫描

无占位符，所有步骤都包含完整代码。

---

## 执行选择

**计划完成并保存到 `docs/zjkycode/plans/2026-04-21-subagent-phase-0.md`。两种执行选项：**

**1. 子代理驱动（推荐）** - 我为每个任务调度一个新子代理，在任务之间审查，快速迭代

**2. 内联执行** - 使用 executing-plans 在此会话中执行任务，带检查点的批量执行

**选择哪种方式？**
