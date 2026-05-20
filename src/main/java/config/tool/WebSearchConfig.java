package config.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class WebSearchConfig {
    private String apiKey = "";
    private int maxResults = 5;
    private String proxy = "";

    /**
     * 搜索引擎选择："anysearch" (默认) | "brave"
     */
    private String engine = "anysearch";

}