package config.tool;

import cn.hutool.json.JSON;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import config.mcp.MCPServerConfig;
import lombok.Data;
import utils.GsonFactory;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class ToolsConfig {
    private WebToolsConfig web = new WebToolsConfig();
    private ExecToolConfig exec = new ExecToolConfig();
    private boolean restrictToWorkspace = false;
    @JsonProperty("mcpServers")
    private Map<String, MCPServerConfig> mcpServers = new HashMap<>();

    public WebToolsConfig getWeb() {
        return web;
    }

    public void setWeb(WebToolsConfig web) {
        this.web = (web != null) ? web : new WebToolsConfig();
    }

    public ExecToolConfig getExec() {
        return exec;
    }

    public void setExec(ExecToolConfig exec) {
        this.exec = (exec != null) ? exec : new ExecToolConfig();
    }

    public boolean isRestrictToWorkspace() {
        return restrictToWorkspace;
    }


    public Map<String, MCPServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, MCPServerConfig> mcpServers) {
        this.mcpServers = (mcpServers != null) ? mcpServers : new HashMap<>();
    }
}