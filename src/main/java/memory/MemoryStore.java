package memory;

import agent.tool.FileSystemTools;
import cn.hutool.core.date.DateUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import session.Session;
import utils.Helpers;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 持久化记忆系统（对齐 OpenClaw 架构）：
 * - MEMORY.md：长期记忆（自动压缩生成）
 * - memory/YYYY-MM-DD.md：每日文件（原始日志）
 * - .javaclawbot/memory.db：SQLite 索引（向量/关键词搜索）
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
     * 虚拟工具：要求模型以工具调用形式返回记忆压缩结果
     */
    private static final List<Map<String, Object>> SAVE_MEMORY_TOOL = buildSaveMemoryTool();

    private final Path workspaceDir;
    private final Path memoryDir;
    private final Path memoryFile;

    /** 记忆搜索工具（支持 memory 目录下多文件） */
    private MemorySearch searchTool;

    public MemoryStore(Path workspace) {
        this.workspaceDir = Objects.requireNonNull(workspace, "workspace");
        // 确保 memory 目录存在
        this.memoryDir = Helpers.ensureDir(workspaceDir.resolve("memory"));
        this.memoryFile = this.memoryDir.resolve("MEMORY.md");
        this.searchTool = null;
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
                        ```\n\n
                        """.formatted( real);
                w.write(findContent);
                //w.newLine();
                //w.newLine();
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

    // ==================== 对话历史压缩 ====================


    /**
     * 将旧消息压缩到 MEMORY.md
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

            // 消息不足以产生"可压缩区间"
            if (session.getMessages().size() <= keepCount) {
                log.info("消息不足以产生'可压缩区间'，因此不进行压缩");
                return CompletableFuture.completedFuture(true);
            }

            // 没有新增可压缩内容
            if (session.getMessages().size() - session.getLastConsolidated() <= 0) {
                log.info("没有新增可压缩内容，因此不进行压缩");
                return CompletableFuture.completedFuture(true);
            }

            // 上一次压缩指针
            int from = session.getLastConsolidated();
            // 大小减去需要保留的消息数即为压缩数量，从后往前保留
            int toExclusive = session.getMessages().size() - keepCount;

            if (toExclusive <= from) {
                return CompletableFuture.completedFuture(true);
            }

            // 待压缩消息
            oldMessages = session.getMessages().subList(from, toExclusive);

            if (oldMessages.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }

            log.info("记忆压缩：压缩 {} 条，保留 {} 条", oldMessages.size(), keepCount);
        }

        // 待压缩的消息将孤立工具调用分别配对修复
        var repairResult = MemoryCompaction.repairToolUseResultPairing(new ArrayList<>(oldMessages));
        List<Map<String, Object>> repairedMessages = repairResult.messages;
        if (repairResult.droppedOrphanCount > 0) {
            log.info("修复了 {} 个孤立的 tool_result", repairResult.droppedOrphanCount);
        }

        // 分块压缩
        int estimatedTokens = MemoryCompaction.estimateMessagesTokens(repairedMessages);
        int maxChunkTokens = maxTokens - MemoryCompaction.SUMMARIZATION_OVERHEAD_TOKENS;

        if (estimatedTokens > maxChunkTokens) {
            log.info("消息总 token 数 {} 超出最大分块数量限制 {}，启用分块压缩", estimatedTokens, maxChunkTokens);
            return consolidateInChunks(session, repairedMessages, provider, model, maxTokens, temperature, archiveAll, keepCount);
        }

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
        // 把待压缩消息转换成可读文本
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
                Map.of(
                        "role", "system",
                        "content", "你是一个记忆压缩代理。调用 save_memory 工具保存对话的压缩结果。"
                ),
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, maxTokens, temperature, null)
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

        log.info("分块压缩：{} 个块", chunks.size());

        List<String> partialSummaries = new ArrayList<>();
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);

        for (List<Map<String, Object>> chunk : chunks) {
            chain = chain.thenCompose(prevSuccess -> {
                if (!prevSuccess) {
                    return CompletableFuture.completedFuture(false);
                }
                return consolidateSingleChunkForPartial(chunk, provider, model, maxTokens, temperature)
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
                String currentMemory = readLongTerm();
                writeLongTerm(summary);
                int newLastConsolidated = archiveAll ? 0 : (session.getMessages().size() - keepCount);
                session.setLastConsolidated(newLastConsolidated);
                return CompletableFuture.completedFuture(true);
            }
            return mergePartialSummaries(partialSummaries, provider, model, maxTokens, temperature)
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
            int maxTokens,
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

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, maxTokens, temperature, null)
                .thenApply(resp -> {
                    if (resp != null && resp.hasToolCalls() && !resp.getToolCalls().isEmpty()) {
                        var toolCall = resp.getToolCalls().get(0);
                        Object argsObj = toolCall.getArguments();
                        try {
                            Map<String, Object> args;
                            if (argsObj instanceof String s) {
                                args = MAPPER.readValue(s, new TypeReference<>() {});
                            } else if (argsObj instanceof Map<?, ?> m) {
                                args = castToStringObjectMap(m);
                            } else {
                                return "";
                            }
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
            int maxTokens,
            double temperature
    ) {
        String mergeInstructions = ""
                + "将这些部分摘要合并为一个连贯的摘要。\n\n"
                + "必须保留：\n"
                + "- 活动任务及其当前状态（进行中、阻塞、待处理）\n"
                + "- 批处理进度（例如 '已完成 5/17 项'）\n"
                + "- 用户最后请求的内容以及正在进行的处理\n"
                + "- 已做出的决策及其理由\n"
                + "- 待办事项、未决问题和约束条件\n"
                + "- 任何承诺或后续跟进事项\n"
                + "\n"
                + "优先保留近期上下文而非历史记录。\n\n"
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

        var toolCall = response.getToolCalls().get(0);

        Object argsObj = toolCall.getArguments();

        Map<String, Object> args;
        if (argsObj instanceof String s) {
            args = MAPPER.readValue(s, new TypeReference<>() {});
        } else if (argsObj instanceof Map<?, ?> m) {
            args = castToStringObjectMap(m);
        } else {
            log.warn("记忆压缩：工具参数类型异常：{}", (argsObj == null ? "null" : argsObj.getClass().getName()));
            return false;
        }

        // memory_update：写入 MEMORY.md
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

    // ==================== 心跳任务整理 ====================

    /**
     * 心跳任务：整理每日文件到 MEMORY.md
     *
     * @param provider     模型提供者
     * @param model        模型名
     * @param maxTokens    最大 token 数
     * @param temperature  温度
     * @param recentDays   读取最近 N 天的每日文件
     * @param keepDays     保留最近 N 天的每日文件（超过的删除）
     * @return 成功返回 true
     */
    public CompletableFuture<Boolean> heartbeatConsolidate(
            LLMProvider provider,
            String model,
            int maxTokens,
            double temperature,
            int recentDays,
            int keepDays
    ) {
        // 读取最近 N 天的每日文件
        List<Path> recentFiles = getRecentDailyFiles(recentDays);
        if (recentFiles.isEmpty()) {
            log.debug("没有需要整理的每日文件");
            return CompletableFuture.completedFuture(true);
        }

        // 读取所有内容
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
                + "请调用 save_memory 工具，返回更新后的长期记忆。"
                + "保留重要信息，移除过时信息，保持简洁。";

        List<Map<String, Object>> msgList = List.of(
                Map.of(
                        "role", "system",
                        "content", "你是一个记忆整理代理。整理每日记录，更新长期记忆。"
                ),
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        );

        return chatCompat(provider, msgList, SAVE_MEMORY_TOOL, model, maxTokens, temperature, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("心跳整理失败", ex);
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
                                log.info("心跳整理完成：更新了 MEMORY.md");
                            }
                        }

                        // 清理旧文件
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

    // ==================== 工具定义 ====================

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
}