package memory;

import org.slf4j.*;

import java.nio.file.*;
import java.util.*;

/**
 * 记忆搜索工具
 *
 * 提供统一的记忆搜索接口，支持：
 * - 向量搜索（语义搜索）
 * - 关键词搜索（全文搜索）
 * - 混合搜索（结合向量和关键词）
 * - grep 搜索（历史日志搜索）
 */
public class MemorySearchTool {

    private static final Logger log = LoggerFactory.getLogger(MemorySearchTool.class);

    // ==================== 配置 ====================

    /** 工作目录 */
    private final Path workspaceDir;

    /** 记忆索引管理器 */
    private MemoryIndexManager indexManager;

    /** 嵌入提供者 */
    private EmbeddingProvider embeddingProvider;

    /** 是否启用向量搜索 */
    private boolean vectorEnabled = true;

    /** 是否启用 FTS */
    private boolean ftsEnabled = true;

    /** 是否启用混合搜索 */
    private boolean hybridEnabled = true;

    /** 最大结果数 */
    private int maxResults = 6;

    /** 最小分数 */
    private double minScore = 0.35;

    /** 混合搜索配置 */
    private MemoryHybridSearch.HybridConfig hybridConfig = new MemoryHybridSearch.HybridConfig();

    // ==================== 构造函数 ====================

    public MemorySearchTool(Path workspaceDir) {
        this.workspaceDir = Objects.requireNonNull(workspaceDir, "工作目录不能为空");
    }

    // ==================== 初始化 ====================

    /**
     * 初始化搜索工具
     */
    public synchronized void initialize() throws Exception {
        if (indexManager != null) {
            return;
        }

        // 创建索引管理器
        indexManager = new MemoryIndexManager(workspaceDir);

        // 设置嵌入提供者（如果已配置）
        if (embeddingProvider != null) {
            indexManager.setEmbeddingProvider(embeddingProvider);
            log.info("记忆搜索工具初始化：向量搜索已启用（model={}）", embeddingProvider.getModel());
        } else {
            log.info("记忆搜索工具初始化：纯关键词搜索模式（未配置嵌入提供者）");
        }

        // 配置
        indexManager.setVectorEnabled(vectorEnabled && embeddingProvider != null);
        indexManager.setFtsEnabled(ftsEnabled);
        indexManager.setMaxResults(maxResults);
        indexManager.setMinScore(minScore);
        indexManager.setHybridConfig(hybridConfig);

        // 初始化
        indexManager.initialize();
    }

    // ==================== 搜索 ====================

    /**
     * 搜索记忆
     *
     * @param query 查询文本
     * @return 搜索结果
     */
    public List<SearchResult> search(String query) throws Exception {
        return search(query, maxResults, minScore);
    }

    /**
     * 搜索记忆
     *
     * @param query      查询文本
     * @param maxResults 最大结果数
     * @param minScore   最小分数
     * @return 搜索结果
     */
    public List<SearchResult> search(String query, int maxResults, double minScore) throws Exception {
        ensureInitialized();

        String cleaned = query.trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        // 执行搜索
        List<MemoryHybridSearch.HybridResult> results = indexManager.search(cleaned, maxResults, minScore, null);

        // 转换结果
        List<SearchResult> searchResults = new ArrayList<>();
        for (MemoryHybridSearch.HybridResult r : results) {
            searchResults.add(new SearchResult(
                    r.path,
                    r.startLine,
                    r.endLine,
                    r.source,
                    r.snippet,
                    r.score,
                    r.vectorScore,
                    r.textScore
            ));
        }

        return searchResults;
    }

    /**
     * 在历史日志中搜索
     *
     * 使用 grep 搜索 HISTORY.md 文件
     *
     * @param keyword 关键词
     * @return 匹配的行
     */
    public List<String> grepHistory(String keyword) throws Exception {
        Path historyFile = workspaceDir.resolve("memory").resolve("HISTORY.md");

        if (!Files.exists(historyFile)) {
            return Collections.emptyList();
        }

        List<String> matches = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();

        try (var lines = Files.lines(historyFile)) {
            lines.filter(line -> line.toLowerCase().contains(lowerKeyword))
                 .forEach(matches::add);
        }

        return matches;
    }

    /**
     * 同步索引
     */
    public void sync() throws Exception {
        ensureInitialized();
        indexManager.sync();
    }

    // ==================== 辅助方法 ====================

    private void ensureInitialized() throws Exception {
        if (indexManager == null) {
            initialize();
        }
    }

    // ==================== Getter/Setter ====================

    public void setEmbeddingProvider(EmbeddingProvider provider) {
        this.embeddingProvider = provider;
        if (indexManager != null) {
            indexManager.setEmbeddingProvider(provider);
        }
    }

    public void setVectorEnabled(boolean enabled) {
        this.vectorEnabled = enabled;
        if (indexManager != null) {
            indexManager.setVectorEnabled(enabled);
        }
    }

    public void setFtsEnabled(boolean enabled) {
        this.ftsEnabled = enabled;
        if (indexManager != null) {
            indexManager.setFtsEnabled(enabled);
        }
    }

    public void setHybridEnabled(boolean enabled) {
        this.hybridEnabled = enabled;
        this.hybridConfig.enabled = enabled;
        if (indexManager != null) {
            indexManager.setHybridConfig(hybridConfig);
        }
    }

    public void setMaxResults(int max) {
        this.maxResults = Math.max(1, max);
        if (indexManager != null) {
            indexManager.setMaxResults(this.maxResults);
        }
    }

    public void setMinScore(double score) {
        this.minScore = Math.max(0, Math.min(1, score));
        if (indexManager != null) {
            indexManager.setMinScore(this.minScore);
        }
    }

    public void setHybridConfig(MemoryHybridSearch.HybridConfig config) {
        this.hybridConfig = config != null ? config : new MemoryHybridSearch.HybridConfig();
        if (indexManager != null) {
            indexManager.setHybridConfig(this.hybridConfig);
        }
    }

    public void setMmrEnabled(boolean enabled) {
        this.hybridConfig.mmrConfig.enabled = enabled;
        if (indexManager != null) {
            indexManager.setHybridConfig(hybridConfig);
        }
    }

    public void setTemporalDecayEnabled(boolean enabled) {
        this.hybridConfig.temporalDecayConfig.enabled = enabled;
        if (indexManager != null) {
            indexManager.setHybridConfig(hybridConfig);
        }
    }

    // ==================== 关闭 ====================

    public void close() {
        if (indexManager != null) {
            indexManager.close();
            indexManager = null;
        }
    }

    // ==================== 结果类 ====================

    /**
     * 搜索结果
     */
    public static class SearchResult {
        /** 文件路径 */
        public final String path;

        /** 起始行号 */
        public final int startLine;

        /** 结束行号 */
        public final int endLine;

        /** 来源（memory 或 sessions） */
        public final String source;

        /** 内容片段 */
        public final String snippet;

        /** 综合分数 */
        public final double score;

        /** 向量分数 */
        public final double vectorScore;

        /** 文本分数 */
        public final double textScore;

        public SearchResult(String path, int startLine, int endLine, String source,
                           String snippet, double score, double vectorScore, double textScore) {
            this.path = path;
            this.startLine = startLine;
            this.endLine = endLine;
            this.source = source;
            this.snippet = snippet;
            this.score = score;
            this.vectorScore = vectorScore;
            this.textScore = textScore;
        }

        @Override
        public String toString() {
            return String.format("SearchResult{path='%s', lines=%d-%d, score=%.3f, source='%s'}",
                    path, startLine, endLine, score, source);
        }
    }
}