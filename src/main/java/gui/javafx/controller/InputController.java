package gui.javafx.controller;

import cn.hutool.core.util.IdUtil;
import gui.javafx.model.ChatMessage;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

public class InputController {
    @FXML private TextArea inputTextArea;
    @FXML private Button sendButton;
    @FXML private Label attachmentCountLabel;

    private ChatController chatController;

    public void initialize() {
        updateSendButton(true);
    }

    public void setChatController(ChatController chatController) {
        this.chatController = chatController;
    }

    @FXML
    private void onSendClicked() {
        String text = inputTextArea.getText();
        if (text != null && !text.trim().isEmpty()) {
            if (chatController != null) {
                chatController.addMessage(new ChatMessage(IdUtil.fastSimpleUUID(), ChatMessage.MessageType.USER, text.trim()));
            }
            inputTextArea.clear();
        }
    }

    @FXML
    private void onInputKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
            event.consume();
            onSendClicked();
        }
    }

    @FXML
    private void onAddFileClicked() {
        // TODO 添加文件
    }

    @FXML
    private void onAddImageClicked() {
        // TODO 添加图片
    }

    public void updateSendButton(boolean enabled) {
        sendButton.setText(enabled ? "Send" : "Stop");
        sendButton.getStyleClass().setAll(enabled ? "primary-button" : "danger-button");
    }
}