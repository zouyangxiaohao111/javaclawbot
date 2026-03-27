package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelegramConfig {
    private boolean enabled = false;
    private String token = "";
    private List<String> allowFrom = new ArrayList<>();
    private String proxy = null;
    private boolean replyToMessage = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public boolean isReplyToMessage() {
        return replyToMessage;
    }

    public void setReplyToMessage(boolean replyToMessage) {
        this.replyToMessage = replyToMessage;
    }
}