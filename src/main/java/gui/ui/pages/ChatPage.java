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

        REASONING_HTML_TEMPLATE = "<!DOCTYPE html><html style='height:100%;background:rgba(0,0,0,0.03);'>"
            + "<head><meta charset='UTF-8'><style>"
            + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
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
        // 宽度绑定：先确定宽度再加载内容，避免窄宽度下测量到错误高度
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
            if (expand && heightReady[0]) {
                reasoningWv.setPrefHeight(measuredHeight[0]);
                reasoningWv.setMaxHeight(measuredHeight[0]);
            } else if (!expand) {
                reasoningWv.setPrefHeight(0);
                reasoningWv.setMaxHeight(0);
            }
            toggleArrow.setText(expand ? "\u25BE" : "\u25B8");
        });

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        row.getChildren().addAll(avatar, reasoningBlock, rightSpacer);
        messageContainer.getChildren().add(row);
        smartScrollToBottom();

        // 宽度确定后再加载内容
        reasoningBlock.sceneProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.scene.Scene> obs,
                                javafx.scene.Scene oldScene, javafx.scene.Scene newScene) {
                if (newScene != null) {
                    reasoningBlock.sceneProperty().removeListener(this);
                    // 按实际可用宽度设置 WebView 宽度
                    double w = Math.min(600, newScene.getWidth() - 256 - 32 - 44);
                    reasoningWv.setPrefWidth(w);
                    reasoningWv.setMaxWidth(w);
                    Platform.runLater(() -> reasoningWv.getEngine().loadContent(reasoningHtml));
                }
            }
        });
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
                        }
                        ready[0] = true;
                        return;
                    }
                    result[0] = height;
                    if (attempt >= 5) {
                        // 达到最大重试次数，使用当前测量值
                        ready[0] = true;
                        return;
                    }
                    // 继续重试
                    javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(100));
                    final int next = attempt + 1;
                    delay.setOnFinished(ev -> measureWebViewHeightWithRetry(wv, result, ready, next));
                    delay.play();
                    return;
                }
            }
            // 高度为 0，继续重试
            if (attempt < 5) {
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(100));
                final int next = attempt + 1;
                delay.setOnFinished(ev -> measureWebViewHeightWithRetry(wv, result, ready, next));
                delay.play();
            }
        } catch (Exception ignored) {}
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

        java.util.Map<String, ToolCallCard> cardById = new java.util.LinkedHashMap<>();

        for (java.util.Map<String, Object> msg : history) {
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
                    // clean 文本（伴随工具调用）
                    if (content != null && !content.isBlank()) {
                        addAssistantMessage(content);
                    }
                    // 推理
                    if (reasoning != null) {
                        addReasoningBlock(reasoning);
                    }
                    // 工具卡片
                    for (var tc : toolCalls) {
                        String tn = extractToolName(tc);
                        String params = formatToolParams(tn, tc);
                        ToolCallCard card = addToolCallCard(tn, "completed", params, false);
                        cardById.put((String) tc.get("id"), card);
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
                        if ("TodoWrite".equals(toolName)) {
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
        }
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
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
}
