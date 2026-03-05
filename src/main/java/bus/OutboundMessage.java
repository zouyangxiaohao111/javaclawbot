package bus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站消息：准备发送到聊天渠道的消息
 */
public class OutboundMessage {

    /** 渠道类型 */
    private String channel;

    /** 会话/群组/频道标识 */
    private String chatId;

    /** 消息正文 */
    private String content;

    /** 可选：回复的消息标识 */
    private String replyTo;

    /** 媒体资源链接列表（默认：空列表） */
    private List<String> media = new ArrayList<>();

    /** 渠道相关的扩展数据（默认：空字典） */
    private Map<String, Object> metadata = new HashMap<>();

    public OutboundMessage() {
    }

    public OutboundMessage(String channel, String chatId, String content, List<String> media, Map<String, Object> metadata) {
        this.channel = channel;
        this.chatId = chatId;
        this.content = content;
        this.media = (media != null) ? media : new ArrayList<>();
        this.metadata = (metadata != null) ? metadata : new HashMap<>();
    }

    // Getter / Setter

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }

    public List<String> getMedia() {
        return media;
    }

    public void setMedia(List<String> media) {
        this.media = (media != null) ? media : new ArrayList<>();
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = (metadata != null) ? metadata : new HashMap<>();
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "channel='" + channel + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + content + '\'' +
                ", replyTo='" + replyTo + '\'' +
                ", media=" + media +
                ", metadata=" + metadata +
                '}';
    }
}