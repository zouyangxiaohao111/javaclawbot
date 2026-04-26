package gui.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
            VBox card = createAgentCard(agent[0], agent[1], agent[2]);
            agentList.getChildren().add(card);
        }

        Button addBtn = new Button("+ 创建 Agent");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);

        content.getChildren().addAll(titleBox, agentList, addBtn);
        VBox.setMargin(addBtn, new Insets(24, 0, 0, 0));

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createAgentCard(String name, String description, String status) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

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

    private void refresh() {
        if (backendBridge == null) return;
        agentList.getChildren().clear();

        config.Config cfg = backendBridge.getConfig();
        config.agent.AgentDefaults defaults = cfg.getAgents().getDefaults();
        String model = defaults.getModel() != null ? defaults.getModel() : "未配置";
        String provider = cfg.getProviderName(model);
        if (provider == null) provider = "auto";

        agentList.getChildren().add(createAgentCard("default",
            "默认 Agent · 模型: " + model + " · 提供方: " + provider,
            "已配置"));
    }
}
