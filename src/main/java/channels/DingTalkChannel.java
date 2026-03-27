package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.channel.DingTalkConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * 钉钉（DingTalk / DingDing）渠道实现（发送侧完整实现，接收侧留 TODO）。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>Python 版本使用 dingtalk-stream SDK（Stream Mode）来接收消息。</li>
 *   <li>你选择不在意接收侧 SDK，因此本类的“接收消息”部分保留 TODO 占位，保证工程可编译。</li>
 *   <li>发送侧使用钉钉 HTTP API：获取 accessToken、上传媒体、batchSend 发消息。</li>
 * </ul>
 */
public class DingTalkChannel extends BaseChannel {

    private static final Logger LOG = Logger.getLogger(DingTalkChannel.class.getName());

    /** JSON 序列化/反序列化工具（项目里已使用 Jackson 注解，通常会带 jackson-databind） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 渠道名称（用于 message.bus 的 channel 字段） */
    public DingTalkChannelName nameHolder = new DingTalkChannelName();
    // 为了保持与你 BaseChannel 的设计一致：直接覆盖父类 name 字段
    {
        this.name = "dingtalk";
    }

    /** 允许的图片扩展名集合 */
    private static final Set<String> IMAGE_EXTS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp");
    /** 允许的音频扩展名集合 */
    private static final Set<String> AUDIO_EXTS = Set.of(".amr", ".mp3", ".wav", ".ogg", ".m4a", ".aac");
    /** 允许的视频扩展名集合 */
    private static final Set<String> VIDEO_EXTS = Set.of(".mp4", ".mov", ".avi", ".mkv", ".webm");

    /** 钉钉配置（来自 DingTalkConfig） */
    private final DingTalkConfig cfg;

    /** HTTP 客户端（JDK17 自带） */
    private final HttpClient http;

    /** access token（发送消息必需） */
    private volatile String accessToken;

    /** token 过期时间（epoch seconds），提前 60 秒过期以减少边界问题 */
    private volatile long tokenExpiryEpochSeconds = 0;

    /** 保存后台任务引用，避免被 GC 丢掉；stop 时会取消 */
    private final Set<Future<?>> backgroundTasks = ConcurrentHashMap.newKeySet();

    /** 执行器：用于异步 HTTP、文件 IO、后台循环等 */
    private final ExecutorService executor;

