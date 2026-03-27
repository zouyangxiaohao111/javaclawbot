package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DingTalkConfig {
    private boolean enabled = false;
    private String clientId = "";
    private String clientSecret = "";
    private List<String> allowFrom = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }
}