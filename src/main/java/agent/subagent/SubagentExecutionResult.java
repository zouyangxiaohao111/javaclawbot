package agent.subagent;

/**
 * 子Agent执行结果
 */
public class SubagentExecutionResult {

    private final SubagentOutcome outcome;
    private final String resultText;
    private final long runtimeMs;
    private final int toolCallsCount;

    public SubagentExecutionResult(SubagentOutcome outcome, String resultText) {
        this(outcome, resultText, 0, 0);
    }

    public SubagentExecutionResult(SubagentOutcome outcome, String resultText, long runtimeMs, int toolCallsCount) {
        this.outcome = outcome;
        this.resultText = resultText;
        this.runtimeMs = runtimeMs;
        this.toolCallsCount = toolCallsCount;
    }

    public static SubagentExecutionResult ok(String resultText) {
        return new SubagentExecutionResult(SubagentOutcome.ok(resultText), resultText);
    }

    public static SubagentExecutionResult error(String error) {
        return new SubagentExecutionResult(SubagentOutcome.error(error), null);
    }

    public static SubagentExecutionResult timeout() {
        return new SubagentExecutionResult(SubagentOutcome.timeout(), null);
    }

    public SubagentOutcome getOutcome() { return outcome; }
    public String getResultText() { return resultText; }
    public long getRuntimeMs() { return runtimeMs; }
    public int getToolCallsCount() { return toolCallsCount; }
}