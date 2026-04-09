package gui;

import agent.AgentLoop;
import agent.tool.mcp.McpManager;
import config.Config;
import config.ConfigIO;
import config.mcp.MCPServerConfig;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * MCP 工具列表弹窗
 * 
 * 显示所有工具的状态，支持启用/禁用切换和重试连接
 */
public class McpToolsDialog extends JDialog {
    
    private static final int DIALOG_WIDTH = 500;
    private static final int DIALOG_HEIGHT = 400;
    
    private final AgentLoop agentLoop;
    private final Config config;
    private JPanel toolsPanel;
    private JButton refreshButton;
    private JButton closeButton;
    
    // 颜色定义
    private static final Color CONNECTED_COLOR = new Color(52, 199, 89);   // 绿色
    private static final Color FAILED_COLOR = new Color(255, 59, 48);      // 红色
    private static final Color DISABLED_COLOR = new Color(142, 142, 147);  // 灰色
    private static final Color CARD_BG = new Color(255, 255, 255);
    private static final Color BORDER_COLOR = new Color(229, 229, 234);
    
    public McpToolsDialog(JFrame parent, AgentLoop agentLoop, Config config) {
        super(parent, "MCP 工具管理", true);
        this.agentLoop = agentLoop;
        this.config = config;
        
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        setLocationRelativeTo(parent);
        setResizable(true);
        
        initComponents();
        refreshStatus();
    }
    
    /**
     * 初始化组件
     */
    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(CARD_BG);
        
        // 工具列表区域
        toolsPanel = new JPanel();
        toolsPanel.setLayout(new BoxLayout(toolsPanel, BoxLayout.Y_AXIS));
        toolsPanel.setBackground(CARD_BG);
        
        JScrollPane scrollPane = new JScrollPane(toolsPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBackground(CARD_BG);
        
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // 底部按钮区域
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.setOpaque(false);
        
        refreshButton = new JButton("刷新状态");
        refreshButton.setFont(UiFonts.body());
        refreshButton.addActionListener(e -> refreshStatus());
        
        closeButton = new JButton("关闭");
        closeButton.setFont(UiFonts.bodyBold());
        closeButton.addActionListener(e -> {
            saveConfig();
            dispose();
        });
        
        buttonPanel.add(refreshButton);
        buttonPanel.add(closeButton);
        
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        setContentPane(mainPanel);
    }
    
    /**
     * 刷新所有工具状态
     */
    public void refreshStatus() {
        toolsPanel.removeAll();
        
        // 添加内置工具
        addBuiltinToolsSection();
        
        // 添加 MCP 工具
        addMcpToolsSection();
        
        toolsPanel.revalidate();
        toolsPanel.repaint();
    }
    
    /**
     * 添加内置工具区域
     */
    private void addBuiltinToolsSection() {
        JPanel sectionPanel = createSectionPanel("内置工具");
        
        if (agentLoop == null) {
            addEmptyMessage(sectionPanel, "AgentLoop 未初始化");
            toolsPanel.add(sectionPanel);
            return;
        }
        
        // 获取内置工具列表
        List<ToolInfo> builtinTools = getBuiltinToolsInfo();
        
        for (ToolInfo info : builtinTools) {
            JPanel toolPanel = createToolItemPanel(info, null, true);
            sectionPanel.add(toolPanel);
        }
        
        toolsPanel.add(sectionPanel);
        toolsPanel.add(Box.createVerticalStrut(8));
    }
    
    /**
     * 获取内置工具信息
     */
    private List<ToolInfo> getBuiltinToolsInfo() {
        List<ToolInfo> tools = new ArrayList<>();
        
        // 主要内置工具列表
        tools.add(new ToolInfo("read_file", "读取文件内容", true, true));
        tools.add(new ToolInfo("write_file", "写入文件内容", true, true));
        tools.add(new ToolInfo("edit_file", "编辑文件内容", true, true));
        tools.add(new ToolInfo("list_files", "列出目录内容", true, true));
        tools.add(new ToolInfo("Bash", "执行 shell 命令", true, true));
        tools.add(new ToolInfo("web_search", "网络搜索", true, true));
        tools.add(new ToolInfo("web_fetch", "获取网页内容", true, true));
        tools.add(new ToolInfo("skill", "加载技能", true, true));
        tools.add(new ToolInfo("memory_search", "搜索记忆", true, true));
        tools.add(new ToolInfo("Grep", "搜索文件内容", true, true));
        tools.add(new ToolInfo("Glob", "搜索文件路径", true, true));
        tools.add(new ToolInfo("message", "发送消息", true, true));
        tools.add(new ToolInfo("cron", "管理定时任务", true, true));
        tools.add(new ToolInfo("sessions_spawn", "创建子代理", true, true));
        tools.add(new ToolInfo("subagents_control", "控制子代理", true, true));
        
        return tools;
    }
    
