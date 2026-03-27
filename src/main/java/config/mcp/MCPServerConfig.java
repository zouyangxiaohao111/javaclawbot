package config.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class MCPServerConfig {
    private String type = "";
    /**
     * 是否开启
     */
    private boolean enable = true;
    private String command = "";
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();
    private String url = "";
    private Map<String, String> headers = new HashMap<>();
    private int toolTimeout = 30;


    public void setType(String type) {
        this.type = (type != null) ? type : "";
    }


    public void setArgs(List<String> args) {
        this.args = (args != null) ? args : new ArrayList<>();
    }

    public void setEnv(Map<String, String> env) {
        this.env = (env != null) ? env : new HashMap<>();
    }


    public void setHeaders(Map<String, String> headers) {
        this.headers = (headers != null) ? headers : new HashMap<>();
    }

}