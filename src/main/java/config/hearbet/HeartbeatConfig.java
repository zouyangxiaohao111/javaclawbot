package config.hearbet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeartbeatConfig {
    private boolean enabled = true;
    private String every = "30m";
    private String prompt = null;
    private String target = "none";
    private String model = null;
    private int ackMaxChars = 300;
    private boolean isolatedSession = false;
    private boolean includeReasoning = false;
    private String activeHoursStart = null;
    private String activeHoursEnd = null;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEvery() {
        return every;
    }

    public void setEvery(String every) {
        this.every = every;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getAckMaxChars() {
        return ackMaxChars;
    }

    public void setAckMaxChars(int ackMaxChars) {
        this.ackMaxChars = ackMaxChars;
    }

    public boolean isIsolatedSession() {
        return isolatedSession;
    }

    public void setIsolatedSession(boolean isolatedSession) {
        this.isolatedSession = isolatedSession;
    }

    public boolean isIncludeReasoning() {
        return includeReasoning;
    }

    public void setIncludeReasoning(boolean includeReasoning) {
        this.includeReasoning = includeReasoning;
    }

    public String getActiveHoursStart() {
        return activeHoursStart;
    }

    public void setActiveHoursStart(String activeHoursStart) {
        this.activeHoursStart = activeHoursStart;
    }

    public String getActiveHoursEnd() {
        return activeHoursEnd;
    }

    public void setActiveHoursEnd(String activeHoursEnd) {
        this.activeHoursEnd = activeHoursEnd;
    }
}