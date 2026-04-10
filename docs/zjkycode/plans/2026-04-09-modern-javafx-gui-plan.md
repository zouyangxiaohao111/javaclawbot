# JavaFX GUI 现代化实施计划

> **对于代理工作者：** 使用内联执行方式逐任务实施此计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 为 javaclawbot 构建现代化 macOS 风格的 JavaFX GUI，替代现有的 Swing 界面。

**架构：** 使用 JavaFX 17+ 框架，采用 MVC 模式，通过 FXML 定义布局，CSS 实现样式，RichTextFX 处理 Markdown 渲染和代码高亮。

**技术栈：** JavaFX 17.0.13, RichTextFX 0.11.2, Flowless 0.7.3, ControlsFX 11.2.1, FontAwesomeFX 4.7.0-9.1.2

---

## 前置条件检查

- [ ] **步骤 1：确认项目结构**

```bash
cd E:/idea_workspace/javaclawbot
ls -la src/main/java/gui/
```

预期：`gui` 目录存在，包含 Swing GUI 文件

- [ ] **步骤 2：检查当前分支**

```bash
cd E:/idea_workspace/javaclawbot
git status
git branch
```

预期：位于 `feature/modern-javafx-gui` 分支

- [ ] **步骤 3：备份现有 GUI 文件**

```bash
cd E:/idea_workspace/javaclawbot
cp -r src/main/java/gui src/main/java/gui_swing_backup
echo "Swing GUI backed up"
```

---

## 任务 1：添加 Maven 依赖

**文件：**
- 修改：`E:/idea_workspace/javaclawbot/pom.xml`

- [ ] **步骤 1：定位 dependencies 部分**

查看当前依赖：
```bash
cd E:/idea_workspace/javaclawbot
grep -n "<dependencies>" pom.xml
grep -n "</dependencies>" pom.xml
```

- [ ] **步骤 2：添加 JavaFX 相关依赖**

编辑 `pom.xml`，在 `</dependencies>` 前添加：

```xml
        <!-- JavaFX GUI 现代化依赖 -->
        <!-- JavaFX 基础 -->
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-controls</artifactId>
            <version>17.0.13</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-fxml</artifactId>
            <version>17.0.13</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-web</artifactId>
            <version>17.0.13</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-graphics</artifactId>
            <version>17.0.13</version>
        </dependency>

        <!-- RichTextFX - Markdown 渲染和代码高亮 -->
        <dependency>
            <groupId>org.fxmisc.richtext</groupId>
            <artifactId>richtextfx</artifactId>
            <version>0.11.2</version>
        </dependency>

        <!-- Flowless - 高性能虚拟化列表 -->
        <dependency>
            <groupId>org.fxmisc.flowless</groupId>
            <artifactId>flowless</artifactId>
            <version>0.7.3</version>
        </dependency>

        <!-- ControlsFX - 高级组件 -->
        <dependency>
            <groupId>org.controlsfx</groupId>
            <artifactId>controlsfx</artifactId>
            <version>11.2.1</version>
        </dependency>

        <!-- FontAwesomeFX - 图标 -->
        <dependency>
            <groupId>de.jensd</groupId>
            <artifactId>fontawesomefx-fontawesome</artifactId>
            <version>4.7.0-9.1.2</version>
        </dependency>
```

- [ ] **步骤 3：验证依赖语法**

```bash
cd E:/idea_workspace/javaclawbot
mvn dependency:resolve
```

预期：依赖解析成功，无错误

- [ ] **步骤 4：提交依赖更改**

```bash
cd E:/idea_workspace/javaclawbot
git add pom.xml
git commit -m "feat: 添加 JavaFX 现代化 GUI 依赖"
```

---

## 任务 2：创建 JavaFX 项目结构

**文件：**
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/styles/`

- [ ] **步骤 1：创建目录结构**

```bash
cd E:/idea_workspace/javaclawbot
mkdir -p src/main/java/gui/javafx/controller
mkdir -p src/main/java/gui/javafx/view
mkdir -p src/main/java/gui/javafx/model
mkdir -p src/main/java/gui/javafx/service
mkdir -p src/main/java/gui/javafx/util
mkdir -p src/main/java/gui/javafx/resources/styles
```

- [ ] **步骤 2：验证目录创建**

```bash
find src/main/java/gui/javafx -type d
```

预期：显示创建的目录结构

- [ ] **步骤 3：提交目录结构**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/
git commit -m "feat: 创建 JavaFX GUI 项目结构"
```

---

## 任务 3：创建数据模型

