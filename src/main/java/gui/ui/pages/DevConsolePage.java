package gui.ui.pages;

import gui.ui.LogEntry;
import gui.ui.LogWatcher;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DevConsolePage extends VBox {

    private static final int MAX_BUFFER_SIZE = 5000;

    private final ConcurrentLinkedQueue<LogEntry> logBuffer = new ConcurrentLinkedQueue<>();
    private final LogWatcher logWatcher = new LogWatcher(logBuffer);

    private WebView webView;
    private WebEngine engine;
    private Label lineCountLabel;
    private Label statusLabel;

    private ComboBox<String> levelFilter;
    private TextField searchField;
    private ToggleButton autoScrollBtn;

    private Timeline uiTimer;
    private boolean autoScroll = true;

    public DevConsolePage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        // Toolbar
        HBox toolbar = createToolbar();

        // WebView
        webView = createWebView();

        // StatusBar
        HBox statusBar = createStatusBar();

        getChildren().addAll(toolbar, webView, statusBar);
        VBox.setVgrow(webView, Priority.ALWAYS);

        // 页面可见时启停 LogWatcher 和 UI 定时器
        visibleProperty().addListener((obs, old, visible) -> {
            if (visible) {
                startLogWatcher();
                startUITimer();
            } else {
                stopUITimer();
            }
        });
    }

    // ========== Toolbar ==========

    private HBox createToolbar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #eae8e1; -fx-border-color: rgba(0,0,0,0.06); -fx-border-width: 0 0 1px 0;");

        // 级别过滤
        Label filterLabel = new Label("级别:");
        filterLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0,0,0,0.5);");
        levelFilter = new ComboBox<>();
        levelFilter.getItems().addAll("ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        levelFilter.setValue("ALL");
        levelFilter.setPrefWidth(90);
        levelFilter.setStyle("-fx-background-color: white; -fx-background-radius: 6px; -fx-border-color: transparent; -fx-font-size: 12px;");
        levelFilter.setOnAction(e -> {
            String level = levelFilter.getValue();
            if (engine != null) {
                Platform.runLater(() -> engine.executeScript("setFilter('" + level + "')"));
            }
        });

        // 搜索框
        searchField = new TextField();
        searchField.setPromptText("搜索日志...");
        searchField.setPrefWidth(200);
        searchField.setStyle("-fx-background-color: white; -fx-background-radius: 6px; -fx-border-color: transparent; -fx-font-size: 12px; -fx-padding: 6px 10px;");
        searchField.textProperty().addListener((obs, old, text) -> {
            if (engine != null) {
                Platform.runLater(() -> engine.executeScript("search('" + escapeJs(text) + "')"));
            }
        });

        // 自动滚动
        autoScrollBtn = new ToggleButton("自动滚动");
        autoScrollBtn.setSelected(true);
        autoScrollBtn.setStyle("-fx-background-color: white; -fx-background-radius: 6px; -fx-border-color: transparent; -fx-font-size: 12px; -fx-padding: 6px 10px;");
        autoScrollBtn.selectedProperty().addListener((obs, old, sel) -> {
            autoScroll = sel;
            if (engine != null) {
                Platform.runLater(() -> engine.executeScript("setAutoScroll(" + sel + ")"));
            }
        });

        // 导出
        Button exportBtn = new Button("导出");
        exportBtn.setStyle("-fx-background-color: white; -fx-background-radius: 6px; -fx-border-color: transparent; -fx-font-size: 12px; -fx-padding: 6px 12px;");
        exportBtn.setOnAction(e -> exportLogs());

        // 清除
        Button clearBtn = new Button("清除");
        clearBtn.setStyle("-fx-background-color: white; -fx-background-radius: 6px; -fx-border-color: transparent; -fx-font-size: 12px; -fx-padding: 6px 12px;");
        clearBtn.setOnAction(e -> clearLogs());

        bar.getChildren().addAll(filterLabel, levelFilter, searchField, autoScrollBtn, exportBtn, clearBtn);
        return bar;
    }

    // ========== WebView ==========

    private WebView createWebView() {
        WebView wv = new WebView();
        wv.setStyle("-fx-background-color: #f8f6f0;");
        engine = wv.getEngine();

        String html = loadHtmlTemplate();
        engine.loadContent(html);

        return wv;
    }

    private String loadHtmlTemplate() {
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n<style>\n" +
            "  body { background: #f8f6f0; font-family: 'Consolas', 'Courier New', monospace;\n" +
            "    font-size: 13px; line-height: 1.6; margin: 0; padding: 8px; }\n" +
            "  .log-line { white-space: pre-wrap; word-break: break-all; padding: 2px 8px; }\n" +
            "  .log-line .level-tag { display: inline-block; padding: 0px 6px; border-radius: 3px;\n" +
            "    margin-right: 4px; font-weight: 600; font-size: 12px; }\n" +
            "  .log-line.ERROR { background: #fee2e2; }\n" +
            "  .log-line.ERROR .level-tag { background: #dc2626; color: #fff; }\n" +
            "  .log-line.WARN  { background: #fef3c7; }\n" +
            "  .log-line.WARN  .level-tag { background: #f59e0b; color: #fff; }\n" +
            "  .log-line.INFO  { color: #374151; }\n" +
            "  .log-line.INFO  .level-tag { background: #dbeafe; color: #1e40af; }\n" +
            "  .log-line.DEBUG { color: #6b7280; }\n" +
            "  .log-line.DEBUG .level-tag { color: #6b7280; font-weight: 400; }\n" +
            "  .log-line.TRACE { color: #9ca3af; }\n" +
            "  .log-line.TRACE .level-tag { color: #9ca3af; font-weight: 400; }\n" +
            "  .highlight { background: #fde68a; border-radius: 2px; }\n" +
            "</style>\n</head>\n<body>\n<div id=\"log-container\"></div>\n<script>\n" +
            "  var MAX_LINES = " + MAX_BUFFER_SIZE + ";\n" +
            "  var currentLevel = 'ALL';\n" +
            "  var searchTerm = '';\n" +
            "  var autoScroll = true;\n" +
            "  function appendLog(ts, level, logger, msg) {\n" +
            "    if (currentLevel !== 'ALL' && level !== currentLevel) return;\n" +
            "    var line = document.createElement('div');\n" +
            "    line.className = 'log-line ' + level;\n" +
            "    line.innerHTML = '[' + escapeHtml(ts) + '] ' +\n" +
            "      '<span class=\"level-tag\">' + level + '</span> ' +\n" +
            "      escapeHtml(logger) + ' - ' + escapeHtml(msg);\n" +
            "    applyHighlight(line);\n" +
            "    document.getElementById('log-container').appendChild(line);\n" +
            "    trimLines();\n" +
            "    if (autoScroll) window.scrollTo(0, document.body.scrollHeight);\n" +
            "  }\n" +
            "  function setFilter(level) {\n" +
            "    currentLevel = level;\n" +
            "    var lines = document.querySelectorAll('.log-line');\n" +
            "    for (var i = 0; i < lines.length; i++) {\n" +
            "      var cls = lines[i].classList;\n" +
            "      lines[i].style.display = (level === 'ALL' || cls.contains(level)) ? '' : 'none';\n" +
            "    }\n" +
            "    if (autoScroll) window.scrollTo(0, document.body.scrollHeight);\n" +
            "  }\n" +
            "  function setAutoScroll(val) { autoScroll = val; }\n" +
            "  function search(keyword) {\n" +
            "    searchTerm = keyword;\n" +
            "    var lines = document.querySelectorAll('.log-line');\n" +
            "    for (var i = 0; i < lines.length; i++) { applyHighlight(lines[i]); }\n" +
            "    if (keyword) {\n" +
            "      for (var i = 0; i < lines.length; i++) {\n" +
            "        if (lines[i].style.display !== 'none' &&\n" +
            "            lines[i].textContent.toLowerCase().indexOf(keyword.toLowerCase()) >= 0) {\n" +
            "          lines[i].scrollIntoView({block: 'center'});\n" +
            "          break;\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "  function applyHighlight(el) {\n" +
            "    if (!searchTerm) return;\n" +
            "    var text = el.textContent;\n" +
            "    var idx = text.toLowerCase().indexOf(searchTerm.toLowerCase());\n" +
            "    if (idx >= 0) {\n" +
            "      el.innerHTML = escapeHtml(text.substring(0, idx)) +\n" +
            "        '<span class=\"highlight\">' +\n" +
            "        escapeHtml(text.substring(idx, idx + searchTerm.length)) +\n" +
            "        '</span>' + escapeHtml(text.substring(idx + searchTerm.length));\n" +
            "    }\n" +
            "  }\n" +
            "  function escapeHtml(s) {\n" +
            "    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');\n" +
            "  }\n" +
            "  function trimLines() {\n" +
            "    var container = document.getElementById('log-container');\n" +
            "    while (container.children.length > MAX_LINES) {\n" +
            "      container.removeChild(container.firstChild);\n" +
            "    }\n" +
            "  }\n" +
            "  function getSelectedText() { return window.getSelection().toString(); }\n" +
            "  function clearAll() { document.getElementById('log-container').innerHTML = ''; }\n" +
            "  function getLineCount() { return document.getElementById('log-container').children.length; }\n" +
            "</script>\n</body>\n</html>";
    }

    // ========== StatusBar ==========

    private HBox createStatusBar() {
        HBox bar = new HBox(16);
        bar.setPadding(new Insets(6, 16, 6, 16));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #eae8e1; -fx-border-color: rgba(0,0,0,0.06); -fx-border-width: 1px 0 0 0;");

        Label fileLabel = new Label("\uD83D\uDCC4 ~/.javaclawbot/logs/app.log");
        fileLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.5);");

        lineCountLabel = new Label("共 0 行");
        lineCountLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.5);");

        statusLabel = new Label("\u25CF 未启动");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");

        bar.getChildren().addAll(fileLabel, lineCountLabel, statusLabel);
        return bar;
    }

    // ========== Lifecycle ==========

    private void startLogWatcher() {
        if (!logWatcher.isStopped() && statusLabel.getText().contains("监听中")) return;
        logWatcher.start();
        Platform.runLater(() -> {
            statusLabel.setText("\u25CF 监听中");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #16a34a;");
        });
    }

    private void startUITimer() {
        if (uiTimer != null) return;
        uiTimer = new Timeline(new KeyFrame(Duration.millis(60), e -> {
            while (logBuffer.peek() != null) {
                LogEntry entry = logBuffer.poll();
                if (entry != null && engine != null) {
                    engine.executeScript("appendLog('" + escapeJs(entry.timestamp()) + "','"
                        + entry.level() + "','" + escapeJs(entry.logger()) + "','"
                        + escapeJs(entry.message()) + "')");
                }
            }
            // 更新行数
            if (engine != null) {
                Object count = engine.executeScript("getLineCount()");
                if (count instanceof Number) {
                    lineCountLabel.setText("共 " + ((Number) count).intValue() + " 行");
                }
            }
        }));
        uiTimer.setCycleCount(Timeline.INDEFINITE);
        uiTimer.play();
    }

    private void stopUITimer() {
        if (uiTimer != null) {
            uiTimer.stop();
            uiTimer = null;
        }
    }

    // ========== Actions ==========

    private void exportLogs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出日志");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("日志文件", "*.log"));
        chooser.setInitialFileName("app.log");
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;

        try (FileWriter fw = new FileWriter(file)) {
            if (engine != null) {
                Object text = engine.executeScript(
                    "Array.from(document.querySelectorAll('.log-line')).map(function(e){return e.textContent;}).join('\\n')");
                if (text instanceof String) {
                    fw.write((String) text);
                }
            }
        } catch (IOException ex) {
            // 导出失败静默处理
        }
    }

    private void clearLogs() {
        logBuffer.clear();
        if (engine != null) {
            Platform.runLater(() -> {
                engine.executeScript("clearAll()");
                lineCountLabel.setText("共 0 行");
            });
        }
    }

    // ========== Util ==========

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
