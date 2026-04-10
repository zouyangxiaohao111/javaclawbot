package gui.javafx.view;

import gui.javafx.model.ChatMessage;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MessageBubbleView extends VBox {
    private ChatMessage message;

    public MessageBubbleView(ChatMessage message) {
        this.message = message;
        setupUI();
    }

    private void setupUI() {
        setSpacing(4);
        setPadding(new Insets(8));

        Label contentLabel = new Label(message.getContent());
        contentLabel.setWrapText(true);

        if (message.getType() == ChatMessage.MessageType.USER) {
            getStyleClass().add("message-bubble-user");
        } else if (message.getType() == ChatMessage.MessageType.AI) {
            getStyleClass().add("message-bubble-ai");
        } else {
            getStyleClass().add("message-bubble-system");
        }

        getChildren().add(contentLabel);
    }
}