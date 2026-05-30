package corn;

public class CronPayload {

    public enum Kind { system_event, agent_turn }

    private Kind kind = Kind.agent_turn;
    private String message = "";
    private boolean deliver = false;
    private String channel; // whatsapp/telegram/...
    private String to;      // recipient id

    /** 是否加入主 agent 会话（false=独立会话，true=复用标签会话） */
    private boolean useMainSession = false;
    /** 每次执行是否新建对话（true=每次全新上下文，false=累积历史） */
    private boolean newConversation = true;

    public CronPayload() {}

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isDeliver() { return deliver; }
    public void setDeliver(boolean deliver) { this.deliver = deliver; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public boolean isUseMainSession() { return useMainSession; }
    public void setUseMainSession(boolean useMainSession) { this.useMainSession = useMainSession; }

    public boolean isNewConversation() { return newConversation; }
    public void setNewConversation(boolean newConversation) { this.newConversation = newConversation; }
}
