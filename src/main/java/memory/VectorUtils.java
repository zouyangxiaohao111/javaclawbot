package memory;

import java.util.*;

/**
 * 向量工具类
 *
 * 提供向量计算相关的工具方法
 */
public class VectorUtils {

    private VectorUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算余弦相似度
     *
     * 公式: cos(θ) = (A · B) / (||A|| * ||B||)
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 相似度，范围 [-1, 1]，值越大越相似
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }

        float dotProduct = 0f;
        float normA = 0f;
        float normB = 0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0f;
        }

        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 计算余弦距离
     *
     * 公式: distance = 1 - similarity
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 距离，范围 [0, 2]，值越小越相似
     */
    public static float cosineDistance(float[] a, float[] b) {
        return 1f - cosineSimilarity(a, b);
    }

    /**
     * 计算欧几里得距离
     *
     * 公式: ||A - B||
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 距离，值越小越相似
     */
    public static float euclideanDistance(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return Float.MAX_VALUE;
        }

        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }

        return (float) Math.sqrt(sum);
    }

    /**
     * 归一化向量（L2 范数）
     *
     * @param vector 输入向量
     * @return 归一化后的向量
     */
    public static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return vector;
        }

        float norm = 0f;
        for (float v : vector) {
            norm += v * v;
        }

        if (norm == 0) {
            return vector;
        }

        norm = (float) Math.sqrt(norm);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] / norm;
        }

        return result;
    }

    /**
     * 向量加法
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 结果向量
     */
    public static float[] add(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            throw new IllegalArgumentException("向量长度不匹配");
        }

        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }

        return result;
    }

    /**
     * 向量标量乘法
     *
     * @param vector 向量
     * @param scalar 标量
     * @return 结果向量
     */
    public static float[] multiply(float[] vector, float scalar) {
        if (vector == null) {
            return null;
        }

        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] * scalar;
        }

        return result;
    }

    /**
     * 向量点积
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 点积结果
     */
    public static float dotProduct(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0f;
        }

        float result = 0f;
        for (int i = 0; i < a.length; i++) {
            result += a[i] * b[i];
        }

        return result;
    }

    /**
     * 将 float[] 转换为字符串（用于存储）
     */
    public static String encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(Float.floatToIntBits(vector[i]));
        }
        return sb.toString();
    }

    /**
     * 从字符串解码为 float[]
     */
    public static float[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new float[0];
        }

        String[] parts = encoded.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vector[i] = Float.intBitsToFloat(Integer.parseInt(parts[i].trim()));
            } catch (NumberFormatException e) {
                vector[i] = 0f;
            }
        }

        return vector;
    }

    /**
     * 将 float[] 转换为 JSON 数组字符串
     */
    public static String toJsonArray(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}