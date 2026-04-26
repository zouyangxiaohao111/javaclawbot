package gui.ui.pages;

import gui.ui.components.ChatInput;
import gui.ui.components.MessageBubble;
import gui.ui.components.ToolCallCard;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class ChatPage extends VBox {

    private final VBox messageContainer;
    private final ScrollPane scrollPane;
    private final ChatInput chatInput;
    private final SplitPane splitPane;

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

        // 输入区域
        chatInput = new ChatInput();

        // SplitPane：支持拖拽调整输入框高度（类似微信）
        splitPane = new SplitPane();
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        splitPane.getItems().addAll(scrollPane, chatInput);
        splitPane.setDividerPosition(0, 0.75);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        getChildren().add(splitPane);

        // 添加欢迎消息
        addWelcomeMessage();
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
        clearWelcomeIfNeeded();

        MessageBubble bubble = new MessageBubble(MessageBubble.Role.USER, content);
        messageContainer.getChildren().add(bubble);
        scrollToBottom();
    }

    public void addAssistantMessage(String content) {
        MessageBubble bubble = new MessageBubble(MessageBubble.Role.ASSISTANT, content);
        messageContainer.getChildren().add(bubble);
        scrollToBottom();
    }

    public void addToolCallCard(String toolName, String status, String params) {
        addToolCallCard(toolName, status, params, false);
    }

    public void addToolCallCard(String toolName, String status, String params, boolean startExpanded) {
        ToolCallCard card = new ToolCallCard(toolName, status, params, startExpanded);
        messageContainer.getChildren().add(card);
        scrollToBottom();
    }

    private void clearWelcomeIfNeeded() {
        if (!messageContainer.getChildren().isEmpty()
            && messageContainer.getChildren().get(0) instanceof VBox) {
            messageContainer.getChildren().clear();
        }
    }

    private void scrollToBottom() {
        scrollPane.setVvalue(1.0);
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
    }
}
