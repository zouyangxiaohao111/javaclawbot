package monitor;

import config.channel.EmailMonitorConfig;
import jakarta.mail.*;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * IMAP 邮件轮询器
 * 负责连接邮箱并获取新邮件
 */
public class EmailPoller {
    
    private static final Logger log = LoggerFactory.getLogger(EmailPoller.class);
    
    private final EmailMonitorConfig config;
    private Session session;
    private Store store;
    private Folder folder;
    private final Set<Long> processedUids = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_PROCESSED_UIDS = 10_000;

    public EmailPoller(EmailMonitorConfig config) {
        this.config = config;
    }

    /**
     * 连接到 IMAP 服务器
     */
    public CompletionStage<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.store.protocol", "imaps");
                props.put("mail.imaps.host", config.getImapHost());
                props.put("mail.imaps.port", config.getImapPort());
                props.put("mail.imaps.ssl.enable", config.isImapUseSsl());
                props.put("mail.imaps.ssl.trust", "*");

                session = Session.getInstance(props, null);
                store = session.getStore("imaps");
                store.connect(config.getImapHost(), config.getImapUsername(), config.getImapPassword());
                
                folder = store.getFolder(config.getImapMailbox());
                folder.open(Folder.READ_WRITE);
                
                log.info("Connected to IMAP: {}", config.getImapHost());
            } catch (Exception e) {
                log.error("Failed to connect to IMAP: {}", e.getMessage());
                throw new RuntimeException("IMAP connection failed", e);
            }
        });
    }

    /**
     * 断开连接
     */
    public CompletionStage<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (folder != null && folder.isOpen()) {
                    folder.close(false);
                }
                if (store != null) {
                    store.close();
                }
                log.info("Disconnected from IMAP");
            } catch (Exception e) {
                log.warn("Error disconnecting from IMAP: {}", e.getMessage());
            }
        });
    }

    /**
     * 获取新邮件（未读）
     */
    public List<MailInfo> fetchNewMails() {
        try {
            if (folder == null || !folder.isOpen()) {
                reconnect();
            }
            
            Message[] messages = folder.search(new SearchTerm() {
                @Override
                public boolean match(Message msg) {
                    try {
                        return !msg.isSet(Flags.Flag.SEEN);
                    } catch (MessagingException e) {
                        return false;
                    }
                }
            });
            
            List<MailInfo> result = new ArrayList<>();
            for (Message msg : messages) {
                MailInfo mail = parseMessage(msg);
                if (mail != null) {
                    // 使用 UID 去重
                    long uid = ((UIDFolder) folder).getUID(msg);
                    if (!processedUids.contains(uid)) {
                        result.add(mail);
                        markProcessed(uid);
                    }
                }
            }
            
            log.debug("Fetched {} new mails", result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch new mails: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按日期范围查询历史邮件
     */
    public List<MailInfo> fetchHistory(LocalDate startDate, LocalDate endDate, String keywords, int limit) {
        try {
            if (folder == null || !folder.isOpen()) {
                reconnect();
            }
            
            SearchTerm dateTerm = new AndTerm(
                new ReceivedDateTerm(ComparisonTerm.GE, Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant())),
                new ReceivedDateTerm(ComparisonTerm.LT, Date.from(endDate.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            );
            
            Message[] messages = folder.search(dateTerm);
            
            List<MailInfo> result = new ArrayList<>();
            for (Message msg : messages) {
                MailInfo mail = parseMessage(msg);
                if (mail != null) {
                    // 关键词过滤
                    if (keywords != null && !keywords.isBlank()) {
                        if (!matchKeywords(mail, keywords)) {
                            continue;
                        }
                    }
                    result.add(mail);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
            
            log.info("Fetched {} history mails from {} to {}", result.size(), startDate, endDate);
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch history mails: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void reconnect() throws MessagingException {
        disconnect().toCompletableFuture().join();
        connect().toCompletableFuture().join();
    }

    private MailInfo parseMessage(Message msg) {
        try {
            MailInfo mail = new MailInfo();
            mail.setMessageId(msg.getHeader("Message-ID") != null ? msg.getHeader("Message-ID")[0] : UUID.randomUUID().toString());
            
            Address[] from = msg.getFrom();
            mail.setFrom(from != null && from.length > 0 ? from[0].toString() : "unknown");
            
            mail.setSubject(msg.getSubject());
            mail.setBody(getTextContent(msg));
            mail.setDate(LocalDateTime.ofInstant(msg.getReceivedDate().toInstant(), ZoneId.systemDefault()));
            
            return mail;
        } catch (Exception e) {
            log.warn("Failed to parse message: {}", e.getMessage());
            return null;
        }
    }

    private String getTextContent(Message msg) throws Exception {
        if (msg.isMimeType("text/plain")) {
            return (String) msg.getContent();
        } else if (msg.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) msg.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    return (String) bp.getContent();
                }
            }
        }
        return "";
    }

    private boolean matchKeywords(MailInfo mail, String keywords) {
        String lowerKeywords = keywords.toLowerCase();
        return (mail.getSubject() != null && mail.getSubject().toLowerCase().contains(lowerKeywords))
            || (mail.getBody() != null && mail.getBody().toLowerCase().contains(lowerKeywords))
            || (mail.getFrom() != null && mail.getFrom().toLowerCase().contains(lowerKeywords));
    }

    private void markProcessed(long uid) {
        processedUids.add(uid);
        // 防止无限增长
        if (processedUids.size() > MAX_PROCESSED_UIDS) {
            Iterator<Long> it = processedUids.iterator();
            it.next();
            it.remove();
        }
    }
}