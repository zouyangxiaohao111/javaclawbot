package agent.subagent.lifecycle;

import agent.subagent.types.TaskState;
import agent.subagent.types.TaskType;
import agent.subagent.types.TaskStatus;
import agent.subagent.framework.ProgressTracker;

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

    /** 消息历史 - 使用 Map 表示消息 */
    private List<Map<String, Object>> messages;

    /** 是否后台运行 */
    private boolean isBackgrounded;

    /** 待处理消息 */
    private Map<String, String> pendingMessages;

    /** 错误信息 */
    private String error;

    /** 执行结果 */
    private String result;

    /** 工作目录 */
    private String worktreePath;

    public LocalAgentTaskState() {
        this.type = TaskType.LOCAL_AGENT;
        this.status = TaskStatus.PENDING;
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
        state.startTime = Instant.now().toEpochMilli();
        return state;
    }

    // Getters
    public String getAgentId() { return agentId; }
    public String getPrompt() { return prompt; }
    public String getSelectedAgentType() { return selectedAgentType; }
    public String getModel() { return model; }
    public AtomicBoolean getAbortSignal() { return abortSignal; }
    public ProgressTracker getProgressTracker() { return progressTracker; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public boolean isBackgrounded() { return isBackgrounded; }
    public Map<String, String> getPendingMessages() { return pendingMessages; }
    public String getError() { return error; }
    public String getResult() { return result; }
    public String getWorktreePath() { return worktreePath; }

    // Setters
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public void setSelectedAgentType(String selectedAgentType) { this.selectedAgentType = selectedAgentType; }
    public void setModel(String model) { this.model = model; }
    public void setProgressTracker(ProgressTracker progressTracker) { this.progressTracker = progressTracker; }
    public void setBackgrounded(boolean backgrounded) { isBackgrounded = backgrounded; }
    public void setError(String error) { this.error = error; }
    public void setResult(String result) { this.result = result; }
    public void setWorktreePath(String worktreePath) { this.worktreePath = worktreePath; }

    /**
     * 添加消息
     */
    public void addMessage(Map<String, Object> message) {
        this.messages.add(message);
    }

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

    /**
     * 判断是否正在运行
     */
    public boolean isRunning() {
        return this.status == TaskStatus.RUNNING;
    }
}
