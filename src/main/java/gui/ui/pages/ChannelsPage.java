package gui.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class ChannelsPage extends VBox {

    private VBox channelList;
    private gui.ui.BackendBridge backendBridge;

    public ChannelsPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(16);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("通道管理");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("管理消息通道配置");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        channelList = new VBox(12);
        channelList.setMaxWidth(800);

        String[][] channels = {
            {"\uD83D\uDCF1", "Telegram", "已配置", "运行中"},
            {"\uD83D\uDCAC", "飞书", "未配置", "已停止"},
            {"\uD83D\uDCE7", "Email", "已配置", "运行中"}
        };

        for (String[] ch : channels) {
            channelList.getChildren().add(createChannelCard(ch[0], ch[1], ch[2], ch[3]));
        }

        content.getChildren().addAll(titleBox, channelList);
        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createChannelCard(String iconText, String name, String config, String status) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label iconLabel = new Label(iconText);
        iconLabel.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 10px; -fx-pref-width: 40px; -fx-pref-height: 40px; -fx-alignment: center;");
        iconLabel.setMinSize(40, 40);

        VBox infoBox = new VBox(2);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label configLabel = new Label(config);
        configLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(nameLabel, configLabel);

        Label statusBadge = new Label(status);
        statusBadge.getStyleClass().addAll("status-badge", "运行中".equals(status) ? "running" : "error");

        header.getChildren().addAll(iconLabel, infoBox, statusBadge);
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
        channelList.getChildren().clear();

        config.channel.ChannelsConfig ch = backendBridge.getConfig().getChannels();
        addChannelCard(channelList, "\uD83D\uDCF1", "Telegram",
            ch.getTelegram().getToken(), backendBridge.getConfig());
        addChannelCard(channelList, "\uD83D\uDCAC", "飞书",
            ch.getFeishu().getAppId(), backendBridge.getConfig());
        addChannelCard(channelList, "\uD83D\uDCE7", "Email",
            ch.getEmail().getSmtpHost(), backendBridge.getConfig());
    }

    private void addChannelCard(VBox list, String icon, String name, String configValue, config.Config cfg) {
        boolean configured = configValue != null && !configValue.isBlank();
        list.getChildren().add(createChannelCard(icon, name,
            configured ? "已配置" : "未配置",
            configured ? "运行中" : "已停止"));
    }
}
