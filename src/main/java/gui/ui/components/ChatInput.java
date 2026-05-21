package gui.ui.components;

import bus.MessageBus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import gui.ui.BackendBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import session.Session;

import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class ChatInput extends VBox {

    private static final Logger log = LoggerFactory.getLogger(ChatInput.class);

    private final TextArea inputArea;
    private final Button sendButton;
    private final HBox statusBar;
    private final Label leftStatusLabel;
    private final ProjectStatusBadge projectBadge;
    /** 上下文使用率进度条容器 */
    private final HBox contextUsageBar;
    /** 进度条填充区域（宽度动态变化） */
    private final Region contextProgressFill;
    /** 百分比文字标签 */
    private final Label contextPercentLabel;
    private final CompletionPopup completionPopup;
    private final List<Consumer<String>> sendListeners = new ArrayList<>();
    // 附件：图片路径（放入 media）、其他文件路径（拼入消息文本）
    private final List<java.nio.file.Path> imagePaths = new ArrayList<>();
    private final List<java.nio.file.Path> otherFilePaths = new ArrayList<>();
    private final HBox imagePreviewRow;
    private final HBox fileTagRow;

    /** 是否正在等待 LLM 回复 */
    private volatile boolean sending = false;
    /** 停止回调 */
    private Runnable stopCallback;
    /** 模型名点击回调 */
    private Runnable onModelClickHandler;
    /** 当前显示的模型名 */
    private String currentModelDisplayName = "";
    /** 双击 Esc 跟踪 */
    private long lastEscTime = 0;
    private int escCount = 0;
    /** SVG 图标：发送（箭头）/ 停止（方块） */
    private javafx.scene.layout.StackPane sendGraphic;
    private javafx.scene.layout.StackPane stopGraphic;
    private javafx.scene.shape.SVGPath sendSvg;
    private javafx.scene.shape.SVGPath stopSvg;

    // ── 历史消息导航相关字段 ──
    private BackendBridge backendBridge;
    /** 当前历史消息导航索引，-1表示未处于导航状态 */
    private int historyIndex = -1;
    /** 开始导航前保存的草稿文本 */
    private String draftText = "";

    public ChatInput() {
        setSpacing(0);
        setPadding(new Insets(8, 24, 8, 24));

        // 输入卡片
        VBox inputCard = new VBox(4);
        inputCard.setStyle("-fx-background-color: white; -fx-background-radius: 16px; -fx-border-color: rgba(0, 0, 0, 0.1); -fx-border-radius: 16px; -fx-border-width: 1px; -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.05), 4, 0, 0, 1);");
        inputCard.setPadding(new Insets(6, 12, 4, 12));

        // 拖拽手柄：卡片顶部的一条细线，鼠标按住上下拖动调整输入框高度
        Region grabber = new Region();
        grabber.setStyle("-fx-background-color: rgba(0, 0, 0, 0.08);");
        grabber.setPrefHeight(1);
        grabber.setMaxHeight(1);
        grabber.setCursor(javafx.scene.Cursor.V_RESIZE);

        // 文本输入：默认，自动扩展，无焦点边框
        inputArea = new TextArea();
        inputArea.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-font-size: 15px; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-insets: 0;");
        inputArea.setWrapText(true);
        inputArea.setPrefRowCount(10);
        inputArea.setPromptText("输入你的问题，或使用 ALT+↑/↓ 导航历史消息");

        inputArea.setOnKeyPressed(keyEvent -> {
            // Alt+↑/↓ 历史消息导航
            if (keyEvent.isAltDown()) {
                switch (keyEvent.getCode()) {
                    case UP -> {
                        keyEvent.consume();
                        navigateHistory(-1); // 上一条（更早的消息）
                    }
                    case DOWN -> {
                        keyEvent.consume();
                        navigateHistory(1);  // 下一条（更新的消息）
                    }
                }
            }
        });

        // 按钮行
        HBox buttonRow = new HBox(8);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        buttonRow.setPadding(new Insets(0, 0, 0, 0));

        // 图片预览行
        imagePreviewRow = new HBox(6);
        imagePreviewRow.setPadding(new Insets(0, 0, 0, 0));
        imagePreviewRow.setVisible(false);
        imagePreviewRow.setManaged(false);

        // 文件标签行（非图片文件）
        fileTagRow = new HBox(6);
        fileTagRow.setPadding(new Insets(0, 0, 0, 0));
        fileTagRow.setVisible(false);
        fileTagRow.setManaged(false);

        // 附件按钮——SVG 纸夹图标（非 emoji，跨平台渲染一致）
        javafx.scene.shape.SVGPath attachIcon = new javafx.scene.shape.SVGPath();
        attachIcon.setContent("M3.4 20.4c-1.9-1.9-1.9-5.1 0-7L15.6 1.2c1.2-1.2 3.1-1.2 4.2 0 1.2 1.2 1.2 3.1 0 4.2L9.2 16c-.5.5-1.4.5-2 0-.5-.5-.5-1.4 0-1.9l8.8-8.8");
        attachIcon.setStyle("-fx-stroke: rgba(0,0,0,0.4); -fx-stroke-width: 2px;"
            + " -fx-fill: transparent; -fx-stroke-line-cap: round; -fx-stroke-line-join: round;");

        javafx.scene.layout.StackPane attachBtn = new javafx.scene.layout.StackPane(attachIcon);
        attachBtn.setPrefSize(28, 28);
        attachBtn.setMaxSize(28, 28);
        String attachDefault = "-fx-background-color: rgba(0,0,0,0.08); -fx-background-radius: 8px; -fx-cursor: hand;";
        String attachHover  = "-fx-background-color: rgba(0,0,0,0.15); -fx-background-radius: 8px; -fx-cursor: hand;";
        String attachPress  = "-fx-background-color: rgba(0,0,0,0.22); -fx-background-radius: 8px; -fx-cursor: hand;";
        attachBtn.setStyle(attachDefault);
        attachBtn.setOnMouseEntered(e -> {
            attachBtn.setStyle(attachHover);
            attachIcon.setStyle(attachIcon.getStyle().replace("rgba(0,0,0,0.4)", "rgba(0,0,0,0.7)"));
        });
        attachBtn.setOnMouseExited(e -> {
            attachBtn.setStyle(attachDefault);
            attachIcon.setStyle(attachIcon.getStyle().replace("rgba(0,0,0,0.7)", "rgba(0,0,0,0.4)"));
        });
        attachBtn.setOnMousePressed(e -> {
            attachBtn.setStyle(attachPress);
            attachIcon.setStyle(attachIcon.getStyle().replace("rgba(0,0,0,0.4)", "rgba(0,0,0,0.8)"));
        });
        attachBtn.setOnMouseReleased(e -> {
            attachBtn.setStyle(attachHover);
            attachIcon.setStyle(attachIcon.getStyle().replace("rgba(0,0,0,0.8)", "rgba(0,0,0,0.7)"));
        });
        attachBtn.setOnMouseClicked(e -> selectFiles());

        Button mentionBtn = new Button("@");
        mentionBtn.setStyle("-fx-background-color: transparent; -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-background-radius: 8px;");
        mentionBtn.setOnAction(e -> {
            inputArea.insertText(inputArea.getCaretPosition(), "@");
            inputArea.requestFocus();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 发送/停止按钮 —— SVG 图标（跨平台一致渲染）
        this.sendSvg = new javafx.scene.shape.SVGPath();
        this.sendSvg.setContent("M6 8 L13 12 L6 16 Z"); // 右箭头
        this.sendGraphic = new javafx.scene.layout.StackPane(this.sendSvg);
        this.sendGraphic.setPrefSize(20, 20);

        this.stopSvg = new javafx.scene.shape.SVGPath();
        this.stopSvg.setContent("M7 7 L17 7 L17 17 L7 17 Z"); // 方块
        this.stopGraphic = new javafx.scene.layout.StackPane(this.stopSvg);
        this.stopGraphic.setPrefSize(20, 20);

        sendButton = new Button();
        sendButton.setGraphic(sendGraphic);
        String btnBase = "-fx-pref-width: 40px; -fx-pref-height: 40px;"
            + " -fx-background-radius: 10px; -fx-font-size: 18px; -fx-cursor: hand;";
        sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.08);" + btnBase);
        sendSvg.setStyle("-fx-fill: rgba(0,0,0,0.4);");
        stopSvg.setStyle("-fx-fill: rgba(220,38,38,0.7);");

        sendButton.setOnMouseEntered(e -> {
            sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.15);" + btnBase);
            sendSvg.setStyle("-fx-fill: rgba(0,0,0,0.7);");
        });
        sendButton.setOnMouseExited(e -> {
            sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.08);" + btnBase);
            sendSvg.setStyle("-fx-fill: rgba(0,0,0,0.4);");
        });
        sendButton.setOnMousePressed(e -> {
            sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.22);" + btnBase);
            sendSvg.setStyle("-fx-fill: rgba(0,0,0,0.8);");
        });
        sendButton.setOnMouseReleased(e -> {
            sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.15);" + btnBase);
            sendSvg.setStyle("-fx-fill: rgba(0,0,0,0.7);");
        });

        buttonRow.getChildren().addAll(attachBtn, mentionBtn, spacer, sendButton);

        inputCard.getChildren().addAll(grabber, inputArea, imagePreviewRow, fileTagRow, buttonRow);

        // 间距：保持状态栏到卡片和到底部距离一致（各 8px）
        Region gap = new Region();
        gap.setPrefHeight(4);
        gap.setMinHeight(4);

        // 状态栏：左右分布
        statusBar = new HBox();
        statusBar.setPadding(new Insets(0, 16, 0, 16));

        leftStatusLabel = new Label("\u25CF 模型就绪 \u00B7 " + currentModelDisplayName + " \u25BE");
        leftStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #000000; -fx-cursor: hand;");
        leftStatusLabel.setOnMouseEntered(e ->
            leftStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc785c; -fx-underline: true; -fx-cursor: hand;"));
        leftStatusLabel.setOnMouseExited(e ->
            leftStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #000000; -fx-cursor: hand;"));
        leftStatusLabel.setOnMouseClicked(e -> {
            if (onModelClickHandler != null) onModelClickHandler.run();
        });

        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);

        projectBadge = new ProjectStatusBadge();

        // ── 上下文使用率进度条（电池风格） ──
        // 电池外壳：Pane 固定 46×4，灰色圆角边框
        javafx.scene.layout.Pane progressPane = new javafx.scene.layout.Pane();
        progressPane.setPrefSize(46, 4);
        progressPane.setMinSize(46, 4);
        progressPane.setMaxSize(46, 4);
        progressPane.setStyle("-fx-background-color: transparent; -fx-border-color: #d1d5db; -fx-border-width: 1px; -fx-border-radius: 3px;");

        // 电量填充：Region，绝对定位在 Pane 内，左/上各留 1px 边距
        contextProgressFill = new Region();
        contextProgressFill.setPrefWidth(0);
        contextProgressFill.setPrefHeight(2);
        contextProgressFill.setLayoutX(1);
        contextProgressFill.setLayoutY(1);
        contextProgressFill.setStyle("-fx-background-color: #22c55e; -fx-background-radius: 1px;");

        progressPane.getChildren().add(contextProgressFill);

        contextPercentLabel = new Label("0%");
        contextPercentLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: #9ca3af;");

        contextUsageBar = new HBox(4, progressPane, contextPercentLabel);
        contextUsageBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        contextUsageBar.setVisible(false);
        contextUsageBar.setManaged(false);

        // 间距：在标签和进度条之间留空
        Region contextGap = new Region();
        contextGap.setPrefWidth(10);
        contextGap.setMinWidth(10);

        statusBar.getChildren().addAll(leftStatusLabel, contextGap, contextUsageBar, statusSpacer, projectBadge);

        // Completion popup (after inputArea created)
        completionPopup = new CompletionPopup(inputArea);

        getChildren().addAll(inputCard, gap, statusBar);

        // 发送按钮事件：发送中为停止，否则发送
        sendButton.setOnAction(e -> {
            if (sending) {
                triggerStop();
            } else {
                sendMessage();
            }
        });

        // 使用 addEventFilter（捕获阶段）确保在 TextArea 处理 ENTER 之前拦截
        inputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed() || completionPopup.isShowing()) return;
            // 粘贴事件：检查剪贴板中的文件 / 图片，有则走 handleFile 流程
            if (isPasteShortcut(e)) {
                if (handleClipboardPaste()) {
                    e.consume();
                    return;
                }
                // 剪贴板无文件/图片则放行，让 TextArea 正常处理文本粘贴
            }
            // Esc 双击触发停止
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                if (sending) {
                    long now = System.currentTimeMillis();
                    if (now - lastEscTime < 500) {
                        escCount++;
                        if (escCount >= 2) {
                            e.consume();
                            escCount = 0;
                            triggerStop();
                            return;
                        }
                    } else {
                        escCount = 1;
                    }
                    lastEscTime = now;
                }
                return;
            }
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    // Shift+Enter：主动插入换行，不依赖 TextArea 默认行为
                    inputArea.insertText(inputArea.getCaretPosition(), "\n");
                } else {
                    if (sending) {
                        showAlreadySent();
                    } else {
                        sendMessage();
                    }
                }
                e.consume();
            }
        });
    }

    private void sendMessage() {
        String text = inputArea.getText().trim();
        if (!text.isEmpty() || !imagePaths.isEmpty() || !otherFilePaths.isEmpty()) {
            String resolvedText = resolveFileMentions(text);
            for (Consumer<String> listener : sendListeners) {
                listener.accept(resolvedText);
            }
            inputArea.clear();
            clearFiles();
        }
    }

    /**
     * 提取消息中 @绝对路径 的引用，构建文件上下文。
     * @ 补全已填入绝对路径（如 @D:\code\...\Config.java），无需再解析。
     */
    private String resolveFileMentions(String text) {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@([^\\s]+)").matcher(text);
        while (matcher.find()) {
            String ref = matcher.group(1);
            ref = ref.replaceAll("[.,;:!?)\"\'\\]]+$", "");
            if (ref.isEmpty()) continue;
            try {
                java.nio.file.Path p = java.nio.file.Path.of(ref);
                if (p.isAbsolute() && java.nio.file.Files.exists(p)) {
                    paths.add(p.normalize().toString());
                }
            } catch (Exception ignored) {
            }
        }
        if (paths.isEmpty()) return text;

        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            sb.append("用户提供文件：").append(p).append("\n");
        }
        sb.append("\n").append(text);
        return sb.toString();
    }

    private void selectFiles() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择文件");
        chooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("所有支持的文件",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp",
                "*.mp4", "*.avi", "*.mov", "*.mkv", "*.webm",
                "*.txt", "*.md", "*.json", "*.xml", "*.csv",
                "*.pdf", "*.doc", "*.docx", "*.ppt", "*.pptx", "*.xls", "*.xlsx",
                "*.zip", "*.tar", "*.gz", "*.jar",
                "*.java", "*.py", "*.js", "*.ts", "*.go", "*.rs",
                "*.c", "*.cpp", "*.h", "*.html", "*.css", "*.sql",
                "*.yaml", "*.yml", "*.toml", "*.ini", "*.cfg",
                "*.*"));
        java.util.List<java.io.File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files != null) {
            for (java.io.File f : files) {
                handleFile(f.toPath());
            }
        }
    }

    private void handleFile(java.nio.file.Path path) {
        String name = path.getFileName().toString().toLowerCase();
        // 图片：预览 + 加入 media 列表
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")) {
            imagePaths.add(path);
            addImagePreview(path);
            return;
        }
        // 其他文件（含视频）：记录路径，显示标签
        otherFilePaths.add(path);
        addFileTag(path);
    }

    private void addImagePreview(java.nio.file.Path path) {
        // 显示图片缩略图
        javafx.scene.image.Image img = new javafx.scene.image.Image(
            path.toUri().toString(), 80, 60, true, true);
        javafx.scene.image.ImageView imgView = new javafx.scene.image.ImageView(img);
        imgView.setFitWidth(80);
        imgView.setFitHeight(60);
        imgView.setPreserveRatio(true);

        // 点击缩略图查看大图
        imgView.setOnMouseClicked(e -> {
            e.consume();
            showImagePreview(path);
        });

        // 右上角关闭按钮（SVG 绘制 ×），固定位置独立于 imgView
        SVGPath closeSvg = new SVGPath();
        closeSvg.setContent("M5 5 L13 13 M13 5 L5 13");
        closeSvg.setStyle("-fx-stroke: white; -fx-stroke-width: 1.5px; -fx-stroke-line-cap: round;");

        javafx.scene.layout.StackPane closeBtn = new javafx.scene.layout.StackPane(closeSvg);
        closeBtn.setPrefSize(18, 18);
        closeBtn.setMaxSize(18, 18);
        closeBtn.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 9px; -fx-cursor: hand;");
        closeBtn.setPadding(new Insets(0, 0, 0, 0));

        // 先创建空的 container，再分别添加子节点并设置约束
        javafx.scene.layout.StackPane container = new javafx.scene.layout.StackPane();
        container.setStyle("-fx-background-radius: 6px;"
            + " -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 6px; -fx-border-width: 1px;");
        container.getChildren().add(imgView);
        container.getChildren().add(closeBtn);
        // closeBtn 右上角偏移 (-2, -2) 让部分区域超出 container 边界（需要 container 裁剪子节点）
        javafx.scene.layout.StackPane.setAlignment(closeBtn, javafx.geometry.Pos.TOP_RIGHT);
        javafx.scene.layout.StackPane.setMargin(closeBtn, new Insets(-2, -2, 0, 0));

        // 关闭按钮点击：删除缩略图
        closeBtn.setOnMouseClicked(e -> {
            e.consume();
            imagePaths.remove(path);
            imagePreviewRow.getChildren().remove(container);
            if (imagePaths.isEmpty()) {
                imagePreviewRow.setVisible(false);
                imagePreviewRow.setManaged(false);
            }
        });

        imagePreviewRow.getChildren().add(container);
        imagePreviewRow.setVisible(true);
        imagePreviewRow.setManaged(true);
    }

    private void addFileTag(java.nio.file.Path path) {
        Label tag = new Label("\uD83D\uDCC4 " + path.getFileName().toString());
        tag.setStyle("-fx-background-color: rgba(0,0,0,0.05); -fx-background-radius: 8px;"
            + " -fx-padding: 4px 8px; -fx-font-size: 12px; -fx-cursor: hand;");
        tag.setOnMouseClicked(e -> {
            otherFilePaths.remove(path);
            fileTagRow.getChildren().remove(tag);
            if (fileTagRow.getChildren().isEmpty()) {
                fileTagRow.setVisible(false);
                fileTagRow.setManaged(false);
            }
        });
        fileTagRow.getChildren().add(tag);
        fileTagRow.setVisible(true);
        fileTagRow.setManaged(true);
    }

    /** 大图查看弹窗：无边框，半透明背景 */
    private void showImagePreview(java.nio.file.Path path) {
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);

        // 以主窗体为 owner，限制弹窗不超出主窗体
        javafx.stage.Window owner = getScene() != null ? getScene().getWindow() : null;
        double ownerW = owner != null ? owner.getWidth() : 1200;
        double ownerH = owner != null ? owner.getHeight() : 800;

        double maxW = Math.min(ownerW * 0.85, 1000);
        double maxH = Math.min(ownerH * 0.85, 750);

        // 加载原图
        javafx.scene.image.Image img = new javafx.scene.image.Image(
            path.toUri().toString(), maxW, maxH, true, true, true);
        javafx.scene.image.ImageView imgView = new javafx.scene.image.ImageView(img);
        imgView.setPreserveRatio(true);
        imgView.setFitWidth(maxW);
        imgView.setFitHeight(maxH);

        // 右上角关闭按钮（SVG ×）
        SVGPath closeSvg = new SVGPath();
        closeSvg.setContent("M6 6 L18 18 M18 6 L6 18");
        closeSvg.setStyle("-fx-stroke: white; -fx-stroke-width: 2px; -fx-stroke-line-cap: round;");
        javafx.scene.layout.StackPane closeBtn = new javafx.scene.layout.StackPane(closeSvg);
        closeBtn.setPrefSize(28, 28);
        closeBtn.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-background-radius: 14px; -fx-cursor: hand;");
        closeBtn.setOnMouseClicked(e -> stage.close());

        // 底部提示
        Label hint = new Label("点击空白区域或 Esc 关闭");
        hint.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 12px; -fx-padding: 4px 12px;"
            + " -fx-background-color: rgba(0,0,0,0.3); -fx-background-radius: 12px;");
        javafx.scene.layout.StackPane.setAlignment(hint, javafx.geometry.Pos.BOTTOM_CENTER);
        javafx.scene.layout.StackPane.setMargin(hint, new Insets(0, 0, 12, 0));

        // 先设置 closeBtn 约束，再创建 root
        javafx.scene.layout.StackPane.setAlignment(closeBtn, javafx.geometry.Pos.TOP_RIGHT);
        javafx.scene.layout.StackPane.setMargin(closeBtn, new Insets(8, 8, 0, 0));

        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(imgView, closeBtn, hint);
        root.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-background-radius: 12px;");

        // 点击空白区域关闭
        root.setOnMouseClicked(e -> {
            if (e.getTarget() == root) {
                stage.close();
            }
        });

        // Scene 尺寸匹配图片实际尺寸（含 padding 40px），不超出主窗体
        double sceneW = Math.min(maxW + 40, ownerW * 0.95);
        double sceneH = Math.min(maxH + 40, ownerH * 0.95);
        Scene scene = new Scene(root, sceneW, sceneH);
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.close();
        });

        stage.setScene(scene);
        if (owner != null) {
            stage.initOwner(owner);
            // 弹窗居中于主窗口
            stage.setX(owner.getX() + (owner.getWidth() - sceneW) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - sceneH) / 2);
        }
        stage.show();
    }

    /** 检测粘贴快捷键：macOS 用 Meta+V，Windows/Linux 用 Ctrl+V */
    private static boolean isPasteShortcut(KeyEvent e) {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return e.getCode() == KeyCode.V && e.isMetaDown();
        }
        return e.getCode() == KeyCode.V && e.isControlDown();
    }

    /**
     * 处理剪贴板粘贴：文件列表（资源管理器复制）或原始图片数据（截图工具/浏览器复制）。
     * 两种都通过 handleFile() 统一分派。
     * @return true 表示剪贴板中有文件/图片并已处理
     */
    private boolean handleClipboardPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        boolean handled = false;

        // 资源管理器复制的文件（已有磁盘路径）
        if (clipboard.hasFiles()) {
            for (java.io.File f : clipboard.getFiles()) {
                handleFile(f.toPath());
            }
            handled = true;
        }

        // 截图工具 / 浏览器复制的原始图片数据
        if (clipboard.hasImage()) {
            // 优先通过 AWT 系统剪贴板获取 BufferedImage（避免 JavaFX Image 异步加载/像素格式问题）
            BufferedImage awtImage = getAwtClipboardImage();
            if (awtImage != null) {
                try {
                    int imgW = awtImage.getWidth();
                    int imgH = awtImage.getHeight();
                    if (imgW <= 0 || imgH <= 0) {
                        log.warn("AWT剪贴板图片尺寸无效: {}x{}", imgW, imgH);
                        return handled;
                    }
                    Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "javaclawbot", "clipboard");
                    Files.createDirectories(tmpDir);
                    Path tmpFile = tmpDir.resolve("clipboard_" + System.currentTimeMillis() + ".png");
                    boolean written = ImageIO.write(awtImage, "png", tmpFile.toFile());
                    if (!written || !tmpFile.toFile().isFile() || tmpFile.toFile().length() == 0) {
                        log.warn("剪贴板图片保存可能失败: written={}, size={}", written,
                            tmpFile.toFile().isFile() ? tmpFile.toFile().length() : -1);
                        return handled;
                    }
                    log.info("剪贴板图片已保存: {} ({}x{}, {} bytes)",
                        tmpFile, imgW, imgH, tmpFile.toFile().length());
                    handleFile(tmpFile);
                    handled = true;
                } catch (Exception ex) {
                    log.warn("剪贴板图片保存失败", ex);
                }
            } else {
                // 降级：JavaFX Image 方式
                Image fxImage = clipboard.getImage();
                if (fxImage != null) {
                    try {
                        if (fxImage.getProgress() < 1.0) {
                            log.warn("剪贴板图片未完全加载, progress={}", fxImage.getProgress());
                        }
                        int imgW = (int) fxImage.getWidth();
                        int imgH = (int) fxImage.getHeight();
                        if (imgW <= 0 || imgH <= 0) {
                            log.warn("JavaFX剪贴板图片尺寸无效: {}x{}", imgW, imgH);
                            return handled;
                        }
                        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "javaclawbot", "clipboard");
                        Files.createDirectories(tmpDir);
                        Path tmpFile = tmpDir.resolve("clipboard_" + System.currentTimeMillis() + ".png");
                        BufferedImage buffered = javafxImageToBuffered(fxImage);
                        boolean written = ImageIO.write(buffered, "png", tmpFile.toFile());
                        if (!written || !tmpFile.toFile().isFile() || tmpFile.toFile().length() == 0) {
                            log.warn("JavaFX剪贴板图片保存可能失败: written={}, size={}", written,
                                tmpFile.toFile().isFile() ? tmpFile.toFile().length() : -1);
                            return handled;
                        }
                        log.info("剪贴板图片已保存(JavaFX降级): {} ({}x{}, {} bytes)",
                            tmpFile, imgW, imgH, tmpFile.toFile().length());
                        handleFile(tmpFile);
                        handled = true;
                    } catch (Exception ex) {
                        log.warn("JavaFX剪贴板图片保存失败", ex);
                    }
                }
            }
        }

        return handled;
    }

    /**
     * 通过 AWT Toolkit 系统剪贴板获取 BufferedImage，完全绕过 JavaFX Clipboard 的像素格式转换问题。
     * 截图工具（如 Snipaste、微信截图）通常将图片以 DataFlavor.imageFlavor 存入系统剪贴板，
     * AWT 能直接获取原生 BufferedImage，避免 JavaFX Image 异步加载和 premultiplied alpha 问题。
     */
    private static BufferedImage getAwtClipboardImage() {
        try {
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            java.awt.datatransfer.Clipboard systemClipboard = toolkit.getSystemClipboard();
            java.awt.datatransfer.Transferable contents = systemClipboard.getContents(null);
            if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                Object data = contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
                if (data instanceof BufferedImage bi) {
                    return bi;
                }
                if (data instanceof java.awt.Image awtImg) {
                    // 如果 getTransferData 返回的是非 BufferedImage 的 Image，手动转一下
                    int w = awtImg.getWidth(null);
                    int h = awtImg.getHeight(null);
                    if (w > 0 && h > 0) {
                        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D g = bi.createGraphics();
                        g.drawImage(awtImg, 0, 0, null);
                        g.dispose();
                        return bi;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AWT剪贴板图片获取失败", e);
        }
        return null;
    }

    /** JavaFX Image → AWT BufferedImage */
    private static BufferedImage javafxImageToBuffered(Image fxImage) {
        int w = (int) fxImage.getWidth();
        int h = (int) fxImage.getHeight();
        if (w <= 0 || h <= 0) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        // 使用参数管理缓冲区创建，确保与 GraphicsEnvironment 兼容
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = fxImage.getPixelReader();
        // 用 setRGB 批量写入前预先读取整行，减少 JavaFX native 调用
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            reader.getPixels(0, y, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w);
            buffered.setRGB(0, y, w, 1, row, 0, w);
        }
        return buffered;
    }

    private void clearFiles() {
        imagePaths.clear();
        otherFilePaths.clear();
        imagePreviewRow.getChildren().clear();
        imagePreviewRow.setVisible(false);
        imagePreviewRow.setManaged(false);
        fileTagRow.getChildren().clear();
        fileTagRow.setVisible(false);
        fileTagRow.setManaged(false);
    }

    /** 获取所有附件路径（图片+视频+其他文件），用于传给后端 media 列表 */
    public java.util.List<String> getAllAttachmentPaths() {
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (java.nio.file.Path p : imagePaths) {
            paths.add(p.toString());
        }
        for (java.nio.file.Path p : otherFilePaths) {
            paths.add(p.toString());
        }
        return paths;
    }

    /** 获取图片路径列表（用于 media 字段） */
    public java.util.List<String> getAttachedImages() {
        java.util.List<String> paths = new ArrayList<>();
        for (java.nio.file.Path p : imagePaths) {
            paths.add(p.toString());
        }
        return paths;
    }

    public void addSendListener(Consumer<String> listener) {
        sendListeners.add(listener);
    }

    public String getText() {
        return inputArea.getText();
    }

    public void setStatusText(String text) {
        leftStatusLabel.setText(text);
    }

    /**
     * 更新上下文使用率展示。
     * @param ratio 0.0 ~ 1.0，上下文使用比例
     */
    public void setContextUsage(double ratio) {
        log.debug("[ContextUsage] setContextUsage called with ratio={}", ratio);
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) ratio = 0.0;
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int percent = (int) Math.round(clamped * 100);
        int barWidth = (int) Math.round(clamped * 44);
        log.debug("[ContextUsage] clamped={}, percent={}, barWidth={}", clamped, percent, barWidth);

        // 颜色：≤60% 绿 / ≤85% 黄 / >85% 红
        String color;
        if (clamped <= 0.60) {
            color = "#22c55e";
        } else if (clamped <= 0.85) {
            color = "#eab308";
        } else {
            color = "#ef4444";
        }

        javafx.application.Platform.runLater(() -> {
            contextProgressFill.setPrefWidth(barWidth);
            contextProgressFill.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 1px;");
            contextPercentLabel.setText(percent + "%");
            contextPercentLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 600; -fx-text-fill: " + color + ";");

            contextUsageBar.setVisible(true);
            contextUsageBar.setManaged(true);
        });
    }

    public void setWorkspacePath(java.nio.file.Path path) {
        completionPopup.setWorkspacePath(path);
    }

    public void setProjectPath(java.nio.file.Path path) {
        completionPopup.setProjectPath(path);
    }

    /** 设置 ProjectRegistry 引用并刷新徽标 */
    public void setProjectRegistry(providers.cli.ProjectRegistry registry, java.nio.file.Path workspacePath) {
        projectBadge.refresh(registry, workspacePath);
    }

    /** 刷新项目徽标 */
    public void refreshProjectBadge(providers.cli.ProjectRegistry registry, java.nio.file.Path workspacePath) {
        projectBadge.refresh(registry, workspacePath);
    }

    /** 获取 ProjectStatusBadge（用于设置点击回调） */
    public ProjectStatusBadge getProjectBadge() {
        return projectBadge;
    }

    /** 设置停止回调（点击 ⏹ 或双击 Esc 时触发） */
    public void setOnStop(Runnable callback) {
        this.stopCallback = callback;
    }

    /** 设置模型名点击回调 */
    public void setOnModelClick(Runnable handler) {
        this.onModelClickHandler = handler;
    }

    /** 更新状态栏模型显示名称 */
    public void updateModelDisplayName(String modelName) {
        this.currentModelDisplayName = modelName != null ? modelName : "";
        javafx.application.Platform.runLater(() ->
            leftStatusLabel.setText("\u25CF 模型就绪 \u00B7 " + currentModelDisplayName + " \u25BE"));
    }

    /** 获取状态栏左侧标签（用于 ModelSelectorPopup 定位） */
    public Label getLeftStatusLabel() {
        return leftStatusLabel;
    }

    /**
     * 设置 BackendBridge 实例，用于获取历史消息
     */
    public void setBackendBridge(BackendBridge bridge) {
        this.backendBridge = bridge;
        // 传递 SkillsLoader 给自动补全弹窗，用于 / 列出已启用技能
        if (bridge != null) {
            completionPopup.setSkillsLoader(bridge.getSkillsLoader());
        }
    }

    /**
     * 历史消息导航
     * @param direction -1 表示上一条（更早），1 表示下一条（更新）
     */
    private void navigateHistory(int direction) {
        if (backendBridge == null) return;

        // 获取当前会话的用户消息历史
        List<String> userMessages = getUserMessageHistory();
        if (userMessages.isEmpty()) return;

        // 初始化导航状态
        if (historyIndex == -1) {
            // 首次开始导航，保存当前草稿
            draftText = inputArea.getText();
            if (direction == -1) {
                historyIndex = 0; // 从最新的消息开始
            } else {
                return; // 向下导航但还没开始，无操作
            }
        } else {
            // 更新索引
            historyIndex -= direction;
            if (historyIndex < 0) {
                // 恢复到草稿状态
//                historyIndex = 0;
                inputArea.setText(draftText);
                return;
            } else if (historyIndex >= userMessages.size()) {
                // 超过最旧消息，什么都不做
                historyIndex = userMessages.size();
                return;
            }
        }

        // 显示选中的历史消息
        String selectedMessage = userMessages.get(historyIndex);
        inputArea.setText(selectedMessage);
        inputArea.positionCaret(selectedMessage.length());
    }

    /**
     * 获取当前会话中用户发送的消息列表（按时间倒序，最新的在前）
     */
    private List<String> getUserMessageHistory() {
        List<String> result = new ArrayList<>();
        if (backendBridge == null) return result;

        Session session = backendBridge.getCurrentSession();
        if (session == null) return result;

        List<Map<String, Object>> messages = session.getMessages();
        // 逆序遍历：messages 按时间正序排列（最早在前），导航需要最新在前
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            Object role = msg.get("role");
            if ("user".equals(role)) {
                Object content = msg.get("content");
                if (content != null) {
                    String text = content.toString();
                    if (!text.isBlank()) {
                        result.add(text);
                    }
                }
            }
        }
        return result;
    }

    /** 切换到发送中状态：按钮变方块（stop），背景变红 */
    public void setSending(boolean sending) {
        this.sending = sending;
        javafx.application.Platform.runLater(() -> {
            String btnBase = "-fx-pref-width: 40px; -fx-pref-height: 40px;"
                + " -fx-background-radius: 10px; -fx-cursor: hand;";
            if (sending) {
                sendButton.setGraphic(stopGraphic);
                sendButton.setStyle("-fx-background-color: rgba(220, 38, 38, 0.12);" + btnBase);
            } else {
                sendButton.setGraphic(sendGraphic);
                sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.08);" + btnBase);
                sendSvg.setStyle("-fx-fill: rgba(0,0,0,0.4);");
            }
        });
    }

    private void triggerStop() {
        // 立即恢复按钮状态，不依赖后端回调（stopMessage 会清空 responseCallback）
        setSending(false);
        if (stopCallback != null) {
            stopCallback.run();
        }
    }

    private void showAlreadySent() {
        javafx.application.Platform.runLater(() -> {
            Label toast = new Label("消息已发送");
            toast.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7); -fx-text-fill: white;"
                + " -fx-background-radius: 8px; -fx-padding: 6px 16px; -fx-font-size: 13px;");
            toast.setAlignment(javafx.geometry.Pos.CENTER);
            javafx.scene.layout.StackPane overlay = new javafx.scene.layout.StackPane(toast);
            overlay.setMouseTransparent(true);
            overlay.setPadding(new javafx.geometry.Insets(0, 0, 80, 0));
            overlay.setAlignment(javafx.geometry.Pos.BOTTOM_CENTER);
            // 找到最顶层的 root 来显示 toast
            if (getScene() != null && getScene().getRoot() instanceof javafx.scene.layout.Pane root) {
                root.getChildren().add(overlay);
                javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(
                    javafx.util.Duration.seconds(1.5));
                pt.setOnFinished(ev -> root.getChildren().remove(overlay));
                pt.play();
            }
        });
    }
}
