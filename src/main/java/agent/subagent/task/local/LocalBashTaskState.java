package agent.subagent.task.local;

import agent.subagent.task.TaskState;
import agent.subagent.task.TaskType;
import agent.subagent.task.TaskStatus;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local bash task state.
 * 对应 Open-ClaudeCode: src/tasks/LocalShellTask/LocalShellTask.tsx - LocalShellTaskState
 */
public class LocalBashTaskState extends TaskState {

    private String command;
    private String shellCommand;
    private String cwd;
    private java.util.Map<String, String> env;
    private transient Process process;
    private boolean destroyed;
    private Integer exitCode;
    private StringBuilder stdout;
    private StringBuilder stderr;
    private AtomicBoolean abortController;
    private Long timeoutMs;
    private boolean keepProcessAlive;
    private long pid;
    private boolean completionStatusSentInAttachment;
    private int lastReportedTotalLines;
    private boolean isBackgrounded;
    private String agentId;
    private String kind;
    private Runnable unregisterCleanup;

    public LocalBashTaskState() {
        this.type = TaskType.LOCAL_BASH;
        this.status = TaskStatus.PENDING;
        this.abortController = new AtomicBoolean(false);
        this.stdout = new StringBuilder();
        this.stderr = new StringBuilder();
        this.destroyed = false;
        this.keepProcessAlive = false;
        this.isBackgrounded = false;
        this.kind = "bash";
    }

    public static LocalBashTaskState create(
            String id,
            String description,
            String command,
            String shellCommand
    ) {
        LocalBashTaskState state = new LocalBashTaskState();
        state.id = id;
        state.description = description;
        state.command = command;
        state.shellCommand = shellCommand;
        state.startTime = Instant.now();
        return state;
    }

    // =====================
    // Getters
    // =====================

    public String getCommand() { return command; }
    public String getShellCommand() { return shellCommand; }
    public String getCwd() { return cwd; }
    public java.util.Map<String, String> getEnv() { return env; }
    public Process getProcess() { return process; }
    public boolean isDestroyed() { return destroyed; }
    public Integer getExitCode() { return exitCode; }
    public StringBuilder getStdout() { return stdout; }
    public StringBuilder getStderr() { return stderr; }
    public AtomicBoolean getAbortController() { return abortController; }
    public Long getTimeoutMs() { return timeoutMs; }
    public boolean isKeepProcessAlive() { return keepProcessAlive; }
    public long getPid() { return pid; }
    public boolean isCompletionStatusSentInAttachment() { return completionStatusSentInAttachment; }
    public int getLastReportedTotalLines() { return lastReportedTotalLines; }
    public boolean isBackgrounded() { return isBackgrounded; }
    public String getAgentId() { return agentId; }
    public String getKind() { return kind; }
    public Runnable getUnregisterCleanup() { return unregisterCleanup; }

    // =====================
    // Setters
    // =====================

    public void setCommand(String command) { this.command = command; }
    public void setShellCommand(String shellCommand) { this.shellCommand = shellCommand; }
    public void setCwd(String cwd) { this.cwd = cwd; }
    public void setEnv(java.util.Map<String, String> env) { this.env = env; }
    public void setProcess(Process process) {
        this.process = process;
        if (process != null) {
            try {
                this.pid = process.pid();
            } catch (Exception e) {
                // Ignore if pid is not available
            }
        }
    }
    public void setDestroyed(boolean destroyed) { this.destroyed = destroyed; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public void setStdout(StringBuilder stdout) { this.stdout = stdout; }
    public void setStderr(StringBuilder stderr) { this.stderr = stderr; }
    public void setAbortController(AtomicBoolean abortController) { this.abortController = abortController; }
    public void setTimeoutMs(Long timeoutMs) { this.timeoutMs = timeoutMs; }
    public void setKeepProcessAlive(boolean keepProcessAlive) { this.keepProcessAlive = keepProcessAlive; }
    public void setPid(long pid) { this.pid = pid; }
    public void setCompletionStatusSentInAttachment(boolean completionStatusSentInAttachment) { this.completionStatusSentInAttachment = completionStatusSentInAttachment; }
    public void setLastReportedTotalLines(int lastReportedTotalLines) { this.lastReportedTotalLines = lastReportedTotalLines; }
    public void setBackgrounded(boolean isBackgrounded) { this.isBackgrounded = isBackgrounded; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public void setKind(String kind) { this.kind = kind; }
    public void setUnregisterCleanup(Runnable unregisterCleanup) { this.unregisterCleanup = unregisterCleanup; }

    // =====================
    // State Operations
    // =====================

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = Instant.now();
    }

    public void markCompleted(int exitCode) {
        this.status = TaskStatus.COMPLETED;
        this.endTime = Instant.now();
        this.exitCode = exitCode;
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.endTime = Instant.now();
    }

    public void markKilled() {
        this.status = TaskStatus.KILLED;
        this.endTime = Instant.now();
        this.destroyed = true;
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

    public void destroyProcess() {
        if (this.process != null && !this.destroyed) {
            this.process.destroyForcibly();
            this.destroyed = true;
        }
    }

    // =====================
    // Output Operations
    // =====================

    public void appendStdout(String text) {
        this.stdout.append(text);
    }

    public void appendStderr(String text) {
        this.stderr.append(text);
    }

    public String getStdoutString() {
        return stdout != null ? stdout.toString() : "";
    }

    public String getStderrString() {
        return stderr != null ? stderr.toString() : "";
    }

    // =====================
    // Copy (for immutable updates)
    // =====================

    @Override
    public TaskState copy() {
        LocalBashTaskState copy = new LocalBashTaskState();
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
        copy.command = this.command;
        copy.shellCommand = this.shellCommand;
        copy.cwd = this.cwd;
        copy.env = this.env != null ? new java.util.HashMap<>(this.env) : null;
        copy.process = this.process;
        copy.destroyed = this.destroyed;
        copy.exitCode = this.exitCode;
        copy.stdout = new StringBuilder(this.stdout.toString());
        copy.stderr = new StringBuilder(this.stderr.toString());
        copy.abortController = this.abortController;
        copy.timeoutMs = this.timeoutMs;
        copy.keepProcessAlive = this.keepProcessAlive;
        copy.pid = this.pid;
        copy.completionStatusSentInAttachment = this.completionStatusSentInAttachment;
        copy.lastReportedTotalLines = this.lastReportedTotalLines;
        copy.isBackgrounded = this.isBackgrounded;
        copy.agentId = this.agentId;
        copy.kind = this.kind;
        copy.unregisterCleanup = this.unregisterCleanup;
        return copy;
    }
}
