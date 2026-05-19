package gui.ui.pages;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import gui.ui.BackendBridge;
import gui.ui.components.ChatInput;
import gui.ui.components.MessageBubble;
import gui.ui.components.ProjectPopover;
import gui.ui.components.ProjectStatusBadge;
import gui.ui.components.ToolCallCard;
import gui.ui.components.FileDiffBadge;
import gui.ui.components.DiffViewerPopup;
import providers.cli.ProjectRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebView;
import javafx.util.Duration;

public class ChatPage extends VBox {

    private final VBox messageContainer;
    private final ScrollPane scrollPane;
    private final ChatInput chatInput;
    private final SplitPane splitPane;
    private final StackPane scrollStack;
    private final Label scrollToBottomBtn;

    private boolean autoScroll = true;
    private boolean programmaticScroll = false;
    private double lastVvalue = 1.0;
    private double lastContentHeight = 0;

    /** 连体状态浮标：文件变更 + 任务进度，常驻展示 */
    private final FileDiffBadge fileDiffBadge;
    /** 思考中占位气泡 */
    private HBox thinkingPlaceholder;
    /** 思考中动画 */
    private Timeline thinkingAnimation;
    /** 流式输出期间的进度消息气泡（每次更新替换而非追加，避免 WebView 累积卡死 GUI） */
    private javafx.scene.Node lastStreamingBubble;
    /** 流式输出期间创建的独立推理块（最终回复到达时需要清理，避免与合并单元重复） */
    private final java.util.List<javafx.scene.Node> streamingReasoningBlocks = new java.util.ArrayList<>();

    /** 渲染节点数上限：超出后移除最旧节点，防止 WebView 内存堆积导致 GUI 卡顿 */
    private static final int MAX_VISIBLE_NODES = 80;
    /** 每次滚动加载更多时加载的消息数量 */
    private static final int LOAD_MORE_COUNT = 50;
    private int nodesTrimmed = 0;
    private boolean welcomeVisible = true;

    // 无限滚动加载历史消息相关字段
    private java.util.List<java.util.Map<String, Object>> fullHistory; // 完整历史消息
    private int displayStartIndex = 0; // 当前显示的起始索引
    private boolean isLoadingMore = false; // 防止重复加载
    private boolean hasMoreHistory = true; // 是否还有更多历史消息
    private Label loadingIndicator; // 加载指示器

