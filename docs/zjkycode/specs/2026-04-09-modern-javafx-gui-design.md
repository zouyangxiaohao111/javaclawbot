# JavaClawBot 现代化 JavaFX GUI 设计文档

**日期**: 2026-04-09  
**状态**: 已批准  
**作者**: C-550W  
**分支**: feature/modern-javafx-gui

---

## 1. 概述

### 1.1 背景

javaclawbot 当前使用 Swing + FlatLaf 实现 GUI，界面风格较为原始，缺乏现代化视觉效果和高级交互功能。本设计旨在使用 JavaFX 完全重写 GUI，实现 macOS 风格的现代化界面。

### 1.2 目标

- **视觉现代化**: macOS 风格设计，毛玻璃效果、柔和圆角、精致图标
- **功能增强**: Markdown 渲染、代码高亮、图片显示、工具调用可视化、深色/浅色主题切换
- **用户体验**: 平滑动画、响应式布局、拖拽支持、快捷键操作

### 1.3 范围

- 新建 JavaFX GUI 模块 (`src/main/java/gui/javafx/`)
- **保留现有 Swing GUI**：作为备选方案，用户可通过启动参数选择 GUI 类型
  - `gui.Launcher` → Swing GUI（默认）
  - `gui.javafx.LauncherFX` → JavaFX GUI
- 与现有核心组件（AgentLoop、SessionManager、Config）无缝集成
- **不修改**：核心代理逻辑、消息总线、配置系统、工具系统

---

## 2. 技术方案

### 2.1 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| JavaFX | 17.0.13 | UI 框架 |
| RichTextFX | 0.11.2 | Markdown 渲染、代码高亮 |
| Flowless | 0.7.3 | 高性能虚拟化列表 |
| ControlsFX | 11.2.1 | 高级组件（通知、对话框） |
| FontAwesomeFX | 4.7.0-9.1.2 | 图标库 |

### 2.2 架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                        JavaClawBotFX (主窗口)                        │
├─────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌──────────────────────────────────────────────┐  │
│  │  Sidebar    │  │              MainContent                      │  │
│  │  Controller │  │  ┌────────────────────────────────────────┐  │  │
│  │             │  │  │              HeaderBar                  │  │  │
│  │  • 会话列表 │  │  └────────────────────────────────────────┘  │  │
│  │  • 模型切换 │  │  ┌────────────────────────────────────────┐  │  │
│  │  • MCP状态  │  │  │              ChatArea                   │  │  │
│  │  • 设置     │  │  │              Controller                 │  │  │
│  │             │  │  └────────────────────────────────────────┘  │  │
│  │             │  │  ┌────────────────────────────────────────┐  │  │
│  │             │  │  │              InputArea                  │  │  │
│  │             │  │  │              Controller                 │  │  │
│  │             │  │  └────────────────────────────────────────┘  │  │
│  └─────────────┘  └──────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.3 模块划分

| 模块 | 类名 | 职责 |
|------|------|------|
| **主窗口** | `JavaClawBotFX` | 程序入口，窗口管理，主题切换 |
| **侧边栏** | `SidebarController` | 会话列表、快捷工具、折叠控制 |
| **聊天区** | `ChatController` | 消息显示、Markdown 渲染、滚动管理 |
| **输入区** | `InputController` | 输入框、发送按钮、快捷键处理 |
| **消息气泡** | `MessageBubble` | 单条消息渲染、操作按钮 |
| **工具可视化** | `ToolCallView` | 工具调用过程展示 |
| **主题管理** | `ThemeManager` | CSS 主题切换 |

---

## 3. 界面设计

### 3.1 布局结构

采用单窗口经典布局：左侧侧边栏 + 右侧聊天区 + 底部输入框。

### 3.2 侧边栏设计

**展开状态：**

- 新建对话按钮
- 会话列表（按日期分组，可搜索）
- 模型选择下拉框
- MCP 状态指示器（显示连接数）
- 开发者模式开关
- 设置按钮
- 折叠按钮

**折叠状态：**

- 仅显示图标
- 宽度约 50px

