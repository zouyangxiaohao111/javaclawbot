package gui.ui;

import com.google.gson.Gson;
import gui.ui.components.AskQuestionResultView;
import gui.ui.components.QuestionDialog;
import gui.ui.components.Sidebar;
import gui.ui.components.TodoResultView;
import gui.ui.components.ToolCallCard;
import gui.ui.pages.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.InputStream;
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
    /** 用于"新对话"按钮触发时阻止 pageChangeListener 恢复历史会话 */
    private volatile boolean suppressPageResume = false;

    /** 窗口边缘拖拽缩放（6px 热区覆盖边缘+四角） */
    private static final double RESIZE_BORDER = 6;
    private boolean resizing = false;
    private double resizeStartX, resizeStartY;
    private double stageStartX, stageStartY, stageStartW, stageStartH;
    private boolean resizeL, resizeR, resizeT, resizeB;

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

        // 设置窗口图标（任务栏/Dock/Alt+Tab 显示）
        try {
            InputStream icon16 = getClass().getResourceAsStream("/asset/icon/icon-16.png");
            InputStream icon32 = getClass().getResourceAsStream("/asset/icon/icon-32.png");
            InputStream icon64 = getClass().getResourceAsStream("/asset/icon/icon-64.png");
            InputStream icon128 = getClass().getResourceAsStream("/asset/icon/icon-128.png");
            InputStream icon256 = getClass().getResourceAsStream("/asset/icon/icon-256.png");
            if (icon16 != null && icon32 != null && icon64 != null
                && icon128 != null && icon256 != null) {
                stage.getIcons().addAll(
                    new Image(icon16),
                    new Image(icon32),
                    new Image(icon64),
                    new Image(icon128),
                    new Image(icon256)
                );
            }
        } catch (Exception ignored) {
            // 图标加载失败不影响程序运行
        }

        stage.setWidth(DEFAULT_WIDTH);
        stage.setHeight(DEFAULT_HEIGHT);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        root.setStyle("-fx-background-radius: 20px; -fx-background-color: #f1ede1;");

        // 顶部色条：匹配 sidebar 颜色，放置控件 + 支持 resize 热区 + 窗口拖拽平移
        HBox topBar = new HBox();
        topBar.setPrefHeight(28);
        topBar.setMinHeight(28);
        topBar.setMaxHeight(28);
        topBar.setStyle("-fx-background-color: rgba(234, 232, 225, 0.6);");
        topBar.setAlignment(Pos.CENTER_RIGHT);
        topBar.setPadding(new Insets(0, 4, 0, 0));
        topBar.getChildren().add(createWindowControls());
        root.setTop(topBar);
        // 顶部色条拖拽平移窗口
        installDragHandlers(topBar);

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);

        // 圆角裁剪
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        root.setClip(clip);

        // 边缘/四角拖拽缩放
        installResizeHandlers(scene);

        stage.setOnCloseRequest(e -> {
            if (backendBridge != null) {
                backendBridge.stopAllLoops();
            }
            Platform.exit();
            System.exit(0);
        });
    }

    /** 顶部色条鼠标拖拽平移窗口 */
    private void installDragHandlers(HBox topBar) {
        final double[] dragOffset = new double[2];
        topBar.setOnMousePressed(e -> {
            if (e.getY() > RESIZE_BORDER) { // 非 resize 热区才拖拽
                dragOffset[0] = e.getScreenX() - stage.getX();
                dragOffset[1] = e.getScreenY() - stage.getY();
            }
        });
        topBar.setOnMouseDragged(e -> {
            if (e.getY() > RESIZE_BORDER) {
                stage.setX(e.getScreenX() - dragOffset[0]);
                stage.setY(e.getScreenY() - dragOffset[1]);
            }
        });
    }

    /** 窗口控件：最小化 / 最大化 / 关闭 */
    private HBox createWindowControls() {
        HBox controls = new HBox(6);
        controls.setAlignment(Pos.CENTER);

        // 最小化
        SVGPath minSvg = new SVGPath();
        minSvg.setContent("M3 11 L13 11");
        minSvg.setStyle("-fx-stroke: rgba(0,0,0,0.4); -fx-stroke-width: 1.5px;"
            + " -fx-stroke-line-cap: round;");
        Label minBtn = createWinButton(minSvg);
        minBtn.setOnMouseClicked(e -> stage.setIconified(true));

        // 最大化
        SVGPath maxSvg = new SVGPath();
        maxSvg.setContent("M4 4 L12 4 L12 12 L4 12 Z");
        maxSvg.setStyle("-fx-stroke: rgba(0,0,0,0.4); -fx-stroke-width: 1.5px;"
            + " -fx-fill: transparent; -fx-stroke-line-join: round;");
        Label maxBtn = createWinButton(maxSvg);
        maxBtn.setOnMouseClicked(e -> stage.setMaximized(!stage.isMaximized()));

        // 关闭
        SVGPath closeSvg = new SVGPath();
        closeSvg.setContent("M4 4 L12 12 M12 4 L4 12");
        closeSvg.setStyle("-fx-stroke: rgba(0,0,0,0.4); -fx-stroke-width: 1.5px;"
            + " -fx-stroke-line-cap: round;");
        Label closeBtn = createWinButton(closeSvg);
        // 关闭按钮 hover 变红
        closeBtn.setOnMouseEntered(e -> closeSvg.setStyle(
            "-fx-stroke: #ef4444; -fx-stroke-width: 1.5px; -fx-stroke-line-cap: round;"));
        closeBtn.setOnMouseExited(e -> closeSvg.setStyle(
            "-fx-stroke: rgba(0,0,0,0.4); -fx-stroke-width: 1.5px; -fx-stroke-line-cap: round;"));
        closeBtn.setOnMouseClicked(e -> {
            if (backendBridge != null) backendBridge.stopAllLoops();
            Platform.exit();
            System.exit(0);
        });

        controls.getChildren().addAll(minBtn, maxBtn, closeBtn);
        return controls;
    }

    private Label createWinButton(SVGPath svg) {
        StackPane icon = new StackPane(svg);
        icon.setPrefSize(16, 16);
        icon.setAlignment(Pos.CENTER);
        Label btn = new Label();
        btn.setGraphic(icon);
        btn.setStyle("-fx-background-color: transparent; -fx-padding: 4px; -fx-cursor: hand;");
        return btn;
    }

    /** 边缘/四角拖拽缩放：scene 事件捕获阶段统一检测方向 */
    private void installResizeHandlers(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (resizing) return;
            double x = e.getSceneX();
            double y = e.getSceneY();
            double w = scene.getWidth();
            double h = scene.getHeight();
            boolean L = x < RESIZE_BORDER;
            boolean R = x > w - RESIZE_BORDER;
            boolean T = y < RESIZE_BORDER;
            boolean B = y > h - RESIZE_BORDER;
            if (T && L) scene.setCursor(Cursor.NW_RESIZE);
            else if (T && R) scene.setCursor(Cursor.NE_RESIZE);
            else if (B && L) scene.setCursor(Cursor.SW_RESIZE);
            else if (B && R) scene.setCursor(Cursor.SE_RESIZE);
            else if (L) scene.setCursor(Cursor.W_RESIZE);
            else if (R) scene.setCursor(Cursor.E_RESIZE);
            else if (B) scene.setCursor(Cursor.S_RESIZE);
            else if (T) scene.setCursor(Cursor.N_RESIZE);
            else if (!resizing) scene.setCursor(Cursor.DEFAULT);
        });

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            double x = e.getSceneX();
            double y = e.getSceneY();
            double w = scene.getWidth();
            double h = scene.getHeight();
            resizeL = x < RESIZE_BORDER;
            resizeR = x > w - RESIZE_BORDER;
            resizeT = y < RESIZE_BORDER;
            resizeB = y > h - RESIZE_BORDER;
            if (resizeL || resizeR || resizeT || resizeB) {
                resizing = true;
                resizeStartX = e.getScreenX();
                resizeStartY = e.getScreenY();
                stageStartX = stage.getX();
                stageStartY = stage.getY();
                stageStartW = stage.getWidth();
                stageStartH = stage.getHeight();
                e.consume();
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!resizing) return;
            double dx = e.getScreenX() - resizeStartX;
            double dy = e.getScreenY() - resizeStartY;
            if (resizeL) {
                double newW = stageStartW - dx;
                if (newW >= MIN_WIDTH) {
                    stage.setX(stageStartX + dx);
                    stage.setWidth(newW);
                }
            } else if (resizeR) {
                double newW = stageStartW + dx;
                if (newW >= MIN_WIDTH) stage.setWidth(newW);
            }
            if (resizeT) {
                double newH = stageStartH - dy;
                if (newH >= MIN_HEIGHT) {
                    stage.setY(stageStartY + dy);
                    stage.setHeight(newH);
                }
            } else if (resizeB) {
                double newH = stageStartH + dy;
                if (newH >= MIN_HEIGHT) stage.setHeight(newH);
            }
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizing) {
                resizing = false;
                scene.setCursor(Cursor.DEFAULT);
            }
        });
    }

    private void loadStylesheets() {
        String mainCss = getClass().getResource("/static/css/styles/main.css").toExternalForm();
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
        pages.put("devconsole", new DevConsolePage());
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
            // 非 Chat 页面：从磁盘重新加载配置，避免展示缓存值
            if (backendBridge != null && !"chat".equalsIgnoreCase(page.replace(" ", ""))) {
                backendBridge.reloadConfigFromDisk();
                // 触发对应页面刷新
                javafx.scene.Node pageNode = pages.get(page.replace(" ", "").toLowerCase());
                if (pageNode instanceof ModelsPage p) p.refresh();
                else if (pageNode instanceof AgentsPage p) p.refresh();
                else if (pageNode instanceof SettingsPage p) p.refresh();
            }
            if ("chat".equalsIgnoreCase(page.replace(" ", "")) && backendBridge != null) {
                CompletableFuture.runAsync(() -> {
                    List<Map<String, Object>> sessions = backendBridge.getSessionManager().listSessions();
                    Platform.runLater(() -> {
                        sidebar.refreshHistory(sessions);
                        // 如果"新对话"按钮触发了此事件，跳过恢复，交给 newChatListener 处理
                        if (suppressPageResume) {
                            suppressPageResume = false;
                            return;
                        }
                        // 点击 Chat 菜单：最近会话是今天则恢复，否则进入欢迎页
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
                        // 非今天或无历史会话 → 欢迎页流程
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
                suppressPageResume = true;
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
                    backendBridge.resumeSession(sessionId);
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

    private void handleToolResult(BackendBridge.ProgressEvent progress) {
        String tn = progress.toolName();
        String content = progress.content();
        if (content == null || content.isBlank()) return;

        if ("AskUserQuestion".equals(tn)) {
            if (content.contains("awaiting_response")) {
                showAskUserQuestionDialog(content, progress.toolCallId());
            } else if (content.contains("\"questions\"")) {
                if (lastToolCard != null) {
                    lastToolCard.setStatus("completed");
                    lastToolCard.addStructuredContent(AskQuestionResultView.build(content));
                }
            }
        } else if ("TodoWrite".equals(tn)) {
            chatPage.getTodoFloatBadge().updateFromJson(content);
            if (lastToolCard != null) {
                lastToolCard.setStatus("completed");
                lastToolCard.addStructuredContent(TodoResultView.build(content));
            }
        } else {
            if (lastToolCard != null) {
                lastToolCard.setStatus("completed");
                lastToolCard.addResult(content);
            }
        }
    }

    private void handleToolHint(BackendBridge.ProgressEvent progress) {
        String toolName = progress.toolName() != null
            ? progress.toolName()
            : extractToolName(progress.content());
        String params = progress.content();

        if ("TodoWrite".equals(toolName)) {
            params = "更新任务列表";
        }

        ToolCallCard card = chatPage.addToolCallCard(
            toolName, "running", params, false);
        lastToolCard = card;
    }

    private void showAskUserQuestionDialog(String json, String toolCallId) {
        try {
            Gson gson = new Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = gson.fromJson(json, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) root.get("questions");

            if (questions == null || questions.isEmpty()) return;

            QuestionDialog dialog = new QuestionDialog(questions);
            dialog.initOwner(stage.getScene() != null ? stage.getScene().getWindow() : null);
            dialog.showAndWait().ifPresent(answers -> {
                if (!answers.isEmpty() && toolCallId != null) {
                    backendBridge.answerUserQuestion(toolCallId, answers);
                }
            });
        } catch (Exception e) {
            // 解析失败则把原始 JSON 作为普通结果展示
            if (lastToolCard != null) {
                lastToolCard.addResult(json);
            }
        }
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
                    // Wire stop callback
                    chatPage.getChatInput().setOnStop(() -> {
                        backendBridge.stopMessage();
                        chatPage.setStatusText("\u25CF 已停止");
                        chatPage.getChatInput().setSending(false);
                    });

                    // 使 chatInput 可以访问历史消息记录
                    chatPage.getChatInput().setBackendBridge(backendBridge);

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
                        chatPage.getChatInput().setSending(true);
                        chatPage.addThinkingPlaceholder();
                        chatPage.setStatusText("\u25CF \u601D\u8003\u4E2D...");
                        java.util.List<String> allMedia = chatPage.getChatInput().getAllAttachmentPaths();
                        backendBridge.sendMessage(
                            text,
                            allMedia.isEmpty() ? null : allMedia,
                            progress -> {
                                if (progress.isToolResult()) {
                                    handleToolResult(progress);
                                } else if (progress.isToolHint()) {
                                    handleToolHint(progress);
                                } else if (progress.isReasoning()) {
                                    chatPage.addReasoningBlock(progress.content());
                                } else {
                                    chatPage.addAssistantMessage(progress.content());
                                }
                            },
                            response -> {
                                chatPage.removeThinkingPlaceholder();
                                chatPage.getChatInput().setSending(false);
                                // 推理+回复合并为一个视觉单元
                                String reasoning = backendBridge.getLastReasoningContent();
                                if (reasoning != null && !reasoning.isBlank()) {
                                    chatPage.addAssistantMessageWithReasoning(reasoning, response);
                                } else {
                                    chatPage.addAssistantMessage(response);
                                }
                                chatPage.setStatusText("\u25CF 模型就绪 \u00B7 "
                                    + backendBridge.getConfig().getAgents().getDefaults().getModel());
                                sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                            },
                            error -> {
                                chatPage.removeThinkingPlaceholder();
                                chatPage.getChatInput().setSending(false);
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

                    // Refresh sidebar history, ensure fresh session for welcome page
                    sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                    backendBridge.ensureFreshSession();

                    // 标题异步生成后自动刷新侧栏
                    backendBridge.setOnTitleChanged(() ->
                        sidebar.refreshHistory(backendBridge.getSessionManager().listSessions()));

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
