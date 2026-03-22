package gui;

import agent.AgentLoop;
import bus.MessageBus;
import bus.OutboundMessage;
import channels.ChannelManager;
import cli.BuiltinSkillsInstaller;
import cli.OnboardWizard;
import cli.RuntimeComponents;
import heartbeat.HeartbeatService;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import config.ConfigIO;
import config.ConfigSchema;
import corn.CronService;
import providers.CustomProvider;
import providers.LLMProvider;
import providers.ProviderCatalog;
import providers.ProviderRegistry;
import session.SessionManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static config.ConfigReloader.createRuntimeComponents;


/**
 * javaclawbot Swing GUI
 *
 * 目标：
 * 1) 保留 Swing 实现
 * 2) UI 风格贴近当前 JavaFX 版本
 * 3) 顶部按钮区：就绪 / 状态 / 清空 / Onboard
 * 4) 底部多行输入：Enter 发送，Shift+Enter 换行
 * 5) 左右聊天气泡
 * 6) System / Progress 左侧显示
 * 7) 启动信息不进入聊天流
 */
public class JavaClawBotGUI extends JFrame {
    private enum WizardFlow {
        QUICKSTART,
        ADVANCED
    }

    private enum ConfigAction {
        KEEP,
        MODIFY,
        RESET
    }
    // =========================
    // 主题色
    // =========================
    private static final Color WINDOW_BG = new Color(242, 242, 247);
    private static final Color CARD_BG = new Color(255, 255, 255);
    private static final Color HEADER_BG = new Color(255, 255, 255);

    private static final Color TEXT_PRIMARY = new Color(28, 28, 30);
    private static final Color TEXT_SECONDARY = new Color(99, 99, 102);
    private static final Color TEXT_MUTED = new Color(142, 142, 147);

    private static final Color USER_BUBBLE_BG = new Color(0, 122, 255);
    private static final Color USER_BUBBLE_TEXT = Color.WHITE;

    private static final Color BOT_BUBBLE_BG = new Color(255, 255, 255);
    private static final Color BOT_BUBBLE_TEXT = new Color(28, 28, 30);

    private static final Color SYSTEM_BUBBLE_BG = new Color(236, 236, 240);
    private static final Color SYSTEM_BUBBLE_TEXT = new Color(95, 95, 100);

    private static final Color PROGRESS_BUBBLE_BG = new Color(246, 246, 248);
    private static final Color PROGRESS_BUBBLE_TEXT = new Color(120, 120, 128);

    // =========================
    // UI
    // =========================
    private JPanel chatListPanel;
    private JScrollPane chatScrollPane;

    private JTextArea inputArea;
    private JScrollPane inputScrollPane;

    private JButton sendButton;
    private JButton clearButton;
    private JButton onboardButton;
    private JButton statusButton;
    private JButton gatewayButton;
    private JLabel statusLabel;
    private JLabel modelLabel;
    private JLabel gatewayStatusLabel;

    // =========================
    // 核心组件
    // =========================
    private ConfigSchema.Config config;
    private LLMProvider provider;
    private AgentLoop agentLoop;
    private CronService cron;
    private SessionManager sessionManager;
    private HeartbeatService heartbeat;
    private ChannelManager channels;
    private MessageBus bus;

    // =========================
    // Gateway 模式
    // =========================
    private boolean gatewayMode = false;
    private final AtomicBoolean gatewayRunning = new AtomicBoolean(false);

    // =========================
    // 状态
    // =========================
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 是否已经请求停止
     */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /**
     * 发送按钮当前是否处于“停止模式”
     * false = 普通发送
     * true  = 显示为 ■ ，点击发送 /stop
     */
    private final AtomicBoolean stopMode = new AtomicBoolean(false);

    private final String sessionId = "cli:direct";
    private final String cliChannel = "cli";
    private final String cliChatId = "direct";

    public JavaClawBotGUI() {
        this(false);
    }

    public JavaClawBotGUI(boolean gatewayMode) {
        super("javaclawbot");
        this.gatewayMode = gatewayMode;
        initializeWindow();
        initializeUI();
        initializeCore();
    }

