# AddMcpServerDialog RAW 模式支持 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在添加 MCP 服务器的弹窗中添加 RAW 模式选项卡，支持名称+JSON 粘贴配置方式

**Architecture:** AddMcpServerDialog 新增选项卡切换（表单模式/RAW 模式），RAW 模式做前端 JSON 格式校验，BackendBridge 新增 addMcpServer 和 addMcpServerRaw 方法做完整业务验证和持久化，McpPage 编排流程。

**Tech Stack:** JavaFX (Stage/Dialog), Jackson (JSON parse), Java 17

---

### Task 1: 重构 AddMcpServerDialog — 添加选项卡布局

**Files:** `src/main/java/gui/ui/dialogs/AddMcpServerDialog.java`

- [ ] **Step 1: 在 root VBox 顶部添加选项卡按钮栏**

在 `root.getChildren().addAll(title, ...)` 之前，先添加选项卡按钮 HBox：

```java
// 选项卡按钮
HBox tabBar = new HBox(0);
tabBar.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 8px; -fx-padding: 2px;");

Button tabFormBtn = new Button("表单模式");
tabFormBtn.setStyle(tabActiveStyle);
tabFormBtn.setPrefHeight(32);

Button tabRawBtn = new Button("RAW 模式");
tabRawBtn.setStyle(tabInactiveStyle);
tabRawBtn.setPrefHeight(32);

tabBar.getChildren().addAll(tabFormBtn, tabRawBtn);
```

同时定义样式常量：
```java
private static final String tabActiveStyle = "-fx-background-color: white; -fx-background-radius: 6px; -fx-text-fill: #111; -fx-font-size: 13px; -fx-font-weight: 500; -fx-border: none; -fx-cursor: hand;";
private static final String tabInactiveStyle = "-fx-background-color: transparent; -fx-background-radius: 6px; -fx-text-fill: #666; -fx-font-size: 13px; -fx-font-weight: 400; -fx-border: none; -fx-cursor: hand;";
```

- [ ] **Step 2: 将原表单字段包装到 VBox formPanel 中**

将原来的 `nameBox` 和 `commandBox` 放入一个 `VBox formPanel`：

```java
VBox formPanel = new VBox(16);
formPanel.getChildren().addAll(nameBox, commandBox);
```

- [ ] **Step 3: 创建 RAW 模式面板**

```java
// RAW 模式面板
VBox rawPanel = new VBox(16);
rawPanel.setVisible(false);
rawPanel.setManaged(false);

// 名称输入（复用 nameField）
VBox rawNameBox = new VBox(4);
Label rawNameLabel = new Label("服务器名称");
rawNameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");
TextField rawNameField = nameField; // 与表单模式共用
rawNameBox.getChildren().addAll(rawNameLabel, rawNameField);

// JSON 输入
VBox jsonBox = new VBox(4);
Label jsonLabel = new Label("JSON 配置");
jsonLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");

TextArea rawJsonField = new TextArea();
rawJsonField.getStyleClass().add("input-field");
rawJsonField.setPrefHeight(200);
rawJsonField.setPromptText("{\n  \"command\": \"npx\",\n  \"args\": [\"-y\", \"...\"],\n  \"env\": {}\n}");

// 错误标签
Label jsonErrorLabel = new Label();
jsonErrorLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #dc2626;");
jsonErrorLabel.setVisible(false);
jsonErrorLabel.setManaged(false);

jsonBox.getChildren().addAll(jsonLabel, rawJsonField, jsonErrorLabelapsed(16));

rawPanel.getChildren().addAll(rawNameBox, jsonBox);
```

替换 `root.getChildren().addAll(title, nameBox, commandBox, buttonBox)` 为：
```java
root.getChildren().addAll(title, tabBar, formPanel, rawPanel, buttonBox);
```

- [ ] **Step 4: 添加选项卡切换逻辑**

新增字段 `isRawMode`（boolean），在构造方法中添加监听：

```java
tabFormBtn.setOnAction(e -> {
    isRawMode = false;
    tabFormBtn.setStyle(tabActiveStyle);
    tabRawBtn.setStyle(tabInactiveStyle);
    formPanel.setVisible(true);
    formPanel.setManaged(true);
    rawPanel.setVisible(false);
    rawPanel.setManaged(false);
    jsonErrorLabel.setVisible(false);
    jsonErrorLabel.setManaged(false);
});

tabRawBtn.setOnAction(e -> {
    isRawMode = true;
    tabRawBtn.setStyle(tabActiveStyle);
    tabFormBtn.setStyle(tabInactiveStyle);
    formPanel.setVisible(false);
    formPanel.setManaged(false);
    rawPanel.setVisible(true);
    rawPanel.setManaged(true);
    jsonErrorLabel.setVisible(false);
    jsonErrorLabel.setManaged(false);
});
```

- [ ] **Step 5: 默认选中 RAW 模式**

在构造方法末尾（设置 scene 之后，在 setScene 之前更稳妥）主动触发 RAW 模式选中：

```java
// 默认选中 RAW 模式
Platform.runLater(() -> tabRawBtn.fire());
```

需要 import `javafx.application.Platform`。

- [ ] **Step 6: 修改 confirmBtn 的 onClick，RAW 模式下做 JSON 格式校验**

```java
confirmBtn.setOnAction(e -> {
    if (isRawMode) {
        String json = rawJsonField.getText();
        if (json == null || json.isBlank()) {
            jsonErrorLabel.setText("请粘贴 JSON 配置");
            jsonErrorLabel.setVisible(true);
            jsonErrorLabel.setManaged(true);
            return;
        }
        // 格式校验：尝试 parse
        try {
            new ObjectMapper().readTree(json);
        } catch (Exception ex) {
            jsonErrorLabel.setText("JSON 格式错误，请检查后重试");
            jsonErrorLabel.setVisible(true);
            jsonErrorLabel.setManaged(true);
            return;
        }
        jsonErrorLabel.setVisible(false);
        jsonErrorLabel.setManaged(false);
        confirmed = true;
        close();
    } else {
        confirmed = true;
        close();
    }
});
```

