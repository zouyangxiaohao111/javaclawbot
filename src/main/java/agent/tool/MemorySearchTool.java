package agent.tool;

import memory.*;
import org.slf4j.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 记忆搜索工具
 *
 * 对齐 OpenClaw 的 memory_search 工具
 *
 * 功能：
 * - 向量搜索：语义搜索记忆内容
 * - 关键词搜索：全文搜索记忆内容
 * - 混合搜索：结合向量和关键词搜索
 * - grep 搜索：在历史日志中搜索
 *
 * 使用方式：
 * - 用户询问之前讨论过的内容时，使用此工具搜索记忆
 * - 支持自然语言查询和关键词查询
 */
public class MemorySearchTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);

    // ==================== 配置 ====================

    private final Path workspaceDir;
    private final Path sessionsDir;
    private MemorySearch searchTool;

    // ==================== 构造函数 ====================

    public MemorySearchTool(Path workspaceDir) {
        this.workspaceDir = Objects.requireNonNull(workspaceDir, "工作目录不能为空");
        this.sessionsDir = workspaceDir.resolve("sessions");
    }

    /**
     * 创建带会话目录的工具
     *
     * @param workspaceDir 工作目录
     * @param sessionsDir  会话目录
     */
    public MemorySearchTool(Path workspaceDir, Path sessionsDir) {
        this.workspaceDir = Objects.requireNonNull(workspaceDir, "工作目录不能为空");
        this.sessionsDir = sessionsDir;
    }

    // ==================== 实现接口 ====================

    @Override
    public String name() {
        return "memory_search";
    }

    @Override
    public String description() {
        return """
            搜索记忆内容。支持：
            - 向量搜索：语义搜索，找到语义相关的内容
            - 关键词搜索：全文搜索，找到包含关键词的内容
            - 混合搜索：结合向量和关键词搜索，提供更全面的结果

            使用场景：
            - 用户询问之前讨论过的内容
            - 需要查找特定的项目信息或决策
            - 需要回顾历史对话内容

            参数：
            - query: 搜索查询（必需）
            - max_results: 最大结果数（可选，默认 6）
            - min_score: 最小分数阈值（可选，默认 0.35）
            - source: 搜索来源（可选，memory 或 sessions，默认 memory）
            """;
    }

    @Override
    public Map<String, Object> parameters() {
        // 构建 JSON Schema
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // query 参数
        Map<String, Object> queryParam = new LinkedHashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "搜索查询文本");
        properties.put("query", queryParam);

        // max_results 参数
        Map<String, Object> maxResultsParam = new LinkedHashMap<>();
        maxResultsParam.put("type", "integer");
        maxResultsParam.put("description", "最大返回结果数（默认 6）");
        maxResultsParam.put("default", 6);
        properties.put("max_results", maxResultsParam);

        // min_score 参数
        Map<String, Object> minScoreParam = new LinkedHashMap<>();
        minScoreParam.put("type", "number");
        minScoreParam.put("description", "最小分数阈值（默认 0.35）");
        minScoreParam.put("default", 0.35);
        properties.put("min_score", minScoreParam);

        // source 参数
        Map<String, Object> sourceParam = new LinkedHashMap<>();
        sourceParam.put("type", "string");
        sourceParam.put("description", "搜索来源：memory 或 sessions");
        sourceParam.put("enum", List.of("memory", "sessions"));
        sourceParam.put("default", "memory");
        properties.put("source", sourceParam);

        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return schema;
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 解析参数
                String query = (String) args.get("query");
                if (query == null || query.trim().isEmpty()) {
                    return "错误：查询不能为空";
                }

                int maxResults = args.containsKey("max_results")
                        ? ((Number) args.get("max_results")).intValue()
                        : 6;

                double minScore = args.containsKey("min_score")
                        ? ((Number) args.get("min_score")).doubleValue()
                        : 0.35;

                // 确保初始化
                ensureInitialized();

                // 执行搜索
                List<MemorySearch.SearchResult> results = searchTool.search(query, maxResults, minScore);

                // 构建结果
                if (results.isEmpty()) {
                    return "未找到匹配的记忆内容";
                }

                StringBuilder sb = new StringBuilder();
                sb.append("找到 ").append(results.size()).append(" 条相关记忆：\n\n");

                for (int i = 0; i < results.size(); i++) {
                    MemorySearch.SearchResult r = results.get(i);
                    sb.append("### 结果 ").append(i + 1).append("\n");
                    sb.append("- 文件: ").append(r.path).append("\n");
                    sb.append("- 行号: ").append(r.startLine).append("-").append(r.endLine).append("\n");
                    sb.append("- 分数: ").append(String.format("%.3f", r.score)).append("\n");
                    sb.append("- 来源: ").append(r.source).append("\n");
                    sb.append("\n```\n").append(r.snippet).append("\n```\n\n");
                }

                return sb.toString();

            } catch (Exception e) {
                log.error("记忆搜索失败", e);
                return "记忆搜索失败: " + e.getMessage();
            }
        });
    }

    // ==================== 辅助方法 ====================

    private void ensureInitialized() throws Exception {
        if (searchTool == null) {
            searchTool = new MemorySearch(workspaceDir);

            // 设置会话目录（如果存在）
            if (sessionsDir != null && java.nio.file.Files.exists(sessionsDir)) {
                searchTool.setSessionsDir(sessionsDir);
                searchTool.setSources(new HashSet<>(Arrays.asList("memory", "sessions")));
            }

            // 不设置嵌入提供者，降级为纯关键词搜索
            // 只有在配置了 API Key 时才使用真实的嵌入提供者
            // searchTool.setEmbeddingProvider(new OpenAIEmbeddingProvider(apiKey));

            // 初始化
            searchTool.initialize();
        }
    }

    /**
     * 设置嵌入提供者
     */
    public void setEmbeddingProvider(EmbeddingProvider provider) {
        if (searchTool != null) {
            searchTool.setEmbeddingProvider(provider);
        }
    }

    /**
     * 关闭工具
     */
    public void close() {
        if (searchTool != null) {
            searchTool.close();
        }
    }
}