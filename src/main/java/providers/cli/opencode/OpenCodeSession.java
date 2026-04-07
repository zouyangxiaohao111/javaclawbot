package providers.cli.opencode;

import lombok.extern.slf4j.Slf4j;
import providers.cli.*;
import providers.cli.model.FileAttachment;
import providers.cli.model.ImageAttachment;
import providers.cli.model.PermissionResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import utils.GsonFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenCode CLI 会话
 *
 * OpenCode 每次对话启动新进程，使用 --session 保持连续性
 */
@Slf4j
public class OpenCodeSession implements CliAgentSession {

    private final CliAgentConfig config;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final Sinks.Many<CliEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService executor;

    // 当前进程
    private volatile Process currentProcess;
    private volatile OutputStream currentStdin;
    private volatile BufferedReader currentStdoutReader;
    private volatile Thread currentReaderThread;

    // 文件保存目录
    private final Path attachDir;

    public OpenCodeSession(CliAgentConfig config) throws IOException {
        this.config = config;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "opencode-" + config.workDir());
            t.setDaemon(true);
            return t;
        });

        // 创建附件目录
        this.attachDir = Path.of(config.workDir(), ".opencode", "attachments");
        Files.createDirectories(attachDir);

        log.info("OpenCode session created for: {}", config.workDir());
    }

    @Override
    public CompletableFuture<Void> send(String prompt, List<ImageAttachment> images, List<FileAttachment> files) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 构建完整 prompt
                String fullPrompt = buildPrompt(prompt, images, files);

                // 启动新进程
                startProcess(fullPrompt);

            } catch (Exception e) {
                log.error("Failed to send message to OpenCode", e);
                eventSink.tryEmitNext(CliEvent.error(e));
            }
        }, executor);
    }

    /**
     * 构建完整 prompt
     */
    private String buildPrompt(String prompt, List<ImageAttachment> images, List<FileAttachment> files) throws IOException {
        StringBuilder sb = new StringBuilder();

        if (prompt != null && !prompt.isBlank()) {
            sb.append(prompt);
        }

        // 保存图片并添加引用
        if (images != null && !images.isEmpty()) {
            sb.append("\n\nAttached images:\n");
            for (int i = 0; i < images.size(); i++) {
                ImageAttachment img = images.get(i);
                String fname = img.fileName();
                if (fname == null || fname.isBlank()) {
                    fname = "image_" + System.currentTimeMillis() + "_" + i;
                    // 添加扩展名
                    if (img.mimeType() != null) {
                        if (img.mimeType().contains("png")) fname += ".png";
                        else if (img.mimeType().contains("jpeg") || img.mimeType().contains("jpg")) fname += ".jpg";
                        else if (img.mimeType().contains("gif")) fname += ".gif";
                    }
                }
                Path fpath = attachDir.resolve(fname);
                Files.write(fpath, img.data());
                sb.append("- ").append(fpath).append("\n");
            }
        }

        // 保存文件并添加引用
        if (files != null && !files.isEmpty()) {
            sb.append("\n\nAttached files:\n");
            for (int i = 0; i < files.size(); i++) {
                FileAttachment file = files.get(i);
                String fname = file.fileName();
                if (fname == null || fname.isBlank()) {
                    fname = "file_" + System.currentTimeMillis() + "_" + i;
                }
                Path fpath = attachDir.resolve(fname);
                Files.write(fpath, file.data());
                sb.append("- ").append(fpath).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 启动进程
     */
    private void startProcess(String prompt) throws IOException {
        // 停止之前的进程
        stopCurrentProcess();

        // 构建命令
        List<String> command = buildCommand(prompt);
        log.info("Starting OpenCode: {}", String.join(" ", command));

        // 创建进程
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(config.workDir()));
        pb.environment().putAll(buildEnvironment());

        currentProcess = pb.start();
        currentStdin = currentProcess.getOutputStream();
        currentStdoutReader = new BufferedReader(
                new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8));

        // 启动事件读取线程
        currentReaderThread = new Thread(() -> readEvents(currentProcess, currentStdoutReader), "opencode-reader");
        currentReaderThread.setDaemon(true);
        currentReaderThread.start();

        // 启动错误流读取
        Thread errorThread = new Thread(() -> readErrors(currentProcess), "opencode-error");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    /**
     * 构建启动命令
     */
    private List<String> buildCommand(String prompt) {
        List<String> args = new ArrayList<>();
        if (isWindows()) {
            args.add("cmd.exe");
            args.add("/c");
        }
        args.add("opencode");
        args.add("run");
        args.add("--format");
        args.add("json");
        args.add("--thinking");

        // 会话持续
        if (sessionId.get() != null) {
            args.add("--session");
            args.add(sessionId.get());
        }

        // 模型
        if (config.model() != null) {
            args.add("--model");
            args.add(config.model());
        }

        // 模式
        if (config.mode() != null && !config.mode().equals("default")) {
            args.add("--mode");
            args.add(config.mode());
        }

        // 工作目录
        args.add("--dir");
        args.add(config.workDir());

        // Prompt
        args.add(prompt);

        return args;
    }

    /**
     * 构建环境变量
     */
    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());

        // Provider 配置
        for (var provider : config.providers()) {
            String prefix = provider.name().toUpperCase().replace("-", "_");
            if (provider.apiKey() != null) {
                env.put(prefix + "_API_KEY", provider.apiKey());
            }
            if (provider.baseUrl() != null) {
                env.put(prefix + "_BASE_URL", provider.baseUrl());
            }
            if (provider.env() != null) {
                env.putAll(provider.env());
            }
        }

        // 额外环境变量
        if (config.extraEnv() != null) {
            env.putAll(config.extraEnv());
        }

        return env;
    }

    /**
     * 停止当前进程
     */
    private void stopCurrentProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.toHandle().descendants().forEach(ProcessHandle::destroy);
            currentProcess.destroy();
        }
    }

    /**
     * 读取事件循环
     */
    private void readEvents(Process process, BufferedReader reader) {
        try {
            String line;
            while (running.get() && process.isAlive() && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                try {
                    CliEvent event = parseEvent(line);
                    if (event != null) {
                        // 提取 session ID
                        if (event.type() == CliEventType.SESSION_ID && event.sessionId() != null) {
                            sessionId.set(event.sessionId());
                        }

                        eventSink.tryEmitNext(event);

                        // 完成
                        if (event.done()) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse event: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("Error reading OpenCode output", e);
                eventSink.tryEmitNext(CliEvent.error(e));
            }
        }
    }

    /**
     * 读取错误流
     */
    private void readErrors(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.warn("[OpenCode stderr] {}", line);
                }
            }
        } catch (IOException e) {
            log.debug("Error reading stderr", e);
        }
    }

    /**
     * 解析 NDJSON 事件
     */
    @SuppressWarnings("unchecked")
    private CliEvent parseEvent(String line) {
        try {
            Map<String, Object> raw = GsonFactory.getGson().fromJson(line, Map.class);
            if (raw == null) return null;

            String type = (String) raw.get("type");
            if (type == null) return null;

            return switch (type) {
                case "step_start" -> parseStepStartEvent(raw);
                case "text" -> parseTextEvent(raw);
                case "reasoning" -> parseReasoningEvent(raw);
                case "tool_use" -> parseToolUseEvent(raw);
                case "tool_result" -> parseToolResultEvent(raw);
                case "step_finish" -> parseStepFinishEvent(raw);
                case "error" -> parseErrorEvent(raw);
                default -> {
                    log.debug("Unknown event type: {}", type);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to parse event: {}", line, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseStepStartEvent(Map<String, Object> raw) {
        Map<String, Object> part = (Map<String, Object>) raw.get("part");
        if (part == null) return null;

        String sid = (String) part.get("sessionID");
        if (sid != null) {
            sessionId.set(sid);
            return CliEvent.sessionId(sid);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseTextEvent(Map<String, Object> raw) {
        Map<String, Object> part = (Map<String, Object>) raw.get("part");
        if (part == null) return null;

        String text = (String) part.get("text");
        return CliEvent.text(text);
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseReasoningEvent(Map<String, Object> raw) {
        Map<String, Object> part = (Map<String, Object>) raw.get("part");
        if (part == null) return null;

        String text = (String) part.get("text");
        return CliEvent.thinking(text);
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseToolUseEvent(Map<String, Object> raw) {
        Map<String, Object> part = (Map<String, Object>) raw.get("part");
        if (part == null) return null;

        String tool = (String) part.get("tool");
        Map<String, Object> state = (Map<String, Object>) part.get("state");
        Map<String, Object> input = state != null ? (Map<String, Object>) state.get("input") : null;

        String inputSummary = input != null ? GsonFactory.getGson().toJson(input) : "";
        if (inputSummary.length() > 100) {
            inputSummary = inputSummary.substring(0, 100) + "...";
        }

        return CliEvent.toolUse(tool, inputSummary, input);
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseToolResultEvent(Map<String, Object> raw) {
        Map<String, Object> part = (Map<String, Object>) raw.get("part");
        if (part == null) return null;

        String tool = (String) part.get("tool");
        Map<String, Object> state = (Map<String, Object>) part.get("state");

        String output = null;
        String status = null;
        Integer exitCode = null;
        Boolean success = null;

        if (state != null) {
            output = (String) state.get("output");
            status = (String) state.get("status");
            Object ec = state.get("exit_code");
            if (ec instanceof Number) {
                exitCode = ((Number) ec).intValue();
            }
            Object s = state.get("success");
            if (s instanceof Boolean) {
                success = (Boolean) s;
            }
        }

        return CliEvent.toolResult(tool, output, status, exitCode, success);
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseStepFinishEvent(Map<String, Object> raw) {
        Map<String, Object> part = (Map<String, Object>) raw.get("part");
        if (part == null) {
            return CliEvent.result("", sessionId.get(), 0, 0);
        }

        String text = (String) part.get("text");

        // 提取 usage
        Map<String, Object> usage = (Map<String, Object>) part.get("usage");
        int inputTokens = 0;
        int outputTokens = 0;
        if (usage != null) {
            inputTokens = getInt(usage, "input_tokens", 0);
            outputTokens = getInt(usage, "output_tokens", 0);
        }

        return CliEvent.result(text != null ? text : "", sessionId.get(), inputTokens, outputTokens);
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseErrorEvent(Map<String, Object> raw) {
        Map<String, Object> error = (Map<String, Object>) raw.get("error");
        String message = "Unknown error";

        if (error != null) {
            Map<String, Object> data = (Map<String, Object>) error.get("data");
            if (data != null) {
                message = (String) data.get("message");
            }
            if (message == null) {
                message = (String) error.get("message");
            }
        }

        return CliEvent.error(new RuntimeException(message != null ? message : "Unknown error"));
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    @Override
    public CompletableFuture<Void> respondPermission(String requestId, PermissionResult result) {
        // OpenCode 通过模式控制权限，不需要手动响应
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Flux<CliEvent> events() {
        return eventSink.asFlux();
    }

    @Override
    public String currentSessionId() {
        return sessionId.get();
    }

    @Override
    public boolean isAlive() {
        return running.get() && currentProcess != null && currentProcess.isAlive();
    }

    @Override
    public CompletableFuture<Void> close() {
        running.set(false);

        return CompletableFuture.runAsync(() -> {
            try {
                stopCurrentProcess();
                executor.shutdownNow();
                log.info("OpenCode session closed: {}", config.workDir());
            } catch (Exception e) {
                log.warn("Error closing OpenCode session", e);
            }
        });
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    @Override
    public CliAgentConfig getConfig() {
        return config;
    }
}
