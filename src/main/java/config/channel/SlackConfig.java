package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public  class SlackConfig {
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