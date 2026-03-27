package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.channel.WhatsAppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WhatsApp channel implementation using Node.js bridge (WebSocket).
 *
 * Bridge side uses @whiskeysockets/baileys (WhatsApp Web protocol).
 * Java side connects via WebSocket and exchanges JSON frames.
 *
 * Python 版本语义对齐：
 * - start(): connect loop + auto reconnect (5s)
 * - optional auth token after connect
 * - handle types: message/status/qr/error
 * - message dedup: LRU 1000
 * - send(): {"type":"send","to": chatId, "text": content}
 */
public class WhatsAppChannel extends BaseChannel {

    public static final String CHANNEL_NAME = "whatsapp";

    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int DEDUP_MAX = 1000;

    private final WhatsAppConfig waConfig;

    private final ObjectMapper om = new ObjectMapper();

    private final ExecutorService worker;
    private final ScheduledExecutorService scheduler;

    private volatile WebSocket ws;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** LRU 去重：message_id -> null */
    private final Map<String, Boolean> processedIds = new LinkedHashMap<>(DEDUP_MAX * 2, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUP_MAX;
        }
    };

    /** 连接循环任务（便于 stop 时取消） */
    private volatile Future<?> connectLoopFuture;

    public WhatsAppChannel(WhatsAppConfig config, MessageBus bus) {
        super(config, bus);
        this.name = CHANNEL_NAME;
        this.waConfig = Objects.requireNonNull(config, "WhatsAppConfig 不能为空");

        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wa-bridge-loop");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "wa-bridge-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (isBlank(waConfig.getBridgeUrl())) {
                logSevere("WhatsApp bridge_url 未配置");
                return;
            }

            setRunning(true);
            logInfo("Connecting to WhatsApp bridge at " + waConfig.getBridgeUrl() + "...");

            // 启动连接循环
            connectLoopFuture = worker.submit(this::connectLoop);
        });
    }

    @Override
    public CompletionStage<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            setRunning(false);
            connected.set(false);

            // 关闭 ws
            WebSocket s = this.ws;
            this.ws = null;
            if (s != null) {
                try {
                    s.sendClose(WebSocket.NORMAL_CLOSURE, "stopped").join();
                } catch (Exception ignored) {}
            }

            // 取消连接循环任务
            Future<?> f = connectLoopFuture;
            connectLoopFuture = null;
            if (f != null) f.cancel(true);

            logInfo("WhatsApp channel stopped");
        });
    }

    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            WebSocket s = this.ws;
            if (s == null || !connected.get()) {
                logWarn("WhatsApp bridge not connected");
                return;
            }

            try {
                ObjectNode payload = om.createObjectNode();
                payload.put("type", "send");
                payload.put("to", msg.getChatId());
                payload.put("text", msg.getContent() == null ? "" : msg.getContent());

                String json = om.writeValueAsString(payload);
                s.sendText(json, true);
            } catch (Exception e) {
                logSevere("Error sending WhatsApp message: " + e.getMessage());
            }
        });
    }

    // =========================================================
    // Connect loop + listener
    // =========================================================

    private void connectLoop() {
        while (isRunning()) {
            try {
                connectOnce();
                // connectOnce 会阻塞等待关闭（通过 listener 的 onClose / onError 触发完成）
            } catch (CancellationException ce) {
                break;
            } catch (Exception e) {
                connected.set(false);
                this.ws = null;
                logWarn("WhatsApp bridge connection error: " + e.getMessage());
            }

            if (isRunning()) {
                logInfo("Reconnecting in " + RECONNECT_DELAY_SECONDS + " seconds...");
                sleepSeconds(RECONNECT_DELAY_SECONDS);
            }
        }
    }

    /**
     * 建立一次连接，直到断开为止。
     */
    private void connectOnce() throws Exception {
        String bridgeUrl = waConfig.getBridgeUrl().trim();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        CompletableFuture<Void> closedFuture = new CompletableFuture<>();

        WebSocket.Listener listener = new WebSocket.Listener() {

            private final StringBuilder partial = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                WebSocket.Listener.super.onOpen(webSocket);

                ws = webSocket;
                connected.set(true);
                logInfo("Connected to WhatsApp bridge");

                // Send auth token if configured
                if (!isBlank(waConfig.getBridgeToken())) {
                    try {
                        ObjectNode auth = om.createObjectNode();
                        auth.put("type", "auth");
                        auth.put("token", waConfig.getBridgeToken());
                        webSocket.sendText(om.writeValueAsString(auth), true);
                    } catch (Exception e) {
                        logWarn("Failed to send auth token: " + e.getMessage());
                    }
                }

                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                try {
                    partial.append(data);
                    if (last) {
                        String frame = partial.toString();
                        partial.setLength(0);

                        // 处理 JSON 消息
                        handleBridgeMessage(frame);
                    }
                } catch (Exception e) {
                    logWarn("Error handling bridge message: " + e.getMessage());
                } finally {
                    webSocket.request(1);
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                connected.set(false);
                ws = null;
                logWarn("WhatsApp bridge closed: " + statusCode + " " + reason);
                closedFuture.complete(null);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                connected.set(false);
                ws = null;
                logWarn("WhatsApp bridge error: " + (error == null ? "unknown" : error.getMessage()));
                closedFuture.complete(null);
            }
        };

        WebSocket webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(bridgeUrl), listener)
                .join();

        this.ws = webSocket;

        // 阻塞直到连接关闭
        closedFuture.join();
    }

    // =========================================================
    // Message handling (bridge -> bus)
    // =========================================================

    private void handleBridgeMessage(String raw) {
        JsonNode data;
        try {
            data = om.readTree(raw);
        } catch (JsonProcessingException e) {
            logWarn("Invalid JSON from bridge: " + (raw == null ? "" : raw.substring(0, Math.min(100, raw.length()))));
            return;
        }

        String type = text(data, "type");

        if ("message".equals(type)) {
            // Deprecated phone-number style: <phone>@s.whatspp.net
            String pn = text(data, "pn");
            // New LID style: sender
            String sender = text(data, "sender");
            String content = text(data, "content");
            String messageId = text(data, "id");

            // dedup
            if (!isBlank(messageId)) {
                synchronized (processedIds) {
                    if (processedIds.containsKey(messageId)) return;
                    processedIds.put(messageId, Boolean.TRUE);
                }
            }

            // Extract chat id
            String userId = !isBlank(pn) ? pn : sender;
            String senderId = userId;
            int at = userId.indexOf('@');
            if (at >= 0) senderId = userId.substring(0, at);

            logInfo("Sender " + sender);

            // Voice placeholder (桥接暂不支持下载)
            if ("[Voice Message]".equals(content)) {
                logInfo("Voice message received from " + senderId + ", but direct download from bridge is not yet supported.");
                content = "[Voice Message: Transcription not available for WhatsApp yet]";
            }

            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("message_id", messageId);
            metadata.put("timestamp", data.has("timestamp") ? data.get("timestamp").asText(null) : null);
            metadata.put("is_group", data.has("isGroup") && data.get("isGroup").asBoolean(false));

            // 注意：Python 用 sender(完整 LID) 作为 chat_id 用于回复
            // 这里保持一致：chat_id = sender（而不是 senderId）
            this.handleMessage(
                    senderId,
                    sender,
                    content,
                    null,
                    metadata,
                    null
            );
            return;
        }

        if ("status".equals(type)) {
            String status = text(data, "status");
            logInfo("WhatsApp status: " + status);

            if ("connected".equalsIgnoreCase(status)) {
                connected.set(true);
            } else if ("disconnected".equalsIgnoreCase(status)) {
                connected.set(false);
            }
            return;
        }

        if ("qr".equals(type)) {
            logInfo("Scan QR code in the bridge terminal to connect WhatsApp");
            return;
        }

        if ("error".equals(type)) {
            String err = text(data, "error");
            logSevere("WhatsApp bridge error: " + err);
            return;
        }

        // 未知类型
        logDebug("WhatsApp bridge unknown type: " + type);
    }

    private static String text(JsonNode n, String field) {
        if (n == null || field == null) return "";
        JsonNode v = n.get(field);
        return (v != null && !v.isNull()) ? v.asText("") : "";
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void sleepSeconds(int s) {
        try {
            TimeUnit.SECONDS.sleep(s);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}