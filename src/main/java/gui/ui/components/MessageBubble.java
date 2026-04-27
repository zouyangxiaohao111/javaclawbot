package gui.ui.components;

import io.github.raghultech.markdown.javafx.preview.MarkdownWebView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebView;

public class MessageBubble extends HBox {

    private static final double MAX_WIDTH = 700;

    public enum Role { USER, ASSISTANT }

    public MessageBubble(Role role, String content) {
        setSpacing(12);
        setPadding(new Insets(8, 0, 8, 0));

        if (role == Role.USER) {
            setAlignment(Pos.CENTER_RIGHT);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label bubble = new Label(content);
            bubble.getStyleClass().add("user-bubble");
            bubble.setWrapText(true);
            bubble.setMaxWidth(MAX_WIDTH);

            getChildren().addAll(spacer, bubble);
        } else {
            setAlignment(Pos.CENTER_LEFT);

            Label avatar = new Label("\u2728");
            avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
                + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
            avatar.setMinSize(32, 32);

            // 使用 MarkdownWebView (Flexmark + WebView) 渲染助手消息
            MarkdownWebView md = new MarkdownWebView(content, null);
            WebView webView = md.launch();
            webView.setMaxWidth(MAX_WIDTH);
            webView.setPrefWidth(MAX_WIDTH);

            // 根据内容行数估算初始高度，加载完成后 JS 自动调整为精确高度
            int lineCount = (int) content.lines().count();
            double initHeight = Math.min(600, Math.max(40, lineCount * 22 + 24));
            webView.setPrefHeight(initHeight);
            // JavaFX 节点背景透明，内容背景由 CSS 控制
            webView.setStyle("-fx-background-color: transparent;");
            // 注入自定义 CSS（通过 resource URL）
            try {
                String cssUrl = getClass().getResource("/gui/ui/styles/webview.css").toExternalForm();
                webView.getEngine().setUserStyleSheetLocation(cssUrl);
            } catch (Exception ignored) {}

            // 页面加载完成后用 JS 测量内容高度，使 WebView 自适应内容
            webView.getEngine().documentProperty().addListener((obs, old, doc) -> {
                if (doc != null) {
                    Platform.runLater(() -> {
                        try {
                            Object h = webView.getEngine().executeScript(
                                "Math.max(document.body.scrollHeight, "
                                + "document.documentElement.scrollHeight)");
                            if (h instanceof Number) {
                                webView.setPrefHeight(Math.min(
                                    ((Number) h).doubleValue() + 16, 600));
                            }
                        } catch (Exception ignored) {}
                    });
                }
            });

            getChildren().addAll(avatar, webView);
        }
    }
}
