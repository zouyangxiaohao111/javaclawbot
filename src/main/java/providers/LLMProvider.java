package providers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 模型提供者抽象基类
 *
 * 对齐 Python:
 * - api_key / api_base
 * - _sanitize_empty_content(messages)
 * - chat(messages, tools=None, model=None, max_tokens=4096, temperature=0.7, reasoning_effort=None)
 * - get_default_model()
 */
public abstract class LLMProvider {

    protected final String apiKey;
    protected final String apiBase;

    protected LLMProvider(String apiKey, String apiBase) {
        this.apiKey = apiKey;
        this.apiBase = apiBase;
    }

    /**
     * 替换会触发提供者 400 的空内容（对齐 Python 的 _sanitize_empty_content）
     *
     * 规则：
     * 1) 若 content 是空字符串：
     *    - role=assistant 且存在 tool_calls（并且非空）：content 置为 null
     *    - 否则 content 置为 "(empty)"
     *
     * 2) 若 content 是列表：
     *    - 过滤掉：item 是 dict 且 type 为 text/input_text/output_text 且 text 为空
     *    - 若发生过滤：
     *      - filtered 非空：替换为 filtered
     *      - filtered 为空：
     *        - assistant 且存在 tool_calls（并且非空）：content = null
     *        - 否则 content = "(empty)"
     *
     * 说明：
     * - 这里的 messages/内容结构是“面向 provider 的原始 JSON”，不做强类型绑定。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeEmptyContent(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) return result;

        for (Map<String, Object> msg : messages) {
            if (msg == null) continue;

            Object content = msg.get("content");

            // 判断 assistant 是否“确实带工具调用”（对齐 Python: msg.get("tool_calls") 的 truthy 语义）
            boolean assistantHasToolCalls = isAssistantWithToolCalls(msg);

            // 1) content 为字符串且为空
            if (content instanceof String s && s.isEmpty()) {
                Map<String, Object> clean = new HashMap<>(msg);
                clean.put("content", assistantHasToolCalls ? null : "(empty)");
                result.add(clean);
                continue;
            }

            // 2) content 为列表：过滤空文本块
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

            // 无需清洗则原样加入
            result.add(msg);
        }

        return result;
    }

    /**
     * 判断是否为 assistant 且 tool_calls 字段存在且非空
     */
    @SuppressWarnings("unchecked")
    private static boolean isAssistantWithToolCalls(Map<String, Object> msg) {
        Object role = msg.get("role");
        if (!"assistant".equals(role)) return false;

        Object toolCalls = msg.get("tool_calls");
        if (toolCalls == null) return false;

        // tool_calls 通常是 List<Map<...>>
        if (toolCalls instanceof List<?> l) {
            return !l.isEmpty();
        }

        // 兼容某些实现用其他结构，只要非空就视为存在
        return true;
    }

    /**
     * 发送对话请求（异步）
     *
     * 对齐 Python:
     * - tools 可为空
     * - model 可为空（为空时通常使用默认模型）
     * - reasoning_effort 可为空（部分提供者支持）
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
     * 获取默认模型名（对齐 Python: get_default_model）
     */
    public abstract String getDefaultModel();

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
}