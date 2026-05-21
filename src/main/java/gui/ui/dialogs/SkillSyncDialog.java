package gui.ui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import skills.SkillDifference;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能同步弹窗
 * 显示内置技能与工作空间技能的差异，让用户选择覆盖
 */
public class SkillSyncDialog extends Stage {

    private final List<SkillDifference> differences;
    private final Map<CheckBox, SkillDifference> checkBoxMap = new LinkedHashMap<>();
    private List<String> selectedSkills = Collections.emptyList();

    public SkillSyncDialog(List<SkillDifference> differences) {
        this.differences = differences;

        setTitle("技能同步");
        initStyle(StageStyle.TRANSPARENT);
        initModality(Modality.APPLICATION_MODAL);

        buildUI();
    }

    private void buildUI() {
        // 主容器
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #faf9f5; -fx-background-radius: 12px;");

        // 标题
        Label titleLabel = new Label("发现技能差异");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;");

        // 说明
        Label descLabel = new Label("以下内置技能与工作空间中的版本不一致，请选择需要覆盖的技能：");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        // 技能列表
        ScrollPane scrollPane = createSkillList();

        // 按钮区域
        HBox buttonBox = createButtons();

        root.getChildren().addAll(titleLabel, descLabel, scrollPane, buttonBox);

        Scene scene = new Scene(root, 500, 400);
        scene.setFill(null);
        setScene(scene);
    }

    private ScrollPane createSkillList() {
        VBox listBox = new VBox(8);
        listBox.setPadding(new Insets(8, 0, 8, 0));

        for (SkillDifference diff : differences) {
            HBox item = createSkillItem(diff);
            listBox.getChildren().add(item);
        }

        ScrollPane scrollPane = new ScrollPane(listBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(250);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: #e0e0e0; -fx-border-radius: 8px;");

        return scrollPane;
    }

    private HBox createSkillItem(SkillDifference diff) {
        CheckBox checkBox = new CheckBox(diff.getSkillName());
        checkBox.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        checkBoxMap.put(checkBox, diff);

        Label typeLabel = new Label(diff.getTypeLabel());
        typeLabel.setStyle("-fx-background-color: " + getTypeColor(diff.getType()) + "; "
            + "-fx-text-fill: white; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox item = new HBox(12, checkBox, spacer, typeLabel);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setPadding(new Insets(8, 12));
        item.setStyle("-fx-background-color: white; -fx-background-radius: 8; "
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 4, 0, 0, 2);");

        return item;
    }

    private String getTypeColor(SkillDifference.DifferenceType type) {
        return switch (type) {
            case NEW -> "#4caf50";
            case MODIFIED -> "#ff9800";
        };
    }

    private HBox createButtons() {
        Button selectAllBtn = new Button("全选");
        selectAllBtn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; "
            + "-fx-padding: 8 16; -fx-background-radius: 6;");
        selectAllBtn.setOnAction(e -> checkBoxMap.keySet().forEach(cb -> cb.setSelected(true)));

        Button deselectAllBtn = new Button("全不选");
        deselectAllBtn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; "
            + "-fx-padding: 8 16; -fx-background-radius: 6;");
        deselectAllBtn.setOnAction(e -> checkBoxMap.keySet().forEach(cb -> cb.setSelected(false)));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; "
            + "-fx-padding: 8 16; -fx-background-radius: 6;");
        cancelBtn.setOnAction(e -> {
            selectedSkills = Collections.emptyList();
            close();
        });

        Button confirmBtn = new Button("覆盖选中");
        confirmBtn.setStyle("-fx-background-color: #cc785c; -fx-text-fill: white; "
            + "-fx-padding: 8 16; -fx-background-radius: 6; -fx-font-weight: bold;");
        confirmBtn.setOnAction(e -> {
            selectedSkills = checkBoxMap.entrySet().stream()
                .filter(entry -> entry.getKey().isSelected())
                .map(entry -> entry.getValue().getSkillName())
                .collect(Collectors.toList());
            close();
        });

        HBox buttonBox = new HBox(12, selectAllBtn, deselectAllBtn, spacer, cancelBtn, confirmBtn);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);

        return buttonBox;
    }

    /**
     * 显示弹窗并返回用户选择的技能列表
     */
    public static List<String> showAndWait(List<SkillDifference> differences) {
        if (differences.isEmpty()) {
            return Collections.emptyList();
        }

        SkillSyncDialog dialog = new SkillSyncDialog(differences);
        dialog.showAndWait();
        return dialog.selectedSkills;
    }
}
