package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MochatMentionConfig {
    private boolean requireInGroups = false;

    public boolean isRequireInGroups() {
        return requireInGroups;
    }

    public void setRequireInGroups(boolean requireInGroups) {
        this.requireInGroups = requireInGroups;
    }
}