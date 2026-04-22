package agent.subagent.team.messaging;

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
     * 信箱消息
     */
    public static class MailboxMessage {
        private final String sender;
        private final String content;
        private final MessageType type;
        private final long timestamp;

        public MailboxMessage(String sender, String content, MessageType type) {
            this.sender = sender;
            this.content = content;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }

        public String getSender() {
            return sender;
        }

        public String getContent() {
            return content;
        }

        public MessageType getType() {
            return type;
        }

        public long getTimestamp() {
            return timestamp;
        }

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
    }
}
