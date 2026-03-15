package agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Usage 统计
 *
 * 对齐 OpenClaw 的 Usage 系统
 *
 * 功能：
 * - 标准化不同 Provider 的 usage 字段
 * - 累积统计（多次 API 调用）
 * - 缓存 token 统计
 * - 成本估算
 */
public class Usage {

    // ==================== 字段 ====================

    /** 输入 token 数 */
    private long input;

    /** 输出 token 数 */
    private long output;

    /** 缓存读取 token 数 */
    private long cacheRead;

    /** 缓存写入 token 数 */
    private long cacheWrite;

    /** 总 token 数 */
    private long total;

    // ==================== 构造函数 ====================

    public Usage() {}

    public Usage(long input, long output) {
        this.input = input;
        this.output = output;
        this.total = input + output;
    }

    public Usage(long input, long output, long cacheRead, long cacheWrite) {
        this.input = input;
        this.output = output;
        this.cacheRead = cacheRead;
        this.cacheWrite = cacheWrite;
        this.total = input + output + cacheRead + cacheWrite;
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 从 LLM 响应的 usage map 创建
     */
    public static Usage fromMap(Map<String, Integer> usage) {
        if (usage == null || usage.isEmpty()) {
            return new Usage();
        }

        long input = getAsLong(usage, "prompt_tokens", "input_tokens", "input");
        long output = getAsLong(usage, "completion_tokens", "output_tokens", "output");
        long cacheRead = getAsLong(usage, "cache_read_input_tokens", "cached_tokens", "cache_read");
        long cacheWrite = getAsLong(usage, "cache_creation_input_tokens", "cache_write");
        long total = getAsLong(usage, "total_tokens", "total");

        Usage u = new Usage();
        u.input = input;
        u.output = output;
        u.cacheRead = cacheRead;
        u.cacheWrite = cacheWrite;
        u.total = total > 0 ? total : input + output + cacheRead + cacheWrite;

        return u;
    }

    private static long getAsLong(Map<String, Integer> map, String... keys) {
        for (String key : keys) {
            Integer value = map.get(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        return 0;
    }

    // ==================== 累积方法 ====================

    /**
     * 累加另一个 Usage
     */
    public Usage add(Usage other) {
        if (other == null) return this;

        this.input += other.input;
        this.output += other.output;
        this.cacheRead += other.cacheRead;
        this.cacheWrite += other.cacheWrite;
        // 注意：total 不简单累加，因为每次 API 调用的 total 可能包含重复计算的 cache
        // 使用最后一次调用的 input + output + cache 作为参考
        this.total = this.input + this.output;

        return this;
    }

    /**
     * 创建副本
     */
    public Usage copy() {
        Usage u = new Usage();
        u.input = this.input;
        u.output = this.output;
        u.cacheRead = this.cacheRead;
        u.cacheWrite = this.cacheWrite;
        u.total = this.total;
        return u;
    }

    /**
     * 重置
     */
    public void reset() {
        input = 0;
        output = 0;
        cacheRead = 0;
        cacheWrite = 0;
        total = 0;
    }

    // ==================== 计算方法 ====================

    /**
     * 获取 prompt token 数（输入 + 缓存读取 + 缓存写入）
     * 这是实际发送给模型的 token 数
     */
    public long getPromptTokens() {
        return input + cacheRead + cacheWrite;
    }

    /**
     * 检查是否有有效数据
     */
    public boolean hasData() {
        return input > 0 || output > 0 || cacheRead > 0 || cacheWrite > 0;
    }

    /**
     * 转换为 Map
     */
    public Map<String, Long> toMap() {
        Map<String, Long> map = new HashMap<>();
        if (input > 0) map.put("input", input);
        if (output > 0) map.put("output", output);
        if (cacheRead > 0) map.put("cacheRead", cacheRead);
        if (cacheWrite > 0) map.put("cacheWrite", cacheWrite);
        if (total > 0) map.put("total", total);
        return map;
    }

    // ==================== Getter/Setter ====================

    public long getInput() {
        return input;
    }

    public void setInput(long input) {
        this.input = input;
    }

    public long getOutput() {
        return output;
    }

    public void setOutput(long output) {
        this.output = output;
    }

    public long getCacheRead() {
        return cacheRead;
    }

    public void setCacheRead(long cacheRead) {
        this.cacheRead = cacheRead;
    }

    public long getCacheWrite() {
        return cacheWrite;
    }

    public void setCacheWrite(long cacheWrite) {
        this.cacheWrite = cacheWrite;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Usage{");
        
        if (input > 0) sb.append("input=").append(input);
        if (output > 0) {
            if (sb.length() > 6) sb.append(", ");
            sb.append("output=").append(output);
        }
        if (cacheRead > 0) {
            if (sb.length() > 6) sb.append(", ");
            sb.append("cacheRead=").append(cacheRead);
        }
        if (cacheWrite > 0) {
            if (sb.length() > 6) sb.append(", ");
            sb.append("cacheWrite=").append(cacheWrite);
        }
        if (total > 0) {
            if (sb.length() > 6) sb.append(", ");
            sb.append("total=").append(total);
        }
        
        sb.append("}");
        return sb.toString();
    }

    /**
     * 格式化为简短字符串
     */
    public String toShortString() {
        if (!hasData()) {
            return "0 tokens";
        }
        
        StringBuilder sb = new StringBuilder();
        
        long prompt = getPromptTokens();
        if (prompt > 0) {
            sb.append(formatTokens(prompt)).append(" in");
        }
        if (output > 0) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(formatTokens(output)).append(" out");
        }
        if (cacheRead > 0 || cacheWrite > 0) {
            sb.append(" (cache: ");
            if (cacheRead > 0) sb.append(formatTokens(cacheRead)).append(" read");
            if (cacheWrite > 0) {
                if (cacheRead > 0) sb.append(", ");
                sb.append(formatTokens(cacheWrite)).append(" write");
            }
            sb.append(")");
        }
        
        return sb.toString();
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) {
            return String.format("%.1fM", tokens / 1_000_000.0);
        } else if (tokens >= 1_000) {
            return String.format("%.1fK", tokens / 1_000.0);
        } else {
            return String.valueOf(tokens);
        }
    }
}