package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;

import config.channel.EmailConfig;
import jakarta.mail.*;
import jakarta.mail.Flags.Flag;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件渠道实现：IMAP 轮询收信 + SMTP 发信回复。
 *
 * <p>功能说明：</p>
 * <ul>
 *   <li>入站：轮询 IMAP 未读邮件（UNSEEN），解析邮件正文后发布到消息总线</li>
 *   <li>出站：使用 SMTP 向发件人地址发送回复邮件</li>
 * </ul>
 *
 * <p>重要约束：</p>
 * <ul>
 *   <li>必须显式同意 consentGranted 才启用（防止未授权收发邮件）</li>
 *   <li>去重：主要依赖 markSeen；同时用 UID 集合做安全网（限制最大数量防止无限增长）</li>
 * </ul>
 */
public class EmailChannel extends BaseChannel {

    private static final Logger LOG = Logger.getLogger(EmailChannel.class.getName());

    /** 渠道名 */
    public static final String CHANNEL_NAME = "email";

    /** 处理过的 UID 最大数量（防止集合无限增长） */
    private static final int MAX_PROCESSED_UIDS = 100_000;

    /** 简单 HTML 转文本：把 br/p 换行、去标签、反转义 */
    private static final Pattern HTML_BR = Pattern.compile("<\\s*br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_P_END = Pattern.compile("<\\s*/\\s*p\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /** 从 IMAP FETCH 结果中抽 UID 的正则（通常 provider 会提供 UID，但这里保留类似 Python 的容错逻辑） */
    private static final Pattern UID_PATTERN = Pattern.compile("\\bUID\\s+(\\d+)\\b");

    /** 配置对象 */
    private final EmailConfig cfg;

    /** sender -> last subject（用于构造回复主题） */
    private final ConcurrentHashMap<String, String> lastSubjectByChat = new ConcurrentHashMap<>();

    /** sender -> last message-id（用于 In-Reply-To / References） */
    private final ConcurrentHashMap<String, String> lastMessageIdByChat = new ConcurrentHashMap<>();

    /** 已处理过的 UID（安全网去重），容量受限 */
    private volatile Set<String> processedUids = ConcurrentHashMap.newKeySet();

    /** 轮询执行器（单线程，保证行为可控） */
    private final ScheduledExecutorService scheduler;

    /** 轮询任务 */
    private volatile ScheduledFuture<?> pollFuture;

    public EmailChannel(EmailConfig config, MessageBus bus) {
        super(config, bus);
        this.cfg = config;
        this.name = CHANNEL_NAME;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("email-channel-poller");
            return t;
        });
    }

    /**
     * 启动 IMAP 轮询。
     *
     * <p>行为对齐 Python：</p>
     * <ul>
     *   <li>consentGranted=false 直接禁用</li>
     *   <li>校验配置字段缺失则不启动</li>
     *   <li>轮询间隔：pollIntervalSeconds，最小 5 秒</li>
     *   <li>每轮抓取 UNSEEN 邮件，逐个发布到 bus</li>
     * </ul>
     */
    @Override
    public CompletionStage<Void> start() {
        // 未授权则不启用
        if (!cfg.isConsentGranted()) {
            LOG.warning("邮件渠道已禁用：consentGranted=false。必须在明确授权后设置 channels.email.consentGranted=true");
            return CompletableFuture.completedFuture(null);
        }

        // 校验配置
        if (!validateConfig()) {
            return CompletableFuture.completedFuture(null);
        }

        setRunning(true);
        LOG.info("启动 Email 渠道（IMAP 轮询模式）...");

        int pollSeconds = Math.max(5, cfg.getPollIntervalSeconds());

        // 避免重复启动
        synchronized (this) {
            if (pollFuture != null && !pollFuture.isDone()) {
                return CompletableFuture.completedFuture(null);
            }

            pollFuture = scheduler.scheduleWithFixedDelay(() -> {
                if (!isRunning()) return;

                try {
                    List<InboundItem> items = fetchNewMessages();
                    for (InboundItem item : items) {
                        String sender = item.sender;

                        if (item.subject != null && !item.subject.isBlank()) {
                            lastSubjectByChat.put(sender, item.subject);
                        }
                        if (item.messageId != null && !item.messageId.isBlank()) {
                            lastMessageIdByChat.put(sender, item.messageId);
                        }

                        // 发布入站消息（BaseChannel 里会做 allow_from 校验并 publishInbound）
                        handleMessage(
                                sender,
                                sender,
                                item.content,
                                null,
                                item.metadata,
                                null
                        ).toCompletableFuture().join();
                    }
                } catch (Exception e) {
                    LOG.warning("邮件轮询异常: " + e);
                }
            }, 0, pollSeconds, TimeUnit.SECONDS);
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 停止轮询。
     */
    @Override
    public CompletionStage<Void> stop() {
        setRunning(false);

        ScheduledFuture<?> f = pollFuture;
        pollFuture = null;
        if (f != null) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }

        // 不强制 shutdown scheduler：如果整个进程还需要复用，可以不关；
        // 这里为了更贴近“停止即释放资源”，进行关闭。
        scheduler.shutdownNow();

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 发送邮件（SMTP）。
     *
     * <p>行为对齐 Python：</p>
     * <ul>
     *   <li>consentGranted=false 不发送</li>
     *   <li>SMTP host 未配置不发送</li>
     *   <li>收件人地址使用 msg.chatId</li>
     *   <li>自动回复开关：autoReplyEnabled 仅控制“对方先来信后的自动回复”，不控制主动发送</li>
     *   <li>forceSend：metadata.force_send=true 时绕过 autoReplyEnabled</li>
     *   <li>主题：基于 lastSubjectByChat 做 reply subject；metadata.subject 可覆盖</li>
     *   <li>In-Reply-To/References：使用 lastMessageIdByChat</li>
     * </ul>
     */
    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            if (!cfg.isConsentGranted()) {
                LOG.warning("跳过邮件发送：consentGranted=false");
                return;
            }
            if (cfg.getSmtpHost() == null || cfg.getSmtpHost().isBlank()) {
                LOG.warning("邮件渠道 SMTP host 未配置");
                return;
            }

            String toAddr = safeLowerTrim(msg.getChatId());
            if (toAddr.isBlank()) {
                LOG.warning("邮件渠道缺少收件人地址");
                return;
            }

            boolean isReply = lastSubjectByChat.containsKey(toAddr);
            boolean forceSend = truthy(msg.getMetadata() != null ? msg.getMetadata().get("force_send") : null);

            // autoReplyEnabled 只控制自动回复（对方来信后），不影响主动发送
            if (isReply && !cfg.isAutoReplyEnabled() && !forceSend) {
                LOG.info("跳过对 " + toAddr + " 的自动邮件回复：autoReplyEnabled=false");
                return;
            }

            String baseSubject = lastSubjectByChat.getOrDefault(toAddr, "javaclawbot reply");
            String subject = replySubject(baseSubject);

            // 支持 metadata.subject 覆盖主题
            if (msg.getMetadata() != null) {
                Object sub = msg.getMetadata().get("subject");
                if (sub instanceof String s) {
                    String override = s.trim();
                    if (!override.isBlank()) {
                        subject = override;
                    }
                }
            }

            try {
                sendSmtpEmail(toAddr, subject, msg.getContent() != null ? msg.getContent() : "", lastMessageIdByChat.get(toAddr));
            } catch (Exception e) {
                LOG.warning("发送邮件到 " + toAddr + " 失败: " + e);
                // 对齐 Python：抛出让上层可见
                throw new CompletionException(e);
            }
        });
    }

    // =========================================================================
    // 入站：抓取邮件
    // =========================================================================

    /**
     * 抓取未读邮件（UNSEEN），并按配置决定是否 mark seen。
     */
    private List<InboundItem> fetchNewMessages() {
        return fetchMessages(
                new String[]{"UNSEEN"},
                cfg.isMarkSeen(),
                true,
                0
        );
    }

    /**
     * 按日期范围抓取邮件：[startDate, endDate)。
     *
     * <p>说明：</p>
     * <ul>
     *   <li>用于历史汇总类任务</li>
     *   <li>不标记已读，不做去重（因为可能需要重复读取）</li>
     * </ul>
     */
    public List<Map<String, Object>> fetchMessagesBetweenDates(LocalDate startDate, LocalDate endDate, int limit) {
        if (endDate == null || startDate == null || !endDate.isAfter(startDate)) {
            return List.of();
        }

        // IMAP 的 SINCE/BEFORE 基于日期（不含时分秒），这里对齐 Python 逻辑
        String since = formatImapDate(startDate);
        String before = formatImapDate(endDate);

        List<InboundItem> items = fetchMessages(
                new String[]{"SINCE", since, "BEFORE", before},
                false,
                false,
                Math.max(1, limit)
        );

        // 对齐 Python 返回 list[dict[str,Any]]
        List<Map<String, Object>> out = new ArrayList<>();
        for (InboundItem i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sender", i.sender);
            m.put("subject", i.subject);
            m.put("message_id", i.messageId);
            m.put("content", i.content);
            m.put("metadata", i.metadata);
            out.add(m);
        }
        return out;
    }

    /**
     * 通用抓取逻辑：按 IMAP 搜索条件抓取并解析。
     *
     * @param searchCriteria IMAP search 参数（例如 UNSEEN / SINCE ... BEFORE ...）
     * @param markSeen       是否标记已读
     * @param dedupe         是否按 UID 去重
     * @param limit          限制数量（<=0 表示不限制；>0 则取最后 N 封）
     */
    private List<InboundItem> fetchMessages(String[] searchCriteria, boolean markSeen, boolean dedupe, int limit) {
        List<InboundItem> out = new ArrayList<>();

        Store store = null;
        Folder folder = null;

        try {
            Session session = Session.getInstance(buildImapProps());
            store = session.getStore(cfg.isImapUseSsl() ? "imaps" : "imap");
            store.connect(cfg.getImapHost(), cfg.getImapPort(), cfg.getImapUsername(), cfg.getImapPassword());

            String mailbox = (cfg.getImapMailbox() != null && !cfg.getImapMailbox().isBlank()) ? cfg.getImapMailbox() : "INBOX";
            folder = store.getFolder(mailbox);
            if (folder == null || !folder.exists()) {
                return out;
            }

            folder.open(markSeen ? Folder.READ_WRITE : Folder.READ_ONLY);

            // 搜索
            Message[] found = searchFolder(folder, searchCriteria);
            if (found == null || found.length == 0) {
                return out;
            }

            // 限制数量：取最后 N 封（对齐 Python ids[-limit:]）
            if (limit > 0 && found.length > limit) {
                found = Arrays.copyOfRange(found, found.length - limit, found.length);
            }

            for (Message m : found) {
                if (!(m instanceof MimeMessage mm)) {
                    continue;
                }

                String sender = extractSender(mm);
                if (sender.isBlank()) {
                    continue;
                }

                // UID（用于去重）
                String uid = extractUid(folder, mm);

                if (dedupe && uid != null && !uid.isBlank() && processedUids.contains(uid)) {
                    continue;
                }

                String subject = decodeHeaderValue(safeString(mm.getSubject()));
                String dateValue = safeString(mm.getHeader("Date", null));
                String messageId = safeString(mm.getHeader("Message-ID", null)).trim();

                String body = extractTextBody(mm);
                if (body.isBlank()) {
                    body = "(empty email body)";
                }

                int maxBody = cfg.getMaxBodyChars();
                if (maxBody > 0 && body.length() > maxBody) {
                    body = body.substring(0, maxBody);
                }

                String content = ""
                        + "Email received.\n"
                        + "From: " + sender + "\n"
                        + "Subject: " + subject + "\n"
                        + "Date: " + dateValue + "\n\n"
                        + body;

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("message_id", messageId);
                metadata.put("subject", subject);
                metadata.put("date", dateValue);
                metadata.put("sender_email", sender);
                metadata.put("uid", uid);

                out.add(new InboundItem(sender, subject, messageId, content, metadata));

                // 去重集合维护（安全网）
                if (dedupe && uid != null && !uid.isBlank()) {
                    processedUids.add(uid);

                    // 容量控制：超限时随机丢弃一半（与 Python“丢掉后半段”效果相似，且不依赖顺序）
                    if (processedUids.size() > MAX_PROCESSED_UIDS) {
                        processedUids = evictHalf(processedUids);
                    }
                }

                // 标记已读（markSeen 是主去重手段）
                if (markSeen) {
                    try {
                        mm.setFlag(Flag.SEEN, true);
                    } catch (Exception ignored) {
                    }
                }
            }

        } catch (Exception e) {
            LOG.warning("IMAP 抓取失败: " + e);
        } finally {
            // 关闭资源
            try {
                if (folder != null && folder.isOpen()) folder.close(false);
            } catch (Exception ignored) {
            }
            try {
                if (store != null) store.close();
            } catch (Exception ignored) {
            }
        }

        return out;
    }

    /**
     * 在 folder 上执行 IMAP 搜索。
     *
     * <p>说明：Jakarta Mail 的 SearchTerm 体系较复杂，这里为了保持与 Python 的一致，
     * 采用底层协议字符串搜索：Folder.search(SearchTerm) 不能直接拼 "SINCE ... BEFORE ..."。
     *
     * <p>因此，这里采用一个折中方案：</p>
     * <ul>
     *   <li>UNSEEN：使用 FlagTerm</li>
     *   <li>SINCE/BEFORE：使用 ReceivedDateTerm / SentDateTerm 的组合（优先按 ReceivedDate）</li>
     *   <li>如果 criteria 非上述组合，则退化为“返回全部邮件”</li>
     * </ul>
     */
    private Message[] searchFolder(Folder folder, String[] criteria) throws Exception {
        if (criteria == null || criteria.length == 0) {
            return folder.getMessages();
        }

        // 简化解析：只覆盖本工程实际使用的两类：
        // 1) ("UNSEEN")
        // 2) ("SINCE", d1, "BEFORE", d2)
        if (criteria.length == 1 && "UNSEEN".equalsIgnoreCase(criteria[0])) {
            Flags flags = new Flags(Flag.SEEN);
            // 未读：SEEN=false
            SearchTerm term = new FlagTerm(flags, false);
            return folder.search(term);
        }

        if (criteria.length == 4
                && "SINCE".equalsIgnoreCase(criteria[0])
                && "BEFORE".equalsIgnoreCase(criteria[2])) {

            Date since = parseImapDate(criteria[1]);
            Date before = parseImapDate(criteria[3]);
            if (since == null || before == null) {
                return folder.getMessages();
            }

            // 组合条件：since <= date < before
            SearchTerm t1 = new ReceivedDateTerm(ComparisonTerm.GE, since);
            SearchTerm t2 = new ReceivedDateTerm(ComparisonTerm.LT, before);
            return folder.search(new AndTerm(t1, t2));
        }

        // 未覆盖的条件：返回全部（保持“能跑”，但行为可能更宽松）
        return folder.getMessages();
    }

    // =========================================================================
    // 出站：SMTP 发送
    // =========================================================================

    /**
     * 通过 SMTP 发送邮件。
     *
     * @param toAddr     收件人
     * @param subject    主题
     * @param body       正文（纯文本）
     * @param inReplyTo  若不为空，则设置 In-Reply-To/References
     */
    private void sendSmtpEmail(String toAddr, String subject, String body, String inReplyTo) throws Exception {
        Session session = Session.getInstance(buildSmtpProps());
        MimeMessage message = new MimeMessage(session);

        String from = firstNonBlank(cfg.getFromAddress(), cfg.getSmtpUsername(), cfg.getImapUsername());
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddr, false));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(body != null ? body : "", StandardCharsets.UTF_8.name());

        if (inReplyTo != null && !inReplyTo.isBlank()) {
            message.setHeader("In-Reply-To", inReplyTo);
            message.setHeader("References", inReplyTo);
        }

        int timeoutSeconds = 30;

        // 发送（SSL / STARTTLS）
        Transport transport = null;
        try {
            if (cfg.isSmtpUseSsl()) {
                // SMTPS
                transport = session.getTransport("smtps");
            } else {
                transport = session.getTransport("smtp");
            }

            transport.connect(cfg.getSmtpHost(), cfg.getSmtpPort(), cfg.getSmtpUsername(), cfg.getSmtpPassword());
            transport.sendMessage(message, message.getAllRecipients());
        } finally {
            try {
                if (transport != null) transport.close();
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================================
    // 配置校验 / Subject 处理 / 正文提取
    // =========================================================================

    /**
     * 校验邮件配置是否完整。
     */
    private boolean validateConfig() {
        List<String> missing = new ArrayList<>();

        if (isBlank(cfg.getImapHost())) missing.add("imap_host");
        if (isBlank(cfg.getImapUsername())) missing.add("imap_username");
        if (isBlank(cfg.getImapPassword())) missing.add("imap_password");

        if (isBlank(cfg.getSmtpHost())) missing.add("smtp_host");
        if (isBlank(cfg.getSmtpUsername())) missing.add("smtp_username");
        if (isBlank(cfg.getSmtpPassword())) missing.add("smtp_password");

        if (!missing.isEmpty()) {
            LOG.severe("邮件渠道配置不完整，缺少: " + String.join(", ", missing));
            return false;
        }
        return true;
    }

    /**
     * 构造回复主题：若原主题已是 Re: 开头则不重复加。
     */
    private String replySubject(String baseSubject) {
        String subject = (baseSubject != null ? baseSubject : "").trim();
        if (subject.isBlank()) subject = "javaclawbot reply";

        String prefix = (cfg.getSubjectPrefix() != null && !cfg.getSubjectPrefix().isBlank())
                ? cfg.getSubjectPrefix()
                : "Re: ";

        if (subject.toLowerCase(Locale.ROOT).startsWith("re:")) {
            return subject;
        }
        return prefix + subject;
    }

    /**
     * 从邮件中提取 sender 邮箱地址（小写、去空格）。
     */
    private static String extractSender(MimeMessage msg) {
        try {
            Address[] from = msg.getFrom();
            if (from == null || from.length == 0) return "";
            String addr = ((InternetAddress) from[0]).getAddress();
            return safeLowerTrim(addr);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解码邮件头（Subject 等），兼容各种编码。
     */
    private static String decodeHeaderValue(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return MimeUtility.decodeText(value);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 提取可读正文：
     * - multipart：优先 text/plain，其次 text/html（转换为纯文本）
     * - 非 multipart：若是 text/html 则转换，否则直接取文本
     */
    private static String extractTextBody(Part msg) {
        try {
            if (msg.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) msg.getContent();
                List<String> plain = new ArrayList<>();
                List<String> html = new ArrayList<>();

                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart part = mp.getBodyPart(i);

                    // 跳过附件
                    String disp = part.getDisposition();
                    if (disp != null && disp.equalsIgnoreCase(Part.ATTACHMENT)) {
                        continue;
                    }

                    // 递归：multipart/alternative 等也要展开
                    if (part.isMimeType("multipart/*")) {
                        String nested = extractTextBody(part);
                        if (!nested.isBlank()) {
                            plain.add(nested);
                        }
                        continue;
                    }

                    String contentType = safeString(part.getContentType()).toLowerCase(Locale.ROOT);
                    String text = readPartAsString(part);

                    if (text == null || text.isBlank()) {
                        continue;
                    }

                    if (contentType.startsWith("text/plain")) {
                        plain.add(text);
                    } else if (contentType.startsWith("text/html")) {
                        html.add(text);
                    }
                }

                if (!plain.isEmpty()) {
                    return String.join("\n\n", plain).trim();
                }
                if (!html.isEmpty()) {
                    return htmlToText(String.join("\n\n", html)).trim();
                }
                return "";
            }

            // 单体消息
            String contentType = safeString(msg.getContentType()).toLowerCase(Locale.ROOT);
            String text = readPartAsString(msg);
            if (text == null) return "";

            if (contentType.startsWith("text/html")) {
                return htmlToText(text).trim();
            }
            return text.trim();

        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取 Part 的内容为字符串：
     * - 优先 getContent() 是 String
     * - 否则读 InputStream 并按 charset 尝试解码
     */
    private static String readPartAsString(Part part) {
        try {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }

            // 兜底：按字节读取
            try (InputStream in = part.getInputStream()) {
                byte[] bytes = readAllBytes(in);
                String charset = extractCharset(part.getContentType());
                if (charset == null || charset.isBlank()) charset = "utf-8";
                return new String(bytes, charset);
            }
        } catch (Exception e) {
            // 再兜底：按 UTF-8 解码
            try (InputStream in = part.getInputStream()) {
                byte[] bytes = readAllBytes(in);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    /**
     * 把 HTML 转成较可读的纯文本（简化实现）。
     */
    private static String htmlToText(String rawHtml) {
        if (rawHtml == null) return "";
        String text = HTML_BR.matcher(rawHtml).replaceAll("\n");
        text = HTML_P_END.matcher(text).replaceAll("\n");
        text = HTML_TAG.matcher(text).replaceAll("");
        // HTML 实体反转义
        return unescapeHtml(text);
    }

    /**
     * 简单 HTML 实体反转义（覆盖常见实体）。
     */
    private static String unescapeHtml(String s) {
        if (s == null) return "";
        // 常见实体
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /**
     * 提取 Content-Type 中的 charset。
     */
    private static String extractCharset(String contentType) {
        if (contentType == null) return null;
        String[] parts = contentType.split(";");
        for (String p : parts) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("charset=")) {
                String cs = p.substring(p.indexOf('=') + 1).trim();
                cs = cs.replace("\"", "");
                return cs;
            }
        }
        return null;
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // =========================================================================
    // UID 提取 / 日期格式
    // =========================================================================

    /**
     * 从 folder + message 提取 UID（不同 provider 支持度不同）。
     *
     * <p>Jakarta Mail 有 UIDFolder 接口可取 UID：</p>
     * <ul>
     *   <li>如果 folder 实现了 UIDFolder，则使用 getUID(message)</li>
     *   <li>否则尝试从 message.getHeader("X-UID") 等获取（不保证存在）</li>
     * </ul>
     */
    private static String extractUid(Folder folder, Message message) {
        try {
            if (folder instanceof UIDFolder uidFolder) {
                long uid = uidFolder.getUID(message);
                if (uid > 0) return String.valueOf(uid);
            }
        } catch (Exception ignored) {
        }

        try {
            String xuid = safeString(message.getHeader("X-UID") == null ? null : message.getHeader("X-UID")[0]);
            if (!xuid.isBlank()) return xuid.trim();
        } catch (Exception ignored) {
        }

        // 最后兜底：从原始头部里找 UID（不一定有）
        try {
            Enumeration<?> all = message.getAllHeaders();
            while (all.hasMoreElements()) {
                Header h = (Header) all.nextElement();
                if (h == null) continue;
                Matcher m = UID_PATTERN.matcher(h.getValue());
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    /**
     * IMAP 日期格式：dd-MMM-yyyy（月份固定英文缩写）。
     */
    private static String formatImapDate(LocalDate d) {
        // Java 自带 Locale.ENGLISH 可保证月份英文
        return String.format(Locale.ENGLISH, "%02d-%s-%04d", d.getDayOfMonth(), monthAbbr(d.getMonthValue()), d.getYear());
    }

    private static String monthAbbr(int month) {
        return switch (month) {
            case 1 -> "Jan";
            case 2 -> "Feb";
            case 3 -> "Mar";
            case 4 -> "Apr";
            case 5 -> "May";
            case 6 -> "Jun";
            case 7 -> "Jul";
            case 8 -> "Aug";
            case 9 -> "Sep";
            case 10 -> "Oct";
            case 11 -> "Nov";
            case 12 -> "Dec";
            default -> "Jan";
        };
    }

    /**
     * 把 dd-MMM-yyyy 解析成 Date（使用系统时区）。
     */
    private static Date parseImapDate(String ddMmmYyyy) {
        try {
            // 期望格式：02-Jan-2026
            String[] parts = ddMmmYyyy.split("-");
            if (parts.length != 3) return null;

            int day = Integer.parseInt(parts[0]);
            int year = Integer.parseInt(parts[2]);

            String mon = parts[1].trim();
            int month = switch (mon) {
                case "Jan" -> 1;
                case "Feb" -> 2;
                case "Mar" -> 3;
                case "Apr" -> 4;
                case "May" -> 5;
                case "Jun" -> 6;
                case "Jul" -> 7;
                case "Aug" -> 8;
                case "Sep" -> 9;
                case "Oct" -> 10;
                case "Nov" -> 11;
                case "Dec" -> 12;
                default -> 1;
            };

            LocalDate ld = LocalDate.of(year, month, day);
            return Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Props 构造（IMAP/SMTP）
    // =========================================================================

    /**
     * IMAP 连接属性。
     */
    private Properties buildImapProps() {
        Properties p = new Properties();
        p.put("mail.store.protocol", cfg.isImapUseSsl() ? "imaps" : "imap");
        p.put("mail.imap.host", cfg.getImapHost());
        p.put("mail.imap.port", String.valueOf(cfg.getImapPort()));
        p.put("mail.imaps.host", cfg.getImapHost());
        p.put("mail.imaps.port", String.valueOf(cfg.getImapPort()));

        // 连接超时（毫秒）
        p.put("mail.imap.connectiontimeout", "30000");
        p.put("mail.imap.timeout", "30000");
        p.put("mail.imaps.connectiontimeout", "30000");
        p.put("mail.imaps.timeout", "30000");

        return p;
    }

    /**
     * SMTP 连接属性。
     */
    private Properties buildSmtpProps() {
        Properties p = new Properties();
        p.put("mail.transport.protocol", cfg.isSmtpUseSsl() ? "smtps" : "smtp");
        p.put("mail.smtp.host", cfg.getSmtpHost());
        p.put("mail.smtp.port", String.valueOf(cfg.getSmtpPort()));
        p.put("mail.smtp.auth", "true");

        // 超时（毫秒）
        p.put("mail.smtp.connectiontimeout", "30000");
        p.put("mail.smtp.timeout", "30000");
        p.put("mail.smtp.writetimeout", "30000");

        // STARTTLS
        if (!cfg.isSmtpUseSsl() && cfg.isSmtpUseTls()) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "false");
        }

        // SMTPS
        if (cfg.isSmtpUseSsl()) {
            p.put("mail.smtps.host", cfg.getSmtpHost());
            p.put("mail.smtps.port", String.valueOf(cfg.getSmtpPort()));
            p.put("mail.smtps.auth", "true");
        }

        return p;
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String s) return !s.isBlank();
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

    private static String safeLowerTrim(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    /**
     * 超限时丢弃一半元素（随机化丢弃，避免依赖 Set 的迭代顺序）。
     */
    private static Set<String> evictHalf(Set<String> current) {
        List<String> list = new ArrayList<>(current);
        Collections.shuffle(list);
        int keep = Math.max(1, list.size() / 2);
        Set<String> out = ConcurrentHashMap.newKeySet(keep);
        for (int i = 0; i < keep; i++) {
            out.add(list.get(i));
        }
        return out;
    }

    // =========================================================================
    // 入站项结构
    // =========================================================================

    /**
     * 内部结构：一封解析后的邮件。
     */
    private static final class InboundItem {
        private final String sender;
        private final String subject;
        private final String messageId;
        private final String content;
        private final Map<String, Object> metadata;

        private InboundItem(String sender, String subject, String messageId, String content, Map<String, Object> metadata) {
            this.sender = sender;
            this.subject = subject;
            this.messageId = messageId;
            this.content = content;
            this.metadata = metadata;
        }
    }
}