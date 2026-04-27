package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChatInput extends VBox {

    private final TextArea inputArea;
    private final Button sendButton;
    private final Label statusBar;
    private final CompletionPopup completionPopup;
    private final List<Consumer<String>> sendListeners = new ArrayList<>();
    private final List<java.nio.file.Path> attachedImages = new ArrayList<>();
    private final HBox imagePreviewRow;

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
        inputArea.setPromptText("输入你的问题...");

        // 按钮行
        HBox buttonRow = new HBox(8);
        buttonRow.setAlignment(Pos.CENTER_LEFT);
        buttonRow.setPadding(new Insets(0, 0, 0, 0));

        // 图片预览行
        imagePreviewRow = new HBox(6);
        imagePreviewRow.setPadding(new Insets(0, 0, 0, 0));
        imagePreviewRow.setVisible(false);
        imagePreviewRow.setManaged(false);

        Button attachBtn = new Button("\uD83D\uDCCE");
        attachBtn.setStyle("-fx-background-color: transparent; -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-background-radius: 8px;");
        attachBtn.setOnAction(e -> selectImages());

        Button mentionBtn = new Button("@");
        mentionBtn.setStyle("-fx-background-color: transparent; -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-background-radius: 8px;");
        mentionBtn.setOnAction(e -> {
            inputArea.insertText(inputArea.getCaretPosition(), "@");
            inputArea.requestFocus();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        sendButton = new Button("\u27A4");
        sendButton.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-pref-width: 32px; -fx-pref-height: 32px; -fx-background-radius: 8px;");

        buttonRow.getChildren().addAll(attachBtn, mentionBtn, spacer, sendButton);

        inputCard.getChildren().addAll(grabber, inputArea, imagePreviewRow, buttonRow);

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

        // 发送按钮事件
        sendButton.setOnAction(e -> sendMessage());
        inputArea.setOnKeyPressed(e -> {
            // 若事件已被 popup 消费（如 ENTER 选中文件），则不处理
            if (e.isConsumed() || completionPopup.isShowing()) return;
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    inputArea.insertText(inputArea.getCaretPosition(), "\n");
                } else {
                    sendMessage();
                }
                e.consume();
            }
        });
    }

    private void sendMessage() {
        String text = inputArea.getText().trim();
        if (!text.isEmpty() || !attachedImages.isEmpty()) {
            String resolvedText = resolveFileMentions(text);
            for (Consumer<String> listener : sendListeners) {
                listener.accept(resolvedText);
            }
            inputArea.clear();
            clearImages();
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

    private void selectImages() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择图片");
        chooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("图片文件", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
        java.util.List<java.io.File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files != null) {
            for (java.io.File f : files) {
                attachedImages.add(f.toPath());
                addImagePreview(f.toPath());
            }
        }
    }

    private void addImagePreview(java.nio.file.Path path) {
        Label preview = new Label("\uD83D\uDDBC " + path.getFileName().toString());
        preview.setStyle("-fx-background-color: rgba(0,0,0,0.05); -fx-background-radius: 8px; "
            + "-fx-padding: 4px 8px; -fx-font-size: 12px;");
        preview.setOnMouseClicked(e -> {
            attachedImages.remove(path);
            imagePreviewRow.getChildren().remove(preview);
            if (attachedImages.isEmpty()) {
                imagePreviewRow.setVisible(false);
                imagePreviewRow.setManaged(false);
            }
        });
        imagePreviewRow.getChildren().add(preview);
        imagePreviewRow.setVisible(true);
        imagePreviewRow.setManaged(true);
    }

    private void clearImages() {
        attachedImages.clear();
        imagePreviewRow.getChildren().clear();
        imagePreviewRow.setVisible(false);
        imagePreviewRow.setManaged(false);
    }

    public java.util.List<String> getAttachedImages() {
        java.util.List<String> paths = new ArrayList<>();
        for (java.nio.file.Path p : attachedImages) {
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
        statusBar.setText(text);
    }

    public void setWorkspacePath(java.nio.file.Path path) {
        completionPopup.setWorkspacePath(path);
    }

    public void setProjectPath(java.nio.file.Path path) {
        completionPopup.setProjectPath(path);
    }
}