**视觉风格：**

- 毛玻璃背景效果
- 圆角 12px
- 悬停时轻微高亮
- 当前会话项带蓝色边框

### 3.3 聊天区设计

**消息类型：**

| 类型 | 对齐 | 背景 | 特殊功能 |
|------|------|------|----------|
| 系统消息 | 居中 | 灰色 | 小字体，无操作按钮 |
| 进度消息 | 居中 | 浅灰 | 动画指示器 |
| 用户消息 | 右对齐 | 蓝色 | 附件图标 |
| AI 消息 | 左对齐 | 白色 | Markdown 渲染 |
| 工具调用 | 左对齐 | 浅灰 | 可展开/折叠 |

**消息操作按钮：**

- 复制：复制消息文本
- 删除：删除消息
- 重新生成：重新请求 AI 回复
- 引用回复：引用消息内容
- 复制代码：仅复制代码块
- 运行代码：执行代码（可选）

**Markdown 渲染支持：**

- 标题（H1-H6）
- 粗体、斜体、删除线
- 代码块（语法高亮）
- 链接（可点击）
- 列表、表格
- 图片（预览，点击放大）

**工具调用可视化：**

- 显示工具名称和参数
- 状态指示：执行中、成功、失败
- 结果可展开/折叠

### 3.4 输入区设计

**组件：**

- 多行输入框（自动调整高度，最小 1 行，最大 5 行）
- 附件按钮（文件选择器，支持拖拽）
- 图片按钮
- 已选附件列表
- 快捷命令按钮
- 发送/停止按钮（状态切换）

**快捷键：**

- Enter：发送消息
- Shift+Enter：换行
- 上/下箭头：输入历史

**快捷命令：**

- `/new`：新建对话
- `/stop`：停止任务
- `/help`：显示帮助
- `/bind`：绑定项目
- `/status`：查看状态

---

## 4. 主题设计

### 4.1 浅色主题配色

| 用途 | 颜色 | 说明 |
|------|------|------|
| 窗口背景 | `#F5F5F7` | 柔和灰白 |
| 侧边栏背景 | `#FFFFFF` | 白色（毛玻璃） |
| 主文字 | `#1C1C1E` | 深灰黑 |
| 次文字 | `#636366` | 中灰 |
| 主强调色 | `#007AFF` | Apple Blue |
| 成功色 | `#34C759` | 绿色 |
| 错误色 | `#FF3B30` | 红色 |
| 用户气泡 | `#007AFF` | 蓝色背景 |
| AI 气泡 | `#FFFFFF` | 白色背景 |

### 4.2 深色主题配色

| 用途 | 颜色 | 说明 |
|------|------|------|
| 窗口背景 | `#1C1C1E` | 深灰黑 |
| 侧边栏背景 | `#2C2C2E` | 深灰（毛玻璃） |
| 主文字 | `#FFFFFF` | 白色 |
| 次文字 | `#EBEBF5` | 浅白灰 |
| 主强调色 | `#0A84FF` | Apple Blue Dark |
| 成功色 | `#30D158` | 绿色 |
| 错误色 | `#FF453A` | 红色 |
| 用户气泡 | `#0A84FF` | 蓝色背景 |
| AI 气泡 | `#2C2C2E` | 深灰背景 |

### 4.3 macOS 风格元素

| 元素 | 规格 |
|------|------|
| 大圆角 | 12-16px |
| 小圆角 | 8px |
| 阴影 | `0 2px 8px rgba(0,0,0,0.08)` |
| 毛玻璃 | `backdrop-filter: blur(20px)` |
| 按钮 | 圆角 10px，悬停放大 1.02x |
| 输入框 | 圆角 16px，聚焦蓝色边框 |

### 4.4 动画效果

| 动画 | 触发 | 效果 |
|------|------|------|
| 悬停动画 | 鼠标悬停 | 放大 + 阴影增强 |
| 点击涟漪 | 点击按钮 | 涟漪扩散 |
| 消息入场 | 新消息 | 滑入 + 淡入 |
| 侧边栏折叠 | 点击按钮 | 宽度过渡 300ms |
| 主题切换 | 切换主题 | 颜色过渡 200ms |

