package session;

import agent.Usage;
import agent.UsageAccumulator;

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

    /**
     * 生成 UUID v4 作为会话唯一标识
     */
    public static String generateSessionId() {
        return java.util.UUID.randomUUID().toString();
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

    // ==================== 上一次对话的上下文大小（用于判断压缩必要性） ====================

    /** 上一次对话的输入 token 数 */
    private int lastCallInput = 0;

    /** 上一次对话的输出 token 数 */
    private int lastCallOutput = 0;

    /** 上一次对话的缓存读取 token 数 */
    private int lastCallCacheRead = 0;

    /** 上一次对话的缓存写入 token 数 */
    private int lastCallCacheWrite = 0;

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
    public List<Map<String, Object>> getHistory() {
        return messages;
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

    // ==================== 上一次对话的上下文大小 ====================

    public int getLastCallInput() { return lastCallInput; }
    public void setLastCallInput(int lastCallInput) { this.lastCallInput = lastCallInput; }

    public int getLastCallOutput() { return lastCallOutput; }
    public void setLastCallOutput(int lastCallOutput) { this.lastCallOutput = lastCallOutput; }

    public int getLastCallCacheRead() { return lastCallCacheRead; }
    public void setLastCallCacheRead(int lastCallCacheRead) { this.lastCallCacheRead = lastCallCacheRead; }

    public int getLastCallCacheWrite() { return lastCallCacheWrite; }
    public void setLastCallCacheWrite(int lastCallCacheWrite) { this.lastCallCacheWrite = lastCallCacheWrite; }

    /**
     * 获取上一次对话的 prompt tokens（上下文大小）
     */
    public long getLastCallPromptTokens() {
        return (long) lastCallInput + lastCallCacheRead + lastCallCacheWrite;
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

    public UsageAccumulator obtainLastUsage() {

        UsageAccumulator accumulator = new UsageAccumulator();
        Usage usage = new Usage();
        // 使用 lastCall 而非累积值，这样 context ratio 反映的是最后一次对话的实际上下文大小
        long promptTokens = getLastCallPromptTokens();
        long totalTokens = promptTokens + lastCallOutput;
        usage.setInput(this.lastCallInput)
                .setTotal(totalTokens)
                .setOutput(this.lastCallOutput)
                .setCacheRead(this.lastCallCacheRead)
                .setCacheWrite(this.lastCallCacheWrite);
        accumulator.accumulate(usage);
        return accumulator;
    }

}