package memory;

import agent.tool.FileSystemTools;
import agent.tool.Tool;
import agent.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import context.ContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import providers.ToolCallRequest;
import session.Session;
import utils.Helpers;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 持久化记忆系统（对齐 OpenClaw 架构）：
 * - MEMORY.md：长期记忆（自动压缩生成）
 * - memory/YYYY-MM-DD.md：每日文件（原始日志）
 * - memory/semantic/patterns.json：语义记忆（抽象模式）
 * - memory/episodic/YYYY/：情景记忆（具体经验）
 *
 * 工作方式：
 * - 日常对话 → 记录到 memory/YYYY-MM-DD.md
 * - 对话历史摘要总结 → 更新 MEMORY.md
 * - 心跳任务 → 定期整理每日文件到 MEMORY.md，清理旧文件
 *
 * 搜索能力：
 * - 向量搜索（可选，需配置 embeddingProvider）
 * - 关键词搜索（FTS5，默认启用）
 * - 混合搜索（结合向量和关键词）
 */
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 工具 1：消息修剪 - 分析消息，决定哪些可以丢弃
     */
    private static final List<Map<String, Object>> PRUNE_MESSAGES_TOOL = buildPruneMessagesTool();

    /**
     * 工具 2：更新记忆 - 更新长期记忆内容（完整替换模式）
     */
    private static final List<Map<String, Object>> UPDATE_MEMORY_TOOL = buildUpdateMemoryTool();

    /**
     * 系统提示词：消息修剪
     */
    private static final String PRUNE_SYSTEM_PROMPT = buildPruneSystemPrompt();

    /**
     * 系统提示词：更新记忆
     */
    private static final String UPDATE_MEMORY_SYSTEM_PROMPT = buildUpdateMemorySystemPrompt();

    /**
     * 旧工具：用于心跳整理等场景
     */
    private static final List<Map<String, Object>> SAVE_MEMORY_TOOL = buildSaveMemoryTool();

    private final Path workspaceDir;
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path semanticDir;
    private final Path episodicDir;

    /** 记忆搜索工具（支持 memory 目录下多文件） */
    private MemorySearch searchTool;

    private final ContextBuilder contextBuilder;

    public MemoryStore(Path workspace, ContextBuilder contextBuilder) {
        this.workspaceDir = Objects.requireNonNull(workspace, "workspace");
        // 确保 memory 目录存在
        this.memoryDir = Helpers.ensureDir(workspaceDir.resolve("memory"));
        this.memoryFile = this.memoryDir.resolve("MEMORY.md");
        // 确保多记忆架构目录存在
        this.semanticDir = Helpers.ensureDir(memoryDir.resolve("semantic"));
        this.episodicDir = Helpers.ensureDir(memoryDir.resolve("episodic"));
        Helpers.ensureDir(episodicDir.resolve(String.valueOf(LocalDate.now().getYear())));
        this.searchTool = null;
        this.contextBuilder = contextBuilder;
    }

    // ==================== 每日文件管理 ====================

    /**
     * 获取今日每日文件路径
     */
    public Path getTodayFilePath() {
        String fileName = LocalDate.now().format(DATE_FORMATTER) + ".md";
        return memoryDir.resolve(fileName);
    }

    /**
     * 获取指定日期的每日文件路径
     */
    public Path getDailyFilePath(LocalDate date) {
        String fileName = date.format(DATE_FORMATTER) + ".md";
        return memoryDir.resolve(fileName);
    }

    /**
     * 追加内容到今日每日文件
     */
    public void appendToToday(String content) {
        appendToDailyFile(LocalDate.now(), content);
    }

    /**
     * 追加内容到指定日期的每日文件
     */
    public void appendToDailyFile(LocalDate date, String content) {
        Path filePath = getDailyFilePath(date);
        try {
            Files.createDirectories(filePath.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(
                    filePath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                String real = rstrip(content);
                String findContent = """
                        ```
                        %s
                        ```
                        
                        """.formatted(real);
                w.write(findContent);
            }
        } catch (Exception e) {
            throw new RuntimeException("追加写入每日文件失败: " + filePath, e);
        }
    }

    /**
     * 读取今日每日文件内容
     */
    public String readTodayFile() {
        return readDailyFile(LocalDate.now());
    }

    /**
     * 读取指定日期的每日文件内容
     */
    public String readDailyFile(LocalDate date) {
        Path filePath = getDailyFilePath(date);
        if (!Files.exists(filePath)) return "";
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 获取最近 N 天的每日文件列表（从新到旧）
     */
    public List<Path> getRecentDailyFiles(int days) {
        List<Path> files = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            Path file = getDailyFilePath(today.minusDays(i));
            if (Files.exists(file)) {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * 获取所有每日文件（从新到旧）
     */
    public List<Path> getAllDailyFiles() {
        try {
            if (!Files.exists(memoryDir)) {
                return List.of();
            }
            return Files.list(memoryDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("获取每日文件列表失败", e);
            return List.of();
        }
    }

    /**
     * 清理超过指定天数的每日文件
     */
    public int cleanupOldDailyFiles(int keepDays) {
        LocalDate cutoff = LocalDate.now().minusDays(keepDays);
        int deleted = 0;
        try {
            if (!Files.exists(memoryDir)) {
                return 0;
            }
            for (Path file : Files.list(memoryDir).toList()) {
                String name = file.getFileName().toString();
                if (name.matches("\\d{4}-\\d{2}-\\d{2}\\.md")) {
                    try {
                        LocalDate fileDate = LocalDate.parse(name.replace(".md", ""), DATE_FORMATTER);
                        if (fileDate.isBefore(cutoff)) {
                            Files.delete(file);
                            deleted++;
                            log.debug("删除旧每日文件: {}", name);
                        }
                    } catch (Exception e) {
                        // 忽略解析失败的文件
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理旧每日文件失败", e);
        }
        return deleted;
    }

    // ==================== 长期记忆管理 ====================

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
     * 读取长期记忆 前两百行
     */
    public String readLongTermShort() {
        if (!Files.exists(memoryFile)) return "";
        try {
            String context = Files.readString(memoryFile, StandardCharsets.UTF_8);
            String firstNLines = FileSystemTools.firstNLines(context, 200);
            return firstNLines;
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
     * 返回注入到系统提示词的记忆片段
     */
    public String getMemoryContext() {
        String longTerm = readLongTerm();
        return (longTerm != null && !longTerm.isEmpty())
                ? "## Long-term Memory\n" + longTerm
                : "";
    }

    // ==================== 对话历史压缩（新架构） ====================

    /**
     * 将旧消息压缩到 MEMORY.md（两阶段流程）
     *
     * 阶段 1：prune_messages - 分析消息，决定哪些可以丢弃
     * 阶段 2：update_memory - 更新长期记忆内容
     *
     * @param session      会话对象
     * @param provider     模型提供者
     * @param model        模型名
     * @param contextWindow 最大 token 数
     * @param temperature  温度
     * @param archiveAll   是否归档全部
     * @param memoryWindow 记忆窗口
     * @return 成功返回 true
     */
    public CompletableFuture<Boolean> consolidate(
            Session session,
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            boolean archiveAll,
            int memoryWindow
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");

        final List<Map<String, Object>> messages = session.getMessages();
        final int keepCount = archiveAll ? 0 : memoryWindow / 2;

        // 消息不足以压缩
        if (messages.size() <= keepCount) {
            log.info("消息不足以压缩，跳过");
            return CompletableFuture.completedFuture(true);
        }

        // 没有新增可压缩内容
        if (session.getLastConsolidated() >= messages.size() - keepCount) {
            log.info("没有新增可压缩内容，跳过");
            return CompletableFuture.completedFuture(true);
        }

        // 待压缩消息
        int from = session.getLastConsolidated();
        int toExclusive = messages.size() - keepCount;
        List<Map<String, Object>> toConsolidate = new ArrayList<>(messages.subList(from, toExclusive));

        if (toConsolidate.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        log.info("记忆压缩：压缩 {} 条消息（索引 {} 到 {}）", toConsolidate.size(), from, toExclusive - 1);

        // 修复孤立工具调用配对
        var repairResult = MemoryCompaction.repairToolUseResultPairing(toConsolidate);
        List<Map<String, Object>> repairedMessages = repairResult.messages;
        if (repairResult.droppedOrphanCount > 0) {
            log.info("修复了 {} 个孤立的 tool_result", repairResult.droppedOrphanCount);
        }

        // 读取当前 MEMORY.md
        String currentMemory = readLongTerm();

        // 阶段 1：调用 prune_messages
        PruneResult pruneResult = callPruneMessages(provider, model, contextWindow, temperature, repairedMessages);
        if (pruneResult == null) {
            log.warn("prune_messages 返回空结果，使用传统压缩");
            return fallbackConsolidate(session, repairedMessages, provider, model, contextWindow, temperature, archiveAll, keepCount, currentMemory);
        }

        // 执行消息删除
        List<Map<String, Object>> importantMessages = executePrune(session, pruneResult, from, repairedMessages);

        // 阶段 2：调用 update_memory
        return callUpdateMemory(provider, model, contextWindow, temperature, currentMemory, importantMessages)
                .thenApply(updateSuccess -> {
                    if (updateSuccess) {
                        // 更新 lastConsolidated
                        session.setLastConsolidated(session.getMessages().size());
                        log.info("记忆压缩完成，Session 剩余 {} 条消息", session.getMessages().size());
                    }
                    return updateSuccess;
                });
    }

    /**
     * 阶段 1：调用 prune_messages 工具
     */
    private PruneResult callPruneMessages(
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            List<Map<String, Object>> messages
    ) {
        // 构建消息列表摘要
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下消息，决定哪些可以删除：\n\n");
        for (int i = 0; i < messages.size(); i++) {
            sb.append("[").append(i).append("] ");
            Map<String, Object> msg = messages.get(i);
            sb.append(msg.getOrDefault("role", "?")).append(": ");
            Object content = msg.get("content");
            if (content instanceof String s) {
                sb.append(truncate(s, 200));
            } else {
                sb.append("[复杂内容]");
            }
            if (msg.containsKey("tool_calls")) {
                sb.append(" [tool_calls]");
            }
            if (msg.containsKey("tool_call_id")) {
                sb.append(" [tool_result]");
            }
            sb.append("\n");
        }

        List<Map<String, Object>> promptMessages = List.of(
                Map.of("role", "system", "content", PRUNE_SYSTEM_PROMPT),
                Map.of("role", "user", "content", sb.toString())
        );

        try {
            LLMResponse response = chatCompat(provider, promptMessages, PRUNE_MESSAGES_TOOL, model, contextWindow, temperature, null).join();
            return parsePruneResult(response, messages.size());
        } catch (Exception e) {
            log.error("调用 prune_messages 失败", e);
            return null;
        }
    }

    /**
     * 解析 prune_messages 工具返回结果
     */
    private PruneResult parsePruneResult(LLMResponse response, int totalMessages) {
        if (response == null || !response.hasToolCalls() || response.getToolCalls().isEmpty()) {
            return null;
        }

        var toolCall = response.getToolCalls().get(0);
        if (!"prune_messages".equals(toolCall.getName())) {
            return null;
        }

        try {
            Map<String, Object> args = parseToolCallArguments(toolCall);

            List<Integer> droppedIndices = parseIntegerList(args.get("dropped_indices"));
            List<Integer> importantIndices = parseIntegerList(args.get("important_indices"));
            List<Map<String, Object>> patterns = parsePatterns(args.get("patterns_detected"));
            String reasoning = (String) args.get("reasoning");

            // 验证索引范围
            if (droppedIndices != null) {
                droppedIndices = droppedIndices.stream()
                        .filter(i -> i >= 0 && i < totalMessages)
                        .toList();
            }

            return new PruneResult(droppedIndices, importantIndices, patterns, reasoning);
        } catch (Exception e) {
            log.warn("解析 prune_messages 结果失败", e);
            return null;
        }
    }

    /**
     * 执行消息删除
     */
    private List<Map<String, Object>> executePrune(
            Session session,
            PruneResult pruneResult,
            int fromIndex,
            List<Map<String, Object>> toConsolidate
    ) {
        List<Map<String, Object>> sessionMessages = session.getMessages();
        List<Integer> droppedIndices = pruneResult.droppedIndices();

        // 收集重要消息内容
        List<Map<String, Object>> importantMessages = new ArrayList<>();
        if (pruneResult.importantIndices() != null) {
            for (int idx : pruneResult.importantIndices()) {
                if (idx >= 0 && idx < toConsolidate.size()) {
                    importantMessages.add(toConsolidate.get(idx));
                }
            }
        }

        // 从后往前删除，避免索引变化
        if (droppedIndices != null && !droppedIndices.isEmpty()) {
            List<Integer> sortedIndices = droppedIndices.stream()
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .toList();

            int deletedCount = 0;
            for (int idx : sortedIndices) {
                if (idx >= 0 && idx < toConsolidate.size()) {
                    int actualIndex = fromIndex + idx - deletedCount;
                    if (actualIndex >= 0 && actualIndex < sessionMessages.size()) {
                        sessionMessages.remove(actualIndex);
                        deletedCount++;
                    }
                }
            }
            log.info("从 Session 中删除了 {} 条消息，原因：{}", deletedCount, pruneResult.reasoning());
        }

        // 保存模式
        if (pruneResult.patterns() != null && !pruneResult.patterns().isEmpty()) {
            savePatterns(pruneResult.patterns());
            log.info("保存了 {} 个模式", pruneResult.patterns().size());
        }

        return importantMessages;
    }

    /**
     * 阶段 2：调用 update_memory 工具
     */
    private CompletableFuture<Boolean> callUpdateMemory(
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            String currentMemory,
            List<Map<String, Object>> importantMessages
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前长期记忆\n\n");
        sb.append(currentMemory == null || currentMemory.isEmpty() ? "(空)" : currentMemory);
        sb.append("\n\n## 本次重要消息\n\n");

        if (importantMessages.isEmpty()) {
            sb.append("（无特别重要的消息）\n");
        } else {
            for (Map<String, Object> msg : importantMessages) {
                sb.append("- [").append(msg.getOrDefault("role", "?")).append("] ");
                Object content = msg.get("content");
                if (content instanceof String s) {
                    sb.append(truncate(s, 300));
                } else {
                    sb.append("[复杂内容]");
                }
                sb.append("\n");
            }
        }

        sb.append("\n请更新长期记忆。");

        List<Map<String, Object>> promptMessages = List.of(
                Map.of("role", "system", "content", UPDATE_MEMORY_SYSTEM_PROMPT),
                Map.of("role", "user", "content", sb.toString())
        );

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new FileSystemTools.ReadFileTool(workspaceDir, null));
        List<Map<String, Object>> definitions = toolRegistry.getDefinitions();
        definitions.addAll(UPDATE_MEMORY_TOOL);

        return chatCompat(provider, promptMessages, definitions, model, contextWindow, temperature, null)
                .thenApply(response -> {
                    if (response == null || !response.hasToolCalls() || response.getToolCalls().isEmpty()) {
                        log.warn("update_memory 未返回工具调用");
                        return false;
                    }

                    var toolCall = response.getToolCalls().get(0);
                    if (!"update_memory".equals(toolCall.getName())) {
                        return false;
                    }

                    try {
                        Map<String, Object> args = parseToolCallArguments(toolCall);
                        String memoryUpdate = (String) args.get("memory_update");
                        if (memoryUpdate != null && !memoryUpdate.isBlank()) {
                            writeLongTerm(memoryUpdate);
                            log.info("已更新 MEMORY.md");
                            return true;
                        }
                    } catch (Exception e) {
                        log.error("解析 update_memory 结果失败", e);
                    }
                    return false;
                });
    }

    /**
     * 传统压缩（回退方案）
     */
    private CompletableFuture<Boolean> fallbackConsolidate(
            Session session,
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            boolean archiveAll,
            int keepCount,
            String currentMemory
    ) {
        return consolidateSingleChunk(session, messages, provider, model, contextWindow, temperature, archiveAll, keepCount);
    }

    // ==================== 传统压缩方法（保留用于回退和分块压缩） ====================

    /**
     * 单块压缩（传统方式）
     */
    private CompletableFuture<Boolean> consolidateSingleChunk(
            Session session,
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            boolean archiveAll,
            int keepCount
    ) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            if (m == null) continue;

            Object contentObj = m.get("content");
            if (contentObj == null) continue;

            String content = String.valueOf(contentObj);
            if (content.isEmpty()) continue;

            String timestamp = safeTsPrefix(m.get("timestamp"));
            String role = String.valueOf(m.getOrDefault("role", "?")).toUpperCase(Locale.ROOT);

            String toolsSuffix = "";
            Object toolsUsedObj = m.get("tools_used");
            if (toolsUsedObj instanceof List<?> list && !list.isEmpty()) {
                String joined = list.stream().map(String::valueOf).collect(Collectors.joining(", "));
                toolsSuffix = " [tools: " + joined + "]";
            }

            lines.add("[" + timestamp + "] " + role + toolsSuffix + ": " + content);
        }

        String currentMemory = readLongTerm();

        String compactionInstructions = MemoryCompaction.buildCompactionInstructions(null, true);

        String prompt = ""
                + "处理此对话并调用 save_memory 工具保存压缩结果。\n\n"
                + (compactionInstructions != null ? "## 指令\n" + compactionInstructions + "\n\n" : "")
                + "## 当前长期记忆\n"
                + ((currentMemory == null || currentMemory.isEmpty()) ? "(空)" : currentMemory)
                + "\n\n"
                + "## 待处理对话\n"
                + String.join("\n", lines);

        List<Map<String, Object>> msgList = List.of(
                Map.of("role", "system", "content", "你是一个记忆压缩代理。调用 save_memory 工具保存对话的压缩结果。"),
                Map.of("role", "user", "content", prompt)
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, contextWindow, temperature, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("记忆压缩失败", ex);
                        return false;
                    }
                    try {
                        return handleResponse(session, resp, archiveAll, keepCount, currentMemory);
                    } catch (Exception e) {
                        log.error("处理压缩结果失败", e);
                        return false;
                    }
                });
    }

    /**
     * 分块压缩
     */
    private CompletableFuture<Boolean> consolidateInChunks(
            Session session,
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            boolean archiveAll,
            int keepCount
    ) {
        double adaptiveRatio = MemoryCompaction.computeAdaptiveChunkRatio(messages, contextWindow);
        int adaptiveMaxChunkTokens = Math.max(1,
                (int) Math.floor(contextWindow * adaptiveRatio) - MemoryCompaction.SUMMARIZATION_OVERHEAD_TOKENS);

        List<List<Map<String, Object>>> chunks = MemoryCompaction.chunkMessagesByContextWindow(messages, adaptiveMaxChunkTokens);

        log.info("分块压缩：{} 个块", chunks.size());

        List<String> partialSummaries = new ArrayList<>();
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);

        for (List<Map<String, Object>> chunk : chunks) {
            chain = chain.thenCompose(prevSuccess -> {
                if (!prevSuccess) {
                    return CompletableFuture.completedFuture(false);
                }
                return consolidateSingleChunkForPartial(chunk, provider, model, contextWindow, temperature)
                        .thenAccept(partialSummaries::add)
                        .thenApply(v -> true);
            });
        }

        return chain.thenCompose(success -> {
            if (!success || partialSummaries.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            if (partialSummaries.size() == 1) {
                String summary = partialSummaries.get(0);
                writeLongTerm(summary);
                int newLastConsolidated = archiveAll ? 0 : (session.getMessages().size() - keepCount);
                session.setLastConsolidated(newLastConsolidated);
                return CompletableFuture.completedFuture(true);
            }
            return mergePartialSummaries(partialSummaries, provider, model, contextWindow, temperature)
                    .thenApply(merged -> {
                        if (merged) {
                            int newLastConsolidated = archiveAll ? 0 : (session.getMessages().size() - keepCount);
                            session.setLastConsolidated(newLastConsolidated);
                        }
                        return merged;
                    });
        });
    }

    /**
     * 单块压缩（返回部分摘要）
     */
    private CompletableFuture<String> consolidateSingleChunkForPartial(
            List<Map<String, Object>> messages,
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature
    ) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            if (m == null) continue;
            Object contentObj = m.get("content");
            if (contentObj == null) continue;
            String content = String.valueOf(contentObj);
            if (content.isEmpty()) continue;
            String timestamp = safeTsPrefix(m.get("timestamp"));
            String role = String.valueOf(m.getOrDefault("role", "?")).toUpperCase(Locale.ROOT);
            lines.add("[" + timestamp + "] " + role + ": " + content);
        }

        String prompt = ""
                + "总结以下对话片段的关键信息。\n\n"
                + "## 对话片段\n"
                + String.join("\n", lines);

        List<Map<String, Object>> msgList = List.of(
                Map.of("role", "system", "content", "你是一个记忆压缩代理。生成简洁的摘要。"),
                Map.of("role", "user", "content", prompt)
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, contextWindow, temperature, null)
                .thenApply(resp -> {
                    if (resp != null && resp.hasToolCalls() && !resp.getToolCalls().isEmpty()) {
                        var toolCall = resp.getToolCalls().get(0);
                        try {
                            Map<String, Object> args = parseToolCallArguments(toolCall);
                            Object memoryUpdateObj = args.get("memory_update");
                            if (memoryUpdateObj != null) {
                                return (memoryUpdateObj instanceof String)
                                        ? (String) memoryUpdateObj
                                        : MAPPER.writeValueAsString(memoryUpdateObj);
                            }
                        } catch (Exception e) {
                            log.warn("解析部分摘要失败", e);
                        }
                    }
                    return "";
                });
    }

    /**
     * 合并部分摘要
     */
    private CompletableFuture<Boolean> mergePartialSummaries(
            List<String> partialSummaries,
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature
    ) {
        String mergeInstructions = ""
                + "将这些部分摘要合并为一个连贯的摘要。\n\n"
                + "必须保留：\n"
                + "- 活动任务及其当前状态\n"
                + "- 批处理进度\n"
                + "- 用户最后请求的内容\n"
                + "- 已做出的决策及其理由\n"
                + "- 待办事项、未决问题和约束条件\n"
                + "\n"
                + MemoryCompaction.IDENTIFIER_PRESERVATION_INSTRUCTIONS;

        StringBuilder prompt = new StringBuilder();
        prompt.append(mergeInstructions).append("\n\n");
        prompt.append("## 待合并的部分摘要\n\n");

        for (int i = 0; i < partialSummaries.size(); i++) {
            prompt.append("### 摘要 ").append(i + 1).append("\n");
            prompt.append(partialSummaries.get(i)).append("\n\n");
        }

        List<Map<String, Object>> msgList = List.of(
                Map.of("role", "system", "content", "你是一个记忆压缩代理。合并这些部分摘要。"),
                Map.of("role", "user", "content", prompt.toString())
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, contextWindow, temperature, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("合并部分摘要失败", ex);
                        return false;
                    }
                    try {
                        if (resp != null && resp.hasToolCalls() && !resp.getToolCalls().isEmpty()) {
                            var toolCall = resp.getToolCalls().get(0);
                            Map<String, Object> args = parseToolCallArguments(toolCall);
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
     * 处理模型返回的工具调用结果（传统方式）
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

        var toolCall = response.getToolCalls().get(0);
        Map<String, Object> args = parseToolCallArguments(toolCall);

        Object memoryUpdateObj = args.get("memory_update");
        if (memoryUpdateObj != null) {
            String update = (memoryUpdateObj instanceof String)
                    ? (String) memoryUpdateObj
                    : MAPPER.writeValueAsString(memoryUpdateObj);

            if (!Objects.equals(update, currentMemory)) {
                writeLongTerm(update);
            }
        }

        int newLastConsolidated = archiveAll ? 0 : (session.getMessages().size() - keepCount);
        session.setLastConsolidated(newLastConsolidated);

        log.info("记忆压缩完成：总消息 {}，last_consolidated={}", session.getMessages().size(), session.getLastConsolidated());
        return true;
    }

    // ==================== 心跳任务整理 ====================

    /**
     * 心跳任务：整理每日文件到 MEMORY.md
     */
    public CompletableFuture<Boolean> heartbeatConsolidate(
            LLMProvider provider,
            String model,
            int contextWindow,
            double temperature,
            int recentDays,
            int keepDays
    ) {
        List<Path> recentFiles = getRecentDailyFiles(recentDays);
        if (recentFiles.isEmpty()) {
            log.debug("没有需要整理的每日文件");
            return CompletableFuture.completedFuture(true);
        }

        List<String> contents = new ArrayList<>();
        for (Path file : recentFiles) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    String fileName = file.getFileName().toString();
                    contents.add("## " + fileName + "\n\n" + content);
                }
            } catch (Exception e) {
                log.warn("读取每日文件失败: {}", file, e);
            }
        }

        if (contents.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        String currentMemory = readLongTerm();

        String prompt = ""
                + "整理以下每日记录，提取重要信息更新到长期记忆中。\n\n"
                + "## 当前长期记忆\n"
                + ((currentMemory == null || currentMemory.isEmpty()) ? "(空)" : currentMemory)
                + "\n\n"
                + "## 最近每日记录\n"
                + String.join("\n\n", contents)
                + "\n\n"
                + "请调用 save_memory 工具，返回更新后的长期记忆。";

        List<Map<String, Object>> msgList = List.of(
                Map.of("role", "system", "content", "你是一个记忆整理代理。整理每日记录，更新长期记忆。"),
                Map.of("role", "user", "content", prompt)
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, contextWindow, temperature, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("心跳整理失败", ex);
                        return false;
                    }
                    try {
                        if (resp != null && resp.hasToolCalls() && !resp.getToolCalls().isEmpty()) {
                            var toolCall = resp.getToolCalls().get(0);
                            Map<String, Object> args = parseToolCallArguments(toolCall);
                            Object memoryUpdateObj = args.get("memory_update");
                            if (memoryUpdateObj != null) {
                                String update = (memoryUpdateObj instanceof String)
                                        ? (String) memoryUpdateObj
                                        : MAPPER.writeValueAsString(memoryUpdateObj);
                                writeLongTerm(update);
                                log.info("心跳整理完成：更新了 MEMORY.md");
                            }
                        }

                        int deleted = cleanupOldDailyFiles(keepDays);
                        if (deleted > 0) {
                            log.info("清理了 {} 个旧每日文件", deleted);
                        }

                        return true;
                    } catch (Exception e) {
                        log.error("处理心跳整理结果失败", e);
                        return false;
                    }
                });
    }

    // ==================== 搜索功能 ====================

    /**
     * 搜索记忆（向量/关键词/混合）
     */
    public List<MemorySearch.SearchResult> search(String query, int maxResults, double minScore) {
        try {
            ensureSearchToolInitialized();
            return searchTool.search(query, maxResults, minScore);
        } catch (Exception e) {
            log.warn("记忆搜索失败", e);
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
     */
    public synchronized void setEmbeddingProvider(EmbeddingProvider provider) {
        this.pendingEmbeddingProvider = provider;
        if (this.searchTool != null) {
            this.searchTool.setEmbeddingProvider(provider);
        }
    }

    private EmbeddingProvider pendingEmbeddingProvider;

    private synchronized void ensureSearchToolInitialized() throws Exception {
        if (searchTool == null) {
            searchTool = new MemorySearch(workspaceDir);
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

    // ==================== 模式存储 ====================

    /**
     * 保存检测到的模式到 semantic/patterns.json
     */
    private void savePatterns(List<Map<String, Object>> patterns) {
        try {
            Path patternsFile = semanticDir.resolve("patterns.json");
            
            // 读取现有模式
            Map<String, Object> existing = new LinkedHashMap<>();
            if (Files.exists(patternsFile)) {
                String content = Files.readString(patternsFile, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    existing = MAPPER.readValue(content, new TypeReference<>() {});
                }
            }

            // 获取 patterns map
            Map<String, Object> patternsMap = (Map<String, Object>) existing.getOrDefault("patterns", new LinkedHashMap<>());
            
            // 添加新模式
            for (Map<String, Object> pattern : patterns) {
                String patternId = "pat-" + LocalDate.now().format(DATE_FORMATTER) + "-" + UUID.randomUUID().toString().substring(0, 8);
                Map<String, Object> patternData = new LinkedHashMap<>();
                patternData.put("id", patternId);
                patternData.put("name", pattern.get("pattern"));
                patternData.put("category", pattern.get("category"));
                patternData.put("confidence", pattern.getOrDefault("confidence", 0.7));
                patternData.put("created", LocalDate.now().format(DATE_FORMATTER));
                patternData.put("applications", 0);
                if (pattern.containsKey("source_indices")) {
                    patternData.put("source_indices", pattern.get("source_indices"));
                }
                patternsMap.put(patternId, patternData);
            }

            existing.put("patterns", patternsMap);
            
            // 更新 metrics
            Map<String, Object> metrics = (Map<String, Object>) existing.getOrDefault("metrics", new LinkedHashMap<>());
            metrics.put("patterns_learned", patternsMap.size());
            existing.put("metrics", metrics);

            // 写入文件
            Files.writeString(patternsFile, MAPPER.writeValueAsString(existing), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存模式失败", e);
        }
    }

    // ==================== 工具定义构建 ====================

    private static List<Map<String, Object>> buildPruneMessagesTool() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "prune_messages",
                                "description", "分析对话消息，决定哪些可以从 Session 中删除。",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "dropped_indices", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "integer"),
                                                        "description", "可以删除的消息索引列表"
                                                ),
                                                "important_indices", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "integer"),
                                                        "description", "特别重要的消息索引"
                                                ),
                                                "patterns_detected", Map.of(
                                                        "type", "array",
                                                        "items", Map.of(
                                                                "type", "object",
                                                                "properties", Map.of(
                                                                        "pattern", Map.of("type", "string"),
                                                                        "category", Map.of("type", "string"),
                                                                        "confidence", Map.of("type", "number"),
                                                                        "source_indices", Map.of("type", "array", "items", Map.of("type", "integer"))
                                                                )
                                                        ),
                                                        "description", "检测到的可复用模式"
                                                ),
                                                "reasoning", Map.of(
                                                        "type", "string",
                                                        "description", "删除理由"
                                                )
                                        ),
                                        "required", List.of("dropped_indices", "reasoning")
                                )
                        )
                )
        );
    }

    private static List<Map<String, Object>> buildUpdateMemoryTool() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "update_memory",
                                "description", "更新长期记忆内容（完整替换模式）",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "memory_update", Map.of(
                                                        "type", "string",
                                                        "description", "更新后的长期记忆内容"
                                                )
                                        ),
                                        "required", List.of("memory_update")
                                )
                        )
                )
        );
    }

    private static String buildPruneSystemPrompt() {
        return """
你是一个会话管理代理，负责分析对话并识别可以删除的消息。

## 背景
- memory/YYYY-MM-DD.md 已保存完整原始对话（作为历史档案）
- Session 只需保留"活跃窗口"内的消息
- 你的任务是识别可以安全删除的消息

## 必须保留的消息（不要放入 dropped_indices）
1. **Skill 使用记录**：包含 tool_calls 的消息
2. **重要决策**：用户或助手做出的关键决策
3. **用户反馈**：用户表达的偏好、不满、确认
4. **任务状态**：当前任务的进度、待办事项
5. **错误和解决方案**：遇到的错误及其解决方法

## 可以丢弃的消息（放入 dropped_indices）
1. **重复确认**：多次重复的"好的"、"明白了"等
2. **简单问候**：问候和告别语
3. **冗余上下文**：已被总结的详细过程描述
4. **过长输出**：工具结果中过长的部分

## 输出要求
调用 prune_messages 工具，提供：
- dropped_indices：可以删除的消息索引
- important_indices：特别重要的消息索引
- patterns_detected：检测到的可复用模式（可选）
- reasoning：简要说明删除理由
""";
    }

    private static String buildUpdateMemorySystemPrompt() {
        return """
你是一个记忆管理代理，负责更新长期记忆。

## 自改进循环

Skill Event → Extract Experience → Abstract Pattern → Update

## 多记忆架构

### 存储位置

{工作空间}/memory/
├── MEMORY.md              # 长期记忆（人类可读摘要 + 链接索引）
├── semantic/patterns.json # 语义记忆：抽象模式和规则
├── episodic/YYYY/         # 情景记忆：具体经验
└── working/               # 工作记忆：当前会话状态

### MEMORY.md 的角色

1. **摘要**：提供关键信息的快速浏览
2. **链接**：指向结构化数据的存储位置
3. **入口**：作为检索结构化记忆的起点

## 经验提取

What happened:
  skill_used: {which skill}
  task: {what was being done}
  outcome: {success|partial|failure}

Key Insights:
  what_went_well: [what worked]
  what_went_wrong: [what didn't work]

## 模式抽象

| Concrete Experience | Abstract Pattern | Target Skill |
|--------------------|------------------|--------------|
| "User forgot to save PRD notes" | "Always persist thinking to files" | prd-planner |
| "Code review missed SQL injection" | "Add security checklist item" | code-reviewer |

## 抽象规则

If experience_repeats 3+ times:
  pattern_level: critical

If solution_was_effective:
  pattern_level: best_practice

If user_rating >= 7:
  pattern_level: strength

If user_rating <= 4:
  pattern_level: weakness

## 进化标记

<!-- Evolution: YYYY-MM-DD | source: episode_id | skill: skill_name -->

## MEMORY.md 结构

# Long-term Memory

## User Information
## Preferences
## Project Context
## Technical Notes
## Important Notes

## Patterns Detected
> 详细模式数据：[memory/semantic/patterns.json](memory/semantic/patterns.json)

## Episodic Memory
> 情景记忆存储：[memory/episodic/](memory/episodic/)

## 最佳实践

### DO
- ✅ Learn from EVERY skill interaction
- ✅ Extract patterns at the right abstraction level
- ✅ Track confidence and apply counts

### DON'T
- ❌ Over-generalize from single experiences
- ❌ Ignore negative feedback
- ❌ Create contradictory patterns
""";
    }

    private static List<Map<String, Object>> buildSaveMemoryTool() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "save_memory",
                                "description", "保存记忆压缩结果",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "memory_update", Map.of(
                                                        "type", "string",
                                                        "description", "更新后的长期记忆内容（覆盖写）"
                                                )
                                        ),
                                        "required", List.of("memory_update")
                                )
                        )
                )
        );
    }

    // ==================== 辅助方法 ====================

    private static String rstrip(String s) {
        if (s == null) return "";
        return s.stripTrailing();
    }

    private static String safeTsPrefix(Object ts) {
        if (ts == null) return "";
        if (ts instanceof Number n) {
            long millis = n.longValue();
            java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
            return instant.atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return String.valueOf(ts);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

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

    private static Map<String, Object> castToStringObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static Map<String, Object> parseToolCallArguments(ToolCallRequest toolCall) throws Exception {
        Object argsObj = toolCall.getArguments();
        if (argsObj instanceof String s) {
            return MAPPER.readValue(s, new TypeReference<>() {});
        } else if (argsObj instanceof Map<?, ?> m) {
            return castToStringObjectMap(m);
        }
        throw new IllegalArgumentException("无法解析工具参数: " + (argsObj == null ? "null" : argsObj.getClass()));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> parseIntegerList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(v -> v instanceof Number)
                    .map(v -> ((Number) v).intValue())
                    .toList();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parsePatterns(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(v -> v instanceof Map)
                    .map(v -> (Map<String, Object>) v)
                    .toList();
        }
        return null;
    }

    // ==================== 内部类 ====================

    /**
     * prune_messages 工具返回结果
     */
    private record PruneResult(
            List<Integer> droppedIndices,
            List<Integer> importantIndices,
            List<Map<String, Object>> patterns,
            String reasoning
    ) {}
}