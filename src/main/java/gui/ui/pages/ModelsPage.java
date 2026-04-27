package gui.ui.pages;

import config.provider.ProviderConfig;
import gui.ui.components.ModelCard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class ModelsPage extends VBox {

    private VBox modelList;
    private gui.ui.BackendBridge backendBridge;

    public ModelsPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(16);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        // 页面标题
        Label title = new Label("模型管理");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("管理和配置 AI 模型");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        // 模型列表
        modelList = new VBox(12);
        modelList.setMaxWidth(800);

        modelList.getChildren().addAll(
            new ModelCard("Claude Sonnet 4", "anthropic", true, true),
            new ModelCard("GPT-4o", "openai", false, true),
            new ModelCard("DeepSeek V3", "deepseek", false, false)
        );

        // 添加按钮
        Button addBtn = new Button("+ 添加模型");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);
        addBtn.setOnAction(e -> showModelDialog(null));

        content.getChildren().addAll(titleBox, modelList, addBtn);
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
        modelList.getChildren().clear();

        config.Config cfg = backendBridge.getConfig();
        String defaultModel = cfg.getAgents().getDefaults().getModel();
        config.provider.ProvidersConfig provCfg = cfg.getProviders();

        String[] names = {"anthropic", "openai", "deepseek", "openrouter", "groq",
            "zhipu", "dashscope", "gemini", "moonshot", "minimax", "aihubmix",
            "siliconflow", "volcengine", "vllm", "githubCopilot"};
        String[] labels = {"Anthropic", "OpenAI", "DeepSeek", "OpenRouter", "Groq",
            "智谱 GLM", "阿里云 DashScope", "Google Gemini", "Moonshot", "MiniMax", "AIHubMix",
            "SiliconFlow", "火山引擎", "vLLM", "GitHub Copilot"};

        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            String label = labels[i];
            ProviderConfig pc = provCfg.getByName(n);
            if (pc == null) continue;
            boolean isConfigured = pc.getApiKey() != null && !pc.getApiKey().isBlank();
            providers.ProviderRegistry.ProviderSpec spec = providers.ProviderRegistry.findByName(n);
            boolean isOauth = spec != null && spec.isOauth();
            boolean isReady = isConfigured || isOauth;
            boolean isDefault = defaultModel != null && cfg.getProviderName(defaultModel) != null
                && cfg.getProviderName(defaultModel).equals(n);
            ModelCard card = new ModelCard(label, n, isDefault, isReady);
            // 点击卡片编辑
            final String providerName = n;
            card.setOnMouseClicked(ev -> showModelDialog(providerName));
            card.setStyle(card.getStyle() + "; -fx-cursor: hand;");
            modelList.getChildren().add(card);
        }
    }

    private void showModelDialog(String providerName) {
        config.Config cfg = backendBridge.getConfig();
        config.provider.ProvidersConfig provCfg = cfg.getProviders();
        boolean isEdit = providerName != null;

        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.initModality(Modality.APPLICATION_MODAL);

        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #f1ede1; -fx-background-radius: 16px;"
            + " -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 16px; -fx-border-width: 1px;");

        Label title = new Label(isEdit ? "编辑模型配置" : "添加模型配置");
        title.setStyle("-fx-font-family: Georgia; -fx-font-size: 24px; -fx-text-fill: rgba(0,0,0,0.7);");

        // Provider 选择
        ComboBox<String> providerCombo = new ComboBox<>();
        String[] allProviders = {"openai","anthropic","deepseek","openrouter","groq",
            "zhipu","dashscope","gemini","moonshot","minimax","aihubmix",
            "siliconflow","volcengine","vllm","githubCopilot","custom"};
        String[] allLabels = {"OpenAI","Anthropic","DeepSeek","OpenRouter","Groq",
            "智谱 GLM","阿里云 DashScope","Google Gemini","Moonshot","MiniMax","AIHubMix",
            "SiliconFlow","火山引擎","vLLM","GitHub Copilot","Custom"};
        for (int i = 0; i < allProviders.length; i++) {
            ProviderConfig pc = provCfg.getByName(allProviders[i]);
            String prefix = (pc != null && pc.getApiKey() != null && !pc.getApiKey().isBlank()) ? "✓ " : "  ";
            providerCombo.getItems().add(prefix + allLabels[i]);
        }
        providerCombo.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 13px;");
        providerCombo.setPrefHeight(40);

        // 预选
        if (isEdit) {
            for (int i = 0; i < allProviders.length; i++) {
                if (allProviders[i].equals(providerName)) {
                    providerCombo.getSelectionModel().select(i);
                    break;
                }
            }
        }

        // API Key
        String initKey = "";
        if (isEdit) {
            ProviderConfig pc = provCfg.getByName(providerName);
            if (pc != null && pc.getApiKey() != null) initKey = pc.getApiKey();
        }
        TextField apiKeyField = new TextField(initKey);
        apiKeyField.setPromptText("API Key");
        apiKeyField.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 14px; -fx-padding: 10px 14px;");
        apiKeyField.setPrefHeight(40);

        // Base URL
        String initBase = "";
        if (isEdit) {
            ProviderConfig pc = provCfg.getByName(providerName);
            if (pc != null && pc.getApiBase() != null) initBase = pc.getApiBase();
        }
        TextField baseField = new TextField(initBase);
        baseField.setPromptText("API Base URL (可选)");
        baseField.setStyle(apiKeyField.getStyle());
        baseField.setPrefHeight(40);

        // 设为默认
        String defaultModel = cfg.getAgents().getDefaults().getModel();
        String defaultProvider = cfg.getProviderName(defaultModel);
        javafx.scene.control.CheckBox defaultCheck = new javafx.scene.control.CheckBox("设为默认模型");
        defaultCheck.setStyle("-fx-font-size: 13px;");
        if (isEdit && providerName.equals(defaultProvider)) defaultCheck.setSelected(true);

        // 按钮
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
            int idx = providerCombo.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            String selProvider = allProviders[idx];
            String apiKey = apiKeyField.getText();
            String baseUrl = baseField.getText();

            ProviderConfig pc = provCfg.getByName(selProvider);
            if (pc == null) return;
            if (apiKey != null && !apiKey.isBlank()) pc.setApiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) pc.setApiBase(baseUrl);

            if (defaultCheck.isSelected()) {
                // Set this provider's first model as default
                if (pc.getModelConfigs() != null && !pc.getModelConfigs().isEmpty()) {
                    cfg.getAgents().getDefaults().setModel(pc.getModelConfigs().get(0).getModel());
                }
                cfg.getAgents().getDefaults().setProvider(selProvider);
            }

            try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
            dialog.close();
            refresh();
        });

        btnRow.getChildren().addAll(cancelBtn, saveBtn);
        root.getChildren().addAll(title, providerCombo, apiKeyField, baseField, defaultCheck, btnRow);

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.showAndWait();
    }
}
