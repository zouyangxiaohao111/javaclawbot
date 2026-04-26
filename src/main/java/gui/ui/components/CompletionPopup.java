package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 输入框完成提示弹窗：支持 /command 和 @文件/文件夹 提示。
 */
public class CompletionPopup {

    private static final int MAX_VISIBLE = 8;
    private static final double ITEM_HEIGHT = 28;
    private static final double POPUP_WIDTH = 340;

    private final TextArea inputArea;
    private final VBox itemContainer;
    private final ScrollPane scrollPane;
    private final Popup popup;
    private final List<CompletionItem> allItems = new ArrayList<>();
    private final List<CompletionItem> filtered = new ArrayList<>();
    private int selectedIndex = -1;

    private Path workspacePath;

    public record CompletionItem(String text, String description, CompletionKind kind) {}
    public enum CompletionKind { COMMAND, FILE, DIR }

    private static final List<CompletionItem> COMMANDS = List.of(
        new CompletionItem("/stop", "停止当前任务", CompletionKind.COMMAND),
        new CompletionItem("/help", "显示帮助信息", CompletionKind.COMMAND),
        new CompletionItem("/clear", "清空对话历史", CompletionKind.COMMAND),
        new CompletionItem("/memory", "搜索记忆", CompletionKind.COMMAND),
        new CompletionItem("/mcp-reload", "重新加载 MCP 工具", CompletionKind.COMMAND),
        new CompletionItem("/mcp-init", "初始化 MCP 连接", CompletionKind.COMMAND),
        new CompletionItem("/context-press", "压缩上下文", CompletionKind.COMMAND),
        new CompletionItem("/init", "初始化会话", CompletionKind.COMMAND),
        new CompletionItem("/resume", "恢复会话", CompletionKind.COMMAND),
        new CompletionItem("/fork", "分叉会话", CompletionKind.COMMAND),
        new CompletionItem("/bind", "绑定项目路径", CompletionKind.COMMAND),
        new CompletionItem("/unbind", "解绑项目", CompletionKind.COMMAND),
        new CompletionItem("/projects", "列出所有项目", CompletionKind.COMMAND),
        new CompletionItem("/cc", "使用 Claude Code", CompletionKind.COMMAND),
        new CompletionItem("/oc", "使用 OpenCode", CompletionKind.COMMAND)
    );

