package providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Azure OpenAI Provider 实现
 *
 * 对齐 Python: azure_openai_provider.py
 *
 * 特性：
 * - API 版本 2024-10-21
 * - 使用 api-key header 而非 Authorization Bearer
 * - 使用 max_completion_tokens 而非 max_tokens
 * - model 字段作为 Azure deployment name
 */
public class AzureOpenAIProvider extends LLMProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_VERSION = "2024-10-21";

    private final String defaultModel;
    private final HttpClient http;

    public AzureOpenAIProvider(String apiKey, String apiBase, String defaultModel) {
        super(apiKey, normalizeApiBase(apiBase));

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Azure OpenAI api_key is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            throw new IllegalArgumentException("Azure OpenAI api_base is required");
        }

        this.defaultModel = (defaultModel != null && !defaultModel.isBlank()) ? defaultModel : "gpt-4o";

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
    }

    private static String normalizeApiBase(String apiBase) {
        if (apiBase == null || apiBase.isBlank()) return apiBase;
        return apiBase.endsWith("/") ? apiBase : apiBase + "/";
    }

    private String buildChatUrl(String deploymentName) {
        // Azure OpenAI URL format:
        // https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version={version}
        return apiBase + "openai/deployments/" + deploymentName + "/chat/completions?api-version=" + API_VERSION;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("api-key", apiKey);  // Azure OpenAI 使用 api-key header
        headers.put("x-session-affinity", UUID.randomUUID().toString().replace("-", ""));
        return headers;
    }

    private static boolean supportsTemperature(String deploymentName, String reasoningEffort) {
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            return false;
        }
        String name = deploymentName.toLowerCase(Locale.ROOT);
        return !name.contains("gpt-5") && !name.contains("o1") && !name.contains("o3") && !name.contains("o4");
    }

    private Map<String, Object> preparePayload(
            String deploymentName,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // 清理消息
        List<Map<String, Object>> sanitized = sanitizeEmptyContent(messages);
        List<Map<String, Object>> cleaned = sanitizeRequestMessages(sanitized);
        payload.put("messages", cleaned);

        // Azure API 2024-10-21 使用 max_completion_tokens
        payload.put("max_completion_tokens", Math.max(1, maxTokens));

        if (supportsTemperature(deploymentName, reasoningEffort)) {
            payload.put("temperature", temperature);
        }

        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            payload.put("reasoning_effort", reasoningEffort);
        }

        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }

        return payload;
    }

    /**
     * 清理请求消息，只保留 Azure 支持的字段
     */
    private static List<Map<String, Object>> sanitizeRequestMessages(List<Map<String, Object>> messages) {
        Set<String> allowedKeys = Set.of("role", "content", "tool_calls", "tool_call_id", "name");
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : msg.entrySet()) {
                if (allowedKeys.contains(e.getKey())) {
                    cleaned.put(e.getKey(), e.getValue());
                }
            }
            result.add(cleaned);
        }

        return result;
    }

    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        String deploymentName = (model != null && !model.isBlank()) ? model : defaultModel;
        String url = buildChatUrl(deploymentName);
        Map<String, String> headers = buildHeaders();
        Map<String, Object> payload = preparePayload(deploymentName, messages, tools, maxTokens, temperature, reasoningEffort);

        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new LLMResponse(
                    "Error serializing request: " + e.getMessage(),
                    null,
                    "error",
                    null,
                    null,
                    null
            ));
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        for (Map.Entry<String, String> h : headers.entrySet()) {
            reqBuilder.header(h.getKey(), h.getValue());
        }

        HttpRequest req = reqBuilder.build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        return new LLMResponse(
                                "Azure OpenAI API Error " + resp.statusCode() + ": " + resp.body(),
                                null,
                                "error",
                                null,
                                null,
                                null
                        );
                    }

                    try {
                        return parseResponse(MAPPER.readTree(resp.body()));
                    } catch (Exception e) {
                        return new LLMResponse(
                                "Error parsing Azure OpenAI response: " + e.getMessage(),
                                null,
                                "error",
                                null,
                                null,
                                null
                        );
                    }
                })
                .exceptionally(ex -> new LLMResponse(
                        "Error calling Azure OpenAI: " + rootMessage(ex),
                        null,
                        "error",
                        null,
                        null,
                        null
                ));
    }

    private LLMResponse parseResponse(JsonNode root) {
        try {
            JsonNode choice = root.path("choices").get(0);
            JsonNode message = choice.path("message");

            List<ToolCallRequest> toolCalls = new ArrayList<>();
            JsonNode tcNode = message.get("tool_calls");
            if (tcNode != null && tcNode.isArray()) {
                for (JsonNode tc : tcNode) {
                    String id = tc.path("id").asText("");
                    String name = tc.path("function").path("name").asText("");
                    JsonNode argsNode = tc.path("function").path("arguments");

                    Map<String, Object> args;
                    if (argsNode.isObject()) {
                        args = MAPPER.convertValue(argsNode, Map.class);
                    } else if (argsNode.isTextual()) {
                        args = MAPPER.readValue(argsNode.asText(), Map.class);
                    } else {
                        args = new HashMap<>();
                    }

                    toolCalls.add(new ToolCallRequest(id, name, args));
                }
            }

            Map<String, Integer> usage = new HashMap<>();
            JsonNode usageNode = root.get("usage");
            if (usageNode != null) {
                usage.put("prompt_tokens", usageNode.path("prompt_tokens").asInt(0));
                usage.put("completion_tokens", usageNode.path("completion_tokens").asInt(0));
                usage.put("total_tokens", usageNode.path("total_tokens").asInt(0));
            }

            String content = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText()
                    : null;

            String reasoningContent = message.has("reasoning_content") && !message.get("reasoning_content").isNull()
                    ? message.get("reasoning_content").asText()
                    : null;

            String finishReason = choice.has("finish_reason")
                    ? choice.get("finish_reason").asText("stop")
                    : "stop";

            return new LLMResponse(content, toolCalls, finishReason, usage, reasoningContent, null);
        } catch (Exception e) {
            return new LLMResponse(
                    "Error parsing Azure OpenAI response: " + e.getMessage(),
                    null,
                    "error",
                    null,
                    null,
                    null
            );
        }
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }
}