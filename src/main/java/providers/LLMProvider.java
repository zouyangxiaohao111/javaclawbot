package providers;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
@Slf4j
public abstract class LLMProvider {

    protected final String apiKey;
    protected final String apiBase;

    /** 重试延迟（秒），对齐 Python: _CHAT_RETRY_DELAYS = (1, 2, 4) */
    protected static final List<Integer> CHAT_RETRY_DELAYS = Arrays.asList(1, 2, 4);

    /** 瞬态错误标记，对齐 Python: _TRANSIENT_ERROR_MARKERS */
    protected static final List<String> TRANSIENT_ERROR_MARKERS = Arrays.asList(
            "429",
            "rate limit",
            "500",
            "502",
            "503",
            "504",
            "overloaded",
            "timeout",
            "timed out",
            "connection",
            "server error",
            "temporarily unavailable"
    );

    protected LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    /**
     * 替换会触发提供者 400 的空内容（对齐 Python 的 _sanitize_empty_content）
     */
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
     * 发送对话请求（异步）
     */
    public abstract CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    );

    /**
     * 获取默认模型名
     */
    public abstract String getDefaultModel();

    /**
     * 带重试的对话请求（对齐 Python: chat_with_retry）
     *
     * 重试逻辑：
     * 1. 捕获异常，返回 finish_reason="error" 的响应
     * 2. 如果是瞬态错误（429、500、502、503、504、timeout 等），延迟后重试
     * 3. 最多重试 3 次（延迟 1s、2s、4s）
     */
    public CompletableFuture<LLMResponse> chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        return chatWithRetryInternal(messages, tools, model, maxTokens, temperature, reasoningEffort, 0);
    }

    private CompletableFuture<LLMResponse> chatWithRetryInternal(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            int attempt
    ) {
        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort)
                .exceptionally(ex -> {
                    log.error("调用LLM失败", ex);

                    // 捕获异常，返回错误响应
                    return new LLMResponse(
                            "调用 LLM 失败: " + ex.getMessage(),
                            null,
                            "error",
                            null,
                            null,
                            null
                    );
                })
                .thenCompose(response -> {
                    // 如果不是错误，直接返回
                    if (!"error".equals(response.getFinishReason())) {
                        return CompletableFuture.completedFuture(response);
                    }

                    // 如果不是瞬态错误，直接返回
                    /*if (!isTransientError(response.getContent())) {
                        return CompletableFuture.completedFuture(response);
                    }*/

                    // 检查是否还有重试机会
                    if (attempt >= CHAT_RETRY_DELAYS.size()) {
                        // 最后一次尝试
                        return chat(messages, tools, model, maxTokens, temperature, reasoningEffort)
                                .exceptionally(ex -> new LLMResponse(
                                        "Error calling LLM: " + ex.getMessage(),
                                        null,
                                        "error",
                                        null,
                                        null,
                                        null
                                ));
                    }

                    // 延迟后重试
                    int delayMs = CHAT_RETRY_DELAYS.get(attempt) * 100;
                    String errPreview = response.getContent() != null && response.getContent().length() > 120
                            ? response.getContent().substring(0, 120)
                            : response.getContent();

                    System.getLogger(LLMProvider.class.getName()).log(System.Logger.Level.WARNING,
                            "LLM transient error (attempt {0}/{1}), retrying in {2}s: {3}",
                            attempt + 1, CHAT_RETRY_DELAYS.size(), delayMs, errPreview);

                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            TimeUnit.MICROSECONDS.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }).thenCompose(v -> chatWithRetryInternal(
                            messages, tools, model, maxTokens, temperature, reasoningEffort, attempt + 1
                    ));
                });
    }

    /**
     * 便捷重载：不传 reasoning_effort
     */
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature
    ) {
        return chat(messages, tools, model, maxTokens, temperature, null);
    }

    /**
     * 便捷重载：带重试，不传 reasoning_effort
     */
    public CompletableFuture<LLMResponse> chatWithRetry(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature
    ) {
        return chatWithRetry(messages, tools, model, maxTokens, temperature, null);
    }
}