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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

        // 外观设置
        settingsContainer.getChildren().add(createAppearanceSection());
        settingsContainer.getChildren().add(createSeparator());

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

    private VBox createAppearanceSection() {
        VBox section = new VBox(16);

        Label sectionTitle = new Label("外观");
        sectionTitle.getStyleClass().add("section-title");

        HBox themeBox = new HBox(12);
        Button lightBtn = new Button("\u2600\uFE0F 亮色");
        lightBtn.getStyleClass().addAll("pill-button", "selected");
        lightBtn.setPrefHeight(40);

        Button darkBtn = new Button("\uD83C\uDF19 暗色");
        darkBtn.getStyleClass().add("pill-button");
        darkBtn.setPrefHeight(40);

        themeBox.getChildren().addAll(lightBtn, darkBtn);

        section.getChildren().addAll(sectionTitle, themeBox);
        return section;
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

    private void refresh() {
        if (backendBridge == null) return;
        settingsContainer.getChildren().clear();
        settingsContainer.getChildren().add(createAppearanceSection());
        settingsContainer.getChildren().add(createSeparator());
        settingsContainer.getChildren().add(buildModelSection());
        settingsContainer.getChildren().add(createSeparator());
        settingsContainer.getChildren().add(createGatewaySection());
        settingsContainer.getChildren().add(createSeparator());
        settingsContainer.getChildren().add(buildChannelsSection());
    }

    private VBox buildModelSection() {
        config.Config cfg = backendBridge.getConfig();
        String model = cfg.getAgents().getDefaults().getModel();
        String providerName = cfg.getProviderName(model);

        VBox section = new VBox(16);
        Label sectionTitle = new Label("模型");
        sectionTitle.getStyleClass().add("section-title");

        // 收集所有已配置 provider 的模型列表
        Set<String> modelNames = new LinkedHashSet<>();
        modelNames.add(model); // 当前模型排最前
        config.provider.ProvidersConfig provCfg = cfg.getProviders();
        for (String pn : new String[]{"anthropic","openai","deepseek","openrouter","groq",
            "zhipu","dashscope","gemini","moonshot","minimax","aihubmix",
            "siliconflow","volcengine","vllm","githubCopilot","custom"}) {
            config.provider.ProviderConfig pc = provCfg.getByName(pn);
            if (pc == null || pc.getModelConfigs() == null) continue;
            for (config.provider.model.ModelConfig mc : pc.getModelConfigs()) {
                if (mc.getModel() != null && !mc.getModel().isBlank()) {
                    modelNames.add(mc.getModel());
                }
            }
        }

        // 模型选择行（使用 ComboBox）
        HBox modelRow = new HBox(16);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        VBox infoBox = new VBox(4);
        Label titleLabel = new Label("默认模型");
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label descLabel = new Label("选择用于对话的 AI 模型");
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(titleLabel, descLabel);

        ComboBox<String> modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll(new ArrayList<>(modelNames));
        modelCombo.setValue(model);
        modelCombo.setEditable(true);
        modelCombo.setStyle("-fx-background-color: rgba(0, 0, 0, 0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-family: monospace; -fx-font-size: 13px;");
        modelCombo.setPrefHeight(40);
        modelCombo.setOnAction(e -> {
            String selected = modelCombo.getValue();
            if (selected != null && !selected.isBlank() && !selected.equals(model)) {
                cfg.getAgents().getDefaults().setModel(selected);
                try {
                    config.ConfigIO.saveConfig(cfg, null);
                } catch (Exception ignored) {}
                if (onModelChanged != null) onModelChanged.accept(selected);
                // 刷新当前 section 显示
                refresh();
            }
        });

        modelRow.getChildren().addAll(infoBox, modelCombo);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        // API Key 显示
        String apiKey = "";
        if (providerName != null) {
            config.provider.ProviderConfig pc = cfg.getProviders().getByName(providerName);
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