### 4.5 字体规范

| 用途 | 字体 | 大小 | 权重 |
|------|------|------|------|
| 窗口标题 | SF Pro Display / Microsoft YaHei UI | 18px | Bold |
| 消息内容 | SF Pro Text / Microsoft YaHei UI | 14px | Regular |
| 代码块 | SF Mono / JetBrains Mono | 13px | Regular |
| 时间戳 | SF Pro Text | 11px | Regular |
| 按钮文字 | SF Pro Text | 13px | Medium |

---

## 5. 项目结构

```
src/main/java/
├── gui/
│   ├── javafx/
│   │   ├── JavaClawBotFX.java          # 主窗口入口
│   │   ├── LauncherFX.java             # JavaFX 启动器
│   │   │
│   │   ├── controller/
│   │   │   ├── MainController.java     # 主窗口控制器
│   │   │   ├── SidebarController.java  # 侧边栏控制器
│   │   │   ├── ChatController.java     # 聊天区控制器
│   │   │   ├── InputController.java    # 输入区控制器
│   │   │   └── SettingsController.java # 设置对话框控制器
│   │   │
│   │   ├── view/
│   │   │   ├── MessageBubble.java      # 消息气泡组件
│   │   │   ├── ToolCallView.java       # 工具调用可视化
│   │   │   ├── SessionListItem.java    # 会话列表项
│   │   │   ├── AttachmentView.java     # 附件显示组件
│   │   │   └── CodeBlockView.java      # 代码块组件
│   │   │
│   │   ├── model/
│   │   │   ├── ChatMessage.java        # 消息数据模型
│   │   │   ├── SessionInfo.java        # 会话信息模型
│   │   │   ├── ToolCallInfo.java       # 工具调用信息
│   │   │   └── AttachmentInfo.java     # 附件信息
│   │   │
│   │   ├── service/
│   │   │   ├── MarkdownRenderer.java   # Markdown 渲染服务
│   │   │   ├── ThemeManager.java       # 主题管理
│   │   │   ├── SessionStore.java       # 会话存储
│   │   │   └── ImageLoader.java        # 图片加载服务
│   │   │
│   │   └── util/
│   │   │   ├── AnimationUtils.java     # 动画工具
│   │   │   ├── ClipboardUtils.java     # 剑贴板工具
│   │   │   └── DragDropHandler.java    # 拖拽处理
│   │   │
│   │   └── resources/
│   │       ├── main.fxml               # 主窗口布局
│   │       ├── sidebar.fxml            # 侧边栏布局
│   │       ├── chat.fxml               # 聊天区布局
│   │       ├── input.fxml              # 输入区布局
│   │       ├── settings.fxml           # 设置对话框布局
│   │       │
│   │       └── styles/
│   │           ├── light-theme.css     # 浅色主题
│   │           ├── dark-theme.css      # 深色主题
│   │           └── common.css          # 公共样式
│   │
│   └── swing/                          # 保留旧 Swing GUI（可选）
│       ├── JavaClawBotGUI.java
│       ├── Launcher.java
│       └── ...
```

---

## 6. Maven 依赖

