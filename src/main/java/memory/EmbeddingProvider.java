package memory;

import java.util.*;

/**
 * 嵌入向量提供者接口
 *
 * 对齐 OpenClaw 的 EmbeddingProvider
 *
 * 支持多种嵌入模型：
 * - OpenAI: text-embedding-3-small, text-embedding-3-large
 * - Gemini: gemini-embedding-001
 * - Voyage: voyage-4-large
 * - Mistral: mistral-embed
 * - Ollama: nomic-embed-text
 * - 本地模型: 通过 ONNX/DJL 加载
 */
public interface EmbeddingProvider {

    /**
     * 获取提供者 ID
     */
    String getId();

    /**
     * 获取模型名称
     */
    String getModel();

    /**
     * 获取最大输入 token 数
     */
    default int getMaxInputTokens() {
        return 8192; // 默认值
    }

    /**
     * 获取向量维度
     */
    default int getDimensions() {
        return 1536; // 默认值
    }

    /**
     * 嵌入单个查询文本
     *
     * @param text 查询文本
     * @return 嵌入向量
     */
    float[] embedQuery(String text);

    /**
     * 批量嵌入文本
     *
     * @param texts 文本列表
     * @return 嵌入向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 关闭提供者，释放资源
     */
    default void close() {
        // 默认空实现
    }
}