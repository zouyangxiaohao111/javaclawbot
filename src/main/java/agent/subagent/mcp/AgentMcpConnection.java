package agent.subagent.mcp;

import agent.subagent.definition.AgentDefinition;
import agent.tool.Tool;
import agent.tool.mcp.McpManager;
import config.mcp.MCPServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 子代理专属 MCP 连接管理器
 *
 * 混合架构：
 * - 使用全局 McpManager 管理共享连接
 * - 子代理可在 AgentDefinition 中定义额外的 MCP 服务器
 * - 这些额外连接是隔离的，在子代理结束时清理
 *
 * 对应 Open-ClaudeCode: initializeAgentMcpServers()
 */
public class AgentMcpConnection {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpConnection.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 子代理 ID */
    private final String agentId;

    /** 额外创建的 MCP 客户端（仅这个子代理使用，需要清理） */
    private final List<McpManager.DynamicMcpConnection> dynamicConnections = new CopyOnWriteArrayList<>();

    /** 额外 MCP 连接提供的工具 */
    private final List<Tool> additionalTools = new CopyOnWriteArrayList<>();

    /** 父级 McpManager（用于获取已连接的客户端） */
    private final McpManager parentMcpManager;

    /** 父级 McpManager 的工具（用于去重） */
    private final List<Tool> parentTools;

    private AgentMcpConnection(String agentId, McpManager parentMcpManager, List<Tool> parentTools) {
        this.agentId = agentId;
        this.parentMcpManager = parentMcpManager;
        this.parentTools = parentTools;
    }

    /**
     * 创建子代理专属 MCP 连接
     *
     * @param agentId      子代理 ID
     * @param agent        子代理定义（可能包含 mcpServers）
     * @param workspace    工作目录
     * @return AgentMcpConnection 或 null（如果没有额外的 MCP 配置）
     */
    public static AgentMcpConnection create(
            String agentId,
            AgentDefinition agent,
            String workspace
    ) {
        List<String> mcpServers = agent != null ? agent.getMcpServers() : null;

        // 如果没有定义额外的 MCP 服务器，返回 null
        if (mcpServers == null || mcpServers.isEmpty()) {
            log.debug("Agent {} has no additional MCP servers defined", agentId);
            return null;
        }

        // 获取全局 McpManager
        McpManager parentMcpManager = McpManager.getInstance();
        if (parentMcpManager == null) {
            log.warn("Global McpManager not initialized, cannot create agent MCP connection");
            return null;
        }

        log.info("Creating isolated MCP connections for agent {} with {} server(s)",
                agentId, mcpServers.size());

        AgentMcpConnection connection = new AgentMcpConnection(agentId, parentMcpManager, parentMcpManager.getTools());

        for (String serverSpec : mcpServers) {
            try {
                connection.processServerSpec(serverSpec, workspace);
            } catch (Exception e) {
                log.error("Failed to process MCP server '{}' for agent {}: {}",
                        serverSpec, agentId, e.getMessage());
            }
        }

        // 如果没有成功创建任何连接，返回 null
        if (connection.dynamicConnections.isEmpty() && connection.additionalTools.isEmpty()) {
            log.debug("No dynamic MCP connections created for agent {}", agentId);
            return null;
        }

        return connection;
    }

    /**
     * 处理单个 MCP 服务器规格
     *
     * 支持两种格式：
     * 1. 字符串格式：按名称引用已注册的 MCP 配置
     * 2. 内联对象格式：{ "serverName": { ...config } }
     *
     * 对应 Open-ClaudeCode:
     * - 字符串: getMcpConfigByName(spec) → connectToServer(name, config)
     * - 内联: 解析 config，设置 scope: 'dynamic' → connectToServer(name, config)
     */
    private void processServerSpec(String serverSpec, String workspace) {
        if (serverSpec == null || serverSpec.isBlank()) {
            return;
        }

        serverSpec = serverSpec.trim();

        // 检查是否是内联配置（JSON 对象格式）
        if (serverSpec.startsWith("{")) {
            processInlineConfig(serverSpec);
        } else {
            // 按名称引用
            processNamedServer(serverSpec);
        }
    }

