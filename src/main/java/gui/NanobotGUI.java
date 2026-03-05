package gui;

import agent.AgentLoop;
import bus.MessageBus;
import config.ConfigIO;
import config.ConfigSchema;
import corn.CronService;
import providers.CustomProvider;
import providers.LLMProvider;
import providers.ProviderRegistry;
import session.SessionManager;
import utils.Helpers;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nanobot Swing GUI 客户端
 * 提供图形化界面与AI代理交互
 */
public class NanobotGUI extends JFrame {
    
    // UI组件
    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendButton;
    private JButton clearButton;
    private JLabel statusLabel;
    private JMenuBar menuBar;
    
    // 核心组件
    private ConfigSchema.Config config;
    private LLMProvider provider;
    private AgentLoop agentLoop;
    private CronService cron;
    private SessionManager sessionManager;
    
    // 状态管理
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean processing = new AtomicBoolean(false);
    private String sessionId = "cli:direct";
    private String cliChannel = "cli";
    private String cliChatId = "direct";
    
    // 样式
    private Style userStyle;
    private Style botStyle;
    private Style systemStyle;
    private Style timestampStyle;
    
    public NanobotGUI() {
        super("🐈 Nanobot - AI Assistant");
        initializeUI();
        initializeCore();
    }
    
    /**
     * 初始化UI界面
     */
    private void initializeUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 700);
        setLocationRelativeTo(null);
        
        // 创建菜单栏
        createMenuBar();
        
        // 主面板
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // 聊天显示区域
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14));
        chatPane.setMargin(new Insets(10, 10, 10, 10));
        
        // 初始化样式
        StyledDocument doc = chatPane.getStyledDocument();
        userStyle = doc.addStyle("UserStyle", null);
        StyleConstants.setForeground(userStyle, new Color(0, 100, 200));
        StyleConstants.setBold(userStyle, true);
        
        botStyle = doc.addStyle("BotStyle", null);
        StyleConstants.setForeground(botStyle, new Color(50, 50, 50));
        
        systemStyle = doc.addStyle("SystemStyle", null);
        StyleConstants.setForeground(systemStyle, new Color(150, 150, 150));
        StyleConstants.setItalic(systemStyle, true);
        
        timestampStyle = doc.addStyle("TimestampStyle", null);
        StyleConstants.setForeground(timestampStyle, new Color(180, 180, 180));
        StyleConstants.setFontSize(timestampStyle, 12);
        
        JScrollPane scrollPane = new JScrollPane(chatPane);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        
        // 输入区域
        JPanel inputPanel = new JPanel(new BorderLayout(10, 0));
        
        inputField = new JTextField();
        inputField.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14));
        inputField.addActionListener(e -> sendMessage());
        
        sendButton = new JButton("发送");
        sendButton.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 14));
        sendButton.addActionListener(e -> sendMessage());
        
        clearButton = new JButton("清空");
        clearButton.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14));
        clearButton.addActionListener(e -> clearChat());
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttonPanel.add(clearButton);
        buttonPanel.add(sendButton);
        
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(buttonPanel, BorderLayout.EAST);
        
        // 状态栏
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        // 组装主面板
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(inputPanel, BorderLayout.SOUTH);
        
        add(mainPanel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        
        // 添加窗口监听器
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        
        // 快捷键
        InputMap im = inputField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputField.getActionMap();
        
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        am.put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });
    }
    
    /**
     * 创建菜单栏
     */
    private void createMenuBar() {
        menuBar = new JMenuBar();
        
        // 文件菜单
        JMenu fileMenu = new JMenu("文件");
        fileMenu.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        
        JMenuItem statusItem = new JMenuItem("查看状态");
        statusItem.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        statusItem.addActionListener(e -> showStatus());
        
        JMenuItem clearItem = new JMenuItem("清空对话");
        clearItem.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        clearItem.addActionListener(e -> clearChat());
        
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        exitItem.addActionListener(e -> {
            shutdown();
            System.exit(0);
        });
        
        fileMenu.add(statusItem);
        fileMenu.add(clearItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        
        // 设置菜单
        JMenu settingsMenu = new JMenu("设置");
        settingsMenu.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        
        JMenuItem configItem = new JMenuItem("打开配置文件");
        configItem.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        configItem.addActionListener(e -> openConfig());
        
        JMenuItem workspaceItem = new JMenuItem("打开工作空间");
        workspaceItem.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        workspaceItem.addActionListener(e -> openWorkspace());
        
        settingsMenu.add(configItem);
        settingsMenu.add(workspaceItem);
        
        // 帮助菜单
        JMenu helpMenu = new JMenu("帮助");
        helpMenu.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        
        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        aboutItem.addActionListener(e -> showAbout());
        
        helpMenu.add(aboutItem);
        
        menuBar.add(fileMenu);
        menuBar.add(settingsMenu);
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
    }
    
    /**
     * 初始化核心组件
     */
    private void initializeCore() {
        try {
            // 加载配置
            config = ConfigIO.loadConfig(null);
            
            // 初始化组件
            provider = makeProvider(config);
            
            Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
            cron = new CronService(cronStorePath, null);
            
            sessionManager = new SessionManager(config.getWorkspacePath());
            MessageBus bus = new MessageBus();

            // 使用 null bus，因为 processDirect 不需要 bus
            agentLoop = new AgentLoop(
                    bus,  // bus not needed for processDirect
                provider,
                config.getWorkspacePath(),
                config.getAgents().getDefaults().getModel(),
                config.getAgents().getDefaults().getMaxToolIterations(),
                config.getAgents().getDefaults().getTemperature(),
                config.getAgents().getDefaults().getMaxTokens(),
                config.getAgents().getDefaults().getMemoryWindow(),
                config.getAgents().getDefaults().getReasoningEffort(),
                config.getTools().getWeb().getSearch().getApiKey(),
                config.getTools().getExec(),
                cron,
                config.getTools().isRestrictToWorkspace(),
                sessionManager,
                config.getTools().getMcpServers(),
                config.getChannels()
            );
            
            running.set(true);
            
            appendSystem("Nanobot 已启动，模型: " + config.getAgents().getDefaults().getModel());
            updateStatus("就绪 - " + config.getAgents().getDefaults().getModel());
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, 
                "初始化失败: " + e.getMessage(), 
                "错误", 
                JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
    
    /**
     * 发送消息 - 使用 agent 命令的 processDirect 方式
     */
    private void sendMessage() {
        String message = inputField.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        
        // 防止重复发送
        if (processing.get()) {
            return;
        }
        processing.set(true);
        
        // 显示用户消息
        appendUser(message);
        inputField.setText("");
        inputField.setEnabled(false);
        sendButton.setEnabled(false);
        updateStatus("思考中...");
        
        // 使用 processDirect 方式发送消息（对齐 AgentCmd 单次模式）
        CompletableFuture.runAsync(() -> {
            try {
                String resp = agentLoop.processDirect(
                    message,
                    sessionId,
                    cliChannel,
                    cliChatId,
                    this::onProgress
                ).toCompletableFuture().join();
                
                SwingUtilities.invokeLater(() -> {
                    if (resp != null && !resp.isBlank()) {
                        appendBot(resp);
                    }
                    updateStatus("就绪");
                    inputField.setEnabled(true);
                    sendButton.setEnabled(true);
                    inputField.requestFocus();
                    processing.set(false);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("错误: " + e.getMessage());
                    updateStatus("错误");
                    inputField.setEnabled(true);
                    sendButton.setEnabled(true);
                    inputField.requestFocus();
                    processing.set(false);
                });
            }
        });
    }
    
    /**
     * 进度回调 - 对齐 AgentCmd.BiProgress
     */
    private CompletionStage<Void> onProgress(String content, boolean toolHint) {
        var ch = agentLoop.getChannelsConfig();
        if (ch != null && toolHint && !ch.isSendToolHints()) {
            return CompletableFuture.completedFuture(null);
        }
        if (ch != null && !toolHint && !ch.isSendProgress()) {
            return CompletableFuture.completedFuture(null);
        }
        
        SwingUtilities.invokeLater(() -> appendProgress(content == null ? "" : content));
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 追加用户消息
     */
    private void appendUser(String message) {
        appendMessage("你", message, userStyle);
    }
    
    /**
     * 追加机器人消息
     */
    private void appendBot(String message) {
        appendMessage("🐈 Nanobot", message, botStyle);
    }
    
    /**
     * 追加系统消息
     */
    private void appendSystem(String message) {
        appendMessage("系统", message, systemStyle);
    }
    
    /**
     * 追加进度消息
     */
    private void appendProgress(String message) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            doc.insertString(doc.getLength(), "  ↳ " + message + "\n", systemStyle);
            scrollToBottom();
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 追加消息
     */
    private void appendMessage(String sender, String message, Style style) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            
            // 时间戳
            String timestamp = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
            doc.insertString(doc.getLength(), "[" + timestamp + "] ", timestampStyle);
            
            // 发送者
            doc.insertString(doc.getLength(), sender + ":\n", style);
            
            // 消息内容
            doc.insertString(doc.getLength(), message + "\n\n", botStyle);
            
            scrollToBottom();
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 滚动到底部
     */
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            chatPane.setCaretPosition(chatPane.getDocument().getLength());
        });
    }
    
    /**
     * 清空对话
     */
    private void clearChat() {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            doc.remove(0, doc.getLength());
            appendSystem("对话已清空");
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 更新状态栏
     */
    private void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
        });
    }
    
    /**
     * 显示状态
     */
    private void showStatus() {
        Path configPath = ConfigIO.getConfigPath();
        Path workspace = config.getWorkspacePath();
        
        StringBuilder sb = new StringBuilder();
        sb.append("🐈 Nanobot 状态\n\n");
        sb.append("配置文件: ").append(configPath).append(Files.exists(configPath) ? " ✓" : " ✗").append("\n");
        sb.append("工作空间: ").append(workspace).append(Files.exists(workspace) ? " ✓" : " ✗").append("\n");
        sb.append("模型: ").append(config.getAgents().getDefaults().getModel()).append("\n\n");
        
        sb.append("提供商状态:\n");
        for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
            var p = config.getProviders().getByName(spec.getName());
            if (p == null) continue;
            
            if (spec.isOauth()) {
                sb.append("  ").append(spec.getLabel()).append(": ✓ (OAuth)\n");
            } else if (spec.isLocal()) {
                String base = p.getApiBase();
                sb.append("  ").append(spec.getLabel()).append(": ")
                    .append(base != null && !base.isBlank() ? ("✓ " + base) : "未设置").append("\n");
            } else {
                boolean hasKey = p.getApiKey() != null && !p.getApiKey().isBlank();
                sb.append("  ").append(spec.getLabel()).append(": ").append(hasKey ? "✓" : "未设置").append("\n");
            }
        }
        
        JOptionPane.showMessageDialog(this, sb.toString(), "状态", JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * 打开配置文件
     */
    private void openConfig() {
        try {
            Path configPath = ConfigIO.getConfigPath();
            if (Files.exists(configPath)) {
                Desktop.getDesktop().open(configPath.toFile());
            } else {
                JOptionPane.showMessageDialog(this, 
                    "配置文件不存在: " + configPath, 
                    "错误", 
                    JOptionPane.ERROR_MESSAGE);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "无法打开配置文件: " + e.getMessage(), 
                "错误", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 打开工作空间
     */
    private void openWorkspace() {
        try {
            Path workspace = config.getWorkspacePath();
            if (Files.exists(workspace)) {
                Desktop.getDesktop().open(workspace.toFile());
            } else {
                JOptionPane.showMessageDialog(this, 
                    "工作空间不存在: " + workspace, 
                    "错误", 
                    JOptionPane.ERROR_MESSAGE);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "无法打开工作空间: " + e.getMessage(), 
                "错误", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * 显示关于对话框
     */
    private void showAbout() {
        String about = "🐈 Nanobot - AI Assistant\n\n" +
            "版本: 1.0\n" +
            "个人AI助手客户端\n\n" +
            "基于 Java Swing 开发\n" +
            "提供图形化界面与AI代理交互";
        
        JOptionPane.showMessageDialog(this, about, "关于", JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * 关闭资源
     */
    private void shutdown() {
        running.set(false);
        if (agentLoop != null) {
            agentLoop.stop();
            try {
                agentLoop.closeMcp().toCompletableFuture().join();
            } catch (Exception ignored) {}
        }
        if (cron != null) {
            cron.stop();
        }
    }
    
    /**
     * 创建Provider（对齐Commands.makeProvider）
     */
    static LLMProvider makeProvider(ConfigSchema.Config config) {
        String model = config.getAgents().getDefaults().getModel();
        String providerName = config.getProviderName(model);
        var p = config.getProvider(model);
        
        if ("openai_codex".equals(providerName) || (model != null && model.startsWith("openai-codex/"))) {
            throw new RuntimeException("Error: OpenAI Codex is not supported in this Java build.");
        }
        
        String apiKey = (p != null && p.getApiKey() != null) ? p.getApiKey() : null;
        String apiBase = config.getApiBase(model);
        
        if ("custom".equals(providerName)) {
            if (apiBase == null || apiBase.isBlank()) apiBase = "http://localhost:8000/v1";
            if (apiKey == null || apiKey.isBlank()) apiKey = "no-key";
            return new CustomProvider(apiKey, apiBase, model);
        }
        
        if (apiBase != null && !apiBase.isBlank()) {
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = "no-key";
            }
            return new CustomProvider(apiKey, apiBase, model);
        }
        
        ProviderRegistry.ProviderSpec spec = ProviderRegistry.findByName(providerName);
        boolean isOauth = spec != null && spec.isOauth();
        boolean isBedrock = model != null && model.startsWith("bedrock/");
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        
        if (!isBedrock && !hasKey && !isOauth) {
            throw new RuntimeException("Error: No API key configured (and no api_base set).");
        }
        
        throw new RuntimeException(
            "Error: Provider '" + providerName + "' is not supported in this Java build. " +
            "Tip: set tools/providers api_base to an OpenAI-compatible endpoint so CustomProvider can be used.");
    }
    
    /**
     * 主入口
     */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> {
            NanobotGUI gui = new NanobotGUI();
            gui.setVisible(true);
        });
    }
}