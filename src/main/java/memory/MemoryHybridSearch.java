package memory;

import java.util.*;

/**
 * 混合搜索
 *
 * 对齐 OpenClaw 的 hybrid.ts
 *
 * 结合向量搜索和关键词搜索的结果，提供更全面的搜索能力
 */
public class MemoryHybridSearch {

    /**
     * 向量搜索结果
     */
    public static class VectorResult {
        public final String id;
        public final String path;
        public final int startLine;
        public final int endLine;
        public final String source;
        public final String snippet;
        public final double vectorScore;

        public VectorResult(String id, String path, int startLine, int endLine,
                           String source, String snippet, double vectorScore) {
            this.id = id;
            this.path = path;
            this.startLine = startLine;
            this.endLine = endLine;
            this.source = source;
            this.snippet = snippet;
            this.vectorScore = vectorScore;
        }
    }

    /**
     * 关键词搜索结果
     */
    public static class KeywordResult {
        public final String id;
        public final String path;
        public final int startLine;
        public final int endLine;
        public final String source;
        public final String snippet;
        public final double textScore;

        public KeywordResult(String id, String path, int startLine, int endLine,
                            String source, String snippet, double textScore) {
            this.id = id;
            this.path = path;
            this.startLine = startLine;
            this.endLine = endLine;
            this.source = source;
            this.snippet = snippet;
            this.textScore = textScore;
        }
    }

    /**
     * 混合搜索结果
     */
    public static class HybridResult implements MemoryMMR.MMRItem, MemoryTemporalDecay.TemporalDecayItem {
        public final String id;
        public final String path;
        public final int startLine;
        public final int endLine;
        public final String source;
        public String snippet;
        public double score;
        public final double vectorScore;
        public final double textScore;

