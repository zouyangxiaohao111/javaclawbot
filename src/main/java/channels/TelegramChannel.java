package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;

import config.channel.TelegramConfig;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.File;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telegram 渠道（基于 TelegramBots 库，长轮询）
 *
 * <p>功能点：</p>
 * <ul>
 *   <li>长轮询收消息（文本 / 图片 / 语音 / 音频 / 文件）</li>
 *   <li>/start /new /stop /help 命令处理（/new /stop 转发到 bus）</li>
 *   <li>媒体下载到 ~/.javaclawbot/media/</li>
 *   <li>typing 指示器（每 4 秒刷新一次，直到处理结束或取消）</li>
 *   <li>媒体组（相册）短暂缓冲 0.6s，合并成一次输入转发</li>
 *   <li>出站：支持回复 message_id（可选），支持发送媒体文件 + 文本（markdown->Telegram HTML）</li>
 * </ul>
 *
 * <p>说明：</p>
 * <ul>
 *   <li>Python 版本里 audio/voice 有 Groq 转写，这里默认提供“空转写实现”，保持可编译可运行。</li>
 *   <li>如果你后续要接入真实转写，在 {@link TranscriptionProvider} 里实现即可。</li>
 * </ul>
 */
public class TelegramChannel extends BaseChannel {

    /** 渠道名称 */
    public static final String CHANNEL_NAME = "telegram";

    /** Telegram 命令菜单（对应 Python 的 BOT_COMMANDS） */
    private static final List<BotCommand> BOT_COMMANDS = List.of(
            new BotCommand("start", "Start the bot"),
            new BotCommand("new", "Start a new conversation"),
            new BotCommand("stop", "Stop the current task"),
            new BotCommand("help", "Show available commands")
    );

    /** Telegram 配置 */
    private final TelegramConfig tgConfig;

    /** 转写提供者（默认空实现） */
    private final TranscriptionProvider transcriber;

    /** bot 会话（用于 stop 时关闭轮询线程） */
    private volatile org.telegram.telegrambots.meta.generics.BotSession botSession;

    /** 机器人实例 */
    private volatile InternalBot bot;

    /** sender_id -> chat_id（用于后续回复；与 Python 一致保存） */
    private final ConcurrentHashMap<String, Long> chatIdBySender = new ConcurrentHashMap<>();

    /** chat_id -> typing 任务 */
    private final ConcurrentHashMap<String, Future<?>> typingTasks = new ConcurrentHashMap<>();

    /** 媒体组缓冲：key=chatId:mediaGroupId */
    private final ConcurrentHashMap<String, MediaGroupBuffer> mediaGroupBuffers = new ConcurrentHashMap<>();

    /** 媒体组 flush 任务：key=chatId:mediaGroupId */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> mediaGroupFlushTasks = new ConcurrentHashMap<>();

    /** 线程池：typing、媒体组延迟 flush、后台启动 */
    private final ScheduledExecutorService scheduler;

    /** 后台执行器：避免阻塞调用线程 */
    private final ExecutorService worker;

    public TelegramChannel(TelegramConfig config, MessageBus bus, String groqApiKey) {
        super(config, bus);
        this.name = CHANNEL_NAME;
        this.tgConfig = Objects.requireNonNull(config, "TelegramConfig 不能为空");

        // 默认转写：什么都不做，返回 null（与 Python “转写失败则走 [voice: path]” 分支的语义一致）
        this.transcriber = new NoopTranscriptionProvider();

        // 线程池：守护线程，避免阻塞进程退出
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "tg-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.worker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tg-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public TelegramChannel(TelegramConfig config, MessageBus bus) {
        this(config, bus, "");
    }

    /**
     * 启动 Telegram 机器人（长轮询）
     */
    @Override
    public CompletionStage<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (isBlank(tgConfig.getToken())) {
                logSevere("Telegram bot token 未配置");
                return;
            }

