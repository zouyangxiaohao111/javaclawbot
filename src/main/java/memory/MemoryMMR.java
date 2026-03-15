package memory;

import java.util.*;

/**
 * MMR（最大边际相关性）重排序算法
 *
 * 对齐 OpenClaw 的 mmr.ts
 *
 * MMR 平衡相关性和多样性，通过迭代选择最大化以下公式的结果：
 * MMR = λ * relevance - (1-λ) * max_similarity_to_selected
 *
 * @see Carbonell & Goldstein, "The Use of MMR, Diversity-Based Reranking" (1998)
 */
public class MemoryMMR {

    /**
     * MMR 配置
     */
    public static class MMRConfig {
        /** 是否启用 MMR 重排序，默认 false（可选功能） */
        public boolean enabled = false;
        /** Lambda 参数：0 = 最大多样性，1 = 最大相关性，默认 0.7 */
        public double lambda = 0.7;

        public MMRConfig() {}

        public MMRConfig(boolean enabled, double lambda) {
            this.enabled = enabled;
            this.lambda = Math.max(0, Math.min(1, lambda));
        }
    }

    /** 默认 MMR 配置 */
    public static final MMRConfig DEFAULT_CONFIG = new MMRConfig(false, 0.7);

    /**
     * MMR 项目接口
     */
    public interface MMRItem {
        /** 唯一标识 */
        String getId();
        /** 相关性分数 */
        double getScore();
        /** 内容文本（用于计算相似度） */
        String getContent();
    }

    private MemoryMMR() {
        // 工具类，禁止实例化
    }

    /**
     * 分词（用于 Jaccard 相似度计算）
     *
     * 提取字母数字和下划线，转换为小写
     */
    public static Set<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> tokens = new HashSet<>();
        // 匹配字母、数字、下划线、中文字符
        String[] parts = text.toLowerCase().split("[^a-z0-9_\\u4e00-\\u9fa5]+");
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }

        return tokens;
    }

    /**
     * 计算 Jaccard 相似度
     *
     * 公式: |A ∩ B| / |A ∪ B|
     *
     * @return 相似度，范围 [0, 1]，1 表示完全相同
     */
    public static double jaccardSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() && setB.isEmpty()) {
            return 1.0;
        }
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }

        int intersectionSize = 0;
        Set<String> smaller = setA.size() <= setB.size() ? setA : setB;
        Set<String> larger = setA.size() <= setB.size() ? setB : setA;

        for (String token : smaller) {
            if (larger.contains(token)) {
                intersectionSize++;
            }
        }

        int unionSize = setA.size() + setB.size() - intersectionSize;
        return unionSize == 0 ? 0 : (double) intersectionSize / unionSize;
    }

    /**
     * 计算文本相似度（使用 Jaccard 相似度）
     */
    public static double textSimilarity(String contentA, String contentB) {
        return jaccardSimilarity(tokenize(contentA), tokenize(contentB));
    }

    /**
     * 计算项目与已选项目的最大相似度
     */
    private static <T extends MMRItem> double maxSimilarityToSelected(
            T item,
            List<T> selectedItems,
            Map<String, Set<String>> tokenCache
    ) {
        if (selectedItems.isEmpty()) {
            return 0.0;
        }

        double maxSim = 0.0;
        Set<String> itemTokens = tokenCache.computeIfAbsent(item.getId(), k -> tokenize(item.getContent()));

        for (T selected : selectedItems) {
            Set<String> selectedTokens = tokenCache.computeIfAbsent(selected.getId(), k -> tokenize(selected.getContent()));
            double sim = jaccardSimilarity(itemTokens, selectedTokens);
            if (sim > maxSim) {
                maxSim = sim;
            }
        }

        return maxSim;
    }

    /**
     * 计算 MMR 分数
     *
     * 公式: MMR = λ * relevance - (1-λ) * max_similarity
     */
    public static double computeMMRScore(double relevance, double maxSimilarity, double lambda) {
        return lambda * relevance - (1 - lambda) * maxSimilarity;
    }

    /**
     * 使用 MMR 重排序项目
     *
     * 算法步骤：
     * 1. 从最高分项目开始
     * 2. 对于每个剩余位置，选择最大化 MMR 分数的项目
     * 3. MMR 分数 = λ * 相关性 - (1-λ) * 与已选项目的最大相似度
     *
     * @param items  待重排序的项目
     * @param config MMR 配置
     * @return 重排序后的项目列表
     */
    public static <T extends MMRItem> List<T> mmrRerank(List<T> items, MMRConfig config) {
        if (config == null) {
            config = DEFAULT_CONFIG;
        }

        // 早退出
        if (!config.enabled || items == null || items.size() <= 1) {
            return items == null ? Collections.emptyList() : new ArrayList<>(items);
        }

        double lambda = Math.max(0, Math.min(1, config.lambda));

        // 如果 lambda = 1，直接按相关性排序（无多样性惩罚）
        if (lambda == 1.0) {
            List<T> sorted = new ArrayList<>(items);
            sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return sorted;
        }

        // 预先分词所有项目以提高效率
        Map<String, Set<String>> tokenCache = new HashMap<>();
        for (T item : items) {
            tokenCache.put(item.getId(), tokenize(item.getContent()));
        }

        // 归一化分数到 [0, 1] 以便与相似度公平比较
        double maxScore = Double.NEGATIVE_INFINITY;
        double minScore = Double.POSITIVE_INFINITY;
        for (T item : items) {
            if (item.getScore() > maxScore) maxScore = item.getScore();
            if (item.getScore() < minScore) minScore = item.getScore();
        }
        double scoreRange = maxScore - minScore;

        final double finalMinScore = minScore;
        final double finalScoreRange = scoreRange;
        java.util.function.DoubleUnaryOperator normalizeScore = score -> {
            if (finalScoreRange == 0) return 1.0;
            return (score - finalMinScore) / finalScoreRange;
        };

        List<T> selected = new ArrayList<>();
        Set<T> remaining = new LinkedHashSet<>(items);

        // 迭代选择项目
        while (!remaining.isEmpty()) {
            T bestItem = null;
            double bestMMRScore = Double.NEGATIVE_INFINITY;

            for (T candidate : remaining) {
                double normalizedRelevance = normalizeScore.applyAsDouble(candidate.getScore());
                double maxSim = maxSimilarityToSelected(candidate, selected, tokenCache);
                double mmrScore = computeMMRScore(normalizedRelevance, maxSim, lambda);

                // 使用原始分数作为决胜局（越高越好）
                if (mmrScore > bestMMRScore ||
                    (mmrScore == bestMMRScore && candidate.getScore() > (bestItem != null ? bestItem.getScore() : Double.NEGATIVE_INFINITY))) {
                    bestMMRScore = mmrScore;
                    bestItem = candidate;
                }
            }

            if (bestItem != null) {
                selected.add(bestItem);
                remaining.remove(bestItem);
            } else {
                // 安全退出
                break;
            }
        }

        return selected;
    }

    /**
     * 对混合搜索结果应用 MMR 重排序
     */
    public static <T extends MMRItem> List<T> applyMMRToHybridResults(List<T> results, MMRConfig config) {
        if (results == null || results.isEmpty()) {
            return results == null ? Collections.emptyList() : results;
        }

        return mmrRerank(results, config);
    }
}