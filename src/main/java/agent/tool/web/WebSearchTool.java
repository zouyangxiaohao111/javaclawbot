package agent.tool.web;

import agent.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import lombok.extern.slf4j.Slf4j;

/**
 * Java port of javaclawbot/agent/tools/web.py -> WebSearchTool
 *
 * 对齐 Python:
 * - proxy 支持
 * - Brave Search API
 */
@Slf4j
public class WebSearchTool extends Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";
    private static final int DEFAULT_MAX_RESULTS = 5;

    private final HttpClient http;
    private final String initApiKey;
    private final int maxResults;
    private final String proxy;

    public WebSearchTool(String apiKey, Integer maxResults) {
        this(apiKey, maxResults, null);
    }

    public WebSearchTool(String apiKey, Integer maxResults, String proxy) {
        this.initApiKey = apiKey;
        this.maxResults = (maxResults == null || maxResults <= 0) ? DEFAULT_MAX_RESULTS : Math.min(maxResults, 10);
        this.proxy = proxy;

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER);

        if (proxy != null && !proxy.isBlank()) {
            builder.proxy(createProxySelector(proxy));
        }

        this.http = builder.build();
    }

    private static ProxySelector createProxySelector(String proxyUrl) {
        try {
            URI uri = URI.create(proxyUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) {
                // 尝试解析 host:port 格式
                String[] parts = proxyUrl.split(":");
                if (parts.length == 2) {
                    host = parts[0];
                    port = Integer.parseInt(parts[1]);
                } else {
                    throw new IllegalArgumentException("Invalid proxy URL: " + proxyUrl);
                }
            }
            InetSocketAddress addr = new InetSocketAddress(host, port);
            return ProxySelector.of(addr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse proxy URL: " + proxyUrl, e);
        }
    }

    private String resolveApiKey() {
        if (initApiKey != null && !initApiKey.isBlank()) return initApiKey;
        String env = System.getenv("BRAVE_API_KEY");
        return env == null ? "" : env.trim();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web. Returns titles, URLs, and snippets.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> countSchema = new LinkedHashMap<>();
        countSchema.put("type", "integer");
        countSchema.put("description", "Results (1-10)");
        countSchema.put("minimum", 1);
        countSchema.put("maximum", 10);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "Search query"));
        props.put("count", countSchema);

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("query")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            log.warn("执行工具: web_search 失败, Brave Search API Key 未配置");
            return CompletableFuture.completedFuture(
                    "Error: Brave Search API key not configured. " +
                    "Set it in ~/.javaclawbot/config.json under tools.web.search.apiKey " +
                    "(or export BRAVE_API_KEY), then restart the gateway."
            );
        }

        String query = String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isEmpty()) {
            log.warn("执行工具: web_search 失败, 查询参数为空");
            return CompletableFuture.completedFuture("Error: query is required");
        }

        Integer count = null;
        Object c = args.get("count");
        if (c instanceof Number n) count = n.intValue();
        int n = Math.min(Math.max(count != null ? count : this.maxResults, 1), 10);

        log.info("执行工具: web_search, 参数: query={}, count={}", query, n);

        String url = API_ENDPOINT
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + n;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();

        log.debug("向Brave Search API发起请求: url={}", url);

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        log.warn("Brave Search API返回错误状态码: {}, 响应: {}", resp.statusCode(), safeTrim(resp.body(), 2000));
                        return "Error: Brave Search API HTTP " + resp.statusCode() + "\n" + safeTrim(resp.body(), 2000);
                    }
                    try {
                        JsonNode root = MAPPER.readTree(resp.body());
                        JsonNode results = root.path("web").path("results");
                        if (!results.isArray() || results.size() == 0) {
                            log.debug("搜索无结果: query={}", query);
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
                        log.debug("工具执行成功: web_search, 结果数量={}", Math.min(results.size(), n));
                        return String.join("\n", lines);
                    } catch (Exception e) {
                        log.error("工具执行失败: web_search, 解析响应失败, 错误: {}", e.getMessage(), e);
                        return "Error: " + e.getMessage();
                    }
                })
                .exceptionally(ex -> {
                    log.error("工具执行异常: web_search, 错误: {}", ex.getMessage(), ex);
                    String msg = rootMessage(ex);
                    if (msg != null && (msg.contains("proxy") || msg.contains("Proxy"))) {
                        return "Proxy error: " + msg;
                    }
                    return "Error: " + msg;
                });
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