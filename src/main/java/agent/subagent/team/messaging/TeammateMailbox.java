package agent.subagent.team.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Optional;

/**
 * Teammate 信箱
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - TeammateMailbox
 *
 * 用于 teammate 之间的消息传递
 */
public class TeammateMailbox {

    private static final Logger log = LoggerFactory.getLogger(TeammateMailbox.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 信箱存储: teamName:teammateName -> messages */
    private final Map<String, ConcurrentLinkedQueue<MailboxMessage>> mailboxes = new ConcurrentHashMap<>();

    /**
     * 写入消息
     * 对应: write()
     *
     * @param teammateName 接收者 teammate 名称
     * @param message 消息
     * @param teamName 团队名称
     */
    public void write(String teammateName, MailboxMessage message, String teamName) {
        String key = buildKey(teamName, teammateName);
        mailboxes.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(message);
        log.debug("Wrote message to mailbox: key={}, type={}", key, message.getType());
    }

    /**
     * 读取消息（不删除）
     * 对应: read()
     *
     * @param teammateName teammate 名称
     * @param teamName 团队名称
     * @return 消息，或 empty
     */
    public Optional<MailboxMessage> read(String teammateName, String teamName) {
        String key = buildKey(teamName, teammateName);
        ConcurrentLinkedQueue<MailboxMessage> queue = mailboxes.get(key);
        if (queue == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(queue.peek());
    }

    /**
     * 轮询新消息
     * 对应: pollNewMessages()
     *
     * @param teammateName teammate 名称
     * @param teamName 团队名称
     * @return 新消息列表
     */
    public List<MailboxMessage> pollNewMessages(String teammateName, String teamName) {
        String key = buildKey(teamName, teammateName);
        ConcurrentLinkedQueue<MailboxMessage> queue = mailboxes.get(key);
        if (queue == null) {
            return Collections.emptyList();
        }

        List<MailboxMessage> messages = new ArrayList<>();
        MailboxMessage msg;
        while ((msg = queue.poll()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    /**
     * 获取消息数量
     *
     * @param teammateName teammate 名称
     * @param teamName 团队名称
     * @return 消息数量
     */
    public int getMessageCount(String teammateName, String teamName) {
        String key = buildKey(teamName, teammateName);
        ConcurrentLinkedQueue<MailboxMessage> queue = mailboxes.get(key);
        return queue != null ? queue.size() : 0;
    }

    /**
     * 清空信箱
     *
     * @param teammateName teammate 名称
     * @param teamName 团队名称
     */
    public void clear(String teammateName, String teamName) {
        String key = buildKey(teamName, teammateName);
        mailboxes.remove(key);
    }

    private String buildKey(String teamName, String teammateName) {
        return teamName + ":" + teammateName;
    }

    /**
     * Teammate 消息
     *
     * 对应 Open-ClaudeCode: src/utils/swarm/backends/types.ts - TeammateMessage
     */
    public static class MailboxMessage {

        /** 消息内容 */
        private final String text;

        /** 发送者 agent ID */
        private final String from;

        /** 发送者显示颜色 */
        private final String color;

        /** 消息时间戳 (ISO 字符串) */
        private final String timestamp;

        /** 5-10 词摘要，在 UI 中显示为预览 */
        private final String summary;

        /** 消息类型 */
        private final MessageType type;

        public MailboxMessage(String text, String from, String color, String timestamp,
                            String summary, MessageType type) {
            this.text = text;
            this.from = from;
            this.color = color;
            this.timestamp = timestamp != null ? timestamp : java.time.Instant.now().toString();
            this.summary = summary;
            this.type = type;
        }

        /**
         * 创建普通消息
         */
        public static MailboxMessage message(String text, String from) {
            return new MailboxMessage(text, from, null, null, null, MessageType.MESSAGE);
        }

        /**
         * 创建带颜色的消息
         */
        public static MailboxMessage message(String text, String from, String color) {
            return new MailboxMessage(text, from, color, null, null, MessageType.MESSAGE);
        }

        /**
         * 创建完整消息
         */
        public static MailboxMessage create(String text, String from, String color,
                                          String timestamp, String summary) {
            return new MailboxMessage(text, from, color, timestamp, summary, MessageType.MESSAGE);
        }

        public String getText() { return text; }
        public String getFrom() { return from; }
        public String getColor() { return color; }
        public String getTimestamp() { return timestamp; }
        public String getSummary() { return summary; }
        public MessageType getType() { return type; }

        public enum MessageType {
            /** 普通消息 */
            MESSAGE,
            /** 任务完成通知 */
            TASK_COMPLETE,
            /** 错误通知 */
            ERROR,
            /** 心跳 */
            HEARTBEAT
        }

        @Override
        public String toString() {
            return "MailboxMessage{" +
                    "text='" + text + '\'' +
                    ", from='" + from + '\'' +
                    ", color='" + color + '\'' +
                    ", timestamp='" + timestamp + '\'' +
                    ", summary='" + summary + '\'' +
                    ", type=" + type +
                    '}';
        }
    }

    // =====================
    // Shutdown Request Detection
    // =====================

    /**
     * 检查消息是否是关闭请求
     * 对应 Open-ClaudeCode: src/utils/teammateMailbox.ts - isShutdownRequest()
     *
     * @param messageText 消息文本
     * @return 如果是关闭请求，返回解析后的消息；否则返回 null
     */
    public ShutdownRequestInfo isShutdownRequest(String messageText) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(messageText, Map.class);
            if ("shutdown_request".equals(parsed.get("type"))) {
                ShutdownRequestInfo info = new ShutdownRequestInfo();
                info.type = "shutdown_request";
                info.from = (String) parsed.get("from");
                info.reason = (String) parsed.get("reason");
                return info;
            }
        } catch (Exception e) {
            // Not JSON, ignore
        }
        return null;
    }

    /**
     * 关闭请求信息
     */
    public static class ShutdownRequestInfo {
        public String type;
        public String from;
        public String reason;
    }

    // =====================
    // Idle Notification
    // =====================

    /**
     * 创建空闲通知
     * 对应 Open-ClaudeCode: src/utils/teammateMailbox.ts - createIdleNotification()
     *
     * @param agentId 代理 ID
     * @param idleReason 空闲原因 (available, interrupted, failed)
     * @param summary 摘要
     * @param completedTaskId 完成的任务 ID
     * @param completedStatus 完成状态 (resolved, blocked, failed)
     * @param failureReason 失败原因
     * @return 空闲通知消息
     */
    public static MailboxMessage createIdleNotification(
            String agentId,
            String idleReason,
            String summary,
            String completedTaskId,
            String completedStatus,
            String failureReason
    ) {
        Map<String, Object> notification = new java.util.HashMap<>();
        notification.put("type", "idle_notification");
        notification.put("from", agentId);
        notification.put("timestamp", java.time.Instant.now().toString());
        notification.put("idleReason", idleReason);
        if (summary != null) {
            notification.put("summary", summary);
        }
        if (completedTaskId != null) {
            notification.put("completedTaskId", completedTaskId);
        }
        if (completedStatus != null) {
            notification.put("completedStatus", completedStatus);
        }
        if (failureReason != null) {
            notification.put("failureReason", failureReason);
        }

        try {
            String json = objectMapper.writeValueAsString(notification);
            return MailboxMessage.create(json, agentId, null, null, summary);
        } catch (Exception e) {
            log.warn("Failed to create idle notification JSON", e);
            return MailboxMessage.message("{\"type\":\"idle_notification\",\"from\":\"" + agentId + "\"}", agentId);
        }
    }

    // =====================
    // Message Reading with Read Flag
    // =====================

    /**
     * 读取所有消息（不清除）
     * 对应 Open-ClaudeCode: readMailbox()
     *
     * @param teammateName teammate 名称
     * @param teamName 团队名称
     * @return 所有消息列表
     */
    public List<MailboxMessage> readAll(String teammateName, String teamName) {
        String key = buildKey(teamName, teammateName);
        ConcurrentLinkedQueue<MailboxMessage> queue = mailboxes.get(key);
        if (queue == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(queue);
    }
}
