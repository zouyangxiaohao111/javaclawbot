package gui.ui.components;

import config.Config;
import config.provider.ProvidersConfig;
import config.provider.ProviderConfig;
import config.provider.model.ModelConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import providers.ProviderRegistry;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 模型选择弹窗 — 从状态栏向上弹出。
 * 复用 SettingsPage 的 Provider Pill Tabs + Model Card Grid 样式。
 */
public class ModelSelectorPopup {

    // ── 样式 Token（Claude 品牌设计系统）──
    private static final String COLOR_PRIMARY = "#cc785c";
    private static final String COLOR_CANVAS = "#faf9f5";
    private static final String COLOR_SURFACE_SOFT = "#f5f0e8";
    private static final String COLOR_CREAM_STRONG = "#e8e0d2";
    private static final String COLOR_HAIRLINE = "#e6dfd8";
    private static final String COLOR_INK = "#141413";
    private static final String COLOR_MUTED = "#6c6a64";
    private static final String FONT_MONO = "'JetBrains Mono', monospace";

    private final Popup popup;
    private final VBox root;
    private final VBox modelGridContainer;
    private final Label titleLabel;
    private final Label subtitleLabel;

    private Config config;
    private String selectedProvider;
    private String selectedModel;
    private String currentProvider;
    private String currentModel;
    private BiConsumer<String, String> onConfirm; // (providerName, model) -> void