**文件：**
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/model/ChatMessage.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/model/SessionInfo.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/model/ToolCallInfo.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/model/AttachmentInfo.java`

- [ ] **步骤 1：创建 ChatMessage.java**

```java
package gui.javafx.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ChatMessage {
    public enum MessageType {
        USER, AI, SYSTEM, TOOL_CALL, PROGRESS
    }
    
    public enum MessageStatus {
        PENDING, SUCCESS, ERROR
    }
    
    private String id;
    private MessageType type;
    private String content;
    private Instant timestamp;
    private List<AttachmentInfo> attachments = new ArrayList<>();
    private List<ToolCallInfo> toolCalls = new ArrayList<>();
    private MessageStatus status = MessageStatus.SUCCESS;
    private boolean isMarkdown = false;
    private String referencedMessageId;
    
    public ChatMessage() {}
    
    public ChatMessage(String id, MessageType type, String content) {
        this.id = id;
        this.type = type;
        this.content = content;
        this.timestamp = Instant.now();
    }
    
    // Getter 和 Setter 方法
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    
    public List<AttachmentInfo> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentInfo> attachments) { this.attachments = attachments; }
    
    public List<ToolCallInfo> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallInfo> toolCalls) { this.toolCalls = toolCalls; }
    
    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }
    
    public boolean isMarkdown() { return isMarkdown; }
    public void setMarkdown(boolean markdown) { isMarkdown = markdown; }
    
    public String getReferencedMessageId() { return referencedMessageId; }
    public void setReferencedMessageId(String referencedMessageId) { this.referencedMessageId = referencedMessageId; }
}
```

- [ ] **步骤 2：创建 SessionInfo.java**

```java
package gui.javafx.model;

import java.time.Instant;

public class SessionInfo {
    private String id;
    private String title;
    private Instant createdAt;
    private Instant lastActiveAt;
    private int messageCount;
    private String modelUsed;
    
    public SessionInfo() {}
    
    public SessionInfo(String id, String title) {
        this.id = id;
        this.title = title;
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
        this.messageCount = 0;
    }
    
    // Getter 和 Setter 方法
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
}
```

- [ ] **步骤 3：创建 ToolCallInfo.java**

```java
package gui.javafx.model;

import java.time.Instant;
import java.util.Map;

public class ToolCallInfo {
    public enum ToolCallStatus {
        RUNNING, SUCCESS, ERROR
    }
    
    private String toolName;
    private Map<String, Object> parameters;
    private ToolCallStatus status;
    private String result;
    private Instant startTime;
    private Instant endTime;
    
    public ToolCallInfo() {}
    
    public ToolCallInfo(String toolName, Map<String, Object> parameters) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.status = ToolCallStatus.RUNNING;
        this.startTime = Instant.now();
    }
    
    // Getter 和 Setter 方法
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    
    public ToolCallStatus getStatus() { return status; }
    public void setStatus(ToolCallStatus status) { this.status = status; }
    
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
}
```

- [ ] **步骤 4：创建 AttachmentInfo.java**

```java
package gui.javafx.model;

public class AttachmentInfo {
    public enum AttachmentType {
        FILE, IMAGE, AUDIO, VIDEO, DOCUMENT
    }
    
    private String id;
    private String fileName;
    private AttachmentType type;
    private String filePath;
    private long fileSize;
    private String thumbnailPath;
    
    public AttachmentInfo() {}
    
    public AttachmentInfo(String fileName, String filePath, AttachmentType type) {
        this.id = java.util.UUID.randomUUID().toString();
        this.fileName = fileName;
        this.filePath = filePath;
        this.type = type;
    }
    
    // Getter 和 Setter 方法
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    
    public AttachmentType getType() { return type; }
    public void setType(AttachmentType type) { this.type = type; }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    
    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }
}
```

- [ ] **步骤 5：验证模型编译**

```bash
cd E:/idea_workspace/javaclawbot
mvn compile -DskipTests
```

预期：编译成功

- [ ] **步骤 6：提交数据模型**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/model/
git commit -m "feat: 添加 JavaFX GUI 数据模型"
```

---

## 任务 4：创建主题管理服务

**文件：**
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/service/ThemeManager.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/styles/common.css`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/styles/light-theme.css`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/styles/dark-theme.css`

- [ ] **步骤 1：创建 ThemeManager.java**

```java
package gui.javafx.service;

import javafx.scene.Parent;
import java.util.Objects;

public class ThemeManager {
    public enum Theme {
        LIGHT, DARK
    }
    
    private final Parent root;
    private Theme currentTheme;
    
