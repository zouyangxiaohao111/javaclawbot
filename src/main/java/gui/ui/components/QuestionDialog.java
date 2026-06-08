package gui.ui.components;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.*;

/**
 * AskUserQuestion 弹窗。
 * 显示 AI 提出的问题及选项，用户可选择选项或自行填写答案。
 */
public class QuestionDialog extends Dialog<Map<String, String>> {

    private final List<QuestionBlock> blocks = new ArrayList<>();

    public QuestionDialog(List<Map<String, Object>> questions) {
        setTitle("AI 需要确认");
        setHeaderText("请回答以下问题以继续");

        VBox content = new VBox(12);
        content.setPadding(new Insets(0, 0, 8, 0));

        for (Map<String, Object> q : questions) {
            QuestionBlock block = createQuestionBlock(q);
            blocks.add(block);
            content.getChildren().add(block.root);
        }

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(400);
        scroll.setStyle("-fx-background-color: transparent;");
        getDialogPane().setContent(scroll);

        ButtonType submitBtn = new ButtonType("提交", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(submitBtn, ButtonType.CANCEL);

        setResultConverter(btn -> {
            if (btn != submitBtn) return null;
            Map<String, String> answers = new LinkedHashMap<>();
            for (QuestionBlock block : blocks) {
                String answer = block.getAnswer();
                if (answer != null && !answer.isBlank()) {
                    answers.put(block.questionText, answer);
                }
            }
            return answers;
        });

        getDialogPane().setMinWidth(520);
        getDialogPane().setMinHeight(300);

        // 防止键盘（Enter/Space/ESC）意外关闭弹窗 —— 必须鼠标点击按钮才能确认/取消。
        // 监听 Scene 创建时机，按钮节点要等 Scene 就绪后才会被实例化。
        getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;
            Platform.runLater(() -> {
                Node submitNode = getDialogPane().lookupButton(submitBtn);
                if (submitNode instanceof Button btn) {
                    btn.setDefaultButton(false);
                }
                Node cancelNode = getDialogPane().lookupButton(ButtonType.CANCEL);
                if (cancelNode instanceof Button btn) {
                    btn.setCancelButton(false);
                }
            });
            // Scene 级键盘过滤器：吞掉 Enter/Space/ESC（TextField 输入框内仍允许正常输入）
            newScene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getTarget() instanceof TextField) {
                    return;
                }
                KeyCode code = e.getCode();
                if (code == KeyCode.ENTER || code == KeyCode.SPACE || code == KeyCode.ESCAPE) {
                    e.consume();
                }
            });
        });
    }

    private QuestionBlock createQuestionBlock(Map<String, Object> q) {
        String questionText = (String) q.getOrDefault("question", "");
        String header = (String) q.getOrDefault("header", "");
        boolean multiSelect = Boolean.TRUE.equals(q.get("multiSelect"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) q.getOrDefault("options", List.of());

        VBox root = new VBox(6);
        root.setStyle("-fx-background-color: rgba(0,0,0,0.02); -fx-background-radius: 12px; -fx-padding: 12px;");

        // Header chip + question
        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);
        Label headerLabel = new Label(header);
        headerLabel.setStyle("-fx-background-color: rgba(0,0,0,0.08); -fx-background-radius: 6px;"
            + " -fx-padding: 2px 8px; -fx-font-size: 11px; -fx-font-weight: 600;");
        Label questionLabel = new Label(questionText);
        questionLabel.setWrapText(true);
        questionLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0,0,0,0.8);");
        topRow.getChildren().addAll(headerLabel, questionLabel);

        root.getChildren().add(topRow);

        // Options
        ToggleGroup group = multiSelect ? null : new ToggleGroup();
        List<ToggleButton> optionBtns = new ArrayList<>();
        VBox optionsBox = new VBox(4);

        for (Map<String, Object> opt : options) {
            String label = (String) opt.getOrDefault("label", "");
            String desc = (String) opt.getOrDefault("description", "");
            String preview = (String) opt.get("preview");

            if (multiSelect) {
                CheckBox cb = new CheckBox();
                cb.setText(label + (desc != null && !desc.isBlank() ? " — " + desc : ""));
                cb.setStyle("-fx-font-size: 12px;");
                cb.setUserData(label);
                optionsBox.getChildren().add(cb);
            } else {
                // 单选按钮 + 说明文字（内联显示，不再依赖 tooltip）
                VBox optBox = new VBox(2);
                optBox.setAlignment(Pos.CENTER_LEFT);

                ToggleButton btn = new ToggleButton();
                btn.setText(label);
                btn.setToggleGroup(group);
                btn.setUserData(label);
                btn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px; "
                    + "-fx-background-radius: 6px; -fx-border-radius: 6px; "
                    + "-fx-border-color: rgba(0,0,0,0.12); -fx-border-width: 1px; "
                    + "-fx-background-color: transparent; -fx-cursor: hand;");
                btn.setOnMouseEntered(e -> {
                    if (!btn.isSelected())
                        btn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px; "
                            + "-fx-background-radius: 6px; -fx-border-radius: 6px; "
                            + "-fx-border-color: rgba(0,0,0,0.2); -fx-border-width: 1px; "
                            + "-fx-background-color: rgba(0,0,0,0.03); -fx-cursor: hand;");
                });
                btn.setOnMouseExited(e -> {
                    if (!btn.isSelected())
                        btn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px; "
                            + "-fx-background-radius: 6px; -fx-border-radius: 6px; "
                            + "-fx-border-color: rgba(0,0,0,0.12); -fx-border-width: 1px; "
                            + "-fx-background-color: transparent; -fx-cursor: hand;");
                });
                btn.selectedProperty().addListener((obs, was, isNow) -> {
                    if (isNow) {
                        btn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px; "
                            + "-fx-background-radius: 6px; -fx-border-radius: 6px; "
                            + "-fx-border-color: #3b82f6; -fx-border-width: 1.5px; "
                            + "-fx-background-color: rgba(59,130,246,0.08); -fx-cursor: hand;");
                    } else {
                        btn.setStyle("-fx-font-size: 11px; -fx-padding: 4px 10px; "
                            + "-fx-background-radius: 6px; -fx-border-radius: 6px; "
                            + "-fx-border-color: rgba(0,0,0,0.12); -fx-border-width: 1px; "
                            + "-fx-background-color: transparent; -fx-cursor: hand;");
                    }
                });

                optBox.getChildren().add(btn);

                // 每个选项下方内联显示说明文字
                if (desc != null && !desc.isBlank()) {
                    Label descLabel = new Label(desc);
                    descLabel.setWrapText(true);
                    descLabel.setMaxWidth(280);
                    descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(0,0,0,0.45); "
                        + "-fx-padding: 0 0 0 2px;");
                    optBox.getChildren().add(descLabel);
                }

                optionBtns.add(btn);
                optionsBox.getChildren().add(optBox);
            }
        }

        // 单选按钮水平排列（若有说明文字则纵向包裹）
        if (!multiSelect && !optionBtns.isEmpty()) {
            // 改用 VBox 展示带说明的单选选项，不再强制 HBox 水平排列
            // optionsBox 已经是垂直排列（每个选项的 optBox 已加入）
        }

        root.getChildren().add(optionsBox);

        // Custom answer row ("Other") — always present
        HBox customRow = new HBox(8);
        customRow.setAlignment(Pos.CENTER_LEFT);
        Label customLabel = new Label("其他:");
        customLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(0,0,0,0.5);");
        TextField customField = new TextField();
        customField.setPromptText("自行填写...");
        customField.setStyle("-fx-font-size: 12px; -fx-padding: 4px 8px; "
            + "-fx-background-radius: 6px; -fx-border-radius: 6px; "
            + "-fx-border-color: rgba(0,0,0,0.1); -fx-border-width: 1px; "
            + "-fx-background-color: rgba(0,0,0,0.02);");
        customField.setMaxWidth(300);
        HBox.setHgrow(customField, Priority.ALWAYS);
        customRow.getChildren().addAll(customLabel, customField);
        root.getChildren().add(customRow);

        return new QuestionBlock(root, questionText, group, optionBtns, optionsBox, customField);
    }

    private static class QuestionBlock {
        final VBox root;
        final String questionText;
        final ToggleGroup group;
        final List<ToggleButton> optionBtns;
        final VBox optionsBox;
        final TextField customField;

        QuestionBlock(VBox root, String questionText, ToggleGroup group,
                      List<ToggleButton> optionBtns, VBox optionsBox, TextField customField) {
            this.root = root;
            this.questionText = questionText;
            this.group = group;
            this.optionBtns = optionBtns;
            this.optionsBox = optionsBox;
            this.customField = customField;
        }

        String getAnswer() {
            // Custom field takes priority
            if (customField.getText() != null && !customField.getText().isBlank()) {
                return customField.getText().trim();
            }
            // Selected option
            if (group != null && group.getSelectedToggle() != null) {
                Object data = group.getSelectedToggle().getUserData();
                if (data instanceof String s) return s;
            }
            // Multi-select via checkboxes
            List<String> selected = new ArrayList<>();
            for (javafx.scene.Node node : optionsBox.getChildren()) {
                if (node instanceof CheckBox cb && cb.isSelected()) {
                    Object data = cb.getUserData();
                    if (data instanceof String s) selected.add(s);
                }
            }
            if (!selected.isEmpty()) {
                return String.join(", ", selected);
            }
            return null;
        }
    }
}
