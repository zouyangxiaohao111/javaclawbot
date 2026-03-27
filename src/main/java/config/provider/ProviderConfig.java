package config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.provider.model.ModelConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@Data
public class ProviderConfig {
    private String apiKey = "";
    private String apiBase = null;
    private List<ModelConfig> modelConfigs = new ArrayList<>();
    private Map<String, String> extraHeaders = null;

    public ProviderConfig(String apiBase) {
        this.apiBase = apiBase;
    }

}