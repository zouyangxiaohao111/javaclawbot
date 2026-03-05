package corn;

public class CronPayload {

    public enum Kind { system_event, agent_turn }

    private Kind kind = Kind.agent_turn;
    private String message = "";
    private boolean deliver = false;
    private String channel; // whatsapp/telegram/...
    private String to;      // recipient id

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
}