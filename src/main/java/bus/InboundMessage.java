package bus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站消息：从聊天渠道接收到的消息
 */
public class InboundMessage {

    /** 渠道类型，例如：telegram、discord、slack、whatsapp */
    private String channel;

    /** 发送者标识 */
    private String senderId;

    /** 会话/群组/频道标识 */
    private String chatId;

    /** 消息正文 */
    private String content;

    /** 时间戳（默认：当前时间） */
    private LocalDateTime timestamp = LocalDateTime.now();

    /** 媒体资源链接列表（默认：空列表） */
    private List<String> media = new ArrayList<>();

    /** 渠道相关的扩展数据（默认：空字典） */
    private Map<String, Object> metadata = new HashMap<>();

    /** 可选：覆盖默认会话键 */
    private String sessionKeyOverride;

    public InboundMessage() {
    }

    public InboundMessage(String channel, String senderId, String chatId, String content) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
    }

    public InboundMessage(String channel, String senderId, String chatId, String content, List<String> media, Map<String, Object> metadata) {
        this.channel = channel;
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
        this.media = media;
        this.metadata = metadata;
    }

    /**
     * 会话唯一键：
     * - 如果提供覆盖值则优先使用
     * - 否则使用 “渠道:chatId”
     */
    public String getSessionKey() {
        if (sessionKeyOverride != null) {
            return sessionKeyOverride;
        }
        return channel + ":" + chatId;
    }

    // Getter / Setter

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
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

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
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

    public String getSessionKeyOverride() {
        return sessionKeyOverride;
    }

    public void setSessionKeyOverride(String sessionKeyOverride) {
        this.sessionKeyOverride = sessionKeyOverride;
    }

    @Override
    public String toString() {
        return "InboundMessage{" +
                "channel='" + channel + '\'' +
                ", senderId='" + senderId + '\'' +
                ", chatId='" + chatId + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                ", media=" + media +
                ", metadata=" + metadata +
                ", sessionKeyOverride='" + sessionKeyOverride + '\'' +
                '}';
    }
}