    public ThemeManager(Parent root) {
        this.root = root;
        this.currentTheme = Theme.LIGHT;
    }
    
    public void applyTheme(Theme theme) {
        if (root == null) return;
        
        root.getStylesheets().clear();
        
        String commonCss = Objects.requireNonNull(getClass().getResource("/gui/javafx/resources/styles/common.css")).toExternalForm();
        root.getStylesheets().add(commonCss);
        
        String themeCss = theme == Theme.LIGHT ?
            Objects.requireNonNull(getClass().getResource("/gui/javafx/resources/styles/light-theme.css")).toExternalForm() :
            Objects.requireNonNull(getClass().getResource("/gui/javafx/resources/styles/dark-theme.css")).toExternalForm();
        root.getStylesheets().add(themeCss);
        
        currentTheme = theme;
    }
    
    public void toggleTheme() {
        applyTheme(currentTheme == Theme.LIGHT ? Theme.DARK : Theme.LIGHT);
    }
    
    public Theme getCurrentTheme() {
        return currentTheme;
    }
}
```

- [ ] **步骤 2：创建 common.css**

```css
/* 公共样式定义 */

.root {
    -fx-font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
    -fx-font-smoothing-type: gray;
}

.scroll-bar {
    -fx-background-color: transparent;
}

.scroll-bar .thumb {
    -fx-background-color: #C7C7CC;
    -fx-background-radius: 4px;
}

.scroll-bar .thumb:hover {
    -fx-background-color: #AEAEB2;
}

.button {
    -fx-background-radius: 10px;
    -fx-border-radius: 10px;
    -fx-cursor: hand;
    -fx-padding: 8px 16px;
    -fx-font-size: 13px;
    -fx-font-weight: 500;
}

.button:hover {
    -fx-scale-x: 1.02;
    -fx-scale-y: 1.02;
}

.text-field, .text-area {
    -fx-background-radius: 16px;
    -fx-border-radius: 16px;
    -fx-border-color: transparent;
    -fx-padding: 12px 16px;
    -fx-font-size: 14px;
}

.text-field:focused, .text-area:focused {
    -fx-border-color: #007AFF;
    -fx-border-width: 2px;
}

.separator {
    -fx-background-color: #E5E5EA;
    -fx-pref-height: 1px;
}

.glass-effect {
    -fx-background-color: rgba(255, 255, 255, 0.85);
}

.card {
    -fx-background-radius: 16px;
}
```

- [ ] **步骤 3：创建 light-theme.css**

```css
/* 浅色主题 */

.root {
    -fx-background-color: #F5F5F7;
}

.sidebar {
    -fx-background-color: rgba(255, 255, 255, 0.85);
    -fx-border-color: #E5E5EA;
    -fx-border-width: 0 1 0 0;
}

.message-bubble-user {
    -fx-background-color: #007AFF;
    -fx-background-radius: 16px;
    -fx-text-fill: white;
    -fx-padding: 12px 16px;
}

.message-bubble-ai {
    -fx-background-color: #FFFFFF;
    -fx-background-radius: 16px;
    -fx-text-fill: #1C1C1E;
    -fx-padding: 12px 16px;
}

.message-bubble-system {
    -fx-background-color: #F0F0F5;
    -fx-background-radius: 8px;
    -fx-text-fill: #636366;
    -fx-padding: 8px 12px;
    -fx-font-size: 12px;
}

.input-area {
    -fx-background-color: #FFFFFF;
    -fx-background-radius: 16px;
}

.primary-button {
    -fx-background-color: #007AFF;
    -fx-text-fill: white;
}

.secondary-button {
    -fx-background-color: #F2F2F7;
    -fx-text-fill: #1C1C1E;
}

.danger-button {
    -fx-background-color: #FF3B30;
    -fx-text-fill: white;
}

.primary-text {
    -fx-text-fill: #1C1C1E;
}

.secondary-text {
    -fx-text-fill: #636366;
}

.hint-text {
    -fx-text-fill: #AEAEB2;
}

.link-text {
    -fx-text-fill: #007AFF;
    -fx-cursor: hand;
}

.success-indicator {
    -fx-text-fill: #34C759;
}

.error-indicator {
    -fx-text-fill: #FF3B30;
}
```

- [ ] **步骤 4：创建 dark-theme.css**

```css
/* 深色主题 */

.root {
    -fx-background-color: #1C1C1E;
}

.sidebar {
    -fx-background-color: rgba(44, 44, 46, 0.85);
    -fx-border-color: #38383A;
    -fx-border-width: 0 1 0 0;
}

