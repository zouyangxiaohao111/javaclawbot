package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SlackDMConfig {
    private boolean enabled = true;
    private String policy = "open";
    private List<String> allowFrom = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }
}