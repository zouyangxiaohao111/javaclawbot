package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import agent.tool.file.RipgrepConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 输入框完成提示弹窗：支持 /command 和 @文件/文件夹 提示。
 */
public class CompletionPopup {

    private static final int MAX_VISIBLE = 10;
    private static final double ITEM_HEIGHT = 28;
    private static final double POPUP_WIDTH = 440;

    private final TextArea inputArea;
    private final VBox itemContainer;
    private final ScrollPane scrollPane;
    private final Popup popup;
    private final List<CompletionItem> allItems = new ArrayList<>();
    private final List<CompletionItem> filtered = new ArrayList<>();
    private int selectedIndex = -1;

    private Path workspacePath;
    private Path projectPath;

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
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        popup = new Popup();
        // 不用 autoHide——会拦截双击事件
        popup.setAutoHide(false);
        popup.getContent().add(scrollPane);

        // Scene 级 key handler：只在 popup 显示时注入，避免干扰 inputArea 原生换行
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> popupKeyHandler = this::handleKeyPress;
        inputArea.textProperty().addListener((obs, old, text) -> updateFilter(text));
        // 点 popup 之外区域关闭（含切窗口场景：Stage 失焦时场景内 MOUSE_PRESSED 不会触发）
        inputArea.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                    if (popup.isShowing()) {
                        javafx.scene.Node target = e.getTarget() instanceof javafx.scene.Node n ? n : null;
                        if (target != null && target != inputArea && !isInsidePopup(target)) {
                            hide();
                        }
                    }
                });
                // Stage 失焦时也关闭 popup（Alt+Tab 切窗口）
                scene.windowProperty().addListener((prop, oldWin, newWin) -> {
                    if (newWin != null) {
                        newWin.focusedProperty().addListener((fobs, fOld, fNew) -> {
                            if (!fNew) hide();
                        });
                    }
                });
            }
        });

        // 用 show/hide 控制 scene 级 key filter 的注册/移除，避免常态干扰 TextArea
        popup.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            javafx.scene.Scene scene = inputArea.getScene();
            if (scene != null) {
                if (isShowing) {
                    scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, popupKeyHandler);
                } else {
                    scene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, popupKeyHandler);
                }
            }
        });
    }

    public void setWorkspacePath(Path workspacePath) {
        this.workspacePath = workspacePath;
    }

    public void setProjectPath(Path projectPath) {
        this.projectPath = projectPath;
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    /** 返回当前用于文件解析的基础路径 */
    public Path getBasePath() {
        return (projectPath != null && Files.exists(projectPath)) ? projectPath : workspacePath;
    }

    private void handleKeyPress(javafx.scene.input.KeyEvent e) {
        if (!popup.isShowing()) return;

        if (e.getCode() == KeyCode.UP) {
            e.consume();
            selectUp();
        } else if (e.getCode() == KeyCode.DOWN) {
            e.consume();
            selectDown();
        } else if ((e.getCode() == KeyCode.TAB || e.getCode() == KeyCode.ENTER)
                && !e.isShiftDown()) {
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
            // 命令后已有空格说明命令已补全，用户正在输入参数，不再弹窗
            if (trimmed.indexOf(' ') > 0) {
                hide();
                return;
            }
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
        Path basePath = (projectPath != null && Files.exists(projectPath)) ? projectPath : workspacePath;
        if (basePath == null || !Files.exists(basePath)) { hide(); return; }

        filtered.clear();

        List<String> matches = searchFiles(basePath, partial);

        if (matches.isEmpty()) { hide(); return; }

        for (String relPath : matches) {
            Path p = basePath.resolve(relPath);
            boolean isDir = Files.isDirectory(p);
            String name = p.getFileName().toString();
            filtered.add(new CompletionItem(
                name + (isDir ? "/" : ""),
                relPath,
                isDir ? CompletionKind.DIR : CompletionKind.FILE));
        }

        selectedIndex = 0;
        showPopup();
    }

    /** 用 ripgrep --files 快速递归搜索文件名，返回相对于 basePath 的路径列表 */
    private List<String> searchFiles(Path basePath, String partial) {
        // 解析：@docs/plan → 在 docs/ 下搜 *plan*；@Config → 全局搜 **/*Config*
        final Path searchDir;
        final String nameFilter;
        int lastSlash = partial.lastIndexOf('/');
        if (lastSlash >= 0) {
            Path resolved = basePath.resolve(partial.substring(0, lastSlash));
            if (!Files.isDirectory(resolved)) return List.of();
            searchDir = resolved;
            nameFilter = partial.substring(lastSlash + 1);
        } else {
            searchDir = basePath;
            nameFilter = partial;
        }

        String glob = nameFilter.isEmpty() ? "*" : "*" + nameFilter + "*";

        try {
            RipgrepConfig config = RipgrepConfig.getRipgrepConfig();
            ProcessBuilder pb = new ProcessBuilder(
                config.getExecutablePath().toString(),
                "--files", "--hidden",
                "--iglob", "!.git",
                "--iglob", glob,
                searchDir.toString()
            );
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null && n < MAX_VISIBLE) {
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (!line.isBlank()) { lines.add(line); n++; }
                }
            }
            boolean done = proc.waitFor(3, TimeUnit.SECONDS);
            if (!done) proc.destroyForcibly();
            if (lines.isEmpty()) return List.of();

            List<String> out = new ArrayList<>();
            for (String abs : lines) {
                try {
                    Path p = Path.of(abs);
                    out.add(basePath.relativize(p).toString().replace('\\', '/'));
                } catch (Exception e) {
                    out.add(abs);
                }
            }
            out.sort(Comparator.comparing(s -> {
                Path full = basePath.resolve(s);
                return !Files.isDirectory(full);
            }));
            return out;
        } catch (Exception e) {
            // rg 进程启动失败，回退到 Files.walk
            return walkSearchFiles(basePath, partial);
        }
    }

    /** 回退方案：纯 Java NIO 递归搜索（rg 不可用时） */
    private List<String> walkSearchFiles(Path basePath, String partial) {
        final java.util.Set<String> skipDirs = java.util.Set.of(
            ".git", ".svn", ".hg", "node_modules", "target", "__pycache__",
            ".idea", ".vscode", "build", "dist", ".cache");

        final Path searchDir;
        final String nameFilter;
        int lastSlash = partial.lastIndexOf('/');
        if (lastSlash >= 0) {
            Path resolved = basePath.resolve(partial.substring(0, lastSlash));
            if (!Files.isDirectory(resolved)) return List.of();
            searchDir = resolved;
            nameFilter = partial.substring(lastSlash + 1).toLowerCase();
        } else {
            searchDir = basePath;
            nameFilter = partial.toLowerCase();
        }

        List<Path> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(searchDir, 5)) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                if (name.startsWith(".")) return false;
                for (int i = 0; i < p.getNameCount(); i++) {
                    if (skipDirs.contains(p.getName(i).toString())) return false;
                }
                if (nameFilter.isEmpty()) return true;
                return name.contains(nameFilter);
            })
            .sorted(Comparator.comparing(p -> !Files.isDirectory(p)))
            .limit(MAX_VISIBLE)
            .forEach(results::add);
        } catch (IOException ignored) {}

        return results.stream()
            .map(p -> basePath.relativize(p).toString().replace('\\', '/'))
            .toList();
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

    private boolean isInsidePopup(javafx.scene.Node target) {
        javafx.scene.Node node = target;
        while (node != null) {
            if (node == scrollPane) return true;
            node = node.getParent();
        }
        return false;
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
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 10, 4, 10));
        row.setPrefHeight(ITEM_HEIGHT);
        row.setMinHeight(ITEM_HEIGHT);
        row.setStyle(selected
            ? "-fx-background-color: rgba(59,130,246,0.1); -fx-background-radius: 6px;"
            : "-fx-background-color: transparent; -fx-background-radius: 6px;");

        Label icon = new Label(item.kind() == CompletionKind.COMMAND ? "/" :
            item.kind() == CompletionKind.DIR ? "\uD83D\uDCC1" : "\uD83D\uDCC4");
        icon.setStyle("-fx-font-size: 13px;");
        icon.setMinWidth(18);

        Label nameLabel = new Label(item.text());
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500;");
        nameLabel.setMinWidth(100);
        nameLabel.setMaxWidth(160);

        Label descLabel = new Label(item.description());
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.4);");
        descLabel.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        HBox.setHgrow(descLabel, Priority.ALWAYS);

        row.getChildren().addAll(icon, nameLabel, descLabel);

        final int idx = filtered.indexOf(item);
        row.setOnMouseEntered(e -> { selectedIndex = idx; rebuildItems(); });
        row.setOnMouseClicked(e -> completeItem(item));

        return row;
    }

    private void selectUp() {
        if (filtered.isEmpty() || selectedIndex <= 0) return;
        selectedIndex--;
        rebuildItems();
    }

    private void selectDown() {
        if (filtered.isEmpty() || selectedIndex >= filtered.size() - 1) return;
        selectedIndex++;
        rebuildItems();
    }

    private void completeSelected() {
        if (filtered.isEmpty() || selectedIndex < 0 || selectedIndex >= filtered.size()) return;
        completeItem(filtered.get(selectedIndex));
    }

    private void completeItem(CompletionItem item) {
        String text = inputArea.getText();
        if (item.kind() == CompletionKind.COMMAND) {
            // Replace the partial command prefix
            String prefix = text.substring(0, text.lastIndexOf('/'));
            inputArea.setText(prefix + item.text() + " ");
            inputArea.positionCaret(inputArea.getText().length());
        } else {
            // 填入绝对路径，免去 send 时的 Path.resolve 解析
            int atIdx = text.lastIndexOf('@');
            if (atIdx >= 0) {
                String prefix = text.substring(0, atIdx + 1);
                String afterAt = text.substring(atIdx + 1);
                int lastSlash = Math.max(afterAt.lastIndexOf('/'), afterAt.lastIndexOf('\\'));
                if (lastSlash >= 0) {
                    prefix = text.substring(0, atIdx + 1 + lastSlash + 1);
                }
                Path base = getBasePath();
                String absPath = base.resolve(item.description()).toAbsolutePath().normalize().toString();
                inputArea.setText(prefix + absPath);
                inputArea.positionCaret(inputArea.getText().length());
            }
        }
        hide();
        inputArea.requestFocus();
    }
}
