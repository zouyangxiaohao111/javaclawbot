package gui.ui;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台线程，用 WatchService 监听日志文件增量，
 * 解析为 LogEntry 并推入 LogBuffer。
 */
public class LogWatcher {

    private static final Pattern LOG_PATTERN =
        Pattern.compile("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+(\\S+)\\s+-\\s+(.*)$");

    private final Path logFile;
    private final ConcurrentLinkedQueue<LogEntry> buffer;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private RandomAccessFile raf;
    private WatchService watchService;
    private Thread thread;

    public LogWatcher(ConcurrentLinkedQueue<LogEntry> buffer) {
        this.buffer = buffer;
        String home = System.getProperty("user.home");
        Path logDir = Paths.get(home, ".javaclawbot", "logs");
        this.logFile = logDir.resolve("app.log");
    }

    /**
     * 启动后台监听线程。
     */
    public void start() {
        stopped.set(false);
        thread = new Thread(this::run, "log-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 停止监听，释放资源。
     */
    public void stop() {
        stopped.set(true);
        if (thread != null) {
            thread.interrupt();
        }
        closeFile();
        closeWatcher();
    }

    public boolean isStopped() {
        return stopped.get();
    }

    private void run() {
        try {
            ensureDirAndFile();
            openFile();
            registerWatcher();
            mainLoop();
        } catch (Exception e) {
            buffer.offer(new LogEntry(
                java.time.LocalTime.now().toString().substring(0, 12),
                "ERROR", "LogWatcher",
                "LogWatcher 异常: " + e.getMessage(),
                "ERROR LogWatcher - LogWatcher 异常: " + e.getMessage()
            ));
        } finally {
            closeFile();
            closeWatcher();
        }
    }

    private void ensureDirAndFile() throws IOException {
        Path logDir = logFile.getParent();
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }
        if (!Files.exists(logFile)) {
            Files.createFile(logFile);
        }
    }

    private void openFile() throws IOException {
        raf = new RandomAccessFile(logFile.toFile(), "r");
    }

    private void registerWatcher() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        logFile.getParent().register(watchService,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE);
    }

    private void mainLoop() {
        while (!stopped.get()) {
            try {
                WatchKey key = watchService.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed != null && changed.toString().equals(logFile.getFileName().toString())) {
                            readNewLines();
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 读取异常不中断主循环
            }
        }
    }

    private void readNewLines() throws IOException {
        if (raf == null) return;
        String line;
        while ((line = raf.readLine()) != null) {
            LogEntry entry = parseLine(line);
            if (entry != null) {
                buffer.offer(entry);
            }
        }
    }

    private LogEntry parseLine(String line) {
        if (line == null || line.isBlank()) return null;
        Matcher m = LOG_PATTERN.matcher(line);
        if (m.matches()) {
            return new LogEntry(m.group(1), m.group(2), m.group(3), m.group(4), line);
        }
        // 无法匹配标准格式的行，作为 INFO 处理
        return new LogEntry(
            java.time.LocalTime.now().toString().substring(0, 12),
            "INFO", "unknown", line, line);
    }

    private void closeFile() {
        try {
            if (raf != null) {
                raf.close();
                raf = null;
            }
        } catch (IOException ignored) {}
    }

    private void closeWatcher() {
        try {
            if (watchService != null) {
                watchService.close();
                watchService = null;
            }
        } catch (IOException ignored) {}
    }
}
