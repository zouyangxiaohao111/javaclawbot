package agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Usage 累积器
 *
 * 对齐 OpenClaw 的 UsageAccumulator
 *
 * 功能：
 * - 累积多次 API 调用的 usage
 * - 跟踪最后一次调用的 usage（用于上下文大小估算）
 * - 支持工具调用循环中的累积
 */
public class UsageAccumulator {

    private static final Logger log = LoggerFactory.getLogger(UsageAccumulator.class);

    // ==================== 字段 ====================

    /** 累积的总 usage */
    private Usage total = new Usage();

    /** 最后一次 API 调用的 usage */
    private Usage lastCall = new Usage();

    /** 历史记录（可选，用于调试） */
    private List<Usage> history = new ArrayList<>();

    /** 是否记录历史 */
    private boolean recordHistory = false;

    /** API 调用次数 */
    private int callCount = 0;

    // ==================== 累积方法 ====================

    /**
     * 累加 LLM 响应的 usage
     */
    public void accumulate(LLMResponse response) {
        if (response == null) return;

        Usage usage = Usage.fromMap(response.getUsage());
        if (!usage.hasData()) return;

        callCount++;

        // 保存最后一次调用
        lastCall = usage.copy();

        // 累积到总数
        total.add(usage);

        // 记录历史
        if (recordHistory) {
            history.add(usage.copy());
        }

        log.debug("使用量累计: {} (总计: {})", usage.toShortString(), total.toShortString());
    }

    /**
     * 累加一个 Usage 对象
     */
    public void accumulate(Usage usage) {
        if (usage == null || !usage.hasData()) return;

        callCount++;

        // 保存最后一次调用
        lastCall = usage.copy();

        // 累积到总数
        total.add(usage);

        // 记录历史
        if (recordHistory) {
            history.add(usage.copy());
        }
    }

    /**
     * 重置累积器
     */
    public void reset() {
        total.reset();
        lastCall.reset();
        history.clear();
        callCount = 0;
    }

    // ==================== 获取方法 ====================

    /**
     * 获取累积的总 usage
     */
    public Usage getTotal() {
        return total.copy();
    }

    /**
     * 获取最后一次调用的 usage
     * 
     * 注意：这个值更准确地反映当前上下文大小，
     * 因为累积的 total 会重复计算工具调用循环中的 cacheRead
     */
    public Usage getLastCall() {
        return lastCall.copy();
    }

    /**
     * 获取当前上下文大小估算
     * 
     * 使用最后一次调用的 prompt tokens，而不是累积值
     */
    public long getContextSize() {
        return lastCall.getPromptTokens();
    }

    /**
     * 获取 API 调用次数
     */
    public int getCallCount() {
        return callCount;
    }

    /**
     * 获取历史记录
     */
    public List<Usage> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * 是否有有效数据
     */
    public boolean hasData() {
        return total.hasData();
    }

    // ==================== 配置 ====================

    public void setRecordHistory(boolean record) {
        this.recordHistory = record;
    }

    public boolean isRecordHistory() {
        return recordHistory;
    }

    // ==================== 报告 ====================

    /**
     * 生成使用报告
     */
    public String generateReport() {
        if (!hasData()) {
            return "无使用数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Usage Report ===\n");
        sb.append("API Calls: ").append(callCount).append("\n");
        sb.append("Total: ").append(total.toShortString()).append("\n");
        sb.append("Last Call: ").append(lastCall.toShortString()).append("\n");
        sb.append("Context Size: ~").append(formatTokens(getContextSize())).append(" tokens\n");

        if (recordHistory && !history.isEmpty()) {
            sb.append("\nHistory:\n");
            for (int i = 0; i < history.size(); i++) {
                sb.append("  Call ").append(i + 1).append(": ")
                  .append(history.get(i).toShortString()).append("\n");
            }
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

    // ==================== toString ====================

    @Override
    public String toString() {
        return String.format("UsageAccumulator{calls=%d, total=%s, lastCall=%s}",
                callCount, total.toShortString(), lastCall.toShortString());
    }
}