.message-bubble-user {
    -fx-background-color: #0A84FF;
    -fx-background-radius: 16px;
    -fx-text-fill: white;
    -fx-padding: 12px 16px;
}

.message-bubble-ai {
    -fx-background-color: #2C2C2E;
    -fx-background-radius: 16px;
    -fx-text-fill: #FFFFFF;
    -fx-padding: 12px 16px;
}

.message-bubble-system {
    -fx-background-color: #3A3A3C;
    -fx-background-radius: 8px;
    -fx-text-fill: #EBEBF5;
    -fx-padding: 8px 12px;
    -fx-font-size: 12px;
}

.input-area {
    -fx-background-color: #2C2C2E;
    -fx-background-radius: 16px;
}

.primary-button {
    -fx-background-color: #0A84FF;
    -fx-text-fill: white;
}

.secondary-button {
    -fx-background-color: #3A3A3C;
    -fx-text-fill: #FFFFFF;
}

.danger-button {
    -fx-background-color: #FF453A;
    -fx-text-fill: white;
}

.primary-text {
    -fx-text-fill: #FFFFFF;
}

.secondary-text {
    -fx-text-fill: #EBEBF5;
}

.hint-text {
    -fx-text-fill: #8E8E93;
}

.link-text {
    -fx-text-fill: #0A84FF;
    -fx-cursor: hand;
}

.success-indicator {
    -fx-text-fill: #30D158;
}

.error-indicator {
    -fx-text-fill: #FF453A;
}
```

- [ ] **步骤 5：验证 CSS 文件**

```bash
cd E:/idea_workspace/javaclawbot
find src/main/java/gui/javafx/resources -name "*.css"
```

预期：显示 3 个 CSS 文件

- [ ] **步骤 6：提交主题管理文件**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/service/ThemeManager.java
git add src/main/java/gui/javafx/resources/styles/
git commit -m "feat: 添加主题管理服务和 CSS 样式"
```

---

## 任务 5：创建主窗口入口

**文件：**
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/JavaClawBotFX.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/LauncherFX.java`

- [ ] **步骤 1：创建 JavaClawBotFX.java**

```java
package gui.javafx;

import config.Config;
import config.ConfigIO;
import gui.javafx.controller.MainController;
import gui.javafx.service.ThemeManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

public class JavaClawBotFX extends Application {
    private Config config;
    private ThemeManager themeManager;
    
    @Override
    public void start(Stage primaryStage) throws Exception {
        config = ConfigIO.loadConfig();
        
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/gui/javafx/resources/main.fxml"));
        Parent root = loader.load();
        
        MainController controller = loader.getController();
        controller.initialize(config);
        
        themeManager = new ThemeManager(root);
        themeManager.applyTheme(ThemeManager.Theme.LIGHT);
        
        Scene scene = new Scene(root, 1100, 800);
        
        primaryStage.setTitle("javaclawbot");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(920);
        primaryStage.setMinHeight(660);
        
        primaryStage.setOnCloseRequest(event -> {
            if (controller != null) {
                controller.onWindowClosing();
            }
        });
        
        primaryStage.show();
        
        if (controller != null) {
            controller.onWindowShown();
        }
    }
    