需要 import `com.fasterxml.jackson.databind.ObjectMapper`。

- [ ] **Step 7: 新增 getter 方法**

```java
public String getRawJson() {
    return rawJsonField.getText();
}

public boolean isRawMode() {
    return isRawMode;
}
```

- [ ] **Step 8: 调整弹窗宽度以适应 JSON 输入**

`root.setPrefWidth(400)` → `root.setPrefWidth(520)`，给 JSON 区域更多空间。

- [ ] **Step 9: 增加 VBox 间距**

`VBox root = new VBox(16)` 保持不变，JSON 区域内部使用 4px 间距。

---

### Task 2: BackendBridge 新增 addMcpServer / addMcpServerRaw 方法

**Files:** `src/main/java/gui/ui/BackendBridge.java`

- [ ] **Step 1: 新增 addMcpServer 方法**

```java
/**
 * 通过表单模式添加 MCP 服务器
 */
public boolean addMcpServer(String name, String command) {
    if (config.getTools().getMcpServers().containsKey(name)) {
        throw new IllegalArgumentException("服务器名称已存在: " + name);
    }
    MCPServerConfig cfg = new MCPServerConfig();
    cfg.setCommand(command);
    config.getTools().getMcpServers().put(name, cfg);
    try {
        ConfigIO.saveConfig(config, null);
        return true;
    } catch (IOException e) {
        config.getTools().getMcpServers().remove(name);
        throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 2: 新增 addMcpServerRaw 方法**

```java
/**
 * 通过 RAW JSON 模式添加 MCP 服务器
 */
public boolean addMcpServerRaw(String name, String jsonStr) {
    if (config.getTools().getMcpServers().containsKey(name)) {
        throw new IllegalArgumentException("服务器名称已存在: " + name);
    }
    // 使用与 ConfigIO 一致的 ObjectMapper 配置（SNAKE_CASE）
    ObjectMapper mapper = new ObjectMapper();
    mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    MCPServerConfig cfg;
    try {
        cfg = mapper.readValue(jsonStr, MCPServerConfig.class);
    } catch (Exception e) {
        throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
    }

    // 验证：command 或 url 至少一个非空
    boolean hasCommand = cfg.getCommand() != null && !cfg.getCommand().isBlank();
    boolean hasUrl = cfg.getUrl() != null && !cfg.getUrl().isBlank();
    if (!hasCommand && !hasUrl) {
        throw new IllegalArgumentException("command 或 url 至少需要配置一个");
    }

    config.getTools().getMcpServers().put(name, cfg);
    try {
        ConfigIO.saveConfig(config, null);
        return true;
    } catch (IOException e) {
        config.getTools().getMcpServers().remove(name);
        throw new RuntimeException("保存配置失败: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 3: 添加 import**

在 BackendBridge.java 顶部添加：
```java
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
```

（`IOException` 如果已有则跳过）

---

### Task 3: McpPage 编排保存逻辑

**Files:** `src/main/java/gui/ui/pages/McpPage.java`

- [ ] **Step 1: 替换 TODO 占位符为实际调用逻辑**

将：
```java
dialog.showAndWait();
if (dialog.isConfirmed()) {
    // TODO: 添加新服务器
}
```

替换为：
```java
dialog.showAndWait();
if (dialog.isConfirmed()) {
    String name = dialog.getServerName();
    try {
        if (dialog.isRawMode()) {
            backendBridge.addMcpServerRaw(name, dialog.getRawJson());
        } else {
            backendBridge.addMcpServer(name, dialog.getCommand());
        }
        refresh();
    } catch (Exception ex) {
        System.err.println("添加 MCP 服务器失败: " + ex.getMessage());
        // 简单 UI 提示：在控制台输出错误
    }
}
```

---

### Task 4: 编译验证

**Files:** n/a (compile check)

- [ ] **Step 1: 编译项目确认无错误**

Run Maven compile:
```bash
"C:\Program Files\Java\jdk-17\bin\java.exe" -Dmaven.multiModuleProjectDirectory=F:\javaclawbot -Dmaven.home=D:\IDEA20240307\plugins\maven\lib\maven3 -Dclassworlds.conf=D:\IDEA20240307\plugins\maven\lib\maven3\bin\m2.conf -Dmaven.ext.class.path=D:\IDEA20240307\plugins\maven\lib\maven-event-listener.jar -Dfile.encoding=UTF-8 -classpath D:\IDEA20240307\plugins\maven\lib\maven3\boot\plexus-classworlds-2.8.0.jar;D:\IDEA20240307\plugins\maven\lib\maven3\boot\plexus-classworlds.license org.codehaus.classworlds.Launcher -Didea.version=2024.3.7 -Dmaven.repo.local=D:\apps\maven\repository compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: 如有编译错误**

修复后重新编译直到 BUILD SUCCESS。

---

### Task 5: Commit

- [ ] **Step 1: Commit 改动**

```bash
git add src/main/java/gui/ui/dialogs/AddMcpServerDialog.java src/main/java/gui/ui/pages/McpPage.java src/main/java/gui/ui/BackendBridge.java
git commit -m "$(cat <<'EOF'
feat(gui): AddMcpServerDialog 添加 RAW 模式选项卡

在添加 MCP 服务器弹窗中新增 RAW 模式选项卡，
支持直接粘贴 JSON 配置来创建 MCP 服务器。
BackendBridge 新增 addMcpServer/addMcpServerRaw 方法做完整业务验证和持久化。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
