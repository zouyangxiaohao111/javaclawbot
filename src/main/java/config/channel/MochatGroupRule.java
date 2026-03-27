package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MochatGroupRule {
    private boolean requireMention = false;

    public boolean isRequireMention() {
        return requireMention;
    }

    public void setRequireMention(boolean requireMention) {
        this.requireMention = requireMention;
    }
}