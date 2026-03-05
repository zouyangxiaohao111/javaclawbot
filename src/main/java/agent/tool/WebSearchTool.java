package agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/**
 * Java port of nanobot/agent/tools/web.py -> WebSearchTool
 *
 * Brave Search API:
 * - Endpoint: https://api.search.brave.com/res/v1/web/search?q=...&count=...
 * - Auth header: X-Subscription-Token: <API_KEY>
 */
public class WebSearchTool extends Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";
    private static final int DEFAULT_MAX_RESULTS = 5;

    private final HttpClient http;
    private final String initApiKey;
    private final int maxResults;

    public WebSearchTool(String apiKey, Integer maxResults) {
        this.initApiKey = apiKey;
        this.maxResults = (maxResults == null || maxResults <= 0) ? DEFAULT_MAX_RESULTS : Math.min(maxResults, 10);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
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
            return CompletableFuture.completedFuture(
                    "Error: Brave Search API key not configured. " +
                    "Set it in ~/.nanobot/config.json under tools.web.search.apiKey " +
                    "(or export BRAVE_API_KEY), then restart the gateway."
            );
        }

        String query = String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isEmpty()) {
            return CompletableFuture.completedFuture("Error: query is required");
        }

        Integer count = null;
        Object c = args.get("count");
        if (c instanceof Number n) count = n.intValue();
        int n = Math.min(Math.max(count != null ? count : this.maxResults, 1), 10);

        String url = API_ENDPOINT
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + n;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        return "Error: Brave Search API HTTP " + resp.statusCode() + "\n" + safeTrim(resp.body(), 2000);
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
                        return String.join("\n", lines);
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .exceptionally(ex -> "Error: " + rootMessage(ex));
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