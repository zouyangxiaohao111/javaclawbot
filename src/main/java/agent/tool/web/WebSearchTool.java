package agent.tool.web;

import agent.tool.Tool;
import config.tool.AnySearchConfig;
import config.tool.WebSearchConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Web 搜索工具（Facade）。
 *
 * 根据配置中的 engine 字段选择搜索引擎：
 * - "anysearch" (默认) → AnySearchEngine，支持 domains/content_types/zone/language/freshness 等过滤
 * - "brave" → BraveSearchEngine（保留原有逻辑）
 */
@Slf4j
public class WebSearchTool extends Tool {

    private static final int DEFAULT_MAX_RESULTS = 5;

    private final SearchEngine engine;
    private final int maxResults;
    private final String engineType;

    /**
     * @param searchConfig   通用搜索配置（含 engine 选择、proxy 等）
     * @param anysearchConfig AnySearch 专属配置
     */
    public WebSearchTool(WebSearchConfig searchConfig, AnySearchConfig anysearchConfig) {
        this.maxResults = (searchConfig != null && searchConfig.getMaxResults() > 0)
                ? searchConfig.getMaxResults() : DEFAULT_MAX_RESULTS;

        String engineName = (searchConfig != null && searchConfig.getEngine() != null)
                ? searchConfig.getEngine().trim().toLowerCase() : "anysearch";

        String proxy = (searchConfig != null) ? searchConfig.getProxy() : null;

        if ("brave".equals(engineName)) {
            this.engineType = "brave";
            this.engine = new BraveSearchEngine(searchConfig, proxy);
            log.info("WebSearch 引擎: Brave Search");
        } else {
            this.engineType = "anysearch";
            this.engine = new AnySearchEngine(anysearchConfig, proxy);
            log.info("WebSearch 引擎: AnySearch");
        }
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        if ("anysearch".equals(engineType)) {
            return "Search the web via AnySearch. Returns titles, URLs, and snippets. " +
                   "Supports optional filters: domains, contentTypes, zone (cn/intl), " +
                   "language (zh-CN/en), freshness (day/week/month/year).";
        }
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

        // AnySearch 额外参数（Brave 引擎会忽略这些）
        if ("anysearch".equals(engineType)) {
            props.put("domains", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description",
                    "Domain filter: general, code, tech, academic, finance, news, etc."
            ));
            props.put("contentTypes", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description",
                    "Content type filter: web, news, code, doc, academic, data, image, video, audio"
            ));
            props.put("zone", Map.of(
                    "type", "string",
                    "enum", List.of("cn", "intl"),
                    "description", "Region: cn (China) or intl (International)"
            ));
            props.put("language", Map.of(
                    "type", "string",
                    "description", "Language preference: zh-CN, en, etc."
            ));
            props.put("freshness", Map.of(
                    "type", "string",
                    "enum", List.of("day", "week", "month", "year"),
                    "description", "Recency filter: day, week, month, year"
            ));
        }

        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("query")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String query = String.valueOf(args.getOrDefault("query", "")).trim();
        if (query.isEmpty()) {
            log.warn("web_search 失败: 查询参数为空");
            return CompletableFuture.completedFuture("Error: query is required");
        }

        int count = this.maxResults;
        Object c = args.get("count");
        if (c instanceof Number n) {
            count = Math.min(Math.max(n.intValue(), 1), 10);
        }

        log.debug("web_search engine={}, query={}, count={}", engineType, query, count);

        return engine.search(query, count, args);
    }
}
