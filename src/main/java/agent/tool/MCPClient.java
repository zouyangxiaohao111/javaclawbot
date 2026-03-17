package agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import config.ConfigSchema;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * MCP 客户端
 *
 * 对齐 Python: connect_mcp_servers
 *
 * 支持三种传输方式：
 * 1. stdio - 通过命令行启动 MCP 服务器
 * 2. sse - Server-Sent Events
 * 3. streamableHttp - HTTP 流式传输
 */
@Slf4j
public class MCPClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final ConfigSchema.MCPServerConfig config;
    private final ToolRegistry registry;

    private Process stdioProcess;
    private BufferedWriter stdioWriter;
    private BufferedReader stdioReader;
    private HttpClient httpClient;
    private String sessionId;

    private volatile boolean connected = false;
    private final List<MCPToolWrapper> registeredTools = new ArrayList<>();

    public MCPClient(String serverName, ConfigSchema.MCPServerConfig config, ToolRegistry registry) {
        this.serverName = serverName;
        this.config = config;
        this.registry = registry;
    }

    /**
     * 连接到 MCP 服务器
     */
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                String transportType = determineTransportType();

                switch (transportType) {
                    case "stdio":
                        connectStdio();
                        break;
                    case "sse":
                        connectSse();
                        break;
                    case "streamableHttp":
                        connectStreamableHttp();
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown transport type: " + transportType);
                }

                // 初始化会话
                initializeSession();

                // 列出并注册工具
                listAndRegisterTools();

                connected = true;
                log.info("MCP server '{}': connected, {} tools registered", serverName, registeredTools.size());
            } catch (Exception e) {
                log.error("MCP server '{}': failed to connect: {}", serverName, e.getMessage());
                throw new RuntimeException("Failed to connect MCP server: " + serverName, e);
            }
        });
    }

    private String determineTransportType() {
        if (config.getType() != null && !config.getType().isBlank()) {
            return config.getType();
        }

        if (config.getCommand() != null && !config.getCommand().isBlank()) {
            return "stdio";
        }

        if (config.getUrl() != null && !config.getUrl().isBlank()) {
            // Convention: URLs ending with /sse use SSE transport
            if (config.getUrl().replaceAll("/$", "").endsWith("/sse")) {
                return "sse";
            }
            return "streamableHttp";
        }

        throw new IllegalArgumentException("MCP server '" + serverName + "': no command or url configured");
    }

    private void connectStdio() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.getCommand());
        if (config.getArgs() != null) {
            cmd.addAll(config.getArgs());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (config.getEnv() != null) {
            pb.environment().putAll(config.getEnv());
        }
        pb.redirectErrorStream(false);

        stdioProcess = pb.start();
        stdioWriter = new BufferedWriter(new OutputStreamWriter(stdioProcess.getOutputStream(), StandardCharsets.UTF_8));
        stdioReader = new BufferedReader(new InputStreamReader(stdioProcess.getInputStream(), StandardCharsets.UTF_8));

        log.debug("MCP server '{}': started stdio process: {}", serverName, config.getCommand());
    }

    private void connectSse() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        log.debug("MCP server '{}': connected via SSE to {}", serverName, config.getUrl());
    }

    private void connectStreamableHttp() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        log.debug("MCP server '{}': connected via HTTP to {}", serverName, config.getUrl());
    }

    private void initializeSession() throws Exception {
        // 发送 initialize 请求
        Map<String, Object> initRequest = new LinkedHashMap<>();
        initRequest.put("jsonrpc", "2.0");
        initRequest.put("id", UUID.randomUUID().toString());
        initRequest.put("method", "initialize");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of(
                "name", "javaclawbot-java",
                "version", "1.0.0"
        ));
        initRequest.put("params", params);

        String response = sendRequest(initRequest);
        log.debug("MCP server '{}': initialize response: {}", serverName, response);

        // 发送 initialized 通知
        Map<String, Object> initializedNotification = new LinkedHashMap<>();
        initializedNotification.put("jsonrpc", "2.0");
        initializedNotification.put("method", "notifications/initialized");
        sendRequest(initializedNotification);
    }

    private void listAndRegisterTools() throws Exception {
        Map<String, Object> listRequest = new LinkedHashMap<>();
        listRequest.put("jsonrpc", "2.0");
        listRequest.put("id", UUID.randomUUID().toString());
        listRequest.put("method", "tools/list");

        String response = sendRequest(listRequest);
        JsonNode root = MAPPER.readTree(response);
        JsonNode tools = root.path("result").path("tools");

        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                String toolName = tool.path("name").asText();
                String toolDesc = tool.path("description").asText("");
                Map<String, Object> inputSchema = MAPPER.convertValue(tool.path("inputSchema"), Map.class);

                MCPToolWrapper wrapper = new MCPToolWrapper(
                        serverName,
                        toolName,
                        toolDesc,
                        inputSchema,
                        config.getToolTimeout()
                );
                wrapper.setMcpClient(this, sessionId);

                registry.register(wrapper);
                registeredTools.add(wrapper);

                log.debug("MCP: registered tool '{}' from server '{}'", wrapper.name(), serverName);
            }
        }
    }

    /**
     * 调用 MCP 工具
     */
    public String callTool(String toolName, Map<String, Object> args) throws Exception {
        Map<String, Object> callRequest = new LinkedHashMap<>();
        callRequest.put("jsonrpc", "2.0");
        callRequest.put("id", UUID.randomUUID().toString());
        callRequest.put("method", "tools/call");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args != null ? args : Map.of());
        callRequest.put("params", params);

        String response = sendRequest(callRequest);
        return parseToolResult(response);
    }

    private String parseToolResult(String response) throws Exception {
        JsonNode root = MAPPER.readTree(response);
        JsonNode content = root.path("result").path("content");

        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    sb.append(block.path("text").asText());
                } else {
                    sb.append(block.toString());
                }
            }
            return sb.length() > 0 ? sb.toString() : "(no output)";
        }

        return "(no output)";
    }

    private String sendRequest(Map<String, Object> request) throws Exception {
        String json = MAPPER.writeValueAsString(request);

        if (stdioWriter != null) {
            return sendStdioRequest(json);
        } else if (httpClient != null) {
            return sendHttpRequest(json);
        }

        throw new IllegalStateException("No transport available");
    }

    private String sendStdioRequest(String json) throws Exception {
        stdioWriter.write(json);
        stdioWriter.newLine();
        stdioWriter.flush();

        // 读取响应（简化实现，实际需要处理 Content-Length 头）
        String line = stdioReader.readLine();
        if (line == null) {
            throw new IOException("No response from MCP server");
        }
        return line;
    }

    private String sendHttpRequest(String json) throws Exception {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(config.getUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

        if (config.getHeaders() != null) {
            for (Map.Entry<String, String> h : config.getHeaders().entrySet()) {
                reqBuilder.header(h.getKey(), h.getValue());
            }
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP error: " + response.statusCode());
        }

        return response.body();
    }

    @Override
    public void close() {
        connected = false;

        // 注销工具
        for (MCPToolWrapper tool : registeredTools) {
            registry.unregister(tool.name());
        }
        registeredTools.clear();

        // 关闭 stdio 进程
        if (stdioProcess != null) {
            stdioProcess.destroy();
            stdioProcess = null;
        }

        log.info("MCP server '{}': disconnected", serverName);
    }

    public boolean isConnected() {
        return connected;
    }

}