    public ModelSelectorPopup() {
        popup = new Popup();
        popup.setAutoHide(true);

        root = new VBox(10);
        root.setStyle(
            "-fx-background-color: " + COLOR_CANVAS + ";" +
            "-fx-background-radius: 12px;" +
            "-fx-border-color: " + COLOR_HAIRLINE + ";" +
            "-fx-border-radius: 12px;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 16, 0, 0, 4);");
        root.setPadding(new Insets(16));
        root.setMaxWidth(560);
        root.setMinWidth(480);

        // 标题
        titleLabel = new Label();
        titleLabel.setStyle(
            "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + COLOR_INK + ";");

        subtitleLabel = new Label();
        subtitleLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + COLOR_MUTED + ";");

        // 提供商 Pill Tabs + 模型卡片容器
        modelGridContainer = new VBox(8);

        // 底部按钮
        HBox buttonRow = new HBox(8);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(8, 0, 0, 0));

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: " + COLOR_MUTED + ";" +
            "-fx-font-size: 12px; -fx-padding: 6px 16px; -fx-cursor: hand;" +
            "-fx-border-color: " + COLOR_HAIRLINE + "; -fx-border-radius: 6px;");
        cancelBtn.setOnAction(e -> popup.hide());

        Button confirmBtn = new Button("确认切换");
        confirmBtn.setStyle(
            "-fx-background-color: " + COLOR_PRIMARY + "; -fx-text-fill: white;" +
            "-fx-font-size: 12px; -fx-font-weight: bold;" +
            "-fx-padding: 6px 20px; -fx-cursor: hand;" +
            "-fx-background-radius: 6px;");
        confirmBtn.setOnAction(e -> {
            if (onConfirm != null && selectedProvider != null && selectedModel != null) {
                onConfirm.accept(selectedProvider, selectedModel);
            }
            popup.hide();
        });

        buttonRow.getChildren().addAll(cancelBtn, confirmBtn);

        root.getChildren().addAll(titleLabel, subtitleLabel, modelGridContainer, buttonRow);
        popup.getContent().add(root);
    }

    /**
     * 显示弹窗，定位在 owner 上方。
     * @param owner       触发节点（状态栏 leftStatusLabel）
     * @param config      应用配置
     * @param tabId       当前标签 ID（用于标题显示）
     * @param curProvider 当前标签的提供商名称
     * @param curModel    当前标签的模型名称
     * @param onConfirm   确认回调 (providerName, modelName) -> void
     */
    public void show(javafx.scene.Node owner, Config config, String tabId,
                     String curProvider, String curModel,
                     BiConsumer<String, String> onConfirm) {
        this.config = Objects.requireNonNull(config);
        this.currentProvider = curProvider;
        this.currentModel = curModel;
        this.selectedProvider = curProvider;
        this.selectedModel = curModel;
        this.onConfirm = onConfirm;

        titleLabel.setText("切换模型");
        subtitleLabel.setText("当前模型：" + curProvider + " / " + curModel);

        rebuildProviderTabs();

        if (owner.getScene() == null) return;

        javafx.geometry.Bounds bounds = owner.localToScreen(owner.getBoundsInLocal());
        double popoverHeight = root.prefHeight(-1);
        popup.show(owner.getScene().getWindow(),
            bounds.getMinX(),
            bounds.getMinY() - popoverHeight - 8);
    }

    public void hide() { popup.hide(); }
    public boolean isShowing() { return popup.isShowing(); }

    private void rebuildProviderTabs() {
        modelGridContainer.getChildren().clear();

        // Provider Pill Tabs - 使用TilePane支持换行
        TilePane pillRow = new TilePane();
        pillRow.setHgap(6);
        pillRow.setVgap(6);
        pillRow.setPrefColumns(4);

        ProvidersConfig provCfg = config.getProviders();
        java.util.Set<String> allNames = provCfg.names();

        // 按 PROVIDERS 顺序排列
        for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
            String pName = spec.getName();
            if (!allNames.contains(pName)) continue;

            Button pill = new Button(pName.substring(0, 1).toUpperCase() + pName.substring(1));
            boolean isSelected = pName.equals(selectedProvider);
            pill.setStyle(buildPillStyle(isSelected));
            pill.setOnAction(e -> {
                selectedProvider = pName;
                rebuildProviderTabs();
            });
            pillRow.getChildren().add(pill);
        }

        modelGridContainer.getChildren().add(pillRow);

        // 当前选中提供商的模型区域
        if (selectedProvider != null) {
            ProviderConfig pc = provCfg.getByName(selectedProvider);
            if (pc == null || pc.getModelConfigs() == null || pc.getModelConfigs().isEmpty()) {
                Label empty = new Label("该提供商无可用模型");
                empty.setStyle("-fx-font-size: 11px; -fx-text-fill: " + COLOR_MUTED + "; -fx-padding: 8px 0;");
                modelGridContainer.getChildren().add(empty);
                return;
            }

            // 提供商小标题
            String displayName = selectedProvider.substring(0, 1).toUpperCase() + selectedProvider.substring(1);
            Label providerLabel = new Label("\u25CF " + displayName);
            providerLabel.setStyle(
                "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + COLOR_INK + ";");

            // 模型卡片网格（带圆角底框）
            VBox modelGridWrapper = new VBox(4);
            modelGridWrapper.setStyle(
                "-fx-background-color: " + COLOR_SURFACE_SOFT + ";" +
                "-fx-background-radius: 8px; -fx-padding: 8px;");

            TilePane modelGrid = new TilePane();
            modelGrid.setHgap(8);
            modelGrid.setVgap(8);
            modelGrid.setPrefColumns(2);

            for (ModelConfig mc : pc.getModelConfigs()) {
                if (mc.getModel() == null || mc.getModel().isBlank()) continue;
                boolean isSelected = mc.getModel().equals(selectedModel)
                        && selectedProvider != null && selectedProvider.equals(currentProvider);
                modelGrid.getChildren().add(createModelCard(mc.getModel(), isSelected));
            }

            modelGridWrapper.getChildren().add(modelGrid);
            modelGridContainer.getChildren().addAll(providerLabel, modelGridWrapper);
        }
    }

    private javafx.scene.Node createModelCard(String modelName, boolean isSelected) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setPrefWidth(180);

        card.setStyle(
            "-fx-background-color: white; -fx-background-radius: 8px;" +
            "-fx-border-color: " + (isSelected ? COLOR_PRIMARY : "transparent") + ";" +
            "-fx-border-width: " + (isSelected ? "2px" : "1px") + ";" +
            "-fx-border-radius: 8px;" +
            "-fx-cursor: hand;");

        Label nameLabel = new Label(modelName);
        nameLabel.setStyle(
            "-fx-font-family: " + FONT_MONO + ";" +
            "-fx-font-size: 12px; -fx-text-fill: " + COLOR_INK + ";");

        Label statusLabel = new Label(isSelected ? "\u2713 当前选择" : "点击选择");
        statusLabel.setStyle(
            "-fx-font-size: 10px; -fx-text-fill: " + (isSelected ? COLOR_PRIMARY : COLOR_MUTED) + ";");

        card.getChildren().addAll(nameLabel, statusLabel);

        card.setOnMouseClicked(e -> {
            selectedModel = modelName;
            // 立即确认并关闭
            if (onConfirm != null && selectedProvider != null) {
                onConfirm.accept(selectedProvider, selectedModel);
            }
            popup.hide();
        });

        card.setOnMouseEntered(e -> {
            if (!isSelected) {
                card.setStyle(
                    "-fx-background-color: white; -fx-background-radius: 8px;" +
                    "-fx-border-color: " + COLOR_HAIRLINE + ";" +
                    "-fx-border-width: 1px; -fx-border-radius: 8px;" +
                    "-fx-cursor: hand;");
            }
        });
        card.setOnMouseExited(e -> {
            if (!isSelected) {
                card.setStyle(
                    "-fx-background-color: white; -fx-background-radius: 8px;" +
                    "-fx-border-color: transparent; -fx-border-width: 1px;" +
                    "-fx-border-radius: 8px; -fx-cursor: hand;");
            }
        });

        return card;
    }

    private String buildPillStyle(boolean selected) {
        if (selected) {
            return "-fx-background-color: " + COLOR_CREAM_STRONG + ";" +
                "-fx-border-color: " + COLOR_PRIMARY + "; -fx-border-width: 1.5px;" +
                "-fx-border-radius: 16px; -fx-background-radius: 16px;" +
                "-fx-padding: 3px 12px; -fx-font-size: 11px;" +
                "-fx-text-fill: " + COLOR_INK + "; -fx-font-weight: bold;" +
                "-fx-cursor: hand;";
        }
        return "-fx-background-color: white;" +
            "-fx-border-color: " + COLOR_HAIRLINE + "; -fx-border-width: 1px;" +
            "-fx-border-radius: 16px; -fx-background-radius: 16px;" +
            "-fx-padding: 3px 12px; -fx-font-size: 11px;" +
            "-fx-text-fill: " + COLOR_MUTED + "; -fx-cursor: hand;";
    }
}
