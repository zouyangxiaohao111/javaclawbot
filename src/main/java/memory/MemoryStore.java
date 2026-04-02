package memory;

import agent.tool.FileSystemTools;
import agent.tool.Tool;
import agent.tool.ToolRegistry;
import cn.hutool.core.io.resource.ResourceUtil;
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
     * 系统提示词：消息修剪（供 /context-compress 命令使用）
     */
    public static final String PRUNE_SYSTEM_PROMPT = buildPruneSystemPrompt();

    /**
     * 系统提示词：更新记忆（供 /memory 命令使用）
     */
    public static final String UPDATE_MEMORY_SYSTEM_PROMPT = buildUpdateMemorySystemPrompt();


    private final Path workspaceDir;
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path semanticDir;
    private final Path episodicDir;

    /** 记忆搜索工具（支持 memory 目录下多文件） */
    private MemorySearch searchTool;

    public MemoryStore(Path workspace) {
        this.workspaceDir = Objects.requireNonNull(workspace, "workspace");
        // 确保 memory 目录存在
        this.memoryDir = Helpers.ensureDir(workspaceDir.resolve("memory"));
        this.memoryFile = this.memoryDir.resolve("MEMORY.md");
        // 确保多记忆架构目录存在
        this.semanticDir = Helpers.ensureDir(memoryDir.resolve("semantic"));
        this.episodicDir = Helpers.ensureDir(memoryDir.resolve("episodic"));
        Helpers.ensureDir(episodicDir.resolve(String.valueOf(LocalDate.now().getYear())));
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
- memory/YYYY-MM-DD.md 已保存完整原始对话（作为历史档案,不能读取,非常重要 不能读取!）
- Session 只需保留"活跃窗口"内的消息
- 你的任务是识别可以安全删除、修剪的消息

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

## 可以裁剪的消息
1. 过于庞大的工具输出
2. 当前最后一个user 消息 距离过长并且无实际指导意义的输出
## 输出要求
调用 prune_messages 工具，提供：
- dropped_indices：可以删除的消息索引
- important_indices：特别重要的消息索引
- sub_indices：需要裁剪的消息索引
- patterns_detected：检测到的可复用模式（可选）
- reasoning：简要说明删除理由

## 额外说明
当前用户工作空间,workspace: {workspace}, 活跃会话消息目录: {workspace}/session 
```text
  {工作空间}/sessions/
  ├── sessions.json   # 当前活跃会话对应的session_id映射
  ├── {session_id}.jsonl        # 对应的活跃会话数据
```
""";
    }

    private static String buildUpdateMemorySystemPrompt() {
        try {
            return ResourceUtil.readUtf8Str("templates/memory/memory_system.md");
        } catch (Exception e) {
            log.error("读取模板文件失败", e);
            throw new RuntimeException(e);
        }
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