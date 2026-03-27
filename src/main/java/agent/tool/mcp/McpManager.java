package agent.tool.mcp;

import agent.tool.Tool;
import agent.tool.ToolRegistry;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.json.JsonMapper;
import config.Config;
import config.ConfigIO;
import config.ConfigSchema;
import config.mcp.MCPServerConfig;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import utils.GsonFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * MCP 连接管理器（官方 Java SDK 版）
 *
 * 作用：
 * 1. 使用官方 Java SDK 连接所有 MCP server
 * 2. initialize 后拉取 tools/list
 * 3. 将远端工具包装成本地 Tool 并注册到 mcpTools
 * 4. 提供 snapshotRegistry() 给每次请求构建工具视图使用
 * 5. 支持定时刷新 tools/list，动态增删工具
 */
@Slf4j
public class McpManager {

    private Map<String, MCPServerConfig> mcpServers;
    private final Executor executor;

    /**
     * 专门存放 MCP 动态工具
     */
    private final ToolRegistry mcpTools = new ToolRegistry();

    /**
     * 已连接的 server 句柄
     */
    private final Map<String, ServerHandle> handles = new LinkedHashMap<>();

    private final Object connectLock = new Object();

    /**
     * 首次连接 / 手动刷新 / 定时刷新 共用
     */
    private volatile CompletableFuture<String> currentConnectFuture;

    private volatile boolean connected = false;

    /**
     * 定时刷新任务句柄
     */
    private volatile java.util.concurrent.ScheduledFuture<?> refreshFuture;

    private final ScheduledExecutorService scheduler;

    private final Path workspace;

