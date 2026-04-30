package gui.ui.pages;

import gui.ui.components.McpServerCard;
import gui.ui.dialogs.AddMcpServerDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Arrays;

public class McpPage extends VBox {

    private final VBox serverList;
    private gui.ui.BackendBridge backendBridge;

    public McpPage(Stage stage) {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(16);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        // 页面标题
        Label title = new Label("MCP 管理");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("管理 Model Context Protocol 服务器和工具");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        // 服务器列表
        serverList = new VBox(12);
        serverList.setMaxWidth(800);

        // 示例数据
        serverList.getChildren().addAll(
            new McpServerCard("filesystem", "npx -y @modelcontextprotocol/server-filesystem", true,
                Arrays.asList("read_file", "write_file", "list_directory", "delete_file"), null),
            new McpServerCard("fetch", "npx -y @modelcontextprotocol/server-fetch", true,
                Arrays.asList("web_fetch", "web_search"), null),
            new McpServerCard("database", "npx -y @modelcontextprotocol/server-sqlite", false,
                null, "无法启动 MCP 服务器 - 端口已被占用")
        );

        // 添加按钮
        Button addBtn = new Button("+ 添加 MCP 服务器");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);
        addBtn.setOnAction(e -> {
            AddMcpServerDialog dialog = new AddMcpServerDialog(stage);
            dialog.showAndWait();
            if (dialog.isConfirmed()) {
                String name = dialog.getServerName();
                try {
                    if (dialog.isRawMode()) {
                        backendBridge.addMcpServerRaw(name, dialog.getRawJson());
                    } else {
                        backendBridge.addMcpServer(name, dialog.getCommand());
                    }
                    refresh();
                } catch (Exception ex) {
                    System.err.println("添加 MCP 服务器失败: " + ex.getMessage());
                }
            }
        });

        content.getChildren().addAll(titleBox, serverList, addBtn);
        VBox.setMargin(addBtn, new Insets(24, 0, 0, 0));

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    public void setBackendBridge(gui.ui.BackendBridge bridge) {
        this.backendBridge = bridge;
        refresh();
    }

    private void refresh() {
        if (backendBridge == null) return;
        serverList.getChildren().clear();

        java.util.Map<String, config.mcp.MCPServerConfig> servers =
            backendBridge.getConfig().getTools().getMcpServers();
        for (java.util.Map.Entry<String, config.mcp.MCPServerConfig> entry : servers.entrySet()) {
            String name = entry.getKey();
            config.mcp.MCPServerConfig sc = entry.getValue();
            String cmd = sc.getCommand() != null && !sc.getCommand().isBlank()
                ? sc.getCommand() + " " + String.join(" ", sc.getArgs())
                : (sc.getUrl() != null ? sc.getUrl() : "");
            serverList.getChildren().add(
                new McpServerCard(name, cmd, sc.isEnable(), java.util.List.of(), null));
        }
    }
}