    @Override
    public void stop() throws Exception {
        if (config != null) {
            ConfigIO.saveConfig(config);
        }
        super.stop();
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **步骤 2：创建 LauncherFX.java**

```java
package gui.javafx;

public class LauncherFX {
    public static void main(String[] args) {
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");
        System.setProperty("prism.allowhidpi", "true");
        JavaClawBotFX.main(args);
    }
}
```

- [ ] **步骤 3：验证主类编译**

```bash
cd E:/idea_workspace/javaclawbot
mvn compile -DskipTests
```

预期：编译成功

- [ ] **步骤 4：提交主窗口入口**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/JavaClawBotFX.java
git add src/main/java/gui/javafx/LauncherFX.java
git commit -m "feat: 添加 JavaFX 主窗口入口和启动器"
```

---

## 任务 6：创建 FXML 布局文件

**文件：**
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/main.fxml`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/sidebar.fxml`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/chat.fxml`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/resources/input.fxml`

- [ ] **步骤 1：创建 main.fxml**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>

<BorderPane xmlns="http://javafx.com/javafx"
            xmlns:fx="http://javafx.com/fxml"
            fx:controller="gui.javafx.controller.MainController"
            prefWidth="1100" prefHeight="800">
    
    <top>
        <BorderPane styleClass="header-bar" prefHeight="48">
            <left>
                <HBox alignment="CENTER_LEFT" spacing="8" padding="10">
                    <Label text="javaclawbot" styleClass="primary-text" style="-fx-font-size: 18px; -fx-font-weight: bold;" />
                    <Separator orientation="VERTICAL" />
                    <Label fx:id="statusLabel" text="Ready" styleClass="secondary-text" />
                </HBox>
            </left>
            <right>
                <HBox alignment="CENTER_RIGHT" spacing="8" padding="10">
                    <Button text="Gateway" styleClass="secondary-button" onAction="#onGatewayClicked" />
                    <Button fx:id="themeToggleButton" text="Dark" onAction="#onThemeToggleClicked" 
                            styleClass="secondary-button" minWidth="60" />
                </HBox>
            </right>
        </BorderPane>
    </top>
    
    <center>
        <SplitPane dividerPositions="0.2" style="-fx-background-color: transparent;">
            <fx:include fx:id="sidebar" source="sidebar.fxml" />
            <VBox spacing="0" style="-fx-background-color: transparent;">
                <fx:include fx:id="chatArea" source="chat.fxml" VBox.vgrow="ALWAYS" />
                <fx:include fx:id="inputArea" source="input.fxml" />
            </VBox>
        </SplitPane>
    </center>
    
    <bottom>
        <BorderPane prefHeight="32" styleClass="status-bar">
            <left>
                <HBox alignment="CENTER_LEFT" spacing="8" padding="5">
                    <Label fx:id="modelLabel" text="Model: Claude" styleClass="secondary-text" />
                    <Label fx:id="mcpStatusLabel" text="MCP: 0/0" styleClass="secondary-text" />
                </HBox>
            </left>
            <right>
                <HBox alignment="CENTER_RIGHT" spacing="8" padding="5">
                    <Label fx:id="memoryUsageLabel" text="Memory: 0MB" styleClass="secondary-text" />
                </HBox>
            </right>
        </BorderPane>
    </bottom>
</BorderPane>
```

- [ ] **步骤 2：创建 sidebar.fxml**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>

<VBox xmlns="http://javafx.com/javafx"
      xmlns:fx="http://javafx.com/fxml"
      fx:controller="gui.javafx.controller.SidebarController"
      styleClass="sidebar"
      prefWidth="200" minWidth="50" maxWidth="300">
    
    <!-- 新建对话按钮 -->
    <Button fx:id="newChatButton" text="+ New Chat" 
            styleClass="primary-button" 
            maxWidth="Infinity" 
            prefHeight="40"
            onAction="#onNewChatClicked" />
    
    <Separator />
    
    <!-- 会话列表 -->
    <Label text="Sessions" styleClass="secondary-text" padding="10,10,5,10" />
    <ListView fx:id="sessionListView" VBox.vgrow="ALWAYS" />
    
    <Separator />
    
    <!-- 快捷工具 -->
    <Label text="Tools" styleClass="secondary-text" padding="10,10,5,10" />
    
    <HBox spacing="8" padding="10">
        <Label text="Model:" styleClass="secondary-text" />
        <ComboBox fx:id="modelComboBox" promptText="Select Model" />
    </HBox>
    
    <HBox spacing="8" padding="10">
        <Label text="MCP:" styleClass="secondary-text" />
        <Label fx:id="mcpStatusLabel" text="0/0" styleClass="secondary-text" />
        <Button text="Manage" styleClass="secondary-button" onAction="#onMcpManageClicked" />
    </HBox>
    
    <HBox spacing="8" padding="10">
        <Label text="Dev:" styleClass="secondary-text" />
        <ToggleButton fx:id="devModeToggle" text="OFF" onAction="#onDevModeToggled" />
    </HBox>
    
    <Separator />
    
    <!-- 底部按钮 -->
    <Button text="Settings" styleClass="secondary-button" 
            maxWidth="Infinity" onAction="#onSettingsClicked" />
    <Button text="Help" styleClass="secondary-button" 
            maxWidth="Infinity" onAction="#onHelpClicked" />
    
    <!-- 折叠按钮 -->
    <Button fx:id="toggleSidebarButton" text="<" 
            styleClass="secondary-button" 
            maxWidth="Infinity" onAction="#onToggleSidebarClicked" />
</VBox>
```

- [ ] **步骤 3：创建 chat.fxml**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>

<VBox xmlns="http://javafx.com/javafx"
      xmlns:fx="http://javafx.com/fxml"
      fx:controller="gui.javafx.controller.ChatController"
      spacing="0"
      VBox.vgrow="ALWAYS">
    
    <!-- 消息列表 -->
    <ScrollPane fx:id="scrollPane" VBox.vgrow="ALWAYS" 
                style="-fx-background-color: transparent;">
        <VBox fx:id="messageContainer" spacing="12" padding="20">
            <!-- 消息将动态添加到这里 -->
        </VBox>
    </ScrollPane>
</VBox>
```

- [ ] **步骤 4：创建 input.fxml**

```xml
<?xml version="1.0" encoding="UTF-8"?>

<?import javafx.scene.layout.*?>
<?import javafx.scene.control.*?>
<?import javafx.geometry.Insets?>

VBox xmlns="http://javafx.com/javafx"
     xmlns:fx="http://javafx.com/fxml"
     fx:controller="gui.javafx.controller.InputController"
     spacing="8"
     padding="15"
     styleClass="input-area">
    
    <!-- 附件栏 -->
    <HBox fx:id="attachmentBar" spacing="8" visible="false">
        <Button text="+ File" styleClass="secondary-button" onAction="#onAddFileClicked" />
        <Button text="+ Image" styleClass="secondary-button" onAction="#onAddImageClicked" />
        <Label fx:id="attachmentCountLabel" text="" styleClass="secondary-text" />
    </HBox>
    
    <!-- 输入区域 -->
    <HBox spacing="12" alignment="CENTER_LEFT">
        <TextArea fx:id="inputTextArea" 
                  promptText="Type a message... (Enter to send, Shift+Enter for new line)"
                  styleClass="input-area"
                  wrapText="true"
                  prefRowCount="1"
                  HBox.hgrow="ALWAYS"
                  onKeyPressed="#onInputKeyPressed" />
        
        <Button fx:id="sendButton" text="Send" 
                styleClass="primary-button" 
                prefWidth="80" prefHeight="40"
                onAction="#onSendClicked" />
    </HBox>
    
    <!-- 快捷命令提示 -->
    <Label text="Commands: /new /stop /help /status" styleClass="hint-text" />
</VBox>
```

- [ ] **步骤 5：验证 FXML 文件**

```bash
cd E:/idea_workspace/javaclawbot
find src/main/java/gui/javafx/resources -name "*.fxml"
```

预期：显示 4 个 FXML 文件

- [ ] **步骤 6：提交 FXML 文件**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/resources/
git commit -m "feat: 添加 FXML 布局文件"
```

---

## 任务 7：创建控制器

**文件：**
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/controller/MainController.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/controller/SidebarController.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/controller/ChatController.java`
- 创建：`E:/idea_workspace/javaclawbot/src/main/java/gui/javafx/controller/InputController.java`

- [ ] **步骤 1：创建 MainController.java**

```java
package gui.javafx.controller;

import config.Config;
import gui.javafx.service.ThemeManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

public class MainController {
    @FXML private Label statusLabel;
    @FXML private Label modelLabel;
    @FXML private Label mcpStatusLabel;
    @FXML private Label memoryUsageLabel;
    @FXML private Button themeToggleButton;
    
    @FXML private SidebarController sidebar;
    @FXML private ChatController chatArea;
    @FXML private InputController inputArea;
    
    private Config config;
    private ThemeManager themeManager;
    
    public void initialize(Config config) {
        this.config = config;
        updateStatus("Ready");
        updateModelLabel(config.getProvider());
    }
    
    @FXML
    private void onGatewayClicked() {
        updateStatus("Gateway clicked");
    }
    
    @FXML
    private void onThemeToggleClicked() {
        if (themeManager != null) {
            themeManager.toggleTheme();
            String currentText = themeToggleButton.getText();
            themeToggleButton.setText("Light".equals(currentText) ? "Dark" : "Light");
        }
    }
    
    public void onWindowShown() {
        if (sidebar != null) {
            sidebar.loadSessions();
        }
    }
    
    public void onWindowClosing() {
        // 保存配置
    }
    
    private void updateStatus(String status) {
        if (statusLabel != null) {
            statusLabel.setText(status);
        }
    }
    
    private void updateModelLabel(String provider) {
        if (modelLabel != null && provider != null) {
            modelLabel.setText("Model: " + provider);
        }
    }
}
```

- [ ] **步骤 2：创建 SidebarController.java**

```java
package gui.javafx.controller;

import gui.javafx.model.SessionInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldListCell;

import java.util.ArrayList;
import java.util.List;

public class SidebarController {
    @FXML private ListView<SessionInfo> sessionListView;
    @FXML private ComboBox<String> modelComboBox;
    @FXML private Label mcpStatusLabel;
    @FXML private ToggleButton devModeToggle;
    @FXML private Button toggleSidebarButton;
    
    private ObservableList<SessionInfo> sessions = FXCollections.observableArrayList();
    private boolean expanded = true;
    
    public void initialize() {
        sessionListView.setItems(sessions);
        sessionListView.setCellFactory(param -> new ListCell<SessionInfo>() {
            @Override
            protected void updateItem(SessionInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getTitle());
                }
            }
        });
        
        // 设置模型选项
        modelComboBox.getItems().addAll("Claude", "GPT-4", "DeepSeek");
        modelComboBox.setValue("Claude");
    }
    
    public void loadSessions() {
        sessions.clear();
        // 从 SessionManager 加载会话
        sessions.add(new SessionInfo("1", "New Chat"));
    }
    
    @FXML
    private void onNewChatClicked() {
        sessions.add(0, new SessionInfo(java.util.UUID.randomUUID().toString(), "New Chat"));
    }
    
    @FXML
    private void onMcpManageClicked() {
        // 打开 MCP 管理对话框
    }
    
    @FXML
    private void onDevModeToggled() {
        devModeToggle.setText(devModeToggle.isSelected() ? "ON" : "OFF");
    }
    
    @FXML
    private void onSettingsClicked() {
        // 打开设置对话框
    }
    
    @FXML
    private void onHelpClicked() {
        // 显示帮助
    }
    
    @FXML
    private void onToggleSidebarClicked() {
        expanded = !expanded;
        toggleSidebarButton.setText(expanded ? "<" : ">");
    }
}
```

- [ ] **步骤 3：创建 ChatController.java**

```java
package gui.javafx.controller;

import gui.javafx.model.ChatMessage;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public class ChatController {
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messageContainer;
    
    private List<ChatMessage> messages = new ArrayList<>();
    
    public void initialize() {
        messageContainer.setSpacing(12);
        messageContainer.setPadding(new Insets(20));
    }
    
    public void addMessage(ChatMessage message) {
        messages.add(message);
        
        Label messageLabel = new Label(message.getContent());
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(600);
        
        if (message.getType() == ChatMessage.MessageType.USER) {
            messageLabel.getStyleClass().add("message-bubble-user");
        } else if (message.getType() == ChatMessage.MessageType.AI) {
            messageLabel.getStyleClass().add("message-bubble-ai");
        } else {
            messageLabel.getStyleClass().add("message-bubble-system");
        }
        
        messageContainer.getChildren().add(messageLabel);
        
        // 滚动到底部
        scrollPane.setVvalue(1.0);
    }
    
    public void clearMessages() {
        messages.clear();
        messageContainer.getChildren().clear();
    }
}
```

- [ ] **步骤 4：创建 InputController.java**

```java
package gui.javafx.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;

public class InputController {
    @FXML private TextArea inputTextArea;
    @FXML private Button sendButton;
    @FXML private Label attachmentCountLabel;
    
    private ChatController chatController;
    
    public void initialize() {
        updateSendButton(true);
    }
    
    public void setChatController(ChatController chatController) {
        this.chatController = chatController;
    }
    
    @FXML
    private void onSendClicked() {
        String text = inputTextArea.getText();
        if (text != null && !text.trim().isEmpty()) {
            if (chatController != null) {
                // 创建用户消息
                // chatController.addMessage(...)
            }
            inputTextArea.clear();
        }
    }
    
    @FXML
    private void onInputKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER:
                if (!event.isShiftDown()) {
                    event.consume();
                    onSendClicked();
                }
                break;
            default:
                break;
        }
    }
    
    @FXML
    private void onAddFileClicked() {
        // 添加文件
    }
    
    @FXML
    private void onAddImageClicked() {
        // 添加图片
    }
    
    public void updateSendButton(boolean enabled) {
        sendButton.setText(enabled ? "Send" : "Stop");
        sendButton.getStyleClass().clear();
        sendButton.getStyleClass().add(enabled ? "primary-button" : "danger-button");
    }
}
```

- [ ] **步骤 5：验证控制器编译**

```bash
cd E:/idea_workspace/javaclawbot
mvn compile -DskipTests
```

预期：编译成功

- [ ] **步骤 6：提交控制器**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/controller/
git commit -m "feat: 添加 JavaFX 控制器"
```

---

## 任务 8：集成测试

**文件：**
- 修改：现有 pom.xml（添加 JavaFX 模块路径配置）

- [ ] **步骤 1：添加 JavaFX Maven 插件配置**

在 pom.xml 的 build 部分添加：

```xml
<plugin>
    <groupId>org.openjfx</groupId>
    <artifactId>javafx-maven-plugin</artifactId>
    <version>0.0.8</version>
    <configuration>
        <mainClass>gui.javafx.LauncherFX</mainClass>
    </configuration>
</plugin>
```

- [ ] **步骤 2：创建启动脚本**

创建 `E:/idea_workspace/javaclawbot/run-javafx.bat`：

```batch
@echo off
cd /d "%~dp0"
call mvn javafx:run -f pom.xml
```

- [ ] **步骤 3：运行测试**

```bash
cd E:/idea_workspace/javaclawbot
mvn compile -DskipTests
```

预期：编译成功，无错误

- [ ] **步骤 4：提交集成测试**

```bash
cd E:/idea_workspace/javaclawbot
git add run-javafx.bat
git commit -m "test: 添加 JavaFX 启动脚本"
```

---

## 任务 9：完善功能

**文件：**
- 创建：Markdown 渲染服务
- 创建：消息气泡组件
- 创建：工具调用可视化组件

- [ ] **步骤 1：创建 MarkdownRenderer.java**

```java
package gui.javafx.service;

import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownRenderer {
    private static final Pattern CODE_PATTERN = Pattern.compile("```[\\s\\S]*?```|`[^`]+`");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*[^*]+\\*\\*|__[^_]+__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*[^*]+\\*|[^\\*]+[^\\*]");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[[^\\]]+\\]\\([^)]+\\)");
    
    public StyleSpans<?> computeStyles(String text) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        
        int lastEnd = 0;
        Matcher matcher = CODE_PATTERN.matcher(text);
        
        while (matcher.find()) {
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singletonList("code"), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        
        return builder.create();
    }
}
```

- [ ] **步骤 2：创建 MessageBubbleView.java**

```java
package gui.javafx.view;

import gui.javafx.model.ChatMessage;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class MessageBubbleView extends VBox {
    private ChatMessage message;
    
    public MessageBubbleView(ChatMessage message) {
        this.message = message;
        setupUI();
    }
    
    private void setupUI() {
        setSpacing(4);
        setPadding(new Insets(8));
        
        Label contentLabel = new Label(message.getContent());
        contentLabel.setWrapText(true);
        
        if (message.getType() == ChatMessage.MessageType.USER) {
            getStyleClass().add("message-bubble-user");
        } else if (message.getType() == ChatMessage.MessageType.AI) {
            getStyleClass().add("message-bubble-ai");
        } else {
            getStyleClass().add("message-bubble-system");
        }
        
        getChildren().add(contentLabel);
    }
}
```

- [ ] **步骤 3：创建 ToolCallView.java**

```java
package gui.javafx.view;

import gui.javafx.model.ToolCallInfo;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

public class ToolCallView extends VBox {
    private ToolCallInfo toolCallInfo;
    
