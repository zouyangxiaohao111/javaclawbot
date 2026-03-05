package providers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 直连 OpenAI 兼容接口的提供者（绕过中间层）
 *
 * 语义对齐 Python CustomProvider：
 * - chat(): 构造 kwargs：
 *   - model: model 或 default_model
 *   - messages: sanitize_empty_content
 *   - max_tokens: max(1, max_tokens)
 *   - temperature
 *   - reasoning_effort: 若传入则追加到请求体
 *   - tools: 若存在则 tools + tool_choice="auto"
 * - 异常：返回 LLMResponse(content="Error: ...", finish_reason="error")
 * - 解析：
 *   - choice = choices[0]
 *   - msg = choice.message
 *   - tool_calls：从 msg.tool_calls 读取
 *     - arguments 若为字符串：按 Python 的 json_repair.loads 语义尽量修复解析
 * - usage：prompt/completion/total
 * - reasoning_content：若存在则读取，否则 null
 */
public final class CustomProvider extends LLMProvider {

    private static final Logger LOG = Logger.getLogger(CustomProvider.class.getName());

    private final String defaultModel;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * @param apiKey        API Key（为空则使用 "no-key"）
     * @param apiBase       API Base（为空则使用 "http://localhost:8000/v1"）
     * @param defaultModel  默认模型名（为空则使用 "default"）
     */
    public CustomProvider(String apiKey, String apiBase, String defaultModel) {
        super(
                (apiKey == null || apiKey.isBlank()) ? "no-key" : apiKey,
                (apiBase == null || apiBase.isBlank()) ? "http://localhost:8000/v1" : apiBase
        );
        this.defaultModel = (defaultModel == null || defaultModel.isBlank()) ? "default" : defaultModel;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    public CustomProvider() {
        this("no-key", "http://localhost:8000/v1", "default");
    }

    /**
     * 对齐 Python: chat(..., reasoning_effort=None)
     */
    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        String useModel = (model == null || model.isBlank()) ? defaultModel : model;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("messages", LLMProvider.sanitizeEmptyContent(messages));
        body.put("max_tokens", Math.max(1, maxTokens));
        body.put("temperature", temperature);

        // Python：if reasoning_effort: kwargs["reasoning_effort"] = reasoning_effort
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning_effort", reasoningEffort);
        }

        // Python：if tools: kwargs.update(tools=tools, tool_choice="auto")
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        String url = normalizeBase(apiBase) + "/chat/completions";

