package providers;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 模型提供者抽象基类
 *
 * 对齐 Python:
 * - api_key / api_base
 * - _sanitize_empty_content(messages)
 * - chat(messages, tools=None, model=None, max_tokens=4096, temperature=0.7, reasoning_effort=None)
 * - chat_with_retry() - 重试逻辑
 * - get_default_model()
 */
public abstract class LLMProvider {

    public static Logger log = LoggerFactory.getLogger(LLMProvider.class);

    protected final String apiKey;
    protected final String apiBase;

    /** 重试延迟（秒），对齐 Python: _CHAT_RETRY_DELAYS = (1, 2, 4) */
    protected static final List<Integer> CHAT_RETRY_DELAYS = Arrays.asList(1, 2, 4);

    /** 瞬态错误标记（仅 rate limit，不重试） */
    protected static final List<String> TRANSIENT_ERROR_MARKERS = Arrays.asList(
            "429",
            "rate limit"
    );

    protected LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeEmptyContent(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) return result;

        for (Map<String, Object> msg : messages) {
            if (msg == null) continue;

            Object content = msg.get("content");
            boolean assistantHasToolCalls = isAssistantWithToolCalls(msg);

            if (content instanceof String s && s.isEmpty()) {
                Map<String, Object> clean = new HashMap<>(msg);
                clean.put("content", assistantHasToolCalls ? null : "(empty)");
                result.add(clean);
                continue;
            }

            if (content instanceof List<?> list) {
                List<Object> filtered = new ArrayList<>();
                boolean changed = false;

                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Object type = m.get("type");
                        if (type instanceof String t
                                && (t.equals("text") || t.equals("input_text") || t.equals("output_text"))) {
                            Object text = m.get("text");
                            if (text == null || (text instanceof String ts && ts.isEmpty())) {
                                changed = true;
                                continue;
                            }
                        }
                    }
                    filtered.add(item);
                }

