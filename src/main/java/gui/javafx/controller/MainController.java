package gui.javafx.controller;

import config.Config;
import gui.javafx.service.ThemeManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainController {
    @FXML private Label statusLabel;
    @FXML private Label modelLabel;
    @FXML private Label mcpStatusLabel;
    @FXML private Label memoryUsageLabel;
    @FXML private Button themeToggleButton;

    // fx:include 根节点
    @FXML private VBox sidebar;
    @FXML private VBox chatArea;
    @FXML private VBox inputArea;

    // fx:include controller
    @FXML private SidebarController sidebarController;
    @FXML private ChatController chatAreaController;
    @FXML private InputController inputAreaController;

    private Config config;
    private ThemeManager themeManager;
    private String currentModel;

    public void initialize(Config config) {
        this.config = config;

        currentModel = resolveCurrentModel(config);
        updateStatus("Ready");
        updateModelLabel(currentModel);
        updateMcpLabel("0/0");
        updateMemoryLabel("0MB");

        if (inputAreaController != null && chatAreaController != null) {
            inputAreaController.setChatController(chatAreaController);
        }

        if (sidebarController != null) {
            sidebarController.setModelChangeListener(this::onModelChanged);
            sidebarController.initModelSelector(currentModel, buildModelOptions(currentModel));
        }
    }

    public void setThemeManager(ThemeManager themeManager) {
        this.themeManager = themeManager;
    }

    @FXML
    private void onGatewayClicked() {
        updateStatus("Gateway clicked");
    }

    @FXML
    private void onThemeToggleClicked() {
        if (themeManager != null) {
            themeManager.toggleTheme();
            themeToggleButton.setText("Dark".equals(themeToggleButton.getText()) ? "Light" : "Dark");
        }
    }

    public void onWindowShown() {
        if (sidebarController != null) {
            sidebarController.loadSessions();
        }
    }

    public void onWindowClosing() {
        // 这里如果后面要持久化当前模型，可在这里补保存逻辑
    }

    private void onModelChanged(String newModel) {
        if (newModel == null || newModel.isBlank()) {
            return;
        }
        currentModel = newModel;
        updateModelLabel(newModel);
        updateStatus("Model switched to " + newModel);

        // 这里只修 UI 同步；如果你后面要真正切后端模型，
        // 再把 newModel 写回运行时配置/agent manager 即可
    }

    private String resolveCurrentModel(Config config) {
        try {
            if (config != null
                    && config.getAgents() != null
                    && config.getAgents().getDefaults() != null
                    && config.getAgents().getDefaults().getModel() != null
                    && !config.getAgents().getDefaults().getModel().isBlank()) {
                return config.getAgents().getDefaults().getModel();
            }
        } catch (Exception ignored) {
        }
        return "Unknown";
    }

    private List<String> buildModelOptions(String currentModel) {
        Set<String> set = new LinkedHashSet<>();
        if (currentModel != null && !currentModel.isBlank()) {
            set.add(currentModel);
        }
        set.add("Claude");
        set.add("GPT-4");
        set.add("DeepSeek");
        set.add("MiniMax-M2.5");
        return new ArrayList<>(set);
    }

    private void updateStatus(String status) {
        if (statusLabel != null) {
            statusLabel.setText(status);
        }
    }

    private void updateModelLabel(String provider) {
        if (modelLabel != null && provider != null) {
            modelLabel.setText("Model: " + provider);
        }
    }

    private void updateMcpLabel(String text) {
        if (mcpStatusLabel != null) {
            mcpStatusLabel.setText("MCP: " + text);
        }
    }

    private void updateMemoryLabel(String text) {
        if (memoryUsageLabel != null) {
            memoryUsageLabel.setText("Memory: " + text);
        }
    }
}