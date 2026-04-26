package agent.subagent.task.local;

import agent.subagent.task.TaskState;
import agent.subagent.task.TaskType;
import agent.subagent.task.TaskStatus;
import agent.subagent.framework.ProgressTracker;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local agent task state.
 * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - LocalAgentTaskState
 *
 * export type LocalAgentTaskState = TaskStateBase & {
 *   type: 'local_agent';
 *   agentId: string;
 *   prompt: string;
 *   selectedAgent?: AgentDefinition;
 *   agentType: string;
 *   model?: string;
 *   abortController?: AbortController;
 *   unregisterCleanup?: () => void;
 *   error?: string;
 *   result?: AgentToolResult;
 *   progress?: AgentProgress;
 *   retrieved: boolean;
 *   messages?: Message[];
 *   lastReportedToolCount: number;
 *   lastReportedTokenCount: number;
 *   isBackgrounded: boolean;
 *   pendingMessages: string[];
 *   retain: boolean;
 *   diskLoaded: boolean;
 *   evictAfter?: number;
 * };
 */
public class LocalAgentTaskState extends TaskState {

    private String agentId;
    private String prompt;
    private Object selectedAgent;
    private String agentType;
    private String model;
    private AtomicBoolean abortController;
    private Runnable unregisterCleanup;
    private String error;
    private Object result;
    private Object progress;
    private ProgressTracker progressTracker;
    private boolean retrieved;
    private List<Object> messages;
    private int lastReportedToolCount;
    private int lastReportedTokenCount;
    private boolean isBackgrounded;
    private List<String> pendingMessages;
    private boolean retain;
    private boolean diskLoaded;
    private Long evictAfter;

    public LocalAgentTaskState() {
        this.type = TaskType.LOCAL_AGENT;
        this.status = TaskStatus.PENDING;
        this.abortController = new AtomicBoolean(false);
        this.messages = new CopyOnWriteArrayList<>();
        this.pendingMessages = new CopyOnWriteArrayList<>();
        this.retrieved = false;
        this.isBackgrounded = true;
        this.retain = false;
        this.diskLoaded = false;
    }

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
    public Object getProgress() { return progress; }
    public ProgressTracker getProgressTracker() { return progressTracker; }
    public boolean isRetrieved() { return retrieved; }
    public List<Object> getMessages() { return messages; }
    public int getLastReportedToolCount() { return lastReportedToolCount; }
    public int getLastReportedTokenCount() { return lastReportedTokenCount; }
    public boolean isBackgrounded() { return isBackgrounded; }
    public List<String> getPendingMessages() { return pendingMessages; }
    public boolean isRetain() { return retain; }
    public boolean isDiskLoaded() { return diskLoaded; }
    public Long getEvictAfter() { return evictAfter; }

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
    public void setProgress(Object progress) { this.progress = progress; }
    public void setProgressTracker(ProgressTracker progressTracker) { this.progressTracker = progressTracker; }
    public void setRetrieved(boolean retrieved) { this.retrieved = retrieved; }
    public void setMessages(List<Object> messages) { this.messages = messages; }
    public void setLastReportedToolCount(int count) { this.lastReportedToolCount = count; }
    public void setLastReportedTokenCount(int count) { this.lastReportedTokenCount = count; }
    public void setBackgrounded(boolean backgrounded) { this.isBackgrounded = backgrounded; }
    public void setPendingMessages(List<String> messages) { this.pendingMessages = new CopyOnWriteArrayList<>(messages); }
    public void setRetain(boolean retain) { this.retain = retain; }
    public void setDiskLoaded(boolean diskLoaded) { this.diskLoaded = diskLoaded; }
    public void setEvictAfter(Long evictAfter) { this.evictAfter = evictAfter; }

    // =====================
    // State Operations
    // =====================

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = Instant.now();
    }

    public void markCompleted(Object result) {
        this.status = TaskStatus.COMPLETED;
        this.endTime = Instant.now();
        this.result = result;
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.endTime = Instant.now();
        this.error = error;
    }

    public void markKilled() {
        this.status = TaskStatus.KILLED;
        this.endTime = Instant.now();
        if (this.abortController != null) {
            this.abortController.set(true);
        }
    }

    public void requestAbort() {
        if (this.abortController != null) {
            this.abortController.set(true);
        }
    }

    public boolean isRunning() {
        return this.status == TaskStatus.RUNNING;
    }

    // =====================
    // Message Operations
    // =====================

    public void addMessage(Object message) {
        this.messages.add(message);
    }

    public void addPendingMessage(String message) {
        this.pendingMessages.add(message);
    }

    public List<String> drainPendingMessages() {
        List<String> drained = new java.util.ArrayList<>(this.pendingMessages);
        this.pendingMessages.clear();
        return drained;
    }

    // =====================
    // Copy (for immutable updates)
    // =====================

    @Override
    public TaskState copy() {
        LocalAgentTaskState copy = new LocalAgentTaskState();
        copy.id = this.id;
        copy.type = this.type;
        copy.status = this.status;
        copy.description = this.description;
        copy.toolUseId = this.toolUseId;
        copy.startTime = this.startTime;
        copy.endTime = this.endTime;
        copy.totalPausedMs = this.totalPausedMs;
        copy.outputFile = this.outputFile;
        copy.outputOffset = this.outputOffset;
        copy.notified = this.notified;
        copy.agentId = this.agentId;
        copy.prompt = this.prompt;
        copy.selectedAgent = this.selectedAgent;
        copy.agentType = this.agentType;
        copy.model = this.model;
        copy.abortController = this.abortController;
        copy.unregisterCleanup = this.unregisterCleanup;
        copy.error = this.error;
        copy.result = this.result;
        copy.progress = this.progress;
        copy.progressTracker = this.progressTracker;
        copy.retrieved = this.retrieved;
        copy.messages = new CopyOnWriteArrayList<>(this.messages);
        copy.lastReportedToolCount = this.lastReportedToolCount;
        copy.lastReportedTokenCount = this.lastReportedTokenCount;
        copy.isBackgrounded = this.isBackgrounded;
        copy.pendingMessages = new CopyOnWriteArrayList<>(this.pendingMessages);
        copy.retain = this.retain;
        copy.diskLoaded = this.diskLoaded;
        copy.evictAfter = this.evictAfter;
        return copy;
    }
}
