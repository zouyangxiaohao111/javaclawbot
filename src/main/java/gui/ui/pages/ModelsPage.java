package gui.ui.pages;

import config.provider.ProviderConfig;
import config.provider.model.ModelConfig;
import gui.ui.components.ModelCard;
import javafx.application.Platform;
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

    private static final String[] ALL_PROVIDERS = {"openai","anthropic","deepseek","openrouter","groq",
        "zhipu","dashscope","gemini","moonshot","minimax","aihubmix",
        "siliconflow","volcengine","vllm","githubCopilot","custom"};
    private static final String[] ALL_LABELS = {"OpenAI","Anthropic","DeepSeek","OpenRouter","Groq",
        "智谱 GLM","阿里云 DashScope","Google Gemini","Moonshot","MiniMax","AIHubMix",
        "SiliconFlow","火山引擎","vLLM","GitHub Copilot","Custom"};

    private String fieldStyle() {
        return "-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 14px; -fx-padding: 10px 14px;";
    }

    private void showModelDialog(String providerName) {
        config.Config cfg = backendBridge.getConfig();
        config.provider.ProvidersConfig provCfg = cfg.getProviders();

        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.initModality(Modality.APPLICATION_MODAL);

        VBox root = new VBox(12);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #f1ede1; -fx-background-radius: 16px;"
            + " -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 16px; -fx-border-width: 1px;");
        root.setMinWidth(560);

        Label title = new Label(providerName != null ? "编辑 " + providerName : "添加模型");
        title.setStyle("-fx-font-family: Georgia; -fx-font-size: 24px; -fx-text-fill: rgba(0,0,0,0.7);");

        // === 第一部分：选择提供商 + API Key ===
        Label providerTitle = new Label("提供商");
        providerTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: rgba(0,0,0,0.5);");

        ComboBox<String> providerCombo = new ComboBox<>();
        for (int i = 0; i < ALL_PROVIDERS.length; i++) {
            ProviderConfig pc = provCfg.getByName(ALL_PROVIDERS[i]);
            String prefix = (pc != null && pc.getApiKey() != null && !pc.getApiKey().isBlank()) ? "✓ " : "  ";
            providerCombo.getItems().add(prefix + ALL_LABELS[i]);
        }
        providerCombo.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 13px;");
        providerCombo.setPrefHeight(40);

        // 获取初始选中的 provider
        String[] currentProvider = {providerName != null ? providerName : ALL_PROVIDERS[0]};
        if (providerName != null) {
            for (int i = 0; i < ALL_PROVIDERS.length; i++) {
                if (ALL_PROVIDERS[i].equals(providerName)) { providerCombo.getSelectionModel().select(i); break; }
            }
        } else {
            providerCombo.getSelectionModel().select(0);
        }

        ProviderConfig initPc = provCfg.getByName(currentProvider[0]);
        TextField apiKeyField = new TextField(initPc != null && initPc.getApiKey() != null ? initPc.getApiKey() : "");
        apiKeyField.setPromptText("API Key");
        apiKeyField.setStyle(fieldStyle()); apiKeyField.setPrefHeight(38);

        TextField baseField = new TextField(initPc != null && initPc.getApiBase() != null ? initPc.getApiBase() : "");
        baseField.setPromptText("API Base URL (可选)");
        baseField.setStyle(fieldStyle()); baseField.setPrefHeight(38);

        // 切换 provider 时更新 API Key / Base URL 和模型列表
        VBox modelsBox = new VBox(8);
        Runnable refreshModelsBox = () -> {
            int idx = providerCombo.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            String sel = ALL_PROVIDERS[idx];
            currentProvider[0] = sel;
            ProviderConfig pc = provCfg.getByName(sel);
            if (pc != null) {
                apiKeyField.setText(pc.getApiKey() != null ? pc.getApiKey() : "");
                baseField.setText(pc.getApiBase() != null ? pc.getApiBase() : "");
            } else {
                apiKeyField.setText("");
                baseField.setText("");
            }
            rebuildModelList(modelsBox, pc, cfg, dialog);
        };

        providerCombo.setOnAction(e -> refreshModelsBox.run());

        // === 第二部分：模型列表 ===
        rebuildModelList(modelsBox, initPc, cfg, dialog);

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
            String selProvider = ALL_PROVIDERS[idx];
            String apiKey = apiKeyField.getText();
            String baseUrl = baseField.getText();

            ProviderConfig pc = provCfg.getByName(selProvider);
            if (pc == null) return;
            if (apiKey != null && !apiKey.isBlank()) pc.setApiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) pc.setApiBase(baseUrl);

            try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
            dialog.close();
            Platform.runLater(() -> refresh());
        });

        btnRow.getChildren().addAll(cancelBtn, saveBtn);

        // 模型列表区域 (可滚动)
        ScrollPane modelScrollPane = new ScrollPane(modelsBox);
        modelScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        modelScrollPane.setFitToWidth(true);
        modelScrollPane.setPrefViewportHeight(200);
        modelScrollPane.setMaxHeight(350);

        root.getChildren().addAll(title, providerTitle, providerCombo,
            newLabel("API Key"), apiKeyField,
            newLabel("API Base URL (可选)"), baseField,
            new javafx.scene.control.Separator(),
            newLabel("模型列表"), modelScrollPane, btnRow);

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.showAndWait();
    }

    private Label newLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: rgba(0,0,0,0.5);");
        return l;
    }

    private void rebuildModelList(VBox box, ProviderConfig pc, config.Config cfg, Stage dialog) {
        box.getChildren().clear();
        if (pc == null || pc.getModelConfigs() == null || pc.getModelConfigs().isEmpty()) {
            Label empty = new Label("（暂无模型）");
            empty.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0,0,0,0.3); -fx-padding: 8px 0;");
            box.getChildren().add(empty);
        } else {
            String defModel = cfg.getAgents().getDefaults().getModel();
            for (ModelConfig mc : pc.getModelConfigs()) {
                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(6, 10, 6, 10));
                row.setStyle("-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 8px;");

                String label = mc.getModel();
                if (mc.getAlias() != null && !mc.getAlias().isBlank()) label += " (" + mc.getAlias() + ")";
                if (label.equals(defModel)) label += " ★";
                Label nameLabel = new Label(label);
                nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500;");

                Label typeBadge = new Label(mc.getType() != null ? mc.getType().name() : "CHAT");
                typeBadge.setStyle("-fx-background-color: rgba(0,0,0,0.04); -fx-background-radius: 6px;"
                    + " -fx-font-size: 10px; -fx-padding: 2px 8px;");

                Label tokensLabel = new Label("max: " + (mc.getMaxTokens() != null ? mc.getMaxTokens() : "-"));
                tokensLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.4);");

                HBox spacer = new HBox(); HBox.setHgrow(spacer, Priority.ALWAYS);

                Button delBtn = new Button("×");
                delBtn.setStyle("-fx-background-color: transparent; -fx-font-size: 14px;"
                    + " -fx-text-fill: rgba(0,0,0,0.3); -fx-cursor: hand; -fx-padding: 0 4px;");
                delBtn.setOnAction(e -> {
                    pc.getModelConfigs().remove(mc);
                    try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
                    rebuildModelList(box, pc, cfg, dialog);
                });

                row.getChildren().addAll(nameLabel, typeBadge, tokensLabel, spacer, delBtn);
                box.getChildren().add(row);
            }
        }

        // 添加模型按钮
        Button addModelBtn = new Button("+ 添加模型");
        addModelBtn.getStyleClass().add("pill-button");
        addModelBtn.setPrefHeight(30);
        addModelBtn.setOnAction(e -> showAddModelFields(box, pc, cfg, dialog));
        box.getChildren().add(addModelBtn);
    }

    private void showAddModelFields(VBox box, ProviderConfig pc, config.Config cfg, Stage dialog) {
        // 移除添加按钮
        box.getChildren().remove(box.getChildren().size() - 1);

        VBox form = new VBox(6);
        form.setPadding(new Insets(8));
        form.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 8px;");

        TextField modelField = new TextField();
        modelField.setPromptText("模型名称 (如 gpt-4o)");
        modelField.setStyle(fieldStyle()); modelField.setPrefHeight(36);

        TextField aliasField = new TextField();
        aliasField.setPromptText("别名 (可选)");
        aliasField.setStyle(fieldStyle()); aliasField.setPrefHeight(36);

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("CHAT","TEXT","VISION","IMAGE_GENERATION","EMBEDDING","AUDIO","RERANK","MODERATION");
        typeCombo.setValue("CHAT");
        typeCombo.setStyle(fieldStyle()); typeCombo.setPrefHeight(36);

        HBox numRow = new HBox(8);
        TextField maxTokensField = new TextField("8192");
        maxTokensField.setPromptText("Max Tokens"); maxTokensField.setStyle(fieldStyle()); maxTokensField.setPrefHeight(36); maxTokensField.setPrefWidth(120);
        TextField tempField = new TextField("");
        tempField.setPromptText("Temperature"); tempField.setStyle(fieldStyle()); tempField.setPrefHeight(36); tempField.setPrefWidth(120);
        TextField topPField = new TextField("");
        topPField.setPromptText("Top P"); topPField.setStyle(fieldStyle()); topPField.setPrefHeight(36); topPField.setPrefWidth(120);
        TextField ctxField = new TextField("");
        ctxField.setPromptText("Context Window"); ctxField.setStyle(fieldStyle()); ctxField.setPrefHeight(36); ctxField.setPrefWidth(140);
        numRow.getChildren().addAll(maxTokensField, tempField, topPField, ctxField);

        HBox btnRow = new HBox(8);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        Button cancelModelBtn = new Button("取消");
        cancelModelBtn.getStyleClass().add("pill-button");
        cancelModelBtn.setPrefHeight(30);
        cancelModelBtn.setOnAction(ev -> rebuildModelList(box, pc, cfg, dialog));

        Button confirmBtn = new Button("确认添加");
        confirmBtn.getStyleClass().addAll("pill-button", "selected");
        confirmBtn.setPrefHeight(30);
        confirmBtn.setOnAction(ev -> {
            String modelName = modelField.getText();
            if (modelName == null || modelName.isBlank()) return;

            ModelConfig mc = new ModelConfig();
            mc.setModel(modelName.trim());
            if (aliasField.getText() != null && !aliasField.getText().isBlank()) mc.setAlias(aliasField.getText().trim());
            try { mc.setType(ModelConfig.ModelType.valueOf(typeCombo.getValue())); } catch (Exception ignored) {}
            try { mc.setMaxTokens(Integer.parseInt(maxTokensField.getText())); } catch (Exception ignored) {}
            if (tempField.getText() != null && !tempField.getText().isBlank()) {
                try { mc.setTemperature(Double.parseDouble(tempField.getText())); } catch (Exception ignored) {}
            }
            if (topPField.getText() != null && !topPField.getText().isBlank()) {
                try { mc.setTopP(Double.parseDouble(topPField.getText())); } catch (Exception ignored) {}
            }
            if (ctxField.getText() != null && !ctxField.getText().isBlank()) {
                try { mc.setContextWindow(Integer.parseInt(ctxField.getText())); } catch (Exception ignored) {}
            }
            if (pc.getModelConfigs() == null) pc.setModelConfigs(new java.util.ArrayList<>());
            pc.getModelConfigs().add(mc);
            try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
            rebuildModelList(box, pc, cfg, dialog);
        });

        btnRow.getChildren().addAll(cancelModelBtn, confirmBtn);

        form.getChildren().addAll(newLabel("模型名称"), modelField,
            newLabel("别名"), aliasField,
            newLabel("类型"), typeCombo,
            newLabel("参数"), numRow,
            btnRow);
        box.getChildren().add(form);
    }
}
