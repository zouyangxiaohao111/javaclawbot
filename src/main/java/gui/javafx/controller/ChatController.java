package gui.javafx.controller;

import gui.javafx.model.ChatMessage;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public class ChatController {
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messageContainer;

    private final List<ChatMessage> messages = new ArrayList<>();

    public void initialize() {
        scrollPane.setFitToWidth(true);
        messageContainer.setSpacing(12);
        messageContainer.setPadding(new Insets(18));
        messageContainer.setFillWidth(true);
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);

        Label messageLabel = new Label(message.getContent());
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(720);

        switch (message.getType()) {
            case USER -> messageLabel.getStyleClass().add("message-bubble-user");
            case AI -> messageLabel.getStyleClass().add("message-bubble-ai");
            default -> messageLabel.getStyleClass().add("message-bubble-system");
        }

        messageContainer.getChildren().add(messageLabel);
        scrollPane.layout();
        scrollPane.setVvalue(1.0);
    }

    public void clearMessages() {
        messages.clear();
        messageContainer.getChildren().clear();
    }
}