package config.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * AnySearch 搜索引擎配置。
 * 对应 config.json 中 tools.web.anysearch 节点。
 *
 * 仅包含认证相关配置。搜索过滤参数（domains/content_types/zone/language/freshness 等）
 * 由 AI 在每次请求中通过工具参数动态指定。
 *
 * API 文档：https://www.anysearch.com/docs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class AnySearchConfig {

    /** 内置默认 API Key（用户未配置时回退使用） */
    private static final String BUILTIN_DEFAULT_API_KEY = "as_sk_a95d63d2e77de587a95b88dd9e0de48b";

    /** API Key。回退链：配置值 → 环境变量 ANYSEARCH_API_KEY → 内置默认 */
    private String apiKey = "";

    /**
     * 解析最终使用的 API Key。
     * 优先级：配置值 > 环境变量 > 内置默认
     */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String envKey = System.getenv("ANYSEARCH_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return BUILTIN_DEFAULT_API_KEY;
    }
}