        public HybridResult(String id, String path, int startLine, int endLine,
                           String source, String snippet, double score,
                           double vectorScore, double textScore) {
            this.id = id;
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
        public String getId() {
            return id;
        }

        @Override
        public double getScore() {
            return score;
        }

        @Override
        public String getContent() {
            return snippet != null ? snippet : "";
        }

        @Override
        public void setScore(double score) {
            this.score = score;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getSource() {
            return source;
        }
    }

    /**
     * 混合搜索配置
     */
    public static class HybridConfig {
        /** 是否启用混合搜索，默认 true */
        public boolean enabled = true;
        /** 向量权重，默认 0.7 */
        public double vectorWeight = 0.7;
        /** 文本权重，默认 0.3 */
        public double textWeight = 0.3;
        /** 候选结果倍数，默认 4 */
        public int candidateMultiplier = 4;
        /** MMR 配置 */
        public MemoryMMR.MMRConfig mmrConfig = new MemoryMMR.MMRConfig();
        /** 时间衰减配置 */
        public MemoryTemporalDecay.TemporalDecayConfig temporalDecayConfig = new MemoryTemporalDecay.TemporalDecayConfig();
    }

    /** 默认配置 */
    public static final HybridConfig DEFAULT_CONFIG = new HybridConfig();

    private MemoryHybridSearch() {
        // 工具类，禁止实例化
    }

    /**
     * 构建 FTS 查询
     *
     * 将原始查询转换为 FTS5 兼容的查询格式
     *
     * @param raw 原始查询字符串
     * @return FTS 查询字符串，如果无法构建则返回 null
     */
    public static String buildFtsQuery(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        // 提取字母、数字、下划线、中文字符
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || isChinese(c)) {
                current.append(c);
            } else {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        if (tokens.isEmpty()) {
            return null;
        }

        // 构建查询：用 AND 连接所有 token
        List<String> quoted = new ArrayList<>();
        for (String token : tokens) {
            quoted.add("\"" + token.replace("\"", "") + "\"");
        }

        return String.join(" AND ", quoted);
    }

    /**
     * 检查是否为中文字符
     */
    private static boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    /**
     * 将 BM25 排名转换为分数
     *
     * BM25 返回负数表示相关性，越接近 0 越相关
     *
     * @param rank BM25 排名
     * @return 分数，范围 (0, 1]
     */
    public static double bm25RankToScore(double rank) {
        if (!Double.isFinite(rank)) {
            return 1.0 / (1 + 999);
        }

        if (rank < 0) {
            // 负数表示相关性，转换为正数
            double relevance = -rank;
            return relevance / (1 + relevance);
        }

        return 1.0 / (1 + rank);
    }

    /**
     * 合并向量和关键词搜索结果
     *
     * @param vectorResults  向量搜索结果
     * @param keywordResults 关键词搜索结果
     * @param config         混合搜索配置
     * @param workspaceDir   工作目录（用于时间衰减）
     * @return 合并后的结果
     */
    public static List<HybridResult> mergeHybridResults(
            List<VectorResult> vectorResults,
            List<KeywordResult> keywordResults,
            HybridConfig config,
            java.nio.file.Path workspaceDir
    ) {
        if (config == null) {
            config = DEFAULT_CONFIG;
        }

        // 按 ID 合并结果
        Map<String, HybridResult> byId = new LinkedHashMap<>();

        // 添加向量结果
        if (vectorResults != null) {
            for (VectorResult r : vectorResults) {
                byId.put(r.id, new HybridResult(
                        r.id, r.path, r.startLine, r.endLine,
                        r.source, r.snippet, 0, r.vectorScore, 0
                ));
            }
        }

        // 添加/更新关键词结果
        if (keywordResults != null) {
            for (KeywordResult r : keywordResults) {
                HybridResult existing = byId.get(r.id);
                if (existing != null) {
                    // 更新现有结果
                    byId.put(r.id, new HybridResult(
                            r.id, r.path, r.startLine, r.endLine,
                            r.source,
                            r.snippet != null && !r.snippet.isEmpty() ? r.snippet : existing.snippet,
                            0,
                            existing.vectorScore,
                            r.textScore
                    ));
                } else {
                    byId.put(r.id, new HybridResult(
                            r.id, r.path, r.startLine, r.endLine,
                            r.source, r.snippet, 0, 0, r.textScore
                    ));
                }
            }
        }

        // 计算混合分数
        List<HybridResult> merged = new ArrayList<>();
        for (HybridResult entry : byId.values()) {
            double score = config.vectorWeight * entry.vectorScore + config.textWeight * entry.textScore;
            merged.add(new HybridResult(
                    entry.id, entry.path, entry.startLine, entry.endLine,
                    entry.source, entry.snippet, score,
                    entry.vectorScore, entry.textScore
            ));
        }

        // 应用时间衰减
        List<HybridResult> decayed = MemoryTemporalDecay.applyTemporalDecayToHybridResults(
                merged, config.temporalDecayConfig, workspaceDir
        );

        // 按分数排序
        decayed.sort((a, b) -> Double.compare(b.score, a.score));

        // 应用 MMR 重排序
        if (config.mmrConfig.enabled) {
            return MemoryMMR.applyMMRToHybridResults(decayed, config.mmrConfig);
        }

        return decayed;
    }

    /**
     * 执行混合搜索
     *
     * @param query           查询文本
     * @param vectorSearch    向量搜索函数
     * @param keywordSearch   关键词搜索函数
     * @param config          混合搜索配置
     * @param maxResults      最大结果数
     * @param workspaceDir    工作目录
     * @return 搜索结果
     */
    public static List<HybridResult> search(
            String query,
            java.util.function.Function<String, List<VectorResult>> vectorSearch,
            java.util.function.Function<String, List<KeywordResult>> keywordSearch,
            HybridConfig config,
            int maxResults,
            java.nio.file.Path workspaceDir
    ) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        if (config == null) {
            config = DEFAULT_CONFIG;
        }

        int candidates = Math.min(200, Math.max(1, maxResults * config.candidateMultiplier));

        // 执行向量搜索
        List<VectorResult> vectorResults = Collections.emptyList();
        if (vectorSearch != null) {
            try {
                vectorResults = vectorSearch.apply(query);
                if (vectorResults == null) {
                    vectorResults = Collections.emptyList();
                }
            } catch (Exception e) {
                vectorResults = Collections.emptyList();
            }
        }

        // 执行关键词搜索
        List<KeywordResult> keywordResults = Collections.emptyList();
        if (keywordSearch != null && config.enabled) {
            try {
                keywordResults = keywordSearch.apply(query);
                if (keywordResults == null) {
                    keywordResults = Collections.emptyList();
                }
            } catch (Exception e) {
                keywordResults = Collections.emptyList();
            }
        }

        // 合并结果
        List<HybridResult> merged = mergeHybridResults(vectorResults, keywordResults, config, workspaceDir);

        // 截取最大结果数
        if (merged.size() > maxResults) {
            return merged.subList(0, maxResults);
        }

        return merged;
    }
}