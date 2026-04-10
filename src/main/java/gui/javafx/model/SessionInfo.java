package gui.javafx.model;

import java.time.Instant;

public class SessionInfo {
    private String id;
    private String title;
    private Instant createdAt;
    private Instant lastActiveAt;
    private int messageCount;
    private String modelUsed;

    public SessionInfo() {}

    public SessionInfo(String id, String title) {
        this.id = id;
        this.title = title;
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
        this.messageCount = 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
}