package gui;

import agent.AgentLoop;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import channels.ChannelManager;
import cli.BuiltinSkillsInstaller;
import cli.OnboardWizard;
import cli.RuntimeComponents;
import cli.GatewayRuntime;
import config.Config;
import config.channel.FeishuConfig;
import config.provider.FallbackTarget;
import config.provider.ProviderConfig;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private JPanel bottomModePanel;
    private CardLayout bottomModeLayout;
    private JButton gatewayStopInlineButton;

    // =========================
    // 命令补全
    // =========================
    private CommandCompletionPopup commandCompletionPopup;

    // =========================
    // 侧边栏
    // =========================
    private CollapsibleSidebar sidebar;

    private static final String BOTTOM_CARD_INPUT = "input";
    private static final String BOTTOM_CARD_GATEWAY = "gateway";

    // =========================
    // 核心组件
    // =========================
    private Config config;
    private LLMProvider provider;
    private AgentLoop agentLoop;
    private CronService cron;
    private SessionManager sessionManager;
    private HeartbeatService heartbeat;
    private ChannelManager channels;
    private MessageBus bus;
    private CompletableFuture<Void> busTask;
    private CompletableFuture<Void> outboundTask;
    private final AtomicBoolean busLoopRunning = new AtomicBoolean(false);

    /**
     * GUI 当前会话阶段：
     * IDLE     - 空闲，可发送新消息
     * RUNNING  - 当前轮次正在执行，按钮显示为停止
     * STOPPING - 用户已点击停止，等待本轮收尾，按钮仍显示为停止
     *
     * 说明：
     * 旧实现把 processing / stopRequested / stopMode / latch 分散控制，
     * 在“点击停止 -> 旧轮次收尾 -> 新轮次开始”的交界处存在竞态，
     * 会出现下一次点击仍被当成停止按钮的问题。
     *
     * 这里改为显式状态机（State Pattern 的轻量实现）：
     * - UI 是否显示停止按钮，只由 turnPhase 决定
     * - 是否已有活动轮次，只由 activeTurnRef 决定
     * - 一轮消息的等待对象、响应缓存，全部收口到 TurnContext
     */
    private enum TurnPhase {
        IDLE,
        RUNNING,
        STOPPING
    }

    private enum GatewayPhase {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    private static final class TurnContext {
        final long turnId;
        final String userMessage;
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<String> responseRef = new AtomicReference<>(null);
        final AtomicBoolean outboundArrived = new AtomicBoolean(false);
        final AtomicBoolean stopSent = new AtomicBoolean(false);

        TurnContext(long turnId, String userMessage) {
            this.turnId = turnId;
            this.userMessage = userMessage;
        }

        void acceptOutbound(String content) {
            if (content != null && !content.isBlank()) {
                responseRef.compareAndSet(null, content);
            }
            if (outboundArrived.compareAndSet(false, true)) {
                doneLatch.countDown();
            }
        }

        String response() {
            return responseRef.get();
        }
    }

    private final AtomicReference<TurnContext> activeTurnRef = new AtomicReference<>(null);
    private final java.util.concurrent.atomic.AtomicLong turnSeq =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final AtomicReference<TurnPhase> turnPhase =
            new AtomicReference<>(TurnPhase.IDLE);

    private final java.util.concurrent.ExecutorService guiAgentExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "javaclawbot-gui-agent");
                t.setDaemon(true);
                return t;
            });

    // =========================
    // Gateway 模式
    // =========================
    private final boolean autoGatewayOnLaunch;
    private final AtomicBoolean gatewayRunning = new AtomicBoolean(false);
    private final AtomicReference<GatewayPhase> gatewayPhase = new AtomicReference<>(GatewayPhase.IDLE);
    private final AtomicBoolean guiChatAvailable = new AtomicBoolean(true);
    private GatewayRuntime gatewayRuntime;

    // =========================
    // 状态
    // =========================
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 是否已经请求停止
     *
     * 兼容旧逻辑保留此字段，但它现在只是 turnPhase 的派生缓存，
     * 不再作为发送按钮行为的唯一判定来源。
     */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /**
     * 发送按钮当前是否处于“停止模式”
     * false = 普通发送
     * true  = 显示为 ■ ，点击发送 /stop
     *
     * 兼容旧 UI 判断保留此字段，但真实来源统一由 applyTurnUiState() 写入。
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
        this.autoGatewayOnLaunch = gatewayMode;
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

        // 创建侧边栏
        sidebar = new CollapsibleSidebar();
        sidebar.setToolsButtonListener(e -> showMcpToolsDialog());

        // 创建中心区域（侧边栏 + 聊天区域）
        JPanel centerWrapper = new JPanel(new BorderLayout(0, 0));
        centerWrapper.setOpaque(false);
        centerWrapper.add(sidebar, BorderLayout.WEST);
        centerWrapper.add(buildCenterPanel(), BorderLayout.CENTER);

        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(centerWrapper, BorderLayout.CENTER);
        root.add(buildBottomPanel(), BorderLayout.SOUTH);

        add(root, BorderLayout.CENTER);

        // 初始化命令补全弹窗
        boolean developerMode = false;
        try {
            if (config != null && config.getAgents() != null
                && config.getAgents().getDefaults() != null) {
                developerMode = config.getAgents().getDefaults().isDevelopment();
            }
        } catch (Exception ignored) {}

        commandCompletionPopup = new CommandCompletionPopup(this, inputArea, developerMode);
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

        JLabel titleLabel = new JLabel("🐱 javaclawbot");
        titleLabel.setFont(UiFonts.title());
        titleLabel.setForeground(TEXT_PRIMARY);

        modelLabel = new JLabel("Model · unknown");
        modelLabel.setFont(UiFonts.heading());
        modelLabel.setForeground(TEXT_MUTED);

        left.add(titleLabel);
        left.add(Box.createVerticalStrut(3));
        left.add(modelLabel);

        statusLabel = new JLabel("就绪");
        statusLabel.setFont(UiFonts.heading());
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
     *
     * 说明：
     * - 普通模式显示可输入卡片
     * - Gateway 运行时切换到占位卡片，隐藏/灰掉输入区
     * - 占位卡片中保留“停止 Gateway”按钮，可恢复到 agent 直连模式
     */
    private JComponent buildBottomPanel() {
        bottomModeLayout = new CardLayout();
        bottomModePanel = new JPanel(bottomModeLayout);
        bottomModePanel.setOpaque(false);

        RoundedPanel inputCard = new RoundedPanel(24, CARD_BG);
        inputCard.setLayout(new BorderLayout(10, 0));
        inputCard.setBorder(new EmptyBorder(12, 12, 12, 12));

        inputArea = new JTextArea(3, 20);
        inputArea.setFont(UiFonts.input());
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

        RoundedPanel gatewayCard = new RoundedPanel(24, new Color(246, 246, 248));
        gatewayCard.setLayout(new BorderLayout(12, 0));
        gatewayCard.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel gatewayTextPanel = new JPanel();
        gatewayTextPanel.setOpaque(false);
        gatewayTextPanel.setLayout(new BoxLayout(gatewayTextPanel, BoxLayout.Y_AXIS));

        JLabel gatewayTitleLabel = new JLabel("Gateway 后台运行中");
        gatewayTitleLabel.setFont(UiFonts.bold(14));
        gatewayTitleLabel.setForeground(TEXT_PRIMARY);

        gatewayStatusLabel = new JLabel("当前输入区已禁用，消息将由 Gateway 在后台与飞书 / Telegram / Discord 等渠道通信");
        gatewayStatusLabel.setFont(UiFonts.normal(12));
        gatewayStatusLabel.setForeground(TEXT_SECONDARY);

        gatewayTextPanel.add(gatewayTitleLabel);
        gatewayTextPanel.add(Box.createVerticalStrut(4));
        gatewayTextPanel.add(gatewayStatusLabel);

        gatewayStopInlineButton = new JButton("停止 Gateway 并恢复直连");
        styleSecondaryButton(gatewayStopInlineButton, 170, 40);
        gatewayStopInlineButton.addActionListener(e -> stopGateway());

        JPanel gatewayRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        gatewayRight.setOpaque(false);
        gatewayRight.add(gatewayStopInlineButton);

        gatewayCard.add(gatewayTextPanel, BorderLayout.CENTER);
        gatewayCard.add(gatewayRight, BorderLayout.EAST);

        bottomModePanel.add(inputCard, BOTTOM_CARD_INPUT);
        bottomModePanel.add(gatewayCard, BOTTOM_CARD_GATEWAY);
        bottomModeLayout.show(bottomModePanel, BOTTOM_CARD_INPUT);

        return bottomModePanel;
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
     * 切换底部输入区域的展示模式。
     * Gateway 采用显式生命周期状态：IDLE / STARTING / RUNNING / STOPPING。
     */
    private void applyGatewayUiMode(GatewayPhase phase) {
        ui(() -> {
            boolean gatewayActive = phase != GatewayPhase.IDLE;
            boolean canCancelOrStop = phase == GatewayPhase.STARTING || phase == GatewayPhase.RUNNING;

            if (bottomModePanel != null && bottomModeLayout != null) {
                bottomModeLayout.show(bottomModePanel, gatewayActive ? BOTTOM_CARD_GATEWAY : BOTTOM_CARD_INPUT);
            }
            if (inputArea != null) {
                inputArea.setEditable(!gatewayActive);
                inputArea.setEnabled(!gatewayActive);
            }
            if (inputScrollPane != null) {
                inputScrollPane.setEnabled(!gatewayActive);
            }
            if (sendButton != null && !gatewayActive) {
                sendButton.setEnabled(true);
            }
            if (gatewayStopInlineButton != null) {
                gatewayStopInlineButton.setVisible(gatewayActive);
                gatewayStopInlineButton.setEnabled(canCancelOrStop);
                if (phase == GatewayPhase.STARTING) {
                    gatewayStopInlineButton.setText("取消 Gateway 启动并恢复直连");
                } else if (phase == GatewayPhase.RUNNING) {
                    gatewayStopInlineButton.setText("停止 Gateway 并恢复直连");
                } else if (phase == GatewayPhase.STOPPING) {
                    gatewayStopInlineButton.setText("正在停止 Gateway...");
                } else {
                    gatewayStopInlineButton.setText("停止 Gateway 并恢复直连");
                }
            }
            if (gatewayStatusLabel != null) {
                if (phase == GatewayPhase.STARTING) {
                    gatewayStatusLabel.setText("Gateway 正在后台启动，你现在可以点击右侧按钮取消启动并恢复 GUI 直连模式");
                } else if (phase == GatewayPhase.RUNNING) {
                    gatewayStatusLabel.setText("当前输入区已禁用，消息将由 Gateway 在后台与飞书 / Telegram / Discord 等渠道通信");
                } else if (phase == GatewayPhase.STOPPING) {
                    gatewayStatusLabel.setText("Gateway 正在停止并恢复 GUI 直连模式，请稍候");
                } else {
                    gatewayStatusLabel.setText("Gateway 已停止，当前输入区将恢复可用");
                }
            }
        });
    }

    /**
     * 初始化核心组件
     *
     * 重构目标：
     * 1) Core 与 GUI Direct Adapter 解耦
     * 2) 启动 Gateway 时，不再让 GUI 默认直连模式继续占用 bus / agent
     * 3) GUI 只在非 gatewayMode 下启动本地直连聊天
     */
    private void initializeCore() {
        try {
            RuntimeComponents rt = createRuntimeComponents();
            this.config = rt.getConfig();
            this.sessionManager = new SessionManager(this.config.getWorkspacePath());

            refreshModelLabel();

            // 更新命令补全的开发者模式
            if (commandCompletionPopup != null) {
                boolean devMode = this.config.getAgents().getDefaults().isDevelopment();
                commandCompletionPopup.updateDeveloperMode(devMode);
            }

            if (autoGatewayOnLaunch) {
                guiChatAvailable.set(false);
                applyTurnUiState(TurnPhase.IDLE);
                applyGatewayUiMode(GatewayPhase.STARTING);
                appendSystem("检测到 gatewayMode=true：GUI 将自动后台启动 Gateway，输入区已切换为 Gateway 占位模式");
                updateStatus("启动 Gateway...");
                SwingUtilities.invokeLater(() -> startGateway(true, false));
            } else {
                startGuiDirectMode();
            }
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
     * 构建 GUI 直连模式所需的 core 组件，但不自动启动 bus adapter。
     */
    private void buildGuiDirectCoreIfNeeded() {
        if (bus != null && agentLoop != null && cron != null && provider != null) {
            return;
        }

        RuntimeComponents rt = createRuntimeComponents();
        this.config = rt.getConfig();

        if (this.sessionManager == null) {
            this.sessionManager = new SessionManager(this.config.getWorkspacePath());
        }

        provider = makeProvider(this.config);

        Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
        cron = new CronService(cronStorePath, null);
        bus = new MessageBus();

        agentLoop = new AgentLoop(
                this.bus,
                provider,
                this.config.getWorkspacePath(),
                this.config.getAgents().getDefaults().getModel(),
                this.config.getAgents().getDefaults().getMaxToolIterations(),
                this.config.obtainTemperature(provider.getDefaultModel()),
                this.config.obtainMaxTokens(provider.getDefaultModel()),
                this.config.obtainContextWindow(provider.getDefaultModel()),
                this.config.getAgents().getDefaults().getMemoryWindow(),
                this.config.getAgents().getDefaults().getReasoningEffort(),
                cron,
                this.config.getTools().isRestrictToWorkspace(),
                sessionManager,
                this.config.getTools().getMcpServers(),
                this.config.getChannels(),
                rt.getRuntimeSettings()
        );

        refreshModelLabel();
    }

    /**
     * 启动 GUI 本地直连模式（cli:direct）。
     */
    private void startGuiDirectMode() {
        try {
            buildGuiDirectCoreIfNeeded();
            running.set(true);
            guiChatAvailable.set(true);
            gatewayPhase.set(GatewayPhase.IDLE);
            startBusInteractiveMode();
            applyGatewayUiMode(GatewayPhase.IDLE);
            applyTurnUiState(TurnPhase.IDLE);
            if (gatewayButton != null) {
                gatewayButton.setText("启动 Gateway");
                gatewayButton.setEnabled(true);
            }
            updateStatus("就绪");

            // 加载历史消息
            loadHistoryMessages();
        } catch (Exception e) {
            guiChatAvailable.set(false);
            applyTurnUiState(TurnPhase.IDLE);
            appendSystem("GUI 直连模式启动失败: " + e.getMessage());
            updateStatus("初始化失败");
        }
    }

    /**
     * 停止 GUI 本地直连模式。
     *
     * @param shutdownCore true 时同时停止并释放 bus/agent/cron/provider
     */
    private void stopGuiDirectMode(boolean shutdownCore) {
        stopBusInteractiveMode();

        processing.set(false);
        stopRequested.set(false);
        turnPhase.set(TurnPhase.IDLE);
        guiChatAvailable.set(false);
        applyTurnUiState(TurnPhase.IDLE);

        if (!shutdownCore) {
            return;
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
            agentLoop = null;
        }

        if (cron != null) {
            try {
                cron.stop();
            } catch (Exception ignored) {
            }
            cron = null;
        }

        bus = null;
        provider = null;
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
            Config cfg;
            if (Files.exists(configPath) && configAction == ConfigAction.RESET) {
                cfg = new Config();
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
                    "🐱 javaclawbot is ready!\n\n"
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
            Config cfg,
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
        ProviderConfig providerConfig = cfg.getProviders().getByName(providerName);
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

    private void configureFallbackDialog(Config cfg) {
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

        FallbackTarget t = new FallbackTarget();
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

    private void configureChannelsDialog(Config cfg, WizardFlow flow) {
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
                FeishuConfig feishu = cfg.getChannels().getFeishu();

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

    private void configureSkillsDialog(Config cfg, boolean overwrite) {
        java.util.List<BuiltinSkillsInstaller.SkillResource> builtinSkills =
                BuiltinSkillsInstaller.discoverBuiltinSkills();

        if (builtinSkills == null || builtinSkills.isEmpty()) {
            appendSystem("没有找到可安装的 builtin skills");
            return;
        }

        // Build multi-select dialog
        JDialog dialog = new JDialog(this, "选择要安装的内置技能", true);
        dialog.setLayout(new java.awt.BorderLayout(10, 10));

        JPanel checkboxPanel = new JPanel();
        checkboxPanel.setLayout(new javax.swing.BoxLayout(checkboxPanel, javax.swing.BoxLayout.Y_AXIS));

        java.util.List<JCheckBox> checkboxes = new java.util.ArrayList<>();
        for (BuiltinSkillsInstaller.SkillResource skill : builtinSkills) {
            JCheckBox cb = new JCheckBox(skill.getName(), true);
            checkboxes.add(cb);
            checkboxPanel.add(cb);
        }

        JScrollPane scrollPane = new JScrollPane(checkboxPanel);
        scrollPane.setPreferredSize(new java.awt.Dimension(350, Math.min(400, builtinSkills.size() * 30 + 50)));

        JPanel buttonPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        JButton okButton = new JButton("安装选中");
        JButton cancelButton = new JButton("跳过");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        dialog.add(scrollPane, java.awt.BorderLayout.CENTER);
        dialog.add(buttonPanel, java.awt.BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        final boolean[] confirmed = {false};
        okButton.addActionListener(e -> {
            confirmed[0] = true;
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);

        if (!confirmed[0]) {
            appendSystem("跳过 skills 安装");
            return;
        }

        // Collect selected skills
        java.util.List<BuiltinSkillsInstaller.SkillResource> selected = new java.util.ArrayList<>();
        for (int i = 0; i < checkboxes.size(); i++) {
            if (checkboxes.get(i).isSelected()) {
                selected.add(builtinSkills.get(i));
            }
        }

        if (selected.isEmpty()) {
            appendSystem("没有选择任何技能，跳过安装");
            return;
        }

        try {
            BuiltinSkillsInstaller.InstallSummary summary =
                    BuiltinSkillsInstaller.installSelectedSkills(
                            cfg.getWorkspacePath(),
                            selected,
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
     * 启动 GUI 版 bus 交互模式
     * 逻辑对齐 CLI 的 agent 非单次模式：
     * 1) 后台常驻运行 agentLoop.run()
     * 2) GUI 通过 bus.publishInbound() 发消息
     * 3) GUI 后台消费 bus outbound，并把 progress / 最终回复分流
     */

    private void startBusInteractiveMode() {
        if (busLoopRunning.get()) {
            return;
        }
        if (bus == null || agentLoop == null) {
            appendSystem("Bus 交互模式启动失败：bus 或 agentLoop 未初始化");
            return;
        }

        busLoopRunning.set(true);

        busTask = CompletableFuture.runAsync(() -> {
            try {
                agentLoop.run();
            } catch (Exception e) {
                ui(() -> appendSystem("AgentLoop 运行失败: " + e.getMessage()));
            }
        }, guiAgentExecutor);

        outboundTask = CompletableFuture.runAsync(() -> {
            while (busLoopRunning.get()) {
                try {
                    OutboundMessage out = bus.consumeOutbound(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (out == null) {
                        continue;
                    }

                    // Gateway 模式与 GUI 直连模式共用同一 bus 时，必须尽量只消费当前 GUI 会话的消息。
                    // 这里使用反射读取 channel/chatId，避免强依赖消息类的具体 getter 形状。
                    if (!isTargetCliOutbound(out)) {
                        continue;
                    }

                    Map<String, Object> meta = out.getMetadata() != null ? out.getMetadata() : Map.of();
                    boolean isProgress = Boolean.TRUE.equals(meta.get("_progress"));
                    boolean isToolHint = Boolean.TRUE.equals(meta.get("_tool_hint"));

                    if (isProgress) {
                        // GUI 直连模式：工具调用提示始终显示，不受 ChannelsConfig.sendToolHints 控制
                        // ChannelsConfig 仅用于外部渠道（Telegram/Discord 等）
                        if (!isToolHint) {
                            var ch = agentLoop.getChannelsConfig();
                            if (ch != null && !ch.isSendProgress()) {
                                continue;
                            }
                        }

                        String content = out.getContent() == null ? "" : out.getContent();
                        ui(() -> appendProgress(content));
                        continue;
                    }

                    TurnContext turn = activeTurnRef.get();
                    if (turn != null) {
                        turn.acceptOutbound(out.getContent());
                    } else if (out.getContent() != null && !out.getContent().isBlank()) {
                        String content = out.getContent();
                        ui(() -> appendBot(content));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                }
            }
        }, guiAgentExecutor);
    }

    /**
     * 停止 GUI 版 bus 交互模式
     */
    private void stopBusInteractiveMode() {
        busLoopRunning.set(false);

        TurnContext turn = activeTurnRef.getAndSet(null);
        if (turn != null) {
            while (turn.doneLatch.getCount() > 0) {
                turn.doneLatch.countDown();
            }
        }

        if (outboundTask != null) {
            outboundTask.cancel(true);
        }
        if (busTask != null) {
            busTask.cancel(true);
        }
    }

    /**
     * 切换为“停止模式”
     * 按钮显示为正方形 ■，可点击发送 /stop
     */
    private void enterStopMode() {
        applyTurnUiState(turnPhase.get() == TurnPhase.IDLE ? TurnPhase.RUNNING : turnPhase.get());
    }

    /**
     * 退出“停止模式”
     * 按钮恢复发送状态
     */
    private void exitStopMode() {
        applyTurnUiState(TurnPhase.IDLE);
    }

    /**
     * 点击停止按钮后，发送一条 /stop
     * 注意：这里也必须走 bus，不能再走 processDirect，避免两套通道并存。
     */
    private void sendStopCommand() {
        TurnContext turn = activeTurnRef.get();
        if (turn == null || !processing.get()) {
            return;
        }

        if (!turn.stopSent.compareAndSet(false, true)) {
            return;
        }

        stopRequested.set(true);
        turnPhase.set(TurnPhase.STOPPING);
        enterStopMode();

        appendSystem("正在发送 /stop ...");
        updateStatus("停止中...");

        CompletableFuture.runAsync(() -> {
            try {
                InboundMessage stopMsg = new InboundMessage(
                        cliChannel,
                        "user",
                        cliChatId,
                        "/stop",
                        null,
                        null
                );

                bus.publishInbound(stopMsg).toCompletableFuture().join();
            } catch (Exception e) {
                ui(() -> appendSystem("发送 /stop 失败: " + e.getMessage()));
            }
        }, guiAgentExecutor);
    }

    /**
     * 发送消息
     * 改为对齐 CLI agent 非单次交互模式：
     * 1) GUI 只负责 publishInbound
     * 2) AgentLoop.run() 常驻消费
     * 3) 最终回复由 outbound 消费线程回填
     */
    private void sendMessage() {
        if (gatewayRunning.get() || !guiChatAvailable.get()) {
            appendSystem("Gateway 运行中，GUI 直连聊天已停用");
            return;
        }

        String message = inputArea.getText() == null ? "" : inputArea.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        if (bus == null || agentLoop == null) {
            appendSystem("错误: bus 或 agentLoop 尚未初始化");
            return;
        }

        // 显式状态机：只有 IDLE 才允许开启新轮次
        if (!processing.compareAndSet(false, true)) {
            return;
        }

        TurnContext turn = new TurnContext(turnSeq.incrementAndGet(), message);
        activeTurnRef.set(turn);
        stopRequested.set(false);
        turnPhase.set(TurnPhase.RUNNING);

        appendUser(message);
        inputArea.setText("");
        enterStopMode();
        updateStatus("思考中...");

        CompletableFuture.runAsync(() -> {
            try {
                InboundMessage in = new InboundMessage(
                        cliChannel,
                        "user",
                        cliChatId,
                        message,
                        null,
                        null
                );

                bus.publishInbound(in).toCompletableFuture().join();

                boolean ok = turn.doneLatch.await(30, java.util.concurrent.TimeUnit.MINUTES);
                String resp = turn.response();

                finishTurn(turn, ok, resp, null);
            } catch (Exception e) {
                finishTurn(turn, false, null, e);
            }
        }, guiAgentExecutor);
    }

    /**
     * 统一收尾当前轮次。
     *
     * 这是这次修复的核心：
     * - processing / stopRequested / stopMode / activeTurn 一次性在同一处归位
     * - 不再把“外层 invokeLater -> 内层 exitStopMode 再 invokeLater”拆成两拍
     * - 避免出现 processing 已 false，但 stopMode 还没恢复，导致下一次点击仍被识别成停止
     */
    private void finishTurn(TurnContext turn, boolean ok, String resp, Exception error) {
        if (turn == null) {
            return;
        }

        // 只允许当前活动轮次收尾，旧轮次/重复回调直接丢弃
        if (!activeTurnRef.compareAndSet(turn, null)) {
            return;
        }

        processing.set(false);
        stopRequested.set(false);
        turnPhase.set(TurnPhase.IDLE);

        ui(() -> {
            try {
                if (error != null) {
                    appendSystem("错误: " + error.getMessage());
                    updateStatus("错误");
                } else if (!ok) {
                    appendSystem("等待回复超时");
                    updateStatus("超时");
                } else {
                    if (resp != null && !resp.isBlank()) {
                        appendBot(resp);
                    }
                    updateStatus(turn.stopSent.get() ? "已停止" : "就绪");
                }
            } finally {
                exitStopMode();
            }
        });
    }

    private void ui(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /**
     * 用单一入口刷新输入区状态，避免 stopMode / processing / 按钮文本分散改动。
     */
    private void applyTurnUiState(TurnPhase phase) {
        ui(() -> {
            boolean stop = phase == TurnPhase.RUNNING || phase == TurnPhase.STOPPING;

            stopMode.set(stop);

            if (stop) {
                sendButton.setText("■");
                sendButton.setPreferredSize(new Dimension(40, 40));
                sendButton.setMinimumSize(new Dimension(40, 40));
                sendButton.setMaximumSize(new Dimension(40, 40));
                sendButton.setEnabled(true);

                inputArea.setEnabled(false);
                inputArea.setEditable(false);

                if (clearButton != null) clearButton.setEnabled(false);
                if (onboardButton != null) onboardButton.setEnabled(false);
                if (statusButton != null) statusButton.setEnabled(false);
                if (gatewayButton != null) gatewayButton.setEnabled(false);
            } else {
                boolean chatEnabled = guiChatAvailable.get();

                sendButton.setText("发送");
                sendButton.setPreferredSize(new Dimension(72, 40));
                sendButton.setMinimumSize(new Dimension(72, 40));
                sendButton.setMaximumSize(new Dimension(72, 40));
                sendButton.setEnabled(chatEnabled);

                inputArea.setEnabled(chatEnabled);
                inputArea.setEditable(chatEnabled);

                if (clearButton != null) clearButton.setEnabled(true);
                if (onboardButton != null) onboardButton.setEnabled(true);
                if (statusButton != null) statusButton.setEnabled(true);
                if (gatewayButton != null) gatewayButton.setEnabled(true);

                if (chatEnabled) {
                    inputArea.requestFocusInWindow();
                }
            }

            sendButton.revalidate();
            sendButton.repaint();
        });
    }

    /**
     * 兼容不同版本 OutboundMessage：
     * - 若能读到 channel/chatId，则只消费 cli:direct
     * - 若读不到 getter，则保守放行，避免和旧类签名不兼容
     */
    private boolean isTargetCliOutbound(OutboundMessage out) {
        String channel = reflectString(out, "getChannel");
        String chatId = reflectString(out, "getChatId");

        if (channel == null && chatId == null) {
            return true;
        }

        boolean channelOk = channel == null || cliChannel.equals(channel);
        boolean chatOk = chatId == null || cliChatId.equals(chatId);
        return channelOk && chatOk;
    }

    private String reflectString(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            Object value = m.invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
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
        if (enabled) {
            applyTurnUiState(TurnPhase.IDLE);
        } else {
            // 外部调用禁用输入时，不改变当前 turnPhase，仅按当前阶段刷新 UI。
            applyTurnUiState(turnPhase.get() == TurnPhase.IDLE ? TurnPhase.RUNNING : turnPhase.get());
        }
    }

    private void appendUser(String message) {
        addBubble(BubbleType.USER, "你", message);
    }

    private void appendBot(String message) {
        addBubble(BubbleType.BOT, "🐱 javaclawbot", message);
    }

    private void appendSystem(String message) {
        addBubble(BubbleType.SYSTEM, "系统", message);
    }

    private void appendProgress(String message) {
        addBubble(BubbleType.PROGRESS, null, "↳ " + message);
    }

    /**
     * 加载历史消息
     */
    private void loadHistoryMessages() {
        if (sessionManager == null) {
            return;
        }

        try {
            // 获取 cli:direct 会话
            session.Session session = sessionManager.getOrCreate(cliChannel + ":" + cliChatId);
            java.util.List<Map<String, Object>> history = session.getHistory();

            if (history == null || history.isEmpty()) {
                return;
            }

            // 在 Swing 线程中渲染
            SwingUtilities.invokeLater(() -> {
                for (Map<String, Object> msg : history) {
                    String role = (String) msg.get("role");
                    Object contentObj = msg.get("content");
                    String content = extractContent(contentObj);

                    if (content == null || content.isBlank()) {
                        continue;
                    }

                    // 根据角色渲染气泡
                    if ("user".equals(role)) {
                        addBubble(BubbleType.USER, "你", content);
                    } else if ("assistant".equals(role)) {
                        addBubble(BubbleType.BOT, "🐱 javaclawbot", content);
                    }
                    // 跳过 system 消息
                }

                // 滚动到底部
                scrollToBottom();
            });

        } catch (Exception e) {
            appendSystem("加载历史消息失败: " + e.getMessage());
        }
    }

    /**
     * 从消息内容中提取文本
     */
    private String extractContent(Object contentObj) {
        if (contentObj == null) {
            return null;
        }

        if (contentObj instanceof String s) {
            return s;
        }

        if (contentObj instanceof java.util.List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        Object text = map.get("text");
                        if (text instanceof String s) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(s);
                        }
                    }
                    // 跳过 image_url 等其他类型
                }
            }
            return sb.toString();
        }

        return String.valueOf(contentObj);
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
        sb.append("🐱 javaclawbot 状态\n\n");
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
        ta.setFont(UiFonts.body());
        ta.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(580, 360));

        JOptionPane.showMessageDialog(this, sp, "状态", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * 显示 MCP 工具列表弹窗
     */
    private void showMcpToolsDialog() {
        McpToolsDialog dialog = new McpToolsDialog(this, agentLoop, config);
        dialog.setVisible(true);

        // 弹窗关闭后刷新配置
        try {
            this.config = ConfigIO.loadConfig(null);
            refreshModelLabel();
        } catch (Exception e) {
            appendSystem("刷新配置失败: " + e.getMessage());
        }
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
                + "🐱 javaclawbot - AI Assistant\n\n"
                + "版本: 1.0\n"
                + "UI: FlatLaf / FlatMacLightLaf\n"
                + "聊天布局: 左右气泡 + Markdown 展示\n"
                + "附加功能: GUI Onboard 按钮\n\n"
                + "基于 Java Swing 构建";

        JOptionPane.showMessageDialog(this, about, "关于", JOptionPane.INFORMATION_MESSAGE);
    }

    private void shutdown() {
        running.set(false);

        if (gatewayRunning.get() && gatewayRuntime != null) {
            try {
                gatewayRuntime.stop().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
            gatewayRunning.set(false);
            gatewayRuntime = null;
        }

        stopGuiDirectMode(true);

        try {
            guiAgentExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    // =========================
    // Gateway Mode Methods
    // =========================

    /**
     * 手动启动 Gateway。
     */
    private void startGateway() {
        startGateway(false, true);
    }

    /**
     * 启动 Gateway。
     *
     * @param autoStart   是否为 GUI 启动时自动拉起
     * @param needConfirm 是否弹出确认框
     */
    private void startGateway(boolean autoStart, boolean needConfirm) {
        GatewayPhase currentPhase = gatewayPhase.get();
        if (currentPhase != GatewayPhase.IDLE) {
            appendSystem(currentPhase == GatewayPhase.STARTING ? "Gateway 正在启动中" : "Gateway 已在运行或停止流程中");
            return;
        }

        try {
            RuntimeComponents rt = createRuntimeComponents();
            this.config = rt.getConfig();
            refreshModelLabel();
        } catch (Exception e) {
            appendSystem("读取配置失败: " + e.getMessage());
            startGuiDirectMode();
            return;
        }

        if (config == null) {
            appendSystem("错误: 配置未初始化");
            startGuiDirectMode();
            return;
        }

        StringBuilder channelInfo = new StringBuilder();
        boolean hasEnabledChannels = false;

        var channelsConfig = config.getChannels();
        if (channelsConfig != null) {
            if (channelsConfig.getDiscord() != null && channelsConfig.getDiscord().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• Discord: 已启用");
            }
            if (channelsConfig.getTelegram() != null && channelsConfig.getTelegram().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• Telegram: 已启用");
            }
            if (channelsConfig.getWhatsapp() != null && channelsConfig.getWhatsapp().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• WhatsApp: 已启用");
            }
            if (channelsConfig.getFeishu() != null && channelsConfig.getFeishu().isEnabled()) {
                hasEnabledChannels = true;
                channelInfo.append("• 飞书: 已启用");
            }
        }

        if (needConfirm) {
            String message;
            String title;
            if (hasEnabledChannels) {
                message = "检测到以下频道已配置并启用:"
                        + channelInfo
                        + "启动 Gateway 后将连接这些频道，并暂停 GUI 直连聊天。是否确认启动 Gateway?";
                title = "确认启动 Gateway";
            } else {
                message = "未检测到已启用的频道配置。请确认 channel 配置是否正确。是否仍要启动 Gateway?";
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
        }

        appendSystem(autoStart ? "检测到 gatewayMode=true，正在自动后台启动 Gateway..." : "正在切换到 Gateway 后台模式...");
        updateStatus("启动 Gateway...");
        gatewayPhase.set(GatewayPhase.STARTING);
        gatewayRunning.set(false);
        guiChatAvailable.set(false);
        applyGatewayUiMode(GatewayPhase.STARTING);
        applyTurnUiState(TurnPhase.IDLE);
        if (gatewayButton != null) {
            gatewayButton.setText("取消启动");
            gatewayButton.setEnabled(true);
        }

        CompletableFuture.runAsync(() -> {
            try {
                stopGuiDirectMode(true);

                GatewayRuntime runtime = new GatewayRuntime();
                gatewayRuntime = runtime;

                runtime.start().whenComplete((v, ex) -> {
                    if (ex == null) {
                        gatewayRunning.set(true);
                        gatewayPhase.set(GatewayPhase.RUNNING);
                        ui(() -> {
                            applyGatewayUiMode(GatewayPhase.RUNNING);
                            applyTurnUiState(TurnPhase.IDLE);
                            appendSystem("✓ Gateway 已启动");
                            appendSystem("GUI 输入区已隐藏/禁用；后续消息将由 Gateway 在后台与渠道通信");
                            updateStatus("Gateway 运行中");
                            if (gatewayButton != null) {
                                gatewayButton.setText("停止 Gateway");
                                gatewayButton.setEnabled(true);
                            }
                        });
                    } else {
                        gatewayRunning.set(false);
                        gatewayRuntime = null;
                        gatewayPhase.set(GatewayPhase.IDLE);
                        ui(() -> {
                            appendSystem("Gateway 启动失败: " + rootMessage(ex));
                            updateStatus("Gateway 启动失败");
                            applyGatewayUiMode(GatewayPhase.IDLE);
                            if (gatewayButton != null) {
                                gatewayButton.setText("启动 Gateway");
                                gatewayButton.setEnabled(true);
                            }
                        });
                        startGuiDirectMode();
                    }
                });
            } catch (Exception e) {
                gatewayRunning.set(false);
                gatewayRuntime = null;
                gatewayPhase.set(GatewayPhase.IDLE);

                ui(() -> {
                    appendSystem("Gateway 启动失败: " + e.getMessage());
                    updateStatus("Gateway 启动失败");
                    applyGatewayUiMode(GatewayPhase.IDLE);
                    if (gatewayButton != null) {
                        gatewayButton.setText("启动 Gateway");
                        gatewayButton.setEnabled(true);
                    }
                });

                startGuiDirectMode();
            }
        }, guiAgentExecutor);
    }

    /**
     * 停止 Gateway，并恢复到 GUI 直连 Agent 模式。
     */
    private void stopGateway() {
        GatewayPhase phase = gatewayPhase.get();
        if (phase == GatewayPhase.IDLE) {
            appendSystem("Gateway 未在运行");
            startGuiDirectMode();
            return;
        }

        boolean cancellingStartup = phase == GatewayPhase.STARTING;
        appendSystem(cancellingStartup ? "正在取消 Gateway 启动，并恢复到 Agent 直连模式..." : "正在停止 Gateway，并恢复到 Agent 直连模式...");
        updateStatus(cancellingStartup ? "取消 Gateway 启动..." : "停止 Gateway...");
        gatewayPhase.set(GatewayPhase.STOPPING);
        applyGatewayUiMode(GatewayPhase.STOPPING);
        if (gatewayButton != null) {
            gatewayButton.setText("停止中...");
            gatewayButton.setEnabled(false);
        }

        CompletableFuture.runAsync(() -> {
            try {
                GatewayRuntime localRuntime = gatewayRuntime;
                if (localRuntime != null) {
                    try {
                        localRuntime.stop().toCompletableFuture().get(18, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        appendSystem("Gateway 停止超时，先恢复 GUI 直连；后台清理继续进行");
                    }
                    gatewayRuntime = null;
                }

                gatewayRunning.set(false);
                gatewayPhase.set(GatewayPhase.IDLE);
                startGuiDirectMode();

                ui(() -> {
                    appendSystem(cancellingStartup ? "✓ Gateway 启动已取消" : "✓ Gateway 已停止");
                    appendSystem("已恢复到 Agent 直连模式");
                    applyGatewayUiMode(GatewayPhase.IDLE);
                    updateStatus("就绪");
                    if (gatewayButton != null) {
                        gatewayButton.setText("启动 Gateway");
                        gatewayButton.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                gatewayRunning.set(false);
                gatewayPhase.set(GatewayPhase.IDLE);
                gatewayRuntime = null;
                startGuiDirectMode();
                ui(() -> {
                    appendSystem("Gateway 停止阶段出现异常，已强制恢复 GUI 直连: " + rootMessage(e));
                    updateStatus("就绪");
                    applyGatewayUiMode(GatewayPhase.IDLE);
                    if (gatewayButton != null) {
                        gatewayButton.setText("启动 Gateway");
                        gatewayButton.setEnabled(true);
                    }
                });
            }
        }, guiAgentExecutor);
    }

    private String rootMessage(Throwable ex) {
        if (ex == null) return "";
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    /**
     * Toggle gateway mode
     */
    private void toggleGateway() {
        GatewayPhase phase = gatewayPhase.get();
        if (phase == GatewayPhase.IDLE) {
            startGateway();
        } else {
            stopGateway();
        }
    }

    static LLMProvider makeProvider(Config config) {
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
        btn.setFont(UiFonts.bodyBold());
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        btn.setPreferredSize(new Dimension(width, height));
        btn.setFocusable(false);
    }

    private void styleSecondaryButton(JButton btn, int width, int height) {
        btn.setFont(UiFonts.body());
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
                Config cfg = ConfigIO.loadConfig(null);
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
                senderLabel.setFont(UiFonts.captionBold());
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
            htmlPane.setFont(type == BubbleType.BOT || type == BubbleType.USER ? UiFonts.body() : UiFonts.small());
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
            timeLabel.setFont(UiFonts.caption());
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
                Pattern.compile("(?s)```([a-zA-Z0-9_+-]*)\n(.*?)\n```");

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
            String bodyFontSize = (type == BubbleType.BOT || type == BubbleType.USER) ? "13" : "12";

            return "<html><head>"
                    + "<style>"
                    + "body { font-family: " + UiFonts.htmlFontFamily() + "; color:" + textColor + "; font-size:" + bodyFontSize + "px; margin:0; padding:0; }"
                    + "h1 { font-size:18px; margin:8px 0 10px 0; }"
                    + "h2 { font-size:16px; margin:8px 0 8px 0; }"
                    + "h3 { font-size:14px; margin:6px 0 8px 0; }"
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

            Font font = UiFonts.body();
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