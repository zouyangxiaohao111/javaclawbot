package gui.ui.components;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.web.WebView;

import java.util.List;

public class MessageBubble extends HBox {

    private static final double MAX_WIDTH = 700;

    /** 当 WebView 内容高度被 JS 回调调整后触发，用于通知外层重新滚动到底部 */
    private Runnable onHeightAdjusted;

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;
    private static final String HTML_TEMPLATE;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();

        HTML_TEMPLATE = "<!DOCTYPE html><html style='height:100%;background:transparent;'>"
            + "<head><meta charset='UTF-8'><style>"
            + "html,body{overflow:hidden;}"
            + "html{height:100%;}body{min-height:100%;}"
            + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
            + "font-size:14px;line-height:1.6;color:#1c1c1e;background:transparent;margin:0;padding:12px 16px;}"
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
            + "ul,ol{padding-left:20px;margin:4px 0;}li{margin:2px 0;}"
            + "a{color:#3b82f6;}"
            + "table{border-collapse:collapse;margin:8px 0;font-size:13px;}"
            + "th,td{border:1px solid rgba(0,0,0,0.1);padding:6px 12px;text-align:left;}"
            + "th{background:rgba(0,0,0,0.04);}"
            + "hr{border:none;border-top:1px solid rgba(0,0,0,0.08);margin:12px 0;}"
            + "p{margin:4px 0;}img{max-width:100%;border-radius:8px;}"
            + ".copy-btn{position:absolute;top:6px;right:8px;display:flex;align-items:center;gap:4px;"
            + "background:rgba(0,0,0,0.06);border:1px solid rgba(0,0,0,0.08);border-radius:6px;"
            + "padding:4px 10px;cursor:pointer;font-size:12px;color:rgba(0,0,0,0.4);"
            + "opacity:0;transition:opacity 0.2s;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}"
            + "pre:hover .copy-btn,.copy-btn.copied{opacity:1;}"
            + ".copy-btn:hover{background:rgba(0,0,0,0.1);color:rgba(0,0,0,0.7);}"
            + ".copy-btn.copied{color:#16a34a;}"
            + "</style></head><body>%s<script>"
            + "(function(){var svg='<svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><rect x=\"9\" y=\"9\" width=\"13\" height=\"13\" rx=\"2\" ry=\"2\"/><path d=\"M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1\"/></svg>';"
            + "var pres=document.querySelectorAll('pre');"
            + "for(var i=0;i<pres.length;i++){(function(pre){pre.style.position='relative';"
            + "var btn=document.createElement('button');btn.className='copy-btn';btn.innerHTML=svg;"
            + "btn.onclick=function(e){e.stopPropagation();"
            + "var code=pre.querySelector('code')||pre;var text=code.textContent;"
            + "var done=function(){btn.classList.add('copied');btn.textContent='已复制';"
            + "setTimeout(function(){btn.classList.remove('copied');btn.innerHTML=svg;},2000);};"
            + "try{navigator.clipboard.writeText(text).then(done).catch(function(){fallback();});}"
            + "catch(_){fallback();}"
            + "function fallback(){var ta=document.createElement('textarea');ta.value=text;"
            + "ta.style.cssText='position:fixed;opacity:0;';document.body.appendChild(ta);ta.select();"
            + "document.execCommand('copy');document.body.removeChild(ta);done();}};"
            + "pre.appendChild(btn);})(pres[i]);}})();"
            + "</script></body></html>";
    }

    public enum Role { USER, ASSISTANT }

    /** 创建消息 WebView 气泡（不含 avatar/HBox 包装，供组合使用） */
    public static StackPane createBubbleWebView(String content) {
        String htmlBody = RENDERER.render(PARSER.parse(content));
        String html = HTML_TEMPLATE.replace("%s", htmlBody);

        WebView webView = new WebView();
        webView.setContextMenuEnabled(false);
        double initW = estimateContentWidth(content);
        webView.setPrefWidth(initW);
        webView.setMaxWidth(initW);
        int lineCount = (int) content.lines().count();
        webView.setPrefHeight(Math.max(40, lineCount * 22 + 24));
        webView.getEngine().documentProperty().addListener((obs, old, doc) -> {
            if (doc != null) {
                javafx.application.Platform.runLater(() -> adjustWebViewHeight(webView));
            }
        });

        StackPane bubble = new StackPane(webView);
        bubble.setStyle("-fx-background-color: rgba(0,0,0,0.05);"
            + " -fx-background-radius: 16px;"
            + " -fx-padding: 0;");

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(bubble.widthProperty());
        clip.heightProperty().bind(bubble.heightProperty());
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        bubble.setClip(clip);

        // 滚轮转发
        webView.addEventFilter(ScrollEvent.SCROLL, e -> {
            e.consume();
            javafx.event.Event.fireEvent(bubble, e.copyFor(bubble, bubble));
        });

        // 延迟加载内容：等 bubble 进入场景后再加载，确保宽度已确定
        bubble.sceneProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.scene.Scene> obs,
                                javafx.scene.Scene oldScene, javafx.scene.Scene newScene) {
                if (newScene != null) {
                    bubble.sceneProperty().removeListener(this);
                    Platform.runLater(() -> webView.getEngine().loadContent(html));
                }
            }
        });

        return bubble;
    }

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
            webView.setContextMenuEnabled(false);
            // 初始尺寸：宽度按内容估算，高度按行数估算（JS 回调后会调整为精确值）
            double initW = estimateContentWidth(content);
            webView.setPrefWidth(initW);
            webView.setMaxWidth(initW);
            int lineCount = (int) content.lines().count();
            webView.setPrefHeight(Math.max(40, lineCount * 22 + 24));

            // 页面加载完成后 JS 自适应内容高度
            webView.getEngine().documentProperty().addListener((obs, old, doc) -> {
                if (doc != null) {
                    Platform.runLater(() -> {
                        adjustWebViewHeight(webView);
                        if (onHeightAdjusted != null) {
                            onHeightAdjusted.run();
                        }
                    });
                }
            });

            // 气泡容器：背景 + 圆角（与 .assistant-bubble 一致）
            StackPane bubble = new StackPane(webView);
            bubble.setStyle("-fx-background-color: rgba(0,0,0,0.05);"
                + " -fx-background-radius: 16px;"
                + " -fx-padding: 0;");

            // clip 使圆角对 WebView（native 节点）也生效
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(bubble.widthProperty());
            clip.heightProperty().bind(bubble.heightProperty());
            clip.setArcWidth(32);
            clip.setArcHeight(32);
            bubble.setClip(clip);

            // 右侧 spacer：吸收多余空间
            Region rightSpacer = new Region();
            HBox.setHgrow(rightSpacer, Priority.ALWAYS);

            getChildren().addAll(avatar, bubble, rightSpacer);

            // WebView 是 native 节点，会截获滚轮事件不给外层 ScrollPane。
            // 拦截 SCROLL 事件，复制并转发到 bubble 容器使其冒泡到 ScrollPane。
            webView.addEventFilter(ScrollEvent.SCROLL, e -> {
                e.consume();
                javafx.event.Event.fireEvent(bubble, e.copyFor(bubble, bubble));
            });

            // 宽度根据可用空间自适应。先确定宽度再加载内容，避免窄宽度下测出错误高度。
            sceneProperty().addListener((obs, o, s) -> {
                if (s != null) {
                    updateBubbleWidth(webView, bubble, s.getWidth(), content);
                    Platform.runLater(() -> webView.getEngine().loadContent(html));
                    s.widthProperty().addListener((wObs, wOld, wNew) -> {
                        updateBubbleWidth(webView, bubble, wNew.doubleValue(), content);
                        Platform.runLater(() -> adjustWebViewHeight(webView));
                    });
                }
            });
        }
    }

    /** 根据内容估算最佳宽度（模拟 Label wrapText 的"短则窄、长则宽"效果） */
    private static double estimateContentWidth(String content) {
        double maxLine = 0;
        for (String line : content.split("\n")) {
            double w = line.length() * 8.5; // 14px 中文字符近似宽度
            if (w > maxLine) maxLine = w;
        }
        return Math.min(MAX_WIDTH, Math.max(200, maxLine + 32)); // 32 = body padding
    }

    private static void updateBubbleWidth(WebView wv, StackPane bubble, double sceneWidth, String content) {
        double available = Math.min(MAX_WIDTH, Math.max(300,
            sceneWidth - 256 - 32 - 44));
        // WebView 宽度按内容估算（窄消息不撑满），上限取可用宽度
        double contentW = Math.min(available, estimateContentWidth(content));
        wv.setPrefWidth(contentW);
        wv.setMaxWidth(contentW);
        bubble.setMaxWidth(available);
    }

    public void setOnHeightAdjusted(Runnable callback) {
        this.onHeightAdjusted = callback;
    }

    private static void adjustWebViewHeight(WebView wv) {
        adjustWebViewHeight(wv, 0, 0);
    }

    /** 带重试的高度测量：临时解除 html height:100% 再测量真实内容高度，连续两次一致后确认 */
    private static void adjustWebViewHeight(WebView wv, double prevHeight, int attempt) {
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
            javafx.util.Duration.millis(attempt == 0 ? 100 : 80));
        delay.setOnFinished(ev -> {
            try {
                Object h = wv.getEngine().executeScript(
                    "(function(){var d=document;var e=d.documentElement;"
                    + "var oldH=e.style.height;e.style.height='auto';"
                    + "var sh=Math.max(d.body.scrollHeight,e.scrollHeight);"
                    + "e.style.height=oldH;"
                    + "return sh;})()");
                if (h instanceof Number) {
                    double height = ((Number) h).doubleValue();
                    if (height > 0) {
                        if (prevHeight > 0 && Math.abs(height - prevHeight) < 2) {
                            wv.setPrefHeight(height);
                        } else if (attempt >= 5) {
                            wv.setPrefHeight(height);
                        } else {
                            adjustWebViewHeight(wv, height, attempt + 1);
                        }
                    } else if (attempt < 5) {
                        adjustWebViewHeight(wv, 0, attempt + 1);
                    }
                }
            } catch (Exception ignored) {}
        });
        delay.play();
    }
}
