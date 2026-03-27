package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.channel.DiscordConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Discord 渠道实现（使用 Discord Gateway WebSocket + REST API 发送消息）。
 *
 * <p>对应 Python：discord gateway websocket + httpx。</p>
 * <p>实现要点：</p>
 * <ul>
 *   <li>Gateway：连接 -> 收到 HELLO(op=10) -> 启动 heartbeat -> IDENTIFY(op=2)</li>
 *   <li>事件：MESSAGE_CREATE -> 下载附件（<=20MB）到 ~/.javaclawbot/media -> 发布到 bus</li>
 *   <li>发送：REST /channels/{channelId}/messages，自动分片（<=2000 字符）</li>
 *   <li>Typing：收到入站消息时开始定时 typing，发出回复后停止 typing</li>
 *   <li>重连：连接异常或 op=7/op=9 时退出当前连接并 5 秒后重连</li>
 * </ul>
 */
public class DiscordChannel extends BaseChannel {

    private static final Logger LOG = Logger.getLogger(DiscordChannel.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Discord API 基地址（v10） */
    private static final String DISCORD_API_BASE = "https://discord.com/api/v10";

    /** 附件最大大小（20MB） */
    private static final long MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L;

    /** Discord 单条消息最大长度（2000） */
    private static final int MAX_MESSAGE_LEN = 2000;

    /** 配置 */
    private final DiscordConfig cfg;

    /** HTTP 客户端（REST + 附件下载） */
    private final HttpClient http;

    /** 执行器：用于跑 start 连接循环、HTTP 调用、下载附件等 */
    private final ExecutorService executor;

    /** 定时器：用于 heartbeat 与 typing */
    private final ScheduledExecutorService scheduler;

    /** WebSocket 连接 */
    private volatile WebSocket ws;

    /** gateway 序列号 s（heartbeat 时带上） */
    private final AtomicLong seq = new AtomicLong(-1);

    /** 心跳任务 */
    private volatile ScheduledFuture<?> heartbeatFuture;

    /** typing 任务：channelId -> future */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> typingFutures = new ConcurrentHashMap<>();

    /** 当前连接关闭信号：用于阻塞等待一条连接结束，然后再重连 */
    private volatile CountDownLatch connectionClosedLatch;

    /** 标记当前连接是否需要重连 */
    private volatile boolean reconnectRequested = false;

    public DiscordChannel(DiscordConfig config, MessageBus bus) {
        super(config, bus);
        this.cfg = config;

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(Redirect.NORMAL)
                .build();

        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("discord-channel-" + t.getId());
            return t;
        });

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("discord-scheduler-" + t.getId());
            return t;
        });

        // 覆盖父类渠道名
        this.name = "discord";
    }

    /**
     * 启动 Discord Gateway 连接循环（长期运行）。
     *
     * <p>注意：与 Python 一样，这里是“长时间运行任务”。ChannelManager.startAll()
     * 一般会并发启动各渠道并不等待它们结束。</p>
     */
    @Override
    public CompletionStage<Void> start() {
        if (cfg.getToken() == null || cfg.getToken().isBlank()) {
            LOG.severe("Discord bot token 未配置");
            return CompletableFuture.completedFuture(null);
        }

        setRunning(true);

        // 连接循环放后台线程跑，避免阻塞调用方
        Future<?> f = executor.submit(this::connectLoop);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 停止渠道：关闭 websocket、停止 heartbeat、停止 typing、关闭线程池。
     */
    @Override
    public CompletionStage<Void> stop() {
        setRunning(false);

        // 停止 heartbeat
        stopHeartbeat();

        // 停止 typing
        for (String channelId : new ArrayList<>(typingFutures.keySet())) {
            stopTyping(channelId);
        }

        // 关闭 websocket
        WebSocket current = this.ws;
        this.ws = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            } catch (Exception ignored) {
            }
        }

        // 唤醒等待中的连接
        CountDownLatch latch = this.connectionClosedLatch;
        if (latch != null) {
            latch.countDown();
        }

        // 关闭线程池（让进程能干净退出）
        executor.shutdownNow();
        scheduler.shutdownNow();

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 发送消息（通过 Discord REST API）。
     *
     * <p>行为对齐 Python：</p>
     * <ul>
     *   <li>按 2000 字符拆分</li>
     *   <li>只在第一段设置 reply_to（message_reference）</li>
     *   <li>遇到 rate limit(429) 自动等待 retry_after 重试（最多 3 次）</li>
     *   <li>最终会停止 typing（与 Python finally 相同）</li>
     * </ul>
     */
    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<String> chunks = splitMessage(msg.getContent(), MAX_MESSAGE_LEN);
                if (chunks.isEmpty()) {
                    return;
                }

                String url = DISCORD_API_BASE + "/channels/" + msg.getChatId() + "/messages";
                Map<String, String> headers = Map.of(
                        "Authorization", "Bot " + cfg.getToken(),
                        "Content-Type", "application/json"
                );

                for (int i = 0; i < chunks.size(); i++) {
                    String chunk = chunks.get(i);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", chunk);

                    // 只对第一段设置回复引用
                    if (i == 0 && msg.getReplyTo() != null && !msg.getReplyTo().isBlank()) {
                        payload.put("message_reference", Map.of("message_id", msg.getReplyTo()));
                        payload.put("allowed_mentions", Map.of("replied_user", false));
                    }

                    boolean ok = sendPayloadWithRetry(url, headers, payload);
                    if (!ok) {
                        // 失败则中断剩余分片（与 Python 一致）
                        break;
                    }
                }
            } finally {
                // 发送完毕停止 typing（Python: finally await _stop_typing(msg.chat_id)）
                stopTyping(msg.getChatId());
            }
        }, executor);
    }

    // =========================================================================
    // 连接循环
    // =========================================================================

    /**
     * 网关连接循环：断开后 5 秒重连（只要仍在 running）。
     */
    private void connectLoop() {
        while (isRunning()) {
            reconnectRequested = false;
            seq.set(-1);

            try {
                LOG.info("连接 Discord gateway... url=" + cfg.getGatewayUrl());

                connectionClosedLatch = new CountDownLatch(1);

                // 建立 websocket 连接
                WebSocket.Listener listener = new GatewayListener();
                WebSocket newWs = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .buildAsync(URI.create(cfg.getGatewayUrl()), listener)
                        .join();

                this.ws = newWs;

                // 阻塞等待连接结束（onClose / onError / stop() 都会 countDown）
                connectionClosedLatch.await();

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("Discord gateway 连接/运行异常: " + e);
            } finally {
                // 清理当前 ws
                WebSocket current = this.ws;
                this.ws = null;
                if (current != null) {
                    try {
                        current.abort();
                    } catch (Exception ignored) {
                    }
                }

                // 停止心跳（连接已断开）
                stopHeartbeat();
            }

            // 如果仍在运行，则等待 5 秒后重连
            if (isRunning()) {
                LOG.info("5 秒后重连 Discord gateway...");
                sleepSilently(5_000);
            }
        }
    }

    // =========================================================================
    // WebSocket Listener：解析 JSON + 分发 op/event
    // =========================================================================

    /**
     * Gateway Listener：接收文本帧并处理。
     *
     * <p>JDK WebSocket 是异步回调模式，这里模拟 Python 的 async for raw in ws。</p>
     */
    private final class GatewayListener implements WebSocket.Listener {

        /** 拼接分片文本帧（有些实现会分多次回调） */
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            LOG.info("Discord gateway WebSocket 已连接");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (!last) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            String raw = textBuffer.toString();
            textBuffer.setLength(0);

            // 处理消息放到 executor，避免阻塞 websocket 回调线程
            executor.execute(() -> handleGatewayRaw(raw));

            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("Discord gateway 关闭 status=" + statusCode + " reason=" + reason);
            CountDownLatch latch = connectionClosedLatch;
            if (latch != null) latch.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.warning("Discord gateway WebSocket 错误: " + error);
            CountDownLatch latch = connectionClosedLatch;
            if (latch != null) latch.countDown();
        }
    }

    /**
     * 处理 gateway 收到的原始 JSON 文本。
     */
    private void handleGatewayRaw(String raw) {
        Map<String, Object> data;
        try {
            data = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warning("Discord gateway JSON 无效: " + safeHead(raw, 100));
            return;
        }

        Integer op = asInt(data.get("op"), null);
        String eventType = asString(data.get("t"), null);
        Integer s = asInt(data.get("s"), null);
        Object dObj = data.get("d");

        if (s != null) {
            seq.set(s);
        }

        // HELLO(op=10)：启动 heartbeat，并 identify
        if (op != null && op == 10) {
            Map<String, Object> payload = castMap(dObj);
            long intervalMs = asLong(payload.get("heartbeat_interval"), 45_000L);
            startHeartbeat(intervalMs);
            identify();
            return;
        }

        // DISPATCH(op=0)
        if (op != null && op == 0) {
            if ("READY".equals(eventType)) {
                LOG.info("Discord gateway READY");
                return;
            }
            if ("MESSAGE_CREATE".equals(eventType)) {
                Map<String, Object> payload = castMap(dObj);
                handleMessageCreate(payload);
                return;
            }
            return;
        }

        // RECONNECT(op=7)
        if (op != null && op == 7) {
            LOG.info("Discord gateway 请求重连(op=7)");
            requestReconnect();
            return;
        }

        // INVALID_SESSION(op=9)
        if (op != null && op == 9) {
            LOG.warning("Discord gateway invalid session(op=9)，将重连");
            requestReconnect();
        }
    }

    /**
     * 请求重连：关闭当前连接，使 connectLoop 进入下一轮。
     */
    private void requestReconnect() {
        reconnectRequested = true;

        WebSocket current = this.ws;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
            } catch (Exception ignored) {
            }
        }

        CountDownLatch latch = connectionClosedLatch;
        if (latch != null) latch.countDown();
    }

    // =========================================================================
    // Identify / Heartbeat
    // =========================================================================

    /**
     * 发送 IDENTIFY(op=2)。
     */
    private void identify() {
        WebSocket current = this.ws;
        if (current == null) return;

        Map<String, Object> identify = new LinkedHashMap<>();
        identify.put("op", 2);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", cfg.getToken());
        d.put("intents", cfg.getIntents());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("os", "javaclawbot");
        props.put("browser", "javaclawbot");
        props.put("device", "javaclawbot");

        d.put("properties", props);
        identify.put("d", d);

        try {
            String json = MAPPER.writeValueAsString(identify);
            current.sendText(json, true);
        } catch (Exception e) {
            LOG.warning("Discord IDENTIFY 发送失败: " + e);
        }
    }

    /**
     * 启动/重启 heartbeat：按 interval_ms 周期发送 op=1。
     */
    private void startHeartbeat(long intervalMs) {
        stopHeartbeat();

        long interval = Math.max(5_000L, intervalMs); // 做一个下限保护
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!isRunning()) return;
            WebSocket current = ws;
            if (current == null) return;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("op", 1);

            long s = seq.get();
            // Python 允许 None，这里用 null 表示未知 seq
            payload.put("d", (s >= 0) ? s : null);

            try {
                String json = MAPPER.writeValueAsString(payload);
                current.sendText(json, true);
            } catch (Exception e) {
                LOG.warning("Discord heartbeat 发送失败: " + e);
                // 心跳失败通常意味着连接不稳定，这里触发一次重连
                requestReconnect();
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止 heartbeat。
     */
    private void stopHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        heartbeatFuture = null;
        if (f != null) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================================
    // MESSAGE_CREATE：下载附件 + 发布到 bus
    // =========================================================================

    /**
     * 处理 Discord MESSAGE_CREATE 事件。
     */
    private void handleMessageCreate(Map<String, Object> payload) {
        // author.bot 则忽略（防止自触发循环）
        Map<String, Object> author = castMap(payload.get("author"));
        if (truthy(author.get("bot"))) {
            return;
        }

        String senderId = asString(author.get("id"), "");
        String channelId = asString(payload.get("channel_id"), "");
        String content = asString(payload.get("content"), "");

        if (senderId.isBlank() || channelId.isBlank()) {
            return;
        }

        // 权限：对齐 Python is_allowed
        if (!isAllowed(senderId)) {
            return;
        }

        List<String> contentParts = new ArrayList<>();
        if (!content.isBlank()) {
            contentParts.add(content);
        }

        List<String> mediaPaths = new ArrayList<>();
        Path mediaDir = Path.of(System.getProperty("user.home", ""), ".javaclawbot", "media");

        // 处理 attachments
        List<Map<String, Object>> attachments = castListOfMaps(payload.get("attachments"));
        for (Map<String, Object> att : attachments) {
            String url = asString(att.get("url"), "");
            String filename = asString(att.get("filename"), "attachment");
            long size = asLong(att.get("size"), 0L);

            if (url.isBlank()) {
                continue;
            }

            if (size > 0 && size > MAX_ATTACHMENT_BYTES) {
                contentParts.add("[attachment: " + filename + " - too large]");
                continue;
            }

            try {
                Files.createDirectories(mediaDir);

                String attId = asString(att.get("id"), "file");
                String safeName = filename.replace("/", "_");
                Path filePath = mediaDir.resolve(attId + "_" + safeName);

                byte[] bytes = httpGetBytes(url);
                if (bytes == null) {
                    contentParts.add("[attachment: " + filename + " - download failed]");
                    continue;
                }

                Files.write(filePath, bytes);
                mediaPaths.add(filePath.toString());
                contentParts.add("[attachment: " + filePath + "]");

            } catch (Exception e) {
                LOG.warning("Discord 附件下载失败: " + e);
                contentParts.add("[attachment: " + filename + " - download failed]");
            }
        }

        // referenced_message.id（作为 reply_to，存 metadata）
        Map<String, Object> referenced = castMap(payload.get("referenced_message"));
        String replyTo = asString(referenced.get("id"), null);

        // 收到用户消息后，开启 typing 指示（对齐 Python）
        startTyping(channelId);

        // 发布到 bus
        String finalContent = joinNonEmpty(contentParts, "\n");
        if (finalContent.isBlank()) {
            finalContent = "[empty message]";
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("message_id", asString(payload.get("id"), ""));
        metadata.put("guild_id", payload.get("guild_id"));
        metadata.put("reply_to", replyTo);

        // BaseChannel.handleMessage 会做 allow_from 校验并 publishInbound
        handleMessage(senderId, channelId, finalContent, mediaPaths, metadata, null)
                .toCompletableFuture()
                .exceptionally(ex -> {
                    LOG.warning("Discord 入站消息发布失败: " + ex);
                    return null;
                });
    }

    // =========================================================================
    // Typing 指示：每 8 秒打一次 /typing
    // =========================================================================

    /**
     * 开始 typing：先停止同 channelId 旧任务，然后启动新任务。
     */
    private void startTyping(String channelId) {
        stopTyping(channelId);

        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(() -> {
            if (!isRunning()) return;

            try {
                String url = DISCORD_API_BASE + "/channels/" + channelId + "/typing";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bot " + cfg.getToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();

                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // 失败就停掉 typing（对齐 Python：异常时 return）
                stopTyping(channelId);
            }
        }, 0, 8, TimeUnit.SECONDS);

        typingFutures.put(channelId, f);
    }

    /**
     * 停止 typing。
     */
    private void stopTyping(String channelId) {
        ScheduledFuture<?> f = typingFutures.remove(channelId);
        if (f != null) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================================
    // 发送 REST payload（含 429 重试）
    // =========================================================================

    /**
     * 发送 Discord 消息 payload，遇到 rate limit(429) 自动等待 retry_after 重试（最多 3 次）。
     */
    private boolean sendPayloadWithRetry(String url, Map<String, String> headers, Map<String, Object> payload) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String body = MAPPER.writeValueAsString(payload);

                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

                for (Map.Entry<String, String> e : headers.entrySet()) {
                    b.header(e.getKey(), e.getValue());
                }

                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (resp.statusCode() == 429) {
                    // rate limit：解析 retry_after
                    double retryAfter = 1.0;
                    try {
                        Map<String, Object> data = MAPPER.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
                        retryAfter = asDouble(data.get("retry_after"), 1.0);
                    } catch (Exception ignored) {
                    }
                    LOG.warning("Discord rate limited，等待 " + retryAfter + " 秒后重试");
                    sleepSilently((long) (retryAfter * 1000));
                    continue;
                }

                if (resp.statusCode() >= 400) {
                    throw new IOException("HTTP " + resp.statusCode() + " body=" + safeHead(resp.body(), 300));
                }

                return true;

            } catch (Exception e) {
                if (attempt == 2) {
                    LOG.severe("Discord 发送消息失败: " + e);
                } else {
                    sleepSilently(1_000);
                }
            }
        }
        return false;
    }

    // =========================================================================
    // message split（对齐 Python _split_message）
    // =========================================================================

    /**
     * 将长文本拆分为不超过 maxLen 的片段，优先按换行，其次按空格。
     */
    private static List<String> splitMessage(String content, int maxLen) {
        if (content == null || content.isEmpty()) return List.of();
        if (content.length() <= maxLen) return List.of(content);

        List<String> chunks = new ArrayList<>();
        String remaining = content;

        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLen) {
                chunks.add(remaining);
                break;
            }

            String cut = remaining.substring(0, maxLen);
            int pos = cut.lastIndexOf('\n');
            if (pos <= 0) pos = cut.lastIndexOf(' ');
            if (pos <= 0) pos = maxLen;

            String part = remaining.substring(0, pos);
            chunks.add(part);

            remaining = remaining.substring(pos).stripLeading();
        }

        return chunks;
    }

    // =========================================================================
    // HTTP 下载
    // =========================================================================

    /**
     * GET 下载字节数组（附件下载用）。
     */
    private byte[] httpGetBytes(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // 工具：类型转换/安全处理
    // =========================================================================

    private static void sleepSilently(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** “真值”判断：null/false/0/空字符串/空集合 都视为 false */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private static String asString(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v);
        return (s != null) ? s : def;
    }

    private static Integer asInt(Object v, Integer def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static long asLong(Object v, long def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static double asDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castListOfMaps(Object v) {
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                out.add(castMap(o));
            }
            return out;
        }
        return List.of();
    }

    private static String joinNonEmpty(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String safeHead(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}