                if (changed) {
                    Map<String, Object> clean = new HashMap<>(msg);
                    if (!filtered.isEmpty()) {
                        clean.put("content", filtered);
                    } else {
                        clean.put("content", assistantHasToolCalls ? null : "(empty)");
                    }
                    result.add(clean);
                    continue;
                }
            }

            result.add(msg);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static boolean isAssistantWithToolCalls(Map<String, Object> msg) {
        Object role = msg.get("role");
        if (!"assistant".equals(role)) return false;

        Object toolCalls = msg.get("tool_calls");
        if (toolCalls == null) return false;

        if (toolCalls instanceof List<?> l) {
            return !l.isEmpty();
        }

        return true;
    }

    /**
     * 判断是否为瞬态错误（对齐 Python: _is_transient_error）
     */
    protected static boolean isTransientError(String content) {
        if (content == null || content.isEmpty()) return false;
        String lower = content.toLowerCase();
        for (String marker : TRANSIENT_ERROR_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否已取消，若取消则抛出异常
     */
    private void checkCancelled(CancelChecker cancelChecker) {
        if (cancelChecker != null && cancelChecker.isCancelled()) {
            throw new CancellationException("LLM request cancelled");
        }
    }

    /**
     * 创建错误响应
     */
    private LLMResponse errorResponse(String message) {
        return new LLMResponse(message, null, "error", null, null, null);
    }

    /**
     * 判断是否需要重试
     * - 非错误响应：不重试
     * - Rate limit 错误（isTransientError=true）：不重试
     * - 其他错误：重试
     */
    private boolean shouldRetry(LLMResponse response) {
        if (!"error".equals(response.getFinishReason())) {
            return false;
        }
        // isTransientError 识别 rate limit
        // rate limit 不重试，其他错误重试
        return !isTransientError(response.getContent());
    }

    /**
     * 统一处理异常
     */
    private LLMResponse handleException(Throwable ex, CancelChecker cancelChecker) {
        Throwable root = (ex instanceof CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;

        if (root instanceof CancellationException) {
            throw new CompletionException(root);
        }

        log.error("调用LLM失败", root);
        return errorResponse("调用 LLM 失败: " + root.getMessage());
    }

    /**
     * 延迟执行，支持取消检查
     */
    private CompletableFuture<Void> delayWithCancelCheck(int delayMs, CancelChecker cancelChecker) {
        return CompletableFuture.runAsync(() -> {
            long slept = 0L;
            while (slept < delayMs) {
                checkCancelled(cancelChecker);
                long sleepMs = Math.min(100L, delayMs - slept);
                try {
                    TimeUnit.MILLISECONDS.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
                slept += sleepMs;
            }
        });
    }

    /**
     * 记录重试日志
     */
    private void logRetry(int attempt, int delayMs, String content) {
        String preview = content != null && content.length() > 120
                ? content.substring(0, 120) : content;
        log.warn("LLM error (attempt {}/{}), retrying in {}ms: {}",
                attempt + 1, CHAT_RETRY_DELAYS.size(), delayMs, preview);
    }

    /**
     * 执行重试逻辑
     */
    private CompletableFuture<LLMResponse> retryTransientError(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Map<String, Object> think,
            Map<String, Object> extraBody,
            int attempt,
            CancelChecker cancelChecker,
            LLMResponse lastError
    ) {
        if (attempt >= CHAT_RETRY_DELAYS.size()) {
            return CompletableFuture.completedFuture(lastError);
        }

        int delayMs = CHAT_RETRY_DELAYS.get(attempt) * 1000;
        logRetry(attempt, delayMs, lastError.getContent());

        return delayWithCancelCheck(delayMs, cancelChecker)
                .thenCompose(v -> chatWithRetryInternal(
                        messages, tools, model, maxTokens, temperature,
                        reasoningEffort, think, extraBody, attempt + 1, cancelChecker
                ));
    }

    /**
     * 发送对话请求（异步）- 主方法
     *
     * @param think         思考参数（不同模型格式不同），如 {"type": "enabled", "clear_thinking": false}
     *                      非null时会合并到请求 body 的 "thinking" 字段
     * @param extraBody     额外请求参数，直接合并到请求 body 中
     */
    public abstract CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Map<String, Object> think,
            Map<String, Object> extraBody,
            CancelChecker cancelChecker
    );


    public abstract String getDefaultModel();

    /**
     * 带重试的聊天请求（主方法）
     */
    public CompletableFuture<LLMResponse> chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Map<String, Object> think,
            Map<String, Object> extraBody,
            CancelChecker cancelChecker
    ) {
        return chatWithRetryInternal(
                messages, tools, model, maxTokens, temperature, reasoningEffort, think, extraBody, 0, cancelChecker
        );
    }

    /**
     * 兼容旧签名
     */
    public CompletableFuture<LLMResponse> chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        return chatWithRetry(messages, tools, model, maxTokens, temperature, reasoningEffort, null, null, null);
    }

    private CompletableFuture<LLMResponse> chatWithRetryInternal(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Map<String, Object> think,
            Map<String, Object> extraBody,
            int attempt,
            CancelChecker cancelChecker
    ) {
        // 前置取消检查
        if (cancelChecker != null && cancelChecker.isCancelled()) {
            return CompletableFuture.failedFuture(new CancellationException("LLM request cancelled"));
        }

        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort, think, extraBody, cancelChecker)
                .thenApply(response -> {
                    checkCancelled(cancelChecker);
                    return response;
                })
                .exceptionally(ex -> handleException(ex, cancelChecker))
                .thenCompose(response -> {
                    if (!shouldRetry(response)) {
                        return CompletableFuture.completedFuture(response);
                    }
                    return retryTransientError(messages, tools, model, maxTokens, temperature,
                            reasoningEffort, think, extraBody, attempt, cancelChecker, response);
                });
    }

    public CompletableFuture<LLMResponse> chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature
    ) {
        return chatWithRetry(messages, tools, model, maxTokens, temperature, null, null, null, null);
    }
}