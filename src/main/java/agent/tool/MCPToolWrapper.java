package agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * MCP Tool 包装器
 *
 * 对齐 Python: MCPToolWrapper
 *
 * 将 MCP 服务器的工具包装为 javaclawbot Tool
 */
public class MCPToolWrapper extends Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final String originalName;
    private final String toolName;
    private final String description;
    private final Map<String, Object> parameters;
    private final int toolTimeout;

    // MCP 客户端引用（由 MCPClient 管理）
    private volatile Object mcpClient;
    private volatile String sessionId;

    public MCPToolWrapper(String serverName, String toolName, String description, Map<String, Object> parameters, int toolTimeout) {
        this.serverName = serverName;
        this.originalName = toolName;
        this.toolName = "mcp_" + serverName + "_" + toolName;
        this.description = description != null ? description : toolName;
        this.parameters = parameters != null ? parameters : Map.of("type", "object", "properties", Map.of());
        this.toolTimeout = toolTimeout > 0 ? toolTimeout : 30;
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    public String getServerName() {
        return serverName;
    }

    public String getOriginalName() {
        return originalName;
    }

    /**
     * 设置 MCP 客户端引用
     */
    public void setMcpClient(Object client, String sessionId) {
        this.mcpClient = client;
        this.sessionId = sessionId;
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // TODO: 实际调用 MCP 客户端
                // 这里需要等待 MCP SDK 集成完成后实现
                if (mcpClient == null) {
                    return "(MCP tool '" + toolName + "' not connected to server)";
                }

                // 模拟调用（实际实现需要 MCP SDK）
                return callMcpTool(args);
            } catch (Exception e) {
                return "(MCP tool call failed: " + e.getMessage() + ")";
            }
        }).orTimeout(toolTimeout, TimeUnit.SECONDS)
          .exceptionally(ex -> {
              if (ex instanceof java.util.concurrent.TimeoutException) {
                  return "(MCP tool call timed out after " + toolTimeout + "s)";
              }
              return "(MCP tool call failed: " + rootMessage(ex) + ")";
          });
    }

    /**
     * 调用 MCP 工具
     *
     * 实际实现需要使用 MCP Java SDK
     */
    private String callMcpTool(Map<String, Object> args) {
        // 占位实现
        // 实际实现：
        // 1. 使用 MCP SDK 的 session.call_tool(originalName, args)
        // 2. 解析返回的 content blocks
        // 3. 拼接文本内容返回

        return "(MCP tool '" + originalName + "' called with args: " + args.keySet() + " - implementation pending)";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() != null ? cur.getMessage() : cur.toString();
    }
}