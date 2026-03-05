package session;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 会话对象
 *
 * 功能：
 * - 以追加方式保存消息（便于缓存与持久化）
 * - 支持追加消息
 * - 获取用于模型输入的历史消息（仅未归并部分，并对齐到用户轮次）
 * - 支持清空会话
 */
public final class Session {

    /** 会话标识：channel:chat_id */
    private final String key;

    /** 消息列表（追加式） */
    private List<Map<String, Object>> messages = new ArrayList<>();

    /** 创建时间 */
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 更新时间 */
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 元数据 */
    private Map<String, Object> metadata = new HashMap<>();

    /** 已归并到文件的消息数量 */
    private int lastConsolidated = 0;

    public Session(String key) {
        this.key = key;
    }

    public Session(String key,
                   List<Map<String, Object>> messages,
                   LocalDateTime createdAt,
                   LocalDateTime updatedAt,
                   Map<String, Object> metadata,
                   int lastConsolidated) {
        this.key = key;
        if (messages != null) this.messages = messages;
        if (createdAt != null) this.createdAt = createdAt;
        if (updatedAt != null) this.updatedAt = updatedAt;
        if (metadata != null) this.metadata = metadata;
        this.lastConsolidated = Math.max(0, lastConsolidated);
    }

    public String getKey() {
        return key;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = (messages == null) ? new ArrayList<>() : messages;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        if (createdAt != null) this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        if (updatedAt != null) this.updatedAt = updatedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = (metadata == null) ? new HashMap<>() : metadata;
    }

    public int getLastConsolidated() {
        return lastConsolidated;
    }

    public void setLastConsolidated(int lastConsolidated) {
        this.lastConsolidated = Math.max(0, lastConsolidated);
    }

    /**
     * 追加一条消息
     *
     * @param role    角色
     * @param content 内容
     * @param extra   额外字段（可为空）
     */
    public void addMessage(String role, String content, Map<String, Object> extra) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", LocalDateTime.now().toString());
        if (extra != null && !extra.isEmpty()) {
            msg.putAll(extra);
        }
        messages.add(msg);
        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取用于模型输入的历史消息（仅未归并部分）
     *
     * 规则：
     * - 取 messages[lastConsolidated:] 的尾部 maxMessages 条
     * - 丢弃开头连续的非 user 消息，避免孤立的工具输出块
     * - 仅输出 role/content 以及 tool_calls/tool_call_id/name（若存在）
     */
    public List<Map<String, Object>> getHistory(int maxMessages) {
        int start = Math.min(lastConsolidated, messages.size());
        List<Map<String, Object>> unconsolidated = messages.subList(start, messages.size());

        int from = Math.max(0, unconsolidated.size() - Math.max(0, maxMessages));
        List<Map<String, Object>> sliced = new ArrayList<>(unconsolidated.subList(from, unconsolidated.size()));

        for (int i = 0; i < sliced.size(); i++) {
            Object role = sliced.get(i).get("role");
            if ("user".equals(role)) {
                if (i > 0) sliced = new ArrayList<>(sliced.subList(i, sliced.size()));
                break;
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : sliced) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", m.get("role"));
            entry.put("content", m.getOrDefault("content", ""));

            if (m.containsKey("tool_calls")) entry.put("tool_calls", m.get("tool_calls"));
            if (m.containsKey("tool_call_id")) entry.put("tool_call_id", m.get("tool_call_id"));
            if (m.containsKey("name")) entry.put("name", m.get("name"));

            out.add(entry);
        }
        return out;
    }

    /**
     * 清空会话
     */
    public void clear() {
        this.messages = new ArrayList<>();
        this.lastConsolidated = 0;
        this.updatedAt = LocalDateTime.now();
    }
}