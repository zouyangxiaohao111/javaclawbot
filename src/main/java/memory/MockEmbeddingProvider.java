package memory;

import org.slf4j.*;

import java.util.*;

/**
 * 模拟嵌入提供者
 *
 * 用于测试和开发环境，不调用真实 API
 *
 * 使用简单的哈希算法生成伪嵌入向量
 */
public class MockEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(MockEmbeddingProvider.class);

    /** 默认维度 */
    private static final int DEFAULT_DIMENSIONS = 1536;

    // ==================== 配置 ====================

    private final String model;
    private final int dimensions;

    // ==================== 构造函数 ====================

    public MockEmbeddingProvider() {
        this("mock-embedding", DEFAULT_DIMENSIONS);
    }

    public MockEmbeddingProvider(String model, int dimensions) {
        this.model = model != null ? model : "mock-embedding";
        this.dimensions = Math.max(1, dimensions);

        log.info("模拟嵌入提供者初始化: model={}, dimensions={}", this.model, this.dimensions);
    }

    // ==================== 实现接口 ====================

    @Override
    public String getId() {
        return "mock";
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public int getMaxInputTokens() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null || text.isEmpty()) {
            return new float[dimensions];
        }

        // 使用文本哈希生成伪随机向量
        float[] embedding = new float[dimensions];
        Random random = new Random(text.hashCode());

        for (int i = 0; i < dimensions; i++) {
            embedding[i] = (float) (random.nextGaussian() * 0.1);
        }

        // 归一化
        return VectorUtils.normalize(embedding);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embedQuery(text));
        }

        return results;
    }

    @Override
    public void close() {
        // 无需关闭
    }
}