            setRunning(true);

            try {
                DefaultBotOptions options = new DefaultBotOptions();
                applyProxyIfAny(options, tgConfig.getProxy());

                // 创建 bot
                this.bot = new InternalBot(tgConfig.getToken(), options);

                // 注册 bot 到 TelegramBotsApi，并拿到 BotSession（用于 stop）
                TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
                this.botSession = api.registerBot(bot);

                logInfo("Starting Telegram bot (polling mode)...");

                // 注册命令菜单
                registerBotCommands();

                logInfo("Telegram bot connected (polling started)");
            } catch (Exception e) {
                logSevere("Telegram bot start failed: " + e.getMessage());
                setRunning(false);
            }
        }, worker);
    }

    /**
     * 停止 Telegram 机器人
     */
    @Override
    public CompletionStage<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            setRunning(false);

            // 取消所有 typing
            for (String chatId : new ArrayList<>(typingTasks.keySet())) {
                stopTyping(chatId);
            }

            // 取消媒体组 flush 任务
            for (ScheduledFuture<?> f : mediaGroupFlushTasks.values()) {
                f.cancel(true);
            }
            mediaGroupFlushTasks.clear();
            mediaGroupBuffers.clear();

            // 停止轮询会话
            if (botSession != null) {
                try {
                    botSession.stop();
                } catch (Exception ignored) {
                }
                botSession = null;
            }

            logInfo("Telegram bot stopped");
        }, worker);
    }

    /**
     * 发送消息到 Telegram
     */
    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            if (bot == null) {
                logWarn("Telegram bot not running");
                return;
            }

            // 发送前停止 typing（与 Python 一致：先停 typing）
            stopTyping(msg.getChatId());

            long chatId;
            try {
                chatId = Long.parseLong(msg.getChatId());
            } catch (NumberFormatException e) {
                logSevere("Invalid chat_id: " + msg.getChatId());
                return;
            }

            Integer replyToMessageId = null;
            if (tgConfig.isReplyToMessage()) {
                Object v = (msg.getMetadata() != null) ? msg.getMetadata().get("message_id") : null;
                if (v != null) {
                    try {
                        replyToMessageId = Integer.parseInt(String.valueOf(v));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 1) 先发送媒体（与 Python 一致：逐个发送）
            List<String> media = (msg.getMedia() != null) ? msg.getMedia() : List.of();
            for (String mediaPath : media) {
                if (isBlank(mediaPath)) continue;
                try {
                    sendOneMedia(chatId, mediaPath, replyToMessageId);
                } catch (Exception e) {
                    String filename = safeFilename(mediaPath);
                    logSevere("Failed to send media " + mediaPath + ": " + e.getMessage());
                    // 媒体失败时发送提示文本（与 Python 一致）
                    safeSendText(chatId, "[Failed to send: " + filename + "]", replyToMessageId);
                }
            }

            // 2) 再发送文本（分片 + markdown->HTML）
            String content = (msg.getContent() != null) ? msg.getContent() : "";
            if (!content.isBlank() && !"[empty message]".equals(content)) {
                for (String chunk : splitMessage(content, 4000)) {
                    try {
                        String html = markdownToTelegramHtml(chunk);
                        SendMessage sm = new SendMessage(String.valueOf(chatId), html);
                        sm.setParseMode("HTML");
                        if (replyToMessageId != null) {
                            sm.setReplyToMessageId(replyToMessageId);
                        }
                        bot.execute(sm);
                    } catch (Exception e) {
                        // HTML 解析失败则降级成纯文本（与 Python 一致）
                        logWarn("HTML parse failed, falling back to plain text: " + e.getMessage());
                        safeSendText(chatId, chunk, replyToMessageId);
                    }
                }
            }
        }, worker);
    }

    // =========================================================
    // 内部 Bot：把 Update 转发到 Channel
    // =========================================================

    private final class InternalBot extends TelegramLongPollingBot {

        private final String token;

        private InternalBot(String token, DefaultBotOptions options) {
            super(options);
            this.token = token;
        }

        @Override
        public String getBotUsername() {
            // 机器人用户名可不配置；不影响 long polling
            return "javaclawbot";
        }

        @Override
        public String getBotToken() {
            return token;
        }

        @Override
        public void onUpdateReceived(Update update) {
            // 所有处理走异步，避免阻塞 TelegramBots 内部线程
            worker.submit(() -> handleUpdateSafely(update));
        }

        @Override
        public void onUpdatesReceived(List<Update> updates) {
            // 批量更新也逐个处理
            for (Update u : updates) {
                onUpdateReceived(u);
            }
        }

        private void handleUpdateSafely(Update update) {
            try {
                onUpdate(update);
            } catch (Exception e) {
                // 对齐 Python 的 _on_error：记录错误
                logSevere("Telegram error: " + e.getMessage());
            }
        }
    }

    /**
     * 处理 Telegram Update（文本/命令/媒体）
     */
    private void onUpdate(Update update) throws Exception {
        if (!isRunning() || update == null) return;
        Message message = update.getMessage();
        if (message == null) return;

        User user = message.getFrom();
        if (user == null) return;

        String chatId = String.valueOf(message.getChatId());
        String senderId = buildSenderId(user);

        // 保存 chat_id 映射（与 Python 一致）
        chatIdBySender.put(senderId, message.getChatId());

        // 命令：/start /help /new /stop
        String text = message.getText();
        if (text != null && text.startsWith("/")) {
            handleCommand(senderId, chatId, message, user, text.trim());
            return;
        }

        // 普通消息：文本 + 媒体
        handleMessage(senderId, chatId, message, user);
    }

    private void handleCommand(String senderId, String chatId, Message message, User user, String cmd) throws Exception {
        // /start：不经过 ACL，直接欢迎
        if (cmd.startsWith("/start")) {
            safeSendText(Long.parseLong(chatId),
                    "👋 Hi " + safeUserFirstName(user) + "! I'm javaclawbot.\n\n"
                            + "Send me a message and I'll respond!\n"
                            + "Type /help to see available commands.",
                    null);
            return;
        }

        // /help：不经过 ACL（与 Python 一致）
        if (cmd.startsWith("/help")) {
            safeSendText(Long.parseLong(chatId),
                    "🐱 javaclawbot commands:\n"
                            + "/new — Start a new conversation\n"
                            + "/stop — Stop the current task\n"
                            + "/help — Show available commands",
                    null);
            return;
        }

        // /new /stop：转发到 bus，统一由 AgentLoop 处理
        if (cmd.startsWith("/new") || cmd.startsWith("/stop")) {
            startTyping(chatId);
            try {
                this.handleMessage(
                        senderId,
                        chatId,
                        cmd,
                        null,
                        null,
                        null
                ).toCompletableFuture().join();
            } finally {
                // 注意：typing 在 send() 或处理结束后会停止；
                // 这里不强行 stop，避免影响“处理中的持续 typing”
            }
        }
    }

    /**
     * 处理非命令消息（文本/照片/语音/音频/文件）
     */
    private void handleMessage(String senderId, String chatId, Message message, User user) throws Exception {
        if (bot == null) return;

        List<String> contentParts = new ArrayList<>();
        List<String> mediaPaths = new ArrayList<>();

        // 文本内容
        if (!isBlank(message.getText())) {
            contentParts.add(message.getText());
        }
        if (!isBlank(message.getCaption())) {
            contentParts.add(message.getCaption());
        }

        // 识别媒体
        MediaPick pick = pickMedia(message);

        // 下载媒体（如果有）
        if (pick != null) {
            try {
                Path saved = downloadTelegramFile(pick.fileId, pick.mediaType, pick.mimeType);
                mediaPaths.add(saved.toString());

                // 语音/音频尝试转写（默认 Noop 返回 null）
                if ("voice".equals(pick.mediaType) || "audio".equals(pick.mediaType)) {
                    String transcription = transcriber.transcribe(saved).toCompletableFuture().get(60, TimeUnit.SECONDS);
                    if (!isBlank(transcription)) {
                        logInfo("Transcribed " + pick.mediaType + ": " + transcription.substring(0, Math.min(50, transcription.length())) + "...");
                        contentParts.add("[transcription: " + transcription + "]");
                    } else {
                        contentParts.add("[" + pick.mediaType + ": " + saved + "]");
                    }
                } else {
                    String tag = "image".equals(pick.mediaType) ? "image" : "file";
                    contentParts.add("[" + tag + ": " + saved + "]");
                }

                logInfo("Downloaded " + pick.mediaType + " to " + saved);
            } catch (Exception e) {
                logSevere("Failed to download media: " + e.getMessage());
                String mt = (pick.mediaType != null) ? pick.mediaType : "file";
                contentParts.add("[" + mt + ": download failed]");
            }
        }

        String content = contentParts.isEmpty() ? "[empty message]" : String.join("\n", contentParts);
        logInfo("Telegram message from " + senderId + ": " + safeLogText(content));

        // metadata（与 Python 一致）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("message_id", message.getMessageId());
        metadata.put("user_id", user.getId());
        metadata.put("username", user.getUserName());
        metadata.put("first_name", user.getFirstName());
        metadata.put("is_group", message.getChat() != null && !"private".equalsIgnoreCase(message.getChat().getType()));

        // 媒体组：短暂缓冲，合并一次输入
        String mediaGroupId = message.getMediaGroupId();
        if (!isBlank(mediaGroupId)) {
            String key = chatId + ":" + mediaGroupId;

            mediaGroupBuffers.computeIfAbsent(key, k -> {
                MediaGroupBuffer b = new MediaGroupBuffer(senderId, chatId, metadata);
                startTyping(chatId);
                return b;
            });

            MediaGroupBuffer buf = mediaGroupBuffers.get(key);
            if (buf != null) {
                if (!isBlank(content) && !"[empty message]".equals(content)) {
                    buf.contents.add(content);
                }
                buf.media.addAll(mediaPaths);
            }

            // 只创建一次 flush 任务
            mediaGroupFlushTasks.computeIfAbsent(key, k -> scheduler.schedule(() -> flushMediaGroup(k), 600, TimeUnit.MILLISECONDS));
            return;
        }

        // 启动 typing（与 Python 一致：转发前先 typing）
        startTyping(chatId);

        // 转发到 bus
        this.handleMessage(
                senderId,
                chatId,
                content,
                mediaPaths,
                metadata,
                null
        ).toCompletableFuture().join();
    }

    /**
     * flush 媒体组：合并内容 + 去重 media，作为一次输入发送到 bus
     */
    private void flushMediaGroup(String key) {
        try {
            MediaGroupBuffer buf = mediaGroupBuffers.remove(key);
            if (buf == null) return;

            // 合并内容
            String content = buf.contents.isEmpty() ? "[empty message]" : String.join("\n", buf.contents);

            // media 去重（保持顺序）
            List<String> dedupMedia = new ArrayList<>(new LinkedHashSet<>(buf.media));

            this.handleMessage(
                    buf.senderId,
                    buf.chatId,
                    content,
                    dedupMedia,
                    buf.metadata,
                    null
            ).toCompletableFuture().join();
        } catch (Exception e) {
            logSevere("flushMediaGroup failed: " + e.getMessage());
        } finally {
            ScheduledFuture<?> f = mediaGroupFlushTasks.remove(key);
            if (f != null) f.cancel(false);
        }
    }

    // =========================================================
    // typing 指示器（每 4 秒刷新）
    // =========================================================

    private void startTyping(String chatId) {
        stopTyping(chatId);

        if (bot == null) return;

        // 周期性发送 typing
        Future<?> f = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!isRunning() || bot == null) return;
                SendChatAction action = new SendChatAction();
                action.setChatId(chatId);
                action.setAction(ActionType.TYPING);
                bot.execute(action);
            } catch (Exception e) {
                // typing 是 best-effort，不抛异常
                logDebug("Typing indicator stopped for " + chatId + ": " + e.getMessage());
            }
        }, 0, 4, TimeUnit.SECONDS);

        typingTasks.put(chatId, f);
    }

    private void stopTyping(String chatId) {
        Future<?> f = typingTasks.remove(chatId);
        if (f != null) {
            f.cancel(true);
        }
    }

    // =========================================================
    // 媒体下载与保存
    // =========================================================

    private static final class MediaPick {
        private final String fileId;
        private final String mediaType; // image/voice/audio/file
        private final String mimeType;

        private MediaPick(String fileId, String mediaType, String mimeType) {
            this.fileId = fileId;
            this.mediaType = mediaType;
            this.mimeType = mimeType;
        }
    }

    private MediaPick pickMedia(Message message) {
        // 图片：取最大那张
        if (message.getPhoto() != null && !message.getPhoto().isEmpty()) {
            PhotoSize best = message.getPhoto().get(message.getPhoto().size() - 1);
            return new MediaPick(best.getFileId(), "image", "image/jpeg");
        }
        if (message.getVoice() != null) {
            return new MediaPick(message.getVoice().getFileId(), "voice", message.getVoice().getMimeType());
        }
        if (message.getAudio() != null) {
            return new MediaPick(message.getAudio().getFileId(), "audio", message.getAudio().getMimeType());
        }
        if (message.getDocument() != null) {
            return new MediaPick(message.getDocument().getFileId(), "file", message.getDocument().getMimeType());
        }
        return null;
    }

    /**
     * 下载 Telegram file_id 对应文件，并保存到 ~/.javaclawbot/media/
     */
    private Path downloadTelegramFile(String fileId, String mediaType, String mimeType) throws TelegramApiException {
        if (bot == null) throw new IllegalStateException("bot not initialized");

        // 1) 获取文件路径
        org.telegram.telegrambots.meta.api.objects.File tf = bot.execute(new GetFile(fileId));

        // 2) 下载到临时文件
        File downloaded = bot.downloadFile(tf);

        // 3) 决定保存目录与后缀
        Path mediaDir = Paths.get(System.getProperty("user.home"), ".javaclawbot", "media");
        try {
            Files.createDirectories(mediaDir);
        } catch (Exception ignored) {
        }

        String ext = getExtension(mediaType, mimeType);
        String prefix = (fileId != null && fileId.length() >= 16) ? fileId.substring(0, 16) : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Path target = mediaDir.resolve(prefix + ext);

        // 4) 持久化拷贝
        try {
            Files.copy(downloaded.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new TelegramApiException("save media failed: " + e.getMessage(), e);
        }

        return target;
    }

    /**
     * 获取文件后缀（与 Python 的 _get_extension 语义一致）
     */
    private String getExtension(String mediaType, String mimeType) {
        if (!isBlank(mimeType)) {
            Map<String, String> extMap = new HashMap<>();
            extMap.put("image/jpeg", ".jpg");
            extMap.put("image/png", ".png");
            extMap.put("image/gif", ".gif");
            extMap.put("audio/ogg", ".ogg");
            extMap.put("audio/mpeg", ".mp3");
            extMap.put("audio/mp4", ".m4a");
            String hit = extMap.get(mimeType);
            if (!isBlank(hit)) return hit;
        }

        Map<String, String> typeMap = new HashMap<>();
        typeMap.put("image", ".jpg");
        typeMap.put("voice", ".ogg");
        typeMap.put("audio", ".mp3");
        typeMap.put("file", "");
        return typeMap.getOrDefault(mediaType, "");
    }

    // =========================================================
    // 出站媒体发送
    // =========================================================

    private void sendOneMedia(long chatId, String mediaPath, Integer replyToMessageId) throws TelegramApiException {
        String mediaType = getOutboundMediaType(mediaPath);
        File f = new File(mediaPath);

        if (!f.isFile()) {
            throw new TelegramApiException("media file not found: " + mediaPath);
        }

        if ("photo".equals(mediaType)) {
            SendPhoto sp = new SendPhoto(String.valueOf(chatId), new InputFile(f));
            if (replyToMessageId != null) sp.setReplyToMessageId(replyToMessageId);
            bot.execute(sp);
            return;
        }

        if ("voice".equals(mediaType)) {
            SendVoice sv = new SendVoice(String.valueOf(chatId), new InputFile(f));
            if (replyToMessageId != null) sv.setReplyToMessageId(replyToMessageId);
            bot.execute(sv);
            return;
        }

        if ("audio".equals(mediaType)) {
            SendAudio sa = new SendAudio(String.valueOf(chatId), new InputFile(f));
            if (replyToMessageId != null) sa.setReplyToMessageId(replyToMessageId);
            bot.execute(sa);
            return;
        }

        // 默认 document
        SendDocument sd = new SendDocument(String.valueOf(chatId), new InputFile(f));
        if (replyToMessageId != null) sd.setReplyToMessageId(replyToMessageId);
        bot.execute(sd);
    }

    /**
     * 根据扩展名猜测出站媒体类型（与 Python _get_media_type 一致）
     */
    private String getOutboundMediaType(String path) {
        String ext = "";
        int i = path.lastIndexOf('.');
        if (i >= 0 && i + 1 < path.length()) {
            ext = path.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        if (Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext)) return "photo";
        if ("ogg".equals(ext)) return "voice";
        if (Set.of("mp3", "m4a", "wav", "aac").contains(ext)) return "audio";
        return "document";
    }

    // =========================================================
    // 命令菜单注册
    // =========================================================

    private void registerBotCommands() {
        if (bot == null) return;
        try {
            SetMyCommands cmd = new SetMyCommands();
            cmd.setScope(new BotCommandScopeDefault());
            cmd.setCommands(BOT_COMMANDS);
            bot.execute(cmd);
            logDebug("Telegram bot commands registered");
        } catch (Exception e) {
            logWarn("Failed to register bot commands: " + e.getMessage());
        }
    }

    // =========================================================
    // markdown -> Telegram HTML（对齐 Python 的转换逻辑）
    // =========================================================

    /**
     * 把 markdown 转为 Telegram 安全 HTML
     *
     * <p>注意：这里是“最小可用转换”，对齐 Python 的处理顺序：</p>
     * <ul>
     *   <li>保护代码块/行内代码</li>
     *   <li>去掉标题 #</li>
     *   <li>去掉引用 ></li>
     *   <li>转义 HTML</li>
     *   <li>处理链接、加粗、斜体、删除线、列表</li>
     *   <li>恢复代码（<code>/<pre><code>）并再次保证内容已转义</li>
     * </ul>
     */
    public static String markdownToTelegramHtml(String text) {
        if (text == null || text.isEmpty()) return "";

        // 1) 保护代码块 ```...```
        List<String> codeBlocks = new ArrayList<>();
        text = replaceWithToken(text, "```", "```", codeBlocks, "\u0000CB", "\u0000");

        // 2) 保护行内代码 `...`
        List<String> inlineCodes = new ArrayList<>();
        text = replaceInlineCode(text, inlineCodes);

        // 3) 标题：# Title -> Title（按行处理）
        text = text.replaceAll("(?m)^#{1,6}\\s+(.+)$", "$1");

        // 4) 引用：> text -> text（按行处理）
        text = text.replaceAll("(?m)^>\\s*(.*)$", "$1");

        // 5) 转义 HTML 特殊字符
        text = escapeHtml(text);

        // 6) 链接 [text](url)
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");

        // 7) 加粗 **text** 或 __text__
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        text = text.replaceAll("__(.+?)__", "<b>$1</b>");

        // 8) 斜体 _text_（避免匹配单词内部）
        text = text.replaceAll("(?<![a-zA-Z0-9])_([^_]+)_(?![a-zA-Z0-9])", "<i>$1</i>");

        // 9) 删除线 ~~text~~
        text = text.replaceAll("~~(.+?)~~", "<s>$1</s>");

        // 10) 列表 - item / * item -> • item
        text = text.replaceAll("(?m)^[-*]\\s+", "• ");

        // 11) 恢复行内代码
        for (int i = 0; i < inlineCodes.size(); i++) {
            String code = escapeHtml(inlineCodes.get(i));
            text = text.replace("\u0000IC" + i + "\u0000", "<code>" + code + "</code>");
        }

        // 12) 恢复代码块
        for (int i = 0; i < codeBlocks.size(); i++) {
            String code = escapeHtml(codeBlocks.get(i));
            text = text.replace("\u0000CB" + i + "\u0000", "<pre><code>" + code + "</code></pre>");
        }

        return text;
    }

    /**
     * 把长文本按 4000 字符切分，尽量在换行/空格处断开（对齐 Python _split_message）
     */
    public static List<String> splitMessage(String content, int maxLen) {
        if (content == null) return List.of("");
        if (content.length() <= maxLen) return List.of(content);

        List<String> chunks = new ArrayList<>();
        String rest = content;

        while (!rest.isEmpty()) {
            if (rest.length() <= maxLen) {
                chunks.add(rest);
                break;
            }
            String cut = rest.substring(0, maxLen);
            int pos = cut.lastIndexOf('\n');
            if (pos < 0) pos = cut.lastIndexOf(' ');
            if (pos < 0) pos = maxLen;

            chunks.add(rest.substring(0, pos));
            rest = rest.substring(pos).stripLeading();
        }

        return chunks;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 用 token 替换成对的分隔符段落（用于代码块保护）
     */
    private static String replaceWithToken(String text, String open, String close,
                                           List<String> store, String tokenPrefix, String tokenSuffix) {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        while (idx < text.length()) {
            int s = text.indexOf(open, idx);
            if (s < 0) {
                out.append(text.substring(idx));
                break;
            }
            int e = text.indexOf(close, s + open.length());
            if (e < 0) {
                // 找不到闭合，直接追加剩余
                out.append(text.substring(idx));
                break;
            }

            // 追加 open 之前的部分
            out.append(text, idx, s);

            // 提取中间内容（兼容 ```lang\n...```）
            int contentStart = s + open.length();
            int contentEnd = e;
            String inner = text.substring(contentStart, contentEnd);

            // 去掉可能的语言标记开头（如 ```python\n）
            inner = inner.replaceFirst("^[\\w-]*\\n?", "");

            store.add(inner);
            out.append(tokenPrefix).append(store.size() - 1).append(tokenSuffix);

            idx = e + close.length();
        }
        return out.toString();
    }

    /**
     * 保护行内代码 `...`
     */
    private static String replaceInlineCode(String text, List<String> store) {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        while (idx < text.length()) {
            int s = text.indexOf('`', idx);
            if (s < 0) {
                out.append(text.substring(idx));
                break;
            }
            int e = text.indexOf('`', s + 1);
            if (e < 0) {
                out.append(text.substring(idx));
                break;
            }

            out.append(text, idx, s);
            String inner = text.substring(s + 1, e);
            store.add(inner);
            out.append("\u0000IC").append(store.size() - 1).append("\u0000");
            idx = e + 1;
        }
        return out.toString();
    }

    // =========================================================
    // proxy 支持（可选）
    // =========================================================

    /**
     * 解析 proxy 字符串并应用到 TelegramBots 选项
     *
     * <p>支持格式（尽量兼容）：</p>
     * <ul>
     *   <li>http://host:port</li>
     *   <li>socks5://host:port</li>
     * </ul>
     *
     * <p>注意：不同版本 TelegramBots 的代理字段可能略有差异，这里用最常见方式。</p>
     */
    private void applyProxyIfAny(DefaultBotOptions options, String proxy) {
        if (options == null || isBlank(proxy)) return;
        try {
            URI uri = URI.create(proxy.trim());
            String scheme = (uri.getScheme() != null) ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            String host = uri.getHost();
            int port = uri.getPort();

            if (isBlank(host) || port <= 0) return;

            if (scheme.startsWith("socks")) {
                options.setProxyHost(host);
                options.setProxyPort(port);
                options.setProxyType(DefaultBotOptions.ProxyType.SOCKS5);
                logInfo("Telegram proxy enabled: SOCKS5 " + host + ":" + port);
                return;
            }

            if (scheme.startsWith("http")) {
                options.setProxyHost(host);
                options.setProxyPort(port);
                options.setProxyType(DefaultBotOptions.ProxyType.HTTP);
                logInfo("Telegram proxy enabled: HTTP " + host + ":" + port);
            }
        } catch (Exception e) {
            logWarn("Invalid proxy ignored: " + proxy + " (" + e.getMessage() + ")");
        }
    }

    // =========================================================
    // 数据结构
    // =========================================================

    private static final class MediaGroupBuffer {
        private final String senderId;
        private final String chatId;
        private final List<String> contents = new ArrayList<>();
        private final List<String> media = new ArrayList<>();
        private final Map<String, Object> metadata;

        private MediaGroupBuffer(String senderId, String chatId, Map<String, Object> metadata) {
            this.senderId = senderId;
            this.chatId = chatId;
            this.metadata = metadata;
        }
    }

    // =========================================================
    // 转写接口（默认空实现）
    // =========================================================

    /**
     * 转写提供者接口
     *
     * <p>后续你要接 Groq / Whisper / 自建 ASR，都可以实现这个接口。</p>
     */
    public interface TranscriptionProvider {
        CompletionStage<String> transcribe(Path audioPath);
    }

    /**
     * 空转写实现：永远返回 null（表示没有转写结果）
     */
    public static final class NoopTranscriptionProvider implements TranscriptionProvider {
        @Override
        public CompletionStage<String> transcribe(Path audioPath) {
            return CompletableFuture.completedFuture(null);
        }
    }

    // =========================================================
    // 工具方法
    // =========================================================

    /**
     * 构造 sender_id：id|username（便于 allowlist 同时支持 id 与 username）
     */
    private static String buildSenderId(User user) {
        String sid = String.valueOf(user.getId());
        String username = user.getUserName();
        if (!isBlank(username)) {
            return sid + "|" + username;
        }
        return sid;
    }

    private static String safeUserFirstName(User user) {
        String fn = user.getFirstName();
        return isBlank(fn) ? "there" : fn;
    }

    private void safeSendText(long chatId, String text, Integer replyToMessageId) {
        if (bot == null) return;
        try {
            SendMessage sm = new SendMessage(String.valueOf(chatId), text);
            if (replyToMessageId != null) sm.setReplyToMessageId(replyToMessageId);
            bot.execute(sm);
        } catch (Exception e) {
            logSevere("Error sending Telegram message: " + e.getMessage());
        }
    }

    private static String safeLogText(String s) {
        if (s == null) return "";
        String t = s.replace("\n", "\\n").replace("\r", "\\r");
        if (t.length() > 200) return t.substring(0, 200) + "...";
        return t;
    }

    private static String safeFilename(String path) {
        if (path == null) return "file";
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (idx >= 0 && idx + 1 < path.length()) ? path.substring(idx + 1) : path;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }


}