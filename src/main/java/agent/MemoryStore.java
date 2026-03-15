package agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import memory.EmbeddingProvider;
import memory.MemorySearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import session.Session;
import utils.Helpers;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 持久化记忆系统：
 * - MEMORY.md：长期事实（覆盖写）
 * - HISTORY.md：可检索历史日志（追加写）
 * - memory/*.md 或子目录：支持多文件组织（向量/关键词搜索）
 *
 * 工作方式：
 * - 把一段旧对话整理成可读文本
 * - 让模型通过工具调用 save_memory 返回：
 *   1) history_entry：追加写入 HISTORY.md
 *   2) memory_update：写入 MEMORY.md（若与当前不同才写）
 *
 * 搜索能力：
 * - 向量搜索（可选，需配置 embeddingProvider）
 * - 关键词搜索（FTS5，默认启用）
 * - 混合搜索（结合向量和关键词）
 */
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 虚拟工具：要求模型以工具调用形式返回记忆压缩结果
     */
    private static final List<Map<String, Object>> SAVE_MEMORY_TOOL = buildSaveMemoryTool();

    private final Path workspaceDir;
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;

    /** 记忆搜索工具（支持 memory 目录下多文件） */
    private MemorySearchTool searchTool;

    public MemoryStore(Path workspace) {
        this.workspaceDir = Objects.requireNonNull(workspace, "workspace");
        // 确保 memory 目录存在
        this.memoryDir = Helpers.ensureDir(workspaceDir.resolve("memory"));
        this.memoryFile = this.memoryDir.resolve("MEMORY.md");
        this.historyFile = this.memoryDir.resolve("HISTORY.md");
    }

    /**
     * 读取长期记忆（不存在则返回空串）
     */
    public String readLongTerm() {
        if (!Files.exists(memoryFile)) return "";
        try {
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 写入长期记忆（覆盖写）
     */
    public void writeLongTerm(String content) {
        try {
            Files.writeString(
                    memoryFile,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            throw new RuntimeException("写入 MEMORY.md 失败", e);
        }
    }

    /**
     * 追加历史日志（每条后面空一行，便于人读和 grep）
     */
    public void appendHistory(String entry) {
        try {
            Files.createDirectories(historyFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(
                    historyFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                w.write(rstrip(entry));
                w.newLine();
                w.newLine();
            }
        } catch (Exception e) {
            throw new RuntimeException("追加写入 HISTORY.md 失败", e);
        }
    }

    /**
     * 返回注入到系统提示词的记忆片段
     */
    public String getMemoryContext() {
        String longTerm = readLongTerm();
        return (longTerm != null && !longTerm.isEmpty())
                ? "## Long-term Memory\n" + longTerm
                : "";
    }

    /**
     * 将旧消息压缩到 MEMORY.md + HISTORY.md
     *
     * 行为说明：
     * - archiveAll=true：压缩全部消息，不保留尾部窗口，lastConsolidated 最终置 0
     * - archiveAll=false：保留 memoryWindow/2 条最新消息，其余按 lastConsolidated 指针开始压缩
     *
     * 新增功能（对齐 OpenClaw）：
     * - 分块压缩：超长对话分块处理，避免超出模型限制
     * - 工具调用配对修复：修剪历史时自动修复孤立的 tool_result
     * - 标识符保留：压缩时保留 UUID、API key 等关键标识符
     *
     * @param session      会话对象（需要：messages + lastConsolidated 可读写）
     * @param provider     模型提供者
     * @param model        模型名
     * @param archiveAll   是否归档全部
     * @param memoryWindow 记忆窗口（默认 50 一类）
     * @return 成功（含无操作）返回 true；失败返回 false
     */
    public CompletableFuture<Boolean> consolidate(
            Session session,
            LLMProvider provider,
            String model,
            int maxTokens,
            double temperature,
            boolean archiveAll,
            int memoryWindow
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");

        final List<Map<String, Object>> oldMessages;
        final int keepCount;

        if (archiveAll) {
            oldMessages = session.getMessages();
            keepCount = 0;
            log.info("记忆压缩（归档全部）：{} 条消息", session.getMessages().size());
        } else {
            keepCount = memoryWindow / 2;

            // 消息不足以产生“可压缩区间”
            if (session.getMessages().size() <= keepCount) {
                return CompletableFuture.completedFuture(true);
            }

            // 没有新增可压缩内容
            if (session.getMessages().size() - session.getLastConsolidated() <= 0) {
                return CompletableFuture.completedFuture(true);
            }

            int from = session.getLastConsolidated();
            int toExclusive = session.getMessages().size() - keepCount;

            if (toExclusive <= from) {
                return CompletableFuture.completedFuture(true);
            }

            oldMessages = session.getMessages().subList(from, toExclusive);

            if (oldMessages.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }

            log.info("记忆压缩：压缩 {} 条，保留 {} 条", oldMessages.size(), keepCount);
        }

        // ========== 新增：工具调用配对修复 ==========
        // 移除孤立的 tool_result（其对应的 tool_use 已被丢弃）
        var repairResult = MemoryCompaction.repairToolUseResultPairing(new ArrayList<>(oldMessages));
        List<Map<String, Object>> repairedMessages = repairResult.messages;
        if (repairResult.droppedOrphanCount > 0) {
            log.info("修复了 {} 个孤立的 tool_result", repairResult.droppedOrphanCount);
        }

        // ========== 新增：分块压缩 ==========
        // 估算 token 数，如果超出限制则分块处理
        int estimatedTokens = MemoryCompaction.estimateMessagesTokens(repairedMessages);
        int maxChunkTokens = maxTokens - MemoryCompaction.SUMMARIZATION_OVERHEAD_TOKENS;

        if (estimatedTokens > maxChunkTokens) {
            log.info("消息总 token 数 {} 超出限制 {}，启用分块压缩", estimatedTokens, maxChunkTokens);
            return consolidateInChunks(session, repairedMessages, provider, model, maxTokens, temperature, archiveAll, keepCount);
        }

        // 正常压缩
        return consolidateSingleChunk(session, repairedMessages, provider, model, maxTokens, temperature, archiveAll, keepCount);
    }

    /**
     * 单块压缩
     */
    private CompletableFuture<Boolean> consolidateSingleChunk(
            Session session,
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int maxTokens,
            double temperature,
            boolean archiveAll,
            int keepCount
    ) {
        // 把待压缩消息转换成可读文本（空 content 直接跳过）
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            if (m == null) continue;

            Object contentObj = m.get("content");
            if (contentObj == null) continue;

            String content = String.valueOf(contentObj);
            if (content.isEmpty()) continue;

            // 时间戳只保留到分钟（便于统一检索）
            String timestamp = safeTsPrefix(m.get("timestamp"));
            String role = String.valueOf(m.getOrDefault("role", "?")).toUpperCase(Locale.ROOT);

            // tools_used：用于检索（可选）
            String toolsSuffix = "";
            Object toolsUsedObj = m.get("tools_used");
            if (toolsUsedObj instanceof List<?> list && !list.isEmpty()) {
                String joined = list.stream().map(String::valueOf).collect(Collectors.joining(", "));
                toolsSuffix = " [tools: " + joined + "]";
            }

            lines.add("[" + timestamp + "] " + role + toolsSuffix + ": " + content);
        }

        String currentMemory = readLongTerm();

        // ========== 新增：标识符保留指令 ==========
        String compactionInstructions = MemoryCompaction.buildCompactionInstructions(null, true);

        // 注意：这里的 prompt 用纯文本拼接，避免 JSON 结构干扰模型
        String prompt = ""
                + "Process this conversation and call the save_memory tool with your consolidation.\n\n"
                + (compactionInstructions != null ? "## Instructions\n" + compactionInstructions + "\n\n" : "")
                + "## Current Long-term Memory\n"
                + ((currentMemory == null || currentMemory.isEmpty()) ? "(empty)" : currentMemory)
                + "\n\n"
                + "## Conversation to Process\n"
                + String.join("\n", lines);

        List<Map<String, Object>> msgList = List.of(
                Map.of(
                        "role", "system",
                        "content", "You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."
                ),
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        );

        // 这里保持默认 max_tokens/temperature，与 provider 默认一致（但显式传入更稳定）
        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, maxTokens, temperature, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("记忆压缩失败", ex);
                        return false;
                    }
                    try {
                        return handleResponse(session, resp, archiveAll, keepCount, currentMemory);
                    } catch (Exception e) {
                        log.error("记忆压缩处理失败", e);
                        return false;
                    }
                });
    }

    /**
     * 分块压缩（对齐 OpenClaw 的 summarizeInStages）
     * 
     * 使用 computeAdaptiveChunkRatio 根据消息大小动态调整分块比例
     */
    private CompletableFuture<Boolean> consolidateInChunks(
            Session session,
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int maxTokens,
            double temperature,
            boolean archiveAll,
            int keepCount
    ) {
        // 使用自适应分块比例
        double adaptiveRatio = MemoryCompaction.computeAdaptiveChunkRatio(messages, maxTokens);
        int adaptiveMaxChunkTokens = Math.max(1, 
                (int) Math.floor(maxTokens * adaptiveRatio) - MemoryCompaction.SUMMARIZATION_OVERHEAD_TOKENS);
        
        List<List<Map<String, Object>>> chunks = MemoryCompaction.chunkMessagesByMaxTokens(messages, adaptiveMaxChunkTokens);

        log.info("分块压缩：{} 条消息分成 {} 块 (adaptiveRatio={})", messages.size(), chunks.size(), adaptiveRatio);

        // 依次压缩每个块，合并结果
        String currentMemory = readLongTerm();
        List<String> partialSummaries = new ArrayList<>();

        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);

        for (int i = 0; i < chunks.size(); i++) {
            final int chunkIndex = i;
            final List<Map<String, Object>> chunk = chunks.get(i);
            final String previousSummary = currentMemory;

            chain = chain.thenCompose(success -> {
                if (!success) return CompletableFuture.completedFuture(false);

                log.info("压缩第 {}/{} 块（{} 条消息）", chunkIndex + 1, chunks.size(), chunk.size());
                return consolidateSingleChunk(session, chunk, provider, model, maxTokens, temperature, archiveAll, keepCount)
                        .thenApply(result -> {
                            if (result) {
                                // 读取更新后的记忆
                                partialSummaries.add(readLongTerm());
                            }
                            return result;
                        });
            });
        }

        // 如果有多个部分摘要，合并它们
        if (chunks.size() > 1) {
            chain = chain.thenCompose(success -> {
                if (!success || partialSummaries.size() <= 1) {
                    return CompletableFuture.completedFuture(success);
                }

                // 合并所有部分摘要
                return mergePartialSummaries(partialSummaries, provider, model, maxTokens, temperature);
            });
        }

        return chain;
    }

    /**
     * 合并部分摘要（对齐 OpenClaw 的 MERGE_SUMMARIES_INSTRUCTIONS）
     */
    private CompletableFuture<Boolean> mergePartialSummaries(
            List<String> partialSummaries,
            LLMProvider provider,
            String model,
            int maxTokens,
            double temperature
    ) {
        String mergeInstructions = ""
                + "Merge these partial summaries into a single cohesive summary.\n\n"
                + "MUST PRESERVE:\n"
                + "- Active tasks and their current status (in-progress, blocked, pending)\n"
                + "- Batch operation progress (e.g., '5/17 items completed')\n"
                + "- The last thing the user requested and what was being done about it\n"
                + "- Decisions made and their rationale\n"
                + "- TODOs, open questions, and constraints\n"
                + "- Any commitments or follow-ups promised\n"
                + "\n"
                + "PRIORITIZE recent context over older history. The agent needs to know\n"
                + "what it was doing, not just what was discussed.\n\n"
                + MemoryCompaction.IDENTIFIER_PRESERVATION_INSTRUCTIONS;

        StringBuilder prompt = new StringBuilder();
        prompt.append(mergeInstructions).append("\n\n");
        prompt.append("## Partial Summaries to Merge\n\n");

        for (int i = 0; i < partialSummaries.size(); i++) {
            prompt.append("### Summary ").append(i + 1).append("\n");
            prompt.append(partialSummaries.get(i)).append("\n\n");
        }

        List<Map<String, Object>> msgList = List.of(
                Map.of("role", "system", "content", "You are a memory consolidation agent. Merge the partial summaries."),
                Map.of("role", "user", "content", prompt.toString())
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, maxTokens, temperature, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("合并部分摘要失败", ex);
                        return false;
                    }
                    try {
                        if (resp != null && resp.hasToolCalls() && !resp.getToolCalls().isEmpty()) {
                            var toolCall = resp.getToolCalls().get(0);
                            Object argsObj = toolCall.getArguments();
                            Map<String, Object> args;
                            if (argsObj instanceof String s) {
                                args = MAPPER.readValue(s, new TypeReference<>() {});
                            } else if (argsObj instanceof Map<?, ?> m) {
                                args = castToStringObjectMap(m);
                            } else {
                                return false;
                            }

                            Object memoryUpdateObj = args.get("memory_update");
                            if (memoryUpdateObj != null) {
                                String update = (memoryUpdateObj instanceof String)
                                        ? (String) memoryUpdateObj
                                        : MAPPER.writeValueAsString(memoryUpdateObj);
                                writeLongTerm(update);
                                log.info("合并部分摘要完成");
                                return true;
                            }
                        }
                        return false;
                    } catch (Exception e) {
                        log.error("处理合并结果失败", e);
                        return false;
                    }
                });
    }

    /**
     * 带回退的压缩（对齐 OpenClaw 的 summarizeWithFallback）
     * 
     * 如果完整压缩失败，尝试跳过超大消息后再压缩
     * 
     * @param session 会话对象
     * @param messages 消息列表
     * @param provider 模型提供者
     * @param model 模型名
     * @param maxTokens 最大 token 数
     * @param temperature 温度
     * @param archiveAll 是否归档全部
     * @param keepCount 保留数量
     * @param contextWindow 上下文窗口大小
     * @return 压缩结果
     */
    private CompletableFuture<Boolean> summarizeWithFallback(
            Session session,
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int maxTokens,
            double temperature,
            boolean archiveAll,
            int keepCount,
            int contextWindow
    ) {
        // 首先尝试完整压缩
        return consolidateSingleChunk(session, messages, provider, model, maxTokens, temperature, archiveAll, keepCount)
                .thenCompose(result -> {
                    if (result) {
                        return CompletableFuture.completedFuture(true);
                    }
                    
                    // 完整压缩失败，尝试跳过超大消息
                    log.warn("完整压缩失败，尝试跳过超大消息");
                    
                    List<Map<String, Object>> smallMessages = new ArrayList<>();
                    List<String> oversizedNotes = new ArrayList<>();
                    
                    for (Map<String, Object> msg : messages) {
                        if (MemoryCompaction.isOversizedForSummary(msg, contextWindow)) {
                            Object role = msg.get("role");
                            String roleStr = role != null ? String.valueOf(role) : "message";
                            int tokens = MemoryCompaction.estimateTokens(msg);
                            oversizedNotes.add(String.format(
                                    "[Large %s (~%dK tokens) omitted from summary]",
                                    roleStr, tokens / 1000));
                        } else {
                            smallMessages.add(msg);
                        }
                    }
                    
                    if (smallMessages.isEmpty()) {
                        log.warn("所有消息都超大，无法压缩");
                        return CompletableFuture.completedFuture(false);
                    }
                    
                    if (smallMessages.size() == messages.size()) {
                        // 没有超大消息，无需重试
                        return CompletableFuture.completedFuture(false);
                    }
                    
                    log.info("跳过 {} 个超大消息，尝试压缩 {} 个小消息",
                            messages.size() - smallMessages.size(), smallMessages.size());
                    
                    return consolidateSingleChunk(session, smallMessages, provider, model,
                            maxTokens, temperature, archiveAll, keepCount);
                });
    }

    /**
     * 处理模型返回的工具调用结果
     */
    private boolean handleResponse(
            Session session,
            LLMResponse response,
            boolean archiveAll,
            int keepCount,
            String currentMemory
    ) throws Exception {
        if (response == null || !response.hasToolCalls() || response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            log.warn("记忆压缩：模型未调用 save_memory，跳过");
            return false;
        }

        // 只取第一个工具调用
        var toolCall = response.getToolCalls().get(0);

        // 说明：
        // - 有些提供者会把 arguments 放成 Map（常见）
        // - 也可能放成 JSON 字符串（少数实现）
        // - 这里都做兼容
        Object argsObj = toolCall.getArguments();

        Map<String, Object> args;
        if (argsObj instanceof String s) {
            args = MAPPER.readValue(s, new TypeReference<>() {});
        } else if (argsObj instanceof Map<?, ?> m) {
            args = castToStringObjectMap(m);
        } else {
            // 如果 ToolCallRequest 里 arguments 固定就是 Map，这里基本不会触发
            log.warn("记忆压缩：工具参数类型异常：{}", (argsObj == null ? "null" : argsObj.getClass().getName()));
            return false;
        }

        // history_entry：写入 HISTORY.md（非字符串则转 JSON 保留信息）
        Object historyEntryObj = args.get("history_entry");
        if (historyEntryObj != null) {
            String entry = (historyEntryObj instanceof String)
                    ? (String) historyEntryObj
                    : MAPPER.writeValueAsString(historyEntryObj);
            appendHistory(entry);
        }

        // memory_update：写入 MEMORY.md（若与当前不同才写，避免无意义覆盖）
        Object memoryUpdateObj = args.get("memory_update");
        if (memoryUpdateObj != null) {
            String update = (memoryUpdateObj instanceof String)
                    ? (String) memoryUpdateObj
                    : MAPPER.writeValueAsString(memoryUpdateObj);

            if (!Objects.equals(update, currentMemory)) {
                writeLongTerm(update);
            }
        }

        // 更新 lastConsolidated 指针：
        // - archiveAll：归档后指针归零
        // - 否则：指向“被保留窗口”的起点
        int newLastConsolidated = archiveAll ? 0 : (session.getMessages().size() - keepCount);
        session.setLastConsolidated(newLastConsolidated);

        log.info("记忆压缩完成：总消息 {}，last_consolidated={}", session.getMessages().size(), session.getLastConsolidated());
        return true;
    }

    /**
     * 对不同 LLMProvider.chat 签名做兼容：
     * - 若存在 6 参版本（含 reasoning_effort），优先调用
     * - 否则回退到 5 参版本
     */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<LLMResponse> chatCompat(
            LLMProvider provider,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        return provider.chatWithRetry(messages, tools, model, maxTokens, temperature, reasoningEffort);
    }

    /**
     * 把任意 Map 的 key 转成字符串，便于后续读取
     */
    private static Map<String, Object> castToStringObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 时间戳只取前 16 个字符（通常是 YYYY-MM-DD HH:MM）
     */
    private static String safeTsPrefix(Object tsObj) {
        if (tsObj == null) return "?";
        String ts = String.valueOf(tsObj);
        return ts.length() >= 16 ? ts.substring(0, 16) : ts;
    }

    /**
     * 去掉字符串末尾的空白字符（用于 history 追加写）
     */
    private static String rstrip(String s) {
        if (s == null) return "";
        int i = s.length() - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i--;
            else break;
        }
        return s.substring(0, i + 1);
    }

    /**
     * 构造 save_memory 工具 schema
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildSaveMemoryTool() {
        Map<String, Object> historyEntry = new LinkedHashMap<>();
        historyEntry.put("type", "string");
        historyEntry.put("description",
                "A paragraph (2-5 sentences) summarizing key events/decisions/topics. " +
                        "Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search.");

        Map<String, Object> memoryUpdate = new LinkedHashMap<>();
        memoryUpdate.put("type", "string");
        memoryUpdate.put("description",
                "Full updated long-term memory as markdown. Include all existing facts plus new ones. " +
                        "Return unchanged if nothing new.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("history_entry", historyEntry);
        properties.put("memory_update", memoryUpdate);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("history_entry", "memory_update"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "save_memory");
        function.put("description", "Save the memory consolidation result to persistent storage.");
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return List.of(tool);
    }

    // ==================== 搜索功能 ====================

    /**
     * 搜索记忆
     *
     * @param query 查询文本
     * @return 搜索结果
     */
    public List<MemorySearchTool.SearchResult> search(String query) {
        try {
            ensureSearchToolInitialized();
            return searchTool.search(query);
        } catch (Exception e) {
            log.warn("记忆搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 搜索记忆
     *
     * @param query      查询文本
     * @param maxResults 最大结果数
     * @param minScore   最小分数
     * @return 搜索结果
     */
    public List<MemorySearchTool.SearchResult> search(String query, int maxResults, double minScore) {
        try {
            ensureSearchToolInitialized();
            return searchTool.search(query, maxResults, minScore);
        } catch (Exception e) {
            log.warn("记忆搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 在历史日志中搜索（grep）
     *
     * @param keyword 关键词
     * @return 匹配的行
     */
    public List<String> grepHistory(String keyword) {
        try {
            ensureSearchToolInitialized();
            return searchTool.grepHistory(keyword);
        } catch (Exception e) {
            log.warn("历史搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 同步记忆索引
     */
    public void syncIndex() {
        try {
            ensureSearchToolInitialized();
            searchTool.sync();
        } catch (Exception e) {
            log.warn("同步记忆索引失败", e);
        }
    }

    /**
     * 设置嵌入提供者（可选，用于向量搜索）
     *
     * @param provider 嵌入提供者，null 则使用纯关键词搜索
     */
    public void setEmbeddingProvider(EmbeddingProvider provider) {
        this.searchTool = null; // 重置，下次使用时重新初始化
        if (searchTool != null) {
            searchTool.setEmbeddingProvider(provider);
        }
        // 保存引用，供后续初始化使用
        this.pendingEmbeddingProvider = provider;
    }

    private EmbeddingProvider pendingEmbeddingProvider;

    /**
     * 确保搜索工具已初始化
     */
    private synchronized void ensureSearchToolInitialized() throws Exception {
        if (searchTool == null) {
            searchTool = new MemorySearchTool(workspaceDir);
            if (pendingEmbeddingProvider != null) {
                searchTool.setEmbeddingProvider(pendingEmbeddingProvider);
            }
            searchTool.initialize();
        }
    }

    /**
     * 关闭资源
     */
    public void close() {
        if (searchTool != null) {
            searchTool.close();
            searchTool = null;
        }
    }
}