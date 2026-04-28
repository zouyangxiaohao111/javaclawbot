package gui.ui.pages;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import gui.ui.components.ChatInput;
import gui.ui.components.MessageBubble;
import gui.ui.components.ToolCallCard;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
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

    /** 思考中占位气泡 */
    private HBox thinkingPlaceholder;
    /** 思考中动画 */
    private Timeline thinkingAnimation;

    private static final Parser REASONING_PARSER;
    private static final HtmlRenderer REASONING_RENDERER;
    private static final String REASONING_HTML_TEMPLATE;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, java.util.List.of(TablesExtension.create()));
        REASONING_PARSER = Parser.builder(options).build();
        REASONING_RENDERER = HtmlRenderer.builder(options).build();

        REASONING_HTML_TEMPLATE = "<!DOCTYPE html><html><head><meta charset='UTF-8'><style>"
            + "html{background:rgba(0,0,0,0.03);height:100%;overflow:hidden;}"
            + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
            + "font-size:13px;line-height:1.6;color:rgba(0,0,0,0.5);"
            + "background:rgba(0,0,0,0.03);margin:0;padding:0 16px 8px 16px;overflow:hidden;}"
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

        // StackPane 层叠消息区域和悬浮按钮
        scrollStack = new StackPane();
        scrollStack.getChildren().addAll(scrollPane, scrollToBottomBtn);
        StackPane.setAlignment(scrollToBottomBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(scrollToBottomBtn, new Insets(0, 24, 12, 0));

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
        MessageBubble bubble = new MessageBubble(MessageBubble.Role.ASSISTANT, content);
        bubble.setOnHeightAdjusted(this::scrollToBottom);
        messageContainer.getChildren().add(bubble);
        smartScrollToBottom();
    }

    public ToolCallCard addToolCallCard(String toolName, String status, String params) {
        return addToolCallCard(toolName, status, params, false);
    }

    public ToolCallCard addToolCallCard(String toolName, String status, String params, boolean startExpanded) {
        ToolCallCard card = new ToolCallCard(toolName, status, params, startExpanded);
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
                Platform.runLater(() -> {
                    try {
                        Object h = reasoningWv.getEngine().executeScript(
                            "(function(){return Math.max(document.body.scrollHeight,"
                            + "document.documentElement.scrollHeight);})()");
                        if (h instanceof Number) {
                            double height = ((Number) h).doubleValue();
                            if (height > 0) {
                                measuredHeight[0] = height;
                                heightReady[0] = true;
                            }
                        }
                    } catch (Exception ignored) {}
                });
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
            if (expand && heightReady[0]) {
                reasoningWv.setPrefHeight(measuredHeight[0]);
                reasoningWv.setMaxHeight(measuredHeight[0]);
            } else if (!expand) {
                reasoningWv.setPrefHeight(0);
                reasoningWv.setMaxHeight(0);
            }
            toggleArrow.setText(expand ? "\u25BE" : "\u25B8"); // ▾ : ▸
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
        Platform.runLater(() -> reasoningWv.getEngine().loadContent(reasoningHtml));
    }


    private void clearWelcomeIfNeeded() {
        if (!messageContainer.getChildren().isEmpty()
            && messageContainer.getChildren().get(0) instanceof VBox) {
            messageContainer.getChildren().clear();
        }
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

    public ChatInput getChatInput() {
        return chatInput;
    }

    public void setStatusText(String text) {
        chatInput.setStatusText(text);
    }

    public void clearMessages() {
        messageContainer.getChildren().clear();
        thinkingPlaceholder = null;
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
        for (java.util.Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            Object contentObj = msg.get("content");
            String content = contentObj instanceof String ? (String) contentObj : "";
            if (content == null || content.isBlank()) continue;
            if ("user".equals(role)) {
                addUserMessage(content);
            } else if ("assistant".equals(role)) {
                addAssistantMessage(content);
            }
        }
        // 历史加载后滚动到底部
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }
}