    /**
     * 处理内联配置
     *
     * 格式: { "serverName": { ...MCPServerConfig } }
     */
    private void processInlineConfig(String inlineConfig) {
        try {
            // 解析内联配置
            JsonNode configNode = objectMapper.readTree(inlineConfig);

            if (!configNode.isObject() || configNode.size() != 1) {
                log.warn("Invalid inline MCP config: expected exactly one key, got {} keys",
                        configNode.size());
                return;
            }

            String serverName = configNode.fieldNames().next();
            JsonNode serverConfigNode = configNode.get(serverName);

            // 将 JsonNode 转换为 MCPServerConfig
            MCPServerConfig config = objectMapper.treeToValue(serverConfigNode, MCPServerConfig.class);

            log.info("Processing inline MCP config for server '{}' (dynamic scope)", serverName);

            // 检查是否已在父级连接
            if (parentMcpManager.isServerConnected(serverName)) {
                log.debug("Server '{}' already connected in parent, sharing connection", serverName);
                // 共享父级连接，不创建新连接
                // 但仍需获取工具列表
                shareParentTools(serverName);
                return;
            }

            // 创建新的动态连接
            createDynamicConnection(serverName, config);

        } catch (Exception e) {
            log.error("Failed to process inline MCP config: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理按名称引用的服务器
     */
    private void processNamedServer(String serverName) {
        serverName = serverName.trim();

        // 检查是否已在父级连接
        if (parentMcpManager.isServerConnected(serverName)) {
            log.debug("Server '{}' already connected in parent, sharing connection", serverName);
            shareParentTools(serverName);
            return;
        }

        // 在全局配置中查找
        MCPServerConfig config = findConfigByName(serverName);
        if (config == null) {
            log.warn("MCP server '{}' not found in global config", serverName);
            return;
        }

        // 创建新的动态连接
        createDynamicConnection(serverName, config);
    }

    /**
     * 创建动态 MCP 连接
     *
     * 这些连接是子代理专属的，需要在 cleanup 时关闭
     */
    private void createDynamicConnection(String serverName, MCPServerConfig config) {
        try {
            // 设置 scope 为 dynamic
            // 注意：Java 版本不需要像 TypeScript 那样显式设置 scope

            // 创建动态连接
            McpManager.DynamicMcpConnection dynamicConn =
                    parentMcpManager.connectDynamicServer(serverName, config);

            // 添加到动态连接列表（需要清理）
            dynamicConnections.add(dynamicConn);

            // 添加额外工具（去重）
            for (Tool tool : dynamicConn.getTools()) {
                if (!hasToolInParent(tool.name())) {
                    additionalTools.add(tool);
                }
            }

            log.info("Created dynamic MCP connection for server '{}' with {} tools (total additional: {})",
                    serverName, dynamicConn.getTools().size(), additionalTools.size());

        } catch (Exception e) {
            log.error("Failed to create dynamic MCP connection for server '{}': {}",
                    serverName, e.getMessage());
        }
    }

    /**
     * 共享父级已连接服务器的工具
     */
    private void shareParentTools(String serverName) {
        // 工具已经在父级的 mcpTools 中，不需要额外处理
        // 只是记录一下共享关系
        log.debug("Sharing MCP server '{}' from parent", serverName);
    }

    /**
     * 检查父级工具列表中是否已有同名工具
     */
    private boolean hasToolInParent(String toolName) {
        if (parentTools == null) {
            return false;
        }
        return parentTools.stream().anyMatch(t -> t.name().equals(toolName));
    }

    /**
     * 在全局配置中查找 MCP 服务器配置
     */
    private MCPServerConfig findConfigByName(String serverName) {
        // 使用 McpManager.getConfigByName() 查找配置
        MCPServerConfig config = parentMcpManager.getConfigByName(serverName);
        if (config == null) {
            log.warn("MCP server '{}' not found in global configuration", serverName);
            return null;
        }

        if (!config.isEnable()) {
            log.warn("MCP server '{}' is disabled in configuration", serverName);
            return null;
        }

        return config;
    }

    /**
     * 获取所有额外的工具
     *
     * @return 额外的工具列表（去重后）
     */
    public List<Tool> getAdditionalTools() {
        return new ArrayList<>(additionalTools);
    }

    /**
     * 合并工具列表
     *
     * 将父级的工具和子代理额外的工具合并
     *
     * @param parentTools 父级的工具列表
     * @return 合并后的工具列表
     */
    public List<Tool> mergeWithParent(List<Tool> parentTools) {
        List<Tool> result = new ArrayList<>();

        // 先添加父级工具
        if (parentTools != null) {
            result.addAll(parentTools);
        }

        // 添加额外工具（去重）
        for (Tool extraTool : additionalTools) {
            boolean found = result.stream()
                    .anyMatch(t -> t.name().equals(extraTool.name()));
            if (!found) {
                result.add(extraTool);
            }
        }

        return result;
    }

    /**
     * 获取动态连接数量
     */
    public int getDynamicConnectionCount() {
        return dynamicConnections.size();
    }

    /**
     * 关闭所有动态创建的连接
     *
     * 对应 Open-ClaudeCode: cleanup() - 仅清理 newlyCreatedClients
     *
     * 注意：共享的父级连接不会被关闭
     */
    public void close() {
        log.info("Closing {} dynamic MCP connections for agent {}",
                dynamicConnections.size(), agentId);

        for (McpManager.DynamicMcpConnection conn : dynamicConnections) {
            try {
                conn.close();
                log.debug("Closed dynamic MCP connection for server: {}", conn.getServerName());
            } catch (Exception e) {
                log.warn("Error closing dynamic MCP connection for server '{}': {}",
                        conn.getServerName(), e.getMessage());
            }
        }

        dynamicConnections.clear();
        additionalTools.clear();
    }

    /**
     * 检查是否有额外的工具
     */
    public boolean hasAdditionalTools() {
        return !additionalTools.isEmpty();
    }
}
