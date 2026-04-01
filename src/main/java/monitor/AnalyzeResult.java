package monitor;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 邮件分析结果
 */
public class AnalyzeResult {
    private boolean shouldNotify;
    private String reason;
    private List<String> targets = new ArrayList<>();
    private String message;
    private String priority;

    public boolean isShouldNotify() { return shouldNotify; }
    public void setShouldNotify(boolean shouldNotify) { this.shouldNotify = shouldNotify; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets != null ? targets : new ArrayList<>(); }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
}