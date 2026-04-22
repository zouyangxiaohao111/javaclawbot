package agent.subagent.lifecycle;

import agent.subagent.types.TaskState;
import agent.subagent.types.TaskType;
import agent.subagent.types.TaskStatus;
import agent.subagent.framework.ProgressTracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地代理任务状态
 *
 * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - LocalAgentTaskState
 *
 * 完整定义所有字段和方法
 */
public class LocalAgentTaskState extends TaskState {
    /** Agent ID */
    private String agentId;

    /** 任务描述 */
    private String prompt;

    /** 选中的代理定义 */
    private Object selectedAgent;  // AgentDefinition

    /** 代理类型 */
    private String agentType;

    /** 模型 */
    private String model;

    /** 中止控制器 */
    private AtomicBoolean abortController;

    /** 清理回调 */
    private Runnable unregisterCleanup;

    /** 错误信息 */
    private String error;

    /** 执行结果 */
    private Object result;  // AgentToolResult

    /** 进度追踪器引用 */
    private ProgressTracker progressTracker;

    /** 消息历史 */
    private List<Object> messages;  // Message[]

    /** 已检索（磁盘加载完成） */
    private boolean retrieved;

    /** 最后报告的工具计数 */
    private int lastReportedToolCount;

    /** 最后报告的 token 计数 */
    private int lastReportedTokenCount;

    /** 是否后台运行（false = 前台运行，true = 后台） */
    private boolean isBackgrounded;

    /** 待处理消息（通过 SendMessage 在轮次边界排空） */
    private CopyOnWriteArrayList<String> pendingMessages;

    /** UI 是否持有此任务：阻止驱逐、启用流追加、触发磁盘引导 */
    private boolean retain;

    /** 磁盘已引导：侧链 JSONL 已读取并 UUID 合并到 messages */
    private boolean diskLoaded;

    /** 面板可见性截止时间戳 */
    private Long evictAfter;

    /** 工作目录 */
    private String worktreePath;

    /** 工作目录分支 */
    private String worktreeBranch;

    /** 工具使用计数 */
    private int toolUseCount;

    /** token 计数 */
    private int tokenCount;

    public LocalAgentTaskState() {
        this.type = TaskType.LOCAL_AGENT;
        this.status = TaskStatus.PENDING;
        this.abortController = new AtomicBoolean(false);
        this.messages = new CopyOnWriteArrayList<>();
        this.pendingMessages = new CopyOnWriteArrayList<>();
        this.retrieved = false;
        this.isBackgrounded = true;  // 默认后台
        this.retain = false;
        this.diskLoaded = false;
    }

    // =====================
    // 静态工厂方法
    // =====================

    /**
     * 创建本地代理任务状态
     *
     * 对应 Open-ClaudeCode: createLocalAgentTaskState()
     */
    public static LocalAgentTaskState create(
            String id,
            String description,
            String toolUseId,
            String prompt,
            String agentType
    ) {
        LocalAgentTaskState state = new LocalAgentTaskState();
        state.id = id;
        state.description = description;
        state.toolUseId = toolUseId;
        state.prompt = prompt;
        state.agentType = agentType;
        state.startTime = Instant.now();
        state.outputFile = getTaskOutputPath(id);
        return state;
    }

    // =====================
    // Getters
    // =====================

    public String getAgentId() { return agentId; }
    public String getPrompt() { return prompt; }
    public Object getSelectedAgent() { return selectedAgent; }
    public String getAgentType() { return agentType; }
    public String getModel() { return model; }
    public AtomicBoolean getAbortController() { return abortController; }
    public Runnable getUnregisterCleanup() { return unregisterCleanup; }
    public String getError() { return error; }
    public Object getResult() { return result; }
    public ProgressTracker getProgressTracker() { return progressTracker; }
    public List<Object> getMessages() { return messages; }
    public void setMessages(List<Object> messages) { this.messages = messages; }
    public boolean isRetrieved() { return retrieved; }
    public int getLastReportedToolCount() { return lastReportedToolCount; }
    public int getLastReportedTokenCount() { return lastReportedTokenCount; }
    public boolean isBackgrounded() { return isBackgrounded; }
    public List<String> getPendingMessages() { return pendingMessages; }
    public void setPendingMessages(List<String> messages) {
        this.pendingMessages = new CopyOnWriteArrayList<>(messages);
    }
    public boolean isRetain() { return retain; }
    public boolean isDiskLoaded() { return diskLoaded; }
    public Long getEvictAfter() { return evictAfter; }
    public String getWorktreePath() { return worktreePath; }
    public String getWorktreeBranch() { return worktreeBranch; }
    public int getToolUseCount() { return toolUseCount; }
    public int getTokenCount() { return tokenCount; }

