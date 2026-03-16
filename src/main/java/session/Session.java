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
 * - 支持生成唯一的 sessionId（对齐 OpenClaw）
 *
 * Session Key vs Session ID：
 * - sessionKey：用于标识会话路由（如 "cli:direct"），是固定的
 * - sessionId：用于标识具体的会话实例（如 "amber-atlas"），每次新会话生成新的
 */
public final class Session {

    // ==================== Session Slug 生成（对齐 OpenClaw session-slug.ts） ====================

    private static final String[] SLUG_ADJECTIVES = {
        "amber", "briny", "brisk", "calm", "clear", "cool", "crisp", "dawn", "delta", "ember",
        "faint", "fast", "fresh", "gentle", "glow", "good", "grand", "keen", "kind", "lucky",
        "marine", "mellow", "mild", "neat", "nimble", "nova", "oceanic", "plaid", "quick", "quiet",
        "rapid", "salty", "sharp", "swift", "tender", "tidal", "tidy", "tide", "vivid", "warm", "wild", "young"
    };

    private static final String[] SLUG_NOUNS = {
        "atlas", "basil", "bison", "bloom", "breeze", "canyon", "cedar", "claw", "cloud", "comet",
        "coral", "cove", "crest", "crustacean", "daisy", "dune", "ember", "falcon", "fjord", "forest",
        "glade", "gulf", "harbor", "haven", "kelp", "lagoon", "lobster", "meadow", "mist", "nudibranch",
        "nexus", "ocean", "orbit", "otter", "pine", "prairie", "reef", "ridge", "river", "rook",
        "sable", "sage", "seaslug", "shell", "shoal", "shore", "slug", "summit", "tidepool", "trail",
        "valley", "wharf", "willow", "zephyr"
    };

    private static final Random RANDOM = new Random();

    /**
     * 生成唯一的 session slug（对齐 OpenClaw createSessionSlug）
     *
     * @return 格式如 "amber-atlas" 或 "brisk-harbor-2"
     */
    public static String generateSessionId() {
        String adj = SLUG_ADJECTIVES[RANDOM.nextInt(SLUG_ADJECTIVES.length)];
        String noun = SLUG_NOUNS[RANDOM.nextInt(SLUG_NOUNS.length)];
        String base = adj + "-" + noun;

        // 10% 概率添加后缀
        if (RANDOM.nextDouble() < 0.1) {
            return base + "-" + (RANDOM.nextInt(10) + 2);
        }

        return base;
    }

    // ==================== 字段 ====================

    /** 会话标识：channel:chat_id（用于路由） */
    private final String key;

    /** 会话实例 ID：唯一标识一个会话实例（用于文件名） */
    private String sessionId;

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

    // ==================== Usage 跟踪字段（对齐 OpenClaw SessionEntry） ====================

    /** 输入 token 数 */
    private int inputTokens = 0;

    /** 输出 token 数 */
    private int outputTokens = 0;

    /** 总 token 数 */
    private int totalTokens = 0;

    /** 缓存读取 token 数 */
    private int cacheRead = 0;

    /** 缓存写入 token 数 */
    private int cacheWrite = 0;

    /** 模型名称 */
    private String model;

    /** 提供商名称 */
    private String modelProvider;

    // ==================== 构造函数 ====================

    public Session(String key) {
        this.key = key;
        this.sessionId = generateSessionId();
    }

    public Session(String key, String sessionId) {
        this.key = key;
        this.sessionId = sessionId != null ? sessionId : generateSessionId();
    }

    public Session(String key,
                   String sessionId,
                   List<Map<String, Object>> messages,
                   LocalDateTime createdAt,
                   LocalDateTime updatedAt,
                   Map<String, Object> metadata,
                   int lastConsolidated) {
        this.key = key;
        this.sessionId = sessionId != null ? sessionId : generateSessionId();
        if (messages != null) this.messages = messages;
        if (createdAt != null) this.createdAt = createdAt;
        if (updatedAt != null) this.updatedAt = updatedAt;
        if (metadata != null) this.metadata = metadata;
        this.lastConsolidated = Math.max(0, lastConsolidated);
    }

    // ==================== Getter/Setter ====================

    public String getKey() {
        return key;
    }

    /**
     * 获取会话实例 ID
     *
     * 用于标识具体的会话实例，每次新会话生成新的 sessionId
     * 文件名格式：{sessionId}.jsonl
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 设置会话实例 ID
     *
     * 用于从文件加载时恢复 sessionId
     */
    public void setSessionId(String sessionId) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            this.sessionId = sessionId.trim();
        }
    }

    /**
     * 生成新的 sessionId（用于 /new 命令）
     */
    public void renewSessionId() {
        this.sessionId = generateSessionId();
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

    // ==================== Usage Getter/Setter ====================

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getCacheRead() {
        return cacheRead;
    }

    public void setCacheRead(int cacheRead) {
        this.cacheRead = cacheRead;
    }

    public int getCacheWrite() {
        return cacheWrite;
    }

    public void setCacheWrite(int cacheWrite) {
        this.cacheWrite = cacheWrite;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    /**
     * 累加 usage（对齐 OpenClaw mergeUsageIntoAccumulator）
     */
    public void addUsage(int input, int output, int cacheRead, int cacheWrite) {
        this.inputTokens += input;
        this.outputTokens += output;
        this.cacheRead += cacheRead;
        this.cacheWrite += cacheWrite;
        this.totalTokens = this.inputTokens + this.outputTokens + this.cacheRead + this.cacheWrite;
    }
}