package agent.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.tool.AnySearchConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * AnySearch 搜索引擎实现。
 * API 文档：https://www.anysearch.com/docs
 *
 * 所有过滤参数（domains/content_types/zone/language/freshness 等）
 * 均由 AI 在运行时通过工具参数动态指定，不从配置文件读取。
 */
@Slf4j
public class AnySearchEngine implements SearchEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_ENDPOINT = "https://api.anysearch.com/v1/search";
    private static final int MAX_RESULTS_CAP = 10;

    private final HttpClient http;
    private final String apiKey;

    public AnySearchEngine(AnySearchConfig config, String proxy) {
        this.apiKey = (config != null ? config : new AnySearchConfig()).resolveApiKey();

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);

        if (proxy != null && !proxy.isBlank()) {
            builder.proxy(createProxySelector(proxy));
        }

        this.http = builder.build();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CompletionStage<String> search(String query, int count, Map<String, Object> args) {
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture("Error: query is required");
        }

        int n = Math.min(Math.max(count, 1), MAX_RESULTS_CAP);

        log.debug("AnySearch query={}, count={}", query, n);

        // 构建请求体：query + max_results + AI 指定的过滤参数
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        body.put("max_results", n);

        // 所有过滤参数均从 runtime args 读取
        mergeStringArray(body, "domains", args.get("domains"));
        mergeStringArray(body, "content_types", args.get("contentTypes"));
        mergeString(body, "zone", args.get("zone"));
        mergeString(body, "language", args.get("language"));
        mergeStringArray(body, "providers", args.get("providers"));
        mergeStringArray(body, "tags", args.get("tags"));

        // constraint: freshness / from / to
        Object freshnessVal = args.get("freshness");
        Object fromVal = args.get("from");
        Object toVal = args.get("to");
        if (freshnessVal != null || fromVal != null || toVal != null) {
            ObjectNode constraint = MAPPER.createObjectNode();
            if (freshnessVal != null) constraint.put("freshness", String.valueOf(freshnessVal));
            if (fromVal != null) constraint.put("from", String.valueOf(fromVal));
            if (toVal != null) constraint.put("to", String.valueOf(toVal));
            body.set("constraint", constraint);
        }

        String jsonBody;
        try {
            jsonBody = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.error("AnySearch 序列化请求体失败: {}", e.getMessage());
            return CompletableFuture.completedFuture(
                    "Error: failed to serialize request body: " + e.getMessage());
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(API_ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest req = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        log.debug("AnySearch 请求: {}", jsonBody);

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    int status = resp.statusCode();
                    String respBody = resp.body();

                    if (log.isDebugEnabled()) {
                        log.debug("AnySearch 响应 status={}, bodyLen={}",
                                status, respBody != null ? respBody.length() : 0);
                    }

                    if (status / 100 != 2) {
                        return handleErrorResponse(status, respBody, query);
                    }

                    try {
                        JsonNode root = MAPPER.readTree(respBody);
                        // 实际响应格式: {"code":0,"message":"success","data":{"results":[...],"metadata":{...}}}
                        JsonNode data = root.path("data");
                        JsonNode results = data.path("results");

                        if (!results.isArray() || results.size() == 0) {
                            log.debug("AnySearch 无结果: query={}", query);
                            return "No results for: " + query;
                        }

                        JsonNode metadata = data.path("metadata");
                        long totalResults = metadata.path("total_results").asLong(0);
                        long searchTimeMs = metadata.path("search_time_ms").asLong(0);

                        List<String> lines = new ArrayList<>();
                        lines.add("Results for: " + query);
                        if (totalResults > 0) {
                            lines.add("(" + totalResults + " results, " + searchTimeMs + "ms)");
                        }
                        lines.add("");

                        int i = 0;
                        for (JsonNode item : results) {
                            if (i >= n) break;
                            i++;
                            String title = item.path("title").asText("");
                            String url = item.path("url").asText("");
                            String content = item.path("content").asText("");
                            String source = item.path("source").asText("web");
                            String published = item.path("published_at").asText("");

                            lines.add(i + ". " + title);
                            lines.add("   " + url);
                            if (!content.isBlank()) {
                                String snippet = content.length() > 200
                                        ? content.substring(0, 200) + "..." : content;
                                lines.add("   " + snippet);
                            }

                            StringBuilder meta = new StringBuilder();
                            if (!source.isBlank() && !"web".equals(source)) {
                                meta.append("[").append(source).append("]");
                            }
                            if (!published.isBlank()) {
                                String shortDate = published.length() >= 10
                                        ? published.substring(0, 10) : published;
                                if (meta.length() > 0) meta.append(" ");
                                meta.append(shortDate);
                            }
                            if (meta.length() > 0) {
                                lines.add("   " + meta.toString().trim());
                            }
                        }

                        log.debug("AnySearch 成功: {} 条结果, query={}", i, query);
                        return String.join("\n", lines);

                    } catch (Exception e) {
                        log.error("AnySearch 解析响应失败: {}", e.getMessage(), e);
                        return "Error: failed to parse search response: " + e.getMessage();
                    }
                })
                .exceptionally(ex -> {
                    log.error("AnySearch 请求异常: {}", ex.getMessage(), ex);
                    String msg = rootMessage(ex);
                    if (msg != null && (msg.contains("proxy") || msg.contains("Proxy"))) {
                        return "Proxy error: " + msg;
                    }
                    return "Error: " + msg;
                });
    }

    /**
     * 处理非 2xx 错误响应
     */
    private String handleErrorResponse(int status, String respBody, String query) {
        log.warn("AnySearch API 错误 status={}, query={}", status, query);

        try {
            JsonNode err = MAPPER.readTree(respBody);
            String code = err.path("code").asText("");
            String message = err.path("message").asText("");
            String requestId = err.path("data").path("request_id").asText("");

            StringBuilder sb = new StringBuilder();
            sb.append("Error: AnySearch API HTTP ").append(status);
            if (!code.isBlank()) {
                sb.append(" (code=").append(code).append(")");
            }
            if (!message.isBlank()) {
                sb.append(" - ").append(message);
            }
            if (!requestId.isBlank()) {
                sb.append(" [request_id: ").append(requestId).append("]");
            }

            if (status == 402) {
                JsonNode data = err.path("data");
                if (!data.isMissingNode()) {
                    sb.append("\nQuota: limit=").append(data.path("quota_limit").asText("?"))
                      .append(", used=").append(data.path("quota_used").asText("?"))
                      .append(", remaining=").append(data.path("quota_remaining").asText("?"));
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error: AnySearch API HTTP " + status + "\n"
                    + safeTrim(respBody, 2000);
        }
    }

    // ---------- 工具方法 ----------

    private void mergeString(ObjectNode body, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            body.put(key, String.valueOf(value));
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeStringArray(ObjectNode body, String key, Object value) {
        if (value == null) return;
        if (value instanceof List<?> list && !list.isEmpty()) {
            var arr = MAPPER.createArrayNode();
            for (Object item : list) {
                arr.add(String.valueOf(item));
            }
            body.set(key, arr);
        }
    }

    private static ProxySelector createProxySelector(String proxyUrl) {
        try {
            URI uri = URI.create(proxyUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) {
                String[] parts = proxyUrl.split(":");
                if (parts.length == 2) {
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                } else {
                    throw new IllegalArgumentException("Invalid proxy URL: " + proxyUrl);
                }
            }
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse proxy URL: " + proxyUrl, e);
        }
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... (truncated)";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }
}
