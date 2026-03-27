package heartbeat;

import config.Config;
import providers.LLMProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 心跳服务：周期性唤醒代理检查是否存在待执行任务
 *
 * 对齐 OpenClaw 的 heartbeat-runner.ts 实现：
 * - 使用 HEARTBEAT_OK token 匹配（而非虚拟工具调用）
 * - 支持空文件检测跳过
 * - 支持去重机制
 * - 支持 cron/exec 事件触发
 * - 支持活跃时间配置
 */
public final class HeartbeatService {

    private static final Logger LOG = Logger.getLogger(HeartbeatService.class.getName());

    /** 心跳确认 token（对齐 OpenClaw HEARTBEAT_TOKEN） */
    public static final String HEARTBEAT_TOKEN = "HEARTBEAT_OK";


    /** 默认确认消息最大字符数 */
    public static final int DEFAULT_HEARTBEAT_ACK_MAX_CHARS = 300;

    /** 默认心跳提示词（对齐 OpenClaw AGENTS.md 中的 Memory Maintenance 指导） */
    public static final String DEFAULT_HEARTBEAT_PROMPT =
        "如果HEARTBEAT.md存在（工作区上下文），请读取它。严格遵守。不要从之前的聊天中推断或重复旧任务。如果没有什么需要注意的，请回复HEARTBEAT_OK。" +
                """
                   ## 记忆维护
                   定期(每隔6小时),你应该：
                   1.阅读近期的 memory/YYYY-MM-DD.md 文件.
                   2. 识别出值得长期保留的重要事件、经验教训或洞见, 提炼用户反复强调 正确的流程,以及对应可复用的调用工具
                   3. 用提炼出的学习内容更新 MEMORY.md
                   4. 从 MEMORY.md 中移除不再相关的过时信息
                   每日文件是原始笔记，MEMORY.md 是经过筛选的智慧结晶。
                   
                   ## 主动工作
                   你还可以：
                   - 阅读并整理记忆文件
                   - 检查项目状态（例如 git svn 状态等）
                   - 更新文档
                   - 提交并推送你自己的更改
                """;

    /** 心跳运行结果 */
    public record HeartbeatRunResult(
        String status,      // "ran", "skipped", "failed"
        String reason,      // 跳过/失败原因
        long durationMs,    // 执行时长
        String preview      // 发送内容预览
    ) {}

    /** 心跳配置 */
    public record HeartbeatInnerConfig(
        boolean enabled,
        int intervalMs,
        String prompt,
        String target,
        String model,
        int ackMaxChars,
        boolean isolatedSession,
        boolean includeReasoning,
        String activeHoursStart,  // e.g. "09:00"
        String activeHoursEnd     // e.g. "18:00"
    ) {}

    /** 心跳状态 */
    public record HeartbeatState(
        String lastHeartbeatText,
        long lastHeartbeatSentAt
    ) {}

    private final Path workspace;
    private final LLMProvider provider;
    private final String model;
    private final Function<String, CompletableFuture<String>> onExecute;
    private final Function<String, CompletableFuture<Void>> onNotify;
    private final HeartbeatInnerConfig config;

    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;
    private HeartbeatState lastState = new HeartbeatState(null, 0);

    public HeartbeatService(
            Path workspace,
            LLMProvider provider,
            String model,
            Function<String, CompletableFuture<String>> onExecute,
            Function<String, CompletableFuture<Void>> onNotify,
            HeartbeatInnerConfig config
    ) {
        this.workspace = workspace;
        this.provider = provider;
        this.model = config.model() != null && !config.model().isBlank() 
            ? config.model() : model;
        this.onExecute = onExecute;
        this.onNotify = onNotify;
        this.config = config;
    }

    /**
     * 便捷构造：使用默认配置
     */
    public HeartbeatService(
            Path workspace,
            LLMProvider provider,
            String model,
            Function<String, CompletableFuture<String>> onExecute,
            Function<String, CompletableFuture<Void>> onNotify
    ) {
        this(workspace, provider, model, onExecute, onNotify, 
            new HeartbeatInnerConfig(true, 30 * 60 * 1000, DEFAULT_HEARTBEAT_PROMPT,
                "none", null, DEFAULT_HEARTBEAT_ACK_MAX_CHARS, false, false, null, null));
    }

    public Path getHeartbeatFile() {
        return workspace.resolve("HEARTBEAT.md");
    }

