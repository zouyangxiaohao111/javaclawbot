package config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import providers.ProviderRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置结构定义（对应 Python 的 pydantic 模型）
 *
 * 说明：
 * - 读取 JSON 时：由 ConfigIO 负责“下划线键 -> 驼峰键”兼容
 * - 这里仅定义字段结构与提供者匹配逻辑
 */
public final class ConfigSchema {

    private ConfigSchema() {}

    // =========================
    // 渠道配置（字段与默认值一比一）
    // =========================

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WhatsAppConfig {
        private boolean enabled = false;
        private String bridgeUrl = "ws://localhost:3001";
        private String bridgeToken = "";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBridgeUrl() { return bridgeUrl; }
        public void setBridgeUrl(String bridgeUrl) { this.bridgeUrl = bridgeUrl; }

        public String getBridgeToken() { return bridgeToken; }
        public void setBridgeToken(String bridgeToken) { this.bridgeToken = bridgeToken; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TelegramConfig {
        private boolean enabled = false;
        private String token = "";
        private List<String> allowFrom = new ArrayList<>();
        private String proxy = null;
        private boolean replyToMessage = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }

        public String getProxy() { return proxy; }
        public void setProxy(String proxy) { this.proxy = proxy; }

        public boolean isReplyToMessage() { return replyToMessage; }
        public void setReplyToMessage(boolean replyToMessage) { this.replyToMessage = replyToMessage; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FeishuConfig {
        private boolean enabled = false;
        private String appId = "";
        private String appSecret = "";
        private String encryptKey = "";
        private String verificationToken = "";
        private List<String> allowFrom = new ArrayList<>();

        // ✅ Python: react_emoji: str = "THUMBSUP"
        private String reactEmoji = "THUMBSUP";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }

        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

        public String getEncryptKey() { return encryptKey; }
        public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }

        public String getVerificationToken() { return verificationToken; }
        public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }

        public String getReactEmoji() { return reactEmoji; }
        public void setReactEmoji(String reactEmoji) { this.reactEmoji = reactEmoji; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DingTalkConfig {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DiscordConfig {
        private boolean enabled = false;
        private String token = "";
        private List<String> allowFrom = new ArrayList<>();
        private String gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
        private int intents = 37377;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }

        public String getGatewayUrl() { return gatewayUrl; }
        public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }

        public int getIntents() { return intents; }
        public void setIntents(int intents) { this.intents = intents; }
    }

    // ✅ Python ChannelsConfig includes matrix: MatrixConfig
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MatrixConfig {
        private boolean enabled = false;
        private String homeserver = "https://matrix.org";
        private String accessToken = "";
        private String userId = "";          // e.g. @bot:matrix.org
        private String deviceId = "";
        private boolean e2eeEnabled = true;
        private int syncStopGraceSeconds = 2;
        private int maxMediaBytes = 20 * 1024 * 1024;
        private List<String> allowFrom = new ArrayList<>();
        private String groupPolicy = "open";        // open / mention / allowlist
        private List<String> groupAllowFrom = new ArrayList<>();
        private boolean allowRoomMentions = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getHomeserver() { return homeserver; }
        public void setHomeserver(String homeserver) { this.homeserver = homeserver; }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

        public boolean isE2eeEnabled() { return e2eeEnabled; }
        public void setE2eeEnabled(boolean e2eeEnabled) { this.e2eeEnabled = e2eeEnabled; }

        public int getSyncStopGraceSeconds() { return syncStopGraceSeconds; }
        public void setSyncStopGraceSeconds(int syncStopGraceSeconds) { this.syncStopGraceSeconds = syncStopGraceSeconds; }

        public int getMaxMediaBytes() { return maxMediaBytes; }
        public void setMaxMediaBytes(int maxMediaBytes) { this.maxMediaBytes = maxMediaBytes; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }

        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }

        public List<String> getGroupAllowFrom() { return groupAllowFrom; }
        public void setGroupAllowFrom(List<String> groupAllowFrom) { this.groupAllowFrom = (groupAllowFrom != null) ? groupAllowFrom : new ArrayList<>(); }

        public boolean isAllowRoomMentions() { return allowRoomMentions; }
        public void setAllowRoomMentions(boolean allowRoomMentions) { this.allowRoomMentions = allowRoomMentions; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmailConfig {
        private boolean enabled = false;
        private boolean consentGranted = false;

        // 接收（IMAP）
        private String imapHost = "";
        private int imapPort = 993;
        private String imapUsername = "";
        private String imapPassword = "";
        private String imapMailbox = "INBOX";
        private boolean imapUseSsl = true;

        // 发送（SMTP）
        private String smtpHost = "";
        private int smtpPort = 587;
        private String smtpUsername = "";
        private String smtpPassword = "";
        private boolean smtpUseTls = true;
        private boolean smtpUseSsl = false;
        private String fromAddress = "";

        // 行为
        private boolean autoReplyEnabled = true;
        private int pollIntervalSeconds = 30;
        private boolean markSeen = true;
        private int maxBodyChars = 12000;
        private String subjectPrefix = "Re: ";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isConsentGranted() { return consentGranted; }
        public void setConsentGranted(boolean consentGranted) { this.consentGranted = consentGranted; }

        public String getImapHost() { return imapHost; }
        public void setImapHost(String imapHost) { this.imapHost = imapHost; }

        public int getImapPort() { return imapPort; }
        public void setImapPort(int imapPort) { this.imapPort = imapPort; }

        public String getImapUsername() { return imapUsername; }
        public void setImapUsername(String imapUsername) { this.imapUsername = imapUsername; }

        public String getImapPassword() { return imapPassword; }
        public void setImapPassword(String imapPassword) { this.imapPassword = imapPassword; }

        public String getImapMailbox() { return imapMailbox; }
        public void setImapMailbox(String imapMailbox) { this.imapMailbox = imapMailbox; }

        public boolean isImapUseSsl() { return imapUseSsl; }
        public void setImapUseSsl(boolean imapUseSsl) { this.imapUseSsl = imapUseSsl; }

        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

        public String getSmtpUsername() { return smtpUsername; }
        public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

        public boolean isSmtpUseTls() { return smtpUseTls; }
        public void setSmtpUseTls(boolean smtpUseTls) { this.smtpUseTls = smtpUseTls; }

        public boolean isSmtpUseSsl() { return smtpUseSsl; }
        public void setSmtpUseSsl(boolean smtpUseSsl) { this.smtpUseSsl = smtpUseSsl; }

        public String getFromAddress() { return fromAddress; }
        public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

        public boolean isAutoReplyEnabled() { return autoReplyEnabled; }
        public void setAutoReplyEnabled(boolean autoReplyEnabled) { this.autoReplyEnabled = autoReplyEnabled; }

        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

        public boolean isMarkSeen() { return markSeen; }
        public void setMarkSeen(boolean markSeen) { this.markSeen = markSeen; }

        public int getMaxBodyChars() { return maxBodyChars; }
        public void setMaxBodyChars(int maxBodyChars) { this.maxBodyChars = maxBodyChars; }

        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MochatMentionConfig {
        private boolean requireInGroups = false;

        public boolean isRequireInGroups() { return requireInGroups; }
        public void setRequireInGroups(boolean requireInGroups) { this.requireInGroups = requireInGroups; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MochatGroupRule {
        private boolean requireMention = false;

        public boolean isRequireMention() { return requireMention; }
        public void setRequireMention(boolean requireMention) { this.requireMention = requireMention; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MochatConfig {
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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getSocketUrl() { return socketUrl; }
        public void setSocketUrl(String socketUrl) { this.socketUrl = socketUrl; }

        public String getSocketPath() { return socketPath; }
        public void setSocketPath(String socketPath) { this.socketPath = socketPath; }

        public boolean isSocketDisableMsgpack() { return socketDisableMsgpack; }
        public void setSocketDisableMsgpack(boolean socketDisableMsgpack) { this.socketDisableMsgpack = socketDisableMsgpack; }

        public int getSocketReconnectDelayMs() { return socketReconnectDelayMs; }
        public void setSocketReconnectDelayMs(int socketReconnectDelayMs) { this.socketReconnectDelayMs = socketReconnectDelayMs; }

        public int getSocketMaxReconnectDelayMs() { return socketMaxReconnectDelayMs; }
        public void setSocketMaxReconnectDelayMs(int socketMaxReconnectDelayMs) { this.socketMaxReconnectDelayMs = socketMaxReconnectDelayMs; }

        public int getSocketConnectTimeoutMs() { return socketConnectTimeoutMs; }
        public void setSocketConnectTimeoutMs(int socketConnectTimeoutMs) { this.socketConnectTimeoutMs = socketConnectTimeoutMs; }

        public int getRefreshIntervalMs() { return refreshIntervalMs; }
        public void setRefreshIntervalMs(int refreshIntervalMs) { this.refreshIntervalMs = refreshIntervalMs; }

        public int getWatchTimeoutMs() { return watchTimeoutMs; }
        public void setWatchTimeoutMs(int watchTimeoutMs) { this.watchTimeoutMs = watchTimeoutMs; }

        public int getWatchLimit() { return watchLimit; }
        public void setWatchLimit(int watchLimit) { this.watchLimit = watchLimit; }

        public int getRetryDelayMs() { return retryDelayMs; }
        public void setRetryDelayMs(int retryDelayMs) { this.retryDelayMs = retryDelayMs; }

        public int getMaxRetryAttempts() { return maxRetryAttempts; }
        public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }

        public String getClawToken() { return clawToken; }
        public void setClawToken(String clawToken) { this.clawToken = clawToken; }

        public String getAgentUserId() { return agentUserId; }
        public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }

        public List<String> getSessions() { return sessions; }
        public void setSessions(List<String> sessions) { this.sessions = (sessions != null) ? sessions : new ArrayList<>(); }

        public List<String> getPanels() { return panels; }
        public void setPanels(List<String> panels) { this.panels = (panels != null) ? panels : new ArrayList<>(); }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }

        public MochatMentionConfig getMention() { return mention; }
        public void setMention(MochatMentionConfig mention) { this.mention = (mention != null) ? mention : new MochatMentionConfig(); }

        public Map<String, MochatGroupRule> getGroups() { return groups; }
        public void setGroups(Map<String, MochatGroupRule> groups) { this.groups = (groups != null) ? groups : new HashMap<>(); }

        public String getReplyDelayMode() { return replyDelayMode; }
        public void setReplyDelayMode(String replyDelayMode) { this.replyDelayMode = replyDelayMode; }

        public int getReplyDelayMs() { return replyDelayMs; }
        public void setReplyDelayMs(int replyDelayMs) { this.replyDelayMs = replyDelayMs; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlackDMConfig {
        private boolean enabled = true;
        private String policy = "open";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SlackConfig {
        private boolean enabled = false;
        private String mode = "socket";
        private String webhookPath = "/slack/events";
        private String botToken = "";
        private String appToken = "";
        private boolean userTokenReadOnly = true;
        private boolean replyInThread = true;
        private String reactEmoji = "eyes";
        private String groupPolicy = "mention";
        private List<String> groupAllowFrom = new ArrayList<>();
        private SlackDMConfig dm = new SlackDMConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getWebhookPath() { return webhookPath; }
        public void setWebhookPath(String webhookPath) { this.webhookPath = webhookPath; }

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }

        public String getAppToken() { return appToken; }
        public void setAppToken(String appToken) { this.appToken = appToken; }

        public boolean isUserTokenReadOnly() { return userTokenReadOnly; }
        public void setUserTokenReadOnly(boolean userTokenReadOnly) { this.userTokenReadOnly = userTokenReadOnly; }

        public boolean isReplyInThread() { return replyInThread; }
        public void setReplyInThread(boolean replyInThread) { this.replyInThread = replyInThread; }

        public String getReactEmoji() { return reactEmoji; }
        public void setReactEmoji(String reactEmoji) { this.reactEmoji = reactEmoji; }

        public String getGroupPolicy() { return groupPolicy; }
        public void setGroupPolicy(String groupPolicy) { this.groupPolicy = groupPolicy; }

        public List<String> getGroupAllowFrom() { return groupAllowFrom; }
        public void setGroupAllowFrom(List<String> groupAllowFrom) { this.groupAllowFrom = (groupAllowFrom != null) ? groupAllowFrom : new ArrayList<>(); }

        public SlackDMConfig getDm() { return dm; }
        public void setDm(SlackDMConfig dm) { this.dm = (dm != null) ? dm : new SlackDMConfig(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QQConfig {
        private boolean enabled = false;
        private String appId = "";
        private String secret = "";
        private List<String> allowFrom = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }

        public List<String> getAllowFrom() { return allowFrom; }
        public void setAllowFrom(List<String> allowFrom) { this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChannelsConfig {
        private boolean sendProgress = true;
        private boolean sendToolHints = false;

        private WhatsAppConfig whatsapp = new WhatsAppConfig();
        private TelegramConfig telegram = new TelegramConfig();
        private DiscordConfig discord = new DiscordConfig();
        private FeishuConfig feishu = new FeishuConfig();
        private MochatConfig mochat = new MochatConfig();
        private DingTalkConfig dingtalk = new DingTalkConfig();
        private EmailConfig email = new EmailConfig();
        private SlackConfig slack = new SlackConfig();
        private QQConfig qq = new QQConfig();

        // ✅ Python has matrix field
        private MatrixConfig matrix = new MatrixConfig();

        public boolean isSendProgress() { return sendProgress; }
        public void setSendProgress(boolean sendProgress) { this.sendProgress = sendProgress; }

        public boolean isSendToolHints() { return sendToolHints; }
        public void setSendToolHints(boolean sendToolHints) { this.sendToolHints = sendToolHints; }

        public WhatsAppConfig getWhatsapp() { return whatsapp; }
        public void setWhatsapp(WhatsAppConfig whatsapp) { this.whatsapp = (whatsapp != null) ? whatsapp : new WhatsAppConfig(); }

        public TelegramConfig getTelegram() { return telegram; }
        public void setTelegram(TelegramConfig telegram) { this.telegram = (telegram != null) ? telegram : new TelegramConfig(); }

        public DiscordConfig getDiscord() { return discord; }
        public void setDiscord(DiscordConfig discord) { this.discord = (discord != null) ? discord : new DiscordConfig(); }

        public FeishuConfig getFeishu() { return feishu; }
        public void setFeishu(FeishuConfig feishu) { this.feishu = (feishu != null) ? feishu : new FeishuConfig(); }

        public MochatConfig getMochat() { return mochat; }
        public void setMochat(MochatConfig mochat) { this.mochat = (mochat != null) ? mochat : new MochatConfig(); }

        public DingTalkConfig getDingtalk() { return dingtalk; }
        public void setDingtalk(DingTalkConfig dingtalk) { this.dingtalk = (dingtalk != null) ? dingtalk : new DingTalkConfig(); }

        public EmailConfig getEmail() { return email; }
        public void setEmail(EmailConfig email) { this.email = (email != null) ? email : new EmailConfig(); }

        public SlackConfig getSlack() { return slack; }
        public void setSlack(SlackConfig slack) { this.slack = (slack != null) ? slack : new SlackConfig(); }

        public QQConfig getQq() { return qq; }
        public void setQq(QQConfig qq) { this.qq = (qq != null) ? qq : new QQConfig(); }

        public MatrixConfig getMatrix() { return matrix; }
        public void setMatrix(MatrixConfig matrix) { this.matrix = (matrix != null) ? matrix : new MatrixConfig(); }
    }

    // =========================
    // 智能体 / 提供者 / 工具配置（字段与默认值一比一）
    // =========================

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    public static class AgentDefaults {
        private String workspace = "~/.nanobot/workspace";
        private String model = "anthropic/claude-opus-4-5";
        private String provider = "auto";
        private int maxTokens = 8192;
        private double temperature = 0.1;
        private int maxToolIterations = 40;
        private int memoryWindow = 100;
        /**
         * 最大技能装载数量
         */
        private int skillMaxLoad = 5;

        private String reasoningEffort = null;

        /**
         * 全局最大并发数（对齐 OpenClaw maxConcurrent）
         */
        private int maxConcurrent = 4;

        /**
         * fallback 调用策略配置
         */
        private FallbackConfig fallback = new FallbackConfig();

        /**
         * 心跳配置
         */
        private HeartbeatConfig heartbeat = new HeartbeatConfig();

        /**
         * 队列配置
         */
        private QueueConfig queue = new QueueConfig();

        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        public int getMaxToolIterations() { return maxToolIterations; }
        public void setMaxToolIterations(int maxToolIterations) { this.maxToolIterations = maxToolIterations; }

        public int getMemoryWindow() { return memoryWindow; }
        public void setMemoryWindow(int memoryWindow) { this.memoryWindow = memoryWindow; }

        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }

        public FallbackConfig getFallback() { return fallback; }
        public void setFallback(FallbackConfig fallback) { this.fallback = (fallback != null) ? fallback : new FallbackConfig(); }

        public HeartbeatConfig getHeartbeat() { return heartbeat; }
        public void setHeartbeat(HeartbeatConfig heartbeat) { this.heartbeat = (heartbeat != null) ? heartbeat : new HeartbeatConfig(); }

        public QueueConfig getQueue() { return queue; }
        public void setQueue(QueueConfig queue) { this.queue = (queue != null) ? queue : new QueueConfig(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FallbackTarget {
        /**
         * 是否启用该 fallback 节点
         */
        private boolean enabled = true;

        /**
         * provider 名
         * 例如：openrouter / deepseek / custom / siliconflow
         */
        private String provider = "";

        /**
         * 该 provider 下的多个候选模型
         * 例如：
         * ["gpt-4.1", "claude-3.7-sonnet", "gemini-2.5-pro"]
         */
        private List<String> models = new ArrayList<>();

        /**
         * 可选：覆盖 apiBase
         * 适合 custom 或同 provider 多网关场景
         */
        private String apiBase = null;

        /**
         * 可选：覆盖 apiKey
         * 一般不建议写在 fallback 节点里，但保留能力
         */
        private String apiKey = null;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public List<String> getModels() { return models; }
        public void setModels(List<String> models) { this.models = (models != null) ? models : new ArrayList<>(); }

        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FallbackConfig {
        /**
         * 是否启用 fallback
         */
        private boolean enabled = true;

        /**
         * 模式：
         * - off
         * - on_error
         * - on_empty
         * - on_invalid
         * - always_try_next
         */
        private String mode = "on_error";

        /**
         * 旧版兼容：仅指定 provider 顺序
         * 若配置了 targets，则优先使用 targets
         */
       /* private List<String> providers = new ArrayList<>();*/

        /**
         * 新版 fallback 目标：
         * 每个 target 可以指定 provider + 多个 models + apiBase/apiKey 覆盖
         */
        private List<FallbackTarget> targets = new ArrayList<>();

        /**
         * 最大尝试次数（包含 primary）
         */
        private int maxAttempts = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

       /* public List<String> getProviders() { return providers; }
        public void setProviders(List<String> providers) { this.providers = (providers != null) ? providers : new ArrayList<>(); }*/
        public List<FallbackTarget> getTargets() { return targets; }
        public void setTargets(List<FallbackTarget> targets) { this.targets = (targets != null) ? targets : new ArrayList<>(); }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentsConfig {
        private AgentDefaults defaults = new AgentDefaults();

        public AgentDefaults getDefaults() { return defaults; }
        public void setDefaults(AgentDefaults defaults) { this.defaults = (defaults != null) ? defaults : new AgentDefaults(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderConfig {
        private String apiKey = "";
        private String apiBase = null;
        private Map<String, String> extraHeaders = null;

        public ProviderConfig(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }

        public Map<String, String> getExtraHeaders() { return extraHeaders; }
        public void setExtraHeaders(Map<String, String> extraHeaders) { this.extraHeaders = extraHeaders; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProvidersConfig {
        private ProviderConfig custom = new ProviderConfig();

        private ProviderConfig anthropic =
                new ProviderConfig("https://api.anthropic.com");

        private ProviderConfig openai =
                new ProviderConfig("https://api.openai.com/v1");

        private ProviderConfig openrouter =
                new ProviderConfig("https://openrouter.ai/api/v1");

        private ProviderConfig deepseek =
                new ProviderConfig("https://api.deepseek.com");

        private ProviderConfig groq =
                new ProviderConfig("https://api.groq.com/openai/v1");

        private ProviderConfig zhipu =
                new ProviderConfig("https://open.bigmodel.cn/api/paas/v4");

        private ProviderConfig dashscope =
                new ProviderConfig("https://dashscope.aliyuncs.com");

        private ProviderConfig vllm =
                new ProviderConfig("http://localhost:8000/v1");

        private ProviderConfig gemini =
                new ProviderConfig("https://generativelanguage.googleapis.com/v1beta");

        private ProviderConfig moonshot =
                new ProviderConfig("https://api.moonshot.cn/v1");

        private ProviderConfig minimax =
                new ProviderConfig("https://api.minimax.chat/v1");

        private ProviderConfig aihubmix =
                new ProviderConfig("https://api.aihubmix.com/v1");

        private ProviderConfig siliconflow =
                new ProviderConfig("https://api.siliconflow.cn/v1");

        private ProviderConfig volcengine =
                new ProviderConfig("https://ark.cn-beijing.volces.com/api/v3");

        private ProviderConfig openaiCodex =
                new ProviderConfig("https://api.openai.com/v1");

        private ProviderConfig githubCopilot =
                new ProviderConfig("https://api.githubcopilot.com");

        public ProviderConfig getCustom() { return custom; }
        public void setCustom(ProviderConfig custom) { this.custom = (custom != null) ? custom : new ProviderConfig(); }

        public ProviderConfig getAnthropic() { return anthropic; }
        public void setAnthropic(ProviderConfig anthropic) { this.anthropic = (anthropic != null) ? anthropic : new ProviderConfig(); }

        public ProviderConfig getOpenai() { return openai; }
        public void setOpenai(ProviderConfig openai) { this.openai = (openai != null) ? openai : new ProviderConfig(); }

        public ProviderConfig getOpenrouter() { return openrouter; }
        public void setOpenrouter(ProviderConfig openrouter) { this.openrouter = (openrouter != null) ? openrouter : new ProviderConfig(); }

        public ProviderConfig getDeepseek() { return deepseek; }
        public void setDeepseek(ProviderConfig deepseek) { this.deepseek = (deepseek != null) ? deepseek : new ProviderConfig(); }

        public ProviderConfig getGroq() { return groq; }
        public void setGroq(ProviderConfig groq) { this.groq = (groq != null) ? groq : new ProviderConfig(); }

        public ProviderConfig getZhipu() { return zhipu; }
        public void setZhipu(ProviderConfig zhipu) { this.zhipu = (zhipu != null) ? zhipu : new ProviderConfig(); }

        public ProviderConfig getDashscope() { return dashscope; }
        public void setDashscope(ProviderConfig dashscope) { this.dashscope = (dashscope != null) ? dashscope : new ProviderConfig(); }

        public ProviderConfig getVllm() { return vllm; }
        public void setVllm(ProviderConfig vllm) { this.vllm = (vllm != null) ? vllm : new ProviderConfig(); }

        public ProviderConfig getGemini() { return gemini; }
        public void setGemini(ProviderConfig gemini) { this.gemini = (gemini != null) ? gemini : new ProviderConfig(); }

        public ProviderConfig getMoonshot() { return moonshot; }
        public void setMoonshot(ProviderConfig moonshot) { this.moonshot = (moonshot != null) ? moonshot : new ProviderConfig(); }

        public ProviderConfig getMinimax() { return minimax; }
        public void setMinimax(ProviderConfig minimax) { this.minimax = (minimax != null) ? minimax : new ProviderConfig(); }

        public ProviderConfig getAihubmix() { return aihubmix; }
        public void setAihubmix(ProviderConfig aihubmix) { this.aihubmix = (aihubmix != null) ? aihubmix : new ProviderConfig(); }

        public ProviderConfig getSiliconflow() { return siliconflow; }
        public void setSiliconflow(ProviderConfig siliconflow) { this.siliconflow = (siliconflow != null) ? siliconflow : new ProviderConfig(); }

        public ProviderConfig getVolcengine() { return volcengine; }
        public void setVolcengine(ProviderConfig volcengine) { this.volcengine = (volcengine != null) ? volcengine : new ProviderConfig(); }

        public ProviderConfig getOpenaiCodex() { return openaiCodex; }
        public void setOpenaiCodex(ProviderConfig openaiCodex) { this.openaiCodex = (openaiCodex != null) ? openaiCodex : new ProviderConfig(); }

        public ProviderConfig getGithubCopilot() { return githubCopilot; }
        public void setGithubCopilot(ProviderConfig githubCopilot) { this.githubCopilot = (githubCopilot != null) ? githubCopilot : new ProviderConfig(); }

        public ProviderConfig getByName(String name) {
            if (name == null) return null;
            return switch (name) {
                case "custom" -> custom;
                case "anthropic" -> anthropic;
                case "openai" -> openai;
                case "openrouter" -> openrouter;
                case "deepseek" -> deepseek;
                case "groq" -> groq;
                case "zhipu" -> zhipu;
                case "dashscope" -> dashscope;
                case "vllm" -> vllm;
                case "gemini" -> gemini;
                case "moonshot" -> moonshot;
                case "minimax" -> minimax;
                case "aihubmix" -> aihubmix;
                case "siliconflow" -> siliconflow;
                case "volcengine" -> volcengine;
                case "openai_codex", "openaiCodex" -> openaiCodex;
                case "github_copilot", "githubCopilot" -> githubCopilot;
                default -> null;
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueueConfig {
        /**
         * 队列模式：collect/steer/followup/interrupt
         */
        private String mode = "collect";

        /**
         * 去抖动时间（毫秒）
         */
        private int debounceMs = 1000;

        /**
         * 队列容量
         */
        private int cap = 20;

        /**
         * 溢出策略：old/new/summarize
         */
        private String drop = "summarize";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public int getDebounceMs() { return debounceMs; }
        public void setDebounceMs(int debounceMs) { this.debounceMs = debounceMs; }

        public int getCap() { return cap; }
        public void setCap(int cap) { this.cap = cap; }

        public String getDrop() { return drop; }
        public void setDrop(String drop) { this.drop = drop; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeartbeatConfig {
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

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getEvery() { return every; }
        public void setEvery(String every) { this.every = every; }

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getAckMaxChars() { return ackMaxChars; }
        public void setAckMaxChars(int ackMaxChars) { this.ackMaxChars = ackMaxChars; }

        public boolean isIsolatedSession() { return isolatedSession; }
        public void setIsolatedSession(boolean isolatedSession) { this.isolatedSession = isolatedSession; }

        public boolean isIncludeReasoning() { return includeReasoning; }
        public void setIncludeReasoning(boolean includeReasoning) { this.includeReasoning = includeReasoning; }

        public String getActiveHoursStart() { return activeHoursStart; }
        public void setActiveHoursStart(String activeHoursStart) { this.activeHoursStart = activeHoursStart; }

        public String getActiveHoursEnd() { return activeHoursEnd; }
        public void setActiveHoursEnd(String activeHoursEnd) { this.activeHoursEnd = activeHoursEnd; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GatewayConfig {
        private String host = "0.0.0.0";
        private int port = 18790;
        private HeartbeatConfig heartbeat = new HeartbeatConfig();

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public HeartbeatConfig getHeartbeat() { return heartbeat; }
        public void setHeartbeat(HeartbeatConfig heartbeat) { this.heartbeat = (heartbeat != null) ? heartbeat : new HeartbeatConfig(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebSearchConfig {
        private String apiKey = "";
        private int maxResults = 5;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WebToolsConfig {
        private WebSearchConfig search = new WebSearchConfig();

        public WebSearchConfig getSearch() { return search; }
        public void setSearch(WebSearchConfig search) { this.search = (search != null) ? search : new WebSearchConfig(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExecToolConfig {
        private int timeout = 60;
        private String pathAppend = "";

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public String getPathAppend() { return pathAppend; }
        public void setPathAppend(String pathAppend) { this.pathAppend = pathAppend; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MCPServerConfig {
        private String type = "";
        private String command = "";
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        private String url = "";
        private Map<String, String> headers = new HashMap<>();
        private int toolTimeout = 30;

        public String getType() { return type; }
        public void setType(String type) { this.type = (type != null) ? type : ""; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = (args != null) ? args : new ArrayList<>(); }

        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = (env != null) ? env : new HashMap<>(); }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = (headers != null) ? headers : new HashMap<>(); }

        public int getToolTimeout() { return toolTimeout; }
        public void setToolTimeout(int toolTimeout) { this.toolTimeout = toolTimeout; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolsConfig {
        private WebToolsConfig web = new WebToolsConfig();
        private ExecToolConfig exec = new ExecToolConfig();
        private boolean restrictToWorkspace = false;
        private Map<String, MCPServerConfig> mcpServers = new HashMap<>();

        public WebToolsConfig getWeb() { return web; }
        public void setWeb(WebToolsConfig web) { this.web = (web != null) ? web : new WebToolsConfig(); }

        public ExecToolConfig getExec() { return exec; }
        public void setExec(ExecToolConfig exec) { this.exec = (exec != null) ? exec : new ExecToolConfig(); }

        public boolean isRestrictToWorkspace() { return restrictToWorkspace; }
        public void setRestrictToWorkspace(boolean restrictToWorkspace) { this.restrictToWorkspace = restrictToWorkspace; }

        public Map<String, MCPServerConfig> getMcpServers() { return mcpServers; }
        public void setMcpServers(Map<String, MCPServerConfig> mcpServers) { this.mcpServers = (mcpServers != null) ? mcpServers : new HashMap<>(); }
    }

    // =========================
    // 根配置 + 提供者匹配逻辑（与 Python 同语义）
    // =========================

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Config {
        private AgentsConfig agents = new AgentsConfig();
        private ChannelsConfig channels = new ChannelsConfig();
        private ProvidersConfig providers = new ProvidersConfig();
        private GatewayConfig gateway = new GatewayConfig();
        private ToolsConfig tools = new ToolsConfig();

        public AgentsConfig getAgents() { return agents; }
        public void setAgents(AgentsConfig agents) { this.agents = (agents != null) ? agents : new AgentsConfig(); }

        public ChannelsConfig getChannels() { return channels; }
        public void setChannels(ChannelsConfig channels) { this.channels = (channels != null) ? channels : new ChannelsConfig(); }

        public ProvidersConfig getProviders() { return providers; }
        public void setProviders(ProvidersConfig providers) { this.providers = (providers != null) ? providers : new ProvidersConfig(); }

        public GatewayConfig getGateway() { return gateway; }
        public void setGateway(GatewayConfig gateway) { this.gateway = (gateway != null) ? gateway : new GatewayConfig(); }

        public ToolsConfig getTools() { return tools; }
        public void setTools(ToolsConfig tools) { this.tools = (tools != null) ? tools : new ToolsConfig(); }

        /** 展开后的工作区路径（支持 ~） */
        public Path getWorkspacePath() {
            String raw = getAgents().getDefaults().getWorkspace();
            return expandUser(raw);
        }
        public void setWorkspacePath(Path workspacePath) {
            if (workspacePath != null) {
                getAgents().getDefaults().setWorkspace(workspacePath.toString());
            }
        }
        public ProviderConfig getProvider(String model) {
            MatchResult r = matchProvider(model);
            return r.config;
        }

        public String getProviderName(String model) {
            MatchResult r = matchProvider(model);
            return r.name;
        }

        public String getApiKey(String model) {
            ProviderConfig p = getProvider(model);
            return (p != null) ? p.getApiKey() : null;
        }

        public String getApiBase(String model) {
            MatchResult r = matchProvider(model);
            ProviderConfig p = r.config;
            String name = r.name;

            if (p != null && p.getApiBase() != null && !p.getApiBase().isBlank()) {
                return p.getApiBase();
            }

            if (name != null) {
                ProviderRegistry.ProviderSpec spec = ProviderRegistry.findByName(name);
                if (spec != null && spec.isGateway() && spec.getDefaultApiBase() != null && !spec.getDefaultApiBase().isBlank()) {
                    return spec.getDefaultApiBase();
                }
            }
            return null;
        }

        private static final class MatchResult {
            private final ProviderConfig config;
            private final String name;

            private MatchResult(ProviderConfig config, String name) {
                this.config = config;
                this.name = name;
            }
        }

        /**
         * 匹配提供者配置与名称（对应 Python 的 _match_provider）
         */
        private MatchResult matchProvider(String model) {
            String forced = getAgents().getDefaults().getProvider();
            if (forced != null && !"auto".equals(forced)) {
                ProviderConfig p = getProviders().getByName(forced);
                return (p != null) ? new MatchResult(p, forced) : new MatchResult(null, null);
            }

            String modelLower = (model != null ? model : getAgents().getDefaults().getModel());
            modelLower = (modelLower != null ? modelLower : "");
            modelLower = modelLower.toLowerCase(java.util.Locale.ROOT);

            String modelNormalized = modelLower.replace("-", "_");

            String modelPrefix = "";
            int idx = modelLower.indexOf('/');
            if (idx >= 0) modelPrefix = modelLower.substring(0, idx);
            String normalizedPrefix = modelPrefix.replace("-", "_");

            String finalModelLower = modelLower;
            java.util.function.Function<String, Boolean> kwMatches = (kw) -> {
                if (kw == null) return false;
                String k = kw.toLowerCase(java.util.Locale.ROOT);
                return finalModelLower.contains(k) || modelNormalized.contains(k.replace("-", "_"));
            };

            // 规则：显式前缀优先
            for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                ProviderConfig p = getProviders().getByName(spec.getName());
                if (p != null && !modelPrefix.isBlank() && normalizedPrefix.equals(spec.getName())) {
                    if (spec.isOauth() || (p.getApiKey() != null && !p.getApiKey().isBlank())) {
                        return new MatchResult(p, spec.getName());
                    }
                }
            }

            // 规则：按关键字匹配
            for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                ProviderConfig p = getProviders().getByName(spec.getName());
                if (p == null) continue;

                boolean hit = false;
                for (String kw : spec.getKeywords()) {
                    if (Boolean.TRUE.equals(kwMatches.apply(kw))) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    if (spec.isOauth() || (p.getApiKey() != null && !p.getApiKey().isBlank())) {
                        return new MatchResult(p, spec.getName());
                    }
                }
            }

            // 兜底：先网关再其它（OAuth 不参与兜底）
            for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                if (spec.isOauth()) continue;
                ProviderConfig p = getProviders().getByName(spec.getName());
                if (p != null && p.getApiKey() != null && !p.getApiKey().isBlank()) {
                    return new MatchResult(p, spec.getName());
                }
            }

            return new MatchResult(null, null);
        }

        private static Path expandUser(String p) {
            if (p == null) return Paths.get("").toAbsolutePath().normalize();
            String s = p.trim();
            if (s.startsWith("~/") || "~".equals(s)) {
                String home = System.getProperty("user.home", "");
                if ("~".equals(s)) return Paths.get(home).normalize();
                return Paths.get(home, s.substring(2)).normalize();
            }
            return Paths.get(s).normalize();
        }
    }
}