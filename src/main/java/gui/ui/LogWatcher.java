package gui.ui;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台线程，用 WatchService 监听日志文件增量，
 * 解析为 LogEntry 并推入 LogBuffer。
 *
 * 支持日志文件滚动（RollingFileAppender）：当文件被重命名并创建新文件时，
 * 自动关闭旧 reader 并打开新 reader。
 */
public class LogWatcher {

    private static final Pattern LOG_PATTERN =
        Pattern.compile("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+(\\S+)\\s+-\\s+(.*)$");

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 初始读取时最多保留的行数（匹配 DevConsolePage.MAX_BUFFER_SIZE） */
    private static final int INITIAL_READ_MAX_LINES = 5000;

    private final Path logFile;
    private final ConcurrentLinkedQueue<LogEntry> buffer;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private BufferedReader reader;
    private WatchService watchService;
    private Thread thread;

    /** 用户选择的初始读取行数，默认为 200 */
    private volatile int selectedInitialLines = 200;

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
        if (thread != null && thread.isAlive()) {
            return;
        }
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

    /**
     * 设置用户选择的初始读取行数。
     * @param lines 行数，必须大于 0
     */
    public void setSelectedInitialLines(int lines) {
        if (lines > 0) {
            this.selectedInitialLines = lines;
        }
    }

    /**
     * 获取用户选择的初始读取行数。
     * @return 行数
     */
    public int getSelectedInitialLines() {
        return selectedInitialLines;
    }

    private void run() {
        try {
            ensureDirAndFile();
            openFile();
            registerWatcher();
            // 初始读取：只保留最后 selectedInitialLines 行，
            // 防止大文件（2MB+）撑爆缓冲区导致 UI 卡死
            initialRead(selectedInitialLines);
            mainLoop();
        } catch (Exception e) {
            buffer.offer(new LogEntry(
                java.time.LocalTime.now().format(TIME_FMT),
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
        reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(logFile.toFile()), StandardCharsets.UTF_8));
    }

    private void registerWatcher() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        logFile.getParent().register(watchService,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE);
    }

    /**
     * 初始读取：遍历全部已有行，但仅保留最后 maxLines 行推入缓冲区。
     * 避免历史日志过多导致 UI 卡死。
     */
    private void initialRead(int maxLines) throws IOException {
        if (reader == null) return;
        ArrayDeque<LogEntry> ring = new ArrayDeque<>(maxLines);
        String line;
        while ((line = reader.readLine()) != null) {
            LogEntry entry = parseLine(line);
            if (entry != null) {
                if (ring.size() >= maxLines) {
                    ring.pollFirst();
                }
                ring.offerLast(entry);
            }
        }
        for (LogEntry entry : ring) {
            buffer.offer(entry);
        }
    }

    private void mainLoop() {
        while (!stopped.get()) {
            WatchKey key = null;
            try {
                key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed != null && changed.toString().equals(logFile.getFileName().toString())) {
                            // 文件滚动：旧文件被重命名、新文件被创建 → 关闭旧 reader 并连接到新文件
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                                closeFile();
                                openFile();
                            }
                            readNewLines();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[LogWatcher] 读取异常: " + e.getMessage());
            } finally {
                // 无论是否异常都必须 reset，否则 WatchKey 失效导致后续事件全部丢失
                if (key != null) {
                    key.reset();
                }
            }
        }
    }

    private void readNewLines() throws IOException {
        if (reader == null) return;
        String line;
        while ((line = reader.readLine()) != null) {
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
        // 无法匹配标准格式的行（多行消息续行），logger 为空，前端按原始文本展示
        return new LogEntry(
            java.time.LocalTime.now().format(TIME_FMT),
            "INFO", "", line, line);
    }

    private synchronized void closeFile() {
        try {
            if (reader != null) {
                reader.close();
                reader = null;
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
