package agent.tool.mcp;

import agent.tool.Tool;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 将 MCP 远端工具包装成当前系统可直接注册到 ToolRegistry 的本地 Tool。
 *
 * 对齐现有系统：
 * - name()/description()/parameters()/execute(Map<String,Object>)
 * - toSchema() 仍走现有 Tool 基类逻辑
 */
@Slf4j
public class OfficialMcpToolWrapper extends Tool {

    private final String serverName;
    private final String rawToolName;
    private final String exposedName;
    private final String description;
    private final Map<String, Object> parameters;
    @Setter
    @Getter
    private McpAsyncClient client;
    private final Duration timeout;

    public OfficialMcpToolWrapper(
            String serverName,
            String rawToolName,
            String description,
            Map<String, Object> inputSchema,
            McpAsyncClient client,
            Duration timeout
    ) {
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.rawToolName = Objects.requireNonNull(rawToolName, "rawToolName");
        this.exposedName = McpToolNames.toExposedName(serverName, rawToolName);
        this.description = (description == null || description.isBlank())
                ? ("MCP tool from server '" + serverName + "': " + rawToolName)
                : description;
        this.parameters = normalizeParameters(inputSchema);
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
        log.info("注册 MCP 工具: {} (服务器: {}, 原始名称: {})", exposedName, serverName, rawToolName);
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        return description;
    }

    /**
     * 这里返回的必须是"参数 schema 本体"，与现有 Tool 基类保持一致。
     * 即：通常是 {"type":"object","properties":...,"required":[...]}
     */
    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    @Override
    public CompletableFuture<String> execute(Map<String, Object> args) {
        Map<String, Object> safeArgs = (args == null) ? Map.of() : args;

        log.debug("MCP tool: {}, args: {}", exposedName, safeArgs);

        return CompletableFuture.supplyAsync(() -> {
            try {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(rawToolName, safeArgs);
                McpSchema.CallToolResult result = client.callTool(request).block(timeout);

                if (result == null) {
                    log.debug("MCP 工具执行成功: {}, 无输出", exposedName);
                    return "(no output)";
                }
                log.debug("MCP 工具执行成功: {}", exposedName);
                return normalizeToolResult(result);
            } catch (Exception e) {
                log.error("MCP 工具执行失败: {}, 错误: {}", exposedName, e.getMessage(), e);
                throw new RuntimeException(
                        "调用 MCP 工具失败: " + exposedName + " -> " + e.getMessage(), e
                );
            }
        });
    }

    private static Map<String, Object> normalizeParameters(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "object");
            fallback.put("properties", new LinkedHashMap<String, Object>());
            return fallback;
        }

        Object type = inputSchema.get("type");
        if (type == null) {
            Map<String, Object> normalized = new LinkedHashMap<>(inputSchema);
            normalized.put("type", "object");
            return normalized;
        }
        return new LinkedHashMap<>(inputSchema);
    }

    /**
     * CallToolResult.content() -> List<McpSchema.Content>
     * Content 的实现类包括：
     * - TextContent
     * - AudioContent
     * - ImageContent
     * - ResourceLink
     * - EmbeddedResource
     */
    private static String normalizeToolResult(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.content() != null) {
            for (McpSchema.Content block : result.content()) {
                if (block instanceof McpSchema.TextContent text) {
                    if (text.text() != null) {
                        sb.append(text.text());
                    }
                } else if (block instanceof McpSchema.ImageContent image) {
                    sb.append("[image");
                    if (image.mimeType() != null) {
                        sb.append(": ").append(image.mimeType());
                    }
                    sb.append("]");
                } else if (block instanceof McpSchema.AudioContent audio) {
                    sb.append("[audio");
                    if (audio.mimeType() != null) {
                        sb.append(": ").append(audio.mimeType());
                    }
                    sb.append("]");
                } else if (block instanceof McpSchema.ResourceLink link) {
                    sb.append("[resource");
                    if (link.name() != null && !link.name().isBlank()) {
                        sb.append(": ").append(link.name());
                    } else if (link.uri() != null && !link.uri().isBlank()) {
                        sb.append(": ").append(link.uri());
                    }
                    sb.append("]");
                } else if (block instanceof McpSchema.EmbeddedResource embedded) {
                    sb.append("[embedded_resource");
                    if (embedded.resource() != null) {
                        sb.append(": ").append(String.valueOf(embedded.resource()));
                    }
                    sb.append("]");
                } else {
                    sb.append(String.valueOf(block));
                }
            }
        }

        if (sb.length() == 0) {
            return "(no output)";
        }
        return sb.toString();
    }

    public String serverName() {
        return serverName;
    }

    public String rawToolName() {
        return rawToolName;
    }
}
