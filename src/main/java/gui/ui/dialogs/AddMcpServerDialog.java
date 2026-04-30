package gui.ui.dialogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class AddMcpServerDialog extends Stage {

    private static final String TAB_ACTIVE_STYLE = "-fx-background-color: white; -fx-background-radius: 6px; -fx-text-fill: #111; -fx-font-size: 13px; -fx-font-weight: 500; -fx-border: none; -fx-cursor: hand;";
    private static final String TAB_INACTIVE_STYLE = "-fx-background-color: transparent; -fx-background-radius: 6px; -fx-text-fill: #666; -fx-font-size: 13px; -fx-font-weight: 400; -fx-border: none; -fx-cursor: hand;";

    private final TextField nameField;
    private final TextField rawNameField;
    private final TextField commandField;
    private final TextArea rawJsonField;
    private final Label jsonErrorLabel;
    private boolean confirmed = false;
    private boolean isRawMode = true;

    public AddMcpServerDialog(Stage owner) {
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.TRANSPARENT);

        setTitle("添加 MCP 服务器");

        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: white; -fx-background-radius: 16px; -fx-border-color: rgba(0, 0, 0, 0.1); -fx-border-radius: 16px; -fx-border-width: 1px;");
        root.setPrefWidth(520);

        // 标题
        Label title = new Label("添加 MCP 服务器");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: 500;");

        // 选项卡按钮
        HBox tabBar = new HBox(0);
        tabBar.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 8px; -fx-padding: 2px;");

        Button tabFormBtn = new Button("表单模式");
        tabFormBtn.setStyle(TAB_INACTIVE_STYLE);
        tabFormBtn.setPrefHeight(32);

        Button tabRawBtn = new Button("RAW 模式");
        tabRawBtn.setStyle(TAB_ACTIVE_STYLE);
        tabRawBtn.setPrefHeight(32);

        tabBar.getChildren().addAll(tabFormBtn, tabRawBtn);

        // 表单模式面板
        VBox formPanel = new VBox(16);

        VBox nameBox = new VBox(4);
        Label nameLabel = new Label("服务器名称");
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");

        nameField = new TextField();
        nameField.getStyleClass().add("input-field");
        nameField.setPrefHeight(40);
        nameField.setPromptText("例如: filesystem");

        nameBox.getChildren().addAll(nameLabel, nameField);

        VBox commandBox = new VBox(4);
        Label commandLabel = new Label("启动命令");
        commandLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");

        commandField = new TextField();
        commandField.getStyleClass().add("input-field");
        commandField.setPrefHeight(40);
        commandField.setPromptText("npx -y @modelcontextprotocol/server-filesystem");

        commandBox.getChildren().addAll(commandLabel, commandField);

        formPanel.getChildren().addAll(nameBox, commandBox);

        // RAW 模式面板
        VBox rawPanel = new VBox(16);
        rawPanel.setVisible(true);
        rawPanel.setManaged(true);

        VBox rawNameBox = new VBox(4);
        Label rawNameLabel = new Label("服务器名称");
        rawNameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");

        rawNameField = new TextField();
        rawNameField.getStyleClass().add("input-field");
        rawNameField.setPrefHeight(40);
        rawNameField.setPromptText("例如: filesystem");

        rawNameBox.getChildren().addAll(rawNameLabel, rawNameField);

        VBox jsonBox = new VBox(4);
        Label jsonLabel = new Label("JSON 配置");
        jsonLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");

        rawJsonField = new TextArea();
        rawJsonField.getStyleClass().add("input-field");
        rawJsonField.setPrefHeight(200);
        rawJsonField.setPromptText("{\n  \"command\": \"npx\",\n  \"args\": [\"-y\", \"...\"],\n  \"env\": {}\n}");

        jsonErrorLabel = new Label();
        jsonErrorLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #dc2626;");
        jsonErrorLabel.setVisible(false);
        jsonErrorLabel.setManaged(false);

        jsonBox.getChildren().addAll(jsonLabel, rawJsonField, jsonErrorLabel);
        rawPanel.getChildren().addAll(rawNameBox, jsonBox);

        // 表单面板默认隐藏
        formPanel.setVisible(false);
        formPanel.setManaged(false);

        // 选项卡切换逻辑
        tabFormBtn.setOnAction(e -> {
            isRawMode = false;
            tabFormBtn.setStyle(TAB_ACTIVE_STYLE);
            tabRawBtn.setStyle(TAB_INACTIVE_STYLE);
            formPanel.setVisible(true);
            formPanel.setManaged(true);
            rawPanel.setVisible(false);
            rawPanel.setManaged(false);
            jsonErrorLabel.setVisible(false);
            jsonErrorLabel.setManaged(false);
        });

        tabRawBtn.setOnAction(e -> {
            isRawMode = true;
            tabRawBtn.setStyle(TAB_ACTIVE_STYLE);
            tabFormBtn.setStyle(TAB_INACTIVE_STYLE);
            formPanel.setVisible(false);
            formPanel.setManaged(false);
            rawPanel.setVisible(true);
            rawPanel.setManaged(true);
            jsonErrorLabel.setVisible(false);
            jsonErrorLabel.setManaged(false);
        });

        // 按钮
        HBox buttonBox = new HBox(8);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("pill-button");
        cancelBtn.setPrefHeight(40);
        cancelBtn.setOnAction(e -> close());

        Button confirmBtn = new Button("添加");
        confirmBtn.getStyleClass().add("pill-button");
        confirmBtn.setPrefHeight(40);
        confirmBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-background-radius: 999px; -fx-border-radius: 999px; -fx-border-color: #3b82f6; -fx-border-width: 1px;");
        confirmBtn.setOnAction(e -> {
            if (isRawMode) {
                String json = rawJsonField.getText();
                if (json == null || json.isBlank()) {
                    jsonErrorLabel.setText("请粘贴 JSON 配置");
                    jsonErrorLabel.setVisible(true);
                    jsonErrorLabel.setManaged(true);
                    return;
                }
                try {
                    new ObjectMapper().readTree(json);
                } catch (Exception ex) {
                    jsonErrorLabel.setText("JSON 格式错误，请检查后重试");
                    jsonErrorLabel.setVisible(true);
                    jsonErrorLabel.setManaged(true);
                    return;
                }
                jsonErrorLabel.setVisible(false);
                jsonErrorLabel.setManaged(false);
                confirmed = true;
                close();
            } else {
                confirmed = true;
                close();
            }
        });

        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);

        root.getChildren().addAll(title, tabBar, formPanel, rawPanel, buttonBox);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/gui/ui/styles/main.css").toExternalForm());
        setScene(scene);

        // 默认选中 RAW 模式
        Platform.runLater(() -> tabRawBtn.fire());
    }

    public String getServerName() {
        return isRawMode ? rawNameField.getText() : nameField.getText();
    }

    public String getCommand() {
        return commandField.getText();
    }

    public String getRawJson() {
        return rawJsonField.getText();
    }

    public boolean isRawMode() {
        return isRawMode;
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
