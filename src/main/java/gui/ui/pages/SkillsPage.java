package gui.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SkillsPage extends VBox {

    private javafx.scene.layout.GridPane skillGrid;
    private gui.ui.BackendBridge backendBridge;

    public SkillsPage() {
        setSpacing(0);
        setStyle("-fx-background-color: #f1ede1;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(16);
        content.setPadding(new Insets(40, 24, 24, 24));
        content.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("技能管理");
        title.getStyleClass().add("page-title");

        Label subtitle = new Label("扩展 Agent 能力的技能插件");
        subtitle.setStyle("-fx-font-size: 17px; -fx-text-fill: rgba(0, 0, 0, 0.5); -fx-font-weight: 500;");

        VBox titleBox = new VBox(8);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.getChildren().addAll(title, subtitle);

        // 技能网格
        skillGrid = new GridPane();
        skillGrid.setHgap(12);
        skillGrid.setVgap(12);
        skillGrid.setMaxWidth(800);

        String[][] skills = {
            {"\uD83E\uDDE0", "brainstorming", "头脑风暴与设计", "内置"},
            {"\uD83D\uDD0D", "systematic-debugging", "系统化调试", "内置"},
            {"\uD83D\uDCDD", "writing-plans", "编写计划", "内置"},
            {"\u2705", "verification", "完成前验证", "内置"},
            {"\uD83D\uDCCA", "xlsx", "Excel 处理", "已安装"}
        };

        int col = 0, row = 0;
        for (String[] skill : skills) {
            skillGrid.add(createSkillCard(skill[0], skill[1], skill[2], skill[3]), col, row);
            col++;
            if (col >= 2) {
                col = 0;
                row++;
            }
        }

        Button addBtn = new Button("+ 安装新技能");
        addBtn.getStyleClass().add("pill-button");
        addBtn.setPrefHeight(40);
        addBtn.setOnAction(e -> installSkillZip());

        content.getChildren().addAll(titleBox, skillGrid, addBtn);
        VBox.setMargin(addBtn, new Insets(24, 0, 0, 0));

        scrollPane.setContent(content);
        getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    private VBox createSkillCard(String iconText, String name, String desc, String status) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));
        card.setPrefWidth(380);

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        Label iconLabel = new Label(iconText);
        iconLabel.setStyle("-fx-background-color: rgba(59, 130, 246, 0.1); -fx-background-radius: 10px; -fx-pref-width: 40px; -fx-pref-height: 40px; -fx-alignment: center;");
        iconLabel.setMinSize(40, 40);

        VBox infoBox = new VBox(2);
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: 500;");
        Label descLabel = new Label(desc);
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(0, 0, 0, 0.5);");
        infoBox.getChildren().addAll(nameLabel, descLabel);

        Label statusBadge = new Label(status);
        statusBadge.getStyleClass().add("agent-badge");

        header.getChildren().addAll(iconLabel, infoBox, statusBadge);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        card.getChildren().add(header);
        return card;
    }

    public void setBackendBridge(gui.ui.BackendBridge bridge) {
        this.backendBridge = bridge;
        refresh();
    }

    private void refresh() {
        if (backendBridge == null) return;
        skillGrid.getChildren().clear();

        java.util.List<java.util.Map<String, String>> skills =
            backendBridge.getSkillsLoader().listSkills(false);
        int col = 0, row = 0;
        for (java.util.Map<String, String> s : skills) {
            String name = s.get("name");
            String source = s.get("source");
            String status = "builtin".equals(source) ? "内置" : "工作区";
            skillGrid.add(createSkillCard("\u26A1", name,
                source != null ? source : "", status), col, row);
            col++;
            if (col >= 2) { col = 0; row++; }
        }
    }

    private void installSkillZip() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择技能压缩包");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("ZIP 压缩包", "*.zip"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;

        Path skillsDir = backendBridge.getConfig().getWorkspacePath().resolve("skills");
        try {
            extractZip(file, skillsDir.toFile());
            refresh();
        } catch (IOException ex) {
            System.err.println("安装技能失败: " + ex.getMessage());
        }
    }

    private void extractZip(File zipFile, File destDir) throws IOException {
        destDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                // 防止 Zip Slip 攻击
                if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)
                    && !outFile.getCanonicalPath().equals(destDir.getCanonicalPath())) {
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
