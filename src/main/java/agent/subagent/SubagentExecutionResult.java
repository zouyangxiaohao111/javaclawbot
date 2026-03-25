package agent.subagent;

/**
 * 子Agent执行结果
 */
public class SubagentExecutionResult {

    private final SubagentOutcome outcome;
    private final String resultText;

    public SubagentExecutionResult(SubagentOutcome outcome, String resultText) {
        this.outcome = outcome;
        this.resultText = resultText;
    }

    public SubagentOutcome getOutcome() { return outcome; }
    public String getResultText() { return resultText; }
}
