package channels;

import bus.MessageBus;
import bus.OutboundMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.lark.oapi.service.im.ImService;
import com.lark.oapi.ws.Client;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;


import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import config.Config;
import config.ConfigIO;
import config.ConfigSchema;
import config.channel.FeishuConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.Retryer;


import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feishu/Lark channel implementation:
 * - Inbound: Feishu official Java SDK WS long-connection (EventDispatcher + ws.Client)
 * - Outbound: HTTP OpenAPI (tenant_access_token) to send messages / upload / download resources
 */
public class FeishuChannel extends BaseChannel {

    public static final String CHANNEL_NAME = "feishu";

    // ---- media ext sets (match Python) ----
    private static final Set<String> IMAGE_EXTS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".ico", ".tiff", ".tif"
    );
    private static final Set<String> AUDIO_EXTS = Set.of(".opus");
    private static final Map<String, String> FILE_TYPE_MAP = Map.ofEntries(
            Map.entry(".opus", "opus"),
            Map.entry(".mp4", "mp4"),
            Map.entry(".pdf", "pdf"),
            Map.entry(".doc", "doc"),
            Map.entry(".docx", "doc"),
            Map.entry(".xls", "xls"),
            Map.entry(".xlsx", "xls"),
            Map.entry(".ppt", "ppt"),
            Map.entry(".pptx", "ppt")
    );

    // ---- Smart format detection patterns ----
    // Complex markdown patterns (code blocks, tables, headings)
    private static final Pattern COMPLEX_MD_RE = Pattern.compile(
        "```" +                                    // fenced code block
        "|^\\|.+\\|.*\\n\\s*\\|[-:\\s|]+\\|" +    // markdown table
        "|^#{1,6}\\s+",                            // headings
        Pattern.MULTILINE
    );

    // Simple markdown patterns (bold, italic, strikethrough)
    private static final Pattern SIMPLE_MD_RE = Pattern.compile(
        "\\*\\*.+?\\*\\*" +                        // **bold**
        "|__.+?__" +                               // __bold__
        "|(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)" +  // *italic*
        "|~~.+?~~",                                // ~~strikethrough~~
        Pattern.DOTALL
    );

    // Markdown link: [text](url)
    private static final Pattern MD_LINK_RE = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\)]+)\\)");

    // List items
    private static final Pattern LIST_RE = Pattern.compile("^[\\s]*[-*+]\\s+", Pattern.MULTILINE);
    private static final Pattern OLIST_RE = Pattern.compile("^[\\s]*\\d+\\.\\s+", Pattern.MULTILINE);

    // Markdown table pattern
    private static final Pattern TABLE_RE = Pattern.compile(
        "((?:^[ \\t]*\\|.+\\|[ \\t]*\\n)(?:^[ \\t]*\\|[-:\\s|]+\\|[ \\t]*\\n)(?:^[ \\t]*\\|.+\\|[ \\t]*\\n?)+)",
        Pattern.MULTILINE
    );

    // Heading pattern
    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    // Code block pattern
    private static final Pattern CODE_BLOCK_RE = Pattern.compile("(```[\\s\\S]*?```)", Pattern.MULTILINE);

    // Format thresholds
    private static final int TEXT_MAX_LEN = 200;
    private static final int POST_MAX_LEN = 2000;

    // ---- OpenAPI domain (CN default) ----
    private static final String OPENAPI_BASE = "https://open.feishu.cn/open-apis";
    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    // ---- dedupe cache ----
    private final LinkedHashMap<String, Boolean> processedMessageIds = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 1000;
        }
    };

    // ---- runtime ----
    private final Executor executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("feishu-channel-" + t.getId());
        return t;
    });

    private final ObjectMapper om = new ObjectMapper();
    private volatile Client wsClient;
    private volatile Thread wsThread;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .build();

    // tenant access token cache
    private volatile String tenantToken;
    private volatile long tenantTokenExpiryEpochSec = 0;

    // react emoji (optional)
    private volatile String reactEmoji = "THUMBSUP";

    public FeishuChannel(FeishuConfig config, MessageBus bus) {
        super(config, bus);
        this.name = CHANNEL_NAME;
        // optional: read reactEmoji from config if present
        String emoji = ConfigView.getString(config, "reactEmoji", "react_emoji", "reactEmojiType");
        if (emoji != null && !emoji.isBlank()) {
            this.reactEmoji = emoji.trim();
        }
    }

    public static void main(String[] args) throws IOException {
        Config config1 = ConfigIO.loadConfig(null);
        FeishuConfig feishu = config1.getChannels().getFeishu();
        /*FeishuChannel channel = new FeishuChannel(feishu, bus);
        channel.start();*/
        MessageBus bus = new MessageBus();
        ChannelManager cm = new ChannelManager(config1, bus);
        cm.startAll();
        BaseChannel feishu1 = cm.getChannel("feishu");
        OutboundMessage outboundMessage = new OutboundMessage();
        outboundMessage.setContent("测试");
        outboundMessage.setReplyTo("ou_ace496e9dae65620fba8a5c378f02c22");
        outboundMessage.setChatId("oc_be1dbff5089cf385b2b4584b870978b8");
        feishu1.send(outboundMessage);


        /*String appId = channel.mustGetConfig("appId", "app_id", "appID");
        String appSecret = channel.mustGetConfig("appSecret", "app_secret", "appSecretKey");

        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        log.error("接收到消息:\n 用户: {}", event.getEvent().getMessage().getContent());
                        channel.onP2MessageReceive(event);
                    }
                })
                // keep slot for customized events if you want later
                .onCustomizedEvent("unused", new CustomEventHandler() {
                    @Override
                    public void handle(EventReq event) throws Exception {
                        // no-op
                    }
                })
                .build();

        Client cli = new Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .build();
        cli.start();*/

        System.in.read();

    }

    @Override
    public CompletionStage<Void> start() {
        return CompletableFuture.runAsync(() -> {
            String appId = mustGetConfig("appId", "app_id", "appID");
            String appSecret = mustGetConfig("appSecret", "app_secret", "appSecretKey");

            // Feishu WS long-connection needs EventDispatcher builder params = "" , "" (per official doc)
            // We only handle P2MessageReceiveV1.
            EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) throws Exception {
                            onP2MessageReceive(event);
                        }
                    })
                    // keep slot for customized events if you want later
                    .onCustomizedEvent("unused", new CustomEventHandler() {
                        @Override
                        public void handle(EventReq event) throws Exception {
                            // no-op
                        }
                    })
                    .build();

            setRunning(true);

            // Run wsClient.start() in a dedicated thread (it blocks).
            /*wsThread = new Thread(() -> {
                while (isRunning()) {
                    try {
                        wsClient = new Client.Builder(appId, appSecret)
                                .eventHandler(dispatcher)
                                .build();
                        wsClient.start(); // blocking
                    } catch (Throwable t) {
                        logWarn("Feishu WS error: " + t.getMessage());
                    }

                    if (isRunning()) {
                        sleepSilently(5000);
                        logInfo("Reconnecting Feishu WS in 5 seconds...");
                    }
                }
            }, "feishu-ws-thread");
            wsThread.setDaemon(true);
            wsThread.start();
            */
            wsThread = new Thread(() -> {
                try {
                    wsClient = new Client.Builder(appId, appSecret)
                            .eventHandler(dispatcher)
                            .build();

                    wsClient.start(); // 官方自带自动重连，这里只调用一次
                } catch (Throwable t) {
                    logWarn("Feishu WS fatal error: " + t.getMessage());
                }
            }, "feishu-ws-thread");
            wsThread.setDaemon(true);
            wsThread.start();

            logInfo("Feishu channel started (WS long connection).");
        }, executor);
    }

    @Override
    public CompletionStage<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            setRunning(false);
            // SDK does not guarantee stop() API; exiting thread is enough.
            Thread t = wsThread;
            if (t != null) {
                t.interrupt();
            }
            wsThread = null;
            wsClient = null;
            logInfo("Feishu channel stopped.");
        }, executor);
    }

    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            try {
                String token = getTenantAccessToken();
                if (token == null) {
                    logWarn("Feishu send skipped: tenant token unavailable.");
                    return;
                }

                String receiveIdType = msg.getChatId() != null && msg.getChatId().startsWith("oc_")
                        ? "chat_id"
                        : "open_id";

                // 1) send media first (match python)
                List<String> media = msg.getMedia() != null ? msg.getMedia() : List.of();
                for (String filePath : media) {
                    if (filePath == null || filePath.isBlank()) continue;
                    Path p = Paths.get(filePath);
                    if (!Files.isRegularFile(p)) {
                        logWarn("Feishu media not found: " + filePath);
                        continue;
                    }

                    String ext = lowerExt(p.getFileName().toString());
                    if (IMAGE_EXTS.contains(ext)) {
                        String imageKey = uploadImage(token, p);
                        if (imageKey != null) {
                            sendMessage(token, receiveIdType, msg.getChatId(),
                                    "image", Jsons.DEFAULT.toJson(Map.of("image_key", imageKey)));
                        }
                    } else {
                        String fileKey = uploadFile(token, p);
                        if (fileKey != null) {
                            String msgType = AUDIO_EXTS.contains(ext) ? "audio" : "file";
                            sendMessage(token, receiveIdType, msg.getChatId(),
                                    msgType, Jsons.DEFAULT.toJson(Map.of("file_key", fileKey)));
                        }
                    }
                }

                // 2) send content with smart format detection
                if (msg.getContent() != null && !msg.getContent().trim().isEmpty()) {
                    String content = msg.getContent().trim();
                    String fmt = detectMsgFormat(content);
                    
                    if ("text".equals(fmt)) {
                        // Short plain text – send as simple text message
                        String textBody = "{\"text\":\"" + escapeJson(content) + "\"}";
                        sendMessage(token, receiveIdType, msg.getChatId(), "text", textBody);
                    } else if ("post".equals(fmt)) {
                        // Medium content with links – send as rich-text post
                        String postBody = markdownToPost(content);
                        sendMessage(token, receiveIdType, msg.getChatId(), "post", postBody);
                    } else {
                        // Complex / long content – send as interactive card
                        Map<String, Object> card = buildCard(content);
                        sendMessage(token, receiveIdType, msg.getChatId(), "interactive", Jsons.DEFAULT.toJson(card));
                    }
                }
            } catch (Exception e) {
                logWarn("Feishu send error: " + e.getMessage());
            }
        }, executor);
    }

    // =========================
    // Inbound handler (WS)
    // =========================

    private void onP2MessageReceive(P2MessageReceiveV1 wrapper) {
        // Convert event to JSON and parse with Jackson to avoid tight coupling to SDK model getters.
        String json = Jsons.DEFAULT.toJson(wrapper.getEvent());
        try {
            JsonNode root = om.readTree(json);

            // message_id
            String messageId = textAt(root, "/message/message_id");
            if (messageId == null || messageId.isBlank()) return;

            synchronized (processedMessageIds) {
                if (processedMessageIds.containsKey(messageId)) return;
                processedMessageIds.put(messageId, Boolean.TRUE);
            }

            // skip bot
            String senderType = textAt(root, "/sender/sender_type");
            if ("bot".equalsIgnoreCase(senderType)) return;

            String senderId = textAt(root, "/sender/sender_id/open_id");
            if (senderId == null || senderId.isBlank()) senderId = "unknown";

            String chatId = textAt(root, "/message/chat_id");
            if (chatId == null || chatId.isBlank()) return;

            String chatType = textAt(root, "/message/chat_type"); // group/p2p
            String msgType = textAt(root, "/message/message_type"); // text/post/image/audio/file/media/...

            // content json string
            String contentRaw = textAt(root, "/message/content");
            JsonNode contentJson;
            try {
                contentJson = contentRaw != null ? om.readTree(contentRaw) : om.createObjectNode();
            } catch (Exception e) {
                contentJson = om.createObjectNode();
            }

            // reaction (best-effort, optional)
            bestEffortReaction(messageId);

            List<String> contentParts = new ArrayList<>();
            List<String> mediaPaths = new ArrayList<>();

            if ("text".equals(msgType)) {
                String text = contentJson.has("text") ? contentJson.get("text").asText("") : "";
                if (!text.isBlank()) contentParts.add(text);

            } else if ("post".equals(msgType)) {
                PostExtract pe = extractPost(contentJson);
                if (!pe.text.isBlank()) contentParts.add(pe.text);
                for (String imgKey : pe.imageKeys) {
                    Downloaded d = downloadResourceToDisk("image", messageId, imgKey, null);
                    if (d.path != null) mediaPaths.add(d.path.toString());
                    contentParts.add(d.hintText);
                }

            } else if (Set.of("image", "audio", "file", "media").contains(msgType)) {
                String key = msgType.equals("image")
                        ? textOrNull(contentJson, "image_key")
                        : textOrNull(contentJson, "file_key");

                Downloaded d = downloadResourceToDisk(msgType, messageId, key, null);
                if (d.path != null) mediaPaths.add(d.path.toString());
                contentParts.add(d.hintText);

            } else if (Set.of("share_chat", "share_user", "interactive", "share_calendar_event", "system", "merge_forward").contains(msgType)) {
                contentParts.add(extractShareCardText(contentJson, msgType));

            } else {
                contentParts.add("[" + msgType + "]");
            }

            String finalContent = String.join("\n", contentParts).trim();
            if (finalContent.isEmpty() && mediaPaths.isEmpty()) return;

            // Reply routing: python chooses chat_id for group, else sender_id (open_id)
            String replyTo = "group".equalsIgnoreCase(chatType) ? chatId : senderId;

            // publish to bus (BaseChannel enforces allowFrom)
            log.info("飞书已收到消息:{}", finalContent);
            handleMessage(
                    senderId,
                    replyTo,
                    finalContent,
                    mediaPaths,
                    new HashMap<>(Map.of(
                            "message_id", messageId,
                            "chat_type", chatType,
                            "msg_type", msgType
                    ))
            );
        } catch (Exception e) {
            logWarn("Feishu inbound parse error: " + e.getMessage());
        }
    }

    // =========================
    // Token + OpenAPI HTTP
    // =========================

    private String getTenantAccessToken() {
        long now = Instant.now().getEpochSecond();
        if (tenantToken != null && now < tenantTokenExpiryEpochSec) {
            return tenantToken;
        }

        String appId = mustGetConfig("appId", "app_id", "appID");
        String appSecret = mustGetConfig("appSecret", "app_secret", "appSecretKey");

        // internal app token endpoint
        String url = OPENAPI_BASE + "/auth/v3/tenant_access_token/internal";
        String body = Jsons.DEFAULT.toJson(Map.of(
                "app_id", appId,
                "app_secret", appSecret
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                logWarn("Feishu token http error: status=" + resp.statusCode() + " body=" + trim500(resp.body()));
                return null;
            }

            JsonNode r = om.readTree(resp.body());
            int code = r.has("code") ? r.get("code").asInt(-1) : -1;
            if (code != 0) {
                logWarn("Feishu token api error: code=" + code + " msg=" + r.path("msg").asText(""));
                return null;
            }

            String t = r.path("tenant_access_token").asText(null);
            long expire = r.path("expire").asLong(3600);
            if (t == null || t.isBlank()) return null;

            tenantToken = t;
            tenantTokenExpiryEpochSec = now + expire - 60; // safe margin
            return tenantToken;

        } catch (Exception e) {
            logWarn("Feishu token error: " + e.getMessage());
            return null;
        }
    }

    /*private void sendMessage(String token, String receiveIdType, String receiveId, String msgType, String contentJsonString) {
        try {
            String url = OPENAPI_BASE + "/im/v1/messages?receive_id_type=" +
                    URLEncoder.encode(receiveIdType, StandardCharsets.UTF_8);

            String body = Jsons.DEFAULT.toJson(Map.of(
                    "receive_id", receiveId,
                    "msg_type", msgType,
                    "content", contentJsonString
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                logWarn("Feishu send http error: status=" + resp.statusCode() + " body=" + trim500(resp.body()));
                return;
            }

            JsonNode r = om.readTree(resp.body());
            int code = r.has("code") ? r.get("code").asInt(-1) : -1;
            if (code != 0) {
                logWarn("Feishu send api error: code=" + code + " msg=" + r.path("msg").asText(""));
            }

        } catch (Exception e) {
            logWarn("Feishu send exception: " + e.getMessage());
        }
    }*/
    private static final class FeishuHttpException extends RuntimeException {
        final int statusCode;
        final String bodyPreview;

        FeishuHttpException(int statusCode, String bodyPreview) {
            super("http status=" + statusCode);
            this.statusCode = statusCode;
            this.bodyPreview = bodyPreview;
        }
    }

    private static final class FeishuApiException extends RuntimeException {
        final int code;
        final String msg;

        FeishuApiException(int code, String msg) {
            super("api code=" + code + " msg=" + msg);
            this.code = code;
            this.msg = msg;
        }
    }
    private Retryer.RetryDecision decideFeishuRetry(Throwable t) {

        // 超时/IO：通常可重试
        if (t instanceof java.net.http.HttpTimeoutException) {
            return Retryer.RetryDecision.retry("timeout");
        }
        if (t instanceof java.io.IOException) {
            return Retryer.RetryDecision.retry("io");
        }

        // HTTP 状态码判定
        if (t instanceof FeishuHttpException he) {
            int sc = he.statusCode;

            // 429 限流、408 超时、5xx 服务端错误：重试
            if (sc == 429 || sc == 408 || (sc >= 500 && sc <= 599)) {
                return Retryer.RetryDecision.retry("http_" + sc);
            }
            // 其他 4xx：通常是参数/权限问题，不重试
            return Retryer.RetryDecision.stop("http_" + sc);
        }

        // API code：建议先保守，只对白名单重试（你以后拿到常见 code 再补）
        if (t instanceof FeishuApiException ae) {
            if (isRetryableFeishuCode(ae.code)) {
                return Retryer.RetryDecision.retry("api_code_" + ae.code);
            }
            return Retryer.RetryDecision.stop("api_code_" + ae.code);
        }

        return Retryer.RetryDecision.stop("unknown");
    }

    private boolean isRetryableFeishuCode(int code) {
        // 先保守：默认不重试
        // 你拿到飞书实际"服务繁忙/限流"等 code 后再加白名单
        return false;
    }

    private void sendMessage(String token, String receiveIdType, String receiveId, String msgType, String contentJsonString) {
        String opName = "Feishu sendMessage(" + msgType + ", receiveIdType=" + receiveIdType + ")";

        try {
            // 使用 BaseChannel.withRetry：重试日志由框架统一输出
            withRetry(opName, () -> {
                doSendOnce(token, receiveIdType, receiveId, msgType, contentJsonString);
                return null;
            }, this::decideFeishuRetry);

        } catch (Exception finalEx) {
            // 三次后, 再次冗余一次,还是失败,记录最终异常
            String accessToken = getTenantAccessToken();
            try {
                doSendOnce(accessToken, receiveIdType, receiveId, msgType, contentJsonString);
            } catch (Exception e) {
                logWarn("Feishu send exception: " + finalEx.getMessage());
            }
        }
    }

    private void doSendOnce(String token, String receiveIdType, String receiveId, String msgType, String contentJsonString) throws Exception {

        String url = OPENAPI_BASE + "/im/v1/messages?receive_id_type=" +
                URLEncoder.encode(receiveIdType, StandardCharsets.UTF_8);

        String body = Jsons.DEFAULT.toJson(Map.of(
                "receive_id", receiveId,
                "msg_type", msgType,
                "content", contentJsonString
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() / 100 != 2) {
            // 让 retry 框架负责记录重试次数日志
            throw new FeishuHttpException(resp.statusCode(), trim500(resp.body()));
        }

        JsonNode r = om.readTree(resp.body());
        int code = r.has("code") ? r.get("code").asInt(-1) : -1;
        if (code != 0) {
            throw new FeishuApiException(code, r.path("msg").asText(""));
        }
    }

    private String uploadImage(String token, Path file) {
        // POST /im/v1/images  multipart: image_type=message, image=@file
        return uploadMultipart(token, "/im/v1/images",
                Map.of("image_type", "message"),
                "image",
                file,
                "image_key");
    }

    private String uploadFile(String token, Path file) {
        String ext = lowerExt(file.getFileName().toString());
        String fileType = FILE_TYPE_MAP.getOrDefault(ext, "stream");
        // POST /im/v1/files multipart: file_type=?, file_name=?, file=@file
        return uploadMultipart(token, "/im/v1/files",
                Map.of("file_type", fileType, "file_name", file.getFileName().toString()),
                "file",
                file,
                "file_key");
    }

    private String uploadMultipart(
            String token,
            String path,
            Map<String, String> fields,
            String fileFieldName,
            Path file,
            String expectKeyName
    ) {
        String boundary = "----javaclawbot-" + UUID.randomUUID();
        try {
            byte[] fileBytes = Files.readAllBytes(file);

            List<byte[]> parts = new ArrayList<>();

            for (Map.Entry<String, String> e : fields.entrySet()) {
                parts.add(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                parts.add(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                parts.add((e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }

            parts.add(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(("Content-Disposition: form-data; name=\"" + fileFieldName + "\"; filename=\"" +
                    file.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(("Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(fileBytes);
            parts.add("\r\n".getBytes(StandardCharsets.UTF_8));

            parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            int total = parts.stream().mapToInt(a -> a.length).sum();
            byte[] body = new byte[total];
            int off = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, body, off, p.length);
                off += p.length;
            }

            String url = OPENAPI_BASE + path;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                logWarn("Feishu upload http error: status=" + resp.statusCode() + " body=" + trim500(resp.body()));
                return null;
            }

            JsonNode r = om.readTree(resp.body());
            int code = r.has("code") ? r.get("code").asInt(-1) : -1;
            if (code != 0) {
                logWarn("Feishu upload api error: code=" + code + " msg=" + r.path("msg").asText(""));
                return null;
            }
            return r.path("data").path(expectKeyName).asText(null);

        } catch (Exception e) {
            logWarn("Feishu upload error: " + e.getMessage());
            return null;
        }
    }

    // =========================
    // Download resources (inbound media)
    // =========================

    private static class Downloaded {
        final Path path;       // may be null
        final String hintText; // e.g. [image: xxx.jpg] / [file: download failed]
        Downloaded(Path path, String hintText) {
            this.path = path;
            this.hintText = hintText;
        }
    }

    private Downloaded downloadResourceToDisk(String msgType, String messageId, String fileKey, String fallbackName) {
        if (fileKey == null || fileKey.isBlank() || messageId == null || messageId.isBlank()) {
            return new Downloaded(null, "[" + msgType + ": download failed]");
        }

        String token = getTenantAccessToken();
        if (token == null) {
            return new Downloaded(null, "[" + msgType + ": download failed]");
        }

        String typeParam = msgType; // image/audio/file/media
        String url = OPENAPI_BASE + "/im/v1/messages/" + urlEncode(messageId)
                + "/resources/" + urlEncode(fileKey)
                + "?type=" + urlEncode(typeParam);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                return new Downloaded(null, "[" + msgType + ": download failed]");
            }

            String filename = extractFilename(resp.headers().firstValue("content-disposition").orElse(""));
            if (filename == null || filename.isBlank()) {
                filename = fallbackName != null ? fallbackName : (fileKey.substring(0, Math.min(16, fileKey.length())) + guessExt(msgType));
            }

            Path mediaDir = Paths.get(System.getProperty("user.home"), ".javaclawbot", "media");
            Files.createDirectories(mediaDir);

            Path out = mediaDir.resolve(filename);
            try (InputStream in = resp.body()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }

            return new Downloaded(out, "[" + msgType + ": " + filename + "]");
        } catch (Exception e) {
            return new Downloaded(null, "[" + msgType + ": download failed]");
        }
    }

    private static String extractFilename(String contentDisposition) {
        // Content-Disposition: attachment; filename="xxx"
        if (contentDisposition == null) return null;
        Pattern p = Pattern.compile("filename\\*=UTF-8''([^;]+)|filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(contentDisposition);
        if (!m.find()) return null;
        String v = m.group(1) != null ? m.group(1) : m.group(2);
        if (v == null) return null;
        try {
            return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return v;
        }
    }

    private static String guessExt(String msgType) {
        return switch (msgType) {
            case "image" -> ".jpg";
            case "audio" -> ".opus";
            case "media" -> ".mp4";
            default -> ".bin";
        };
    }

    // =========================
    // Smart format detection
    // =========================

    /**
     * Determine the optimal Feishu message format for content.
     * @return "text", "post", or "interactive"
     */
    private String detectMsgFormat(String content) {
        String stripped = content.trim();
        
        // Complex markdown (code blocks, tables, headings) → always card
        if (COMPLEX_MD_RE.matcher(stripped).find()) {
            return "interactive";
        }
        
        // Long content → card
        if (stripped.length() > POST_MAX_LEN) {
            return "interactive";
        }
        
        // Has bold/italic/strikethrough → card
        if (SIMPLE_MD_RE.matcher(stripped).find()) {
            return "interactive";
        }
        
        // Has list items → card
        if (LIST_RE.matcher(stripped).find() || OLIST_RE.matcher(stripped).find()) {
            return "interactive";
        }
        
        // Has links → post format
        if (MD_LINK_RE.matcher(stripped).find()) {
            return "post";
        }
        
        // Short plain text → text format
        if (stripped.length() <= TEXT_MAX_LEN) {
            return "text";
        }
        
        // Medium plain text → post format
        return "post";
    }

    /**
     * Convert markdown content to Feishu post message JSON.
     * Handles links [text](url) as a tags.
     */
    private String markdownToPost(String content) {
        List<List<Map<String, Object>>> paragraphs = new ArrayList<>();
        
        for (String line : content.trim().split("\n")) {
            List<Map<String, Object>> elements = new ArrayList<>();
            int lastEnd = 0;
            java.util.regex.Matcher m = MD_LINK_RE.matcher(line);
            
            while (m.find()) {
                // Text before this link
                if (m.start() > lastEnd) {
                    String before = line.substring(lastEnd, m.start());
                    elements.add(Map.of("tag", "text", "text", before));
                }
                // The link
                elements.add(Map.of(
                    "tag", "a",
                    "text", m.group(1),
                    "href", m.group(2)
                ));
                lastEnd = m.end();
            }
            
            // Remaining text after last link
            if (lastEnd < line.length()) {
                elements.add(Map.of("tag", "text", "text", line.substring(lastEnd)));
            }
            
            // Empty line → empty paragraph for spacing
            if (elements.isEmpty()) {
                elements.add(Map.of("tag", "text", "text", ""));
            }
            
            paragraphs.add(elements);
        }
        
        Map<String, Object> postBody = Map.of(
            "zh_cn", Map.of("content", paragraphs)
        );
        
        try {
            return om.writeValueAsString(postBody);
        } catch (Exception e) {
            return "{\"zh_cn\":{\"content\":[]}}";
        }
    }

    // =========================
    // Card building (interactive)
    // =========================

    private Map<String, Object> buildCard(String content) {
        // Minimal compatible with Feishu interactive message:
        // {"config":{"wide_screen_mode":true},"elements":[{"tag":"markdown","content":"..."}]}
        // If you later want table parsing like python, you can port it here.
        return new HashMap<>(Map.of(
                "config", Map.of("wide_screen_mode", true),
                "elements", List.of(Map.of("tag", "markdown", "content", content))
        ));
    }

    // =========================
    // Post extraction (rich text)
    // =========================

    private static class PostExtract {
        final String text;
        final List<String> imageKeys;
        PostExtract(String text, List<String> imageKeys) {
            this.text = text;
            this.imageKeys = imageKeys;
        }
    }

    private PostExtract extractPost(JsonNode contentJson) {
        // Supports:
        // 1) direct: {"title":"...","content":[...]}
        // 2) localized: {"zh_cn":{...}} / {"en_us":{...}} / {"ja_jp":{...}}
        List<String> langs = List.of("zh_cn", "en_us", "ja_jp");
        if (contentJson.has("content")) {
            return extractPostFromLang(contentJson);
        }
        for (String k : langs) {
            if (contentJson.has(k) && contentJson.get(k).isObject()) {
                PostExtract pe = extractPostFromLang(contentJson.get(k));
                if (!pe.text.isBlank() || !pe.imageKeys.isEmpty()) return pe;
            }
        }
        return new PostExtract("", List.of());
    }

    private PostExtract extractPostFromLang(JsonNode langNode) {
        StringBuilder sb = new StringBuilder();
        List<String> imageKeys = new ArrayList<>();
        if (langNode.has("title")) {
            String title = langNode.get("title").asText("");
            if (!title.isBlank()) sb.append(title).append(" ");
        }
        JsonNode blocks = langNode.get("content");
        if (blocks != null && blocks.isArray()) {
            for (JsonNode block : blocks) {
                if (!block.isArray()) continue;
                for (JsonNode el : block) {
                    String tag = el.path("tag").asText("");
                    switch (tag) {
                        case "text" -> sb.append(el.path("text").asText("")).append(" ");
                        case "a" -> sb.append(el.path("text").asText("")).append(" ");
                        case "at" -> sb.append("@").append(el.path("user_name").asText("user")).append(" ");
                        case "img" -> {
                            String key = el.path("image_key").asText(null);
                            if (key != null && !key.isBlank()) imageKeys.add(key);
                        }
                        default -> { /* ignore */ }
                    }
                }
            }
        }
        return new PostExtract(sb.toString().trim(), imageKeys);
    }

    // =========================
    // Share / interactive placeholders
    // =========================

    private String extractShareCardText(JsonNode contentJson, String msgType) {
        return switch (msgType) {
            case "share_chat" -> "[shared chat: " + contentJson.path("chat_id").asText("") + "]";
            case "share_user" -> "[shared user: " + contentJson.path("user_id").asText("") + "]";
            case "share_calendar_event" -> "[shared calendar event: " + contentJson.path("event_key").asText("") + "]";
            case "interactive" -> "[interactive card]";
            case "system" -> "[system message]";
            case "merge_forward" -> "[merged forward messages]";
            default -> "[" + msgType + "]";
        };
    }

    // =========================
    // Reaction (optional best-effort)
    // =========================

    private void bestEffortReaction(String messageId) {
        // Add reaction emoji to message (best-effort, non-blocking)
        String emojiType = this.reactEmoji;
        if (emojiType == null || emojiType.isBlank()) {
            emojiType = "THUMBSUP";  // default
        }
        
        try {
            String token = getTenantAccessToken();
            if (token == null) {
                logWarn("Feishu reaction skipped: tenant token unavailable.");
                return;
            }
            
            // Use HTTP API to add reaction
            String url = OPENAPI_BASE + "/im/v1/messages/" + messageId + "/reactions";
            String body = om.writeValueAsString(Map.of(
                "reaction_type", Map.of("emoji_type", emojiType)
            ));
            
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                logWarn("Failed to add reaction: status=" + resp.statusCode() + " body=" + trim500(resp.body()));
            } else {
                JsonNode r = om.readTree(resp.body());
                int code = r.has("code") ? r.get("code").asInt(-1) : -1;
                if (code != 0) {
                    logWarn("Failed to add reaction: code=" + code + " msg=" + r.path("msg").asText(""));
                } else {
                    logDebug("Added " + emojiType + " reaction to message " + messageId);
                }
            }
        } catch (Exception e) {
            logWarn("Error adding reaction: " + e.getMessage());
        }
    }

    // =========================
    // Config reading (Object / Map / reflection)
    // =========================

    private String mustGetConfig(String... keys) {
        String v = ConfigView.getString(config, keys);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("FeishuChannel missing config: " + Arrays.toString(keys));
        }
        return v.trim();
    }

    private static final class ConfigView {
        static String getString(Object cfg, String... keys) {
            if (cfg == null) return null;

            // Map
            if (cfg instanceof Map<?, ?> map) {
                for (String k : keys) {
                    Object v = map.get(k);
                    if (v == null) {
                        // also try snake/camel variants
                        v = map.get(k.replace("_", ""));
                    }
                    if (v != null) return String.valueOf(v);
                }
                return null;
            }

            // reflection: getter or field
            for (String k : keys) {
                String m1 = "get" + upperFirst(k);
                String m2 = k; // allow direct method name
                for (String mn : List.of(m1, m2)) {
                    try {
                        var m = cfg.getClass().getMethod(mn);
                        Object v = m.invoke(cfg);
                        if (v != null) return String.valueOf(v);
                    } catch (Exception ignore) {}
                }
                try {
                    var f = cfg.getClass().getDeclaredField(k);
                    f.setAccessible(true);
                    Object v = f.get(cfg);
                    if (v != null) return String.valueOf(v);
                } catch (Exception ignore) {}
            }
            return null;
        }

        private static String upperFirst(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    // =========================
    // Helpers
    // =========================

    private static void sleepSilently(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String lowerExt(String filename) {
        int i = filename.lastIndexOf('.');
        if (i < 0) return "";
        return filename.substring(i).toLowerCase(Locale.ROOT);
    }

    private static String textOrNull(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String textAt(JsonNode root, String jsonPointer) {
        if (root == null) return null;
        JsonNode n = root.at(jsonPointer);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trim500(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}