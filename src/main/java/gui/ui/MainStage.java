package gui.ui;

import com.google.gson.Gson;
import gui.ui.components.*;
import gui.ui.pages.*;
import gui.ui.pages.DatabasesPage;
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

import skills.SkillSyncService;
import skills.SkillDifference;
import gui.ui.dialogs.SkillSyncDialog;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MainStage {

    private static final double DEFAULT_WIDTH = 1100;
    private static final double DEFAULT_HEIGHT = 800;
    private static final double MIN_WIDTH = 480;
    private static final double MIN_HEIGHT = 600;

    private final Stage stage;
    private final BorderPane root;
    private final StackPane contentStack;
    private final Map<String, javafx.scene.Node> pages = new HashMap<>();

    private BackendBridge backendBridge;
    private Sidebar sidebar;
    private SessionTabManager tabManager;
    private ToolCallCard lastToolCard;
    /** 用于"新对话"按钮触发时阻止 pageChangeListener 恢复历史会话 */
    private volatile boolean suppressPageResume = false;
    /** 跟踪 edit_file/write_file 的参数 (toolCallId → file_path) */
    private final Map<String, String> fileEditParams = new HashMap<>();

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

    private SessionTabBar tabBar;

    /** 获取当前活跃标签的 ChatPage */
    private ChatPage getActiveChatPage() {
        return tabManager != null ? tabManager.getActiveChatPage() : null;
    }

    private void setupPages() {
        // 创建聊天区域（标签栏 + ChatPage 容器）
        javafx.scene.layout.VBox chatArea = new javafx.scene.layout.VBox();
        chatArea.setFillWidth(true);
        tabBar = new SessionTabBar();
        javafx.scene.layout.VBox.setVgrow(tabBar, javafx.scene.layout.Priority.NEVER);
        chatArea.getChildren().add(tabBar);

        pages.put("chat", chatArea);
        pages.put("models", new ModelsPage());
        pages.put("agents", new AgentsPage());
        pages.put("channels", new ChannelsPage());
        pages.put("skills", new SkillsPage());
        pages.put("mcp", new McpPage(stage));
        pages.put("databases", new DatabasesPage(stage));
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
                else if (pageNode instanceof SkillsPage p) p.refresh();
                else if (pageNode instanceof ChannelsPage p) p.refresh();
                else if (pageNode instanceof McpPage p) p.refresh();
                else if (pageNode instanceof DatabasesPage p) p.refresh();
                else if (pageNode instanceof CronPage p) p.refresh();
                else if (pageNode instanceof SettingsPage p) p.refresh();
            }
            if ("chat".equalsIgnoreCase(page.replace(" ", "")) && backendBridge != null) {
                CompletableFuture.runAsync(() -> {
                    List<Map<String, Object>> sessions = backendBridge.getSessionManager().listSessions();
                    Platform.runLater(() -> {
                        sidebar.refreshHistory(sessions);
                        // 多标签系统（v2.3.9+）：tabManager 接管所有会话管理
                        // 切换回对话页时只需刷新侧栏历史，不应覆盖活跃标签的内容
                        // ChatPage 通过 setVisible/setManaged 自动保持标签状态
                        if (tabManager != null) {
                            if (suppressPageResume) {
                                suppressPageResume = false;
                            }
                            // 如果当前无活跃标签（极端情况），创建默认标签
                            if (tabManager.getActiveTabId() == null) {
                                tabManager.createDefaultTab();
                            }
                            return;
                        }
                        // === 以下为旧版兼容逻辑（tabManager 未初始化时） ===
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
                                    getActiveChatPage().loadMessages(history);
                                    getActiveChatPage().setContextUsage(backendBridge.getContextUsageRatio());
                                    getActiveChatPage().refreshProjectBadge();
                                    return;
                                }
                            }
                        }
                        // 非今天或无历史会话 → 欢迎页流程
                        backendBridge.resetTitleCounter();
                        backendBridge.newSession();
                        getActiveChatPage().clearMessages();
                        getActiveChatPage().setContextUsage(backendBridge.getContextUsageRatio());
                        getActiveChatPage().refreshProjectBadge();
                        sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                        resetFileBadgeForNewSession();
                    });
                });
            }
        });
        sidebar.addNewChatListener(() -> {
            // 多标签系统（v2.3.9+）：tabManager 接管"新对话"逻辑
            // initializeBackend() 中已注册正确的 newChatListener → tabManager.createNewTab()
            // 旧版兼容逻辑仅在 tabManager 未初始化时执行
            if (tabManager != null) return;
            if (backendBridge != null) {
                suppressPageResume = true;
                backendBridge.resetTitleCounter();
                // 仅清空会话引用和 GUI，不创建新会话（懒创建）
                backendBridge.newSession();
                Platform.runLater(() -> {
                    getActiveChatPage().clearMessages();
                    getActiveChatPage().setContextUsage(backendBridge.getContextUsageRatio());
                    getActiveChatPage().refreshProjectBadge();
                    sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                    resetFileBadgeForNewSession();
                });
            }
        });
        sidebar.addResumeListener(sessionId -> {
            if (backendBridge != null) {
                CompletableFuture.runAsync(() -> {
                    backendBridge.resumeSession(sessionId);
                    // setBackupManager 必须在 loadMessages 之前设置，
                    // 否则历史工具卡片的 [查看对比]/[回滚] 按钮无法找到备份文件
                    agent.tool.file.FileBackupManager fbm = backendBridge.getFileBackupManager();
                    Platform.runLater(() -> {
                        if (fbm != null) {
                            getActiveChatPage().getFileDiffBadge().setBackupManager(fbm);
                            // loadFromBackupManager 要在 loadMessages 之后调用，
                            // 因为 loadMessages 内部的 clearMessages → clearFiles 会清掉刚加载的数据
                        }
                    });
                    List<Map<String, Object>> history = backendBridge.getSessionHistory(sessionId);
                    Platform.runLater(() -> {
                        getActiveChatPage().loadMessages(history);
                        // 在 loadMessages 清空后再重新加载备份数据到 fileDiffBadge
                        if (fbm != null) {
                            getActiveChatPage().getFileDiffBadge().loadFromBackupManager();
                        }
                        getActiveChatPage().setContextUsage(backendBridge.getContextUsageRatio());
                        getActiveChatPage().refreshProjectBadge();
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
        String tcId = progress.toolCallId();
        if (content == null || content.isBlank()) return;

        // 仅当结果为合法 JSON 且 status="awaiting_response" 时进入对话框流程，
        // 避免文件内容中包含 "awaiting_response" 字符串导致误触发（对齐 AgentLoop.isAwaitingResponse）
        if (isAwaitingResponse(content)) {
            showAskUserQuestionDialog(content, tcId);
        } else if ("AskUserQuestion".equals(tn)) {
            if (content.contains("\"questions\"")) {
                if (lastToolCard != null) {
                    lastToolCard.setStatus("completed");
                    lastToolCard.addStructuredContent(AskQuestionResultView.build(content));
                }
            } else if (lastToolCard != null) {
                lastToolCard.setStatus("completed");
                lastToolCard.addResult(content);
            }
        } else if ("TodoWrite".equals(tn)) {
            getActiveChatPage().getFileDiffBadge().updateTodoFromJson(content);
            if (lastToolCard != null) {
                lastToolCard.setStatus("completed");
                lastToolCard.addStructuredContent(TodoResultView.build(content));
            }
        } else if ("edit_file".equals(tn) || "write_file".equals(tn)) {
            // Structured file-change summary
            String filePath = tcId != null ? fileEditParams.remove(tcId) : null;
            if (filePath == null) {
                filePath = extractFilePathFromResult(content);
            }
            if (lastToolCard != null) {
                lastToolCard.setStatus("completed");
                if (filePath != null && backendBridge != null) {
                    agent.tool.file.FileBackupManager fbm = backendBridge.getFileBackupManager();
                    int[] stats = parseDiffStats(content);
                    lastToolCard.setFileEditResult(filePath, stats[0], stats[1], fbm, null);
                    // Notify FileDiffBadge
                    if (fbm != null) {
                        try {
                            java.nio.file.Path p = java.nio.file.Path.of(filePath);
                            java.util.List<agent.tool.file.FileBackupManager.BackupEntry> vers = fbm.getVersions(p);
                            if (!vers.isEmpty()) {
                                getActiveChatPage().getFileDiffBadge().addModifiedFile(p, vers.get(vers.size() - 1));
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    lastToolCard.addResult(content);
                }
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

        // Store file_path for edit_file/write_file cards
        if (("edit_file".equals(toolName) || "write_file".equals(toolName))
                && progress.toolCallId() != null) {
            String filePath = extractFilePathFromParams(params);
            if (filePath != null) {
                fileEditParams.put(progress.toolCallId(), filePath);
            }
            // Show friendly params
            if (filePath != null) {
                params = java.nio.file.Path.of(filePath).getFileName().toString();
            }
        }

        ToolCallCard card = getActiveChatPage().addToolCallCard(
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

            dialog.showAndWait().ifPresentOrElse(answers -> {
                if (!answers.isEmpty() && toolCallId != null) {
                    backendBridge.answerUserQuestion(toolCallId, answers);
                }
            }, () -> {
                // 用户取消/关闭对话框 — 注入空答案解除 AgentLoop 阻塞
                if (toolCallId != null) {
                    backendBridge.answerUserQuestion(toolCallId, java.util.Map.of());
                }
            });
        } catch (Exception e) {
            // 解析失败则把原始 JSON 作为普通结果展示，并标记完成
            if (lastToolCard != null) {
                lastToolCard.setStatus("completed");
                lastToolCard.addResult(json);
            }
        }
    }

    /**
     * 检查工具结果是否为合法的 awaiting_response 格式。
     * 要求结果为合法 JSON 且 status 字段为 "awaiting_response" 且包含 questions 字段，
     * 避免文件内容中包含 "awaiting_response" 字符串导致误触发（对齐 AgentLoop.isAwaitingResponse）。
     */
    private static boolean isAwaitingResponse(String rawResult) {
        try {
            Map<String, Object> map = new Gson().fromJson(rawResult, Map.class);
            return "awaiting_response".equals(map.get("status")) && map.containsKey("questions");
        } catch (Exception e) {
            return false;
        }
    }

    private void injectBridgeToPage(Object page) {
        if (page instanceof ModelsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof AgentsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof ChannelsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof SkillsPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof McpPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof DatabasesPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof CronPage p) p.setBackendBridge(backendBridge);
        else if (page instanceof SettingsPage p) {
            p.setBackendBridge(backendBridge);
            p.setOnModelChanged(model -> {
                // 热刷新 provider 和 AgentLoop，使模型变更即时生效
                backendBridge.refreshProvider();
                getActiveChatPage().setStatusText("\u25CF 模型就绪 \u00B7 " + model);
                if (backendBridge != null) {
                    getActiveChatPage().setContextUsage(backendBridge.getContextUsageRatio());
                }
            });
        }
    }

    private void initializeBackend() {
        backendBridge = new BackendBridge();
        new Thread(() -> {
            try {
                backendBridge.initialize();

                // [新增] 异步检查技能同步
                CompletableFuture.runAsync(() -> {
                    try {
                        checkAndSyncSkills();
                    } catch (Exception e) {
                        System.err.println("技能同步检查失败: " + e.getMessage());
                    }
                });

                Platform.runLater(() -> {
                    // 创建标签管理器
                    javafx.scene.layout.VBox chatArea = (javafx.scene.layout.VBox) pages.get("chat");
                    tabManager = new SessionTabManager(tabBar, backendBridge, chatArea);
                    tabManager.createDefaultTab();

                    // Wire sidebar events to tab manager
                    sidebar.addNewChatListener(() -> {
                        if (tabManager != null) {
                            suppressPageResume = true;
                            tabManager.createNewTab();
                            sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                        }
                    });
                    sidebar.addResumeListener(sessionId -> {
                        if (tabManager != null) {
                            tabManager.switchToSession(sessionId);
                            showPage("chat");
                            sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                        }
                    });
                    sidebar.addDeleteListener(sessionId -> {
                        if (tabManager != null) {
                            tabManager.closeTabBySession(sessionId);
                            backendBridge.deleteSession(sessionId);
                            sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                        }
                    });

                    // Refresh sidebar history
                    sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());

                    // 标题异步生成后自动刷新侧栏和标签标题
                    backendBridge.setOnTitleChanged(() -> {
                        // 刷新侧栏历史列表
                        sidebar.refreshHistory(backendBridge.getSessionManager().listSessions());
                        // 更新标签标题（SessionTabManager 的回调已被覆盖，这里直接处理）
                        if (tabManager != null) {
                            tabManager.updateTitlesFromSessions();
                        }
                    });

                    // Inject BackendBridge into management pages
                    injectBridgeToPage(pages.get("models"));
                    injectBridgeToPage(pages.get("agents"));
                    injectBridgeToPage(pages.get("channels"));
                    injectBridgeToPage(pages.get("skills"));
                    injectBridgeToPage(pages.get("mcp"));
                    injectBridgeToPage(pages.get("databases"));
                    injectBridgeToPage(pages.get("crontasks"));
                    injectBridgeToPage(pages.get("settings"));
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "javaclawbot-fx-init").start();
    }

    /**
     * 检查并同步技能
     * 异步执行，不阻塞 GUI 启动
     */
    private void checkAndSyncSkills() {
        try {
            // 获取工作空间路径（从配置或默认）
            java.nio.file.Path workspace = java.nio.file.Paths.get(
                System.getProperty("user.home"), ".javaclawbot", "workspace"
            );

            SkillSyncService syncService = new SkillSyncService(workspace);
            List<SkillDifference> differences = syncService.findDifferences();

            if (!differences.isEmpty()) {
                // 在 JavaFX 线程显示弹窗
                Platform.runLater(() -> {
                    List<String> selectedSkills = SkillSyncDialog.showAndWait(differences);

                    if (!selectedSkills.isEmpty()) {
                        // 异步执行复制
                        CompletableFuture.runAsync(() -> {
                            for (String skillName : selectedSkills) {
                                try {
                                    syncService.copySkillToWorkspace(skillName);
                                    System.out.println("已同步技能: " + skillName);
                                } catch (Exception e) {
                                    System.err.println("同步技能 " + skillName + " 失败: " + e.getMessage());
                                }
                            }
                            Platform.runLater(() -> {
                                // 刷新技能页面（如果已初始化）
                                javafx.scene.Node skillsPage = pages.get("skills");
                                if (skillsPage instanceof gui.ui.pages.SkillsPage) {
                                    ((gui.ui.pages.SkillsPage) skillsPage).refresh();
                                }
                            });
                        });
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("技能同步检查异常: " + e.getMessage());
        }
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

    // ===== File-edit helpers =====

    /** Extract file_path from tool params string (e.g., "file_path=D:\...\Config.java, old_string=...") */
    private static String extractFilePathFromParams(String params) {
        if (params == null || params.isBlank()) return null;
        // Try to find "file_path=" pattern
        int idx = params.indexOf("file_path=");
        if (idx < 0) return null;
        String after = params.substring(idx + "file_path=".length());
        // Find end: comma, space, or end-of-string (but skip commas inside paths)
        // Simple heuristic: find the next " old_string=" or " content=" or end
        int end = after.length();
        for (String delim : new String[]{", old_string=", ", new_string=", ", content=", ", replace_all="}) {
            int d = after.indexOf(delim);
            if (d > 0 && d < end) end = d;
        }
        return after.substring(0, end).trim();
    }

    /** Extract file_path from edit_file/write_file result content.
     *  Looks for "The file X has been updated" or "File created successfully at: X" */
    private static String extractFilePathFromResult(String result) {
        if (result == null || result.isBlank()) return null;
        // Pattern: "The file D:\...\File.java has been updated"
        String prefix = "The file ";
        int start = result.indexOf(prefix);
        if (start >= 0) {
            String after = result.substring(start + prefix.length());
            String[] endMarkers = {" has been updated", " has been updated successfully", "."};
            for (String m : endMarkers) {
                int end = after.indexOf(m);
                if (end > 0) return after.substring(0, end).trim();
            }
        }
        // Pattern: "File created successfully at: D:\...\File.java"
        String createPrefix = "File created successfully at: ";
        start = result.indexOf(createPrefix);
        if (start >= 0) {
            String after = result.substring(start + createPrefix.length());
            int end = after.indexOf('\n');
            return end > 0 ? after.substring(0, end).trim() : after.trim();
        }
        return null;
    }

    /** Parse unified diff to extract [addedLines, removedLines] */
    private static int[] parseDiffStats(String result) {
        int added = 0, removed = 0;
        if (result == null || result.isBlank()) return new int[]{added, removed};
        for (String line : result.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) added++;
            else if (line.startsWith("-") && !line.startsWith("---")) removed++;
        }
        return new int[]{added, removed};
    }

    /** 重置 FileDiffBadge 到当前会话的备份上下文 */
    private void resetFileBadgeForNewSession() {
        if (backendBridge == null) return;
        agent.tool.file.FileBackupManager fbm = backendBridge.getFileBackupManager();
        if (fbm != null) {
            getActiveChatPage().getFileDiffBadge().setBackupManager(fbm);
            getActiveChatPage().getFileDiffBadge().loadFromBackupManager();
        }
    }
}
