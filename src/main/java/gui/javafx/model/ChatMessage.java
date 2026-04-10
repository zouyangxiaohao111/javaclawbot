package gui.javafx.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ChatMessage {
    public enum MessageType {
        USER, AI, SYSTEM, TOOL_CALL, PROGRESS
    }

    public enum MessageStatus {
        PENDING, SUCCESS, ERROR
    }

    private String id;
    private MessageType type;
    private String content;
    private Instant timestamp;
    private List<AttachmentInfo> attachments = new ArrayList<>();
    private List<ToolCallInfo> toolCalls = new ArrayList<>();
    private MessageStatus status = MessageStatus.SUCCESS;
    private boolean isMarkdown = false;
    private String referencedMessageId;

    public ChatMessage() {}

    public ChatMessage(String id, MessageType type, String content) {
        this.id = id;
        this.type = type;
        this.content = content;
        this.timestamp = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public List<AttachmentInfo> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentInfo> attachments) { this.attachments = attachments; }

    public List<ToolCallInfo> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallInfo> toolCalls) { this.toolCalls = toolCalls; }

    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }

    public boolean isMarkdown() { return isMarkdown; }
    public void setMarkdown(boolean markdown) { isMarkdown = markdown; }

    public String getReferencedMessageId() { return referencedMessageId; }
    public void setReferencedMessageId(String referencedMessageId) { this.referencedMessageId = referencedMessageId; }
}