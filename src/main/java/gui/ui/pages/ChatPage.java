package gui.ui.pages;

import gui.ui.components.ChatInput;
import gui.ui.components.MessageBubble;
import gui.ui.components.ToolCallCard;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class ChatPage extends VBox {

    private final VBox messageContainer;
    private final ScrollPane scrollPane;
    private final ChatInput chatInput;
    private final SplitPane splitPane;
    private final StackPane scrollStack;
    private final Label scrollToBottomBtn;

    private boolean autoScroll = true;

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
            double scrollBottom = val.doubleValue();
            // 内容未溢出时 vvalue 恒为 0，不显示按钮
            double viewHeight = scrollPane.getViewportBounds().getHeight();
            double contentHeight = messageContainer.getHeight();
            boolean canScroll = contentHeight > viewHeight + 1;
            boolean atBottom = scrollBottom >= 0.95;
            if (!canScroll || atBottom) {
                autoScroll = true;
                scrollToBottomBtn.setVisible(false);
            } else {
                autoScroll = false;
                scrollToBottomBtn.setVisible(true);
            }
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
            Platform.runLater(() -> scrollPane.setVvalue(1.0));
        }
    }

    /**
     * 强制滚动到底部（悬浮按钮点击时）
     */
    private void scrollToBottom() {
        autoScroll = true;
        scrollToBottomBtn.setVisible(false);
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    public ChatInput getChatInput() {
        return chatInput;
    }

    public void setStatusText(String text) {
        chatInput.setStatusText(text);
    }

    public void clearMessages() {
        // Remove all messages except the welcome VBox
        if (!messageContainer.getChildren().isEmpty()
            && messageContainer.getChildren().get(0) instanceof VBox) {
            // welcome is present, keep it
            javafx.scene.Node welcome = messageContainer.getChildren().get(0);
            messageContainer.getChildren().clear();
            messageContainer.getChildren().add(welcome);
        } else {
            messageContainer.getChildren().clear();
        }
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
