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

    public SubagentOutcome(Status status, String error) {
        this.status = status != null ? status : Status.UNKNOWN;
        this.error = error;
    }

    public static SubagentOutcome ok() {
        return new SubagentOutcome(Status.OK, null);
    }

    public static SubagentOutcome error(String error) {
        return new SubagentOutcome(Status.ERROR, error);
    }

    public static SubagentOutcome timeout() {
        return new SubagentOutcome(Status.TIMEOUT, null);
    }

    public Status getStatus() { return status; }
    public String getError() { return error; }

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
                '}';
    }
}