    /**
     * 添加 MCP 工具区域
     */
    private void addMcpToolsSection() {
        JPanel sectionPanel = createSectionPanel("MCP 服务器工具");
        
        if (config == null) {
            addEmptyMessage(sectionPanel, "配置未初始化");
            toolsPanel.add(sectionPanel);
            return;
        }
        
        Map<String, MCPServerConfig> mcpServers = config.getTools().getMcpServers();
        if (mcpServers == null || mcpServers.isEmpty()) {
            addEmptyMessage(sectionPanel, "未配置 MCP 服务器");
            toolsPanel.add(sectionPanel);
            return;
        }
        
        // 获取连接状态（从 McpManager 获取真实状态）
        Map<String, Boolean> connectionStatus = getMcpConnectionStatus();
        
        for (Map.Entry<String, MCPServerConfig> entry : mcpServers.entrySet()) {
            String serverName = entry.getKey();
            MCPServerConfig serverConfig = entry.getValue();
            boolean connected = connectionStatus.getOrDefault(serverName, false);
            boolean enabled = serverConfig.isEnable();
            
            // 服务器标题
            JPanel serverHeader = createServerHeader(serverName, enabled, connected);
            sectionPanel.add(serverHeader);
            
            // 服务器下的工具
            ToolInfo serverInfo = new ToolInfo(
                serverName + " (服务器)",
                getServerDescription(serverConfig),
                enabled,
                connected
            );
            
            JPanel toolPanel = createToolItemPanel(serverInfo, serverName, false);
            sectionPanel.add(toolPanel);
            
            // 如果启用但未连接，显示重试按钮
            if (enabled && !connected) {
                JPanel retryPanel = createRetryPanel(serverName);
                sectionPanel.add(retryPanel);
            }
        }
        
        toolsPanel.add(sectionPanel);
    }
    
    /**
     * 获取 MCP 连接状态（从 McpManager 获取真实状态）
     */
    private Map<String, Boolean> getMcpConnectionStatus() {
        Map<String, Boolean> status = new HashMap<>();
        
        // 从 McpManager 获取已连接的服务器列表
        if (agentLoop != null) {
            McpManager mcpManager = agentLoop.getMcpManager();
            if (mcpManager != null) {
                Set<String> connectedServers = mcpManager.getConnectedServerNames();
                
                // 遍历配置中的服务器，检查是否在已连接列表中
                Map<String, MCPServerConfig> mcpServers = config.getTools().getMcpServers();
                if (mcpServers != null) {
                    for (String serverName : mcpServers.keySet()) {
                        status.put(serverName, connectedServers.contains(serverName));
                    }
                }
            }
        }
        
        return status;
    }
    
    /**
     * 获取服务器描述
     */
    private String getServerDescription(MCPServerConfig serverConfig) {
        if (serverConfig.getUrl() != null && !serverConfig.getUrl().isBlank()) {
            return "HTTP 服务: " + serverConfig.getUrl();
        }
        if (serverConfig.getCommand() != null && !serverConfig.getCommand().isBlank()) {
            return "命令: " + serverConfig.getCommand();
        }
        return "MCP 服务器";
    }
    
