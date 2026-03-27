package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailConfig {
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isConsentGranted() {
        return consentGranted;
    }

    public void setConsentGranted(boolean consentGranted) {
        this.consentGranted = consentGranted;
    }

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = imapHost;
    }

    public int getImapPort() {
        return imapPort;
    }

    public void setImapPort(int imapPort) {
        this.imapPort = imapPort;
    }

    public String getImapUsername() {
        return imapUsername;
    }

    public void setImapUsername(String imapUsername) {
        this.imapUsername = imapUsername;
    }

    public String getImapPassword() {
        return imapPassword;
    }

    public void setImapPassword(String imapPassword) {
        this.imapPassword = imapPassword;
    }

    public String getImapMailbox() {
        return imapMailbox;
    }

    public void setImapMailbox(String imapMailbox) {
        this.imapMailbox = imapMailbox;
    }

    public boolean isImapUseSsl() {
        return imapUseSsl;
    }

    public void setImapUseSsl(boolean imapUseSsl) {
        this.imapUseSsl = imapUseSsl;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(int smtpPort) {
        this.smtpPort = smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsername = smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public void setSmtpPassword(String smtpPassword) {
        this.smtpPassword = smtpPassword;
    }

    public boolean isSmtpUseTls() {
        return smtpUseTls;
    }

    public void setSmtpUseTls(boolean smtpUseTls) {
        this.smtpUseTls = smtpUseTls;
    }

    public boolean isSmtpUseSsl() {
        return smtpUseSsl;
    }

    public void setSmtpUseSsl(boolean smtpUseSsl) {
        this.smtpUseSsl = smtpUseSsl;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public boolean isAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    public void setAutoReplyEnabled(boolean autoReplyEnabled) {
        this.autoReplyEnabled = autoReplyEnabled;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public boolean isMarkSeen() {
        return markSeen;
    }

    public void setMarkSeen(boolean markSeen) {
        this.markSeen = markSeen;
    }

    public int getMaxBodyChars() {
        return maxBodyChars;
    }

    public void setMaxBodyChars(int maxBodyChars) {
        this.maxBodyChars = maxBodyChars;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }
}