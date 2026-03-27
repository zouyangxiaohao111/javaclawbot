package config.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebToolsConfig {
    private WebSearchConfig search = new WebSearchConfig();

    public WebSearchConfig getSearch() {
        return search;
    }

    public void setSearch(WebSearchConfig search) {
        this.search = (search != null) ? search : new WebSearchConfig();
    }
}