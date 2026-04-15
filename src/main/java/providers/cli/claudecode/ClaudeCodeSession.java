package providers.cli.claudecode;

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
 * Claude Code CLI 会话
 *
 * 通过 ProcessBuilder 启动 claude CLI，使用 NDJSON 格式交互
 */
@Slf4j
public class ClaudeCodeSession implements CliAgentSession {

    private final CliAgentConfig config;
    private final Process process;
    private final OutputStream stdin;
    private final BufferedReader stdoutReader;
    private final Sinks.Many<CliEvent> eventSink;
    private final Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final ExecutorService executor;

    // 等待权限响应的 Map
    private final Map<String, CompletableFuture<PermissionResult>> pendingPermissions = new ConcurrentHashMap<>();

    public ClaudeCodeSession(CliAgentConfig config) throws IOException {
        this.config = config;
        this.eventSink = Sinks.many().multicast().onBackpressureBuffer();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "claude-code-" + config.workDir());
            t.setDaemon(true);
            return t;
        });

        // 构建命令
        List<String> command = buildCommand();
        log.info("Starting Claude Code: {}", String.join(" ", command));

        // 创建进程
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(config.workDir()));
        pb.environment().putAll(buildEnvironment());

        // 设置工作目录
        if (config.workDir() != null) {
            pb.directory(new File(config.workDir()));
        }

        process = pb.start();
        stdin = process.getOutputStream();
        stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // 启动事件读取线程
        readerThread = new Thread(this::readEvents, "claude-code-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // 启动错误流读取
        Thread errorThread = new Thread(this::readErrors, "claude-code-error");
        errorThread.setDaemon(true);
        errorThread.start();

        log.info("Claude Code session started for: {}", config.workDir());
    }

    /**
     * 构建启动命令
     */
    private List<String> buildCommand() {
        List<String> args = new ArrayList<>();
        if (isWindows()) {
            args.add("cmd.exe");
            args.add("/c");
        }
        args.add("claude");
        args.add("--output-format");
        args.add("stream-json");
        args.add("--input-format");
        args.add("stream-json");
        args.add("--permission-prompt-tool");
        args.add("stdio");

        // Verbose 模式（stream-json 需要此参数输出事件流）
        if (!config.disableVerbose()) {
            args.add("--verbose");
        }

        // 权限模式
        if (config.mode() != null && !config.mode().equals("default")) {
            args.add("--permission-mode");
            args.add(config.mode());
        }

        // 模型
        if (config.model() != null) {
            args.add("--model");
            args.add(config.model());
        }

        // 允许的工具
        if (!config.allowedTools().isEmpty()) {
            args.add("--allowedTools");
            args.add(String.join(",", config.allowedTools()));
        }

        // 禁用的工具
        if (!config.disallowedTools().isEmpty()) {
            args.add("--disallowedTools");
            args.add(String.join(",", config.disallowedTools()));
        }

        return args;
    }

    /**
     * 构建环境变量
     */
    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());

        // 工作目录
        env.put("CLAUDE_CODE_PROJECT", config.workDir());

        // Router 配置
        if (config.routerUrl() != null) {
            env.put("ANTHROPIC_BASE_URL", config.routerUrl());
        }
        if (config.routerApiKey() != null) {
            env.put("ANTHROPIC_API_KEY", config.routerApiKey());
        }

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

        // 禁用 verbose (router 模式)
        if (config.disableVerbose() || config.routerUrl() != null) {
            env.put("CLAUDE_CODE_DISABLE_NON_INTERACTIVE", "1");
        }

        return env;
    }

    /**
     * 读取事件循环
     */
    private void readEvents() {
        log.debug("[ClaudeCode] Starting event reader thread");
        int eventCount = 0;
        try {
            String line;
            while (running.get() && (line = stdoutReader.readLine()) != null) {
                if (line.isBlank()) continue;

                try {
                    List<CliEvent> events = parseEvent(line);
                    if (events != null && !events.isEmpty()) {
                        for (CliEvent event : events) {
                            eventCount++;
                            // 提取 session ID
                            if (event.type() == CliEventType.SESSION_ID && event.sessionId() != null) {
                                sessionId.set(event.sessionId());
                                log.info("[ClaudeCode] Session ID: {}", event.sessionId());
                            }

                            log.trace("[ClaudeCode] Event #{}: type={}", eventCount, event.type());
                            eventSink.tryEmitNext(event);
                        }
                    }
                } catch (Exception e) {
                    // 非 JSON 行（如 banner 文本），跳过
                    log.debug("[ClaudeCode] Non-JSON line: {}", line);
                }
            }
            log.debug("[ClaudeCode] Event reader finished, total events: {}", eventCount);
        } catch (IOException e) {
            if (running.get()) {
                log.error("[ClaudeCode] Error reading output", e);
                eventSink.tryEmitNext(CliEvent.error(e));
            }
        } finally {
            running.set(false);
            eventSink.tryEmitComplete();
        }
    }

    /**
     * 读取错误流
     */
    private void readErrors() {
        log.debug("[ClaudeCode] Starting error reader thread");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.warn("[ClaudeCode stderr] {}", line);
                }
            }
            log.debug("[ClaudeCode] Error reader finished");
        } catch (IOException e) {
            log.debug("[ClaudeCode] Error reading stderr: {}", e.getMessage());
        }
    }

    /**
     * 解析 NDJSON 事件
     */
    @SuppressWarnings("unchecked")
    private List<CliEvent> parseEvent(String line) {
        try {
            Map<String, Object> raw = GsonFactory.getGson().fromJson(line, Map.class);
            if (raw == null) {
                log.trace("[ClaudeCode] Received null JSON");
                return List.of();
            }

            String type = (String) raw.get("type");
            if (type == null) {
                log.trace("[ClaudeCode] Received JSON without type field");
                return List.of();
            }

            log.debug("[ClaudeCode] Received event: type={}, 原始json:{}", type, GsonFactory.getGson().toJson(raw));

            return switch (type) {
                case "system" -> List.of(parseSystemEvent(raw));
                case "assistant" -> parseAssistantEvent(raw);
                case "user" -> List.of(parseUserEvent(raw));
                case "result" -> List.of(parseResultEvent(raw));
                case "control_request" -> List.of(parseControlRequestEvent(raw));
                case "control_cancel_request" -> List.of(parseControlCancelEvent(raw));
                case "error" -> List.of(parseErrorEvent(raw));
                default -> {
                    log.debug("[ClaudeCode] Unknown event type: {}, raw: {}", type, line);
                    yield List.of();
                }
            };
        } catch (Exception e) {
                log.debug("[ClaudeCode] Non-JSON line ({} chars): {}", line.length(), line);
                return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseSystemEvent(Map<String, Object> raw) {
        String sid = (String) raw.get("session_id");
        return CliEvent.sessionId(sid);
    }

    @SuppressWarnings("unchecked")
    private List<CliEvent> parseAssistantEvent(Map<String, Object> raw) {
        Map<String, Object> message = (Map<String, Object>) raw.get("message");
        if (message == null) return List.of();

        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        if (content == null) return List.of();

        // 处理所有内容块，而不是只返回第一个
        List<CliEvent> events = new ArrayList<>();
        for (Map<String, Object> block : content) {
            String blockType = (String) block.get("type");

            if ("text".equals(blockType)) {
                String text = (String) block.get("text");
                events.add(CliEvent.text(text));
            }

            if ("thinking".equals(blockType)) {
                String thinking = (String) block.get("thinking");
                events.add(CliEvent.thinking(thinking));
            }

            if ("tool_use".equals(blockType)) {
                String id = (String) block.get("id");
                String name = (String) block.get("name");
                Map<String, Object> input = (Map<String, Object>) block.get("input");
                String inputSummary = summarizeToolInput(name, input);
                events.add(CliEvent.toolUse(name, inputSummary, input));
            }
        }

        return events;
    }

    /**
     * 解析 user 事件 - 包含工具执行结果
     */
    @SuppressWarnings("unchecked")
    private CliEvent parseUserEvent(Map<String, Object> raw) {
        Map<String, Object> message = (Map<String, Object>) raw.get("message");
        if (message == null) return null;

        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        if (content == null) return null;

        // 处理工具结果
        for (Map<String, Object> block : content) {
            String blockType = (String) block.get("type");

            if ("tool_result".equals(blockType)) {
                String toolUseId = (String) block.get("tool_use_id");
                String toolResult = (String) block.get("content");
                Boolean isError = (Boolean) block.get("is_error");

                // 如果是错误，记录日志
                if (Boolean.TRUE.equals(isError)) {
                    log.warn("[ClaudeCode] Tool execution error: {}", toolResult);
                }

                return CliEvent.toolResult(null, toolResult,
                        Boolean.TRUE.equals(isError) ? "failed" : "completed",
                        null, !Boolean.TRUE.equals(isError));
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseResultEvent(Map<String, Object> raw) {
        String result = (String) raw.get("result");
        Map<String, Object> usage = (Map<String, Object>) raw.get("usage");

        int inputTokens = 0;
        int outputTokens = 0;
        if (usage != null) {
            inputTokens = getInt(usage, "input_tokens", 0);
            outputTokens = getInt(usage, "output_tokens", 0);
        }

        return CliEvent.result(result, sessionId.get(), inputTokens, outputTokens);
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseControlRequestEvent(Map<String, Object> raw) {
        String requestId = (String) raw.get("request_id");
        Map<String, Object> request = (Map<String, Object>) raw.get("request");

        if (request == null) return null;

        String subtype = (String) request.get("subtype");
        String toolName = (String) request.get("tool_name");
        Map<String, Object> input = (Map<String, Object>) request.get("input");

        String inputSummary = summarizeToolInput(toolName, input);

        return CliEvent.permissionRequest(requestId, toolName, inputSummary, input, null);
    }

    private CliEvent parseControlCancelEvent(Map<String, Object> raw) {
        String requestId = (String) raw.get("request_id");
        // 取消权限请求
        CompletableFuture<PermissionResult> future = pendingPermissions.remove(requestId);
        if (future != null) {
            future.cancel(true);
        }
        return null; // 不发送事件
    }

    @SuppressWarnings("unchecked")
    private CliEvent parseErrorEvent(Map<String, Object> raw) {
        String message = null;
        Map<String, Object> error = (Map<String, Object>) raw.get("error");
        if (error != null) {
            message = (String) error.get("message");
            if (message == null) {
                Map<String, Object> data = (Map<String, Object>) error.get("data");
                if (data != null) {
                    message = (String) data.get("message");
                }
            }
        }
        return CliEvent.error(new RuntimeException(message != null ? message : "Unknown error"));
    }

    private String summarizeToolInput(String toolName, Map<String, Object> input) {
        if (input == null) return "";

        return switch (toolName) {
            case "Read" -> {
                Object path = input.get("file_path");
                yield path != null ? path.toString() : "";
            }
            case "Bash" -> {
                Object cmd = input.get("command");
                yield cmd != null ? cmd.toString() : "";
            }
            case "Edit" -> {
                Object path = input.get("file_path");
                yield path != null ? path.toString() : "";
            }
            case "Write" -> {
                Object path = input.get("file_path");
                yield path != null ? path.toString() : "";
            }
            default -> {
                String json = GsonFactory.getGson().toJson(input);
                yield json.length() > 100 ? json.substring(0, 100) + "..." : json;
            }
        };
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    @Override
    public CompletableFuture<Void> send(String prompt, List<ImageAttachment> images, List<FileAttachment> files) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 纯文本消息（无图片/文件附件）使用简洁格式
                boolean hasAttachments = (images != null && !images.isEmpty()) || (files != null && !files.isEmpty());

                if (!hasAttachments) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("type", "user");
                    message.put("message", Map.of(
                            "role", "user",
                            "content", prompt != null ? prompt : ""
                    ));

                    writeJson(message);
                    log.debug("Sent message to Claude Code: {} chars", prompt != null ? prompt.length() : 0);
                    return;
                }

                // 带附件的消息使用多模态格式
                List<Object> parts = new ArrayList<>();

                // 保存图片并添加 base64 编码
                List<String> savedImagePaths = new ArrayList<>();
                if (images != null) {
                    Path attachDir = Path.of(config.workDir(), ".cc-connect", "attachments");
                    Files.createDirectories(attachDir);

                    for (int i = 0; i < images.size(); i++) {
                        ImageAttachment img = images.get(i);
                        String ext = ".png";
                        if (img.mimeType() != null) {
                            if (img.mimeType().contains("jpeg") || img.mimeType().contains("jpg")) ext = ".jpg";
                            else if (img.mimeType().contains("gif")) ext = ".gif";
                            else if (img.mimeType().contains("webp")) ext = ".webp";
                        }
                        String fname = img.fileName();
                        if (fname == null || fname.isBlank()) {
                            fname = "img_" + System.currentTimeMillis() + "_" + i + ext;
                        }
                        Path fpath = attachDir.resolve(fname);
                        Files.write(fpath, img.data());
                        savedImagePaths.add(fpath.toString());

                        String mimeType = img.mimeType() != null ? img.mimeType() : "image/png";
                        parts.add(Map.of(
                                "type", "image",
                                "source", Map.of(
                                        "type", "base64",
                                        "media_type", mimeType,
                                        "data", Base64.getEncoder().encodeToString(img.data())
                                )
                        ));
                    }
                }

                // 保存文件并添加路径引用
                List<String> filePaths = new ArrayList<>();
                if (files != null && !files.isEmpty()) {
                    Path attachDir = Path.of(config.workDir(), ".cc-connect", "attachments");
                    Files.createDirectories(attachDir);

                    for (int i = 0; i < files.size(); i++) {
                        FileAttachment file = files.get(i);
                        String fname = file.fileName();
                        if (fname == null || fname.isBlank()) {
                            fname = "file_" + System.currentTimeMillis() + "_" + i;
                        }
                        Path fpath = attachDir.resolve(fname);
                        Files.write(fpath, file.data());
                        filePaths.add(fpath.toString());
                    }
                }

                // 构建文本部分
                StringBuilder textPart = new StringBuilder();
                if (prompt != null && !prompt.isBlank()) {
                    textPart.append(prompt);
                } else if (!filePaths.isEmpty()) {
                    textPart.append("Please analyze the attached file(s).");
                } else {
                    textPart.append("Please analyze the attached image(s).");
                }
                if (!savedImagePaths.isEmpty()) {
                    textPart.append("\n\n(Images also saved locally: ").append(String.join(", ", savedImagePaths)).append(")");
                }
                if (!filePaths.isEmpty()) {
                    textPart.append("\n\n(Files saved locally, please read them: ").append(String.join(", ", filePaths)).append(")");
                }
                parts.add(Map.of("type", "text", "text", textPart.toString()));

                Map<String, Object> message = new LinkedHashMap<>();
                message.put("type", "user");
                message.put("message", Map.of(
                        "role", "user",
                        "content", parts
                ));

                writeJson(message);
                log.debug("Sent message to Claude Code: {} chars", prompt != null ? prompt.length() : 0);

            } catch (Exception e) {
                log.error("Failed to send message to Claude Code", e);
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }

    private void writeJson(Map<String, Object> data) throws IOException {
        String json = GsonFactory.getGson().toJson(data);
        synchronized (stdin) {
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
            stdin.flush();
        }
    }

    @Override
    public CompletableFuture<Void> respondPermission(String requestId, PermissionResult result) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 构建权限响应内容
                Map<String, Object> permResponse = new LinkedHashMap<>();
                if ("allow".equals(result.behavior())) {
                    permResponse.put("behavior", "allow");
                    // updatedInput 是必需字段，必须传入（如果没有修改，传入原始输入）
                    permResponse.put("updatedInput", result.updatedInput());
                } else {
                    permResponse.put("behavior", "deny");
                    String msg = result.message() != null ? result.message()
                            : "The user denied this tool use. Stop and wait for the user's instructions.";
                    permResponse.put("message", msg);
                }

                // 按照原版 cc-connect 的嵌套格式
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("type", "control_response");
                response.put("response", Map.of(
                        "subtype", "success",
                        "request_id", requestId,
                        "response", permResponse
                ));

                writeJson(response);
                log.debug("Sent permission response: {} -> {}", requestId, result.behavior());

            } catch (Exception e) {
                log.error("Failed to send permission response", e);
                throw new RuntimeException("Failed to send permission response", e);
            }
        }, executor);
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
        return running.get() && process != null && process.isAlive();
    }

    @Override
    public CompletableFuture<Void> close() {
        running.set(false);

        return CompletableFuture.runAsync(() -> {
            try {
                // 关闭 stdin
                if (stdin != null) {
                    stdin.close();
                }

                // 等待进程结束
                if (process != null && process.isAlive()) {
                    process.waitFor(5, TimeUnit.SECONDS);

                    if (process.isAlive()) {
                        // 销毁进程树
                        process.toHandle().descendants().forEach(ProcessHandle::destroy);
                        process.destroy();

                        Thread.sleep(200);

                        if (process.isAlive()) {
                            process.toHandle().descendants().forEach(h -> {
                                if (h.isAlive()) h.destroyForcibly();
                            });
                            if (process.isAlive()) {
                                process.destroyForcibly();
                            }
                        }
                    }
                }

                // 关闭 executor
                executor.shutdownNow();

                log.info("Claude Code session closed: {}", config.workDir());

            } catch (Exception e) {
                log.warn("Error closing Claude Code session", e);
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
