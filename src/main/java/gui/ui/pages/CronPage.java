package gui.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class CronPage extends VBox {

    private VBox taskList;
    private gui.ui.BackendBridge backendBridge;

    public CronPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(16);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("定时任务");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("管理和调度周期性任务");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        taskList = new VBox(12);
        taskList.setMaxWidth(800);

        String[][] tasks = {
            {"每日数据备份", "每天凌晨 2 点执行数据备份", "0 2 * * *", "运行中"},
            {"每周报告生成", "每周一上午 9 点生成周报", "0 9 * * 1", "运行中"},
            {"清理过期会话", "每小时清理过期会话数据", "0 * * * *", "错误"}
        };

        for (String[] task : tasks) {
            taskList.getChildren().add(createTaskCard(task[0], task[1], task[2], task[3]));
        }

        Button addBtn = new Button("+ 新建任务");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);

        content.getChildren().addAll(titleBox, taskList, addBtn);
        VBox.setMargin(addBtn, new Insets(24, 0, 0, 0));

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createTaskCard(String name, String desc, String cron, String status) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox infoBox = new VBox(4);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label descLabel = new Label(desc);
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(nameLabel, descLabel);

        Label statusBadge = new Label(status);
        statusBadge.getStyleClass().addAll("status-badge", "运行中".equals(status) ? "running" : "error");

        header.getChildren().addAll(infoBox, statusBadge);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        // Cron 表达式和操作按钮
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 0, 0, 0));

        Label cronLabel = new Label(cron);
        cronLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 13px; -fx-background-color: rgba(0, 0, 0, 0.03); -fx-background-radius: 12px; -fx-padding: 6px 12px;");

        HBox actionBox = new HBox(8);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(actionBox, Priority.ALWAYS);

        Button editBtn = new Button("编辑");
        editBtn.getStyleClass().add("pill-button");
        editBtn.setPrefHeight(32);

        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().add("pill-button");
        deleteBtn.setPrefHeight(32);

        actionBox.getChildren().addAll(editBtn, deleteBtn);
        footer.getChildren().addAll(cronLabel, actionBox);

        // 分隔线
        Label separator = new Label();
        separator.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-pref-height: 1px;");

        card.getChildren().addAll(header, separator, footer);
        return card;
    }

    public void setBackendBridge(gui.ui.BackendBridge bridge) {
        this.backendBridge = bridge;
        refresh();
    }

    private void refresh() {
        if (backendBridge == null) return;
        taskList.getChildren().clear();

        java.util.List<corn.CronJob> jobs = backendBridge.getCronService().listJobs(false);
        for (corn.CronJob job : jobs) {
            String expr = job.getSchedule().getExpr();
            if (expr == null) {
                corn.CronSchedule.Kind kind = job.getSchedule().getKind();
                if (kind == corn.CronSchedule.Kind.every && job.getSchedule().getEveryMs() != null) {
                    expr = "每 " + (job.getSchedule().getEveryMs() / 1000) + " 秒";
                } else if (kind == corn.CronSchedule.Kind.at && job.getSchedule().getAtMs() != null) {
                    expr = "@ " + java.time.Instant.ofEpochMilli(job.getSchedule().getAtMs()).toString();
                } else {
                    expr = String.valueOf(kind);
                }
            }
            String status = job.isEnabled() ? "运行中" : "已暂停";
            taskList.getChildren().add(createTaskCard(
                job.getName() != null ? job.getName() : job.getId(),
                job.getId(),
                expr,
                status));
        }
    }
}