    /**
     * 初始化窗口
     */
    private void initializeWindow() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(920, 660));
        setSize(1100, 800);
        setLocationRelativeTo(null);

        getContentPane().setBackground(WINDOW_BG);

        try {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "javaclawbot");
        } catch (Exception ignored) {
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
    }

    /**
     * 初始化 UI
     */
    private void initializeUI() {
        setLayout(new BorderLayout());
        setJMenuBar(createMenuBar());

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        root.setBackground(WINDOW_BG);

        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildCenterPanel(), BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);

        add(root, BorderLayout.CENTER);
    }

    /**
     * 顶部栏
     */
    private JComponent buildTopBar() {
        RoundedPanel header = new RoundedPanel(24, HEADER_BG);
        header.setLayout(new BorderLayout());
        header.setBorder(new EmptyBorder(14, 16, 14, 16));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("🐈 javaclawbot");
        titleLabel.setFont(UiFonts.bold(20));
        titleLabel.setForeground(TEXT_PRIMARY);

        modelLabel = new JLabel("Model · unknown");
        modelLabel.setFont(UiFonts.normal(13));
        modelLabel.setForeground(TEXT_MUTED);

        left.add(titleLabel);
        left.add(Box.createVerticalStrut(3));
        left.add(modelLabel);

        statusLabel = new JLabel("就绪");
        statusLabel.setFont(UiFonts.normal(13));
        statusLabel.setForeground(TEXT_SECONDARY);

        RoundedPanel statusCard = new RoundedPanel(16, new Color(248, 248, 250));
        statusCard.setLayout(new BorderLayout());
        statusCard.setBorder(new EmptyBorder(8, 12, 8, 12));
        statusCard.add(statusLabel, BorderLayout.CENTER);

        statusButton = new JButton("状态");
        styleSecondaryButton(statusButton, 82, 38);
        statusButton.addActionListener(e -> showStatus());

        clearButton = new JButton("清空");
        styleSecondaryButton(clearButton, 82, 38);
        clearButton.addActionListener(e -> clearChat());

        onboardButton = new JButton("Onboard");
        stylePrimaryButton(onboardButton, 110, 38);
        onboardButton.addActionListener(e -> runOnboard());

        gatewayButton = new JButton("启动 Gateway");
        styleSecondaryButton(gatewayButton, 120, 38);
        gatewayButton.addActionListener(e -> toggleGateway());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(statusCard);
        right.add(statusButton);
        right.add(clearButton);
        right.add(onboardButton);
        right.add(gatewayButton);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    /**
     * 聊天区
     */
    private JComponent buildCenterPanel() {
        RoundedPanel chatCard = new RoundedPanel(24, CARD_BG);
        chatCard.setLayout(new BorderLayout());
        chatCard.setBorder(new EmptyBorder(12, 12, 12, 12));

        chatListPanel = new JPanel();
        chatListPanel.setOpaque(false);
        chatListPanel.setLayout(new BoxLayout(chatListPanel, BoxLayout.Y_AXIS));
        chatListPanel.setBorder(new EmptyBorder(8, 6, 8, 6));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(chatListPanel, BorderLayout.NORTH);

        chatScrollPane = new JScrollPane(wrapper);
        chatScrollPane.setBorder(null);
        chatScrollPane.getViewport().setBackground(CARD_BG);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        beautifyScrollBar(chatScrollPane.getVerticalScrollBar());

        chatCard.add(chatScrollPane, BorderLayout.CENTER);
        return chatCard;
    }

    /**
     * 底部输入区
     *
     * Enter 发送
     * Shift+Enter 换行
     */
    private JComponent buildBottomPanel() {
        RoundedPanel inputCard = new RoundedPanel(24, CARD_BG);
        inputCard.setLayout(new BorderLayout(10, 0));
        inputCard.setBorder(new EmptyBorder(12, 12, 12, 12));

        inputArea = new JTextArea(3, 20);
        inputArea.setFont(UiFonts.normal(14));
        inputArea.setForeground(TEXT_PRIMARY);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBackground(Color.WHITE);
        inputArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        inputArea.setMargin(new Insets(0, 0, 0, 0));
        inputArea.setToolTipText("Enter 发送，Shift+Enter 换行");

        inputScrollPane = new JScrollPane(inputArea);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        inputScrollPane.setPreferredSize(new Dimension(200, 72));
        inputScrollPane.getViewport().setBackground(Color.WHITE);
        beautifyScrollBar(inputScrollPane.getVerticalScrollBar());

        RoundedPanel inputWrapper = new RoundedPanel(20, Color.WHITE);
        inputWrapper.setLayout(new BorderLayout());
        inputWrapper.add(inputScrollPane, BorderLayout.CENTER);

        // Enter 发送；Shift+Enter 保留默认换行
        InputMap im = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputArea.getActionMap();

        im.put(KeyStroke.getKeyStroke("ENTER"), "sendMessage");
        im.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");

        am.put("sendMessage", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (stopMode.get()) {
                    sendStopCommand();
                } else {
                    sendMessage();
                }
            }
        });

        sendButton = new JButton("发送");
        stylePrimaryButton(sendButton, 72, 40);
        sendButton.addActionListener(e -> {
            if (stopMode.get()) {
                sendStopCommand();
            } else {
                sendMessage();
            }
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(sendButton);

        inputCard.add(inputWrapper, BorderLayout.CENTER);
        inputCard.add(right, BorderLayout.EAST);

        return inputCard;
    }

    /**
     * 菜单栏
     */
    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("文件");
        JMenu settingsMenu = new JMenu("设置");
        JMenu helpMenu = new JMenu("帮助");

        JMenuItem statusItem = new JMenuItem("查看状态");
        statusItem.addActionListener(e -> showStatus());

        JMenuItem clearItem = new JMenuItem("清空对话");
        clearItem.addActionListener(e -> clearChat());

        JMenuItem onboardItem = new JMenuItem("执行 Onboard");
        onboardItem.addActionListener(e -> runOnboard());

        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> {
            shutdown();
            System.exit(0);
        });

        fileMenu.add(statusItem);
        fileMenu.add(clearItem);
        fileMenu.add(onboardItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        JMenuItem configItem = new JMenuItem("打开配置文件");
        configItem.addActionListener(e -> openConfig());

        JMenuItem workspaceItem = new JMenuItem("打开工作空间");
        workspaceItem.addActionListener(e -> openWorkspace());

        settingsMenu.add(configItem);
        settingsMenu.add(workspaceItem);

        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.addActionListener(e -> showAbout());

        helpMenu.add(aboutItem);

        bar.add(fileMenu);
        bar.add(settingsMenu);
        bar.add(helpMenu);
        return bar;
    }

    /**
     * 初始化核心组件
     */
    private void initializeCore() {
        try {
            RuntimeComponents rt = createRuntimeComponents();
            this.config = rt.getConfig();

            provider = makeProvider(this.config);

            Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
            cron = new CronService(cronStorePath, null);

            sessionManager = new SessionManager(this.config.getWorkspacePath());
            MessageBus bus = new MessageBus();

            agentLoop = new AgentLoop(
                    bus,
                    provider,
                    this.config.getWorkspacePath(),
                    this.config.getAgents().getDefaults().getModel(),
                    this.config.getAgents().getDefaults().getMaxToolIterations(),
                    this.config.getAgents().getDefaults().getTemperature(),
                    this.config.getAgents().getDefaults().getMaxTokens(),
                    this.config.getAgents().getDefaults().getMemoryWindow(),
                    this.config.getAgents().getDefaults().getReasoningEffort(),
                    this.config.getTools().getWeb().getSearch().getApiKey(),
                    this.config.getTools().getExec(),
                    cron,
                    this.config.getTools().isRestrictToWorkspace(),
                    sessionManager,
                    this.config.getTools().getMcpServers(),
                    this.config.getChannels(),
                    rt.getRuntimeSettings()
            );

            running.set(true);
            refreshModelLabel();
            updateStatus("就绪");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    this,
                    "初始化失败: " + e.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE
            );
            e.printStackTrace();
        }
    }

    /**
     * GUI版 Onboard
     */
    /**
     * GUI 版 Onboard（对齐 CLI OnboardWizard 流程）
     */
    private void runOnboard() {
        try {
            // 1) 风险确认
            if (!showRiskAcknowledgementDialog()) {
                appendSystem("Onboard 已取消");
                return;
            }

            // 2) 选择模式
            WizardFlow flow = selectWizardFlowDialog();
            if (flow == null) {
                appendSystem("Onboard 已取消");
                return;
            }

            Path configPath = ConfigIO.getConfigPath();
            ConfigAction configAction = ConfigAction.KEEP;

            // 3) 处理已有配置
            if (Files.exists(configPath)) {
                configAction = handleExistingConfigDialog(configPath);
                if (configAction == null) {
                    appendSystem("Onboard 已取消");
                    return;
                }
            }

            // 4) 加载配置
            ConfigSchema.Config cfg;
            if (Files.exists(configPath) && configAction == ConfigAction.RESET) {
                cfg = new ConfigSchema.Config();
            } else {
                cfg = ConfigIO.loadConfig(null);
            }

            // 5) workspace
            Path workspace = HelpersProxy.getWorkspacePathSafe();
            cfg.setWorkspacePath(workspace);

            // 6) 基础目录
            ensureWorkspaceStructure(workspace);

            appendSystem("开始执行 Onboard...");
            appendSystem("模式: " + flow);

            // 7) 配置主 provider
            configurePrimaryProviderDialog(cfg, flow, configAction);

            // 8) 配置 channel
            configureChannelsDialog(cfg, flow);

            // 9) 高级模式：配置 fallback
            if (flow == WizardFlow.ADVANCED) {
                configureFallbackDialog(cfg);
            }

            // 10) 配置技能
            configureSkillsDialog(cfg, configAction == ConfigAction.RESET);

            // 11) 模板
            createWorkspaceTemplates(workspace);

            // 12) 保存
            ConfigIO.saveConfig(cfg, null);

            // 13) 刷新 GUI 内部 config/provider
            this.config = ConfigIO.loadConfig(null);
            try {
                this.provider = makeProvider(this.config);
            } catch (Exception ignored) {
                // provider 未配置完整时允许先保存配置，不强制失败
            }
            refreshModelLabel();

            JOptionPane.showMessageDialog(
                    this,
                    "🐈 javaclawbot is ready!\n\n"
                            + "配置文件: " + configPath + "\n"
                            + "工作空间: " + workspace + "\n\n"
                            + "下一步：\n"
                            + "1. 检查 provider / model 配置\n"
                            + "2. 如需，补充 API key\n"
                            + "3. 开始聊天",
                    "Onboard 完成",
                    JOptionPane.INFORMATION_MESSAGE
            );

            appendSystem("✓ Onboard 完成");
            appendSystem("配置文件: " + configPath);
            appendSystem("工作空间: " + workspace);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    this,
                    "Onboard 失败: " + e.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE
            );
            appendSystem("Onboard 失败: " + e.getMessage());
        }
    }

    private boolean showRiskAcknowledgementDialog() {
        String msg = ""
                + "⚠️ 安全警告 — 请仔细阅读\n\n"
                + "javaclawbot 是一个个人 AI 助手，默认为单一可信操作边界。\n\n"
                + "重要提示：\n"
                + "• 此助手可以读取文件并执行操作（如果启用了工具）\n"
                + "• 恶意提示可能诱导其执行不安全操作\n"
                + "• 默认不适合多用户共享环境\n\n"
                + "建议的安全基线：\n"
                + "• 保持密钥远离助手可访问的文件系统\n"
                + "• 对启用工具的助手使用最强大的模型\n"
                + "• 多用户环境请隔离信任边界\n\n"
                + "是否继续？";

        int result = JOptionPane.showConfirmDialog(
                this,
                msg,
                "安全确认",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        return result == JOptionPane.YES_OPTION;
    }

    private WizardFlow selectWizardFlowDialog() {
        Object[] options = {"QuickStart", "Advanced", "取消"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "选择配置模式：\n\n"
                        + "QuickStart - 快速开始，使用推荐默认值\n"
                        + "Advanced - 高级模式，手动配置所有选项",
                "选择配置模式",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 0) return WizardFlow.QUICKSTART;
        if (choice == 1) return WizardFlow.ADVANCED;
        return null;
    }

    private ConfigAction handleExistingConfigDialog(Path configPath) {
        Object[] options = {"保留现有值", "更新配置值", "重置配置", "取消"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "检测到现有配置：\n" + configPath + "\n\n请选择处理方式：",
                "配置处理方式",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 0) return ConfigAction.KEEP;
        if (choice == 1) return ConfigAction.MODIFY;
        if (choice == 2) return ConfigAction.RESET;
        return null;
    }

    private void configurePrimaryProviderDialog(
            ConfigSchema.Config cfg,
            WizardFlow flow,
            ConfigAction configAction
    ) {
        java.util.List<ProviderCatalog.ProviderMeta> providers = ProviderCatalog.supportedProviders();

        if (providers == null || providers.isEmpty()) {
            appendSystem("未找到可用 provider 列表，跳过 provider 配置");
            return;
        }

        String currentProvider = cfg.getAgents().getDefaults().getProvider();
        ProviderCatalog.ProviderMeta selectedMeta = null;

        // QuickStart + KEEP：沿用现有
        if (flow == WizardFlow.QUICKSTART
                && configAction == ConfigAction.KEEP
                && currentProvider != null
                && !currentProvider.isBlank()) {
            for (ProviderCatalog.ProviderMeta p : providers) {
                if (p.getName().equalsIgnoreCase(currentProvider)) {
                    selectedMeta = p;
                    break;
                }
            }
        }

        if (selectedMeta == null) {
            String[] names = providers.stream()
                    .map(p -> p.getLabel() + " [" + p.getName() + "]")
                    .toArray(String[]::new);

            String selected = (String) JOptionPane.showInputDialog(
                    this,
                    "选择默认提供商：",
                    "Provider 配置",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    names,
                    names[0]
            );

            if (selected == null) {
                appendSystem("已跳过 provider 配置");
                return;
            }

            for (int i = 0; i < providers.size(); i++) {
                String text = providers.get(i).getLabel() + " [" + providers.get(i).getName() + "]";
                if (text.equals(selected)) {
                    selectedMeta = providers.get(i);
                    break;
                }
            }
        }

        if (selectedMeta == null) {
            appendSystem("未选择 provider，跳过");
            return;
        }

        String providerName = selectedMeta.getName();
        ConfigSchema.ProviderConfig providerConfig = cfg.getProviders().getByName(providerName);
        if (providerConfig == null) {
            appendSystem("未找到 provider 配置: " + providerName);
            return;
        }

        // API Base
        if (selectedMeta.isSupportsApiBase()) {
            String defaultBase = (providerConfig.getApiBase() != null && !providerConfig.getApiBase().isBlank())
                    ? providerConfig.getApiBase()
                    : selectedMeta.getDefaultApiBase();

            if (flow == WizardFlow.ADVANCED
                    || configAction == ConfigAction.MODIFY
                    || configAction == ConfigAction.RESET) {
                String input = JOptionPane.showInputDialog(this, "API Base：", defaultBase);
                if (input != null) {
                    providerConfig.setApiBase(input.trim());
                }
            } else if (defaultBase != null && !defaultBase.isBlank()) {
                providerConfig.setApiBase(defaultBase);
            }
        }

        // API Key
        if (selectedMeta.isSupportsApiKey()) {
            String existingKey = providerConfig.getApiKey();
            if (!(flow == WizardFlow.QUICKSTART
                    && configAction == ConfigAction.KEEP
                    && existingKey != null
                    && !existingKey.isBlank())) {
                JPasswordField pf = new JPasswordField();
                if (existingKey != null) {
                    pf.setText(existingKey);
                }
                int result = JOptionPane.showConfirmDialog(
                        this,
                        pf,
                        "输入 API Key",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE
                );
                if (result == JOptionPane.OK_OPTION) {
                    providerConfig.setApiKey(new String(pf.getPassword()).trim());
                }
            }
        }

        // Model
        String model = cfg.getAgents().getDefaults().getModel();
        java.util.List<String> recommended = selectedMeta.getRecommendedModels();

        if (selectedMeta.isManualModelOnly() || recommended == null || recommended.isEmpty()) {
            String input = JOptionPane.showInputDialog(this, "默认模型：", model);
            if (input != null && !input.isBlank()) {
                model = input.trim();
            }
        } else {
            java.util.List<String> modelOptions = new java.util.ArrayList<>(recommended);
            modelOptions.add("手动输入模型名称");

            String selectedModel = (String) JOptionPane.showInputDialog(
                    this,
                    "选择默认模型：",
                    "模型配置",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    modelOptions.toArray(),
                    modelOptions.contains(model) ? model : modelOptions.get(0)
            );

            if (selectedModel != null) {
                if ("手动输入模型名称".equals(selectedModel)) {
                    String manual = JOptionPane.showInputDialog(this, "输入模型名称：", model);
                    if (manual != null && !manual.isBlank()) {
                        model = manual.trim();
                    }
                } else {
                    model = selectedModel;
                }
            }
        }

        cfg.getAgents().getDefaults().setProvider(providerName);
        cfg.getAgents().getDefaults().setModel(model);

        appendSystem("✓ Provider 已配置: " + providerName);
        appendSystem("✓ 默认模型: " + model);
    }

    private void configureFallbackDialog(ConfigSchema.Config cfg) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "是否启用备用模型？\n\n当主模型不可用时，自动请求备用模型。",
                "Fallback 配置",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        boolean enabled = result == JOptionPane.YES_OPTION;
        cfg.getAgents().getDefaults().getFallback().setEnabled(enabled);

        if (!enabled) {
            cfg.getAgents().getDefaults().getFallback().getTargets().clear();
            appendSystem("已关闭 fallback");
            return;
        }

        cfg.getAgents().getDefaults().getFallback().setMode("on_error");

        String provider = JOptionPane.showInputDialog(
                this,
                "输入备用 provider 名称：",
                "custom"
        );
        if (provider == null || provider.isBlank()) {
            appendSystem("未填写 fallback provider，跳过");
            return;
        }

        String models = JOptionPane.showInputDialog(
                this,
                "输入备用模型（多个用逗号分隔）：",
                ""
        );
        if (models == null || models.isBlank()) {
            appendSystem("未填写 fallback model，跳过");
            return;
        }

        ConfigSchema.FallbackTarget t = new ConfigSchema.FallbackTarget();
        t.setEnabled(true);
        t.setProvider(provider.trim());
        t.setModels(
                java.util.Arrays.stream(models.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList()
        );

        cfg.getAgents().getDefaults().getFallback().getTargets().clear();
        cfg.getAgents().getDefaults().getFallback().getTargets().add(t);

        String maxAttemptsRaw = JOptionPane.showInputDialog(
                this,
                "失败尝试次数：",
                String.valueOf(cfg.getAgents().getDefaults().getFallback().getMaxAttempts())
        );
        try {
            int n = Integer.parseInt(maxAttemptsRaw);
            if (n > 0) {
                cfg.getAgents().getDefaults().getFallback().setMaxAttempts(n);
            }
        } catch (Exception ignored) {
        }

        appendSystem("✓ Fallback 已配置");
    }

    private void configureChannelsDialog(ConfigSchema.Config cfg, WizardFlow flow) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "是否配置 Channel？\n\n"
                        + "Channel 用于连接飞书 / Telegram / Discord 等外部平台。",
                "Channel 配置",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result != JOptionPane.YES_OPTION) {
            appendSystem("跳过 Channel 配置");
            return;
        }

        String[] channels = {"飞书(feishu)", "Telegram", "Discord", "WhatsApp", "完成"};
        while (true) {
            String selected = (String) JOptionPane.showInputDialog(
                    this,
                    "选择要配置的 Channel：",
                    "Channel 配置",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    channels,
                    "完成"
            );

            if (selected == null || "完成".equals(selected)) {
                break;
            }

            if ("飞书(feishu)".equals(selected)) {
                ConfigSchema.FeishuConfig feishu = cfg.getChannels().getFeishu();

                String appId = JOptionPane.showInputDialog(this, "Feishu App ID：", feishu.getAppId());
                if (appId != null) feishu.setAppId(appId.trim());

                JPasswordField pf = new JPasswordField();
                if (feishu.getAppSecret() != null) {
                    pf.setText(feishu.getAppSecret());
                }
                int ok = JOptionPane.showConfirmDialog(
                        this, pf, "Feishu App Secret", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
                );
                if (ok == JOptionPane.OK_OPTION) {
                    feishu.setAppSecret(new String(pf.getPassword()).trim());
                }

                int enable = JOptionPane.showConfirmDialog(
                        this,
                        "是否启用飞书 Channel？",
                        "启用确认",
                        JOptionPane.YES_NO_OPTION
                );
                feishu.setEnabled(enable == JOptionPane.YES_OPTION);

                appendSystem("✓ 飞书 Channel 已配置");
            } else {
                JOptionPane.showMessageDialog(
                        this,
                        selected + " 的 Swing 配置器你还没补全。\n"
                                + "当前建议先在 config.json 中手动填写，后续我可以继续帮你补 GUI 配置页。",
                        "提示",
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
        }
    }

    private void configureSkillsDialog(ConfigSchema.Config cfg, boolean overwrite) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "是否安装预构建 skills？\n\n"
                        + "QuickStart 下一般可跳过，后续再装也可以。",
                "Skills 配置",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result != JOptionPane.YES_OPTION) {
            appendSystem("跳过 skills 安装");
            return;
        }

        try {
            java.util.List<BuiltinSkillsInstaller.SkillResource> builtinSkills =
                    BuiltinSkillsInstaller.discoverBuiltinSkills();

            if (builtinSkills == null || builtinSkills.isEmpty()) {
                appendSystem("没有找到可安装的 builtin skills");
                return;
            }

            // 简化版：先全部安装
            BuiltinSkillsInstaller.InstallSummary summary =
                    BuiltinSkillsInstaller.installSelectedSkills(
                            cfg.getWorkspacePath(),
                            builtinSkills,
                            overwrite
                    );

            BuiltinSkillsInstaller.printSummary(summary);
            appendSystem("✓ Skills 安装完成");

        } catch (Exception e) {
            appendSystem("Skills 安装失败: " + e.getMessage());
        }
    }

    private static void ensureWorkspaceStructure(Path workspace) {
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {
        }

        try {
            Files.createDirectories(workspace.resolve("skills"));
        } catch (IOException ignored) {
        }

        Path memoryDir = workspace.resolve("memory");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException ignored) {
        }

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.notExists(memoryFile)) {
            try {
                Files.writeString(memoryFile, "", StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 切换为“停止模式”
     * 按钮显示为正方形 ■，可点击发送 /stop
     */
    private void enterStopMode() {
        SwingUtilities.invokeLater(() -> {
            stopMode.set(true);

            sendButton.setText("■");
            sendButton.setPreferredSize(new Dimension(40, 40));
            sendButton.setMinimumSize(new Dimension(40, 40));
            sendButton.setMaximumSize(new Dimension(40, 40));
            sendButton.setEnabled(true);
            sendButton.revalidate();
            sendButton.repaint();

            // 发送中：输入框禁用，但停止按钮保留
            inputArea.setEnabled(false);
            inputArea.setEditable(false);

            if (clearButton != null) clearButton.setEnabled(false);
            if (onboardButton != null) onboardButton.setEnabled(false);
            if (statusButton != null) statusButton.setEnabled(false);
            if (gatewayButton != null) gatewayButton.setEnabled(false);
        });
    }

    /**
     * 退出“停止模式”
     * 按钮恢复发送状态
     */
    private void exitStopMode() {
        SwingUtilities.invokeLater(() -> {
            stopMode.set(false);

            sendButton.setText("发送");
            sendButton.setPreferredSize(new Dimension(72, 40));
            sendButton.setMinimumSize(new Dimension(72, 40));
            sendButton.setMaximumSize(new Dimension(72, 40));
            sendButton.setEnabled(true);
            sendButton.revalidate();
            sendButton.repaint();

            inputArea.setEnabled(true);
            inputArea.setEditable(true);

            if (clearButton != null) clearButton.setEnabled(true);
            if (onboardButton != null) onboardButton.setEnabled(true);
            if (statusButton != null) statusButton.setEnabled(true);
            if (gatewayButton != null) gatewayButton.setEnabled(true);

            inputArea.requestFocusInWindow();
        });
    }

    /**
     * 点击停止按钮后，发送一条 /stop
     */
    private void sendStopCommand() {
        if (!processing.get()) {
            return;
        }

        // 防止重复点
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }

        appendSystem("正在发送 /stop ...");
        updateStatus("停止中...");

        CompletableFuture.runAsync(() -> {
            try {
                String resp = agentLoop.processDirect(
                        "/stop",
                        sessionId,
                        cliChannel,
                        cliChatId,
                        (content, toolHint) -> CompletableFuture.completedFuture(null)
                ).toCompletableFuture().join();

                SwingUtilities.invokeLater(() -> {
                    if (resp != null && !resp.isBlank()) {
                        appendSystem(resp);
                    } else {
                        appendSystem("已发送 /stop");
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> appendSystem("发送 /stop 失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 发送消息
     */
    private void sendMessage() {
        String message = inputArea.getText() == null ? "" : inputArea.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        // 正在处理时，不允许继续发普通消息
        if (processing.get()) {
            return;
        }

        processing.set(true);
        stopRequested.set(false);

        appendUser(message);
        inputArea.setText("");
        enterStopMode();
        updateStatus("思考中...");

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

                    updateStatus(stopRequested.get() ? "已停止" : "就绪");
                    processing.set(false);
                    stopRequested.set(false);
                    exitStopMode();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("错误: " + e.getMessage());
                    updateStatus("错误");
                    processing.set(false);
                    stopRequested.set(false);
                    exitStopMode();
                });
            }
        });
    }

    /**
     * 进度回调
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

    private void setInputEnabled(boolean enabled) {
        inputArea.setEnabled(enabled);
        inputArea.setEditable(enabled);

        // 如果当前处于 stopMode，发送按钮必须保持可点击
        if (stopMode.get()) {
            sendButton.setEnabled(true);
        } else {
            sendButton.setEnabled(enabled);
        }

        if (clearButton != null) clearButton.setEnabled(enabled);
        if (onboardButton != null) onboardButton.setEnabled(enabled);
        if (statusButton != null) statusButton.setEnabled(enabled);
        if (gatewayButton != null) gatewayButton.setEnabled(enabled);
    }

    private void appendUser(String message) {
        addBubble(BubbleType.USER, "你", message);
    }

    private void appendBot(String message) {
        addBubble(BubbleType.BOT, "🐈 javaclawbot", message);
    }

    private void appendSystem(String message) {
        addBubble(BubbleType.SYSTEM, "系统", message);
    }

    private void appendProgress(String message) {
        addBubble(BubbleType.PROGRESS, null, "↳ " + message);
    }

    /**
     * 添加气泡
     */
    private void addBubble(BubbleType type, String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(2, 8, 2, 8));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JComponent bubble = switch (type) {
            case USER, BOT, SYSTEM, PROGRESS -> new MarkdownBubblePanel(type, sender, message);
        };

        if (type == BubbleType.USER) {
            JPanel wrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            wrap.setOpaque(false);
            wrap.add(bubble);
            row.add(wrap, BorderLayout.EAST);
        } else {
            JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            wrap.setOpaque(false);
            wrap.add(bubble);
            row.add(wrap, BorderLayout.WEST);
        }

        chatListPanel.add(row);
        chatListPanel.add(Box.createVerticalStrut(2));
        chatListPanel.revalidate();
        chatListPanel.repaint();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = chatScrollPane.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    private void clearChat() {
        chatListPanel.removeAll();
        chatListPanel.revalidate();
        chatListPanel.repaint();
        appendSystem("对话已清空");
    }

    private void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    private void refreshModelLabel() {
        modelLabel.setText("Model · " + safeModel());
    }

    private String safeModel() {
        try {
            if (config != null && config.getAgents() != null
                    && config.getAgents().getDefaults() != null
                    && config.getAgents().getDefaults().getModel() != null
                    && !config.getAgents().getDefaults().getModel().isBlank()) {
                return config.getAgents().getDefaults().getModel();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private void showStatus() {
        if (config == null) {
            JOptionPane.showMessageDialog(this, "配置尚未初始化", "状态", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Path configPath = ConfigIO.getConfigPath();
        Path workspace = config.getWorkspacePath();

        StringBuilder sb = new StringBuilder();
        sb.append("🐈 javaclawbot 状态\n\n");
        sb.append("配置文件: ").append(configPath).append(Files.exists(configPath) ? " ＜（＾－＾）＞" : "┭┮﹏┭┮").append("\n");
        sb.append("工作空间: ").append(workspace).append(Files.exists(workspace) ? " ＜（＾－＾）＞" : "┭┮﹏┭┮").append("\n");
        sb.append("模型: ").append(safeModel()).append("\n\n");

        sb.append("提供商状态:\n");
        for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
            var p = config.getProviders().getByName(spec.getName());
            if (p == null) continue;

            if (spec.isOauth()) {
                sb.append("  ").append(spec.getLabel()).append(": 需要 (OAuth)\n");
            } else if (spec.isLocal()) {
                String base = p.getApiBase();
                sb.append("  ").append(spec.getLabel()).append(": ")
                        .append(base != null && !base.isBlank() ? ("＜（＾－＾）＞" + base) : "未设置")
                        .append("\n");
            } else {
                boolean hasKey = p.getApiKey() != null && !p.getApiKey().isBlank();
                sb.append("  ").append(spec.getLabel()).append(": ").append(hasKey ? "＜（＾－＾）＞" : "未设置").append("\n");
            }
        }

        JTextArea ta = new JTextArea(sb.toString());
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(UiFonts.normal(13));
        ta.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(580, 360));

        JOptionPane.showMessageDialog(this, sp, "状态", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openConfig() {
        try {
            Path configPath = ConfigIO.getConfigPath();
            if (Files.exists(configPath)) {
                Desktop.getDesktop().open(configPath.toFile());
            } else {
                JOptionPane.showMessageDialog(
                        this,
                        "配置文件不存在: " + configPath,
                        "错误",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    this,
                    "无法打开配置文件: " + e.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void openWorkspace() {
        try {
            if (config == null) {
                JOptionPane.showMessageDialog(this, "配置尚未初始化", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Path workspace = config.getWorkspacePath();
            if (Files.exists(workspace)) {
                Desktop.getDesktop().open(workspace.toFile());
            } else {
                JOptionPane.showMessageDialog(
                        this,
                        "工作空间不存在: " + workspace,
                        "错误",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    this,
                    "无法打开工作空间: " + e.getMessage(),
                    "错误",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void showAbout() {
        String about = ""
                + "🐈 javaclawbot - AI Assistant\n\n"
                + "版本: 1.0\n"
                + "UI: FlatLaf / FlatMacLightLaf\n"
                + "聊天布局: 左右气泡 + Markdown 展示\n"
                + "附加功能: GUI Onboard 按钮\n\n"
                + "基于 Java Swing 构建";

        JOptionPane.showMessageDialog(this, about, "关于", JOptionPane.INFORMATION_MESSAGE);
    }

    private void shutdown() {
        running.set(false);

        // Stop gateway components if running
        if (gatewayRunning.get()) {
            stopGateway();
        }

        if (agentLoop != null) {
            try {
                agentLoop.stop();
            } catch (Exception ignored) {
            }
            try {
                agentLoop.closeMcp().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }

        if (cron != null) {
            try {
                cron.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // =========================
    // Gateway Mode Methods
    // =========================

    /**
     * Start gateway mode (HeartbeatService + ChannelManager)
     */
    private void startGateway() {
        if (gatewayRunning.get()) {
            appendSystem("Gateway 已在运行中");
            return;
        }

        if (config == null) {
            appendSystem("错误: 配置未初始化");
            return;
        }

        // Check if channels are configured and show confirmation dialog
        StringBuilder channelInfo = new StringBuilder();
        boolean hasEnabledChannels = false;

        var channelsConfig = config.getChannels();
        if (channelsConfig != null) {
            // Check Discord
            if (channelsConfig.getDiscord() != null && channelsConfig.getDiscord().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• Discord: 已启用\n");
            }
            // Check Telegram
            if (channelsConfig.getTelegram() != null && channelsConfig.getTelegram().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• Telegram: 已启用\n");
            }
            // Check WhatsApp
            if (channelsConfig.getWhatsapp() != null && channelsConfig.getWhatsapp().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• WhatsApp: 已启用\n");
            }
            // Check feishu
            if (channelsConfig.getFeishu() != null && channelsConfig.getFeishu().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• 飞书: 已启用\n");
            }
        }

        // Show confirmation dialog
        String message;
        String title;
        if (hasEnabledChannels) {
            message = "检测到以下频道已配置并启用:\n\n" + channelInfo.toString() + "启动 Gateway 后将连接这些频道。\n\n是否确认启动 Gateway?";
            title = "确认启动 Gateway";
        } else {
            message = "未检测到已启用的频道配置。\n\n请确认 channel 配置是否正确。\n\n是否仍要启动 Gateway?";
            title = "频道配置确认";
        }

        int result = JOptionPane.showConfirmDialog(
                this,
                message,
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            appendSystem("Gateway 启动已取消");
            return;
        }

        // User confirmed, set hasEnabledChannels to true to proceed
        hasEnabledChannels = true;

        appendSystem("正在启动 Gateway...");
        updateStatus("启动 Gateway...");

        CompletableFuture.runAsync(() -> {
            try {
                // Initialize MessageBus if not exists
                if (bus == null) {
                    bus = new MessageBus();
                }

                // Initialize ChannelManager
                channels = new ChannelManager(config, bus);

                // Select heartbeat target: prefer non-cli/system session
                final AtomicReference<String> hbChannel = new AtomicReference<>("cli");
                final AtomicReference<String> hbChatId = new AtomicReference<>("direct");
                try {
                    Set<String> enabled = new HashSet<>(channels.getEnabledChannels());
                    for (Map<String, Object> s : sessionManager.listSessions()) {
                        Object keyObj = s.get("key");
                        if (!(keyObj instanceof String key)) continue;
                        if (!key.contains(":")) continue;
                        String[] parts = key.split(":", 2);
                        String ch = parts[0];
                        String cid = parts[1];
                        if ("cli".equals(ch) || "system".equals(ch)) continue;
                        if (enabled.contains(ch) && cid != null && !cid.isBlank()) {
                            hbChannel.set(ch);
                            hbChatId.set(cid);
                            break;
                        }
                    }
                } catch (Exception ignored) {}

                // Initialize HeartbeatService
                heartbeat = new HeartbeatService(
                        config.getWorkspacePath(),
                        provider,
                        agentLoop.getModel(),
                        tasks -> agentLoop.processDirect(
                                tasks,
                                "heartbeat",
                                hbChannel.get(),
                                hbChatId.get(),
                                (c, toolHint) -> CompletableFuture.completedFuture(null)
                        ).toCompletableFuture(),
                        response -> {
                            if ("cli".equals(hbChannel.get())) return CompletableFuture.completedFuture(null);
                            return bus.publishOutbound(new OutboundMessage(
                                    hbChannel.get(),
                                    hbChatId.get(),
                                    response != null ? response : "",
                                    null,
                                    null
                            )).toCompletableFuture();
                        },
                        HeartbeatService.parseConfig(config)
                );

                // Start services
                channels.startAll().toCompletableFuture().join();
                heartbeat.start().toCompletableFuture().join();

                gatewayRunning.set(true);

                SwingUtilities.invokeLater(() -> {
                    appendSystem("✓ Gateway 已启动");
                    appendSystem("  - HeartbeatService: 运行中");
                    appendSystem("  - ChannelManager: 运行中");
                    updateStatus("Gateway 运行中");
                    if (gatewayButton != null) {
                        gatewayButton.setText("停止 Gateway");
                    }
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystem("Gateway 启动失败: " + e.getMessage());
                    updateStatus("Gateway 启动失败");
                });
            }
        });
    }

    /**
     * Stop gateway mode
     */
    private void stopGateway() {
        if (!gatewayRunning.get()) {
            appendSystem("Gateway 未在运行");
            return;
        }

        appendSystem("正在停止 Gateway...");

        try {
            if (heartbeat != null) {
                heartbeat.stop();
                heartbeat = null;
            }

            if (channels != null) {
                channels.stopAll().toCompletableFuture().join();
                channels = null;
            }

            gatewayRunning.set(false);

            appendSystem("✓ Gateway 已停止");
            updateStatus("就绪");
            if (gatewayButton != null) {
                gatewayButton.setText("启动 Gateway");
            }

        } catch (Exception e) {
            appendSystem("Gateway 停止失败: " + e.getMessage());
        }
    }

    /**
     * Toggle gateway mode
     */
    private void toggleGateway() {
        if (gatewayRunning.get()) {
            stopGateway();
        } else {
            startGateway();
        }
    }

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
                        "Tip: set tools/providers api_base to an OpenAI-compatible endpoint so CustomProvider can be used."
        );
    }

    static void createWorkspaceTemplates(Path workspace) {
        OnboardWizard.createWorkspaceTemplates(workspace);
    }

    private void stylePrimaryButton(JButton btn, int width, int height) {
        btn.setFont(UiFonts.bold(13));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        btn.setPreferredSize(new Dimension(width, height));
        btn.setFocusable(false);
    }

    private void styleSecondaryButton(JButton btn, int width, int height) {
        btn.setFont(UiFonts.normal(13));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        btn.setPreferredSize(new Dimension(width, height));
        btn.setFocusable(false);
    }

    private void beautifyScrollBar(JScrollBar scrollBar) {
        scrollBar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(200, 200, 205);
                trackColor = new Color(0, 0, 0, 0);
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(new Color(185, 185, 190));
                g2.fillRoundRect(
                        thumbBounds.x + 3,
                        thumbBounds.y + 2,
                        thumbBounds.width - 6,
                        thumbBounds.height - 4,
                        10,
                        10
                );
                g2.dispose();
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            }

            private JButton zeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                return button;
            }
        });

        scrollBar.setOpaque(false);
        scrollBar.setPreferredSize(new Dimension(12, Integer.MAX_VALUE));
    }

    static final class HelpersProxy {
        static Path getWorkspacePathSafe() {
            try {
                ConfigSchema.Config cfg = ConfigIO.loadConfig(null);
                if (cfg != null && cfg.getWorkspacePath() != null) {
                    return cfg.getWorkspacePath();
                }
            } catch (Exception ignored) {
            }
            return Paths.get(System.getProperty("user.home"), ".javaclawbot", "workspace");
        }
    }

    enum BubbleType {
        USER,
        BOT,
        SYSTEM,
        PROGRESS
    }

    /**
     * 圆角卡片
     */
    static class RoundedPanel extends JPanel {
        private final int arc;
        private final Color bg;

        public RoundedPanel(int arc, Color bg) {
            this.arc = arc;
            this.bg = bg;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(new Color(0, 0, 0, 10));
            g2.fillRoundRect(0, 2, getWidth(), getHeight() - 1, arc, arc);

            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight() - 2, arc, arc);

            g2.setColor(new Color(0, 0, 0, 12));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 3, arc, arc);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Markdown 富文本气泡（User / Bot / System / Progress）
     */
    static class MarkdownBubblePanel extends JPanel {
        MarkdownBubblePanel(BubbleType type, String sender, String markdown) {
            setOpaque(false);
            setLayout(new BorderLayout());

            Color bubbleBg;
            Color senderColor;
            int maxWidth;
            int arc;

            switch (type) {
                case USER -> {
                    bubbleBg = USER_BUBBLE_BG;
                    senderColor = new Color(230, 240, 255);
                    maxWidth = 420;
                    arc = 22;
                }
                case BOT -> {
                    bubbleBg = BOT_BUBBLE_BG;
                    senderColor = TEXT_SECONDARY;
                    maxWidth = 700;
                    arc = 22;
                }
                case SYSTEM -> {
                    bubbleBg = SYSTEM_BUBBLE_BG;
                    senderColor = SYSTEM_BUBBLE_TEXT;
                    maxWidth = 420;
                    arc = 16;
                }
                case PROGRESS -> {
                    bubbleBg = PROGRESS_BUBBLE_BG;
                    senderColor = PROGRESS_BUBBLE_TEXT;
                    maxWidth = 520;
                    arc = 16;
                }
                default -> throw new IllegalStateException("Unexpected value: " + type);
            }

            RoundedPanel bubble = new RoundedPanel(arc, bubbleBg);
            bubble.setLayout(new BorderLayout());

            Insets bubbleInsets;
            if (type == BubbleType.USER) {
                bubbleInsets = new Insets(8, 10, 8, 10);
            } else if (type == BubbleType.SYSTEM || type == BubbleType.PROGRESS) {
                bubbleInsets = new Insets(8, 10, 8, 10);
            } else {
                bubbleInsets = new Insets(10, 12, 10, 12);
            }
            bubble.setBorder(new EmptyBorder(bubbleInsets));
            bubble.setMaximumSize(new Dimension(maxWidth, Integer.MAX_VALUE));

            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

            if (sender != null && !sender.isBlank()) {
                JLabel senderLabel = new JLabel(sender);
                senderLabel.setFont(type == BubbleType.BOT || type == BubbleType.USER ? UiFonts.bold(10) : UiFonts.bold(9));
                senderLabel.setForeground(senderColor);
                senderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(senderLabel);
                content.add(Box.createVerticalStrut(3));
            }

            String html = MarkdownRenderer.toHtml(markdown == null ? "" : markdown, type);

            JEditorPane htmlPane = new JEditorPane();
            htmlPane.setContentType("text/html");
            htmlPane.setText(html);
            htmlPane.setEditable(false);
            htmlPane.setOpaque(false);
            htmlPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            htmlPane.setBorder(null);
            htmlPane.setMargin(new Insets(0, 0, 0, 0));
            htmlPane.setFont(type == BubbleType.BOT || type == BubbleType.USER ? UiFonts.normal(10) : UiFonts.normal(9));
            htmlPane.setForeground(type == BubbleType.USER ? USER_BUBBLE_TEXT : BOT_BUBBLE_TEXT);

            CaretSafe.makeNonUpdating(htmlPane);

            htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);
            htmlPane.setSize(new Dimension(maxWidth - 40, Short.MAX_VALUE));
            Dimension pref = htmlPane.getPreferredSize();
            htmlPane.setPreferredSize(new Dimension(Math.min(pref.width, maxWidth - 40), pref.height));

            htmlPane.addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && Desktop.isDesktopSupported()) {
                    try {
                        Desktop.getDesktop().browse(e.getURL().toURI());
                    } catch (Exception ignored) {
                    }
                }
            });

            content.add(htmlPane);
            content.add(Box.createVerticalStrut(3));

            JLabel timeLabel = new JLabel(
                    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.now())
            );
            timeLabel.setFont(type == BubbleType.BOT || type == BubbleType.USER ? UiFonts.normal(10) : UiFonts.normal(9));
            timeLabel.setForeground(type == BubbleType.USER ? new Color(220, 230, 245) : TEXT_MUTED);
            timeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(timeLabel);

            bubble.add(content, BorderLayout.CENTER);
            add(bubble, BorderLayout.CENTER);

            setMaximumSize(new Dimension(maxWidth, Integer.MAX_VALUE));
        }
    }

    /**
     * Markdown -> HTML
     */
    static final class MarkdownRenderer {

        private static final Pattern CODE_BLOCK_PATTERN =
                Pattern.compile("(?s)```([a-zA-Z0-9_+-]*)\\n(.*?)\\n```");

        private MarkdownRenderer() {
        }

        static String toHtml(String markdown, BubbleType type) {
            String src = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');

            java.util.List<String> codeBlocks = new java.util.ArrayList<>();
            Matcher m = CODE_BLOCK_PATTERN.matcher(src);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String lang = m.group(1) == null ? "" : m.group(1).trim();
                String code = m.group(2) == null ? "" : m.group(2);
                String html = buildCodeBlockHtml(lang, code);
                int idx = codeBlocks.size();
                codeBlocks.add(html);
                m.appendReplacement(sb, "%%CODEBLOCK_" + idx + "%%");
            }
            m.appendTail(sb);
            src = sb.toString();

            String[] lines = src.split("\n", -1);
            StringBuilder out = new StringBuilder();

            boolean inUl = false;
            boolean inOl = false;
            boolean inQuote = false;
            boolean inParagraph = false;

            for (String raw : lines) {
                String line = raw == null ? "" : raw;

                if (line.matches("%%CODEBLOCK_\\d+%%")) {
                    if (inParagraph) {
                        out.append("</p>");
                        inParagraph = false;
                    }
                    if (inUl) {
                        out.append("</ul>");
                        inUl = false;
                    }
                    if (inOl) {
                        out.append("</ol>");
                        inOl = false;
                    }
                    if (inQuote) {
                        out.append("</blockquote>");
                        inQuote = false;
                    }
                    out.append(line);
                    continue;
                }

                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    if (inParagraph) {
                        out.append("</p>");
                        inParagraph = false;
                    }
                    if (inQuote) {
                        out.append("</blockquote>");
                        inQuote = false;
                    }
                    if (inUl) {
                        out.append("</ul>");
                        inUl = false;
                    }
                    if (inOl) {
                        out.append("</ol>");
                        inOl = false;
                    }
                    continue;
                }

                if (trimmed.startsWith("### ")) {
                    closeParagraphListQuote(out, State.of(inParagraph, inUl, inOl, inQuote));
                    inParagraph = inUl = inOl = inQuote = false;
                    out.append("<h3>").append(parseInline(trimmed.substring(4))).append("</h3>");
                    continue;
                }
                if (trimmed.startsWith("## ")) {
                    closeParagraphListQuote(out, State.of(inParagraph, inUl, inOl, inQuote));
                    inParagraph = inUl = inOl = inQuote = false;
                    out.append("<h2>").append(parseInline(trimmed.substring(3))).append("</h2>");
                    continue;
                }
                if (trimmed.startsWith("# ")) {
                    closeParagraphListQuote(out, State.of(inParagraph, inUl, inOl, inQuote));
                    inParagraph = inUl = inOl = inQuote = false;
                    out.append("<h1>").append(parseInline(trimmed.substring(2))).append("</h1>");
                    continue;
                }

                if (trimmed.startsWith("> ")) {
                    if (inParagraph) {
                        out.append("</p>");
                        inParagraph = false;
                    }
                    if (inUl) {
                        out.append("</ul>");
                        inUl = false;
                    }
                    if (inOl) {
                        out.append("</ol>");
                        inOl = false;
                    }
                    if (!inQuote) {
                        out.append("<blockquote>");
                        inQuote = true;
                    }
                    out.append(parseInline(trimmed.substring(2))).append("<br/>");
                    continue;
                } else if (inQuote) {
                    out.append("</blockquote>");
                    inQuote = false;
                }

                if (trimmed.matches("^[-*+]\\s+.+")) {
                    if (inParagraph) {
                        out.append("</p>");
                        inParagraph = false;
                    }
                    if (inOl) {
                        out.append("</ol>");
                        inOl = false;
                    }
                    if (!inUl) {
                        out.append("<ul>");
                        inUl = true;
                    }
                    out.append("<li>").append(parseInline(trimmed.replaceFirst("^[-*+]\\s+", ""))).append("</li>");
                    continue;
                }

                if (trimmed.matches("^\\d+\\.\\s+.+")) {
                    if (inParagraph) {
                        out.append("</p>");
                        inParagraph = false;
                    }
                    if (inUl) {
                        out.append("</ul>");
                        inUl = false;
                    }
                    if (!inOl) {
                        out.append("<ol>");
                        inOl = true;
                    }
                    out.append("<li>").append(parseInline(trimmed.replaceFirst("^\\d+\\.\\s+", ""))).append("</li>");
                    continue;
                }

                if (inUl) {
                    out.append("</ul>");
                    inUl = false;
                }
                if (inOl) {
                    out.append("</ol>");
                    inOl = false;
                }

                if (!inParagraph) {
                    out.append("<p>");
                    inParagraph = true;
                } else {
                    out.append("<br/>");
                }
                out.append(parseInline(trimmed));
            }

            if (inParagraph) out.append("</p>");
            if (inQuote) out.append("</blockquote>");
            if (inUl) out.append("</ul>");
            if (inOl) out.append("</ol>");

            String body = out.toString();

            for (int i = 0; i < codeBlocks.size(); i++) {
                body = body.replace("%%CODEBLOCK_" + i + "%%", codeBlocks.get(i));
            }

            String textColor = switch (type) {
                case USER -> "#ffffff";
                case BOT -> "#1c1c1e";
                case SYSTEM -> "#5f5f64";
                case PROGRESS -> "#787880";
            };

            String quoteBg = type == BubbleType.USER ? "rgba(255,255,255,0.16)" : "#f5f5f7";
            String quoteBorder = type == BubbleType.USER ? "rgba(255,255,255,0.35)" : "#d1d1d6";
            String inlineCodeBg = type == BubbleType.USER ? "rgba(255,255,255,0.16)" : "#f2f2f7";
            String linkColor = type == BubbleType.USER ? "#ffffff" : "#007aff";
            String bodyFontSize = (type == BubbleType.BOT || type == BubbleType.USER) ? "10" : "9";

            return "<html><head>"
                    + "<style>"
                    + "body { font-family: " + UiFonts.htmlFontFamily() + "; color:" + textColor + "; font-size:" + bodyFontSize + "px; margin:0; padding:0; }"
                    + "h1 { font-size:22px; margin:8px 0 10px 0; }"
                    + "h2 { font-size:18px; margin:8px 0 8px 0; }"
                    + "h3 { font-size:16px; margin:6px 0 8px 0; }"
                    + "p { margin:6px 0; line-height:1.6; }"
                    + "ul,ol { margin:6px 0 6px 22px; }"
                    + "li { margin:4px 0; line-height:1.6; }"
                    + "blockquote { margin:8px 0; padding:8px 12px; background:" + quoteBg + "; border-left:4px solid " + quoteBorder + "; }"
                    + "code.inline { font-family: 'JetBrains Mono','Consolas','Monospaced'; background:" + inlineCodeBg + "; padding:2px 5px; }"
                    + "pre { margin:0; white-space:pre-wrap; word-wrap:break-word; font-family: 'JetBrains Mono','Consolas','Monospaced'; font-size:13px; line-height:1.5; }"
                    + "a { color:" + linkColor + "; text-decoration:none; }"
                    + "</style>"
                    + "</head><body>"
                    + body
                    + "</body></html>";
        }

        private static void closeParagraphListQuote(StringBuilder out, State s) {
            if (s.inParagraph) out.append("</p>");
            if (s.inUl) out.append("</ul>");
            if (s.inOl) out.append("</ol>");
            if (s.inQuote) out.append("</blockquote>");
        }

        private static String buildCodeBlockHtml(String lang, String code) {
            String safeLang = escapeHtml(lang == null ? "" : lang);
            String safeCode = escapeHtml(code == null ? "" : code);
            String title = safeLang.isBlank()
                    ? ""
                    : "<div style='font-size:12px;color:#8e8e93;margin-bottom:6px;'>" + safeLang + "</div>";

            return "<div style='margin:8px 0;'>"
                    + "<div style='background:#111827;color:#f9fafb;padding:12px 14px;border-radius:12px;'>"
                    + title
                    + "<pre>" + safeCode + "</pre>"
                    + "</div>"
                    + "</div>";
        }

        private static String parseInline(String text) {
            String s = escapeHtml(text == null ? "" : text);

            s = s.replaceAll("\\[([^\\]]+)]\\((https?://[^)]+)\\)", "<a href=\"$2\">$1</a>");
            s = s.replaceAll("`([^`]+)`", "<code class='inline'>$1</code>");
            s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            s = s.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");

            return s;
        }

        private static String escapeHtml(String s) {
            return s
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
        }

        private record State(boolean inParagraph, boolean inUl, boolean inOl, boolean inQuote) {
            static State of(boolean p, boolean ul, boolean ol, boolean q) {
                return new State(p, ul, ol, q);
            }
        }
    }

    /**
     * 防止 HTML pane caret 导致滚动闪动
     */
    static final class CaretSafe {
        static void makeNonUpdating(JEditorPane pane) {
            try {
                if (pane.getCaret() instanceof DefaultCaret c) {
                    c.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
                }
            } catch (Exception ignored) {
            }
        }
    }

    static final class UiFonts {
        private UiFonts() {
        }

        static Font normal(int size) {
            return new Font("Microsoft YaHei UI", Font.PLAIN, size);
        }

        static Font bold(int size) {
            return new Font("Microsoft YaHei UI", Font.BOLD, size);
        }

        static String htmlFontFamily() {
            return "'Microsoft YaHei UI','Microsoft YaHei','PingFang SC','Segoe UI Emoji','Dialog'";
        }
    }

    public static void main(String[] args) {
        try {
            FlatMacLightLaf.setup();

            UIManager.put("TitlePane.unifiedBackground", true);
            UIManager.put("MenuBar.embedded", true);

            UIManager.put("Button.arc", 18);
            UIManager.put("Component.arc", 18);
            UIManager.put("TextComponent.arc", 18);

            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.showButtons", false);

            UIManager.put("Panel.background", WINDOW_BG);
            UIManager.put("TextField.background", Color.WHITE);
            UIManager.put("Button.default.background", new Color(0, 122, 255));
            UIManager.put("Button.default.foreground", Color.WHITE);

            Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
            FlatLaf.setPreferredFontFamily("Microsoft YaHei UI");
            FlatLaf.setPreferredLightFontFamily("Microsoft YaHei UI");
            FlatLaf.setPreferredSemiboldFontFamily("Microsoft YaHei UI");
            UIManager.put("defaultFont", font);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }

        SwingUtilities.invokeLater(() -> {
            JavaClawBotGUI gui = new JavaClawBotGUI();
            gui.setVisible(true);
        });
    }
}