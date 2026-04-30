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
    private final Label statusBar;
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

        // 状态栏
        statusBar = new Label("\u25CF 模型就绪 \u00B7 Claude Sonnet 4");
        statusBar.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0, 0, 0, 0.36);");
        statusBar.setPadding(new Insets(0, 16, 0, 16));
        statusBar.setAlignment(Pos.CENTER);

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
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                if (sending) {
                    showAlreadySent();
                } else {
                    sendMessage();
                }
            }
            // Shift+Enter 不拦截，让 TextArea 正常插入换行
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
     * 将消息中的 @文件名 引用解析为完整的文件路径上下文。
     * 例如 "@License 解释一下" -> "用户提供文件：/path/to/License\n解释一下"
     */
    private String resolveFileMentions(String text) {
        StringBuilder result = new StringBuilder();
        StringBuilder remaining = new StringBuilder(text);
        java.util.Set<String> resolvedPaths = new java.util.LinkedHashSet<>();

        // 在 workspace/project 目录中查找 @ 引用的文件
        java.nio.file.Path base = completionPopup.getBasePath();
        if (base == null) return text;

        // 查找所有 @word 格式的引用
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("@(\\S+)").matcher(text);
        while (matcher.find()) {
            String ref = matcher.group(1);
            // 去除末尾标点符号
            ref = ref.replaceAll("[.,;:!?)]+$", "");
            if (ref.isEmpty()) continue;

            // 尝试在 base 目录下查找匹配的文件
            java.nio.file.Path resolved = resolveFileName(base, ref);
            if (resolved != null) {
                resolvedPaths.add(resolved.toString());
            }
        }

        // 构建最终消息：文件路径 + 原始消息（去除 @引用）
        if (!resolvedPaths.isEmpty()) {
            for (String path : resolvedPaths) {
                result.append("用户提供文件：").append(path).append("\n");
            }
            result.append("\n");
        }
        result.append(text);
        return result.toString();
    }

    /**
     * 在 base 目录下查找匹配 name 的文件（支持部分名称匹配）
     */
    private java.nio.file.Path resolveFileName(java.nio.file.Path base, String name) {
        // 先尝试直接路径解析
        java.nio.file.Path direct = base.resolve(name);
        if (java.nio.file.Files.exists(direct)) return direct.toAbsolutePath().normalize();

        // 在 base 下搜索匹配的文件名（限制深度避免性能问题）
        try {
            try (var stream = java.nio.file.Files.list(base)) {
                return stream
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        return fn.equalsIgnoreCase(name) || fn.toLowerCase().startsWith(name.toLowerCase());
                    })
                    .findFirst()
                    .orElse(null);
            }
        } catch (Exception ignored) {
            return null;
        }
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
            Image fxImage = clipboard.getImage();
            if (fxImage != null) {
                try {
                    Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "javaclawbot", "clipboard");
                    Files.createDirectories(tmpDir);
                    Path tmpFile = tmpDir.resolve("clipboard_" + System.currentTimeMillis() + ".png");
                    BufferedImage buffered = javafxImageToBuffered(fxImage);
                    ImageIO.write(buffered, "png", tmpFile.toFile());
                    handleFile(tmpFile);
                    handled = true;
                } catch (Exception ex) {
                    log.warn("剪贴板图片保存失败", ex);
                }
            }
        }

        return handled;
    }

    /** JavaFX Image → AWT BufferedImage */
    private static BufferedImage javafxImageToBuffered(Image fxImage) {
        int w = (int) fxImage.getWidth();
        int h = (int) fxImage.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = fxImage.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
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
        statusBar.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0, 0, 0, 0.36);");
        statusBar.setText(text);
    }

    public void setWorkspacePath(java.nio.file.Path path) {
        completionPopup.setWorkspacePath(path);
    }

    public void setProjectPath(java.nio.file.Path path) {
        completionPopup.setProjectPath(path);
    }

    /** 设置停止回调（点击 ⏹ 或双击 Esc 时触发） */
    public void setOnStop(Runnable callback) {
        this.stopCallback = callback;
    }

    /**
     * 设置 BackendBridge 实例，用于获取历史消息
     */
    public void setBackendBridge(BackendBridge bridge) {
        this.backendBridge = bridge;
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
