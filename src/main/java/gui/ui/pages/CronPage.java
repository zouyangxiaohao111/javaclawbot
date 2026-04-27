package gui.ui.pages;

import corn.CronJob;
import corn.CronSchedule;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

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
            taskList.getChildren().add(createTaskCard(null, task[0], task[1], task[2], task[3]));
        }

        Button addBtn = new Button("+ 新建任务");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);
        addBtn.setOnAction(e -> showCronDialog(null));

        content.getChildren().addAll(titleBox, taskList, addBtn);
        VBox.setMargin(addBtn, new Insets(24, 0, 0, 0));

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createTaskCard(CronJob job, String name, String desc, String cron, String status) {
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
        editBtn.setOnAction(e -> {
            if (job != null) showCronDialog(job);
        });

        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().add("pill-button");
        deleteBtn.setPrefHeight(32);
        deleteBtn.setOnAction(e -> {
            if (job != null) {
                backendBridge.getCronService().removeJob(job.getId());
                refresh();
            }
        });

        // 启用/禁用切换（仅当有 job 时可用）
        if (job != null) {
            Button toggleBtn = new Button(job.isEnabled() ? "暂停" : "启用");
            toggleBtn.getStyleClass().add("pill-button");
            toggleBtn.setPrefHeight(32);
            toggleBtn.setOnAction(e -> {
                backendBridge.getCronService().enableJob(job.getId(), !job.isEnabled());
                refresh();
            });
            actionBox.getChildren().addAll(toggleBtn, editBtn, deleteBtn);
        } else {
            actionBox.getChildren().addAll(editBtn, deleteBtn);
        }
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

        java.util.List<CronJob> jobs = backendBridge.getCronService().listJobs(false);
        for (CronJob job : jobs) {
            String expr = job.getSchedule().getExpr();
            if (expr == null) {
                CronSchedule.Kind kind = job.getSchedule().getKind();
                if (kind == CronSchedule.Kind.every && job.getSchedule().getEveryMs() != null) {
                    expr = "每 " + (job.getSchedule().getEveryMs() / 1000) + " 秒";
                } else if (kind == CronSchedule.Kind.at && job.getSchedule().getAtMs() != null) {
                    expr = "@ " + java.time.Instant.ofEpochMilli(job.getSchedule().getAtMs()).toString();
                } else {
                    expr = String.valueOf(kind);
                }
            }
            String status = job.isEnabled() ? "运行中" : "已暂停";
            taskList.getChildren().add(createTaskCard(
                job,
                job.getName() != null ? job.getName() : job.getId(),
                job.getId(),
                expr,
                status));
        }
    }

    private void showCronDialog(CronJob existing) {
        boolean isEdit = existing != null;

        Stage dialog = new Stage();
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.initModality(Modality.APPLICATION_MODAL);

        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #f1ede1; -fx-background-radius: 16px;"
            + " -fx-border-color: rgba(0,0,0,0.1); -fx-border-radius: 16px; -fx-border-width: 1px;");

        Label title = new Label(isEdit ? "编辑任务" : "新建定时任务");
        title.setStyle("-fx-font-family: Georgia; -fx-font-size: 24px; -fx-text-fill: rgba(0,0,0,0.7);");

        // 名称
        TextField nameField = new TextField(isEdit && existing.getName() != null ? existing.getName() : "");
        nameField.setPromptText("任务名称");
        nameField.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 14px; -fx-padding: 10px 14px;");
        nameField.setPrefHeight(40);

        // 消息
        TextField msgField = new TextField(isEdit && existing.getPayload() != null
            && existing.getPayload().getMessage() != null ? existing.getPayload().getMessage() : "");
        msgField.setPromptText("执行消息");
        msgField.setStyle(nameField.getStyle());
        msgField.setPrefHeight(40);

        // 调度类型
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("cron 表达式", "every（间隔）", "at（定时一次）");
        typeCombo.setStyle("-fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 12px;"
            + " -fx-border-color: transparent; -fx-font-size: 13px;");
        typeCombo.setPrefHeight(40);
        if (isEdit) {
            CronSchedule.Kind k = existing.getSchedule().getKind();
            if (k == CronSchedule.Kind.cron) typeCombo.setValue("cron 表达式");
            else if (k == CronSchedule.Kind.every) typeCombo.setValue("every（间隔）");
            else if (k == CronSchedule.Kind.at) typeCombo.setValue("at（定时一次）");
        } else {
            typeCombo.setValue("cron 表达式");
        }

        // 调度值
        String initVal = "";
        if (isEdit) {
            if (existing.getSchedule().getExpr() != null) initVal = existing.getSchedule().getExpr();
            else if (existing.getSchedule().getEveryMs() != null) initVal = String.valueOf(existing.getSchedule().getEveryMs() / 1000);
            else if (existing.getSchedule().getAtMs() != null) initVal = String.valueOf(existing.getSchedule().getAtMs());
        }
        TextField scheduleField = new TextField(initVal);
        scheduleField.setPromptText("如: 0 9 * * * 或 3600 或 1745366400000");
        scheduleField.setStyle(nameField.getStyle());
        scheduleField.setPrefHeight(40);

        // 通道
        TextField channelField = new TextField(isEdit && existing.getPayload() != null
            && existing.getPayload().getChannel() != null ? existing.getPayload().getChannel() : "cli");
        channelField.setPromptText("通道");
        channelField.setStyle(nameField.getStyle());
        channelField.setPrefHeight(40);

        // 按钮
        HBox btnRow = new HBox(12);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("pill-button");
        cancelBtn.setPrefHeight(36);
        cancelBtn.setOnAction(e -> dialog.close());

        Button saveBtn = new Button(isEdit ? "保存" : "创建");
        saveBtn.getStyleClass().addAll("pill-button", "selected");
        saveBtn.setPrefHeight(36);
        saveBtn.setOnAction(e -> {
            try {
                String name = nameField.getText();
                String msg = msgField.getText();
                String typeStr = typeCombo.getValue();
                String schedVal = scheduleField.getText();
                String channel = channelField.getText();

                if (name.isBlank() || schedVal.isBlank()) return;

                CronSchedule schedule;
                if (typeStr.contains("every")) {
                    long sec = Long.parseLong(schedVal);
                    schedule = new CronSchedule(CronSchedule.Kind.every, null, sec * 1000, null, null);
                } else if (typeStr.contains("at")) {
                    long ms = Long.parseLong(schedVal);
                    schedule = new CronSchedule(CronSchedule.Kind.at, ms, null, null, null);
                } else {
                    schedule = new CronSchedule(CronSchedule.Kind.cron, null, null, schedVal, null);
                }

                if (isEdit) {
                    // Update existing job directly
                    existing.setName(name);
                    existing.setSchedule(schedule);
                    if (existing.getPayload() == null) existing.setPayload(new corn.CronPayload());
                    existing.getPayload().setMessage(msg);
                    existing.getPayload().setChannel(channel.isBlank() ? "cli" : channel);
                    existing.setUpdatedAtMs(System.currentTimeMillis());
                    // Persist happens automatically via cron service's store mechanism
                } else {
                    backendBridge.getCronService().addJob(name, schedule, msg, false,
                        channel.isBlank() ? "cli" : channel, null, false);
                }
                dialog.close();
                refresh();
            } catch (Exception ignored) {}
        });

        btnRow.getChildren().addAll(cancelBtn, saveBtn);
        root.getChildren().addAll(title, nameField, msgField, typeCombo, scheduleField, channelField, btnRow);

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.showAndWait();
    }
}