    public ToolCallView(ToolCallInfo toolCallInfo) {
        this.toolCallInfo = toolCallInfo;
        setupUI();
    }
    
    private void setupUI() {
        setSpacing(8);
        setPadding(new Insets(12));
        getStyleClass().add("message-bubble-tool");
        
        Label titleLabel = new Label("Tool: " + toolCallInfo.getToolName());
        titleLabel.setStyle("-fx-font-weight: bold;");
        
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(20, 20);
        
        getChildren().addAll(titleLabel, progress);
    }
}
```

- [ ] **步骤 4：提交完善功能**

```bash
cd E:/idea_workspace/javaclawbot
git add src/main/java/gui/javafx/service/
git add src/main/java/gui/javafx/view/
git commit -m "feat: 添加 Markdown 渲染和视图组件"
```

---

## 任务 10：最终测试和提交

- [ ] **步骤 1：完整编译测试**

```bash
cd E:/idea_workspace/javaclawbot
mvn clean compile -DskipTests
```

预期：编译成功

- [ ] **步骤 2：运行应用程序**

```bash
cd E:/idea_workspace/javaclawbot
mvn javafx:run
```

预期：JavaFX 窗口显示

- [ ] **步骤 3：最终提交**

```bash
cd E:/idea_workspace/javaclawbot
git status
git add .
git commit -m "feat: 完成 JavaFX GUI 现代化实现"
git push origin feature/modern-javafx-gui
```

---

## 计划完成

计划已保存到 `docs/zjkycode/plans/2026-04-09-modern-javafx-gui-plan.md`。

**两种执行选项：**

1. **子代理驱动（推荐）** - 我为每个任务调度一个新子代理，在任务之间审查，快速迭代

2. **内联执行** - 使用 executing-plans 在此会话中执行任务，带检查点的批量执行

**选择哪种方式？**