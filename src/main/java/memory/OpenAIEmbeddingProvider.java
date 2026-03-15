package memory;

import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * OpenAI 嵌入提供者
 *
 * 使用 OpenAI API 生成文本嵌入向量
 *
 * 支持的模型：
 * - text-embedding-3-small: 1536 维，性价比高
 * - text-embedding-3-large: 3072 维，精度更高
 * - text-embedding-ada-002: 1536 维，旧版模型
 */
public class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingProvider.class);

    /** 默认 API 端点 */
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/embeddings";

    /** 默认模型 */
    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    /** 默认维度 */
    private static final Map<String, Integer> MODEL_DIMENSIONS = Map.of(
            "text-embedding-3-small", 1536,
            "text-embedding-3-large", 3072,
            "text-embedding-ada-002", 1536
    );

    /** 默认最大输入 token */
    private static final int DEFAULT_MAX_INPUT_TOKENS = 8191;

    // ==================== 配置 ====================

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int dimensions;
    private final int maxInputTokens;

    // ==================== HTTP 客户端 ====================

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // ==================== 构造函数 ====================

    public OpenAIEmbeddingProvider(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_API_URL);
    }

    public OpenAIEmbeddingProvider(String apiKey, String model) {
        this(apiKey, model, DEFAULT_API_URL);
    }

    public OpenAIEmbeddingProvider(String apiKey, String model, String apiUrl) {
        this.apiKey = Objects.requireNonNull(apiKey, "API Key 不能为空");
        this.model = model != null && !model.isEmpty() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isEmpty() ? apiUrl : DEFAULT_API_URL;
        this.dimensions = MODEL_DIMENSIONS.getOrDefault(this.model, 1536);
        this.maxInputTokens = DEFAULT_MAX_INPUT_TOKENS;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper();

        log.info("OpenAI 嵌入提供者初始化: model={}, dimensions={}", this.model, this.dimensions);
    }

    // ==================== 实现接口 ====================

    @Override
    public String getId() {
        return "openai";
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public int getMaxInputTokens() {
        return maxInputTokens;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public float[] embedQuery(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("input", texts);
            requestBody.put("encoding_format", "float");

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // 构建请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            // 发送请求
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI API 错误: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("OpenAI API 错误: " + response.statusCode());
            }

            // 解析响应
            return parseEmbeddingResponse(response.body());

        } catch (Exception e) {
            log.error("生成嵌入失败", e);
            throw new RuntimeException("生成嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析嵌入响应
     */
    @SuppressWarnings("unchecked")
    private List<float[]> parseEmbeddingResponse(String responseBody) throws Exception {
        Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);

        List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.get("data");
        if (dataList == null || dataList.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 index 排序
        dataList.sort(Comparator.comparingInt(d -> (Integer) d.get("index")));

        List<float[]> embeddings = new ArrayList<>();
        for (Map<String, Object> data : dataList) {
            List<Number> embeddingList = (List<Number>) data.get("embedding");
            float[] embedding = new float[embeddingList.size()];
            for (int i = 0; i < embeddingList.size(); i++) {
                embedding[i] = embeddingList.get(i).floatValue();
            }
            embeddings.add(embedding);
        }

        return embeddings;
    }

    @Override
    public void close() {
        // HTTP 客户端不需要显式关闭
    }
}