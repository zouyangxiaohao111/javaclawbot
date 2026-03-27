package config.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExecToolConfig {
    private int timeout = 60;
    private String pathAppend = "";

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getPathAppend() {
        return pathAppend;
    }

    public void setPathAppend(String pathAppend) {
        this.pathAppend = pathAppend;
    }
}