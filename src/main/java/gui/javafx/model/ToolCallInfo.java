package gui.javafx.model;

import java.time.Instant;
import java.util.Map;

public class ToolCallInfo {
    public enum ToolCallStatus {
        RUNNING, SUCCESS, ERROR
    }

    private String toolName;
    private Map<String, Object> parameters;
    private ToolCallStatus status;
    private String result;
    private Instant startTime;
    private Instant endTime;

    public ToolCallInfo() {}

    public ToolCallInfo(String toolName, Map<String, Object> parameters) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.status = ToolCallStatus.RUNNING;
        this.startTime = Instant.now();
    }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    public ToolCallStatus getStatus() { return status; }
    public void setStatus(ToolCallStatus status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
}