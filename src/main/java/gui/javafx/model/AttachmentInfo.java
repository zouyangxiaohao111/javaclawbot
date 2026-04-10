package gui.javafx.model;

public class AttachmentInfo {
    public enum AttachmentType {
        FILE, IMAGE, AUDIO, VIDEO, DOCUMENT
    }

    private String id;
    private String fileName;
    private AttachmentType type;
    private String filePath;
    private long fileSize;
    private String thumbnailPath;

    public AttachmentInfo() {}

    public AttachmentInfo(String fileName, String filePath, AttachmentType type) {
        this.id = java.util.UUID.randomUUID().toString();
        this.fileName = fileName;
        this.filePath = filePath;
        this.type = type;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public AttachmentType getType() { return type; }
    public void setType(AttachmentType type) { this.type = type; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }
}