        final String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            // 对齐 Python：异常 -> LLMResponse(content="Error: ...", finish_reason="error")
            return CompletableFuture.completedFuture(errorResponse(e));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(600))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        // 对齐 Python：也走错误 LLMResponse
                        String msg = "Error: HTTP " + resp.statusCode() + " " + safe(resp.body());
                        LLMResponse r = new LLMResponse();
                        r.setContent(msg);
                        r.setFinishReason("error");
                        return r;
                    }
                    try {
                        return parse(resp.body());
                    } catch (Exception e) {
                        return errorResponse(e);
                    }
                })
                .exceptionally(ex -> errorResponse(ex));
    }

    /**
     * 兼容旧签名（如果你的工程仍在调用 5 参版本）
     */
    @Override
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
     * 解析 OpenAI 兼容响应（对齐 Python _parse）
     */
    private LLMResponse parse(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode choices = root.path("choices");

        if (!choices.isArray() || choices.isEmpty()) {
            // Python 里 choices[0] 会抛异常；但这里容错返回空
            return new LLMResponse("", List.of(), "stop", Map.of(), null, null);
        }

        JsonNode choice0 = choices.get(0);
        JsonNode message = choice0.path("message");

        // msg.content 可能为 null
        String content = message.hasNonNull("content") ? message.get("content").asText() : null;

        // choice.finish_reason 或 "stop"
        String finishReason = choice0.hasNonNull("finish_reason")
                ? choice0.get("finish_reason").asText("stop")
                : "stop";

        // tool_calls：对齐 Python: (msg.tool_calls or [])
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        JsonNode tcs = message.get("tool_calls");
        if (tcs != null && tcs.isArray()) {
            for (JsonNode tc : tcs) {
                String id = tc.path("id").asText();
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText();

                // arguments 可能是字符串或对象
                JsonNode argsNode = fn.get("arguments");

                Map<String, Object> args = new LinkedHashMap<>();
                if (argsNode != null && !argsNode.isNull()) {
                    if (argsNode.isTextual()) {
                        // Python 使用 json_repair.loads：尽最大努力把字符串解析为 JSON
                        args = parseToolArgumentsWithRepair(argsNode.asText());
                    } else if (argsNode.isObject()) {
                        args = objectMapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() {});
                    } else {
                        // 其他类型：保底记录 raw
                        args.put("raw", argsNode.toString());
                    }
                }

                toolCalls.add(new ToolCallRequest(id, name, args));
            }
        }

        // usage（对齐 Python：u = response.usage）
        Map<String, Integer> usage = new LinkedHashMap<>();
        JsonNode u = root.get("usage");
        if (u != null && u.isObject()) {
            usage.put("prompt_tokens", u.path("prompt_tokens").asInt(0));
            usage.put("completion_tokens", u.path("completion_tokens").asInt(0));
            usage.put("total_tokens", u.path("total_tokens").asInt(0));
        }

        // reasoning_content（对齐 Python：getattr(msg, "reasoning_content", None) or None）
        String reasoning = message.hasNonNull("reasoning_content")
                ? nullIfBlank(message.get("reasoning_content").asText())
                : null;

        return new LLMResponse(content, toolCalls, finishReason, usage, reasoning, null);
    }

    /**
     * 尽最大努力解析工具参数字符串（对齐 Python json_repair.loads 的目标）
     *
     * 说明：
     * - 如果是标准 JSON：直接解析为 Map
     * - 如果存在常见的尾逗号、缺失引号等情况：先做轻量修复再尝试解析
     * - 若仍失败：返回 {"raw": 原始字符串}
     *
     * 注意：
     * - 这里不会实现完整 json_repair 的所有能力，但会覆盖常见损坏场景，保证“尽力修复”的语义。
     */
    private Map<String, Object> parseToolArgumentsWithRepair(String s) {
        if (s == null) return Map.of();
        String raw = s;

        // 第一次尝试：直接当 JSON 解析
        Map<String, Object> direct = tryParseJsonMap(raw);
        if (direct != null) return direct;

        // 轻量修复：去掉代码块包裹
        String repaired = stripCodeFences(raw);

        // 轻量修复：尝试提取第一个 {...} 或 [...]
        String extracted = extractFirstJsonObjectOrArray(repaired);
        if (extracted != null) {
            Map<String, Object> m = tryParseJsonMap(extracted);
            if (m != null) return m;

            // 若是数组则包装成 {"raw": ...} 或 {"_args": [...]}
            List<Object> arr = tryParseJsonList(extracted);
            if (arr != null) {
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("_args", arr);
                return wrap;
            }
        }

        // 轻量修复：移除常见尾逗号（对象/数组末尾）
        String noTrailingCommas = removeTrailingCommas(repaired);
        Map<String, Object> m2 = tryParseJsonMap(noTrailingCommas);
        if (m2 != null) return m2;

        // 实在不行：保留原始字符串
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("raw", raw);
        return fallback;
    }

    private Map<String, Object> tryParseJsonMap(String s) {
        try {
            return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Object> tryParseJsonList(String s) {
        try {
            return objectMapper.readValue(s, new TypeReference<List<Object>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 去掉 ```json ... ``` 这类代码块包裹
     */
    private static String stripCodeFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    /**
     * 从字符串中提取第一个 JSON 对象或数组片段（{...} 或 [...]）
     */
    private static String extractFirstJsonObjectOrArray(String s) {
        if (s == null) return null;
        int obj = s.indexOf('{');
        int arr = s.indexOf('[');

        int start;
        char open;
        char close;

        if (obj < 0 && arr < 0) return null;
        if (obj >= 0 && (arr < 0 || obj < arr)) {
            start = obj; open = '{'; close = '}';
        } else {
            start = arr; open = '['; close = ']';
        }

        int depth = 0;
        boolean inString = false;
        char prev = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '"' && prev != '\\') {
                inString = !inString;
            }
            if (!inString) {
                if (c == open) depth++;
                if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                }
            }
            prev = c;
        }
        return null;
    }

    /**
     * 移除对象/数组末尾常见的尾逗号
     * 例：{"a":1,} -> {"a":1}
     */
    private static String removeTrailingCommas(String s) {
        if (s == null) return null;
        // 简单替换：逗号后面紧跟 ] 或 } 的情况
        return s.replaceAll(",\\s*([}\\]])", "$1");
    }

    private static String nullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static LLMResponse errorResponse(Throwable e) {
        LLMResponse r = new LLMResponse();
        r.setContent("Error: " + String.valueOf(e));
        r.setFinishReason("error");
        return r;
    }

    private static String normalizeBase(String base) {
        if (base == null) return "";
        String b = base.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String safe(String s) {
        if (s == null) return "";
        if (s.length() <= 2000) return s;
        return s.substring(0, 2000) + "...";
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }
}