package gui.ui;

import gui.ui.components.Sidebar;
import gui.ui.components.ToolCallCard;
import gui.ui.pages.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MainStage {

    private static final double DEFAULT_WIDTH = 1280;
    private static final double DEFAULT_HEIGHT = 800;
    private static final double MIN_WIDTH = 960;
    private static final double MIN_HEIGHT = 600;

    private final Stage stage;
    private final BorderPane root;
    private final StackPane contentStack;
    private final Map<String, javafx.scene.Node> pages = new HashMap<>();

    private BackendBridge backendBridge;
    private Sidebar sidebar;
    private ChatPage chatPage;
    private ToolCallCard lastToolCard;

    public MainStage(Stage stage) {
        this.stage = stage;
        this.root = new BorderPane();
        this.contentStack = new StackPane();

        configureStage();
        loadStylesheets();
        setupPages();
        setupSidebar();
        initializeBackend();
    }

    private void configureStage() {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setWidth(DEFAULT_WIDTH);
        stage.setHeight(DEFAULT_HEIGHT);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        // 窗口圆角（参考消息气泡 16px 圆角）
        root.setStyle("-fx-background-radius: 20px; -fx-background-color: #f1ede1;");

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        stage.setScene(scene);

        // 裁剪窗口为圆角矩形
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setWidth(DEFAULT_WIDTH);
        clip.setHeight(DEFAULT_HEIGHT);
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        root.setClip(clip);

        // 窗口大小变化时更新裁剪
        scene.widthProperty().addListener((obs, o, w) -> {
            clip.setWidth(w.doubleValue());
        });
        scene.heightProperty().addListener((obs, o, h) -> {
            clip.setHeight(h.doubleValue());
        });

        stage.setOnCloseRequest(e -> {
            if (backendBridge != null) backendBridge.shutdown();
        });
    }

    private void loadStylesheets() {
        String mainCss = getClass().getResource("/gui/ui/styles/main.css").toExternalForm();
        stage.getScene().getStylesheets().add(mainCss);
    }

    private void setupPages() {
        // 创建所有页面
        chatPage = new ChatPage();
        pages.put("chat", chatPage);
        pages.put("models", new ModelsPage());
        pages.put("agents", new AgentsPage());
        pages.put("channels", new ChannelsPage());
        pages.put("skills", new SkillsPage());
        pages.put("mcp", new McpPage(stage));
        pages.put("crontasks", new CronPage());
        pages.put("settings", new SettingsPage());

        // 添加到 StackPane
        for (javafx.scene.Node page : pages.values()) {
            contentStack.getChildren().add(page);
            page.setVisible(false);
            page.setManaged(false);
        }

        // 默认显示 Chat 页面
        showPage("chat");

        root.setCenter(contentStack);
    }

    private void setupSidebar() {
        sidebar = new Sidebar();
        // 窗口拖拽支持（TRANSPARENT 无原生标题栏，从 sidebar 顶部拖动）
        sidebar.setWindowDragHandler(stage);
        sidebar.addPageChangeListener(page -> {
            showPage(page);
            if ("chat".equalsIgnoreCase(page.replace(" ", "")) && backendBridge != null) {
                CompletableFuture.runAsync(() -> {
                    List<Map<String, Object>> sessions = backendBridge.getSessionManager().listSessions();
                    Platform.runLater(() -> {
                        sidebar.refreshHistory(sessions);
                        // 最近会话是今天则恢复，否则新会话
                        if (!sessions.isEmpty()) {
                            Map<String, Object> recent = sessions.get(0);
                            if (isToday(recent.get("updated_at"))) {
                                String sid = (String) recent.get("session_id");
                                if (sid != null && !sid.isBlank()) {
                                    backendBridge.resumeSession(sid);
                                    List<Map<String, Object>> history = backendBridge.getSessionHistory(sid);
                                    chatPage.loadMessages(history);
                                    return;
                                }
                            }
                        }
                        // 无今日会话，开启新会话
                        backendBridge.resetTitleCounter();
                        backendBridge.newSession();
                        chatPage.clearMessages();
                        sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                    });
                });
            }
        });
        sidebar.addNewChatListener(() -> {
            if (backendBridge != null) {
                backendBridge.resetTitleCounter();
                CompletableFuture.runAsync(() -> backendBridge.newSession())
                    .thenRun(() -> Platform.runLater(() -> {
                        chatPage.clearMessages();
                        sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                    }));
            }
        });
        sidebar.addResumeListener(sessionId -> {
            if (backendBridge != null) {
                CompletableFuture.runAsync(() -> {
                    List<Map<String, Object>> history = backendBridge.getSessionHistory(sessionId);
                    Platform.runLater(() -> {
                        chatPage.loadMessages(history);
                        showPage("chat");
                    });
                });
            }
        });
        sidebar.addDeleteListener(sessionId -> {
            if (backendBridge != null) {
                CompletableFuture.runAsync(() -> {
                    backendBridge.deleteSession(sessionId);
                    Platform.runLater(() ->
                        sidebar.refreshHistory(backendBridge.getSessionManager().listSessions()));
                });
            }
        });
        root.setLeft(sidebar);
    }

    private void showPage(String pageName) {
        // 标准化页面名称
        String normalized = pageName.toLowerCase().replace(" ", "");

        for (Map.Entry<String, javafx.scene.Node> entry : pages.entrySet()) {
            boolean visible = entry.getKey().equals(normalized);
            entry.getValue().setVisible(visible);
            entry.getValue().setManaged(visible);
        }
    }

    public void show() {
        stage.show();
    }

    public BorderPane getRoot() {
        return root;
    }

    public Stage getStage() {
        return stage;
    }

    private static String extractToolName(String toolHint) {
        if (toolHint == null || toolHint.isBlank()) return "tool";
        int paren = toolHint.indexOf('(');
        if (paren > 0) return toolHint.substring(0, paren).trim();
        return toolHint.trim();
    }

    private void injectBridgeToPage(Object page) {
        if (page instanceof ModelsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof AgentsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof ChannelsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof SkillsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof McpPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof CronPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof SettingsPage p) {
            p.setBackendBridge(backendBridge);
            p.setOnModelChanged(model -> {
                // 热刷新 provider 和 AgentLoop，使模型变更即时生效
                backendBridge.refreshProvider();
                chatPage.setStatusText("\u25CF 模型就绪 \u00B7 " + model);
            });
        }
    }

    private void initializeBackend() {
        backendBridge = new BackendBridge();
        new Thread(() -> {
            try {
                backendBridge.initialize();
                Platform.runLater(() -> {
                    // Wire ChatInput send listener to backend
                    chatPage.getChatInput().addSendListener(text -> {
                        if (backendBridge.isWaitingForResponse()) return;
                        java.util.List<String> images = chatPage.getChatInput().getAttachedImages();
                        // 收集图片路径用于聊天区预览展示
                        java.util.List<java.nio.file.Path> imagePaths = new java.util.ArrayList<>();
                        if (images != null) {
                            for (String p : images) {
                                imagePaths.add(java.nio.file.Path.of(p));
                            }
                        }
                        chatPage.addUserMessage(text, imagePaths);
                        chatPage.setStatusText("\u25CF 思考中...");
                        backendBridge.sendMessage(
                            text,
                            images.isEmpty() ? null : images,
                            progress -> {
                                if (progress.isToolResult()) {
                                    // 工具结果：收起到最近一个对应的工具调用卡片中
                                    if (lastToolCard != null) {
                                        lastToolCard.addResult(progress.content());
                                    }
                                } else if (progress.isToolHint()) {
                                    // 工具调用：创建展开的卡片
                                    String toolName = progress.toolName() != null
                                        ? progress.toolName()
                                        : extractToolName(progress.content());
                                    ToolCallCard card = chatPage.addToolCallCard(
                                        toolName, "running", progress.content(), true);
                                    lastToolCard = card;
                                }
                                // 思考内容静默跳过（会在最终回复中体现）
                            },
                            response -> {
                                chatPage.addAssistantMessage(response);
                                chatPage.setStatusText("\u25CF 模型就绪 \u00B7 "
                                    + backendBridge.getConfig().getAgents().getDefaults().getModel());
                                sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                            },
                            error -> {
                                chatPage.addAssistantMessage("\u26A0 " + error);
                                chatPage.setStatusText("\u25CF 错误");
                            }
                        );
                    });

                    // Set workspace and project dir for @file completion
                    chatPage.getChatInput().setWorkspacePath(
                        backendBridge.getConfig().getWorkspacePath());
                    chatPage.getChatInput().setProjectPath(backendBridge.getProjectDir());

                    // Initial status
                    chatPage.setStatusText("\u25CF 模型就绪 \u00B7 "
                        + backendBridge.getConfig().getAgents().getDefaults().getModel());

                    // Load history
                    sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());

                    // Inject BackendBridge into management pages
                    injectBridgeToPage(pages.get("models"));
                    injectBridgeToPage(pages.get("agents"));
                    injectBridgeToPage(pages.get("channels"));
                    injectBridgeToPage(pages.get("skills"));
                    injectBridgeToPage(pages.get("mcp"));
                    injectBridgeToPage(pages.get("crontasks"));
                    injectBridgeToPage(pages.get("settings"));
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                    chatPage.setStatusText("\u25CF 初始化失败: " + e.getMessage()));
            }
        }, "javaclawbot-fx-init").start();
    }

    /** 判断 updated_at 是否为今天 */
    private static boolean isToday(Object updatedAt) {
        if (!(updatedAt instanceof String s)) return false;
        try {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(s);
            return dt.toLocalDate().equals(java.time.LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }
}