    /**
     * 创建区域面板
     */
    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CARD_BG);
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1),
            title,
            javax.swing.border.TitledBorder.LEFT,
            javax.swing.border.TitledBorder.TOP,
            UiFonts.bodyBold(),
            new Color(99, 99, 102)
        ));
        return panel;
    }
    
    /**
     * 添加空消息
     */
    private void addEmptyMessage(JPanel panel, String message) {
        JLabel label = new JLabel(message);
        label.setFont(UiFonts.caption());
        label.setForeground(DISABLED_COLOR);
        label.setBorder(new EmptyBorder(8, 12, 8, 12));
        panel.add(label);
    }
    
    /**
     * 创建服务器标题
     */
    private JPanel createServerHeader(String name, boolean enabled, boolean connected) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(CARD_BG);
        panel.setBorder(new EmptyBorder(4, 8, 4, 8));
        
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(UiFonts.bodyBold());
        nameLabel.setForeground(enabled ? new Color(28, 28, 30) : DISABLED_COLOR);
        
        String statusText = enabled ? (connected ? "[OK] 已连接" : "[X] 连接失败") : "[ ] 未启用";
        Color statusColor = enabled ? (connected ? CONNECTED_COLOR : FAILED_COLOR) : DISABLED_COLOR;
        
        JLabel statusLabel = new JLabel(statusText);
        statusLabel.setFont(UiFonts.caption());
        statusLabel.setForeground(statusColor);
        
        panel.add(nameLabel, BorderLayout.WEST);
        panel.add(statusLabel, BorderLayout.EAST);
        
        return panel;
    }
    
    /**
     * 创建工具项面板
     */
    private JPanel createToolItemPanel(ToolInfo info, String serverName, boolean isBuiltin) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(CARD_BG);
        panel.setBorder(new EmptyBorder(4, 12, 4, 12));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        
        // 工具名称
        JLabel nameLabel = new JLabel(info.name);
        nameLabel.setFont(UiFonts.body());
        nameLabel.setForeground(info.enabled ? new Color(28, 28, 30) : DISABLED_COLOR);
        
        // 来源标签
        JLabel sourceLabel = new JLabel(isBuiltin ? "内置" : "MCP");
        sourceLabel.setFont(UiFonts.caption());
        sourceLabel.setForeground(DISABLED_COLOR);
        
        // 左侧：名称 + 来源
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftPanel.setOpaque(false);
        leftPanel.add(nameLabel);
        leftPanel.add(sourceLabel);
        
        // 状态
        String statusText = info.enabled ? (info.connected ? "[OK]" : "[X]") : "[ ]";
        Color statusColor = info.enabled ? (info.connected ? CONNECTED_COLOR : FAILED_COLOR) : DISABLED_COLOR;
        
        JLabel statusLabel = new JLabel(statusText);
        statusLabel.setFont(UiFonts.body());
        statusLabel.setForeground(statusColor);
        
        // 启用开关
        JCheckBox enableCheckbox = new JCheckBox();
        enableCheckbox.setSelected(info.enabled);
        enableCheckbox.setFont(UiFonts.caption());
        enableCheckbox.setOpaque(false);
        enableCheckbox.setEnabled(!isBuiltin); // 内置工具不可禁用
        enableCheckbox.addActionListener(e -> {
            boolean newEnabled = enableCheckbox.isSelected();
            toggleToolEnable(info.name, serverName, newEnabled);
        });
        
        // 右侧：状态 + 开关
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.setOpaque(false);
        rightPanel.add(statusLabel);
        rightPanel.add(enableCheckbox);
        
        panel.add(leftPanel, BorderLayout.WEST);
        panel.add(rightPanel, BorderLayout.EAST);
        
        // 描述（第二行）
        if (info.description != null && !info.description.isBlank()) {
            JLabel descLabel = new JLabel("  " + info.description);
            descLabel.setFont(UiFonts.caption());
            descLabel.setForeground(DISABLED_COLOR);
            descLabel.setBorder(new EmptyBorder(0, 0, 4, 0));
            
            JPanel descPanel = new JPanel(new BorderLayout());
            descPanel.setOpaque(false);
            descPanel.add(descLabel, BorderLayout.WEST);
            
            // 使用垂直布局包装
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(panel, BorderLayout.NORTH);
            wrapper.add(descPanel, BorderLayout.CENTER);
            
            return wrapper;
        }
        
        return panel;
    }
    
    /**
     * 创建重试面板
     */
    private JPanel createRetryPanel(String serverName) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(0, 12, 4, 12));
        
        JButton retryButton = new JButton("重试连接");
        retryButton.setFont(UiFonts.caption());
        retryButton.addActionListener(e -> retryConnection(serverName));
        
        panel.add(retryButton);
        return panel;
    }
    
    /**
     * 切换工具启用状态
     */
    private void toggleToolEnable(String toolName, String serverName, boolean enable) {
        if (config == null) return;
        
        Map<String, MCPServerConfig> mcpServers = config.getTools().getMcpServers();
        if (mcpServers != null && serverName != null) {
            MCPServerConfig serverConfig = mcpServers.get(serverName);
            if (serverConfig != null) {
                serverConfig.setEnable(enable);
            }
        }
        
        // 刷新显示
        refreshStatus();
    }
    
    /**
     * 重试连接（调用 McpManager.reconnectServer）
     */
    private void retryConnection(String serverName) {
        if (agentLoop == null) {
            JOptionPane.showMessageDialog(this, 
                "AgentLoop 未初始化，无法重试连接", 
                "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        McpManager mcpManager = agentLoop.getMcpManager();
        if (mcpManager == null) {
            JOptionPane.showMessageDialog(this, 
                "McpManager 未初始化，无法重试连接", 
                "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        refreshButton.setText("连接中...");
        refreshButton.setEnabled(false);
        
        mcpManager.reconnectServer(serverName).thenAccept(errorMsg -> {
            SwingUtilities.invokeLater(() -> {
                refreshButton.setText("刷新状态");
                refreshButton.setEnabled(true);
                refreshStatus();
                
                if (errorMsg == null) {
                    JOptionPane.showMessageDialog(this, 
                        "重连成功: " + serverName, 
                        "成功", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, 
                        "重连失败: " + errorMsg, 
                        "错误", JOptionPane.ERROR_MESSAGE);
                }
            });
        }).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> {
                refreshButton.setText("刷新状态");
                refreshButton.setEnabled(true);
                JOptionPane.showMessageDialog(this, 
                    "重连异常: " + ex.getMessage(), 
                    "错误", JOptionPane.ERROR_MESSAGE);
            });
            return null;
        });
    }
    
    /**
     * 保存配置
     */
    private void saveConfig() {
        if (config == null) return;
        
        try {
            ConfigIO.saveConfig(config, null);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, 
                "保存配置失败: " + e.getMessage(), 
                "错误", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 工具信息
     */
    private record ToolInfo(String name, String description, boolean enabled, boolean connected) {}
}