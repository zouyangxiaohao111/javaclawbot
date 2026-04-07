package providers.cli.model;

/**
 * 文件附件
 */
public record FileAttachment(
    String mimeType,
    byte[] data,
    String fileName
) {
    public FileAttachment {
        // 允许 null
    }
}
