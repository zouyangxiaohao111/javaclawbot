package agent.subagent.framework;

import agent.subagent.lifecycle.LocalAgentTaskState;
import agent.subagent.types.AppState;
import agent.subagent.types.SetAppState;
import agent.subagent.types.TaskState;
import agent.subagent.types.TaskStatus;
import agent.subagent.types.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务框架核心
 *
 * 对应 Open-ClaudeCode: src/utils/task/framework.ts - TaskFramework
 *
 * 职责：
 * 1. 任务注册和生命周期管理
 * 2. 任务轮询和状态更新
 * 3. 生成任务附件（通知）
 * 4. 清理过期任务
 */
public class TaskFramework {
    private static final Logger log = LoggerFactory.getLogger(TaskFramework.class);

    // =====================
    // 常量（对应 framework.ts）
    // =====================

    /** 轮询间隔（毫秒） */
    public static final long POLL_INTERVAL_MS = 1000;

    /** 终止任务在驱逐前显示的时间 */
    public static final long STOPPED_DISPLAY_MS = 3_000;

    /** 协调器面板中终态本地代理任务的宽限期 */
    public static final long PANEL_GRACE_MS = 30_000;

    // =====================
    // XML 标签常量（对应 constants/xml.ts）
    // =====================

    public static final String OUTPUT_FILE_TAG = "output-file";
    public static final String STATUS_TAG = "status";
    public static final String SUMMARY_TAG = "summary";
    public static final String TASK_ID_TAG = "task-id";
    public static final String TASK_NOTIFICATION_TAG = "task-notification";
    public static final String TASK_TYPE_TAG = "task-type";
    public static final String TOOL_USE_ID_TAG = "tool-use-id";
    public static final String WORKTREE_TAG = "worktree";
    public static final String WORKTREE_PATH_TAG = "worktreePath";
    public static final String WORKTREE_BRANCH_TAG = "worktreeBranch";

    // =====================
    // 核心组件
    // =====================

    private final TaskRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final SetAppState setAppState;
    private final TaskFramework.AppStateGetter getAppState;
    private final DiskTaskOutput diskOutput;
    private final SdkEventQueue sdkEventQueue;
    private final MessageQueueManager messageQueueManager;

    private volatile boolean running = false;

