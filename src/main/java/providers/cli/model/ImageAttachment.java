package providers.cli.model;

/**
 * 图片附件
 */
public record ImageAttachment(
    String mimeType,
    byte[] data,
    String fileName
) {
    public ImageAttachment {
        // 允许 null
    }

    public static ImageAttachment fromBase64(String base64, String mimeType, String fileName) {
        return new ImageAttachment(mimeType, java.util.Base64.getDecoder().decode(base64), fileName);
    }
}
