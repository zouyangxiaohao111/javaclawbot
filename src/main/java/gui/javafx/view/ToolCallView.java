package gui.javafx.view;

import gui.javafx.model.ToolCallInfo;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

public class ToolCallView extends VBox {
    private ToolCallInfo toolCallInfo;

    public ToolCallView(ToolCallInfo toolCallInfo) {
        this.toolCallInfo = toolCallInfo;
        setupUI();
    }

    private void setupUI() {
        setSpacing(8);
        setPadding(new Insets(12));
        getStyleClass().add("message-bubble-tool");

        Label titleLabel = new Label("Tool: " + toolCallInfo.getToolName());
        titleLabel.setStyle("-fx-font-weight: bold;");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(20, 20);

        getChildren().addAll(titleLabel, progress);
    }
}