    /**
     * 创建任务框架
     */
    public TaskFramework(
            TaskRegistry registry,
            SetAppState setAppState,
            TaskFramework.AppStateGetter getAppState,
            DiskTaskOutput diskOutput,
            SdkEventQueue sdkEventQueue,
            MessageQueueManager messageQueueManager
    ) {
        this.registry = registry;
        this.setAppState = setAppState;
        this.getAppState = getAppState;
        this.diskOutput = diskOutput;
        this.sdkEventQueue = sdkEventQueue;
        this.messageQueueManager = messageQueueManager;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("task-framework-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    // =====================
    // 生命周期
    // =====================

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

    // =====================
    // 核心操作（对应 framework.ts）
    // =====================

    /**
     * 更新任务状态
     *
     * 对应 Open-ClaudeCode: updateTaskState()
     */
    public <T extends TaskState> void updateTaskState(
            String taskId,
            Function<T, T> updater
    ) {
        setAppState.accept(prev -> {
            T task = (T) prev.getTasks().get(taskId);
            if (task == null) {
                return prev;
            }
            T updated = updater.apply(task);
            if (updated == task) {
                // Updater returned same reference (early-return no-op)
                return prev;
            }
            Map<String, TaskState> newTasks = new HashMap<>(prev.getTasks());
            newTasks.put(taskId, updated);
            prev.setTasks(newTasks);
            return prev;
        });
    }

    /**
     * 注册新任务
     *
     * 对应 Open-ClaudeCode: registerTask()
     */
    public void registerTask(TaskState task) {
        final boolean[] isReplacement = {false};

        setAppState.accept(prev -> {
            TaskState existing = prev.getTasks().get(task.getId());
            isReplacement[0] = existing != null;

            // Carry forward UI-held state on re-register (resumeAgentBackground
            // replaces the task; user's retain shouldn't reset). startTime keeps
            // the panel sort stable; messages + diskLoaded preserve the viewed
            // transcript across the replace.
            TaskState merged = existing;
            if (existing != null && existing instanceof LocalAgentTaskState) {
                LocalAgentTaskState existingLocal = (LocalAgentTaskState) existing;
                LocalAgentTaskState taskLocal = task instanceof LocalAgentTaskState
                    ? (LocalAgentTaskState) task
                    : null;

                if (taskLocal != null) {
                    taskLocal.setRetain(existingLocal.isRetain());
                    taskLocal.setStartTime(existingLocal.getStartTime());
                    taskLocal.setMessages(existingLocal.getMessages());
                    taskLocal.setDiskLoaded(existingLocal.isDiskLoaded());
                    taskLocal.setPendingMessages(existingLocal.getPendingMessages());
                }
            }
            merged = task;

            Map<String, TaskState> newTasks = new HashMap<>(prev.getTasks());
            newTasks.put(task.getId(), merged);
            prev.setTasks(newTasks);
            return prev;
        });

        // Replacement (resume) — not a new start. Skip to avoid double-emit.
        if (isReplacement[0]) return;

        // Enqueue SdkEvent for task_started
        enqueueSdkEvent(new SdkEvent() {{
            type = "system";
            subtype = "task_started";
            task_id = task.getId();
            tool_use_id = task.getToolUseId();
            description = task.getDescription();
            task_type = task.getType().getValue();
        }});
    }

    /**
     * 急切地驱逐终态任务
     *
     * 对应 Open-ClaudeCode: evictTerminalTask()
     */
    public void evictTerminalTask(String taskId) {
        setAppState.accept(prev -> {
            TaskState task = prev.getTasks().get(taskId);
            if (task == null) return prev;
            if (!isTerminalTaskStatus(task.getStatus())) return prev;
            if (!task.isNotified()) return prev;

            // Panel grace period — blocks eviction until deadline passes
            if (task instanceof LocalAgentTaskState) {
                LocalAgentTaskState localTask = (LocalAgentTaskState) task;
                Long evictAfter = localTask.getEvictAfter();
                if (evictAfter != null && evictAfter > System.currentTimeMillis()) {
                    return prev;
                }
            }

            Map<String, TaskState> newTasks = new HashMap<>(prev.getTasks());
            newTasks.remove(taskId);
            prev.setTasks(newTasks);
            return prev;
        });
    }

    /**
     * 判断状态是否为终态
     *
     * 对应 Open-ClaudeCode: isTerminalTaskStatus()
     */
    public static boolean isTerminalTaskStatus(TaskStatus status) {
        return status == TaskStatus.COMPLETED
            || status == TaskStatus.FAILED
            || status == TaskStatus.KILLED;
    }

    // =====================
    // 轮询和附件生成
    // =====================

    /**
     * 轮询所有运行中的任务并检查更新
     *
     * 对应 Open-ClaudeCode: pollTasks()
     */
    private void pollTasks() {
        if (!running) return;

        try {
            AppState state = getAppState.get();
            GenerateTaskAttachmentsResult result = generateTaskAttachments(state);

            // Apply offset patches and evictions
            applyTaskOffsetsAndEvictions(
                    result.updatedTaskOffsets,
                    result.evictedTaskIds
            );

            // Send notifications for completed tasks
            for (TaskAttachment attachment : result.attachments) {
                enqueueTaskNotification(attachment);
            }
        } catch (Exception e) {
            log.error("Error polling tasks", e);
        }
    }

    /**
     * 生成任务附件
     *
     * 对应 Open-ClaudeCode: generateTaskAttachments()
     */
    private GenerateTaskAttachmentsResult generateTaskAttachments(AppState state) {
        List<TaskAttachment> attachments = new ArrayList<>();
        Map<String, Long> updatedTaskOffsets = new HashMap<>();
        List<String> evictedTaskIds = new ArrayList<>();

        Map<String, TaskState> tasks = state.getTasks();
        if (tasks == null) {
            return new GenerateTaskAttachmentsResult(attachments, updatedTaskOffsets, evictedTaskIds);
        }

        for (TaskState taskState : tasks.values()) {
            if (taskState.isNotified()) {
                if (isTerminalTaskStatus(taskState.getStatus())) {
                    // Evict terminal tasks — they've been consumed and can be GC'd
                    evictedTaskIds.add(taskState.getId());
                    continue;
                }
                if (taskState.getStatus() == TaskStatus.PENDING) {
                    // Keep in map — hasn't run yet, but parent already knows about it
                    continue;
                }
                if (taskState.getStatus() == TaskStatus.RUNNING) {
                    // Fall through to running logic below
                }
            }

            if (taskState.getStatus() == TaskStatus.RUNNING) {
                // Get delta (new content) since last read
                DiskTaskOutput.DiskOutputDelta delta = DiskTaskOutput.getOutputDelta(
                        taskState.getId(),
                        taskState.getOutputOffset()
                );
                if (delta.content != null && !delta.content.isEmpty()) {
                    updatedTaskOffsets.put(taskState.getId(), delta.newOffset);

                    // Create attachment for delta
                    TaskAttachment attachment = new TaskAttachment();
                    attachment.taskId = taskState.getId();
                    attachment.taskType = taskState.getType();
                    attachment.status = taskState.getStatus();
                    attachment.description = taskState.getDescription();
                    attachment.deltaSummary = delta.content;
                    attachment.toolUseId = taskState.getToolUseId();
                    attachments.add(attachment);
                }
            }
        }

        return new GenerateTaskAttachmentsResult(attachments, updatedTaskOffsets, evictedTaskIds);
    }

    /**
     * 应用输出偏移量补丁和驱逐
     *
     * 对应 Open-ClaudeCode: applyTaskOffsetsAndEvictions()
     */
    private void applyTaskOffsetsAndEvictions(
            Map<String, Long> updatedTaskOffsets,
            List<String> evictedTaskIds
    ) {
        if (updatedTaskOffsets.isEmpty() && evictedTaskIds.isEmpty()) {
            return;
        }

        setAppState.accept(prev -> {
            boolean changed = false;
            Map<String, TaskState> newTasks = new HashMap<>(prev.getTasks());

            // Apply offset patches
            for (Map.Entry<String, Long> entry : updatedTaskOffsets.entrySet()) {
                String id = entry.getKey();
                TaskState fresh = newTasks.get(id);
                // Re-check status on fresh state — task may have completed
                if (fresh != null && fresh.getStatus() == TaskStatus.RUNNING) {
                    fresh.setOutputOffset(entry.getValue());
                    changed = true;
                }
            }

            // Apply evictions
            for (String id : evictedTaskIds) {
                TaskState fresh = newTasks.get(id);
                // Re-check terminal+notified on fresh state
                if (fresh == null) continue;
                if (!isTerminalTaskStatus(fresh.getStatus())) continue;
                if (!fresh.isNotified()) continue;

                // Panel grace period check
                if (fresh instanceof LocalAgentTaskState) {
                    LocalAgentTaskState localTask = (LocalAgentTaskState) fresh;
                    Long evictAfter = localTask.getEvictAfter();
                    if (evictAfter != null && evictAfter > System.currentTimeMillis()) {
                        continue;
                    }
                }

                newTasks.remove(id);
                changed = true;
            }

            if (changed) {
                prev.setTasks(newTasks);
                return prev;
            }
            return null; // No change
        });
    }

    // =====================
    // 通知
    // =====================

    /**
     * 将任务通知入队
     *
     * 对应 Open-ClaudeCode: enqueueTaskNotification()
     */
    private void enqueueTaskNotification(TaskAttachment attachment) {
        String statusText = getStatusText(attachment.status);

        String outputPath = diskOutput.getTaskOutputPath(attachment.taskId);
        String toolUseIdLine = attachment.toolUseId != null
                ? "\n<" + TOOL_USE_ID_TAG + ">" + attachment.toolUseId + "</" + TOOL_USE_ID_TAG + ">"
                : "";

        String message = "<" + TASK_NOTIFICATION_TAG + ">\n" +
                "<" + TASK_ID_TAG + ">" + attachment.taskId + "</" + TASK_ID_TAG + ">" +
                toolUseIdLine + "\n" +
                "<" + TASK_TYPE_TAG + ">" + attachment.taskType.getValue() + "</" + TASK_TYPE_TAG + ">\n" +
                "<" + OUTPUT_FILE_TAG + ">" + outputPath + "</" + OUTPUT_FILE_TAG + ">\n" +
                "<" + STATUS_TAG + ">" + attachment.status.getValue() + "</" + STATUS_TAG + ">\n" +
                "<" + SUMMARY_TAG + ">Task \"" + attachment.description + "\" " + statusText + "</" + SUMMARY_TAG + ">\n" +
                "</" + TASK_NOTIFICATION_TAG + ">";

        messageQueueManager.enqueuePendingNotification(message, "task-notification");
    }

    /**
     * 获取状态文本
     *
     * 对应 Open-ClaudeCode: getStatusText()
     */
    private static String getStatusText(TaskStatus status) {
        switch (status) {
            case COMPLETED: return "completed successfully";
            case FAILED: return "failed";
            case KILLED: return "was stopped";
            case RUNNING: return "is running";
            case PENDING: return "is pending";
            default: return "unknown";
        }
    }

    /**
     * 发送 SdkEvent
     */
    private void enqueueSdkEvent(SdkEvent event) {
        if (sdkEventQueue != null) {
            sdkEventQueue.enqueue(event);
        }
    }

    // =====================
    // 内部类
    // =====================

    /**
     * SdkEvent（简化版）
     */
    public static class SdkEvent {
        public String type;
        public String subtype;
        public String task_id;
        public String tool_use_id;
        public String description;
        public String task_type;
        public String workflow_name;
        public String prompt;
    }

    /**
     * 任务附件
     *
     * 对应 Open-ClaudeCode: TaskAttachment
     */
    public static class TaskAttachment {
        public String taskId;
        public TaskType taskType;
        public TaskStatus status;
        public String description;
        public String deltaSummary;
        public String toolUseId;
    }

    /**
     * 生成附件结果
     */
    private static class GenerateTaskAttachmentsResult {
        List<TaskAttachment> attachments;
        Map<String, Long> updatedTaskOffsets;
        List<String> evictedTaskIds;

        GenerateTaskAttachmentsResult(
                List<TaskAttachment> attachments,
                Map<String, Long> updatedTaskOffsets,
                List<String> evictedTaskIds
        ) {
            this.attachments = attachments;
            this.updatedTaskOffsets = updatedTaskOffsets;
            this.evictedTaskIds = evictedTaskIds;
        }
    }

    /**
     * Disk 输出增量
     */
    public static class DiskOutputDelta {
        public String content;
        public long newOffset;
    }

    /**
     * AppState 获取器接口
     */
    @FunctionalInterface
    public interface AppStateGetter {
        AppState get();
    }

    // =====================
    // SdkEventQueue 接口
    // =====================

    public interface SdkEventQueue {
        void enqueue(SdkEvent event);
    }

    // =====================
    // MessageQueueManager 接口
    // =====================

    public interface MessageQueueManager {
        void enqueuePendingNotification(String message, String mode);
    }
}
