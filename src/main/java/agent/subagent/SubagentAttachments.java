package agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 子Agent附件处理
 *
 * 对齐 OpenClaw 的 subagent-attachments.ts
 *
 * 功能：
 * - 子Agent的附件传递
 * - 附件大小限制
 * - 附件持久化
 */
public class SubagentAttachments {

    private static final Logger log = LoggerFactory.getLogger(SubagentAttachments.class);

    /**
     * 内联附件
     */
    public static class InlineAttachment {
        public final String name;
        public final String content;
        public final String encoding;  // "utf8" or "base64"
        public final String mimeType;

        public InlineAttachment(String name, String content, String encoding, String mimeType) {
            this.name = name;
            this.content = content;
            this.encoding = encoding != null ? encoding : "utf8";
            this.mimeType = mimeType;
        }
    }

    /**
     * 附件接收记录
     */
    public static class AttachmentReceipt {
        public final int count;
        public final long totalBytes;
        public final List<ReceiptFile> files;
        public final String relDir;

        public AttachmentReceipt(int count, long totalBytes, List<ReceiptFile> files, String relDir) {
            this.count = count;
            this.totalBytes = totalBytes;
            this.files = files;
            this.relDir = relDir;
        }
    }

    /**
     * 接收文件记录
     */
    public static class ReceiptFile {
        public final String name;
        public final long bytes;
        public final String sha256;

        public ReceiptFile(String name, long bytes, String sha256) {
            this.name = name;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    /**
     * 附件限制配置
     */
    public static class AttachmentLimits {
        public final boolean enabled;
        public final long maxTotalBytes;
        public final int maxFiles;
        public final long maxFileBytes;
        public final boolean retainOnSessionKeep;

        public AttachmentLimits() {
            this(false, 5 * 1024 * 1024, 50, 1024 * 1024, false);
        }

        public AttachmentLimits(boolean enabled, long maxTotalBytes, int maxFiles, long maxFileBytes, boolean retainOnSessionKeep) {
            this.enabled = enabled;
            this.maxTotalBytes = maxTotalBytes;
            this.maxFiles = maxFiles;
            this.maxFileBytes = maxFileBytes;
            this.retainOnSessionKeep = retainOnSessionKeep;
        }
    }

    /**
     * 物化附件结果
     */
    public static class MaterializeResult {
        public final String status;  // "ok", "forbidden", "error"
        public final AttachmentReceipt receipt;
        public final String absDir;
        public final String rootDir;
        public final boolean retainOnSessionKeep;
        public final String systemPromptSuffix;
        public final String error;

        private MaterializeResult(String status, AttachmentReceipt receipt, String absDir,
                                   String rootDir, boolean retainOnSessionKeep,
                                   String systemPromptSuffix, String error) {
            this.status = status;
            this.receipt = receipt;
            this.absDir = absDir;
            this.rootDir = rootDir;
            this.retainOnSessionKeep = retainOnSessionKeep;
            this.systemPromptSuffix = systemPromptSuffix;
            this.error = error;
        }

        public static MaterializeResult ok(AttachmentReceipt receipt, String absDir,
                                            String rootDir, boolean retainOnSessionKeep,
                                            String systemPromptSuffix) {
            return new MaterializeResult("ok", receipt, absDir, rootDir, retainOnSessionKeep, systemPromptSuffix, null);
        }

        public static MaterializeResult forbidden(String error) {
            return new MaterializeResult("forbidden", null, null, null, false, null, error);
        }

        public static MaterializeResult error(String error) {
            return new MaterializeResult("error", null, null, null, false, null, error);
        }
    }

    /**
     * 物化附件到文件系统
     */
    public static MaterializeResult materializeAttachments(
            Path workspaceDir,
            List<InlineAttachment> attachments,
            AttachmentLimits limits,
            String mountPathHint) {

        if (attachments == null || attachments.isEmpty()) {
            return null;
        }

        if (!limits.enabled) {
            return MaterializeResult.forbidden("Attachments are not enabled");
        }

        if (attachments.size() > limits.maxFiles) {
            return MaterializeResult.forbidden("Too many attachments: " + attachments.size() + " > " + limits.maxFiles);
        }

        // 创建附件目录
        String relDir = mountPathHint != null ? mountPathHint : ".attachments/" + UUID.randomUUID().toString().substring(0, 8);
        Path absDir = workspaceDir.resolve(relDir);

        try {
            Files.createDirectories(absDir);
        } catch (IOException e) {
            return MaterializeResult.error("Failed to create attachment directory: " + e.getMessage());
        }

        List<ReceiptFile> receiptFiles = new ArrayList<>();
        long totalBytes = 0;

        for (InlineAttachment attachment : attachments) {
            if (attachment.name == null || attachment.name.isBlank()) {
                continue;
            }

            // 解码内容
            byte[] content;
            try {
                if ("base64".equalsIgnoreCase(attachment.encoding)) {
                    content = Base64.getDecoder().decode(attachment.content);
                } else {
                    content = attachment.content.getBytes(StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException e) {
                return MaterializeResult.error("Failed to decode attachment " + attachment.name + ": " + e.getMessage());
            }

            // 检查大小限制
            if (content.length > limits.maxFileBytes) {
                return MaterializeResult.forbidden("Attachment too large: " + attachment.name + " (" + content.length + " > " + limits.maxFileBytes + ")");
            }

            totalBytes += content.length;
            if (totalBytes > limits.maxTotalBytes) {
                return MaterializeResult.forbidden("Total attachments too large: " + totalBytes + " > " + limits.maxTotalBytes);
            }

            // 写入文件
            Path filePath = absDir.resolve(attachment.name);
            try {
                Files.write(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                return MaterializeResult.error("Failed to write attachment " + attachment.name + ": " + e.getMessage());
            }

            // 计算SHA256
            String sha256 = computeSha256(content);
            receiptFiles.add(new ReceiptFile(attachment.name, content.length, sha256));
        }

        AttachmentReceipt receipt = new AttachmentReceipt(receiptFiles.size(), totalBytes, receiptFiles, relDir);

        // 构建系统提示后缀
        StringBuilder promptSuffix = new StringBuilder();
        promptSuffix.append("\n\nThe following files have been attached to your workspace:\n");
        for (ReceiptFile file : receiptFiles) {
            promptSuffix.append("- ").append(file.name).append(" (").append(file.bytes).append(" bytes)\n");
        }
        promptSuffix.append("\nThese files are available at: ").append(relDir).append("/\n");

        return MaterializeResult.ok(receipt, absDir.toString(), workspaceDir.toString(), limits.retainOnSessionKeep, promptSuffix.toString());
    }

    /**
     * 解码Base64内容
     */
    public static byte[] decodeBase64(String base64, long maxBytes) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }

        // 检查编码长度
        long maxEncoded = (long) Math.ceil(maxBytes / 3.0) * 4;
        if (base64.length() > maxEncoded * 2) {
            return null;
        }

        // 移除空白
        String normalized = base64.replaceAll("\\s+", "");
        if (normalized.isEmpty() || normalized.length() % 4 != 0) {
            return null;
        }

        // 检查格式
        if (!normalized.matches("^[A-Za-z0-9+/]+={0,2}$")) {
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(normalized);
            if (decoded.length > maxBytes) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 计算SHA256
     */
    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}