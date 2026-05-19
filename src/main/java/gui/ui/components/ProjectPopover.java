package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import providers.cli.ProjectRegistry;
import providers.cli.ProjectRegistry.ProjectInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 项目管理弹出面板（非模态 Stage）。
 * 列出所有绑定项目，支持编辑路径、切换主项目、绑定/解绑。
 * 使用 Stage 替代 Popup 以解决 Popup 不参与焦点模型导致 TextField 无法输入的问题。
 */
public class ProjectPopover {

    private final Stage popupStage;
    private final VBox root;
    private final VBox projectListBox;
    private final Label titleLabel;
    private final HBox bindRow;
    private final TextField nameField;
    private final TextField pathField;
    private final Button bindButton;

    private ProjectRegistry registry;
    private Runnable onChanged;

    public ProjectPopover() {
        popupStage = new Stage(StageStyle.UNDECORATED);
        popupStage.setAlwaysOnTop(true);
        popupStage.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) popupStage.hide();
        });

        root = new VBox(8);
        root.setStyle(
            "-fx-background-color: white; -fx-background-radius: 12px;" +
            "-fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 12px;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.12), 12, 0, 0, 4);");
        root.setPadding(new Insets(12));
        root.setMaxWidth(420);
        root.setMinWidth(360);

        // 标题行
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        titleLabel = new Label("\uD83D\uDCC1 已绑定项目");
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #000000;");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Label addBtn = new Label("+ 绑定");
        addBtn.setStyle("-fx-font-size: 11px; -fx-text-fill: #3b82f6; -fx-cursor: hand;");
        header.getChildren().addAll(titleLabel, headerSpacer, addBtn);

        // 项目列表
        projectListBox = new VBox(4);

        // 绑定输入区（默认隐藏）
        bindRow = new HBox(6);
        bindRow.setAlignment(Pos.CENTER_LEFT);
        nameField = new TextField();
        nameField.setPromptText("名称");
        nameField.setPrefWidth(80);
        nameField.setStyle(
            "-fx-font-size: 11px; -fx-background-color: rgba(0,0,0,0.03);" +
            "-fx-background-radius: 6px; -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 6px;");
        pathField = new TextField();
        pathField.setPromptText("路径");
        pathField.setStyle(
            "-fx-font-size: 11px; -fx-background-color: rgba(0,0,0,0.03);" +
            "-fx-background-radius: 6px; -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 6px;");
        HBox.setHgrow(pathField, Priority.ALWAYS);
        bindButton = new Button("绑定");
        bindButton.setStyle(
            "-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 11px;" +
            "-fx-background-radius: 6px; -fx-padding: 4px 12px; -fx-cursor: hand;");
        bindRow.getChildren().addAll(nameField, pathField, bindButton);
        bindRow.setVisible(false);
        bindRow.setManaged(false);

        root.getChildren().addAll(header, projectListBox, bindRow);

        // 事件绑定
        addBtn.setOnMouseClicked(e -> {
            bindRow.setVisible(true);
            bindRow.setManaged(true);
            nameField.requestFocus();
        });

        bindButton.setOnAction(e -> doBind());
        pathField.setOnAction(e -> doBind());
        nameField.setOnAction(e -> pathField.requestFocus());

        Scene scene = new Scene(root, Color.TRANSPARENT);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/static/css/styles/main.css").toExternalForm());
        popupStage.setScene(scene);
    }

    /**
     * 显示 Popover，定位在 owner 上方
     */
    public void show(javafx.scene.Node owner, ProjectRegistry registry,
                     Runnable onChanged) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.onChanged = onChanged;

        rebuildProjectList();

        if (owner.getScene() == null) return;

        double popoverHeight = root.prefHeight(-1);
        javafx.geometry.Bounds bounds = owner.localToScreen(owner.getBoundsInLocal());
        popupStage.setX(bounds.getMaxX() - root.getMaxWidth());
        popupStage.setY(bounds.getMinY() - popoverHeight - 8);
        popupStage.sizeToScene();
        popupStage.show();
    }

    public void hide() {
        popupStage.hide();
    }

    public boolean isShowing() {
        return popupStage.isShowing();
    }

    /**
     * 刷新项目列表（不改变位置/可见性）。
     * 用于外部注册表变更后同步 popover 内容。
     */
    public void refreshList() {
        rebuildProjectList();
    }

    private void rebuildProjectList() {
        projectListBox.getChildren().clear();

        Map<String, ProjectInfo> projects = registry.listAll();
        if (projects.isEmpty()) {
            Label empty = new Label("暂无绑定项目");
            empty.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.4); -fx-padding: 8px 0;");
            projectListBox.getChildren().add(empty);
            return;
        }

        titleLabel.setText("\uD83D\uDCC1 已绑定项目 (" + projects.size() + ")");

        for (var entry : projects.entrySet()) {
            String name = entry.getKey();
            ProjectInfo info = entry.getValue();
            projectListBox.getChildren().add(createProjectRow(name, info));
        }
    }

    private javafx.scene.Node createProjectRow(String name, ProjectInfo info) {
        VBox row = new VBox(4);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle(info.isMain()
            ? "-fx-background-color: rgba(59,130,246,0.05); -fx-background-radius: 8px;" +
              "-fx-border-color: rgba(59,130,246,0.15); -fx-border-radius: 8px;"
            : "-fx-background-radius: 8px;");

        // 第一行：星标 + 名称 + 路径
        HBox topRow = new HBox(6);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label starLabel = new Label(info.isMain() ? "\u2B50" : "\u25CB");
        starLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " +
            (info.isMain() ? "#f59e0b" : "rgba(0,0,0,0.2)") + ";");

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #000000;");

        // 路径：可点击编辑
        Label pathLabel = new Label(info.getPath());
        pathLabel.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.45);" +
            "-fx-background-color: transparent; -fx-background-radius: 4px;" +
            "-fx-padding: 1px 4px; -fx-cursor: text;");
        pathLabel.setMaxWidth(220);
        pathLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        // 路径不存在警告
        boolean pathExists = Files.exists(Path.of(info.getPath()));
        if (!pathExists) {
            Label warn = new Label("\u26A0");
            warn.setStyle("-fx-font-size: 10px; -fx-text-fill: #f59e0b;");
            topRow.getChildren().add(warn);
        }

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        // 主项目标签
        if (info.isMain()) {
            Label mainTag = new Label("主项目");
            mainTag.setStyle(
                "-fx-font-size: 9px; -fx-background-color: #3b82f6; -fx-text-fill: white;" +
                "-fx-background-radius: 4px; -fx-padding: 1px 6px;");
            topRow.getChildren().addAll(starLabel, nameLabel, pathLabel, topSpacer, mainTag);
        } else {
            topRow.getChildren().addAll(starLabel, nameLabel, pathLabel, topSpacer);
        }

        // 第二行：操作按钮
        HBox actionRow = new HBox(8);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.setPadding(new Insets(0, 0, 0, 22));

        if (!info.isMain()) {
            Label setMainBtn = new Label("设为主项目");
            setMainBtn.setStyle("-fx-font-size: 10px; -fx-text-fill: #3b82f6; -fx-cursor: hand;");
            setMainBtn.setOnMouseClicked(e -> {
                registry.setMain(name);
                registry.save();
                rebuildProjectList();
                if (onChanged != null) onChanged.run();
            });
            actionRow.getChildren().add(setMainBtn);
        }

        Label unbindBtn = new Label("解绑");
        unbindBtn.setStyle("-fx-font-size: 10px; -fx-text-fill: #ef4444; -fx-cursor: hand;");
        unbindBtn.setOnMouseClicked(e -> {
            registry.unbind(name);
            registry.save();
            rebuildProjectList();
            if (onChanged != null) onChanged.run();
        });
        actionRow.getChildren().add(unbindBtn);

        // 路径点击编辑（使用 guard 防止 Enter + 失焦双重提交）
        pathLabel.setOnMouseClicked(e -> {
            TextField editField = new TextField(info.getPath());
            editField.setStyle(
                "-fx-font-size: 11px; -fx-background-color: white;" +
                "-fx-border-color: #3b82f6; -fx-border-radius: 4px; -fx-padding: 1px 4px;");
            editField.setPrefWidth(220);

            int pathIdx = topRow.getChildren().indexOf(pathLabel);
            if (pathIdx < 0) return;
            topRow.getChildren().set(pathIdx, editField);
            editField.requestFocus();
            editField.selectAll();

            final boolean[] committed = {false};

            Runnable commitEdit = () -> {
                if (committed[0]) return;
                committed[0] = true;
                String newPath = editField.getText().trim();
                if (!newPath.isEmpty() && !newPath.equals(info.getPath())) {
                    registry.bind(name, newPath, info.isMain());
                    registry.save();
                    rebuildProjectList();
                    if (onChanged != null) onChanged.run();
                } else {
                    topRow.getChildren().set(pathIdx, pathLabel);
                }
            };

            editField.setOnAction(ev -> commitEdit.run());
            editField.focusedProperty().addListener((obs, old, focused) -> {
                if (!focused) commitEdit.run();
            });
        });

        row.getChildren().addAll(topRow, actionRow);
        return row;
    }

    private void doBind() {
        String name = nameField.getText().trim();
        String path = pathField.getText().trim();
        if (name.isEmpty() || path.isEmpty()) return;

        registry.bind(name, path);
        registry.save();

        nameField.clear();
        pathField.clear();
        bindRow.setVisible(false);
        bindRow.setManaged(false);

        rebuildProjectList();
        if (onChanged != null) onChanged.run();
    }
}