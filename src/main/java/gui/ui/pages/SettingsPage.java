package gui.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

import java.util.function.Consumer;

public class SettingsPage extends VBox {

    private VBox settingsContainer;
    private gui.ui.BackendBridge backendBridge;
    private Consumer<String> onModelChanged;

    public SettingsPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(48);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        // 页面标题
        Label title = new Label("设置");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("管理你的应用配置和偏好");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        // 设置容器
        settingsContainer = new VBox(32);
        settingsContainer.setMaxWidth(800);

        // 模型设置
        settingsContainer.getChildren().add(createModelSection());
        settingsContainer.getChildren().add(createSeparator());

        // Gateway 状态
        settingsContainer.getChildren().add(createGatewaySection());
        settingsContainer.getChildren().add(createSeparator());

        // 通道设置
        settingsContainer.getChildren().add(createChannelsSection());

        content.getChildren().addAll(titleBox, settingsContainer);

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createModelSection() {
        VBox section = new VBox(16);

        Label sectionTitle = new Label("模型");
        sectionTitle.getStyleClass().add("section-title");

        // 默认模型
        HBox modelRow = createSettingRow("默认模型", "选择用于对话的 AI 模型", "claude-sonnet-4 \u25BE");

        // API Key
        HBox apiKeyRow = createSettingRow("API 密钥", "用于认证模型服务", "sk-\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");

        section.getChildren().addAll(sectionTitle, modelRow, apiKeyRow);
        return section;
    }

    private HBox createSettingRow(String titleText, String desc, String value) {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox infoBox = new VBox(4);
        Label titleLabel = new Label(titleText);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label descLabel = new Label(desc);
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(titleLabel, descLabel);

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-background-color: rgba(0, 0, 0, 0.03); -fx-background-radius: 12px; -fx-padding: 0 16px; -fx-pref-height: 40px; -fx-alignment: center; -fx-font-family: monospace; -fx-font-size: 13px;");

        row.getChildren().addAll(infoBox, valueLabel);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        return row;
    }

    private VBox createGatewaySection() {
        VBox section = new VBox(16);

        Label sectionTitle = new Label("Gateway 状态");
        sectionTitle.getStyleClass().add("section-title");

        VBox statusCard = new VBox(12);
        statusCard.setStyle("-fx-background-color: rgba(0, 0, 0, 0.02); -fx-background-radius: 12px; -fx-border-color: rgba(0, 0, 0, 0.05); -fx-border-radius: 12px; -fx-border-width: 1px;");
        statusCard.setPadding(new Insets(16));

        HBox statusRow = new HBox(12);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        Label dot = new Label("\u25CF");
        dot.setStyle("-fx-text-fill: #22c55e; -fx-font-size: 8px;");

        VBox infoBox = new VBox(2);
        Label statusLabel = new Label("Gateway 运行中");
        statusLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label detailLabel = new Label("端口: 18789 · 延迟: 12ms");
        detailLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(statusLabel, detailLabel);

        HBox actionBox = new HBox(8);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(actionBox, Priority.ALWAYS);

        Button restartBtn = new Button("重启");
        restartBtn.getStyleClass().add("pill-button");
        restartBtn.setPrefHeight(32);

        Button stopBtn = new Button("停止");
        stopBtn.getStyleClass().add("pill-button");
        stopBtn.setPrefHeight(32);

        actionBox.getChildren().addAll(restartBtn, stopBtn);
        statusRow.getChildren().addAll(dot, infoBox, actionBox);

        statusCard.getChildren().add(statusRow);
        section.getChildren().addAll(sectionTitle, statusCard);
        return section;
    }

    private VBox createChannelsSection() {
        VBox section = new VBox(16);

        Label sectionTitle = new Label("通道");
        sectionTitle.getStyleClass().add("section-title");

        VBox channelsBox = new VBox(12);

        String[][] channels = {
            {"\uD83D\uDCF1", "Telegram", "已配置"},
            {"\uD83D\uDCAC", "飞书", "未配置"}
        };

        for (String[] ch : channels) {
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));
            row.setStyle("-fx-background-color: rgba(0, 0, 0, 0.02); -fx-background-radius: 12px;");

