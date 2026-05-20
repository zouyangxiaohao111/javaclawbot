package agent.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.tool.WebSearchConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
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
 * Brave Search 搜索引擎实现（保留原有逻辑）。
 * 从 WebSearchTool 中提取，当 config 中 engine 设置为 "brave" 时激活。
 */
@Slf4j
public class BraveSearchEngine implements SearchEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

    private final HttpClient http;
    private final String apiKey;
    private final int maxResults;

    public BraveSearchEngine(WebSearchConfig config, String proxy) {
        this.apiKey = resolveApiKey(config != null ? config.getApiKey() : null);
        int configured = (config != null && config.getMaxResults() > 0) ? config.getMaxResults() : 5;
        this.maxResults = Math.min(configured, 10);

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);

        if (proxy != null && !proxy.isBlank()) {
            builder.proxy(createProxySelector(proxy));
        }

        this.http = builder.build();
    }

    private static String resolveApiKey(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) return configuredKey.trim();
        String env = System.getenv("BRAVE_API_KEY");
        return env == null ? "" : env.trim();
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public CompletionStage<String> search(String query, int count, Map<String, Object> args) {
        if (!isAvailable()) {
            log.warn("Brave Search API Key 未配置");
            return CompletableFuture.completedFuture(
                    "Error: Brave Search API key not configured. " +
                    "Set it in ~/.javaclawbot/config.json under tools.web.search.apiKey " +
                    "(or export BRAVE_API_KEY), then restart the gateway."
            );
        }

        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture("Error: query is required");
        }

        int n = Math.min(Math.max(count, 1), 10);

        log.debug("BraveSearch query={}, count={}", query, n);

        String url = API_ENDPOINT
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + n;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();

        log.debug("BraveSearch 请求: url={}", url);

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        log.warn("BraveSearch API 错误 status={}, body={}",
                                resp.statusCode(), safeTrim(resp.body(), 2000));
                        return "Error: Brave Search API HTTP " + resp.statusCode()
                                + "\n" + safeTrim(resp.body(), 2000);
                    }
                    try {
                        JsonNode root = MAPPER.readTree(resp.body());
                        JsonNode results = root.path("web").path("results");
                        if (!results.isArray() || results.size() == 0) {
                            return "No results for: " + query;
                        }

                        List<String> lines = new ArrayList<>();
                        lines.add("Results for: " + query);
                        lines.add("");

                        int i = 0;
                        for (JsonNode item : results) {
                            if (i >= n) break;
                            i++;
                            String title = item.path("title").asText("");
                            String u = item.path("url").asText("");
                            String desc = item.path("description").asText("");

                            lines.add(i + ". " + title);
                            lines.add("   " + u);
                            if (!desc.isBlank()) lines.add("   " + desc);
                        }
                        log.debug("BraveSearch 成功: {} 条结果", i);
                        return String.join("\n", lines);
                    } catch (Exception e) {
                        log.error("BraveSearch 解析响应失败: {}", e.getMessage(), e);
                        return "Error: " + e.getMessage();
                    }
                })
                .exceptionally(ex -> {
                    log.error("BraveSearch 请求异常: {}", ex.getMessage(), ex);
                    String msg = rootMessage(ex);
                    if (msg != null && (msg.contains("proxy") || msg.contains("Proxy"))) {
                        return "Proxy error: " + msg;
                    }
                    return "Error: " + msg;
                });
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