    // =====================
    // Setters
    // =====================

    public void setAgentId(String agentId) { this.agentId = agentId; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public void setSelectedAgent(Object selectedAgent) { this.selectedAgent = selectedAgent; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public void setModel(String model) { this.model = model; }
    public void setAbortController(AtomicBoolean abortController) { this.abortController = abortController; }
    public void setUnregisterCleanup(Runnable unregisterCleanup) { this.unregisterCleanup = unregisterCleanup; }
    public void setError(String error) { this.error = error; }
    public void setResult(Object result) { this.result = result; }
    public void setProgressTracker(ProgressTracker progressTracker) { this.progressTracker = progressTracker; }
    public void setRetrieved(boolean retrieved) { this.retrieved = retrieved; }
    public void setLastReportedToolCount(int count) { this.lastReportedToolCount = count; }
    public void setLastReportedTokenCount(int count) { this.lastReportedTokenCount = count; }
    public void setBackgrounded(boolean backgrounded) { this.isBackgrounded = backgrounded; }
    public void setRetain(boolean retain) { this.retain = retain; }
    public void setDiskLoaded(boolean diskLoaded) { this.diskLoaded = diskLoaded; }
    public void setEvictAfter(Long evictAfter) { this.evictAfter = evictAfter; }
    public void setWorktreePath(String path) { this.worktreePath = path; }
    public void setWorktreeBranch(String branch) { this.worktreeBranch = branch; }
    public void setToolUseCount(int count) { this.toolUseCount = count; }
    public void setTokenCount(int count) { this.tokenCount = count; }

    // =====================
    // 状态操作方法
    // =====================

    /**
     * 标记任务开始
     */
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = Instant.now();
    }

    /**
     * 标记任务完成
     */
    public void markCompleted(Object result) {
        this.status = TaskStatus.COMPLETED;
        this.endTime = Instant.now();
        this.result = result;
    }

    /**
     * 标记任务失败
     */
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.endTime = Instant.now();
        this.error = error;
    }

    /**
     * 标记任务终止
     */
    public void markKilled() {
        this.status = TaskStatus.KILLED;
        this.endTime = Instant.now();
        if (this.abortController != null) {
            this.abortController.set(true);
        }
    }

    /**
     * 请求中止
     */
    public void requestAbort() {
        if (this.abortController != null) {
            this.abortController.set(true);
        }
    }

    /**
     * 判断是否正在运行
     */
    public boolean isRunning() {
        return this.status == TaskStatus.RUNNING;
    }

    // =====================
    // 消息操作方法
    // =====================

    /**
     * 添加消息
     */
    public void addMessage(Object message) {
        this.messages.add(message);
    }

    /**
     * 添加待处理消息
     */
    public void addPendingMessage(String message) {
        this.pendingMessages.add(message);
    }

    /**
     * 清除并获取所有待处理消息
     */
    public List<String> drainPendingMessages() {
        List<String> drained = new ArrayList<>(this.pendingMessages);
        this.pendingMessages.clear();
        return drained;
    }

    // =====================
    // 工具方法
    // =====================

    /**
     * 获取任务输出路径
     */
    private static String getTaskOutputPath(String taskId) {
        // 应该在 TaskFramework 中实现
        return ".javaclawbot/task-output/" + taskId + ".jsonl";
    }

    /**
     * 判断是否为面板代理任务（非主会话）
     */
    public boolean isPanelAgentTask() {
        return "local_agent".equals(this.type.getValue()) && !"main-session".equals(this.agentType);
    }
}
