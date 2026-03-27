package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MochatConfig {
    private boolean enabled = false;
    private String baseUrl = "https://mochat.io";
    private String socketUrl = "";
    private String socketPath = "/socket.io";
    private boolean socketDisableMsgpack = false;
    private int socketReconnectDelayMs = 1000;
    private int socketMaxReconnectDelayMs = 10000;
    private int socketConnectTimeoutMs = 10000;
    private int refreshIntervalMs = 30000;
    private int watchTimeoutMs = 25000;
    private int watchLimit = 100;
    private int retryDelayMs = 500;
    private int maxRetryAttempts = 0; // 0 means unlimited retries
    private String clawToken = "";
    private String agentUserId = "";
    private List<String> sessions = new ArrayList<>();
    private List<String> panels = new ArrayList<>();
    private List<String> allowFrom = new ArrayList<>();
    private MochatMentionConfig mention = new MochatMentionConfig();
    private Map<String, MochatGroupRule> groups = new HashMap<>();
    private String replyDelayMode = "non-mention";
    private int replyDelayMs = 120000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSocketUrl() {
        return socketUrl;
    }

    public void setSocketUrl(String socketUrl) {
        this.socketUrl = socketUrl;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public void setSocketPath(String socketPath) {
        this.socketPath = socketPath;
    }

    public boolean isSocketDisableMsgpack() {
        return socketDisableMsgpack;
    }

    public void setSocketDisableMsgpack(boolean socketDisableMsgpack) {
        this.socketDisableMsgpack = socketDisableMsgpack;
    }

    public int getSocketReconnectDelayMs() {
        return socketReconnectDelayMs;
    }

    public void setSocketReconnectDelayMs(int socketReconnectDelayMs) {
        this.socketReconnectDelayMs = socketReconnectDelayMs;
    }

    public int getSocketMaxReconnectDelayMs() {
        return socketMaxReconnectDelayMs;
    }

    public void setSocketMaxReconnectDelayMs(int socketMaxReconnectDelayMs) {
        this.socketMaxReconnectDelayMs = socketMaxReconnectDelayMs;
    }

    public int getSocketConnectTimeoutMs() {
        return socketConnectTimeoutMs;
    }

    public void setSocketConnectTimeoutMs(int socketConnectTimeoutMs) {
        this.socketConnectTimeoutMs = socketConnectTimeoutMs;
    }

    public int getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(int refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public int getWatchTimeoutMs() {
        return watchTimeoutMs;
    }

    public void setWatchTimeoutMs(int watchTimeoutMs) {
        this.watchTimeoutMs = watchTimeoutMs;
    }

    public int getWatchLimit() {
        return watchLimit;
    }

    public void setWatchLimit(int watchLimit) {
        this.watchLimit = watchLimit;
    }

    public int getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(int retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public String getClawToken() {
        return clawToken;
    }

    public void setClawToken(String clawToken) {
        this.clawToken = clawToken;
    }

    public String getAgentUserId() {
        return agentUserId;
    }

    public void setAgentUserId(String agentUserId) {
        this.agentUserId = agentUserId;
    }

    public List<String> getSessions() {
        return sessions;
    }

    public void setSessions(List<String> sessions) {
        this.sessions = (sessions != null) ? sessions : new ArrayList<>();
    }

    public List<String> getPanels() {
        return panels;
    }

    public void setPanels(List<String> panels) {
        this.panels = (panels != null) ? panels : new ArrayList<>();
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }

    public MochatMentionConfig getMention() {
        return mention;
    }

    public void setMention(MochatMentionConfig mention) {
        this.mention = (mention != null) ? mention : new MochatMentionConfig();
    }

    public Map<String, MochatGroupRule> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, MochatGroupRule> groups) {
        this.groups = (groups != null) ? groups : new HashMap<>();
    }

    public String getReplyDelayMode() {
        return replyDelayMode;
    }

    public void setReplyDelayMode(String replyDelayMode) {
        this.replyDelayMode = replyDelayMode;
    }

    public int getReplyDelayMs() {
        return replyDelayMs;
    }

    public void setReplyDelayMs(int replyDelayMs) {
        this.replyDelayMs = replyDelayMs;
    }
}