    /**
     * 构造器：ChannelManager 通过反射创建，参数应可匹配 (DingTalkConfig, MessageBus)。
     */
    public DingTalkChannel(DingTalkConfig config, MessageBus bus) {
        super(config, bus);
        this.cfg = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(Redirect.NORMAL)
                .build();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("dingtalk-channel-" + t.getId());
            return t;
        });
    }

    /**
     * 启动渠道。
     *
     * <p>由于你不启用 dingtalk-stream SDK，接收侧暂时不实现。</p>
     * <p>这里仍保持“运行标记 + 生命周期”一致，便于后续接入 SDK。</p>
     */
    @Override
    public CompletionStage<Void> start() {
        setRunning(true);

        // TODO：接入钉钉 Stream SDK（WebSocket/回调）后，在这里启动并持续监听消息，
        // 收到消息后调用 onMessage(content, senderId, senderName)。
        LOG.warning("DingTalk 接收侧（Stream Mode）未接入 SDK：当前仅支持发送消息。");

        // 这里不做阻塞循环，直接返回完成态
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 停止渠道：关闭运行标记，并取消后台任务。
     */
    @Override
    public CompletionStage<Void> stop() {
        setRunning(false);

        // 取消后台任务
        for (Future<?> f : backgroundTasks) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
        backgroundTasks.clear();

        // 关闭执行器（可选：若你希望进程能快速退出）
        executor.shutdownNow();

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 发送出站消息：文本 + 附件（媒体）。
     */
    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            try {
                String token = getAccessTokenBlocking();
                if (token == null || token.isBlank()) {
                    return;
                }

                // 先发文本（markdown）
                String content = (msg.getContent() != null) ? msg.getContent().trim() : "";
                if (!content.isEmpty()) {
                    boolean ok = sendMarkdownTextBlocking(token, msg.getChatId(), content);
                    if (!ok) {
                        LOG.warning("DingTalk 文本发送失败 chatId=" + msg.getChatId());
                    }
                }

                // 再发媒体
                List<String> media = (msg.getMedia() != null) ? msg.getMedia() : List.of();
                for (String ref : media) {
                    boolean ok = sendMediaRefBlocking(token, msg.getChatId(), ref);
                    if (!ok) {
                        // 发送可见兜底，便于用户感知失败
                        String filename = guessFilename(ref, guessUploadType(ref));
                        sendMarkdownTextBlocking(token, msg.getChatId(), "[Attachment send failed: " + filename + "]");
                    }
                }
            } catch (Exception e) {
                LOG.severe("DingTalk send 异常: " + e);
            }
        }, executor);
    }

    // =========================================================================
    // 接收侧：留一个入口，未来接入 SDK 后直接调用
    // =========================================================================

    /**
     * 接收侧入口：当钉钉收到消息时可调用本方法。
     *
     * <p>注意：当前 start() 未接 SDK，因此不会自动触发。</p>
     */
    public CompletionStage<Void> onMessage(String content, String senderId, String senderName) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sender_name", senderName);
            metadata.put("platform", "dingtalk");

            // 约定：私聊时 chatId == senderId（与 Python 行为一致）
            return handleMessage(
                    senderId,
                    senderId,
                    String.valueOf(content),
                    null,
                    metadata,
                    null
            );
        } catch (Exception e) {
            LOG.severe("DingTalk onMessage 发布到 bus 异常: " + e);
            return CompletableFuture.completedFuture(null);
        }
    }

    // =========================================================================
    // AccessToken
    // =========================================================================

    /**
     * 获取或刷新 access token（阻塞式），内部会做缓存与过期判断。
     */
    private String getAccessTokenBlocking() {
        long now = System.currentTimeMillis() / 1000;

        // 未过期则直接返回
        String cached = this.accessToken;
        if (cached != null && now < tokenExpiryEpochSeconds) {
            return cached;
        }

        // 配置检查
        if (cfg == null || cfg.getClientId() == null || cfg.getClientId().isBlank()
                || cfg.getClientSecret() == null || cfg.getClientSecret().isBlank()) {
            LOG.severe("DingTalk clientId/clientSecret 未配置，无法获取 accessToken");
            return null;
        }

        // 调用钉钉获取 token 接口
        String url = "https://api.dingtalk.com/v1.0/oauth2/accessToken";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appKey", cfg.getClientId());
        payload.put("appSecret", cfg.getClientSecret());

        try {
            String body = MAPPER.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                LOG.severe("获取 DingTalk accessToken 失败 status=" + resp.statusCode() + " body=" + safeHead(resp.body(), 500));
                return null;
            }

            Map<String, Object> data = MAPPER.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
            Object tok = data.get("accessToken");
            if (tok == null) {
                LOG.severe("获取 DingTalk accessToken 返回缺少 accessToken 字段 body=" + safeHead(resp.body(), 500));
                return null;
            }

            long expireIn = asLong(data.get("expireIn"), 7200);
            // 提前 60 秒过期，降低临界失败概率
            this.accessToken = String.valueOf(tok);
            this.tokenExpiryEpochSeconds = now + expireIn - 60;

            return this.accessToken;

        } catch (Exception e) {
            LOG.severe("获取 DingTalk accessToken 异常: " + e);
            return null;
        }
    }

    // =========================================================================
    // 发送：batchSend
    // =========================================================================

    /**
     * 发送 Markdown 文本（使用 sampleMarkdown）。
     */
    private boolean sendMarkdownTextBlocking(String token, String chatId, String content) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("text", content);
        param.put("title", "javaclawbot Reply");
        return sendBatchMessageBlocking(token, chatId, "sampleMarkdown", param);
    }

    /**
     * 发送批量消息（实际是给一个 userIds 列表发送，这里只有一个 chatId）。
     */
    private boolean sendBatchMessageBlocking(String token, String chatId, String msgKey, Map<String, Object> msgParam) {
        String url = "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend";

        // 钉钉此接口要求 msgParam 作为 JSON 字符串字段传入
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robotCode", cfg.getClientId());
        payload.put("userIds", List.of(chatId));
        payload.put("msgKey", msgKey);

        try {
            String msgParamJson = MAPPER.writeValueAsString(msgParam);
            payload.put("msgParam", msgParamJson);

            String body = MAPPER.writeValueAsString(payload);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("content-type", "application/json")
                    .header("x-acs-dingtalk-access-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String respBody = resp.body();

            if (resp.statusCode() != 200) {
                LOG.severe("DingTalk send failed msgKey=" + msgKey + " status=" + resp.statusCode()
                        + " body=" + safeHead(respBody, 500));
                return false;
            }

            // 返回体有时包含 errcode，有时没有；两种都视为成功
            Map<String, Object> data;
            try {
                data = MAPPER.readValue(respBody, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                data = Map.of();
            }

            Object errcode = data.get("errcode");
            if (errcode != null && asLong(errcode, 0) != 0) {
                LOG.severe("DingTalk send api error msgKey=" + msgKey + " errcode=" + errcode
                        + " body=" + safeHead(respBody, 500));
                return false;
            }

            return true;

        } catch (Exception e) {
            LOG.severe("DingTalk send 异常 msgKey=" + msgKey + " err=" + e);
            return false;
        }
    }

    // =========================================================================
    // 媒体：读取 / 上传 / 发送
    // =========================================================================

    /**
     * 发送媒体引用：优先使用图片 URL 直发，失败则走上传兜底。
     */
    private boolean sendMediaRefBlocking(String token, String chatId, String mediaRef) {
        String ref = (mediaRef == null) ? "" : mediaRef.trim();
        if (ref.isEmpty()) {
            return true;
        }

        String uploadType = guessUploadType(ref);

        // 图片且是 http URL：先尝试直接发 URL
        if ("image".equals(uploadType) && isHttpUrl(ref)) {
            boolean ok = sendBatchMessageBlocking(token, chatId, "sampleImageMsg", Map.of("photoURL", ref));
            if (ok) {
                return true;
            }
            LOG.warning("DingTalk 图片 URL 直发失败，尝试上传兜底 ref=" + ref);
        }

        MediaBytes mb = readMediaBytesBlocking(ref);
        if (mb == null || mb.data == null || mb.data.length == 0) {
            LOG.severe("DingTalk 媒体读取失败 ref=" + ref);
            return false;
        }

        String filename = (mb.filename != null && !mb.filename.isBlank())
                ? mb.filename
                : guessFilename(ref, uploadType);

        String fileType = getFileType(filename, mb.contentType);
        if ("jpeg".equalsIgnoreCase(fileType)) {
            fileType = "jpg";
        }

        String mediaId = uploadMediaBlocking(token, mb.data, uploadType, filename, mb.contentType);
        if (mediaId == null || mediaId.isBlank()) {
            return false;
        }

        // 图片：生产上验证过 photoURL 可以填 media_id（接口兼容）
        if ("image".equals(uploadType)) {
            boolean ok = sendBatchMessageBlocking(token, chatId, "sampleImageMsg", Map.of("photoURL", mediaId));
            if (ok) {
                return true;
            }
            LOG.warning("DingTalk 图片 media_id 发送失败，回退为文件消息 ref=" + ref);
        }

        Map<String, Object> param = new LinkedHashMap<>();
        param.put("mediaId", mediaId);
        param.put("fileName", filename);
        param.put("fileType", fileType);

        return sendBatchMessageBlocking(token, chatId, "sampleFile", param);
    }

    /**
     * 上传媒体到钉钉（multipart/form-data）。
     */
    private String uploadMediaBlocking(String token, byte[] data, String mediaType, String filename, String contentType) {
        try {
            String url = "https://oapi.dingtalk.com/media/upload?access_token="
                    + urlEncode(token)
                    + "&type=" + urlEncode(mediaType);

            String mime = (contentType != null && !contentType.isBlank())
                    ? contentType
                    : guessMime(filename);

            // 构造 multipart/form-data body
            String boundary = "----javaclawbotBoundary" + UUID.randomUUID();
            byte[] body = buildMultipartBody(boundary, "media", filename, mime, data);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("content-type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String respBody = resp.body();

            if (resp.statusCode() >= 400) {
                LOG.severe("DingTalk media upload failed status=" + resp.statusCode()
                        + " type=" + mediaType
                        + " body=" + safeHead(respBody, 500));
                return null;
            }

            Map<String, Object> result;
            try {
                result = MAPPER.readValue(respBody, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                LOG.severe("DingTalk media upload 返回非 JSON body=" + safeHead(respBody, 500));
                return null;
            }

            long errcode = asLong(result.get("errcode"), 0);
            if (errcode != 0) {
                LOG.severe("DingTalk media upload api error type=" + mediaType
                        + " errcode=" + errcode
                        + " body=" + safeHead(respBody, 500));
                return null;
            }

            // media_id 的字段名在不同版本返回可能不同，做宽松兼容
            Object sub = result.get("result");
            Map<String, Object> subMap = (sub instanceof Map<?, ?>)
                    ? castToStringObjectMap((Map<?, ?>) sub)
                    : Map.of();

            Object mid = firstNonNull(
                    result.get("media_id"),
                    result.get("mediaId"),
                    subMap.get("media_id"),
                    subMap.get("mediaId")
            );

            if (mid == null) {
                LOG.severe("DingTalk media upload 缺少 media_id body=" + safeHead(respBody, 500));
                return null;
            }

            return String.valueOf(mid);

        } catch (Exception e) {
            LOG.severe("DingTalk media upload 异常 type=" + mediaType + " err=" + e);
            return null;
        }
    }

    /**
     * 读取媒体字节：
     * <ul>
     *   <li>http/https：下载</li>
     *   <li>file://：本地文件</li>
     *   <li>其它：当作本地路径（支持 ~）</li>
     * </ul>
     */
    private MediaBytes readMediaBytesBlocking(String mediaRef) {
        if (mediaRef == null || mediaRef.isBlank()) {
            return null;
        }

        // 1) http/https 下载
        if (isHttpUrl(mediaRef)) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(mediaRef))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();

                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 400) {
                    LOG.warning("DingTalk media download failed status=" + resp.statusCode() + " ref=" + mediaRef);
                    return null;
                }

                String ct = headerFirst(resp, "content-type");
                if (ct != null) {
                    int idx = ct.indexOf(';');
                    if (idx >= 0) ct = ct.substring(0, idx).trim();
                }

                String filename = guessFilename(mediaRef, guessUploadType(mediaRef));
                return new MediaBytes(resp.body(), filename, ct);

            } catch (Exception e) {
                LOG.severe("DingTalk media download 异常 ref=" + mediaRef + " err=" + e);
                return null;
            }
        }

        // 2) 本地文件读取
        try {
            Path localPath;

            if (mediaRef.startsWith("file://")) {
                URI u = URI.create(mediaRef);
                // file:// URL 里的路径需要 URLDecode
                String rawPath = u.getPath();
                String decoded = (rawPath != null) ? URLDecoder.decode(rawPath, StandardCharsets.UTF_8) : "";
                localPath = Path.of(decoded);
            } else {
                localPath = expandUser(mediaRef);
            }

            if (!Files.isRegularFile(localPath)) {
                LOG.warning("DingTalk media file not found: " + localPath);
                return null;
            }

            byte[] data = Files.readAllBytes(localPath);
            String ct = probeContentType(localPath);
            return new MediaBytes(data, localPath.getFileName().toString(), ct);

        } catch (Exception e) {
            LOG.severe("DingTalk media read 异常 ref=" + mediaRef + " err=" + e);
            return null;
        }
    }

    // =========================================================================
    // 类型判断与文件名推断
    // =========================================================================

    /**
     * 判断是否为 http/https URL。
     */
    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        try {
            URI u = URI.create(value);
            String scheme = (u.getScheme() != null) ? u.getScheme().toLowerCase(Locale.ROOT) : "";
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据扩展名推断上传类型（image / voice / video / file）。
     */
    private static String guessUploadType(String mediaRef) {
        String ext = suffixLower(mediaRef);
        if (IMAGE_EXTS.contains(ext)) return "image";
        if (AUDIO_EXTS.contains(ext)) return "voice";
        if (VIDEO_EXTS.contains(ext)) return "video";
        return "file";
    }

    /**
     * 推断文件名：优先从 URL path 取 basename，否则返回默认名。
     */
    private static String guessFilename(String mediaRef, String uploadType) {
        try {
            URI u = URI.create(mediaRef);
            String path = u.getPath();
            if (path != null && !path.isBlank()) {
                int idx = path.lastIndexOf('/');
                String name = (idx >= 0) ? path.substring(idx + 1) : path;
                name = (name != null) ? name.trim() : "";
                if (!name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
            // 不是合法 URI：继续走本地路径兜底
        }

        // 本地路径兜底
        try {
            Path p = expandUser(mediaRef);
            String fn = (p.getFileName() != null) ? p.getFileName().toString() : "";
            if (!fn.isBlank()) return fn;
        } catch (Exception ignored) {
        }

        // 默认名
        if ("image".equals(uploadType)) return "image.jpg";
        if ("voice".equals(uploadType)) return "audio.amr";
        if ("video".equals(uploadType)) return "video.mp4";
        return "file.bin";
    }

    /**
     * 获取文件类型：优先使用文件扩展名，其次从 content-type 推断。
     */
    private static String getFileType(String filename, String contentType) {
        String ext = "";
        if (filename != null) {
            int idx = filename.lastIndexOf('.');
            if (idx >= 0 && idx < filename.length() - 1) {
                ext = filename.substring(idx + 1).toLowerCase(Locale.ROOT);
            }
        }
        if (!ext.isBlank()) return ext;

        // content-type 兜底（非常弱的推断）
        if (contentType != null && contentType.contains("/")) {
            String sub = contentType.substring(contentType.indexOf('/') + 1).toLowerCase(Locale.ROOT);
            int semi = sub.indexOf(';');
            if (semi >= 0) sub = sub.substring(0, semi).trim();
            if (!sub.isBlank()) return sub;
        }
        return "bin";
    }

    // =========================================================================
    // multipart/form-data 构造
    // =========================================================================

    /**
     * 构造 multipart/form-data 请求体。
     *
     * @param boundary 边界字符串
     * @param fieldName 表单字段名（钉钉这里是 "media"）
     * @param filename 文件名
     * @param mime MIME 类型
     * @param fileBytes 文件内容
     */
    private static byte[] buildMultipartBody(
            String boundary,
            String fieldName,
            String filename,
            String mime,
            byte[] fileBytes
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 开始边界
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + safeFilename(filename) + "\"\r\n");
        writeAscii(out, "Content-Type: " + mime + "\r\n\r\n");

        // 文件内容
        out.write(fileBytes);

        // 结束
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "file.bin";
        // 简单过滤换行等，避免破坏 multipart 结构
        return filename.replace("\r", "").replace("\n", "");
    }

    // =========================================================================
    // 小工具
    // =========================================================================

    /** 读取响应头第一个值（不区分大小写） */
    private static String headerFirst(HttpResponse<?> resp, String headerName) {
        if (resp == null || headerName == null) return null;
        return resp.headers().firstValue(headerName).orElse(null);
    }

    /** 探测本地文件 content-type */
    private static String probeContentType(Path p) {
        try {
            String ct = Files.probeContentType(p);
            if (ct != null && !ct.isBlank()) return ct;
        } catch (Exception ignored) {
        }
        return guessMime(p.getFileName() != null ? p.getFileName().toString() : "");
    }

    /** 根据文件名猜 MIME（作为兜底） */
    private static String guessMime(String filename) {
        String ext = "";
        if (filename != null) {
            int idx = filename.lastIndexOf('.');
            if (idx >= 0 && idx < filename.length() - 1) {
                ext = filename.substring(idx + 1).toLowerCase(Locale.ROOT);
            }
        }
        // 常见类型兜底
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "amr" -> "audio/amr";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }

    /** 取 URL/path 后缀并小写（例如 ".jpg"） */
    private static String suffixLower(String ref) {
        if (ref == null) return "";
        try {
            URI u = URI.create(ref);
            String path = (u.getPath() != null) ? u.getPath() : ref;
            int idx = path.lastIndexOf('.');
            if (idx >= 0) {
                return path.substring(idx).toLowerCase(Locale.ROOT);
            }
            return "";
        } catch (Exception e) {
            // 非法 URI：当作普通字符串
            int idx = ref.lastIndexOf('.');
            if (idx >= 0) {
                return ref.substring(idx).toLowerCase(Locale.ROOT);
            }
            return "";
        }
    }

    /** 展开 ~ 为用户目录 */
    private static Path expandUser(String raw) {
        if (raw == null) return Path.of("").toAbsolutePath().normalize();
        String s = raw.trim();
        if (s.equals("~")) {
            return Path.of(System.getProperty("user.home", "")).normalize();
        }
        if (s.startsWith("~/")) {
            return Path.of(System.getProperty("user.home", ""), s.substring(2)).normalize();
        }
        return Path.of(s).normalize();
    }

    /** URL 编码 */
    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** 把对象转 long（带默认值） */
    private static long asLong(Object v, long def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    /** 取多个对象中第一个非空 */
    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object v : values) {
            if (v != null) return v;
        }
        return null;
    }

    /** 安全截断日志输出 */
    private static String safeHead(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /** 把 Map<?,?> 转成 Map<String,Object>（用于宽松解析） */
    private static Map<String, Object> castToStringObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null) continue;
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 媒体读取结果对象。
     */
    private static final class MediaBytes {
        private final byte[] data;
        private final String filename;
        private final String contentType;

        private MediaBytes(byte[] data, String filename, String contentType) {
            this.data = data;
            this.filename = filename;
            this.contentType = contentType;
        }
    }

    /**
     * 这个内部类只是为了避免一些静态分析器对“name 字段覆盖”的告警；
     * 实际业务不依赖它。
     */
    public static final class DingTalkChannelName {
        // 占位
    }
}