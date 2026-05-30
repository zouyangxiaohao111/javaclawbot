package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具调用卡片。
 * Header 使用 JavaFX（状态图标、工具名、时间戳、展开箭头），保持交互。
 * 文本工具使用 TextArea 渲染（确定性高度），edit_file/write_file/TodoWrite 使用专属 JavaFX 组件。
 */
@Slf4j
public class ToolCallCard extends VBox {

    private boolean expanded;
    private final VBox contentBox;
    private final TextArea contentTextArea;
    private final ScrollPane contentScrollPane;
    private final Label expandIcon;
    private final Label statusIcon;
    private final Label timestampLabel;

    public ToolCallCard(String toolName, String status, String params) {
        this(toolName, status, params, false, "");
    }

    public ToolCallCard(String toolName, String status, String params, boolean startExpanded) {
        this(toolName, status, params, startExpanded, "");
    }

    public ToolCallCard(String toolName, String status, String params, boolean startExpanded, String timestamp) {
        setSpacing(0);
        getStyleClass().add("tool-call-card");
        this.expanded = startExpanded;

        // ===== Header (JavaFX) =====
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 12, 6, 12));
        header.setCursor(javafx.scene.Cursor.HAND);

        statusIcon = new Label("✓");
        applyStatusStyle(status);

        Label toolIcon = new Label("🔧");
        toolIcon.setStyle("-fx-opacity: 0.6;");

        Label nameLabel = new Label(toolName);
        nameLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        timestampLabel = new Label(timestamp);
        timestampLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(0,0,0,0.3);");

        expandIcon = new Label(startExpanded ? "▼" : "▶");
        HBox.setHgrow(expandIcon, Priority.ALWAYS);
        expandIcon.setAlignment(Pos.CENTER_RIGHT);

        header.getChildren().addAll(statusIcon, toolIcon, nameLabel, timestampLabel, expandIcon);
        header.setOnMouseClicked(e -> toggle());

        // ===== Content TextArea (replaces WebView — no HTML/JS, deterministic sizing) =====
        contentTextArea = new TextArea();
        contentTextArea.setEditable(false);
        contentTextArea.setWrapText(true);
        contentTextArea.setStyle("-fx-font-family: 'JetBrains Mono', 'Fira Code', monospace; "
                + "-fx-font-size: 11px; -fx-control-inner-background: rgba(0,0,0,0.02); "
                + "-fx-background-color: transparent; -fx-text-fill: rgba(0,0,0,0.7);");

        this.contentScrollPane = new ScrollPane(contentTextArea);
        this.contentScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        this.contentScrollPane.setFitToWidth(true);
        this.contentScrollPane.setMaxHeight(400);
        this.contentScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        contentBox = new VBox(0);
        contentBox.getChildren().add(this.contentScrollPane);
        contentBox.setPadding(new Insets(0, 12, 8, 12));
        contentBox.setVisible(startExpanded);
        contentBox.setManaged(startExpanded);

        // Load initial params as plain text
        if (params != null && !params.isBlank()) {
            setContent(params);
        }

        getChildren().addAll(header, contentBox);
    }

    // ===== Styling =====

    private void applyStatusStyle(String status) {
        if ("running".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)) {
            statusIcon.setText("○");
            statusIcon.setStyle("-fx-text-fill: #f59e0b;");
        } else {
            statusIcon.setText("✓");
            statusIcon.setStyle("-fx-text-fill: #22c55e;");
        }
    }

    // ===== Public API =====

    /** Append tool result text */
    public void addResult(String result) {
        String current = contentTextArea.getText();
        StringBuilder sb = new StringBuilder();
        if (current != null && !current.isBlank()) {
            sb.append(current);
            sb.append("\n\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━");
            sb.append("\n\n");
        }
        sb.append(result);
        setContent(sb.toString());
    }

    /** Append structured JavaFX content (e.g., TodoWrite result).
     *  Hides the TextArea since native content replaces it — eliminates blank space. */
    public void addStructuredContent(javafx.scene.Node node) {
        contentScrollPane.setVisible(false);
        contentScrollPane.setManaged(false);
        contentBox.getChildren().add(node);
    }

    /** Set status */
    public void setStatus(String status) {
        applyStatusStyle(status);
    }

    /** Set timestamp */
    public void setTimestamp(String timestamp) {
        timestampLabel.setText(timestamp);
    }

    /** Replace params content */
    public void setParams(String params) {
        setContent(params);
    }

    // ===== File Edit Structured Display (unchanged — pure JavaFX HBox) =====

    /** File path for edit_file/write_file cards (set by MainStage) */
    private java.nio.file.Path fileEditPath;
    /** BackupManager reference for rollback/diff (set by MainStage) */
    private agent.tool.file.FileBackupManager fileBackupManager;
    /** Diff/rollback callback target (MainStage or ChatPage) */
    private java.util.function.BiConsumer<String, agent.tool.file.FileBackupManager.BackupEntry> onFileEditAction;

    /**
     * Render a structured file-change summary for edit_file/write_file results.
     * Uses pure JavaFX HBox — no WebView, no height measurement, no blank space.
     */
    public void setFileEditResult(String filePath, int addedLines, int removedLines,
                                   agent.tool.file.FileBackupManager backupManager,
                                   java.util.function.BiConsumer<String, agent.tool.file.FileBackupManager.BackupEntry> actionHandler) {
        this.fileEditPath = java.nio.file.Path.of(filePath);
        this.fileBackupManager = backupManager;
        this.onFileEditAction = actionHandler;

        // Hide TextArea — file content is now rendered natively
        contentScrollPane.setVisible(false);
        contentScrollPane.setManaged(false);

        // Remove any previous file rows
        contentBox.getChildren().removeIf(n -> n != contentScrollPane && (n instanceof HBox));

        // Build native JavaFX file row
        HBox fileRow = new HBox(8);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        fileRow.setPadding(new Insets(4, 10, 4, 10));
        fileRow.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 8px;");

        Label fileIcon = new Label("\uD83D\uDCC4"); // 📄
        fileIcon.setStyle("-fx-font-size: 14px;");

        String fileName = this.fileEditPath.getFileName().toString();
        Label nameLabel = new Label(fileName);
        nameLabel.setStyle("-fx-font-weight: 500; -fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.8);");
        nameLabel.setMaxWidth(140);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (addedLines > 0) {
            Label added = new Label("+" + addedLines);
            added.setStyle("-fx-font-size: 10px; -fx-text-fill: #16a34a; -fx-font-weight: 600;");
            fileRow.getChildren().add(added);
        }
        if (removedLines > 0) {
            Label removed = new Label("-" + removedLines);
            removed.setStyle("-fx-font-size: 10px; -fx-text-fill: #dc2626; -fx-font-weight: 600;");
            fileRow.getChildren().add(removed);
        }

        Button diffBtn = new Button("查看对比");
        diffBtn.setStyle("-fx-font-size: 10px; -fx-background-color: #3b82f6; -fx-text-fill: white; "
                + "-fx-padding: 2px 8px; -fx-background-radius: 4px; -fx-cursor: hand;");
        diffBtn.setOnAction(e -> handleDiffAction());

        Button rollbackBtn = new Button("回滚");
        rollbackBtn.setStyle("-fx-font-size: 10px; -fx-background-color: #ef4444; -fx-text-fill: white; "
                + "-fx-padding: 2px 8px; -fx-background-radius: 4px; -fx-cursor: hand;");
        rollbackBtn.setOnAction(e -> handleRollbackAction());

        fileRow.getChildren().addAll(fileIcon, nameLabel);
        fileRow.getChildren().add(spacer);
        fileRow.getChildren().addAll(diffBtn, rollbackBtn);

        contentBox.getChildren().add(fileRow);
    }

    private void handleDiffAction() {
        if (fileEditPath == null) {
            log.error("[ToolCallCard] handleDiffAction: fileEditPath is null");
            return;
        }
        if (fileBackupManager == null) {
            log.error("[ToolCallCard] handleDiffAction: fileBackupManager is null for " + fileEditPath);
            return;
        }
        // 诊断日志：定位 FileBackupManager 指向的目录和查询路径
        java.nio.file.Path normalizedPath = fileEditPath.normalize();
        log.info("[ToolCallCard] handleDiffAction: backupRoot={}, fileEditPath={}, normalized={}, indexKeys={}",
                fileBackupManager.getBackupRoot(),
                fileEditPath,
                normalizedPath,
                fileBackupManager.getAllModifiedFiles());
        java.util.List<agent.tool.file.FileBackupManager.BackupEntry> versions =
                fileBackupManager.getVersions(fileEditPath);
        if (versions.isEmpty()) {
            log.error("[ToolCallCard] handleDiffAction: no backup versions found for {}", fileEditPath);
            return;
        }
        agent.tool.file.FileBackupManager.BackupEntry entry = versions.get(versions.size() - 1);
        DiffViewerPopup.show(fileEditPath, entry, fileBackupManager);
    }

    private void handleRollbackAction() {
        if (fileEditPath == null) {
            log.error("[ToolCallCard] handleRollbackAction: fileEditPath is null");
            return;
        }
        if (fileBackupManager == null) {
            log.error("[ToolCallCard] handleRollbackAction: fileBackupManager is null for " + fileEditPath);
            return;
        }
        java.util.List<agent.tool.file.FileBackupManager.BackupEntry> versions =
                fileBackupManager.getVersions(fileEditPath);
        if (versions.isEmpty()) {
            log.error("[ToolCallCard] handleRollbackAction: no backup versions found for " + fileEditPath);
            return;
        }
        agent.tool.file.FileBackupManager.BackupEntry entry = versions.get(versions.size() - 1);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认回滚");
        alert.setHeaderText("回滚文件: " + fileEditPath.getFileName());
        alert.setContentText("将文件回滚到修改前的版本？\n此操作将覆盖当前文件内容。");
        alert.showAndWait().ifPresent(response -> {
            if (response == javafx.scene.control.ButtonType.OK) {
                fileBackupManager.restore(fileEditPath, entry);
            }
        });
    }

    // ===== Internal =====

    /** Set plain text content and recalculate height deterministically */
    private void setContent(String text) {
        contentTextArea.setText(text != null ? text : "");
        recalculateTextAreaHeight();
    }

    /** Calculate height from line count — deterministic, no async JS measurement */
    private void recalculateTextAreaHeight() {
        String text = contentTextArea.getText();
        if (text == null || text.isEmpty()) return;

        // Estimate visual lines: count \n lines + account for wrapping on long lines
        int charsPerLine = 80; // conservative for 11px monospace in ~400px wide area
        int visualLines = 0;
        for (String line : text.split("\n", -1)) {
            visualLines += Math.max(1, (line.length() + charsPerLine - 1) / charsPerLine);
        }

        double lineHeight = 17; // 11px font + spacing
        double height = Math.min(visualLines * lineHeight + 16, 400);

        contentTextArea.setPrefHeight(height);
        contentTextArea.setMaxHeight(Math.min(height, 400));
        contentScrollPane.setMaxHeight(Math.min(height + 2, 400));
        contentScrollPane.setPrefHeight(Math.min(height + 2, 400));
    }

    private void toggle() {
        expanded = !expanded;
        contentBox.setVisible(expanded);
        contentBox.setManaged(expanded);
        expandIcon.setText(expanded ? "▼" : "▶");
        // Recalculate height on expand (only when TextArea is visible — native content needs no measurement)
        if (expanded && contentScrollPane.isVisible()) {
            recalculateTextAreaHeight();
        }
    }
}
