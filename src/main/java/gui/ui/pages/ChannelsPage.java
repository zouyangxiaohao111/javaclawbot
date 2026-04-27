package gui.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

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
            {"\uD83D\uDCF1", "Telegram", "已配置", "运行中", "telegram"},
            {"\uD83D\uDCAC", "飞书", "未配置", "已停止", "feishu"},
            {"\uD83D\uDCE7", "Email", "已配置", "运行中", "email"}
        };

        for (String[] ch : channels) {
            channelList.getChildren().add(createChannelCard(ch[0], ch[1], ch[2], ch[3], ch[4]));
        }

        content.getChildren().addAll(titleBox, channelList);
        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createChannelCard(String iconText, String name, String config, String status,
                                    String channelType) {
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

        Button configBtn = new Button("配置");
        configBtn.getStyleClass().add("pill-button");
        configBtn.setPrefHeight(32);
        configBtn.setOnAction(e -> showChannelDialog(channelType, name));

        header.getChildren().addAll(iconLabel, infoBox, configBtn, statusBadge);
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
            ch.getTelegram().getToken(), "telegram");
        addChannelCard(channelList, "\uD83D\uDCAC", "飞书",
            ch.getFeishu().getAppId(), "feishu");
        addChannelCard(channelList, "\uD83D\uDCE7", "Email",
            ch.getEmail().getSmtpHost(), "email");
        addChannelCard(channelList, "\uD83C\uDFAE", "Discord",
            "", "discord");
        addChannelCard(channelList, "\uD83D\uDC26", "DingTalk",
            "", "dingtalk");
        addChannelCard(channelList, "\uD83D\uDCAC", "Slack",
            "", "slack");
        addChannelCard(channelList, "\uD83D\uDD17", "Matrix",
            "", "matrix");
        addChannelCard(channelList, "\uD83D\uDCF2", "WhatsApp",
            "", "whatsapp");
        addChannelCard(channelList, "\uD83D\uDC26", "QQ",
            "", "qq");
    }

    private void addChannelCard(VBox list, String icon, String name, String configValue, String channelType) {
        boolean configured = configValue != null && !configValue.isBlank();
        list.getChildren().add(createChannelCard(icon, name,
            configured ? "已配置" : "未配置",
            configured ? "运行中" : "已停止",
            channelType));
    }

    private void showChannelDialog(String channelType, String displayName) {
        config.Config cfg = backendBridge.getConfig();
        config.channel.ChannelsConfig ch = cfg.getChannels();

        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.initModality(Modality.APPLICATION_MODAL);

        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #f1ede1; -fx-background-radius: 16px;"
            + " -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 16px; -fx-border-width: 1px;");

        Label title = new Label("配置 " + displayName);
        title.setStyle("-fx-font-family: Georgia; -fx-font-size: 24px; -fx-text-fill: rgba(0,0,0,0.7);");

        String fieldStyle = "-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 14px; -fx-padding: 10px 14px;";

        VBox fields = new VBox(8);

        // Enabled checkbox
        CheckBox enabledCheck = new CheckBox("启用");
        enabledCheck.setStyle("-fx-font-size: 13px;");

        switch (channelType) {
            case "telegram": {
                config.channel.TelegramConfig tg = ch.getTelegram();
                enabledCheck.setSelected(tg.isEnabled());
                TextField tokenField = new TextField(tg.getToken());
                tokenField.setPromptText("Bot Token");
                tokenField.setStyle(fieldStyle); tokenField.setPrefHeight(40);
                TextField proxyField = new TextField(tg.getProxy() != null ? tg.getProxy() : "");
                proxyField.setPromptText("代理地址 (可选)");
                proxyField.setStyle(fieldStyle); proxyField.setPrefHeight(40);
                fields.getChildren().addAll(enabledCheck, tokenField, proxyField);

                addSaveButton(root, dialog, () -> {
                    tg.setEnabled(enabledCheck.isSelected());
                    tg.setToken(tokenField.getText());
                    if (!proxyField.getText().isBlank()) tg.setProxy(proxyField.getText());
                    persistAndRefresh(cfg);
                });
                break;
            }
            case "feishu": {
                config.channel.FeishuConfig fs = ch.getFeishu();
                enabledCheck.setSelected(fs.isEnabled());
                TextField appIdField = new TextField(fs.getAppId());
                appIdField.setPromptText("App ID"); appIdField.setStyle(fieldStyle); appIdField.setPrefHeight(40);
                TextField secretField = new TextField(fs.getAppSecret());
                secretField.setPromptText("App Secret"); secretField.setStyle(fieldStyle); secretField.setPrefHeight(40);
                TextField encryptField = new TextField(fs.getEncryptKey());
                encryptField.setPromptText("Encrypt Key (可选)"); encryptField.setStyle(fieldStyle); encryptField.setPrefHeight(40);
                TextField verifyField = new TextField(fs.getVerificationToken());
                verifyField.setPromptText("Verification Token (可选)"); verifyField.setStyle(fieldStyle); verifyField.setPrefHeight(40);
                fields.getChildren().addAll(enabledCheck, appIdField, secretField, encryptField, verifyField);

                addSaveButton(root, dialog, () -> {
                    fs.setEnabled(enabledCheck.isSelected());
                    fs.setAppId(appIdField.getText());
                    fs.setAppSecret(secretField.getText());
                    if (!encryptField.getText().isBlank()) fs.setEncryptKey(encryptField.getText());
                    if (!verifyField.getText().isBlank()) fs.setVerificationToken(verifyField.getText());
                    persistAndRefresh(cfg);
                });
                break;
            }
            case "email": {
                config.channel.EmailConfig em = ch.getEmail();
                enabledCheck.setSelected(em.isEnabled());
                TextField smtpHost = new TextField(em.getSmtpHost());
                smtpHost.setPromptText("SMTP Host"); smtpHost.setStyle(fieldStyle); smtpHost.setPrefHeight(40);
                TextField smtpUser = new TextField(em.getSmtpUsername());
                smtpUser.setPromptText("SMTP Username"); smtpUser.setStyle(fieldStyle); smtpUser.setPrefHeight(40);
                TextField smtpPass = new TextField(em.getSmtpPassword());
                smtpPass.setPromptText("SMTP Password"); smtpPass.setStyle(fieldStyle); smtpPass.setPrefHeight(40);
                TextField fromAddr = new TextField(em.getFromAddress());
                fromAddr.setPromptText("From Address"); fromAddr.setStyle(fieldStyle); fromAddr.setPrefHeight(40);
                TextField imapHost = new TextField(em.getImapHost());
                imapHost.setPromptText("IMAP Host (可选)"); imapHost.setStyle(fieldStyle); imapHost.setPrefHeight(40);
                TextField imapUser = new TextField(em.getImapUsername());
                imapUser.setPromptText("IMAP Username (可选)"); imapUser.setStyle(fieldStyle); imapUser.setPrefHeight(40);
                TextField imapPass = new TextField(em.getImapPassword());
                imapPass.setPromptText("IMAP Password (可选)"); imapPass.setStyle(fieldStyle); imapPass.setPrefHeight(40);
                fields.getChildren().addAll(enabledCheck, smtpHost, smtpUser, smtpPass, fromAddr,
                    imapHost, imapUser, imapPass);

                addSaveButton(root, dialog, () -> {
                    em.setEnabled(enabledCheck.isSelected());
                    em.setSmtpHost(smtpHost.getText());
                    em.setSmtpUsername(smtpUser.getText());
                    em.setSmtpPassword(smtpPass.getText());
                    em.setFromAddress(fromAddr.getText());
                    if (!imapHost.getText().isBlank()) em.setImapHost(imapHost.getText());
                    if (!imapUser.getText().isBlank()) em.setImapUsername(imapUser.getText());
                    if (!imapPass.getText().isBlank()) em.setImapPassword(imapPass.getText());
                    persistAndRefresh(cfg);
                });
                break;
            }
            default: {
                // Generic: token field
                TextField tokenField = new TextField("");
                tokenField.setPromptText("Token / API Key");
                tokenField.setStyle(fieldStyle); tokenField.setPrefHeight(40);
                fields.getChildren().addAll(enabledCheck, tokenField);
                addSaveButton(root, dialog, () -> persistAndRefresh(cfg));
                break;
            }
        }

        root.getChildren().addAll(title, fields);

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.showAndWait();
    }

    private void addSaveButton(VBox root, Stage dialog, Runnable onSave) {
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
            onSave.run();
            dialog.close();
            refresh();
        });
        btnRow.getChildren().addAll(cancelBtn, saveBtn);
        root.getChildren().add(btnRow);
    }

    private void persistAndRefresh(config.Config cfg) {
        try { config.ConfigIO.saveConfig(cfg, null); } catch (Exception ignored) {}
    }
}
