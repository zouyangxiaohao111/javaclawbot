package agent.subagent.framework;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

/**
 * 磁盘任务输出管理器
 *
 * 对应 Open-ClaudeCode: src/utils/task/diskOutput.ts - DiskTaskOutput
 */
public class DiskTaskOutput {
    private static final Logger log = LoggerFactory.getLogger(DiskTaskOutput.class);

    // 最大输出字节数 (5GB)
    public static final long MAX_TASK_OUTPUT_BYTES = 5L * 1024 * 1024 * 1024;

    // 单例存储
    private static final ConcurrentHashMap<String, DiskTaskOutput> outputs = new ConcurrentHashMap<>();

    private final String taskId;
    private final String path;
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    private long bytesWritten = 0;
    private boolean capped = false;

    public DiskTaskOutput(String taskId) {
        this.taskId = taskId;
        this.path = getTaskOutputPath(taskId);
    }

    /**
     * 追加内容到输出文件
     */
    public void append(String content) {
        if (capped) {
            return;
        }

        bytesWritten += content.length();
        if (bytesWritten > MAX_TASK_OUTPUT_BYTES) {
            capped = true;
            queue.offer("\n[output truncated: exceeded 5GB disk cap]\n");
        } else {
            queue.offer(content);
        }

        CompletableFuture.runAsync(this::drainAllChunks);
    }

    /**
     * 刷新所有待处理的写入
     */
    public CompletableFuture<Void> flush() {
        return CompletableFuture.runAsync(() -> {
            StringBuilder sb = new StringBuilder();
            String chunk;
            while ((chunk = queue.poll()) != null) {
                sb.append(chunk);
            }
            if (sb.length() > 0) {
                try {
                    ensureOutputDir();
                    appendToFile(sb.toString());
                } catch (IOException e) {
                    log.error("Error flushing task output for {}", taskId, e);
                }
            }
        });
    }

    public void cancel() {
        queue.clear();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getPath() {
        return path;
    }

    private void drainAllChunks() {
        try {
            ensureOutputDir();
            StringBuilder sb = new StringBuilder();
            String chunk;
            while ((chunk = queue.poll()) != null) {
                sb.append(chunk);
            }
            if (sb.length() > 0) {
                appendToFile(sb.toString());
            }
        } catch (IOException e) {
            log.error("Error draining task output for {}", taskId, e);
        }
    }

    private void ensureOutputDir() throws IOException {
        Path dir = Paths.get(path).getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private void appendToFile(String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            FileChannel channel = raf.getChannel();
            try {
                long size = channel.size();
                raf.seek(size);
                channel.write(buffer, size);
            } finally {
                channel.close();
            }
        }
    }

    // =====================
    // 静态方法
    // =====================

    public static DiskTaskOutput getOrCreate(String taskId) {
        return outputs.computeIfAbsent(taskId, DiskTaskOutput::new);
    }

    public static CompletableFuture<Void> evict(String taskId) {
        DiskTaskOutput output = outputs.get(taskId);
        if (output != null) {
            output.cancel();
            outputs.remove(taskId);
        }
        return CompletableFuture.completedFuture(null);
    }

    public static CompletableFuture<Void> cleanup(String taskId) {
        DiskTaskOutput output = outputs.get(taskId);
        if (output != null) {
            output.cancel();
            outputs.remove(taskId);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                Path path = Paths.get(getTaskOutputPath(taskId));
                if (Files.exists(path)) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                log.error("Error cleaning up task output for {}", taskId, e);
            }
        });
    }

    public static DiskOutputDelta getOutputDelta(String taskId, long fromOffset) {
        return getOutputDelta(taskId, fromOffset, 8 * 1024 * 1024); // 8MB default
    }

    public static DiskOutputDelta getOutputDelta(String taskId, long fromOffset, int maxBytes) {
        try {
            Path path = Paths.get(getTaskOutputPath(taskId));
            if (!Files.exists(path)) {
                return new DiskOutputDelta("", fromOffset);
            }

            long fileSize = Files.size(path);
            if (fromOffset >= fileSize) {
                return new DiskOutputDelta("", fromOffset);
            }

            long bytesToRead = Math.min(maxBytes, fileSize - fromOffset);
            byte[] buffer = new byte[(int) bytesToRead];

            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(fromOffset);
                int bytesRead = raf.read(buffer);
                String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                return new DiskOutputDelta(content, fromOffset + bytesRead);
            }
        } catch (IOException e) {
            log.error("Error reading task output delta for {}", taskId, e);
            return new DiskOutputDelta("", fromOffset);
        }
    }

    public static String getOutput(String taskId, int maxBytes) {
        try {
            Path path = Paths.get(getTaskOutputPath(taskId));
            if (!Files.exists(path)) {
                return "";
            }

            long fileSize = Files.size(path);
            if (fileSize <= maxBytes) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } else {
                long skipBytes = fileSize - maxBytes;
                try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                    raf.seek(skipBytes);
                    byte[] buffer = new byte[maxBytes];
                    int bytesRead = raf.read(buffer);
                    String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    return "[omitted]\n" + content;
                }
            }
        } catch (IOException e) {
            log.error("Error reading task output for {}", taskId, e);
            return "";
        }
    }

    public static long getOutputSize(String taskId) {
        try {
            Path path = Paths.get(getTaskOutputPath(taskId));
            if (Files.exists(path)) {
                return Files.size(path);
            }
            return 0;
        } catch (IOException e) {
            return 0;
        }
    }

    public static String getTaskOutputPath(String taskId) {
        String tempDir = System.getProperty("java.io.tmpdir");
        return tempDir + "/javaclawbot/tasks/" + taskId + ".output";
    }

    public static void clearAll() {
        outputs.values().forEach(DiskTaskOutput::cancel);
        outputs.clear();
    }

    // 结果类
    public static class DiskOutputDelta {
        public final String content;
        public final long newOffset;

        public DiskOutputDelta(String content, long newOffset) {
            this.content = content;
            this.newOffset = newOffset;
        }
    }
}
