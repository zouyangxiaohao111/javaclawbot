package gui.ui.pages;

import gui.ui.components.ModelCard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
            config.provider.ProviderConfig pc = provCfg.getByName(n);
            if (pc == null) continue;
            boolean isConfigured = pc.getApiKey() != null && !pc.getApiKey().isBlank();
            providers.ProviderRegistry.ProviderSpec spec = providers.ProviderRegistry.findByName(n);
            boolean isOauth = spec != null && spec.isOauth();
            boolean isReady = isConfigured || isOauth;
            boolean isDefault = defaultModel != null && cfg.getProviderName(defaultModel) != null
                && cfg.getProviderName(defaultModel).equals(n);
            modelList.getChildren().add(new ModelCard(label, n, isDefault, isReady));
        }
    }
}