    public McpManager(Path workspace, Map<String, MCPServerConfig> mcpServers, Executor executor) {
        this.mcpServers = (mcpServers == null) ? Map.of() : mcpServers;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workspace = workspace;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-refresh-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    private static String safeErrorMessage(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String msg = e.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        return e.getClass().getSimpleName();
    }
    /**
     * 确保 MCP 已连接。
     *
     * 注意：
     * - 不会像旧版一样在“连接中”时返回假的 completedFuture
     * - 并发调用会复用同一个 future
     *
     * 返回：
     * - 有错误：返回错误汇总字符串
     * - 无错误：返回 null
     */
    public CompletionStage<String> ensureConnected() {
        synchronized (connectLock) {
            if (connected || mcpServers.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            if (currentConnectFuture != null && !currentConnectFuture.isDone()) {
                return currentConnectFuture;
            }

            currentConnectFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder sb = new StringBuilder();

                try {
                    for (Map.Entry<String, MCPServerConfig> entry : mcpServers.entrySet()) {
                        String serverName = entry.getKey();
                        MCPServerConfig cfg = entry.getValue();

                        if (cfg == null || !cfg.isEnable()) {
                            continue;
                        }

                        try {
                            connectOneServer(serverName, cfg);
                        } catch (Exception e) {
                            sb.append("[MCP] 服务连接失败: ")
                                    .append(serverName)
                                    .append("，原因：")
                                    .append(safeErrorMessage(e))
                                    .append("\n");
                            log.error("[MCP] 服务连接失败: {}", serverName, e);
                        }
                    }

                    // 只要还有已连接句柄，就认为已连接；或者根本没有启用项，也算已完成
                    boolean hasEnabledServer = mcpServers.values().stream()
                            .filter(Objects::nonNull)
                            .anyMatch(MCPServerConfig::isEnable);

                    connected = !handles.isEmpty() || !hasEnabledServer;

                } catch (Exception e) {
                    sb.append("[MCP] MCP 连接总流程失败，原因：")
                            .append(safeErrorMessage(e))
                            .append("\n");
                    log.error("[MCP] MCP 连接总流程失败", e);
                }

                return sb.length() == 0 ? null : sb.toString();
            }, executor);

            return currentConnectFuture;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalize(String s) {
        String v = trimToNull(s);
        return v == null ? null : v.toLowerCase();
    }

    private static <T> List<T> nullSafeList(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    private static <K, V> Map<K, V> nullSafeMap(Map<K, V> map) {
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /**
     * 复制配置
     * @param cfg
     * @return
     */
    private MCPServerConfig copyCfg(MCPServerConfig cfg) {
        if (cfg == null) {
            return null;
        }
        return GsonFactory.getGson().fromJson(GsonFactory.getGson().toJson(cfg), MCPServerConfig.class);
    }

    /**
     * 是否需要重连
     * @param oldCfg
     * @param newCfg
     * @return
     */
    private boolean needsReconnect(MCPServerConfig oldCfg,
                                   MCPServerConfig newCfg) {
        if (oldCfg == null) {
            return true;
        }
        if (newCfg == null) {
            return false;
        }

        return !Objects.equals(normalize(oldCfg.getType()), normalize(newCfg.getType()))
                || !Objects.equals(trimToNull(oldCfg.getUrl()), trimToNull(newCfg.getUrl()))
                || !Objects.equals(trimToNull(oldCfg.getCommand()), trimToNull(newCfg.getCommand()))
                || !Objects.equals(nullSafeList(oldCfg.getArgs()), nullSafeList(newCfg.getArgs()))
                || !Objects.equals(nullSafeMap(oldCfg.getEnv()), nullSafeMap(newCfg.getEnv()))
                || !Objects.equals(nullSafeMap(oldCfg.getHeaders()), nullSafeMap(newCfg.getHeaders()));
    }

    /**
     * 手动刷新所有 MCP server 的工具列表。
     *
     * 设计目标：
     * 1. 已有连接则复用 client，只重新 listTools
     * 2. 未连接的 server 尝试新建连接
     * 3. 已禁用的 server 卸载其工具并关闭连接
     * 4. 某个 server 刷新失败时，保留它原有工具，避免整体抖动
     *
     * 返回：
     * - 有错误：返回错误汇总字符串
     * - 无错误：返回 null
     */
    public CompletionStage<String> refreshTools() {
        synchronized (connectLock) {
            if (currentConnectFuture != null && !currentConnectFuture.isDone()) {
                return currentConnectFuture;
            }

            currentConnectFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder sb = new StringBuilder();

                try {
                    // 1) 实时读取配置文件
                    Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));

                    Map<String, MCPServerConfig> latestServers =
                            config != null
                                    && config.getTools() != null
                                    && config.getTools().getMcpServers() != null
                                    ? config.getTools().getMcpServers()
                                    : Map.of();

                    // 2) 先处理：配置中已删除 / 已禁用 的 server
                    List<String> existingNames = new ArrayList<>(handles.keySet());
                    for (String existing : existingNames) {
                        MCPServerConfig latestCfg = latestServers.get(existing);
                        if (latestCfg == null || !latestCfg.isEnable()) {
                            try {
                                removeServer(existing);
                            } catch (Exception e) {
                                sb.append("[MCP] 卸载服务失败: ")
                                        .append(existing)
                                        .append("，原因：")
                                        .append(safeErrorMessage(e))
                                        .append("\n");
                                log.error("[MCP] 卸载服务失败: {}", existing, e);
                            }
                        }
                    }

                    // 3) 处理所有当前配置中的 server
                    for (Map.Entry<String, MCPServerConfig> entry : latestServers.entrySet()) {
                        String serverName = entry.getKey();
                        MCPServerConfig newCfg = entry.getValue();

                        if (newCfg == null || !newCfg.isEnable()) {
                            continue;
                        }

                        ServerHandle oldHandle = handles.get(serverName);

                        try {
                            if (oldHandle == null) {
                                // 新增 server：直接新建连接
                                connectOneServer(serverName, newCfg);
                                log.info("[MCP] 新增并连接 server: {}", serverName);
                            } else if (needsReconnect(oldHandle.config(), newCfg)) {
                                // 连接参数发生变化：必须重连
                                removeServer(serverName);
                                connectOneServer(serverName, newCfg);
                                log.info("[MCP] server 配置变更，已重连: {}", serverName);
                            } else {
                                // 连接参数没变：复用旧 client 刷新工具列表
                                refreshOneServer(serverName, newCfg);
                                log.info("[MCP] server 工具已刷新: {}", serverName);
                            }
                        } catch (Exception e) {
                            // 单个 server 失败，保留旧状态或部分结果，不影响整体
                            sb.append("[MCP] 服务刷新失败: ")
                                    .append(serverName)
                                    .append("，原因：")
                                    .append(safeErrorMessage(e))
                                    .append("\n");
                            log.error("[MCP] 服务刷新失败: {}", serverName, e);
                        }
                    }

                    connected = !handles.isEmpty()
                            || latestServers.values().stream().noneMatch(MCPServerConfig::isEnable);

                } catch (Exception e) {
                    sb.append("[MCP] 刷新工具总流程失败，原因：")
                            .append(safeErrorMessage(e))
                            .append("\n");
                    log.error("[MCP] 刷新工具总流程失败", e);
                }

                return sb.length() == 0 ? null : sb.toString();
            }, executor);

            return currentConnectFuture;
        }
    }

