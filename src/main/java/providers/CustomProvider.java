package providers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
public final class CustomProvider extends LLMProvider {

    private final String defaultModel;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CustomProvider(String apiKey, String apiBase, String defaultModel) {
        this(apiKey, apiBase, defaultModel, 120);
    }

    public CustomProvider(String apiKey, String apiBase, String defaultModel, int timeoutSeconds) {
        super(
                (apiKey == null || apiKey.isBlank()) ? "no-key" : apiKey,
                (apiBase == null || apiBase.isBlank()) ? "http://localhost:8000/v1" : apiBase
        );
        this.defaultModel = (defaultModel == null || defaultModel.isBlank()) ? "default" : defaultModel;

        int timeout = timeoutSeconds > 0 ? timeoutSeconds : 120;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    public CustomProvider() {
        this("no-key", "http://localhost:8000/v1", "default");
    }

    @Override
    public CompletableFuture<LLMResponse> chat(
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
        String useModel = (model == null || model.isBlank()) ? defaultModel : model;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("messages", LLMProvider.sanitizeEmptyContent(messages));
        body.put("max_tokens", Math.max(1, maxTokens));
        body.put("temperature", temperature);

        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning_effort", reasoningEffort);
        }

        // 思考模式：think 非空时添加到请求体
        if (think != null && !think.isEmpty()) {
            body.putAll(think);
        }

        // 额外请求参数：直接合并到请求体
        if (extraBody != null && !extraBody.isEmpty()) {
            body.putAll(extraBody);
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        String url = normalizeBase(apiBase) + "/chat/completions";

        final String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(errorResponse(e));
        }

        if (cancelChecker != null && cancelChecker.isCancelled()) {
            return CompletableFuture.failedFuture(new CancellationException("HTTP request cancelled before send"));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(600))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> rawFuture =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (cancelChecker != null) {
            CompletableFuture.runAsync(() -> {
                while (!rawFuture.isDone()) {
                    if (cancelChecker.isCancelled()) {
                        rawFuture.cancel(true);
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        return rawFuture.thenApply(resp -> {
            if (cancelChecker != null && cancelChecker.isCancelled()) {
                throw new CompletionException(new CancellationException("HTTP request cancelled"));
            }

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
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
        }).exceptionally(ex -> {
            Throwable root = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause()
                    : ex;

            if (root instanceof CancellationException) {
                throw new CompletionException(root);
            }

            return errorResponse(root);
        });
    }

    // ===== 下面保留你原来的 parse / repair / 工具方法，不需要改 =====

    private LLMResponse parse(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode choices = root.path("choices");

        if (!choices.isArray() || choices.isEmpty()) {
            return new LLMResponse("", List.of(), "stop", Map.of(), null, null);
        }

        JsonNode choice0 = choices.get(0);
        JsonNode message = choice0.path("message");

        String content = message.hasNonNull("content") ? message.get("content").asText() : null;

        String finishReason = choice0.hasNonNull("finish_reason")
                ? choice0.get("finish_reason").asText("stop")
                : "stop";

        List<ToolCallRequest> toolCalls = new ArrayList<>();
        JsonNode tcs = message.get("tool_calls");
        if (tcs != null && tcs.isArray()) {
            for (JsonNode tc : tcs) {
                String id = tc.path("id").asText();
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText();

                JsonNode argsNode = fn.get("arguments");

                Map<String, Object> args = new LinkedHashMap<>();
                if (argsNode != null && !argsNode.isNull()) {
                    if (argsNode.isTextual()) {
                        args = parseToolArgumentsWithRepair(argsNode.asText());
                    } else if (argsNode.isObject()) {
                        args = objectMapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() {});
                    } else {
                        args.put("raw", argsNode.toString());
                    }
                }

                toolCalls.add(new ToolCallRequest(id, name, args));
            }
        }

        Map<String, Integer> usage = new LinkedHashMap<>();
        JsonNode u = root.get("usage");
        if (u != null && u.isObject()) {
            usage.put("prompt_tokens", u.path("prompt_tokens").asInt(0));
            usage.put("completion_tokens", u.path("completion_tokens").asInt(0));
            usage.put("total_tokens", u.path("total_tokens").asInt(0));
        }

        // DeepSeek thinking mode 要求 reasoning_content 必须原样传回 API，
        // 即使是空字符串也不能丢，所以这里不用 nullIfBlank
        String reasoning = message.hasNonNull("reasoning_content")
                ? message.get("reasoning_content").asText()
                : null;

        return new LLMResponse(content, toolCalls, finishReason, usage, reasoning, null);
    }

    private Map<String, Object> parseToolArgumentsWithRepair(String s) {
        if (s == null) return Map.of();
        String raw = s;

        Map<String, Object> direct = tryParseJsonMap(raw);
        if (direct != null) return direct;

        String repaired = stripCodeFences(raw);

        String extracted = extractFirstJsonObjectOrArray(repaired);
        if (extracted != null) {
            Map<String, Object> m = tryParseJsonMap(extracted);
            if (m != null) return m;

            List<Object> arr = tryParseJsonList(extracted);
            if (arr != null) {
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("_args", arr);
                return wrap;
            }
        }

        String noTrailingCommas = removeTrailingCommas(repaired);
        Map<String, Object> m2 = tryParseJsonMap(noTrailingCommas);
        if (m2 != null) return m2;

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
        boolean escaped = false;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == open) depth++;
            if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String removeTrailingCommas(String s) {
        if (s == null) return null;
        return s.replaceAll(",\\s*([}\\]])", "$1");
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String normalizeBase(String base) {
        if (base == null || base.isBlank()) return "http://localhost:8000/v1";
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static LLMResponse errorResponse(Throwable e) {
        LLMResponse r = new LLMResponse();
        r.setContent("Error: " + (e == null ? "unknown" : e.getMessage()));
        r.setFinishReason("error");
        return r;
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }
}