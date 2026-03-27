package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public  class ChannelsConfig {
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
