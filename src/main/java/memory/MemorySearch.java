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
 * - 文件读取（安全读取记忆文件）
 */
public class MemorySearch {

    private static final Logger log = LoggerFactory.getLogger(MemorySearch.class);

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

    /** 搜索来源（"memory" 和/或 "sessions"） */
    private Set<String> sources = new HashSet<>(Arrays.asList("memory"));

    /** 会话目录（可选） */
    private Path sessionsDir;

    // ==================== 构造函数 ====================

    public MemorySearch(Path workspaceDir) {
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
     * 读取记忆文件内容
     *
     * @param relPath 相对路径（如 "MEMORY.md" 或 "memory/MEMORY.md"）
     * @param from    起始行号（可选，从 1 开始）
     * @param lines   读取行数（可选）
     * @return 文件内容结果
     */
    public ReadFileResult readFile(String relPath, Integer from, Integer lines) throws Exception {
        // 规范化路径
        Path memoryDir = workspaceDir.resolve("memory");
        Path targetFile;

        if (relPath.equals("MEMORY.md") || relPath.equals("memory/MEMORY.md")) {
            targetFile = memoryDir.resolve("MEMORY.md");
        } else if (relPath.equals("HISTORY.md") || relPath.equals("memory/HISTORY.md")) {
            targetFile = memoryDir.resolve("HISTORY.md");
        } else {
            // 其他文件，在 memory 目录下查找
            if (relPath.startsWith("memory/")) {
                targetFile = workspaceDir.resolve(relPath);
            } else {
                targetFile = memoryDir.resolve(relPath);
            }
        }

        // 安全检查：确保文件在工作目录内
        Path normalizedTarget = targetFile.normalize();
        Path normalizedWorkspace = workspaceDir.normalize();
        if (!normalizedTarget.startsWith(normalizedWorkspace)) {
            throw new SecurityException("路径超出工作目录范围: " + relPath);
        }

        // 检查文件是否存在
        if (!Files.exists(normalizedTarget)) {
            return new ReadFileResult(relPath, "", false, "文件不存在: " + relPath);
        }

        // 读取文件内容
        try {
            List<String> allLines = Files.readAllLines(normalizedTarget);
            int totalLines = allLines.size();

            // 计算起始和结束行
            int startLine = (from != null && from > 0) ? from - 1 : 0; // 转换为 0-based
            int endLine = totalLines;

            if (lines != null && lines > 0) {
                endLine = Math.min(startLine + lines, totalLines);
            }

            // 提取指定行
            List<String> selectedLines = allLines.subList(
                Math.max(0, startLine),
                Math.min(endLine, totalLines)
            );

            String text = String.join("\n", selectedLines);
            return new ReadFileResult(relPath, text, true, null);

        } catch (Exception e) {
            log.error("读取记忆文件失败: {}", relPath, e);
            return new ReadFileResult(relPath, "", false, "读取失败: " + e.getMessage());
        }
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

    /**
     * 设置搜索来源
     *
     * @param sources 来源集合（"memory" 和/或 "sessions"）
     */
    public void setSources(Set<String> sources) {
        this.sources = sources != null ? new HashSet<>(sources) : new HashSet<>(Arrays.asList("memory"));
        if (indexManager != null) {
            indexManager.setSources(this.sources);
        }
    }

    /**
     * 设置会话目录
     *
     * @param sessionsDir 会话目录路径
     */
    public void setSessionsDir(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        if (indexManager != null) {
            indexManager.setSessionsDir(sessionsDir);
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

    /**
     * 读取文件结果
     */
    public static class ReadFileResult {
        /** 文件路径 */
        public final String path;

        /** 文件内容 */
        public final String text;

        /** 是否成功 */
        public final boolean success;

        /** 错误信息 */
        public final String error;

        public ReadFileResult(String path, String text, boolean success, String error) {
            this.path = path;
            this.text = text;
            this.success = success;
            this.error = error;
        }
    }
}