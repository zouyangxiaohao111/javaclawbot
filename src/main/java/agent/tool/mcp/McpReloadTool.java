package agent.tool.mcp;

import agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * MCP 重载工具：按名称刷新单个 MCP server 的工具列表。
 *
 * 与 /mcp-reload 不同，该工具只重连指定的 MCP 服务，不影响其他服务。
 */
@Slf4j
public class McpReloadTool extends Tool {

    private final McpManager mcpManager;

    public McpReloadTool(McpManager mcpManager) {
        this.mcpManager = Objects.requireNonNull(mcpManager, "mcpManager");
    }

    @Override
    public String name() {
        return "mcp_reload";
    }

    @Override
    public String description() {
        return "Reload a specific MCP server by name to refresh its tools. Use when an MCP server's tools have changed and you need to update them without reloading all servers. Requires the server_name parameter.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("server_name", Map.of(
                "type", "string",
                "description", "Name of the MCP server to reload. Must match the server name defined in the MCP configuration."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", java.util.List.of("server_name"));
        return schema;
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String serverName = str(args.get("server_name"));

        if (serverName == null || serverName.isBlank()) {
            return CompletableFuture.completedFuture("Error: server_name is required and cannot be empty");
        }

        log.info("执行工具: mcp_reload, server: {}", serverName);

        return mcpManager.reconnectServer(serverName).thenApply(error -> {
            if (error == null) {
                return "MCP server '" + serverName + "' reloaded successfully";
            }
            return error;
        });
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
