package config.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentsConfig {
    private AgentDefaults defaults = new AgentDefaults();

    public AgentDefaults getDefaults() {
        return defaults;
    }

    public void setDefaults(AgentDefaults defaults) {
        this.defaults = (defaults != null) ? defaults : new AgentDefaults();
    }
}