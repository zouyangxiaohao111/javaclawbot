package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsAppConfig {
    private boolean enabled = false;
    private String bridgeUrl = "ws://localhost:3001";
    private String bridgeToken = "";
    private List<String> allowFrom = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBridgeUrl() {
        return bridgeUrl;
    }

    public void setBridgeUrl(String bridgeUrl) {
        this.bridgeUrl = bridgeUrl;
    }

    public String getBridgeToken() {
        return bridgeToken;
    }

    public void setBridgeToken(String bridgeToken) {
        this.bridgeToken = bridgeToken;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }
}