```xml
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

---

## 7. 与现有系统集成

| 集成点 | 现有组件 | 新 GUI 接口 |
|--------|----------|-------------|
| 消息接收 | `MessageBus` → `OutboundMessage` | `ChatController.onOutboundMessage()` |
| 消息发送 | `InboundMessage` | `InputController.sendToAgentLoop()` |
| 会话管理 | `SessionManager` | `SidebarController.loadSessions()` |
| 配置读取 | `Config` | `MainController.initFromConfig()` |
| 工具调用 | `ToolRegistry` | `ToolCallView.displayToolCall()` |
| MCP 状态 | `McpManager` | `SidebarController.updateMcpStatus()` |

---

## 8. 数据模型

### 8.1 ChatMessage

```java
public class ChatMessage {
    private String id;
    private MessageType type;        // USER, AI, SYSTEM, TOOL_CALL, PROGRESS
    private String content;
    private Instant timestamp;
    private List<AttachmentInfo> attachments;
    private List<ToolCallInfo> toolCalls;
    private MessageStatus status;    // PENDING, SUCCESS, ERROR
    private boolean isMarkdown;
    private String referencedMessageId;
}
```

### 8.2 SessionInfo

```java
public class SessionInfo {
    private String id;
    private String title;
    private Instant createdAt;
    private Instant lastActiveAt;
    private int messageCount;
    private String modelUsed;
}
```

### 8.3 ToolCallInfo

```java
public class ToolCallInfo {
    private String toolName;
    private Map<String, Object> parameters;
    private ToolCallStatus status;   // RUNNING, SUCCESS, ERROR
    private String result;
    private Instant startTime;
    private Instant endTime;
}
```

---

## 9. 核心类设计

### 9.1 JavaClawBotFX（主入口）

```java
public class JavaClawBotFX extends Application {
    private Config config;
    private AgentLoop agentLoop;
    private SessionManager sessionManager;
    private ThemeManager themeManager;

    @Override
    public void start(Stage primaryStage) {
        // 1. 加载配置
        config = ConfigIO.loadConfig();

        // 2. 初始化核心组件
        initializeCoreComponents();

        // 3. 加载 FXML
        FXMLLoader loader = new FXMLLoader(getClass().getResource("jfx/main.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        controller.setDependencies(config, agentLoop, sessionManager);

        // 4. 应用主题
        themeManager = new ThemeManager(root);
        themeManager.applyTheme(config.getTheme());

        // 5. 设置窗口
        primaryStage.setTitle("javaclawbot");
        primaryStage.setScene(new Scene(root, 1100, 800));
        primaryStage.setMinWidth(920);
        primaryStage.setMinHeight(660);
        primaryStage.show();
    }
}
```

### 9.2 ThemeManager

```java
public class ThemeManager {
    private final Parent root;
    private Theme currentTheme;
    
    public enum Theme { LIGHT, DARK }
    
    public void applyTheme(Theme theme) {
        root.getStylesheets().clear();
        root.getStylesheets().add(getClass().getResource("common.css").toExternalForm());
        root.getStylesheets().add(getClass().getResource(
            theme == Theme.LIGHT ? "light-theme.css" : "dark-theme.css"
        ).toExternalForm());
        currentTheme = theme;
    }
    
    public void toggleTheme() {
        applyTheme(currentTheme == Theme.LIGHT ? Theme.DARK : Theme.LIGHT);
    }
}
```

---

## 10. 成功标准

1. **视觉**: 界面符合 macOS 风格设计规范
2. **功能**: 所有设计功能正常工作
3. **性能**: 消息渲染流畅，无明显延迟
4. **兼容**: 与现有核心组件无缝集成
5. **稳定**: 无内存泄漏，长时间运行稳定

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| JavaFX 环境配置问题 | 高 | 使用 Maven 管理依赖，提供启动脚本 |
| Markdown 渲染性能 | 中 | 使用 RichTextFX 虚拟化渲染 |
| 毛玻璃效果跨平台兼容 | 低 | 提供备选样式，优雅降级 |
| 与现有代码集成复杂度 | 中 | 保持接口清晰，逐步迁移 |

---

## 12. 附录

### 12.1 CSS 样式示例

```css
/* light-theme.css */

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
    -fx-background-radius: 16;
    -fx-text-fill: white;
}

.message-bubble-ai {
    -fx-background-color: #FFFFFF;
    -fx-background-radius: 16;
    -fx-text-fill: #1C1C1E;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 8, 0, 0, 2);
}

.input-area {
    -fx-background-color: #FFFFFF;
    -fx-background-radius: 16;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 8, 0, 0, 2);
}

.send-button {
    -fx-background-color: #007AFF;
    -fx-background-radius: 10;
    -fx-text-fill: white;
    -fx-cursor: hand;
}

.send-button:hover {
    -fx-scale-x: 1.02;
    -fx-scale-y: 1.02;
}
```

---

**文档结束**