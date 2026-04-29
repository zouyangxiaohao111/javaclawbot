package gui.ui.components;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AskUserQuestion 结果格式化组件。
 * 将结构化 JSON（含 questions + answers）渲染为选项列表，用户选中的用绿色 ● 标注。
 */
public final class AskQuestionResultView {

    private AskQuestionResultView() {}

    /** 解析 AskUserQuestion 结果 JSON，构建格式化的问题列表。 */
    public static Node build(String json) {
        try {
            Gson gson = new Gson();
            Map<String, Object> root = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) root.get("questions");
            @SuppressWarnings("unchecked")
            Map<String, String> answers = (Map<String, String>) root.get("answers");

            if (questions == null || questions.isEmpty()) {
                return fallback(json);
            }

            VBox box = new VBox(8);
            box.setStyle("-fx-padding: 4px 0;");

            for (Map<String, Object> q : questions) {
                String questionText = (String) q.getOrDefault("question", "");
                String header = (String) q.getOrDefault("header", "");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> options = (List<Map<String, Object>>) q.getOrDefault("options", List.of());

                VBox qBox = new VBox(2);
                qBox.setStyle("-fx-padding: 2px 0;");

                // Header + question
                Label qLabel = new Label(header + (header.isEmpty() ? "" : "：") + questionText);
                qLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: rgba(0,0,0,0.7);");
                qLabel.setWrapText(true);
                qBox.getChildren().add(qLabel);

                // Selected answer
                String selectedAnswer = answers != null ? answers.get(questionText) : null;
                boolean isCustom = selectedAnswer != null && !isOptionLabel(selectedAnswer, options);

                for (Map<String, Object> opt : options) {
                    String label = (String) opt.getOrDefault("label", "");
                    String desc = (String) opt.getOrDefault("description", "");
                    boolean selected = label.equals(selectedAnswer);

                    HBox row = new HBox(6);
                    row.setAlignment(Pos.CENTER_LEFT);

                    Label icon = new Label(selected ? "●" : "○"); // ● or ○
                    icon.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                        + (selected ? "#16a34a" : "rgba(0,0,0,0.25)") + "; -fx-min-width: 14px;");

                    Label labelText = new Label(label);
                    labelText.setStyle("-fx-font-size: 12px;"
                        + (selected ? " -fx-font-weight: 600; -fx-text-fill: #16a34a;" : ""));

                    row.getChildren().addAll(icon, labelText);

                    if (desc != null && !desc.isBlank()) {
                        Label descLabel = new Label("— " + desc);
                        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.35);");
                        descLabel.setWrapText(true);
                        row.getChildren().add(descLabel);
                    }
                    qBox.getChildren().add(row);
                }

                // Custom answer row
                if (isCustom) {
                    HBox customRow = new HBox(6);
                    customRow.setAlignment(Pos.CENTER_LEFT);
                    Label customIcon = new Label("●");
                    customIcon.setStyle("-fx-font-size: 10px; -fx-text-fill: #16a34a; -fx-min-width: 14px;");
                    Label customLabel = new Label("其他: " + selectedAnswer);
                    customLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #16a34a;");
                    customRow.getChildren().addAll(customIcon, customLabel);
                    qBox.getChildren().add(customRow);
                } else if (selectedAnswer == null && answers != null) {
                    // Answer text doesn't match any option label - might be custom or free text
                    HBox customRow = new HBox(6);
                    customRow.setAlignment(Pos.CENTER_LEFT);
                    Label customIcon = new Label("●");
                    customIcon.setStyle("-fx-font-size: 10px; -fx-text-fill: #16a34a; -fx-min-width: 14px;");
                    Label customLabel = new Label("其他: " + selectedAnswer);
                    customLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #16a34a;");
                    customRow.getChildren().addAll(customIcon, customLabel);
                    qBox.getChildren().add(customRow);
                }

                box.getChildren().add(qBox);
            }
            return box;
        } catch (Exception e) {
            return fallback(json);
        }
    }

    private static boolean isOptionLabel(String answer, List<Map<String, Object>> options) {
        for (Map<String, Object> opt : options) {
            if (answer.equals(opt.get("label"))) return true;
        }
        return false;
    }

    private static Node fallback(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 12px; -fx-background-color: rgba(0,0,0,0.02); "
            + "-fx-background-radius: 6px; -fx-padding: 8px;");
        return label;
    }
}
