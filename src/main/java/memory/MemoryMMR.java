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
        public boolean enabled = true;
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
     * 计算两个集合的 Jaccard 相似度
     * Jaccard(A, B) = |A ∩ B| / |A ∪ B|
     *
     * @param setA 第一个词项集合
     * @param setB 第二个词项集合
     * @return [0, 1] 范围内的相似度，0 表示无交集，1 表示完全相同
     */
    public static double jaccardSimilarity(Set<String> setA, Set<String> setB) {
        // 边界：两个都空 → 视为完全相同
        if (setA.isEmpty() && setB.isEmpty()) {
            return 1.0;
        }
        // 边界：一个空一个非空 → 无交集
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }

        // 交集计数
        int intersectionSize = 0;

        // 性能优化：遍历较小集合，在较大集合中查找, 小循环驱动大循环
        Set<String> smaller = setA.size() <= setB.size() ? setA : setB;
        Set<String> larger = setA.size() <= setB.size() ? setB : setA;

        for (String token : smaller) {
            if (larger.contains(token)) {
                intersectionSize++;
            }
        }

        // 容斥原理：|A∪B| = |A| + |B| - |A∩B|
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

        // 获取候选项目的词项集合（从缓存中查找，避免重复分词）
        Set<String> itemTokens = tokenCache.computeIfAbsent(item.getId(), k -> tokenize(item.getContent()));

        // 如果候选项目无词项（空内容），无法计算相似度，返回 0
        if (itemTokens == null || itemTokens.isEmpty()) {
            return 0.0;
        }

        // 记录当前找到的最大相似度，初始为 0
        double maxSim = 0.0;
        // 遍历所有已选项目，找出与候选项目最相似的那个
        for (T selectedItem : selectedItems) {
            // 从缓存获取已选项目的词项集合
            Set<String> selectedTokens = tokenCache.get(selectedItem.getId());

            // 如果已选项目无词项，跳过（相似度为 0）
            if (selectedTokens == null || selectedTokens.isEmpty()) {
                continue;
            }

            // 计算两个词项集合的 Jaccard 相似度
            double sim = jaccardSimilarity(itemTokens, selectedTokens);

            // 更新最大相似度
            if (sim > maxSim) {
                maxSim = sim;
            }
        }

        // 返回与所有已选项目的最大相似度
        // 取 max（而非 avg）是因为只要与任意一个已选项目高度相似，就应被惩罚
        return maxSim;
    }

    /**
     * 计算单个候选项目的 MMR 分数
     *
     * @param relevance 归一化后的相关性分数 [0, 1]，越高越相关
     * @param maxSim    与已选项目的最大相似度 [0, 1]，越高越冗余
     * @param lambda    平衡因子 [0, 1]，越大越偏向相关性，越小越偏向多样性
     * @return MMR 分数，越高越应该被选中
     */
    private static double computeMMRScore(double relevance, double maxSim, double lambda) {
        // MMR 公式：加权组合
        // λ * relevance：相关性收益（选这个项目能带来多少信息增益）
        // (1-λ) * maxSim：冗余惩罚（这个项目与已选项目的重复程度）
        // 两者相减：在相关性和多样性之间取平衡
        return lambda * relevance - (1 - lambda) * maxSim;
    }

    /**
     * 使用 MMR (Maximal Marginal Relevance) 算法对项目进行重排序
     *
     * 核心思想：在相关性和多样性之间取得平衡
     * - 高 lambda：更重视相关性（接近纯相关性排序）
     * - 低 lambda：更重视多样性（结果更分散，减少冗余）
     *
     * @param items  待重排序的项目列表
     * @param config MMR 配置参数（lambda、是否启用等）
     * @param <T>    必须实现 MMRItem 接口的泛型类型
     * @return 重排序后的项目列表（新列表，不修改原列表）
     */
    public static <T extends MMRItem> List<T> mmrRerank(List<T> items, MMRConfig config) {
        // ==================== 1. 参数校验与默认值 ====================

        // 如果未提供配置，使用全局默认配置
        if (config == null) {
            config = DEFAULT_CONFIG;
        }

        // 空输入、单元素、或禁用 MMR 时，直接返回原列表的副本
        // 用 new ArrayList 包装，避免调用方后续修改影响返回结果
        if (!config.enabled || items == null || items.size() <= 1) {
            return items == null ? Collections.emptyList() : new ArrayList<>(items);
        }

        // 将 lambda 限制在 [0, 1] 范围内，防止配置错误导致异常行为
        // lambda=1: 纯相关性排序（无多样性惩罚）
        // lambda=0: 纯多样性排序（忽略相关性，只追求分散）
        double lambda = Math.max(0, Math.min(1, config.lambda));

        // lambda=1 时 MMR 退化为纯相关性排序，不需要执行完整算法
        if (lambda == 1.0) {
            // 创建副本，避免修改原列表
            List<T> sorted = new ArrayList<>(items);
            // 按分数降序排列（分数越高越相关）
            sorted.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return sorted;
        }

        // ==================== 2. 预计算：分词缓存 ====================

        // 使用 IdentityHashMap 以对象引用为 key，避免 getId() 返回值冲突导致缓存覆盖
        // 两个不同 item 如果 getId() 相同，HashMap 会静默覆盖，导致后续相似度计算错误
        Map<String, Set<String>> tokenCache = new IdentityHashMap<>();
        // 遍历所有项目，预先分词并缓存，避免在后续相似度计算中重复分词
        for (T item : items) {
            // tokenize 将文本内容拆分为词项集合，用于后续的 Jaccard 相似度计算
            tokenCache.put(item.getId(), tokenize(item.getContent()));
        }

        // ==================== 3. 分数归一化预计算 ====================

        // 初始化最小/最大分数为极端值，用于后续遍历找出真实范围
        double maxScore = Double.NEGATIVE_INFINITY;  // 初始设为负无穷，确保任何分数都能更新它
        double minScore = Double.POSITIVE_INFINITY;  // 初始设为正无穷，确保任何分数都能更新它

        // 遍历所有项目，找出分数的最小值和最大值
        for (T item : items) {
            // 如果当前项目分数大于已知最大值，更新最大值
            if (item.getScore() > maxScore) {
                maxScore = item.getScore();
            }
            // 如果当前项目分数小于已知最小值，更新最小值
            if (item.getScore() < minScore) {
                minScore = item.getScore();
            }
        }

        // 计算分数的跨度（最大值 - 最小值），用于归一化公式
        double scoreRange = maxScore - minScore;

        // 使用 final 变量，以便在 lambda 表达式中捕获
        final double finalMinScore = minScore;
        final double finalScoreRange = scoreRange;

        // 创建归一化函数：将任意原始分数映射到 [0, 1]
        // 使用 DoubleUnaryOperator 函数式接口，避免每次调用时重复计算 min/max
        java.util.function.DoubleUnaryOperator normalizeScore = score -> {
            // 如果所有分数相同（range=0），返回 1.0（全部视为最高相关性）
            // 返回 0.5 也可以，这里选 1.0 表示"都相关"
            if (finalScoreRange == 0) return 1.0;
            // Min-Max 归一化：(score - min) / range
            // 最低分 → 0.0，最高分 → 1.0，中间按比例分布
            return (score - finalMinScore) / finalScoreRange;
        };

        // selected 存储已选中的项目（按选择顺序排列，即最终的排序结果）
        List<T> selected = new ArrayList<>();

        // remaining 存储尚未被选中的候选项目
        // 使用 ArrayList + 引用比较，避免依赖 equals/hashCode 的正确实现
        // 如果 MMRItem 未正确重写 equals/hashCode，LinkedHashSet 可能导致 remove 失败或死循环
        List<T> remaining = new ArrayList<>(items);

        // 主循环：每一轮从 remaining 中选出一个 MMR 分数最高的项目
        // 循环直到 remaining 为空（所有项目都被选出）
        while (!remaining.isEmpty()) {

            // 当前轮次的最佳候选项目（MMR 分数最高者）
            T bestItem = null;

            // 当前轮次的最佳 MMR 分数，初始化为负无穷以确保第一个候选一定被选中
            double bestMMRScore = Double.NEGATIVE_INFINITY;

            // 遍历所有剩余候选项目，计算各自的 MMR 分数
            for (T candidate : remaining) {

                // 将候选项目的原始 BM25 分数归一化到 [0, 1]
                // 归一化是为了与相似度（本身就在 [0, 1]）在同一尺度上公平比较
                double normalizedRelevance = normalizeScore.applyAsDouble(candidate.getScore());

                // 计算候选项目与已选中项目的最大相似度
                // 如果 selected 为空（第一轮），显式返回 0.0，不依赖 maxSimilarityToSelected 的隐式行为
                double maxSim = selected.isEmpty() ? 0.0
                        : maxSimilarityToSelected(candidate, selected, tokenCache);

                // 计算 MMR 分数
                // 公式: MMR = λ * relevance - (1-λ) * maxSimilarity
                // 高相关性 → 提高 MMR 分数
                // 与已选项目高度相似 → 降低 MMR 分数（多样性惩罚）
                double mmrScore = computeMMRScore(normalizedRelevance, maxSim, lambda);

                // 使用容差比较浮点数，避免浮点精度问题导致 tiebreaker 永远不生效
                // epsilon = 1e-10 足够小，不影响实际排序，但能让真正接近的分数触发 tiebreaker
                double epsilon = 1e-10;

                // 选择条件：
                // 条件1: MMR 分数严格更高（正常情况）
                // 条件2: MMR 分数几乎相等（差值在 epsilon 内），用原始分数作为决胜局
                //        原始分数越高越好（BM25 值越接近 0 越相关，但 getScore() 应该已经是转换后的正向分数）
                if (mmrScore > bestMMRScore + epsilon
                        || (Math.abs(mmrScore - bestMMRScore) < epsilon
                        && candidate.getScore() > (bestItem != null ? bestItem.getScore() : Double.NEGATIVE_INFINITY))) {
                    // 更新最佳候选
                    bestMMRScore = mmrScore;
                    bestItem = candidate;
                }
            }

            // 如果找到了最佳项目（正常情况下一定会找到）
            if (bestItem != null) {
                // 将其加入已选列表（按选择顺序，即最终排序）
                selected.add(bestItem);
                // 从剩余候选中移除（按引用移除，不依赖 equals）
                T finalBestItem = bestItem;
                remaining.removeIf(r -> r == finalBestItem);
            } else {
                // 理论上不会走到这里，但作为安全防护防止死循环
                // 可能触发场景：remaining 中所有元素都因并发修改而丢失
                break;
            }
        }

        // 返回重排序后的结果列表
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