            Label icon = new Label(ch[0]);
            icon.setStyle("-fx-font-size: 20px;");

            VBox infoBox = new VBox(2);
            Label nameLabel = new Label(ch[1]);
            nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
            Label statusLabel = new Label(ch[2]);
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
            infoBox.getChildren().addAll(nameLabel, statusLabel);

            row.getChildren().addAll(icon, infoBox);
            HBox.setHgrow(infoBox, Priority.ALWAYS);

            channelsBox.getChildren().add(row);
        }

        section.getChildren().addAll(sectionTitle, channelsBox);
        return section;
    }

    private Line createSeparator() {
        Line line = new Line();
        line.setEndX(800);
        line.setStyle("-fx-stroke: rgba(0, 0, 0, 0.05);");
        return line;
    }

    public void setBackendBridge(gui.ui.BackendBridge bridge) {
        this.backendBridge = bridge;
        refresh();
    }

    public void refresh() {
        if (backendBridge == null) return;
        settingsContainer.getChildren().clear();
        settingsContainer.getChildren().add(buildModelSection());
        settingsContainer.getChildren().add(createSeparator());
        settingsContainer.getChildren().add(createGatewaySection());
        settingsContainer.getChildren().add(createSeparator());
        settingsContainer.getChildren().add(buildChannelsSection());
    }

    /** ComboBox 条目类型：模型名 或 提供商分隔标题 */
    private static class ModelItem {
        final String text;
        final String modelName;     // null 代表是标题
        final String providerName;  // 提供商名（标题用）或该模型所属的 provider
        ModelItem(String text, String model, String provider) {
            this.text = text; this.modelName = model; this.providerName = provider;
        }
        boolean isHeader() { return modelName == null; }
        @Override public String toString() { return text; }
    }

    private VBox buildModelSection() {
        config.Config cfg = backendBridge.getConfig();
        String currentModel = cfg.getAgents().getDefaults().getModel();
        String currentProvider = cfg.getProviderName(currentModel);

        VBox section = new VBox(16);
        Label sectionTitle = new Label("模型");
        sectionTitle.getStyleClass().add("section-title");

        // 按提供商分组收集模型
        String[] providerOrder = {"openai","anthropic","deepseek","openrouter","groq",
            "zhipu","dashscope","gemini","moonshot","minimax","aihubmix",
            "siliconflow","volcengine","vllm","githubCopilot","custom"};
        String[] providerLabels = {"OpenAI","Anthropic","DeepSeek","OpenRouter","Groq",
            "智谱 GLM","阿里云 DashScope","Google Gemini","Moonshot","MiniMax","AIHubMix",
            "SiliconFlow","火山引擎","vLLM","GitHub Copilot","Custom"};

        config.provider.ProvidersConfig provCfg = cfg.getProviders();
        java.util.List<ModelItem> items = new java.util.ArrayList<>();

        // 当前模型排最前，带提供商前缀
        String currentLabel = (currentProvider != null && !currentProvider.isBlank())
            ? "  " + currentProvider + "/" + currentModel + "  (当前)"
            : "  " + currentModel + "  (当前)";
        items.add(new ModelItem(currentLabel, currentModel, currentProvider != null ? currentProvider : ""));

        for (int i = 0; i < providerOrder.length; i++) {
            String pn = providerOrder[i];
            String pl = providerLabels[i];
            config.provider.ProviderConfig pc = provCfg.getByName(pn);
            if (pc == null || pc.getModelConfigs() == null || pc.getModelConfigs().isEmpty()) continue;

            // 检查此 provider 是否有 API key（标记状态）
            boolean hasKey = pc.getApiKey() != null && !pc.getApiKey().isBlank();
            String headerText = "▸ " + pl + (hasKey ? "" : " (未配置 Key)");
            if (pn.equals(currentProvider)) headerText = "▸ " + pl + " ★";

            // 添加提供商标题
            items.add(new ModelItem(headerText, null, pn));

            for (config.provider.model.ModelConfig mc : pc.getModelConfigs()) {
                if (mc.getModel() != null && !mc.getModel().isBlank()
                    && !mc.getModel().equals(currentModel)) {
                    items.add(new ModelItem("     " + pn + "/" + mc.getModel(), mc.getModel(), pn));
                }
            }
        }

        // 模型选择行
        HBox modelRow = new HBox(16);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        VBox infoBox = new VBox(4);
        Label titleLabel = new Label("默认模型");
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label descLabel = new Label("选择用于对话的 AI 模型");
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(titleLabel, descLabel);

        // 保留完整列表供搜索
        java.util.List<ModelItem> allItems = new java.util.ArrayList<>(items);

        ComboBox<ModelItem> modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll(items);
        if (!items.isEmpty()) modelCombo.setValue(items.get(0));
        modelCombo.setEditable(true);
        modelCombo.setStyle("-fx-background-color: rgba(0, 0, 0, 0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 13px;");
        modelCombo.setPrefHeight(40);
        modelCombo.setMaxWidth(350);

        // 自定义单元格渲染：标题项不可选、灰色
        modelCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ModelItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setDisable(false); return; }
                setText(item.text);
                if (item.isHeader()) {
                    setDisable(true);
                    setStyle("-fx-text-fill: rgba(0,0,0,0.4); -fx-font-weight: 700;"
                        + " -fx-font-size: 11px; -fx-font-family: sans-serif;");
                } else {
                    setDisable(false);
                    setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
                }
            }
        });

        modelCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ModelItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.modelName != null ? item.modelName : item.text);
                setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
            }
        });

        // ---- 搜索过滤：使用独立 Popup，不修改 ComboBox items 避免 IndexOutOfBounds ----
        javafx.stage.Popup searchPopup = new javafx.stage.Popup();
        searchPopup.setAutoHide(true);
        VBox searchList = new VBox(2);
        searchList.setStyle("-fx-background-color: rgba(255,255,255,0.97); -fx-background-radius: 10px;"
            + " -fx-border-color: rgba(0,0,0,0.08); -fx-border-radius: 10px; -fx-border-width: 1px;");
        searchList.setPadding(new Insets(4));
        searchPopup.getContent().add(searchList);

        modelCombo.getEditor().textProperty().addListener((obs, old, text) -> {
            searchPopup.hide();
            if (text == null || text.isBlank()) return;
            String lower = text.toLowerCase().trim();
            if (lower.isEmpty()) return;

            // Filter models
            java.util.List<ModelItem> filtered = new java.util.ArrayList<>();
            for (ModelItem mi : allItems) {
                if (mi.isHeader()) {
                    filtered.add(mi);
                } else if (mi.modelName != null && mi.modelName.toLowerCase().contains(lower)) {
                    filtered.add(mi);
                }
            }
            // Clean orphan headers
            java.util.List<ModelItem> clean = new java.util.ArrayList<>();
            for (int i = 0; i < filtered.size(); i++) {
                ModelItem mi = filtered.get(i);
                if (mi.isHeader()) {
                    if (i + 1 < filtered.size() && !filtered.get(i + 1).isHeader()) {
                        clean.add(mi);
                    }
                } else {
                    clean.add(mi);
                }
            }
            if (clean.isEmpty()) return;

            // Hide native dropdown so it doesn't overlap the search popup
            modelCombo.hide();

            // Build popup rows
            searchList.getChildren().clear();
            for (ModelItem mi : clean) {
                javafx.scene.control.Label row = new javafx.scene.control.Label(mi.text);
                row.setPadding(new Insets(4, 10, 4, 10));
                row.setPrefHeight(24);
                if (mi.isHeader()) {
                    row.setStyle("-fx-text-fill: rgba(0,0,0,0.4); -fx-font-weight: 700;"
                        + " -fx-font-size: 11px; -fx-font-family: sans-serif;");
                    row.setDisable(true);
                } else {
                    row.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;"
                        + " -fx-cursor: hand;");
                    row.setOnMouseClicked(ev -> {
                        searchPopup.hide();
                        modelCombo.setValue(mi);
                        modelCombo.getEditor().setText(mi.modelName);
                    });
                }
                searchList.getChildren().add(row);
            }
            // Show popup below ComboBox
            if (!searchList.getChildren().isEmpty()) {
                var bounds = modelCombo.localToScreen(modelCombo.getBoundsInLocal());
                searchPopup.show(modelCombo.getScene().getWindow(),
                    bounds.getMinX(), bounds.getMaxY() + 2);
            }
        });

        // Hide search popup when ComboBox dropdown is opened (user clicked arrow)
        modelCombo.setOnShowing(e -> searchPopup.hide());

        modelCombo.setOnAction(e -> {
            // editable ComboBox getValue() may return String when user types
            Object value = modelCombo.getValue();
            if (!(value instanceof ModelItem)) return;
            ModelItem selected = (ModelItem) value;
            if (selected.isHeader()) return;
            if (selected.modelName.equals(currentModel)) return;
            String pn = selected.providerName;
            // 同时更新 model 和 provider
            cfg.getAgents().getDefaults().setModel(selected.modelName);
            if (pn != null && !pn.isBlank()) {
                cfg.getAgents().getDefaults().setProvider(pn);
            }
            try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
            if (onModelChanged != null) onModelChanged.accept(selected.modelName);
            refresh();
        });

        modelRow.getChildren().addAll(infoBox, modelCombo);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        // API Key 显示
        String apiKey = "";
        if (currentProvider != null) {
            config.provider.ProviderConfig pc = cfg.getProviders().getByName(currentProvider);
            if (pc != null && pc.getApiKey() != null && !pc.getApiKey().isBlank()) {
                apiKey = pc.getApiKey();
            }
        }
        String maskedKey = apiKey.length() > 4
            ? apiKey.substring(0, 4) + "\u2022\u2022\u2022\u2022" + apiKey.substring(apiKey.length() - 4)
            : (apiKey.isBlank() ? "未配置" : "\u2022\u2022\u2022");
        HBox apiKeyRow = createSettingRow("API 密钥", "用于认证模型服务", maskedKey);

        section.getChildren().addAll(sectionTitle, modelRow, apiKeyRow);
        return section;
    }

    public void setOnModelChanged(Consumer<String> callback) {
        this.onModelChanged = callback;
    }

    private VBox buildChannelsSection() {
        config.channel.ChannelsConfig ch = backendBridge.getConfig().getChannels();
        VBox section = new VBox(16);
        Label sectionTitle = new Label("通道");
        sectionTitle.getStyleClass().add("section-title");
        VBox channelsBox = new VBox(12);

        addChannelRow(channelsBox, "\uD83D\uDCF1", "Telegram",
            ch.getTelegram().getToken() != null && !ch.getTelegram().getToken().isBlank());
        addChannelRow(channelsBox, "\uD83D\uDCAC", "飞书",
            ch.getFeishu().getAppId() != null && !ch.getFeishu().getAppId().isBlank());
        addChannelRow(channelsBox, "\uD83D\uDCE7", "Email",
            ch.getEmail().getSmtpHost() != null && !ch.getEmail().getSmtpHost().isBlank());

        section.getChildren().addAll(sectionTitle, channelsBox);
        return section;
    }

    private void addChannelRow(VBox box, String icon, String name, boolean configured) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12));
        row.setStyle("-fx-background-color: rgba(0, 0, 0, 0.02); -fx-background-radius: 12px;");

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 20px;");
        VBox infoBox = new VBox(2);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label statusLabel = new Label(configured ? "已配置" : "未配置");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(nameLabel, statusLabel);
        row.getChildren().addAll(iconLabel, infoBox);
        HBox.setHgrow(infoBox, Priority.ALWAYS);
        box.getChildren().add(row);
    }
}
