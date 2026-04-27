package gui.ui.components;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebView;

import java.util.List;

public class MessageBubble extends HBox {

    private static final double MAX_WIDTH = 700;

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;
    private static final String HTML_TEMPLATE;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();

        HTML_TEMPLATE = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
            + "<style>"
            + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
            + "font-size:14px;line-height:1.6;color:rgba(0,0,0,0.85);background:transparent;margin:0;padding:0;}"
            + "pre{background:rgba(0,0,0,0.04);border:1px solid rgba(0,0,0,0.08);border-radius:8px;"
            + "padding:12px 16px;overflow-x:auto;font-family:'JetBrains Mono','Fira Code',monospace;font-size:13px;line-height:1.5;}"
            + "code{font-family:'JetBrains Mono','Fira Code',monospace;font-size:13px;"
            + "background:rgba(0,0,0,0.04);padding:2px 6px;border-radius:3px;}"
            + "pre code{background:transparent;padding:0;border-radius:0;}"
            + "blockquote{border-left:3px solid rgba(0,0,0,0.15);margin:8px 0;padding:4px 12px;"
            + "color:rgba(0,0,0,0.65);background:rgba(0,0,0,0.02);border-radius:0 4px 4px 0;}"
            + "h1{font-size:20px;font-weight:700;margin:12px 0 4px;}"
            + "h2{font-size:17px;font-weight:700;margin:10px 0 4px;}"
            + "h3{font-size:15px;font-weight:600;margin:8px 0 4px;}"
            + "ul,ol{padding-left:20px;margin:4px 0;}"
            + "li{margin:2px 0;}"
            + "a{color:#3b82f6;}"
            + "table{border-collapse:collapse;margin:8px 0;font-size:13px;}"
            + "th,td{border:1px solid rgba(0,0,0,0.1);padding:6px 12px;text-align:left;}"
            + "th{background:rgba(0,0,0,0.04);}"
            + "hr{border:none;border-top:1px solid rgba(0,0,0,0.08);margin:12px 0;}"
            + "p{margin:4px 0;}"
            + "img{max-width:100%;border-radius:8px;}"
            + "</style></head><body>%s</body></html>";
    }

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
            setFillHeight(false);

            Label avatar = new Label("\u2728");
            avatar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 999px;"
                + " -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-alignment: center;");
            avatar.setMinSize(32, 32);

            // Flexmark: markdown → HTML
            String htmlBody = RENDERER.render(PARSER.parse(content));
            String html = HTML_TEMPLATE.replace("%s", htmlBody);

            WebView webView = new WebView();
            webView.setStyle("-fx-background-color: transparent;");
            webView.setContextMenuEnabled(false);

            // 设置初始宽度（加入场景后由 scene 监听器更新为精确值）
            webView.setPrefWidth(600);
            webView.setMaxWidth(MAX_WIDTH);

            webView.getEngine().loadContent(html);

            // 页面加载完成后 JS 自适应内容高度
            webView.getEngine().documentProperty().addListener((obs, old, doc) -> {
                if (doc != null) {
                    Platform.runLater(() -> adjustWebViewHeight(webView));
                }
            });

            // 右侧 spacer：吸收多余空间，防止 HBox 膨胀溢出
            Region rightSpacer = new Region();
            HBox.setHgrow(rightSpacer, Priority.ALWAYS);

            getChildren().addAll(avatar, webView, rightSpacer);

            // 宽度根据可用空间自适应（max 700, min 300）
            sceneProperty().addListener((obs, o, s) -> {
                if (s != null) {
                    updateWebViewWidth(webView, s.getWidth());
                    s.widthProperty().addListener((wObs, wOld, wNew) -> {
                        updateWebViewWidth(webView, wNew.doubleValue());
                        Platform.runLater(() -> adjustWebViewHeight(webView));
                    });
                }
            });
        }
    }

    private static void updateWebViewWidth(WebView wv, double sceneWidth) {
        double cw = Math.min(MAX_WIDTH, Math.max(300,
            sceneWidth - 256 - 32 - 44));
        wv.setPrefWidth(cw);
        wv.setMaxWidth(cw);
    }

    private static void adjustWebViewHeight(WebView wv) {
        try {
            Object h = wv.getEngine().executeScript(
                "Math.max(document.body.scrollHeight, "
                + "document.documentElement.scrollHeight)");
            if (h instanceof Number) {
                wv.setPrefHeight(Math.min(
                    ((Number) h).doubleValue() + 16, 600));
            }
        } catch (Exception ignored) {}
    }
}