    private static final Parser REASONING_PARSER;
    private static final HtmlRenderer REASONING_RENDERER;
    private static final String REASONING_HTML_TEMPLATE;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, java.util.List.of(TablesExtension.create()));
        REASONING_PARSER = Parser.builder(options).build();
        REASONING_RENDERER = HtmlRenderer.builder(options).build();

        REASONING_HTML_TEMPLATE = "<!DOCTYPE html><html style='height:100%;background:rgba(0,0,0,0.03);'>"
            + "<head><meta charset='UTF-8'><style>"
            + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Segoe UI Emoji','Apple Color Emoji','Noto Color Emoji',sans-serif;"
            + "font-size:13px;line-height:1.6;color:rgba(0,0,0,0.5);"
            + "background:rgba(0,0,0,0.03);margin:0;padding:8px 16px;overflow:hidden;}"
            + "pre{background:rgba(0,0,0,0.03);border:1px solid rgba(0,0,0,0.06);border-radius:6px;"
            + "padding:8px 12px;overflow-x:auto;font-family:'JetBrains Mono','Fira Code',monospace;"
            + "font-size:12px;line-height:1.4;}"
            + "code{font-family:'JetBrains Mono','Fira Code',monospace;font-size:12px;"
            + "background:rgba(0,0,0,0.03);padding:1px 4px;border-radius:3px;}"
            + "pre code{background:transparent;padding:0;border-radius:0;}"
            + "p{margin:4px 0;}ul,ol{padding-left:18px;margin:4px 0;}li{margin:2px 0;}"
            + "a{color:#3b82f6;}"
            + "</style></head><body>%s</body></html>";
    }

    public ChatPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        // 连体状态浮标（常驻右下角）
        fileDiffBadge = new FileDiffBadge();

        // 消息区域
        messageContainer = new VBox(16);
        messageContainer.setPadding(new Insets(16));
        messageContainer.setStyle("-fx-background-color: transparent;");

        scrollPane = new ScrollPane(messageContainer);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        // 悬浮滚动到底部按钮（必须在 vvalue 监听器之前创建）
        scrollToBottomBtn = createScrollToBottomButton();

        // 跟踪滚动位置，判断是否在底部
        scrollPane.vvalueProperty().addListener((obs, old, val) -> {
            double v = val.doubleValue();
            double viewHeight = scrollPane.getViewportBounds().getHeight();
            double contentHeight = messageContainer.getHeight();
            boolean canScroll = contentHeight > viewHeight + 1;

            // 程序化滚动期间不干预，由 scrollToBottom/smartScrollToBottom 控制
            if (programmaticScroll) {
                lastVvalue = v;
                lastContentHeight = contentHeight;
                return;
            }

            // 当滚动到顶部附近时触发加载更多历史消息
            if (v < 0.1 && hasMoreHistory && !isLoadingMore && fullHistory != null) {
                loadMoreHistory();
            }

            boolean atBottom = v >= 0.95;
            if (!canScroll || atBottom) {
                autoScroll = true;
                scrollToBottomBtn.setVisible(false);
            } else if (contentHeight > lastContentHeight + 1 && lastVvalue >= 0.95) {
                // 内容高度增长（如 WebView 自适应调整）且之前在底部，保持自动滚动
                autoScroll = true;
                scrollToBottomBtn.setVisible(false);
                Platform.runLater(() -> scrollPane.setVvalue(1.0));
            } else {
                autoScroll = false;
                scrollToBottomBtn.setVisible(true);
            }
            lastVvalue = v;
            lastContentHeight = contentHeight;
        });

        // 消息滚动区域 + 悬浮按钮（回到底部）
        scrollStack = new StackPane();
        scrollStack.getChildren().addAll(scrollPane, scrollToBottomBtn);
        StackPane.setAlignment(scrollToBottomBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollToBottomBtn, new Insets(0, 24, 12, 0));

        // 连体状态浮标（右下角，折叠态贴右侧边缘）
        scrollStack.getChildren().add(fileDiffBadge);
        StackPane.setAlignment(fileDiffBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(fileDiffBadge, new Insets(0, 8, 16, 0));

        // 输入区域
        chatInput = new ChatInput();

        // SplitPane：支持拖拽调整输入框高度（类似微信）
        splitPane = new SplitPane();
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        splitPane.getItems().addAll(scrollStack, chatInput);
        splitPane.setDividerPosition(0, 0.75);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        getChildren().add(splitPane);

        // 添加欢迎消息
        addWelcomeMessage();
    }

    private Label createScrollToBottomButton() {
        Label btn = new Label("\u2B07");
        btn.setStyle("-fx-background-color: rgba(255, 255, 255, 0.85);"
            + " -fx-background-radius: 999px;"
            + " -fx-pref-width: 40px; -fx-pref-height: 40px;"
            + " -fx-alignment: center;"
            + " -fx-font-size: 20px;"
            + " -fx-cursor: hand;"
            + " -fx-text-fill: rgba(0, 0, 0, 0.5);"
            + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 4, 0, 0, 1);");
        btn.setVisible(false);
        // 不设 managed=false，让 StackPane 正确布局
        btn.setOnMouseClicked(e -> scrollToBottom());
        btn.setOnMouseEntered(e ->
            btn.setStyle("-fx-background-color: rgba(0, 0, 0, 0.12);"
                + " -fx-background-radius: 999px;"
                + " -fx-pref-width: 40px; -fx-pref-height: 40px;"
                + " -fx-alignment: center;"
                + " -fx-font-size: 20px;"
                + " -fx-cursor: hand;"
                + " -fx-text-fill: rgba(0, 0, 0, 0.7);"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1);"));
        btn.setOnMouseExited(e ->
            btn.setStyle("-fx-background-color: rgba(255, 255, 255, 0.85);"
                + " -fx-background-radius: 999px;"
                + " -fx-pref-width: 40px; -fx-pref-height: 40px;"
                + " -fx-alignment: center;"
                + " -fx-font-size: 20px;"
                + " -fx-cursor: hand;"
                + " -fx-text-fill: rgba(0, 0, 0, 0.5);"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 4, 0, 0, 1);"));
        return btn;
    }

    private void addWelcomeMessage() {
        VBox welcomeBox = new VBox(16);
        welcomeBox.setAlignment(Pos.CENTER);
        welcomeBox.setPadding(new Insets(40));
        welcomeBox.setStyle("-fx-background-color: transparent;");

        Label title = new Label("欢迎使用 NexusAi");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("有什么我可以帮你的吗？");
        subtitle.setStyle("-fx-font-family: Georgia; -fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5);");

        HBox quickActions = new HBox(12);
        quickActions.setAlignment(Pos.CENTER);
        String[] actions = {"解释代码", "生成测试", "重构建议"};
        for (String action : actions) {
            Label btn = new Label(action);
            btn.setStyle("-fx-padding: 6px 16px; -fx-background-radius: 999px; -fx-border-color: rgba(0, 0, 0, 0.1); -fx-border-radius: 999px; -fx-border-width: 1px; -fx-font-size: 13px; -fx-font-weight: 500; -fx-cursor: hand;");
            quickActions.getChildren().add(btn);
        }

        welcomeBox.getChildren().addAll(title, subtitle, quickActions);
        messageContainer.getChildren().add(welcomeBox);
        welcomeVisible = true;
    }

    public void addUserMessage(String content) {
        addUserMessage(content, java.util.List.of());
    }

    /** 用户消息 + 图片预览 */
    public void addUserMessage(String content, java.util.List<java.nio.file.Path> imagePaths) {
        clearWelcomeIfNeeded();

        // 图片预览
        if (imagePaths != null && !imagePaths.isEmpty()) {
            javafx.scene.layout.HBox imgRow = new javafx.scene.layout.HBox(8);
            imgRow.setPadding(new javafx.geometry.Insets(0, 0, 8, 0));
            imgRow.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            for (java.nio.file.Path p : imagePaths) {
                javafx.scene.image.Image img = new javafx.scene.image.Image(
                    p.toUri().toString(), 200, 150, true, true);
                javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);
                iv.setFitWidth(200);
                iv.setFitHeight(150);
                iv.setPreserveRatio(true);
                iv.setStyle("-fx-background-radius: 10px;"
                    + " -fx-border-color: rgba(0,0,0,0.08); -fx-border-radius: 10px;"
                    + " -fx-border-width: 1px;");
                javafx.scene.layout.StackPane sp = new javafx.scene.layout.StackPane(iv);
                sp.setStyle("-fx-background-radius: 10px;");
                imgRow.getChildren().add(sp);
            }
            messageContainer.getChildren().add(imgRow);
        }

        MessageBubble bubble = new MessageBubble(MessageBubble.Role.USER, content);
        messageContainer.getChildren().add(bubble);
        smartScrollToBottom();
    }

    public void addAssistantMessage(String content) {
        addAssistantMessage(content, false);
    }

    /**
     * 添加助手消息气泡。
     * @param replacePrevious 为 true 时替换上一个流式输出气泡（仅用于 LLM 流式进度更新）
     */
    public void addAssistantMessage(String content, boolean replacePrevious) {
        // 过滤 LLM API 适配占位符，不显示无意义文本
        if ("(empty)".equals(content) || "（empty）".equals(content)) return;
        MessageBubble bubble = new MessageBubble(MessageBubble.Role.ASSISTANT, content);
        bubble.setOnHeightAdjusted(this::scrollToBottom);
        // replacePrevious=true 且上一个流式气泡还在时替换它，避免 WebView 累积导致 GUI 卡死
        if (replacePrevious && lastStreamingBubble != null) {
            int idx = messageContainer.getChildren().indexOf(lastStreamingBubble);
            if (idx >= 0) {
                messageContainer.getChildren().set(idx, bubble);
            } else {
                messageContainer.getChildren().add(bubble);
            }
        } else {
            messageContainer.getChildren().add(bubble);
        }
        lastStreamingBubble = bubble;
        smartScrollToBottom();
    }

    /** 清除流式输出期间的所有临时节点（流式气泡 + 独立推理块），为最终合并单元腾出空间 */
    public void clearStreamingBubble() {
        // 移除流式气泡节点（不仅清引用，还从容器中移除，避免残留空白/重复内容）
        if (lastStreamingBubble != null) {
            messageContainer.getChildren().remove(lastStreamingBubble);
            lastStreamingBubble = null;
        }
        // 清除流式推理块追踪，但不从容器移除（推理内容应保持可见，与历史恢复行为一致）
        streamingReasoningBlocks.clear();
    }

    /** 是否存在已展示的流式推理块（用于最终回复时避免重复添加） */
    public boolean hasStreamingReasoningBlocks() {
        return !streamingReasoningBlocks.isEmpty();
    }

    /** 添加独立的推理/思考块（可折叠），用于工具调用前展示思考过程 */
    public void addReasoningBlock(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) return;

        HBox row = new HBox(12);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));

        Label avatar = new Label("✨");
        avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
            + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
        avatar.setMinSize(32, 32);

        VBox reasoningBlock = new VBox();
        reasoningBlock.setStyle("-fx-background-color: rgba(0,0,0,0.03);"
            + " -fx-background-radius: 12px; -fx-padding: 0;");
        reasoningBlock.setMaxWidth(700);

        HBox reasoningHeader = new HBox(8);
        reasoningHeader.setAlignment(Pos.CENTER_LEFT);
        reasoningHeader.setPadding(new Insets(8, 16, 0, 16));
        Label toggleArrow = new Label("▸");
        toggleArrow.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(0,0,0,0.4);");
        Label titleLabel = new Label("💭 已深度思考");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.45);");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        reasoningHeader.getChildren().addAll(toggleArrow, titleLabel, headerSpacer);
        reasoningHeader.setCursor(javafx.scene.Cursor.HAND);
        reasoningBlock.getChildren().add(reasoningHeader);

        String reasoningHtmlBody = REASONING_RENDERER.render(REASONING_PARSER.parse(reasoning));
        String reasoningHtml = REASONING_HTML_TEMPLATE.replace("%s", reasoningHtmlBody);
        WebView reasoningWv = new WebView();
        reasoningWv.setContextMenuEnabled(false);
        reasoningWv.setStyle("-fx-background-color: rgba(0,0,0,0.03);");
        reasoningWv.setPrefHeight(0);
        reasoningWv.setMaxHeight(0);
        reasoningWv.setPrefWidth(600);
        reasoningWv.setMaxWidth(600);

        final double[] measuredHeight = {0};
        final boolean[] heightReady = {false};
        reasoningWv.getEngine().documentProperty().addListener((obs, old, doc) -> {
            if (doc != null) {
                Platform.runLater(() -> measureWebViewHeightWithRetry(reasoningWv, measuredHeight, heightReady, 0));
            }
        });

        reasoningWv.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            e.consume();
            javafx.event.Event.fireEvent(reasoningBlock, e.copyFor(reasoningBlock, reasoningBlock));
        });

        reasoningBlock.getChildren().add(reasoningWv);

        reasoningHeader.setOnMouseClicked(e -> {
            boolean expand = reasoningWv.getMaxHeight() == 0;
            if (expand) {
                // 如果高度还没测量好，强制测量一次
                if (!heightReady[0]) {
                    forceMeasureHeight(reasoningWv, measuredHeight, heightReady);
                }
                // 始终展开：优先使用测量高度，测量未就绪时使用兜底高度 200px 避免空白
                double h = (heightReady[0] && measuredHeight[0] > 0) ? measuredHeight[0] : 200;
                reasoningWv.setPrefHeight(h);
                reasoningWv.setMaxHeight(h);
                toggleArrow.setText("\u25BE");
            } else {
                reasoningWv.setPrefHeight(0);
                reasoningWv.setMaxHeight(0);
                toggleArrow.setText("\u25B8");
            }
        });

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        row.getChildren().addAll(avatar, reasoningBlock, rightSpacer);
        messageContainer.getChildren().add(row);
        // 跟踪流式推理块，以便最终回复到达时清理（避免与合并单元重复）
        streamingReasoningBlocks.add(row);
        smartScrollToBottom();

        // 延迟加载内容：等场景布局完成后，WebView 已有正确宽度
        // 使用 Platform.runLater 而非 sceneProperty 监听器，避免宽度计算为负数的时序问题
        Platform.runLater(() -> {
            // 按实际可用场景宽度调整 WebView 宽度（仅在有效时修正）
            javafx.scene.Scene scene = reasoningBlock.getScene();
            if (scene != null) {
                double w = Math.min(600, scene.getWidth() - 256 - 32 - 44);
                if (w > 0) {
                    reasoningWv.setPrefWidth(w);
                    reasoningWv.setMaxWidth(w);
                }
            }
            reasoningWv.getEngine().load(toDataUri(reasoningHtml));
        });
    }

    public ToolCallCard addToolCallCard(String toolName, String status, String params) {
        return addToolCallCard(toolName, status, params, false);
    }

    public ToolCallCard addToolCallCard(String toolName, String status, String params, boolean startExpanded) {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ToolCallCard card = new ToolCallCard(toolName, status, params, startExpanded, timestamp);
        card.setMaxWidth(700);
        // Wrap in HBox like assistant bubble (avatar + card)
        HBox wrapper = new HBox(12);
        wrapper.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        wrapper.setPadding(new Insets(8, 0, 8, 0));
        Label avatar = new Label("\u2728");
        avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px; -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
        avatar.setMinSize(32, 32);
        wrapper.getChildren().addAll(avatar, card);
        messageContainer.getChildren().add(wrapper);
        smartScrollToBottom();
        return card;
    }

    /** 思考中占位气泡：助手头像 + 灰底文字 + 动画点 */
    public void addThinkingPlaceholder() {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));

        Label avatar = new Label("\u2728");
        avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
            + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
        avatar.setMinSize(32, 32);

        Label text = new Label("\u25CF \u601D\u8003\u4E2D");
        text.setStyle("-fx-font-size: 14px; -fx-text-fill: rgba(0, 0, 0, 0.45);");

        VBox bubble = new VBox();
        bubble.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 16px;"
            + " -fx-padding: 12px 16px;");
        bubble.getChildren().add(text);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        row.getChildren().addAll(avatar, bubble, rightSpacer);
        messageContainer.getChildren().add(row);
        thinkingPlaceholder = row;

        // 动画点
        final String[] dots = {"", ".", "..", "..."};
        final int[] idx = {0};
        thinkingAnimation = new Timeline(
            new KeyFrame(Duration.millis(500), e -> {
                idx[0] = (idx[0] + 1) % dots.length;
                text.setText("\u25CF \u601D\u8003\u4E2D" + dots[idx[0]]);
            })
        );
        thinkingAnimation.setCycleCount(Animation.INDEFINITE);
        thinkingAnimation.play();

        smartScrollToBottom();
    }

    /** 移除思考中占位气泡 */
    public void removeThinkingPlaceholder() {
        if (thinkingPlaceholder != null) {
            messageContainer.getChildren().remove(thinkingPlaceholder);
            thinkingPlaceholder = null;
        }
        if (thinkingAnimation != null) {
            thinkingAnimation.stop();
            thinkingAnimation = null;
        }
    }

    /** 推理+回复合并为一个视觉单元：一个 avatar + 推理块（默认收起）+ 回复块 */
    public void addAssistantMessageWithReasoning(String reasoning, String response) {
        // 如果回复内容是 LLM API 适配占位符，退化为只显示推理块
        if ("(empty)".equals(response)) {
            addReasoningBlock(reasoning);
            return;
        }

        HBox row = new HBox(12);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));

        // 共享头像
        Label avatar = new Label("\u2728");
        avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
            + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
        avatar.setMinSize(32, 32);

        // 回复块（先创建以确定宽度）
        StackPane responseBubble = MessageBubble.createBubbleWebView(response);

        // 推理块容器：灰底圆角，与回复块同宽
        VBox reasoningBlock = new VBox();
        reasoningBlock.setStyle("-fx-background-color: rgba(0,0,0,0.03);"
            + " -fx-background-radius: 12px; -fx-padding: 0;");
        reasoningBlock.setMaxWidth(700);

        // 头部：可点击折叠
        HBox reasoningHeader = new HBox(8);
        reasoningHeader.setAlignment(Pos.CENTER_LEFT);
        reasoningHeader.setPadding(new Insets(8, 16, 0, 16));
        Label toggleArrow = new Label("\u25B8"); // ▸ 默认收起
        toggleArrow.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(0,0,0,0.4);");
        Label titleLabel = new Label("\uD83D\uDCAD \u5DF2\u6DF1\u5EA6\u601D\u8003");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.45);");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        reasoningHeader.getChildren().addAll(toggleArrow, titleLabel, headerSpacer);
        reasoningHeader.setCursor(javafx.scene.Cursor.HAND);
        reasoningBlock.getChildren().add(reasoningHeader);

        // 推理内容 WebView：始终保持 managed，通过 maxHeight=0/正确值 折叠展开
        // 关键：内容只在宽度绑定生效 + 场景布局完成后加载一次，确保 scrollHeight 测量准确
        String reasoningHtmlBody = REASONING_RENDERER.render(REASONING_PARSER.parse(reasoning));
        String reasoningHtml = REASONING_HTML_TEMPLATE.replace("%s", reasoningHtmlBody);
        WebView reasoningWv = new WebView();
        reasoningWv.setContextMenuEnabled(false);
        reasoningWv.setStyle("-fx-background-color: rgba(0,0,0,0.03);");
        reasoningWv.setPrefHeight(0);
        reasoningWv.setMaxHeight(0);

        // 宽度绑定：与回复块同宽（必须在 loadContent 之前设置）
        reasoningWv.prefWidthProperty().bind(responseBubble.widthProperty());
        reasoningWv.maxWidthProperty().bind(responseBubble.widthProperty());

        // 存储测量的内容高度
        final double[] measuredHeight = {0};
        final boolean[] heightReady = {false};
        reasoningWv.getEngine().documentProperty().addListener((obs, old, doc) -> {
            if (doc != null) {
                Platform.runLater(() -> measureWebViewHeightWithRetry(reasoningWv, measuredHeight, heightReady, 0));
            }
        });

        reasoningWv.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            e.consume();
            javafx.event.Event.fireEvent(reasoningBlock, e.copyFor(reasoningBlock, reasoningBlock));
        });

        reasoningBlock.getChildren().add(reasoningWv);

        // 点击切换展开/收起（仅切换高度，不操作 visibility/managed）
        reasoningHeader.setOnMouseClicked(e -> {
            boolean expand = reasoningWv.getMaxHeight() == 0;
            if (expand) {
                if (!heightReady[0]) {
                    forceMeasureHeight(reasoningWv, measuredHeight, heightReady);
                }
                // 始终展开：优先使用测量高度，测量未就绪时使用兜底高度 200px 避免空白
                double h = (heightReady[0] && measuredHeight[0] > 0) ? measuredHeight[0] : 200;
                reasoningWv.setPrefHeight(h);
                reasoningWv.setMaxHeight(h);
                toggleArrow.setText("\u25BE");
            } else {
                reasoningWv.setPrefHeight(0);
                reasoningWv.setMaxHeight(0);
                toggleArrow.setText("\u25B8");
            }
        });

        // 推理块宽度与回复块同宽
        reasoningBlock.prefWidthProperty().bind(responseBubble.widthProperty());

        // 组装
        VBox contentBox = new VBox(6);
        contentBox.getChildren().addAll(reasoningBlock, responseBubble);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        row.getChildren().addAll(avatar, contentBox, rightSpacer);
        messageContainer.getChildren().add(row);
        smartScrollToBottom();

        // 延迟加载推理内容：等场景布局完成后，WebView 已有正确宽度
        Platform.runLater(() -> reasoningWv.getEngine().load(toDataUri(reasoningHtml)));
    }


    /**
     * 带重试的 WebView 高度测量，解决渲染延迟导致的留白问题。
     * 连续两次测量结果一致（误差 < 2px）时确认高度，最多重试 5 次。
     */
    private void measureWebViewHeightWithRetry(WebView wv, double[] result,
                                                boolean[] ready, int attempt) {
        try {
            Object h = wv.getEngine().executeScript(
                "(function(){var d=document;var e=d.documentElement;"
                + "var oldH=e.style.height;e.style.height='auto';"
                + "var sh=Math.max(d.body.scrollHeight,e.scrollHeight);"
                + "e.style.height=oldH;"
                + "return sh;})()");
            if (h instanceof Number) {
                double height = ((Number) h).doubleValue();
                if (height > 0) {
                    if (result[0] > 0 && Math.abs(height - result[0]) < 2) {
                        // 两次测量一致，确认高度
                        if (height != result[0]) {
                            wv.setPrefHeight(height);
                            // 如果已展开（用户点击过），同步更新 maxHeight 避免裁剪
                            if (wv.getMaxHeight() > 0) {
                                wv.setMaxHeight(height);
                            }
                        }
                        ready[0] = true;
                        return;
                    }
                    result[0] = height;
                    if (attempt >= 5) {
                        ready[0] = true;
                        return;
                    }
                    javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(100));
                    final int next = attempt + 1;
                    delay.setOnFinished(ev -> measureWebViewHeightWithRetry(wv, result, ready, next));
                    delay.play();
                    return;
                }
            }
            // 高度为 0 或无效：继续重试，但达到上限时也必须标记 ready
            if (attempt < 5) {
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(100));
                final int next = attempt + 1;
                delay.setOnFinished(ev -> measureWebViewHeightWithRetry(wv, result, ready, next));
                delay.play();
            } else {
                ready[0] = true;
            }
        } catch (Exception ignored) {
            if (attempt >= 5) {
                ready[0] = true;
            }
        }
    }

    /** 单次强制测量 WebView 内容高度（用于点击展开时的回退测量） */
    private void forceMeasureHeight(WebView wv, double[] result, boolean[] ready) {
        try {
            Object h = wv.getEngine().executeScript(
                "(function(){var d=document;var e=d.documentElement;"
                + "var oldH=e.style.height;e.style.height='auto';"
                + "var sh=Math.max(d.body.scrollHeight,e.scrollHeight);"
                + "e.style.height=oldH;"
                + "return sh;})()");
            if (h instanceof Number && ((Number) h).doubleValue() > 0) {
                result[0] = ((Number) h).doubleValue();
                ready[0] = true;
            }
        } catch (Exception ignored) {}
    }

    /** 用 data URI 加载 HTML，确保非 BMP 字符（emoji）被 WebView 正确解码 */
    private static String toDataUri(String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return "data:text/html;charset=UTF-8;base64," + b64;
    }

    private void clearWelcomeIfNeeded() {
        if (!messageContainer.getChildren().isEmpty()
            && messageContainer.getChildren().get(0) instanceof VBox) {
            messageContainer.getChildren().clear();
        }
        welcomeVisible = false;
    }

    /**
     * 渲染节点数窗口化：超出 MAX_VISIBLE_NODES 上限时从头部移除最旧节点，
     * 防止 WebView 累积导致内存爆炸和 GUI 卡顿。
     */
    private void trimToWindow() {
        var children = messageContainer.getChildren();
        int total = children.size();
        if (total <= MAX_VISIBLE_NODES) return;

        int remove = total - MAX_VISIBLE_NODES;
        int startIdx = welcomeVisible ? 1 : 0; // 跳过欢迎消息
        if (startIdx >= children.size()) return;

        int endIdx = Math.min(startIdx + remove, children.size());
        children.remove(startIdx, endIdx);
        nodesTrimmed += (endIdx - startIdx);
    }

    /**
     * 智能滚动：仅在用户处于底部附近时自动滚动到最新消息
     */
    private void smartScrollToBottom() {
        if (autoScroll) {
            programmaticScroll = true;
            Platform.runLater(() -> {
                scrollPane.setVvalue(1.0);
                programmaticScroll = false;
            });
        }
        trimToWindow();
    }

    /**
     * 强制滚动到底部（悬浮按钮点击 / WebView 高度自适应回调时）
     */
    private void scrollToBottom() {
        autoScroll = true;
        scrollToBottomBtn.setVisible(false);
        programmaticScroll = true;
        Platform.runLater(() -> {
            scrollPane.setVvalue(1.0);
            programmaticScroll = false;
        });
    }

    public FileDiffBadge getFileDiffBadge() {
        return fileDiffBadge;
    }

    public ChatInput getChatInput() {
        return chatInput;
    }

    public void setStatusText(String text) {
        chatInput.setStatusText(text);
    }

    /**
     * 更新上下文使用率展示，转发到 ChatInput。
     */
    public void setContextUsage(double ratio) {
        chatInput.setContextUsage(ratio);
    }

    /**
     * 加载更多历史消息（滚动到顶部时触发）
     */
    private void loadMoreHistory() {
        if (isLoadingMore || !hasMoreHistory || fullHistory == null) {
            return;
        }

        isLoadingMore = true;
        showLoadingIndicator();

        // 计算新的起始索引
        int newStartIndex = Math.max(0, displayStartIndex - LOAD_MORE_COUNT);

        // 在后台线程准备消息节点
        Platform.runLater(() -> {
            // 保存当前滚动位置和内容高度
            double currentVvalue = scrollPane.getVvalue();
            double currentContentHeight = messageContainer.getHeight();

            // 准备新消息节点
            java.util.List<javafx.scene.Node> newNodes = new java.util.ArrayList<>();
            java.util.Map<String, ToolCallCard> cardById = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> filePathByCallId = new java.util.LinkedHashMap<>();

            for (int i = newStartIndex; i < displayStartIndex; i++) {
                if (i < 0 || i >= fullHistory.size()) {
                    continue;
                }
                java.util.Map<String, Object> msg = fullHistory.get(i);
                try {
                    String role = String.valueOf(msg.getOrDefault("role", ""));
                    if ("system".equals(role)) {
                        continue;
                    }

                    javafx.scene.Node node = createMessageNode(msg, role, cardById, filePathByCallId);
                    if (node != null) {
                        newNodes.add(node);
                    }
                } catch (Exception e) {
                    // 单条消息加载失败不阻断整个流程
                }
            }

            // 插入到容器顶部（在加载指示器之后）
            int insertIndex = loadingIndicator != null && messageContainer.getChildren().contains(loadingIndicator) ? 1 : 0;
            messageContainer.getChildren().addAll(insertIndex, newNodes);

            // 恢复滚动位置（保持用户查看的位置）
            Platform.runLater(() -> {
                double newContentHeight = messageContainer.getHeight();
                double heightDiff = newContentHeight - currentContentHeight;

                if (heightDiff > 0 && currentContentHeight > 0) {
                    // 调整滚动位置以保持视觉位置不变
                    double newVvalue = Math.min(1.0, currentVvalue + heightDiff / newContentHeight);
                    scrollPane.setVvalue(newVvalue);
                }

                hideLoadingIndicator();
                isLoadingMore = false;
                displayStartIndex = newStartIndex;

                // 检查是否还有更多历史消息
                if (newStartIndex <= 0) {
                    hasMoreHistory = false;
                }
            });
        });
    }

    /**
     * 创建单个消息节点（用于加载更多历史消息）
     * 注意：工具结果消息（role=tool）会更新对应工具调用卡片的状态，但不创建新节点
     */
    private javafx.scene.Node createMessageNode(java.util.Map<String, Object> msg, String role,
                                                  java.util.Map<String, ToolCallCard> cardById,
                                                  java.util.Map<String, String> filePathByCallId) {
        if ("user".equals(role)) {
            String text = extractTextContent(msg.get("content"));
            if (text != null && !text.isBlank()) {
                MessageBubble bubble = new MessageBubble(MessageBubble.Role.USER, text);
                return bubble;
            }
        } else if ("assistant".equals(role)) {
            cardById.clear();
            String content = extractTextContent(msg.get("content"));
            String reasoning = msg.get("reasoning_content") instanceof String s && !s.isBlank() ? s : null;
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> toolCalls =
                (java.util.List<java.util.Map<String, Object>>) msg.get("tool_calls");
            boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

            if (reasoning != null && !hasToolCalls) {
                if (content != null && !content.isBlank()) {
                    // 创建推理+回复合并节点
                    return createAssistantMessageWithReasoningNode(reasoning, content);
                } else {
                    return createReasoningBlockNode(reasoning);
                }
            } else if (hasToolCalls) {
                // 创建工具调用节点组
                java.util.List<javafx.scene.Node> nodes = new java.util.ArrayList<>();
                if (reasoning != null) {
                    nodes.add(createReasoningBlockNode(reasoning));
                }
                if (content != null && !content.isBlank()) {
                    MessageBubble bubble = new MessageBubble(MessageBubble.Role.ASSISTANT, content);
                    nodes.add(bubble);
                }
                filePathByCallId.clear();
                for (var tc : toolCalls) {
                    String tn = extractToolName(tc);
                    String params = formatToolParams(tn, tc);
                    ToolCallCard card = new ToolCallCard(tn, "running", params, false,
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    card.setMaxWidth(700);
                    String callId = (String) tc.get("id");
                    cardById.put(callId, card);
                    if ("edit_file".equals(tn) || "write_file".equals(tn)) {
                        String fp = extractFilePathFromArgs(tc);
                        if (fp != null) filePathByCallId.put(callId, fp);
                    }
                    HBox wrapper = new HBox(12);
                    wrapper.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    wrapper.setPadding(new Insets(8, 0, 8, 0));
                    Label avatar = new Label("\u2728");
                    avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px; -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
                    avatar.setMinSize(32, 32);
                    wrapper.getChildren().addAll(avatar, card);
                    nodes.add(wrapper);
                }
                // 返回容器节点
                VBox container = new VBox(16);
                container.getChildren().addAll(nodes);
                return container;
            } else if (content != null && !content.isBlank()) {
                MessageBubble bubble = new MessageBubble(MessageBubble.Role.ASSISTANT, content);
                return bubble;
            }
        } else if ("tool".equals(role)) {
            // 工具结果消息：更新对应工具调用卡片的状态
            String tcId = msg.get("tool_call_id") instanceof String s ? s : null;
            String toolName = msg.get("name") instanceof String s ? s : null;
            String result = extractTextContent(msg.get("content"));
            if (tcId != null && result != null) {
                ToolCallCard card = cardById.get(tcId);
                if (card != null) {
                    card.setStatus("completed");
                    // edit_file/write_file: 显示结构化对比/回滚按钮
                    if (("edit_file".equals(toolName) || "write_file".equals(toolName))
                            && result != null && !result.isBlank()) {
                        String filePath = extractFilePath(result);
                        // 结果中没提取到时，回退到工具调用参数中的 file_path
                        if (filePath == null || filePath.isBlank()) {
                            filePath = filePathByCallId.get(tcId);
                        }
                        if (filePath != null && !filePath.isBlank()) {
                            agent.tool.file.FileBackupManager fbm = fileDiffBadge.getBackupManager();
                            int[] stats = parseDiff(result);
                            card.setFileEditResult(filePath, stats[0], stats[1], fbm, null);
                        } else {
                            card.addResult(result);
                        }
                    } else if ("TodoWrite".equals(toolName)) {
                        fileDiffBadge.updateTodoFromJson(result);
                        card.addStructuredContent(
                            gui.ui.components.TodoResultView.build(result));
                    } else if ("AskUserQuestion".equals(toolName) && result.contains("\"questions\"")) {
                        card.addStructuredContent(
                            gui.ui.components.AskQuestionResultView.build(result));
                    } else {
                        card.addResult(result);
                    }
                }
            }
            // 工具结果消息不创建新节点，只更新卡片状态
            return null;
        }
        return null;
    }

    /**
     * 创建推理+回复合并节点
     */
    private javafx.scene.Node createAssistantMessageWithReasoningNode(String reasoning, String response) {
        if ("(empty)".equals(response)) {
            return createReasoningBlockNode(reasoning);
        }

        HBox row = new HBox(12);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));

        Label avatar = new Label("\u2728");
        avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
            + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
        avatar.setMinSize(32, 32);

        StackPane responseBubble = MessageBubble.createBubbleWebView(response);

        VBox reasoningBlock = new VBox();
        reasoningBlock.setStyle("-fx-background-color: rgba(0,0,0,0.03);"
            + " -fx-background-radius: 12px; -fx-padding: 0;");
        reasoningBlock.setMaxWidth(700);

        HBox reasoningHeader = new HBox(8);
        reasoningHeader.setAlignment(Pos.CENTER_LEFT);
        reasoningHeader.setPadding(new Insets(8, 16, 0, 16));
        Label toggleArrow = new Label("\u25B8");
        toggleArrow.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(0,0,0,0.4);");
        Label titleLabel = new Label("\uD83D\uDCAD \u5DF2\u6DF1\u5EA6\u601D\u8003");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.45);");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        reasoningHeader.getChildren().addAll(toggleArrow, titleLabel, headerSpacer);
        reasoningHeader.setCursor(javafx.scene.Cursor.HAND);
        reasoningBlock.getChildren().add(reasoningHeader);

        String reasoningHtmlBody = REASONING_RENDERER.render(REASONING_PARSER.parse(reasoning));
        String reasoningHtml = REASONING_HTML_TEMPLATE.replace("%s", reasoningHtmlBody);
        WebView reasoningWv = new WebView();
        reasoningWv.setContextMenuEnabled(false);
        reasoningWv.setStyle("-fx-background-color: rgba(0,0,0,0.03);");
        reasoningWv.setPrefHeight(0);
        reasoningWv.setMaxHeight(0);
        reasoningWv.prefWidthProperty().bind(responseBubble.widthProperty());
        reasoningWv.maxWidthProperty().bind(responseBubble.widthProperty());

        final double[] measuredHeight = {0};
        final boolean[] heightReady = {false};
        reasoningWv.getEngine().documentProperty().addListener((obs, old, doc) -> {
            if (doc != null) {
                Platform.runLater(() -> measureWebViewHeightWithRetry(reasoningWv, measuredHeight, heightReady, 0));
            }
        });

        reasoningWv.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            e.consume();
            javafx.event.Event.fireEvent(reasoningBlock, e.copyFor(reasoningBlock, reasoningBlock));
        });

        reasoningBlock.getChildren().add(reasoningWv);

        reasoningHeader.setOnMouseClicked(e -> {
            boolean expand = reasoningWv.getMaxHeight() == 0;
            if (expand) {
                if (!heightReady[0]) {
                    forceMeasureHeight(reasoningWv, measuredHeight, heightReady);
                }
                double h = (heightReady[0] && measuredHeight[0] > 0) ? measuredHeight[0] : 200;
                reasoningWv.setPrefHeight(h);
                reasoningWv.setMaxHeight(h);
                toggleArrow.setText("\u25BE");
            } else {
                reasoningWv.setPrefHeight(0);
                reasoningWv.setMaxHeight(0);
                toggleArrow.setText("\u25B8");
            }
        });

        reasoningBlock.prefWidthProperty().bind(responseBubble.widthProperty());

        VBox contentBox = new VBox(6);
        contentBox.getChildren().addAll(reasoningBlock, responseBubble);

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        row.getChildren().addAll(avatar, contentBox, rightSpacer);
        Platform.runLater(() -> reasoningWv.getEngine().load(toDataUri(reasoningHtml)));
        return row;
    }

    /**
     * 创建推理块节点
     */
    private javafx.scene.Node createReasoningBlockNode(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) return null;

        HBox row = new HBox(12);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));

        Label avatar = new Label("✨");
        avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
            + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
        avatar.setMinSize(32, 32);

        VBox reasoningBlock = new VBox();
        reasoningBlock.setStyle("-fx-background-color: rgba(0,0,0,0.03);"
            + " -fx-background-radius: 12px; -fx-padding: 0;");
        reasoningBlock.setMaxWidth(700);

        HBox reasoningHeader = new HBox(8);
        reasoningHeader.setAlignment(Pos.CENTER_LEFT);
        reasoningHeader.setPadding(new Insets(8, 16, 0, 16));
        Label toggleArrow = new Label("▸");
        toggleArrow.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(0,0,0,0.4);");
        Label titleLabel = new Label("💭 已深度思考");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.45);");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        reasoningHeader.getChildren().addAll(toggleArrow, titleLabel, headerSpacer);
        reasoningHeader.setCursor(javafx.scene.Cursor.HAND);
        reasoningBlock.getChildren().add(reasoningHeader);

        String reasoningHtmlBody = REASONING_RENDERER.render(REASONING_PARSER.parse(reasoning));
        String reasoningHtml = REASONING_HTML_TEMPLATE.replace("%s", reasoningHtmlBody);
        WebView reasoningWv = new WebView();
        reasoningWv.setContextMenuEnabled(false);
        reasoningWv.setStyle("-fx-background-color: rgba(0,0,0,0.03);");
        reasoningWv.setPrefHeight(0);
        reasoningWv.setMaxHeight(0);
        reasoningWv.setPrefWidth(600);
        reasoningWv.setMaxWidth(600);

        final double[] measuredHeight = {0};
        final boolean[] heightReady = {false};
        reasoningWv.getEngine().documentProperty().addListener((obs, old, doc) -> {
            if (doc != null) {
                Platform.runLater(() -> measureWebViewHeightWithRetry(reasoningWv, measuredHeight, heightReady, 0));
            }
        });

        reasoningWv.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            e.consume();
            javafx.event.Event.fireEvent(reasoningBlock, e.copyFor(reasoningBlock, reasoningBlock));
        });

        reasoningBlock.getChildren().add(reasoningWv);

        reasoningHeader.setOnMouseClicked(e -> {
            boolean expand = reasoningWv.getMaxHeight() == 0;
            if (expand) {
                if (!heightReady[0]) {
                    forceMeasureHeight(reasoningWv, measuredHeight, heightReady);
                }
                double h = (heightReady[0] && measuredHeight[0] > 0) ? measuredHeight[0] : 200;
                reasoningWv.setPrefHeight(h);
                reasoningWv.setMaxHeight(h);
                toggleArrow.setText("\u25BE");
            } else {
                reasoningWv.setPrefHeight(0);
                reasoningWv.setMaxHeight(0);
                toggleArrow.setText("\u25B8");
            }
        });

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        row.getChildren().addAll(avatar, reasoningBlock, rightSpacer);

        Platform.runLater(() -> {
            javafx.scene.Scene scene = reasoningBlock.getScene();
            if (scene != null) {
                double w = Math.min(600, scene.getWidth() - 256 - 32 - 44);
                if (w > 0) {
                    reasoningWv.setPrefWidth(w);
                    reasoningWv.setMaxWidth(w);
                }
            }
            reasoningWv.getEngine().load(toDataUri(reasoningHtml));
        });

        return row;
    }

    /**
     * 显示加载指示器
     */
    private void showLoadingIndicator() {
        if (loadingIndicator == null) {
            loadingIndicator = new Label("加载历史消息中...");
            loadingIndicator.setStyle("-fx-padding: 8px; -fx-text-fill: rgba(0,0,0,0.5); -fx-font-size: 12px;");
            loadingIndicator.setAlignment(Pos.CENTER);
            loadingIndicator.setMaxWidth(Double.MAX_VALUE);
        }
        if (!messageContainer.getChildren().contains(loadingIndicator)) {
            // 在欢迎消息之后插入
            int insertIndex = welcomeVisible ? 1 : 0;
            messageContainer.getChildren().add(insertIndex, loadingIndicator);
        }
    }

    /**
     * 隐藏加载指示器
     */
    private void hideLoadingIndicator() {
        if (loadingIndicator != null) {
            messageContainer.getChildren().remove(loadingIndicator);
        }
    }

    public void clearMessages() {
        messageContainer.getChildren().clear();
        nodesTrimmed = 0;
        fileDiffBadge.clearFiles();
        thinkingPlaceholder = null;
        lastStreamingBubble = null;
        streamingReasoningBlocks.clear();
        loadingIndicator = null;
        if (thinkingAnimation != null) {
            thinkingAnimation.stop();
            thinkingAnimation = null;
        }
        addWelcomeMessage();
        autoScroll = true;
        scrollToBottomBtn.setVisible(false);
    }

    public void loadMessages(java.util.List<java.util.Map<String, Object>> history) {
        clearMessages();
        if (history == null) return;

        // 保存完整历史消息
        this.fullHistory = new java.util.ArrayList<>(history);
        this.hasMoreHistory = true;
        this.isLoadingMore = false;

        java.util.Map<String, ToolCallCard> cardById = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> filePathByCallId = new java.util.LinkedHashMap<>();

        // 限制加载数量：只加载最后 MAX_VISIBLE_NODES 条非系统消息，避免一次性创建过多 WebView
        int systemCount = 0;
        for (var msg : history) {
            if ("system".equals(String.valueOf(msg.getOrDefault("role", "")))) systemCount++;
        }
        int nonSystemTotal = history.size() - systemCount;
        int startIndex = Math.max(0, history.size() - MAX_VISIBLE_NODES - systemCount);
        nodesTrimmed = Math.max(0, nonSystemTotal - MAX_VISIBLE_NODES);
        this.displayStartIndex = startIndex;

        int msgIndex = 0;
        for (java.util.Map<String, Object> msg : history) {
            msgIndex++;
            if (msgIndex <= startIndex) continue;  // 跳过早期消息
            try {
                String role = String.valueOf(msg.getOrDefault("role", ""));

                if ("system".equals(role)) continue;

                if ("user".equals(role)) {
                    String text = extractTextContent(msg.get("content"));
                    if (text != null && !text.isBlank()) {
                        addUserMessage(text);
                    }
                } else if ("assistant".equals(role)) {
                    cardById.clear();

                    String content = extractTextContent(msg.get("content"));
                    String reasoning = msg.get("reasoning_content") instanceof String s && !s.isBlank() ? s : null;
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> toolCalls =
                        (java.util.List<java.util.Map<String, Object>>) msg.get("tool_calls");
                    boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

                    if (reasoning != null && !hasToolCalls) {
                        if (content != null && !content.isBlank()) {
                            addAssistantMessageWithReasoning(reasoning, content);
                        } else {
                            addReasoningBlock(reasoning);
                        }
                    } else if (hasToolCalls) {
                        // 推理（先于文本，与 live chat 顺序一致）
                        if (reasoning != null) {
                            addReasoningBlock(reasoning);
                        }
                        // 伴随工具调用的文本
                        if (content != null && !content.isBlank()) {
                            addAssistantMessage(content);
                        }
                        // 工具卡片
                        filePathByCallId.clear();
                        for (var tc : toolCalls) {
                            String tn = extractToolName(tc);
                            String params = formatToolParams(tn, tc);
                            ToolCallCard card = addToolCallCard(tn, "running", params, false);
                            String callId = (String) tc.get("id");
                            cardById.put(callId, card);
                            // 从参数提取 file_path（比解析结果文本更可靠）
                            if ("edit_file".equals(tn) || "write_file".equals(tn)) {
                                String fp = extractFilePathFromArgs(tc);
                                if (fp != null) filePathByCallId.put(callId, fp);
                            }
                        }
                    } else if (content != null && !content.isBlank()) {
                        addAssistantMessage(content);
                    }
                } else if ("tool".equals(role)) {
                    String tcId = msg.get("tool_call_id") instanceof String s ? s : null;
                    String toolName = msg.get("name") instanceof String s ? s : null;
                    String result = extractTextContent(msg.get("content"));
                    if (tcId != null && result != null) {
                        ToolCallCard card = cardById.get(tcId);
                        if (card != null) {
                            card.setStatus("completed");
                            // edit_file/write_file: 恢复时也显示结构化对比/回滚按钮
                            if (("edit_file".equals(toolName) || "write_file".equals(toolName))
                                    && result != null && !result.isBlank()) {
                                String filePath = extractFilePath(result);
                                // 结果中没提取到时，回退到工具调用参数中的 file_path
                                if (filePath == null || filePath.isBlank()) {
                                    filePath = filePathByCallId.get(tcId);
                                }
                                if (filePath != null && !filePath.isBlank()) {
                                    agent.tool.file.FileBackupManager fbm = fileDiffBadge.getBackupManager();
                                    int[] stats = parseDiff(result);
                                    card.setFileEditResult(filePath, stats[0], stats[1], fbm, null);
                                } else {
                                    card.addResult(result);
                                }
                            } else if ("TodoWrite".equals(toolName)) {
                                fileDiffBadge.updateTodoFromJson(result);
                                card.addStructuredContent(
                                    gui.ui.components.TodoResultView.build(result));
                            } else if ("AskUserQuestion".equals(toolName) && result.contains("\"questions\"")) {
                                card.addStructuredContent(
                                    gui.ui.components.AskQuestionResultView.build(result));
                            } else {
                                card.addResult(result);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 单条消息恢复失败不阻断整个历史加载流程
                // （如 JavaFX 内部文本布局异常等不可控错误）
                System.err.println("[ChatPage] 跳过第 " + msgIndex + " 条消息 (role="
                    + msg.get("role") + "): " + e);
            }
        }
        Platform.runLater(() -> {
            scrollPane.setVvalue(1.0);
        });
    }

    private static String extractTextContent(Object contentObj) {
        if (contentObj instanceof String s) return s;
        if (contentObj instanceof java.util.List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof java.util.Map<?, ?> m && "text".equals(m.get("type"))) {
                Object text = m.get("text");
                return text instanceof String s ? s : String.valueOf(text);
            }
        }
        return contentObj != null ? String.valueOf(contentObj) : null;
    }

    @SuppressWarnings("unchecked")
    private static String extractToolName(java.util.Map<String, Object> tc) {
        Object fn = tc.get("function");
        if (fn instanceof java.util.Map<?, ?> f) {
            Object name = f.get("name");
            return name instanceof String s ? s : String.valueOf(name);
        }
        return "tool";
    }

    private static String formatToolParams(String toolName, java.util.Map<String, Object> tc) {
        Object fn = tc.get("function");
        if (!(fn instanceof java.util.Map<?, ?> f)) return "";
        Object argsObj = f.get("arguments");
        if (!(argsObj instanceof String args) || args.isBlank()) return "";

        if ("AskUserQuestion".equals(toolName)) {
            return formatAskUserQuestionParams(args);
        }
        if ("TodoWrite".equals(toolName)) {
            return formatTodoWriteParams(args);
        }

        return formatGenericParams(args);
    }

    private static String formatAskUserQuestionParams(String args) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.util.Map<String, Object> m = gson.fromJson(args, java.util.Map.class);
            @SuppressWarnings("unchecked")
            java.util.List<java.util.Map<String, Object>> questions =
                (java.util.List<java.util.Map<String, Object>>) m.get("questions");
            if (questions == null || questions.isEmpty()) return "询问用户";
            StringBuilder sb = new StringBuilder();
            for (var q : questions) {
                if (sb.length() > 0) sb.append("; ");
                String h = (String) q.getOrDefault("header", "");
                sb.append(h.isEmpty() ? "询问" : h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "询问用户";
        }
    }

    private static String formatTodoWriteParams(String args) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.util.Map<String, Object> m = gson.fromJson(args, java.util.Map.class);
            @SuppressWarnings("unchecked")
            java.util.List<?> todos = (java.util.List<?>) m.get("todos");
            int count = todos != null ? todos.size() : 0;
            return count > 0 ? count + " 项任务" : "清空任务";
        } catch (Exception e) {
            return "更新任务";
        }
    }

    private static String formatGenericParams(String args) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.util.Map<String, Object> argsMap = gson.fromJson(args, java.util.Map.class);
            StringBuilder sb = new StringBuilder();
            for (var entry : argsMap.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                String v = String.valueOf(entry.getValue());
                if (v.length() > 60) v = v.substring(0, 60) + "...";
                sb.append(entry.getKey()).append("=").append(v);
            }
            return sb.toString();
        } catch (Exception ignored) {}
        return args.length() > 100 ? args.substring(0, 100) + "..." : args;
    }

    /** 从 tool 结果文本中提取文件路径 */
    private static String extractFilePath(String result) {
        if (result == null || result.isBlank()) return null;
        // write_file: "Wrote contents to D:\path\to\file"
        // edit_file: "Replace file succeeded, the file D:\path has been updated"
        for (String line : result.split("\n")) {
            line = line.trim();
            if (line.startsWith("Wrote contents to ")) {
                return line.substring("Wrote contents to ".length()).trim();
            }
            if (line.startsWith("Wrote to ")) {
                return line.substring("Wrote to ".length()).trim();
            }
            if (line.contains("the file ") && line.contains(" has been updated")) {
                int s = line.indexOf("the file ") + 9;
                int e = line.indexOf(" has been updated");
                return line.substring(s, e).trim();
            }
            // diff header with absolute path
            if (line.startsWith("+++ b/")) {
                String p = line.substring(6).trim();
                if (p.length() > 2 && p.charAt(1) == ':') return p;
            }
            if (line.startsWith("--- a/")) {
                String p = line.substring(6).trim();
                if (p.length() > 2 && p.charAt(1) == ':') return p;
            }
        }
        return null;
    }

    /** 从工具调用参数中提取 file_path */
    private static String extractFilePathFromArgs(java.util.Map<String, Object> tc) {
        Object fn = tc.get("function");
        if (!(fn instanceof java.util.Map<?, ?> f)) return null;
        Object argsObj = f.get("arguments");
        if (!(argsObj instanceof String args) || args.isBlank()) return null;
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.util.Map<String, Object> m = gson.fromJson(args, java.util.Map.class);
            Object fp = m.get("file_path");
            if (fp instanceof String s && !s.isBlank()) return s;
        } catch (Exception ignored) {}
        return null;
    }

    /** 解析 unified diff 统计 [added, removed] */
    private static int[] parseDiff(String result) {
        int added = 0, removed = 0;
        if (result == null || result.isBlank()) return new int[]{added, removed};
        for (String line : result.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) added++;
            else if (line.startsWith("-") && !line.startsWith("---")) removed++;
        }
        return new int[]{added, removed};
    }

    private ProjectPopover projectPopover;
    private ProjectRegistry projectRegistry;
    private Path workspacePath;
    private BackendBridge backendBridge;

    /** 设置后端桥接引用 */
    public void setBackendBridge(BackendBridge backendBridge) {
        this.backendBridge = backendBridge;
        // 传递给 ChatInput，使 CompletionPopup 能获取 SkillsLoader 列出技能
        if (chatInput != null) {
            chatInput.setBackendBridge(backendBridge);
        }
    }

    /** 设置项目注册信息并初始化 Popover */
    public void setProjectInfo(ProjectRegistry registry, Path workspacePath) {
        this.projectRegistry = registry;
        this.workspacePath = workspacePath;

        if (projectPopover == null) {
            projectPopover = new ProjectPopover();
        }

        chatInput.setProjectRegistry(registry, workspacePath);

        // 检测开发者模式
        boolean devMode = backendBridge != null
            && backendBridge.getConfig() != null
            && backendBridge.getConfig().getAgents().getDefaults().isDevelopment();

        ProjectStatusBadge badge = chatInput.getProjectBadge();
        badge.setDeveloperMode(devMode);

        badge.setOnClick(() -> {
            if (projectPopover.isShowing()) {
                projectPopover.hide();
                return;
            }

            // 开发者模式：始终弹出项目绑定 Popover（不允许打开文件夹）
            if (badge.isDeveloperMode()) {
                projectPopover.show(badge, this.projectRegistry, () -> {
                    chatInput.refreshProjectBadge(this.projectRegistry, this.workspacePath);
                });
                return;
            }

            String mode = badge.getCurrentMode();
            if ("workspace".equals(mode)) {
                showWorkspaceMenu(badge, this.workspacePath);
            } else {
                // 使用实例字段而非闭包捕获的参数，确保 popover 始终展示最新 registry
                projectPopover.show(badge, this.projectRegistry, () -> {
                    chatInput.refreshProjectBadge(this.projectRegistry, this.workspacePath);
                });
            }
        });
    }

    /** 刷新项目徽标，同步 Popover 内容 */
    public void refreshProjectBadge() {
        if (projectRegistry != null && backendBridge != null) {
            // 从 backendBridge 获取最新的 ProjectRegistry 引用
            this.projectRegistry = backendBridge.getProjectRegistry();
            this.workspacePath = backendBridge.getConfig().getWorkspacePath();
            chatInput.refreshProjectBadge(projectRegistry, workspacePath);
            // Popover 正在显示时同步刷新列表
            if (projectPopover != null && projectPopover.isShowing()) {
                projectPopover.refreshList();
            }
        }
    }

    /** 普通用户：显示工作空间操作菜单 */
    private void showWorkspaceMenu(ProjectStatusBadge badge, Path wsPath) {
        if (wsPath == null) return;
        ContextMenu menu = new ContextMenu();

        MenuItem copyItem = new MenuItem("\uD83D\uDCCB 复制路径");
        copyItem.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(wsPath.toString());
            Clipboard.getSystemClipboard().setContent(content);
        });

        MenuItem openItem = new MenuItem("\uD83D\uDCC2 打开文件夹");
        openItem.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().open(wsPath.toFile());
            } catch (Exception ex) {
                // ignore
            }
        });

        menu.getItems().addAll(copyItem, openItem);
        menu.show(badge, Side.TOP, 0, 0);
    }
}