    /**
     * 启动心跳服务
     */
    public synchronized CompletableFuture<Void> start() {
        if (!config.enabled()) {
            LOG.log(Level.INFO, "Heartbeat disabled");
            return CompletableFuture.completedFuture(null);
        }
        if (running) {
            LOG.log(Level.WARNING, "Heartbeat already running");
            return CompletableFuture.completedFuture(null);
        }

        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javaclawbot-heartbeat");
            t.setDaemon(true);
            return t;
        });

        // 对齐 OpenClaw：先等待 interval，再执行
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running) return;
            try {
                runOnce("interval").join();
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Heartbeat error: " + e.getMessage(), e);
            }
        }, config.intervalMs(), config.intervalMs(), TimeUnit.MILLISECONDS);

        LOG.log(Level.INFO, "Heartbeat started (every {0}ms)", config.intervalMs());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 停止心跳服务
     */
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * 手动触发一次心跳
     */
    public CompletableFuture<HeartbeatRunResult> triggerNow() {
        return runOnce("manual");
    }

    /**
     * Cron 事件触发心跳
     */
    public CompletableFuture<HeartbeatRunResult> triggerCronEvent(String eventContent) {
        return runOnceWithEvent("cron-event", eventContent);
    }

    /**
     * Exec 完成事件触发心跳
     */
    public CompletableFuture<HeartbeatRunResult> triggerExecEvent() {
        return runOnceWithEvent("exec-event", null);
    }

    /**
     * 更新心跳状态（用于去重）
     */
    public void updateState(String text, long sentAt) {
        lastState = new HeartbeatState(text, sentAt);
    }

    // -------------------- 核心逻辑 --------------------

    /**
     * 执行一次心跳检查
     */
    public CompletableFuture<HeartbeatRunResult> runOnce(String reason) {
        return runOnceWithEvent(reason, null);
    }

    /**
     * 执行一次心跳检查（带事件内容）
     */
    private CompletableFuture<HeartbeatRunResult> runOnceWithEvent(String reason, String eventContent) {
        long startedAt = System.currentTimeMillis();

        // 检查活跃时间
        if (!isWithinActiveHours(startedAt)) {
            return CompletableFuture.completedFuture(
                new HeartbeatRunResult("skipped", "quiet-hours", 0, null));
        }

        // 读取 HEARTBEAT.md
        String content = readHeartbeatFile();

        // 空文件检测（对齐 OpenClaw isHeartbeatContentEffectivelyEmpty）
        if (content != null && isHeartbeatContentEffectivelyEmpty(content)) {
            emitHeartbeatEvent("skipped", "empty-heartbeat-file", 0, null);
            return CompletableFuture.completedFuture(
                new HeartbeatRunResult("skipped", "empty-heartbeat-file", 0, null));
        }

        // 构建提示词
        String prompt = buildPrompt(reason, eventContent, content);

        // 调用模型
        return callModel(prompt)
            .thenApply(response -> {
                long durationMs = System.currentTimeMillis() - startedAt;

                if (response == null || response.isBlank()) {
                    emitHeartbeatEvent("ok-empty", reason, durationMs, null);
                    return new HeartbeatRunResult("ran", null, durationMs, null);
                }

                // 检查 HEARTBEAT_OK token
                String normalizedResponse = normalizeHeartbeatReply(response, config.ackMaxChars());
                
                if (isHeartbeatOkToken(normalizedResponse)) {
                    emitHeartbeatEvent("ok-token", reason, durationMs, null);
                    return new HeartbeatRunResult("ran", null, durationMs, null);
                }

                // 去重检查
                if (isDuplicateHeartbeat(normalizedResponse, startedAt)) {
                    emitHeartbeatEvent("skipped", "duplicate", durationMs, normalizedResponse);
                    return new HeartbeatRunResult("ran", null, durationMs, null);
                }

                // 有实际内容需要发送
                if (onNotify != null) {
                    updateState(normalizedResponse, startedAt);
                    onNotify.apply(normalizedResponse);
                }

                emitHeartbeatEvent("sent", reason, durationMs, normalizedResponse);
                return new HeartbeatRunResult("ran", null, durationMs, normalizedResponse);
            })
            .exceptionally(ex -> {
                long durationMs = System.currentTimeMillis() - startedAt;
                emitHeartbeatEvent("failed", ex.getMessage(), durationMs, null);
                return new HeartbeatRunResult("failed", ex.getMessage(), durationMs, null);
            });
    }

    /**
     * 构建心跳提示词
     */
    private String buildPrompt(String reason, String eventContent, String heartbeatContent) {
        StringBuilder prompt = new StringBuilder();

        // 根据触发源构建不同的提示词
        if ("cron-event".equals(reason) && eventContent != null) {
            prompt.append("A scheduled reminder has been triggered. The reminder content is:\n\n")
                  .append(eventContent)
                  .append("\n\nPlease relay this reminder to the user in a helpful and friendly way.");
        } else if ("exec-event".equals(reason)) {
            prompt.append("An async command you ran earlier has completed. The result is shown in the system messages above. ")
                  .append("Please relay the command output to the user in a helpful way.");
        } else {
            // 常规心跳
            prompt.append(config.prompt() != null ? config.prompt() : DEFAULT_HEARTBEAT_PROMPT);
            
            if (heartbeatContent != null && !heartbeatContent.isBlank()) {
                prompt.append("\n\nHEARTBEAT.md content:\n\n").append(heartbeatContent);
            }
        }

        // 添加当前时间
        prompt.append("\n\nCurrent time: ").append(new java.util.Date().toString());

        return prompt.toString();
    }

    /**
     * 调用模型获取回复
     */
    private CompletableFuture<String> callModel(String prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", prompt);
        messages.add(user);

        return provider.chatWithRetry(messages, null, model, 8192, 0.7)
            .thenApply(resp -> {
                if (resp == null || resp.getContent() == null) {
                    return null;
                }
                return resp.getContent();
            });
    }

    /**
     * 检查是否是 HEARTBEAT_OK token
     * 对齐 OpenClaw 的 token 匹配逻辑
     */
    private boolean isHeartbeatOkToken(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String trimmed = text.trim();
        String lower = trimmed.toLowerCase();
        String tokenLower = HEARTBEAT_TOKEN.toLowerCase();

        // 完全匹配
        if (lower.equals(tokenLower)) {
            return true;
        }

        // 以 token 开头
        if (lower.startsWith(tokenLower)) {
            String suffix = lower.substring(tokenLower.length());
            // 检查后缀是否只是标点符号
            if (suffix.isEmpty() || !suffix.matches(".*[a-z0-9_].*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 规范化心跳回复
     * 对齐 OpenClaw 的 normalizeHeartbeatReply
     */
    private String normalizeHeartbeatReply(String text, int ackMaxChars) {
        if (text == null) {
            return "";
        }

        String stripped = stripHeartbeatToken(text);
        
        // 限制长度
        if (stripped.length() > ackMaxChars) {
            stripped = stripped.substring(0, ackMaxChars) + "...";
        }

        return stripped.trim();
    }

    /**
     * 移除 HEARTBEAT_OK token
     */
    private String stripHeartbeatToken(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmed = text.trim();
        String token = HEARTBEAT_TOKEN;
        String tokenLower = token.toLowerCase();
        String lower = trimmed.toLowerCase();

        // 移除开头的 token
        if (lower.startsWith(tokenLower)) {
            trimmed = trimmed.substring(token.length()).trim();
        }

        // 移除结尾的 token（可能带标点）
        Pattern trailingPattern = Pattern.compile(
            Pattern.quote(token) + "[^\\w]{0,4}$",
            Pattern.CASE_INSENSITIVE
        );
        trimmed = trailingPattern.matcher(trimmed).replaceAll("").trim();

        return trimmed;
    }

    /**
     * 检查是否是重复的心跳
     * 对齐 OpenClaw 的去重逻辑（24小时内相同内容跳过）
     */
    private boolean isDuplicateHeartbeat(String text, long now) {
        if (lastState.lastHeartbeatText() == null || lastState.lastHeartbeatText().isBlank()) {
            return false;
        }

        if (text == null || text.isBlank()) {
            return false;
        }

        // 内容相同且在24小时内
        if (text.trim().equals(lastState.lastHeartbeatText().trim())) {
            long elapsed = now - lastState.lastHeartbeatSentAt();
            return elapsed < 24 * 60 * 60 * 1000;
        }

        return false;
    }

    /**
     * 检查 HEARTBEAT.md 内容是否"实际上为空"
     * 对齐 OpenClaw 的 isHeartbeatContentEffectivelyEmpty
     */
    public static boolean isHeartbeatContentEffectivelyEmpty(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过空行
            if (trimmed.isEmpty()) {
                continue;
            }

            // 跳过 markdown 标题行（# 开头）
            if (trimmed.matches("^#+\\s.*")) {
                continue;
            }

            // 跳过空的列表项（- [ ] 或 * [ ] 或 - ）
            if (trimmed.matches("^[-*+]\\s*(\\[[\\sXx]?\\]\\s*)?$")) {
                continue;
            }

            // 找到非空、非注释的内容
            return false;
        }

        // 所有行都是空的或注释
        return true;
    }

    /**
     * 检查当前时间是否在活跃时间内
     */
    private boolean isWithinActiveHours(long nowMs) {
        if (config.activeHoursStart() == null || config.activeHoursEnd() == null) {
            return true; // 未配置则始终活跃
        }

        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(nowMs);
            int currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int currentMinute = cal.get(java.util.Calendar.MINUTE);
            int currentTime = currentHour * 60 + currentMinute;

            int startMinutes = parseTimeToMinutes(config.activeHoursStart());
            int endMinutes = parseTimeToMinutes(config.activeHoursEnd());

            if (startMinutes <= endMinutes) {
                // 同一天内的时间范围
                return currentTime >= startMinutes && currentTime <= endMinutes;
            } else {
                // 跨天的时间范围（如 22:00 - 06:00）
                return currentTime >= startMinutes || currentTime <= endMinutes;
            }
        } catch (Exception e) {
            return true; // 解析失败则默认活跃
        }
    }

    private int parseTimeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /**
     * 读取 HEARTBEAT.md 文件
     */
    private String readHeartbeatFile() {
        Path file = getHeartbeatFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 发射心跳事件（用于日志/监控）
     */
    private void emitHeartbeatEvent(String status, String reason, long durationMs, String preview) {
        String previewStr = preview != null && preview.length() > 200 
            ? preview.substring(0, 200) + "..." 
            : preview;
        
        LOG.log(Level.INFO, "Heartbeat: status={0}, reason={1}, duration={2}ms, preview={3}",
            new Object[]{status, reason, durationMs, previewStr});
    }

    // -------------------- 配置解析 --------------------

    /**
     * 从配置解析心跳配置
     */
    public static HeartbeatInnerConfig parseConfig(Config cfg) {
        config.hearbet.HeartbeatConfig hb = cfg.getAgents().getDefaults().getHeartbeat();

        if (hb == null) {
            return new HeartbeatInnerConfig(
                true, 
                30 * 60 * 1000, 
                DEFAULT_HEARTBEAT_PROMPT,
                "none", 
                null, 
                DEFAULT_HEARTBEAT_ACK_MAX_CHARS,
                false,
                false,
                null,
                null
            );
        }

        int intervalMs = parseInterval(hb.getEvery());
        
        return new HeartbeatInnerConfig(
            hb.isEnabled(),
            intervalMs,
            hb.getPrompt() != null && !hb.getPrompt().isBlank() 
                ? hb.getPrompt() : DEFAULT_HEARTBEAT_PROMPT,
            hb.getTarget() != null ? hb.getTarget() : "none",
            hb.getModel(),
            hb.getAckMaxChars() > 0 ? hb.getAckMaxChars() : DEFAULT_HEARTBEAT_ACK_MAX_CHARS,
            hb.isIsolatedSession(),
            hb.isIncludeReasoning(),
            hb.getActiveHoursStart(),
            hb.getActiveHoursEnd()
        );
    }

    /**
     * 解析时间间隔字符串
     */
    public static int parseInterval(String every) {
        if (every == null || every.isBlank()) {
            return 30 * 60 * 1000; // 默认 30 分钟
        }

        String trimmed = every.trim().toLowerCase();
        int multiplier = 1;
        int value;

        if (trimmed.endsWith("s")) {
            multiplier = 1000;
            value = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
        } else if (trimmed.endsWith("m") && !trimmed.endsWith("ms")) {
            multiplier = 60 * 1000;
            value = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
        } else if (trimmed.endsWith("h")) {
            multiplier = 60 * 60 * 1000;
            value = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
        } else if (trimmed.endsWith("ms")) {
            value = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
        } else {
            // 默认分钟
            multiplier = 60 * 1000;
            value = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
        }

        return value * multiplier;
    }
}