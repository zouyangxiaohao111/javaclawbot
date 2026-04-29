package gui.ui.components;

import com.google.gson.Gson;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/**
 * TodoWrite 工具结果格式化组件。
 * 将 TodoWrite 返回的 JSON 渲染为带状态图标的列表。
 */
public final class TodoResultView {

    private TodoResultView() {}

    /** 解析 TodoWrite 工具返回的 JSON，构建格式化的任务列表 Node。 */
    public static Node build(String json) {
        try {
            Gson gson = new Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = gson.fromJson(json, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> newTodos = (List<Map<String, Object>>) root.get("newTodos");
            Boolean allDone = (Boolean) root.get("allDone");

            VBox box = new VBox(4);
            box.setStyle("-fx-padding: 4px 0;");

            if (allDone != null && allDone) {
                Label done = new Label("所有任务已完成");
                done.setStyle("-fx-font-size: 12px; -fx-text-fill: #16a34a;");
                box.getChildren().add(done);
                return box;
            }

            if (newTodos == null || newTodos.isEmpty()) {
                Label empty = new Label("任务列表为空");
                empty.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.4);");
                box.getChildren().add(empty);
                return box;
            }

            for (Map<String, Object> item : newTodos) {
                String status = (String) item.getOrDefault("status", "pending");
                String content = (String) item.getOrDefault("content", "");
                String activeForm = (String) item.getOrDefault("activeForm", "");

                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setStyle("-fx-padding: 2px 0;");

                String icon;
                String color;
                switch (status) {
                    case "completed": icon = "✓"; color = "#16a34a"; break;
                    case "in_progress": icon = "◉"; color = "#3b82f6"; break;
                    default: icon = "○"; color = "rgba(0,0,0,0.3)"; break;
                }

                Label iconLabel = new Label(icon);
                iconLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + color + "; -fx-min-width: 16px;");
                iconLabel.setMinSize(16, 16);

                Label contentLabel = new Label(content);
                contentLabel.setStyle("-fx-font-size: 12px;");
                contentLabel.setWrapText(true);

                Label statusLabel = new Label(status.equals("in_progress") ? activeForm : "");
                statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(0,0,0,0.4);");

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                row.getChildren().addAll(iconLabel, contentLabel, spacer, statusLabel);
                box.getChildren().add(row);
            }
            return box;
        } catch (Exception e) {
            Label fallback = new Label(json);
            fallback.setWrapText(true);
            fallback.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; "
                + "-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 6px; -fx-padding: 8px;");
            return fallback;
        }
    }
}
