package context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 上下文溢出检测器
 *
 * 对齐 OpenClaw 的 isLikelyContextOverflowError / isContextOverflowError
 *
 * 功能：
 * - 检测 API 返回的错误是否为上下文溢出
 * - 提取溢出的 token 数量
 */
public class ContextOverflowDetector {

    private static final Logger log = LoggerFactory.getLogger(ContextOverflowDetector.class);

    // ==================== 正则表达式 ====================

    /** 上下文窗口过小的正则 */
    private static final Pattern CONTEXT_WINDOW_TOO_SMALL = 
            Pattern.compile("context window.*(too small|minimum is)", Pattern.CASE_INSENSITIVE);

    /** 上下文溢出提示的正则 */
    private static final Pattern CONTEXT_OVERFLOW_HINT = Pattern.compile(
            """
        context.*overflow|
        context window.*(too (?:large|long)|exceed|over|limit|max(?:imum)?|requested|sent|tokens)|
        prompt.*(too (?:large|long)|exceed|over|limit|max(?:imum)?)|
        (?:request|input).*(?:context|window|length|token).*(too (?:large|long)|exceed|over|limit|max(?:imum)?)|
        \\b(?:context|window|long|limit|tokens)\\b
""",
            Pattern.CASE_INSENSITIVE
    );

    /** 速率限制提示的正则 */
    private static final Pattern RATE_LIMIT_HINT = Pattern.compile(
            "rate limit|too many requests|requests per (?:minute|hour|day)|quota|throttl|429\\b|tokens per day",
            Pattern.CASE_INSENSITIVE
    );

    /** TPM (tokens per minute) 提示 */
    private static final Pattern TPM_HINT = Pattern.compile("\\btpm\\b|tokens per minute", Pattern.CASE_INSENSITIVE);

    /** 推理约束错误 */
    private static final Pattern REASONING_CONSTRAINT = Pattern.compile(
            "reasoning is mandatory|reasoning is required|requires reasoning|reasoning.*cannot be disabled",
            Pattern.CASE_INSENSITIVE
    );

    /** 溢出 token 数量提取模式 */
    private static final Pattern[] OVERFLOW_TOKEN_PATTERNS = {
            Pattern.compile("prompt is too long:\\s*([\\d,]+)\\s+tokens\\s*>\\s*[\\d,]+\\s+maximum", Pattern.CASE_INSENSITIVE),
            Pattern.compile("requested\\s+([\\d,]+)\\s+tokens", Pattern.CASE_INSENSITIVE),
            Pattern.compile("resulted in\\s+([\\d,]+)\\s+tokens", Pattern.CASE_INSENSITIVE),
    };

    // ==================== 检测方法 ====================

    /**
     * 检测是否为上下文溢出错误
     */
    public static boolean isContextOverflowError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        String lower = errorMessage.toLowerCase();

        // Groq 使用 413 表示 TPM 限制，不是上下文溢出
        if (hasTpmHint(errorMessage)) {
            return false;
        }

        // 推理约束错误不是溢出
        if (isReasoningConstraintError(errorMessage)) {
            return false;
        }

        boolean hasRequestSizeExceeds = lower.contains("request size exceeds");
        boolean hasContextWindow = lower.contains("context window") ||
                lower.contains("context length") ||
                lower.contains("maximum context length");

        return lower.contains("request_too_large") ||
                lower.contains("request exceeds the maximum size") ||
                lower.contains("context length exceeded") ||
                lower.contains("maximum context length") ||
                lower.contains("prompt is too long") ||
                lower.contains("exceeds model context window") ||
                lower.contains("model token limit") ||
                (hasRequestSizeExceeds && hasContextWindow) ||
                lower.contains("context overflow:") ||
                lower.contains("exceed context limit") ||
                lower.contains("exceeds the model's maximum context") ||
                (lower.contains("max_tokens") && lower.contains("exceed") && lower.contains("context")) ||
                (lower.contains("input length") && lower.contains("exceed") && lower.contains("context")) ||
                (lower.contains("413") && lower.contains("too large")) ||
                // Anthropic API 和 OpenAI 兼容提供商
                lower.contains("context_window_exceeded") ||
                // 中文错误消息
                errorMessage.contains("上下文过长") ||
                errorMessage.contains("上下文超出") ||
                errorMessage.contains("上下文长度超") ||
                errorMessage.contains("超出最大上下文") ||
                errorMessage.contains("请压缩上下文");
    }

    /**
     * 检测是否可能是上下文溢出错误（启发式）
     */
    public static boolean isLikelyContextOverflowError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        // Groq TPM 限制
        if (hasTpmHint(errorMessage)) {
            return false;
        }

        // 推理约束错误
        if (isReasoningConstraintError(errorMessage)) {
            return false;
        }

        // 计费错误可能包含类似模式
        if (isBillingError(errorMessage)) {
            return false;
        }

        // 上下文窗口过小
        if (CONTEXT_WINDOW_TOO_SMALL.matcher(errorMessage).find()) {
            return false;
        }

        // 速率限制错误
        if (isRateLimitError(errorMessage)) {
            return false;
        }

        // 明确的上下文溢出错误
        if (isContextOverflowError(errorMessage)) {
            return true;
        }

        // 速率限制提示
        if (RATE_LIMIT_HINT.matcher(errorMessage).find()) {
            return false;
        }

        // 启发式检测
        return CONTEXT_OVERFLOW_HINT.matcher(errorMessage).find();
    }

    /**
     * 检测是否为压缩失败错误
     */
    public static boolean isCompactionFailureError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        String lower = errorMessage.toLowerCase();
        boolean hasCompactionTerm = lower.contains("summarization failed") ||
                lower.contains("auto-compaction") ||
                lower.contains("compaction failed") ||
                lower.contains("compaction");

        if (!hasCompactionTerm) {
            return false;
        }

        // 当存在压缩术语时，任何可能的溢出形状都是压缩失败
        if (isLikelyContextOverflowError(errorMessage)) {
            return true;
        }

        return lower.contains("context overflow");
    }

    /**
     * 提取溢出的 token 数量
     */
    public static Long extractOverflowTokenCount(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }

        for (Pattern pattern : OVERFLOW_TOKEN_PATTERNS) {
            var matcher = pattern.matcher(errorMessage);
            if (matcher.find()) {
                String rawCount = matcher.group(1).replace(",", "");
                try {
                    long count = Long.parseLong(rawCount);
                    if (count > 0) {
                        return count;
                    }
                } catch (NumberFormatException e) {
                    // 忽略解析错误
                }
            }
        }

        return null;
    }

    // ==================== 辅助方法 ====================

    private static boolean hasTpmHint(String message) {
        return TPM_HINT.matcher(message).find();
    }

    private static boolean isReasoningConstraintError(String message) {
        return REASONING_CONSTRAINT.matcher(message).find();
    }

    private static boolean isRateLimitError(String message) {
        String lower = message.toLowerCase();
        return lower.contains("rate limit") ||
                lower.contains("too many requests") ||
                lower.contains("429") ||
                lower.contains("quota") ||
                lower.contains("throttl");
    }

    private static boolean isBillingError(String message) {
        String lower = message.toLowerCase();
        return lower.contains("billing") ||
                lower.contains("insufficient credit") ||
                lower.contains("insufficient balance") ||
                lower.contains("payment required") ||
                lower.contains("402");
    }

    /**
     * 格式化上下文溢出错误消息
     */
    public static String formatOverflowError() {
        return "上下文溢出：提示词过大，超出模型限制。" +
                "请尝试 /new 开始新会话，或使用更大上下文的模型。";
    }
}