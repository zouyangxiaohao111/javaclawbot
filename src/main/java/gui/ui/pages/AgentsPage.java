package gui.ui.pages;

import config.agent.AgentDefaults;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class AgentsPage extends VBox {

    private VBox agentList;
    private gui.ui.BackendBridge backendBridge;

    public AgentsPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(16);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("Agent 管理");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("管理和配置 AI Agent");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        // Agent 列表
        agentList = new VBox(12);
        agentList.setMaxWidth(800);

        // 示例卡片
        String[][] agents = {
            {"code-reviewer", "代码审查 Agent", "已配置"},
            {"test-generator", "测试生成 Agent", "已配置"},
            {"data-analyst", "数据分析 Agent", "未配置"}
        };

        for (String[] agent : agents) {
            VBox card = createAgentCard(agent[0], agent[1], agent[2], false);
            agentList.getChildren().add(card);
        }

        Button addBtn = new Button("+ 配置 Agent");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);
        addBtn.setOnAction(e -> showAgentDialog());

        content.getChildren().addAll(titleBox, agentList, addBtn);
        VBox.setMargin(addBtn, new Insets(24, 0, 0, 0));

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createAgentCard(String name, String description, String status, boolean clickable) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        if (clickable) {
            card.setStyle(card.getStyle() + "; -fx-cursor: hand;");
            card.setOnMouseClicked(e -> showAgentDialog());
        }

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("\uD83D\uDC64");
        icon.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 10px; -fx-pref-width: 40px; -fx-pref-height: 40px; -fx-alignment: center;");
        icon.setMinSize(40, 40);

        VBox infoBox = new VBox(2);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(nameLabel, descLabel);

        Label statusBadge = new Label(status);
        statusBadge.getStyleClass().addAll("status-badge", "已配置".equals(status) ? "running" : "error");

        header.getChildren().addAll(icon, infoBox, statusBadge);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        card.getChildren().add(header);
        return card;
    }

    public void setBackendBridge(gui.ui.BackendBridge bridge) {
        this.backendBridge = bridge;
        refresh();
    }

    public void refresh() {
        if (backendBridge == null) return;
        agentList.getChildren().clear();

        config.Config cfg = backendBridge.getConfig();
        AgentDefaults defaults = cfg.getAgents().getDefaults();
        String model = defaults.getModel() != null ? defaults.getModel() : "未配置";
        String provider = cfg.getProviderName(model);
        if (provider == null) provider = "auto";

        agentList.getChildren().add(createAgentCard("default",
            "模型: " + model + " · 提供方: " + provider
                + " · 最大迭代: " + defaults.getMaxToolIterations(),
            "已配置", true));
    }

    private void showAgentDialog() {
        config.Config cfg = backendBridge.getConfig();
        AgentDefaults defaults = cfg.getAgents().getDefaults();

        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.initModality(Modality.APPLICATION_MODAL);

        VBox root = new VBox(14);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #f1ede1; -fx-background-radius: 16px;"
            + " -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 16px; -fx-border-width: 1px;");

        Label title = new Label("配置 Agent");
        title.setStyle("-fx-font-family: Georgia; -fx-font-size: 24px; -fx-text-fill: rgba(0,0,0,0.7);");

        String fieldStyle = "-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 14px; -fx-padding: 10px 14px;";

        TextField modelField = new TextField(defaults.getModel() != null ? defaults.getModel() : "");
        modelField.setPromptText("默认模型"); modelField.setStyle(fieldStyle); modelField.setPrefHeight(40);

        TextField providerField = new TextField(defaults.getProvider() != null ? defaults.getProvider() : "");
        providerField.setPromptText("默认提供方"); providerField.setStyle(fieldStyle); providerField.setPrefHeight(40);

        TextField maxIterField = new TextField(String.valueOf(defaults.getMaxToolIterations()));
        maxIterField.setPromptText("最大工具迭代次数"); maxIterField.setStyle(fieldStyle); maxIterField.setPrefHeight(40);

        TextField memWindowField = new TextField(String.valueOf(defaults.getMemoryWindow()));
        memWindowField.setPromptText("记忆窗口大小"); memWindowField.setStyle(fieldStyle); memWindowField.setPrefHeight(40);

        TextField timeoutField = new TextField(String.valueOf(defaults.getTimeoutSeconds()));
        timeoutField.setPromptText("超时（秒）"); timeoutField.setStyle(fieldStyle); timeoutField.setPrefHeight(40);

        TextField maxConcurrentField = new TextField(String.valueOf(defaults.getMaxConcurrent()));
        maxConcurrentField.setPromptText("最大并发"); maxConcurrentField.setStyle(fieldStyle); maxConcurrentField.setPrefHeight(40);

        CheckBox devCheck = new CheckBox("开发模式");
        devCheck.setSelected(defaults.isDevelopment());
        devCheck.setStyle("-fx-font-size: 13px;");

        CheckBox autoCompactCheck = new CheckBox("自动压缩上下文");
        autoCompactCheck.setSelected(defaults.isAutoCompactEnabled());
        autoCompactCheck.setStyle("-fx-font-size: 13px;");

        HBox btnRow = new HBox(12);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("pill-button");
        cancelBtn.setPrefHeight(36);
        cancelBtn.setOnAction(e -> dialog.close());
        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().addAll("pill-button", "selected");
        saveBtn.setPrefHeight(36);
        saveBtn.setOnAction(e -> {
            defaults.setModel(modelField.getText());
            defaults.setProvider(providerField.getText());
            try { defaults.setMaxToolIterations(Integer.parseInt(maxIterField.getText())); } catch (NumberFormatException ignored) {}
            try { defaults.setMemoryWindow(Integer.parseInt(memWindowField.getText())); } catch (NumberFormatException ignored) {}
            try { defaults.setTimeoutSeconds(Integer.parseInt(timeoutField.getText())); } catch (NumberFormatException ignored) {}
            try { defaults.setMaxConcurrent(Integer.parseInt(maxConcurrentField.getText())); } catch (NumberFormatException ignored) {}
            defaults.setDevelopment(devCheck.isSelected());
            defaults.setAutoCompactEnabled(autoCompactCheck.isSelected());
            try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
            dialog.close();
            Platform.runLater(() -> refresh());
        });
        btnRow.getChildren().addAll(cancelBtn, saveBtn);

        root.getChildren().addAll(title, modelField, providerField, maxIterField, memWindowField,
            timeoutField, maxConcurrentField, devCheck, autoCompactCheck, btnRow);

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.showAndWait();
    }
}