    /**
     * 启动定时刷新。
     *
     * @param period    刷新周期
     * @param unit      时间单位
     */
    public synchronized void startAutoRefresh(long period, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");

        stopAutoRefresh();

        refreshFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (ConfigIO.isConfigChanged(workspace)) {
                    log.info("[MCP]检测到配置文件变动，开始更新mcp");
                    refreshTools().toCompletableFuture().join();
                }else {
                    // 日志输出
                    //log.info("[MCP]配置未变动，跳过执行！");
                }
            } catch (Exception e) {
                log.error("[MCP] scheduled refresh failed", e);
            }
        }, period, period, unit);
    }

    /**
     * 停止定时刷新
     */
    public synchronized void stopAutoRefresh() {
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            refreshFuture = null;
        }
    }

    /**
     * 返回 MCP 工具快照。
     * 避免直接把内部 registry 暴露出去，防止请求期误操作。
     */
    public ToolRegistry snapshotRegistry() {
        ToolRegistry copy = new ToolRegistry();
        for (String name : mcpTools.toolNames()) {
            Tool t = mcpTools.get(name);
            if (t != null) {
                copy.register(t);
            }
        }
        return copy;
    }

    public boolean isConnected() {
        return connected;
    }

    public CompletionStage<Void> closeAll() {
        return CompletableFuture.runAsync(() -> {
            stopAutoRefresh();

            for (ServerHandle handle : new ArrayList<>(handles.values())) {
                unregisterTools(handle.registeredTools());

                try {
                    handle.client().closeGracefully().block(Duration.ofSeconds(5));
                } catch (Exception ignored) {
                }
            }

            handles.clear();
            connected = false;
            currentConnectFuture = null;
        }, executor);
    }

    /**
     * 首次连接单个 server
     */
    private void connectOneServer(String serverName, MCPServerConfig cfg) {
        McpAsyncClient client = createClient(cfg);

        client.initialize().block(Duration.ofSeconds(20));
        McpSchema.ListToolsResult toolsResult = client.listTools().block(Duration.ofSeconds(20));

        List<Tool> registered = buildWrappedTools(serverName, client, cfg, toolsResult);

        for (Tool tool : registered) {
            mcpTools.register(tool);
        }

        handles.put(serverName, new ServerHandle(serverName, client, registered, copyCfg(cfg)));
    }

    /**
     * 刷新单个 server
     *
     * 策略：
     * - 已有 handle：复用 client，只更新工具集
     * - 没有 handle：按首次连接处理
     */
    private void refreshOneServer(String serverName, MCPServerConfig cfg) {
        ServerHandle oldHandle = handles.get(serverName);

        if (oldHandle == null) {
            connectOneServer(serverName, cfg);
            return;
        }

        McpAsyncClient client = oldHandle.client();

        // 有些服务端会在连接长期空闲后失效，这里保险起见重新 initialize 一次
        client.initialize().block(Duration.ofSeconds(20));
        McpSchema.ListToolsResult toolsResult = client.listTools().block(Duration.ofSeconds(20));

        List<Tool> newRegistered = buildWrappedTools(serverName, client, cfg, toolsResult);

        // 先卸旧，再注新
        unregisterTools(oldHandle.registeredTools());

        for (Tool tool : newRegistered) {
            mcpTools.register(tool);
        }

        handles.put(serverName, new ServerHandle(serverName, client, newRegistered, copyCfg(cfg)));
    }

    /**
     * 卸载并关闭某个 server
     */
    private void removeServer(String serverName) {
        ServerHandle handle = handles.remove(serverName);
        if (handle == null) {
            return;
        }

        unregisterTools(handle.registeredTools());

        try {
            handle.client().closeGracefully().block(Duration.ofSeconds(5));
        } catch (Exception ignored) {
        }
    }

    /**
     * 根据 listTools 结果构建包装工具
     */
    private List<Tool> buildWrappedTools(
            String serverName,
            McpAsyncClient client,
            MCPServerConfig cfg,
            McpSchema.ListToolsResult toolsResult
    ) {
        List<Tool> registered = new ArrayList<>();

        if (toolsResult == null || toolsResult.tools() == null) {
            return registered;
        }

        for (McpSchema.Tool tool : toolsResult.tools()) {
            Map<String, Object> inputSchema = convertToolSchema(tool.inputSchema());

            Tool wrapper = new OfficialMcpToolWrapper(
                    serverName,
                    tool.name(),
                    tool.description(),
                    inputSchema,
                    client,
                    resolveToolTimeout(cfg)
            );

            registered.add(wrapper);
        }

        return registered;
    }

    /**
     * 从 registry 卸载工具
     */
    private void unregisterTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        for (Tool tool : tools) {
            try {
                mcpTools.unregister(tool.name());
            } catch (Exception ignored) {
            }
        }
    }

    private McpAsyncClient createClient(MCPServerConfig cfg) {
        McpClientTransport transport = createTransport(cfg);

        return McpClient.async(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(20))
                .build();
    }

    private McpJsonMapper createJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    private McpClientTransport createTransport(MCPServerConfig cfg) {
        String transportType = determineTransportType(cfg);

        switch (transportType) {
            case "stdio" -> {
                ServerParameters.Builder builder = ServerParameters.builder(cfg.getCommand());

                if (cfg.getArgs() != null && !cfg.getArgs().isEmpty()) {
                    builder.args(cfg.getArgs());
                }
                if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
                    builder.env(cfg.getEnv());
                }

                return new StdioClientTransport(
                        builder.build(),
                        createJsonMapper()
                );
            }
            case "sse" -> {
                HttpClientSseClientTransport.Builder builder =
                        HttpClientSseClientTransport.builder(cfg.getUrl());

                if (StrUtil.isNotBlank(cfg.getUrl())) {
                    builder.sseEndpoint(cfg.getUrl());
                }

                if (cfg.getHeaders() != null && !cfg.getHeaders().isEmpty()) {
                    builder.customizeRequest(req -> {
                        for (Map.Entry<String, String> e : cfg.getHeaders().entrySet()) {
                            req.header(e.getKey(), e.getValue());
                        }
                    });
                }

                return builder.build();
            }
            case "streamableHttp", "streamable_http", "streamable-http" -> {
                return HttpClientStreamableHttpTransport
                        .builder(cfg.getUrl())
                        .build();
            }
            default -> throw new IllegalArgumentException("未知 MCP transport: " + transportType);
        }
    }

    private static String determineTransportType(MCPServerConfig cfg) {
        if (cfg.getType() != null && !cfg.getType().isBlank()) {
            return cfg.getType();
        }

        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            return "stdio";
        }

        if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            String url = cfg.getUrl().replaceAll("/$", "");
            if (url.endsWith("/sse")) {
                return "sse";
            }
            return "streamableHttp";
        }

        throw new IllegalArgumentException("MCP server 缺少 type/command/url");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertToolSchema(Object schemaObj) {
        if (schemaObj == null) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "object");
            fallback.put("properties", new LinkedHashMap<String, Object>());
            return fallback;
        }

        // MCP SDK 1.0.0: inputSchema is McpSchema.JsonSchema record
        if (schemaObj instanceof McpSchema.JsonSchema jsonSchema) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (jsonSchema.type() != null) {
                out.put("type", jsonSchema.type());
            }
            if (jsonSchema.properties() != null && !jsonSchema.properties().isEmpty()) {
                out.put("properties", jsonSchema.properties());
            } else {
                out.put("properties", new LinkedHashMap<String, Object>());
            }
            if (jsonSchema.required() != null && !jsonSchema.required().isEmpty()) {
                out.put("required", jsonSchema.required());
            }
            if (jsonSchema.additionalProperties() != null) {
                out.put("additionalProperties", jsonSchema.additionalProperties());
            }
            if (jsonSchema.definitions() != null && !jsonSchema.definitions().isEmpty()) {
                out.put("$defs", jsonSchema.definitions());
            }
            if (jsonSchema.defs() != null && !jsonSchema.defs().isEmpty()) {
                out.put("defs", jsonSchema.defs());
            }
            return out;
        }

        if (schemaObj instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "object");
        fallback.put("properties", new LinkedHashMap<String, Object>());
        return fallback;
    }

    private static Duration resolveToolTimeout(MCPServerConfig cfg) {
        Integer seconds = cfg.getToolTimeout();
        if (seconds == null || seconds <= 0) {
            return Duration.ofSeconds(60);
        }
        return Duration.ofSeconds(seconds);
    }

    private record ServerHandle(
            String serverName,
            McpAsyncClient client,
            List<Tool> registeredTools,
            MCPServerConfig config
    ) {}
}