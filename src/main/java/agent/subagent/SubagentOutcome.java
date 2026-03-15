package agent.subagent;

/**
 * 子Agent执行结果
 *
 * 对应 OpenClaw: src/agents/subagent-announce.ts - SubagentRunOutcome
 */
public class SubagentOutcome {

    public enum Status {
        OK,       // 成功完成
        ERROR,    // 执行出错
        TIMEOUT,  // 超时
        UNKNOWN   // 未知状态
    }

    private final Status status;
    private final String error;
    private final String summary;

    public SubagentOutcome(Status status) {
        this(status, null, null);
    }

    public SubagentOutcome(Status status, String error) {
        this(status, error, null);
    }

    public SubagentOutcome(Status status, String error, String summary) {
        this.status = status != null ? status : Status.UNKNOWN;
        this.error = error;
        this.summary = summary;
    }

    public static SubagentOutcome ok() {
        return new SubagentOutcome(Status.OK);
    }

    public static SubagentOutcome ok(String summary) {
        return new SubagentOutcome(Status.OK, null, summary);
    }

    public static SubagentOutcome error(String error) {
        return new SubagentOutcome(Status.ERROR, error);
    }

    public static SubagentOutcome timeout() {
        return new SubagentOutcome(Status.TIMEOUT);
    }

    public static SubagentOutcome unknown() {
        return new SubagentOutcome(Status.UNKNOWN);
    }

    public Status getStatus() { return status; }
    public String getError() { return error; }
    public String getSummary() { return summary; }

    public boolean isOk() { return status == Status.OK; }
    public boolean isError() { return status == Status.ERROR; }
    public boolean isTimeout() { return status == Status.TIMEOUT; }

    /**
     * 获取状态描述文本
     */
    public String getStatusText() {
        switch (status) {
            case OK: return "completed successfully";
            case ERROR: return error != null ? "error: " + error : "error";
            case TIMEOUT: return "timeout";
            default: return "unknown";
        }
    }

    @Override
    public String toString() {
        return "SubagentOutcome{" +
                "status=" + status +
                ", error='" + error + '\'' +
                ", summary='" + summary + '\'' +
                '}';
    }
}