    public CompletionPopup(TextArea inputArea) {
        this.inputArea = inputArea;

        itemContainer = new VBox(2);
        itemContainer.setStyle("-fx-background-color: rgba(255,255,255,0.95); -fx-background-radius: 10px; "
            + "-fx-border-color: rgba(0,0,0,0.08); -fx-border-radius: 10px; -fx-border-width: 1px;");
        itemContainer.setPadding(new Insets(4));

        scrollPane = new ScrollPane(itemContainer);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxHeight(MAX_VISIBLE * ITEM_HEIGHT + 8);
        scrollPane.setMinWidth(POPUP_WIDTH);
        scrollPane.setPrefWidth(POPUP_WIDTH);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        popup = new Popup();
        popup.setAutoHide(true);
        popup.getContent().add(scrollPane);

        inputArea.textProperty().addListener((obs, old, text) -> updateFilter(text));
        inputArea.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::handleKeyPress);
        inputArea.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) hide();
        });
    }

    public void setWorkspacePath(Path workspacePath) {
        this.workspacePath = workspacePath;
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    private void handleKeyPress(javafx.scene.input.KeyEvent e) {
        if (!popup.isShowing()) return;

        if (e.getCode() == KeyCode.UP) {
            e.consume();
            selectUp();
        } else if (e.getCode() == KeyCode.DOWN) {
            e.consume();
            selectDown();
        } else if (e.getCode() == KeyCode.TAB || e.getCode() == KeyCode.ENTER) {
            e.consume();
            completeSelected();
        } else if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            hide();
        }
    }

    private void updateFilter(String text) {
        if (text == null) { hide(); return; }

        String trimmed = text.trim();
        if (trimmed.startsWith("/")) {
            filterCommands(trimmed);
        } else if (trimmed.startsWith("@")) {
            filterFiles(trimmed.substring(1));
        } else {
            hide();
        }
    }

    private void filterCommands(String prefix) {
        String lower = prefix.toLowerCase();
        filtered.clear();
        for (CompletionItem item : COMMANDS) {
            if (item.text().toLowerCase().startsWith(lower)) {
                filtered.add(item);
            }
        }
        if (filtered.isEmpty()) { hide(); return; }
        selectedIndex = 0;
        showPopup();
    }

    private void filterFiles(String partial) {
        if (workspacePath == null || !Files.exists(workspacePath)) { hide(); return; }

        filtered.clear();
        String lower = partial.toLowerCase();
        List<Path> children = new ArrayList<>();

        // Determine target directory and filter prefix
        final Path searchDir;
        final String nameFilter;
        int lastSlash = partial.lastIndexOf('/');
        if (lastSlash >= 0) {
            String dirPart = partial.substring(0, lastSlash);
            nameFilter = partial.substring(lastSlash + 1).toLowerCase();
            Path resolved = workspacePath.resolve(dirPart);
            if (Files.isDirectory(resolved)) {
                searchDir = resolved;
            } else {
                hide(); return;
            }
        } else {
            searchDir = workspacePath;
            nameFilter = lower;
        }

        try (Stream<Path> stream = Files.list(searchDir)) {
            String filter = nameFilter;
            stream.filter(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) return false;
                if (filter.isEmpty()) return true;
                return name.toLowerCase().contains(filter);
            })
            .sorted(Comparator.comparing(p -> !Files.isDirectory(p)))
            .limit(MAX_VISIBLE)
            .forEach(children::add);
        } catch (IOException ignored) { hide(); return; }

        for (Path p : children) {
            String name = p.getFileName().toString();
            boolean isDir = Files.isDirectory(p);
            String relPath = workspacePath.relativize(p).toString();
            filtered.add(new CompletionItem(
                name + (isDir ? "/" : ""),
                relPath,
                isDir ? CompletionKind.DIR : CompletionKind.FILE));
        }

        if (filtered.isEmpty()) { hide(); return; }
        selectedIndex = 0;
        showPopup();
    }

    private void showPopup() {
        rebuildItems();
        // Position above input area
        Window window = inputArea.getScene().getWindow();
        var bounds = inputArea.localToScreen(inputArea.getBoundsInLocal());
        double x = bounds.getMinX();
        double y = bounds.getMinY() - scrollPane.getMaxHeight() - 4;
        popup.show(window, x, Math.max(y, 0));
    }

    private void hide() {
        popup.hide();
        filtered.clear();
        selectedIndex = -1;
    }

    private void rebuildItems() {
        itemContainer.getChildren().clear();
        for (int i = 0; i < filtered.size(); i++) {
            itemContainer.getChildren().add(createItemRow(filtered.get(i), i == selectedIndex));
        }
    }

    private HBox createItemRow(CompletionItem item, boolean selected) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 10, 4, 10));
        row.setPrefHeight(ITEM_HEIGHT);
        row.setMinHeight(ITEM_HEIGHT);
        row.setMaxWidth(POPUP_WIDTH - 20);
        row.setStyle(selected
            ? "-fx-background-color: rgba(59,130,246,0.1); -fx-background-radius: 6px;"
            : "-fx-background-color: transparent; -fx-background-radius: 6px;");

        Label icon = new Label(item.kind() == CompletionKind.COMMAND ? "/" :
            item.kind() == CompletionKind.DIR ? "\uD83D\uDCC1" : "\uD83D\uDCC4");
        icon.setStyle("-fx-font-size: 13px;");

        Label nameLabel = new Label(item.text());
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500;");

        Label descLabel = new Label(item.description());
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.4);");
        HBox.setHgrow(descLabel, Priority.ALWAYS);
        descLabel.setAlignment(Pos.CENTER_RIGHT);

        row.getChildren().addAll(icon, nameLabel, descLabel);

        final int idx = filtered.indexOf(item);
        row.setOnMouseEntered(e -> { selectedIndex = idx; rebuildItems(); });
        row.setOnMouseClicked(e -> completeItem(item));

        return row;
    }

    private void selectUp() {
        if (filtered.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + filtered.size()) % filtered.size();
        rebuildItems();
    }

    private void selectDown() {
        if (filtered.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % filtered.size();
        rebuildItems();
    }

    private void completeSelected() {
        if (filtered.isEmpty() || selectedIndex < 0 || selectedIndex >= filtered.size()) return;
        completeItem(filtered.get(selectedIndex));
    }

    private void completeItem(CompletionItem item) {
        String prefix;
        String text = inputArea.getText();
        if (item.kind() == CompletionKind.COMMAND) {
            // Replace the partial command prefix
            prefix = text.substring(0, text.lastIndexOf('/'));
            inputArea.setText(prefix + item.text() + " ");
            inputArea.positionCaret(inputArea.getText().length());
        } else {
            // Replace from @... to file name
            int atIdx = text.lastIndexOf('@');
            if (atIdx >= 0) {
                prefix = text.substring(0, atIdx + 1);
                String afterAt = text.substring(atIdx + 1);
                int lastSlash = afterAt.lastIndexOf('/');
                if (lastSlash >= 0) {
                    prefix = text.substring(0, atIdx + 1 + lastSlash + 1);
                }
                inputArea.setText(prefix + item.text());
                inputArea.positionCaret(inputArea.getText().length());
            }
        }
        hide();
        inputArea.requestFocus();
    }
}
