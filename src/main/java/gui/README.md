# Nanobot GUI 客户端

基于 Java Swing 开发的图形化客户端，提供友好的用户界面与 AI 代理交互。

## 功能特性

### 🎨 图形化界面
- **聊天窗口** - 显示对话历史，支持滚动查看
- **输入框** - 输入消息，支持回车发送
- **状态栏** - 实时显示当前状态
- **菜单栏** - 提供文件、设置、帮助等选项

### 💬 对话功能
- **实时对话** - 与 AI 代理进行实时交互
- **进度显示** - 显示 AI 思考和工具调用进度
- **时间戳** - 每条消息显示发送时间
- **样式区分** - 用户消息、AI消息、系统消息使用不同颜色

### ⚙️ 系统功能
- **查看状态** - 显示配置、工作空间、提供商状态
- **打开配置** - 快速打开配置文件
- **打开工作空间** - 快速打开工作空间目录
- **清空对话** - 清空当前对话历史

## 快速开始

### 1. 编译项目

```bash
cd E:\idea_workspac\nanobot-dev
mvn clean package
```

### 2. 运行 GUI 客户端

**方式一：直接运行主类**
```bash
java -cp target/classes:target/dependency/* gui.NanobotGUI
```

**方式二：使用启动器**
```bash
java -cp target/classes:target/dependency/* gui.Launcher
```

**方式三：创建可执行 JAR**

在 `pom.xml` 中添加：
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <configuration>
                <archive>
                    <manifest>
                        <mainClass>gui.NanobotGUI</mainClass>
                    </manifest>
                </archive>
            </configuration>
        </plugin>
    </plugins>
</build>
```

然后运行：
```bash
mvn clean package
java -jar target/nanobot-dev-1.0.jar
```

### 3. 使用 GUI

1. **启动后** - 窗口显示 "Nanobot 已启动"
2. **输入消息** - 在底部输入框输入消息
3. **发送消息** - 点击"发送"按钮或按回车键
4. **查看回复** - AI 回复显示在聊天窗口
5. **查看进度** - AI 思考时显示进度信息

## 界面说明

### 主窗口布局

```
┌─────────────────────────────────────────┐
│  文件  设置  帮助                        │ ← 菜单栏
├─────────────────────────────────────────┤
│                                         │
│  [14:30:15] 你:                         │
│  你好，请帮我写一个Java程序              │ ← 聊天显示区
│                                         │
│  [14:30:16] 🐈 Nanobot:                 │
│  好的，我来帮你写一个简单的Java程序...   │
│                                         │
│  ↳ 正在生成代码...                      │ ← 进度显示
│                                         │
├─────────────────────────────────────────┤
│  输入消息...              [清空] [发送]  │ ← 输入区域
├─────────────────────────────────────────┤
│  就绪 - glm-4-flash                     │ ← 状态栏
└─────────────────────────────────────────┘
```

### 菜单功能

#### 文件菜单
- **查看状态** - 显示配置、工作空间、提供商状态
- **清空对话** - 清空当前对话历史
- **退出** - 关闭程序

#### 设置菜单
- **打开配置文件** - 用系统默认编辑器打开 `config.json`
- **打开工作空间** - 用系统文件管理器打开工作空间目录

#### 帮助菜单
- **关于** - 显示程序版本和说明

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| Enter | 发送消息 |
| Alt+F4 | 退出程序 |

## 配置要求

GUI 客户端使用与命令行相同的配置文件：

- **配置文件**: `~/.nanobot/config.json`
- **工作空间**: `~/.nanobot/workspace`

确保已运行过 `nanobot onboard` 或手动创建配置文件。

## 技术架构

### 核心组件

```
NanobotGUI (主窗口)
    ├── UI组件
    │   ├── JTextPane (聊天显示)
    │   ├── JTextField (输入框)
    │   ├── JButton (按钮)
    │   └── JMenuBar (菜单栏)
    │
    ├── 核心组件
    │   ├── ConfigSchema.Config (配置)
    │   ├── MessageBus (消息总线)
    │   ├── LLMProvider (AI提供商)
    │   ├── AgentLoop (代理循环)
    │   └── CronService (定时任务)
    │
    └── 线程管理
        ├── SwingUtilities (UI线程)
        ├── CompletableFuture (异步任务)
        └── CountDownLatch (同步等待)
```

### 消息流程

```
用户输入 → 发送消息 → MessageBus → AgentLoop → AI处理
                                              ↓
显示回复 ← MessageBus ← AgentLoop ← AI响应 ←┘
```

## 与命令行的区别

| 特性 | 命令行 | GUI |
|------|--------|-----|
| 界面 | 文本终端 | 图形窗口 |
| 交互 | 键盘输入 | 鼠标+键盘 |
| 历史 | JLine历史 | 聊天窗口滚动 |
| 进度 | 文本输出 | 样式化显示 |
| 配置 | 命令参数 | 菜单选项 |
| 多任务 | 单线程 | 多线程 |

## 开发说明

### 依赖项

GUI 客户端依赖以下核心模块：

- `agent.AgentLoop` - AI代理循环
- `bus.MessageBus` - 消息总线
- `config.ConfigIO` - 配置管理
- `providers.LLMProvider` - AI提供商
- `corn.CronService` - 定时任务

### 扩展功能

可以在 `NanobotGUI.java` 中扩展功能：

1. **添加新菜单项** - 在 `createMenuBar()` 方法中添加
2. **自定义样式** - 修改 `userStyle`, `botStyle`, `systemStyle`
3. **添加快捷键** - 在 `InputMap` 和 `ActionMap` 中添加
4. **扩展对话功能** - 在 `sendMessage()` 方法中添加逻辑

## 故障排除

### 问题：启动失败

**原因**: 配置文件不存在或格式错误

**解决**: 
```bash
# 初始化配置
java -cp target/classes:target/dependency/* cli.Commands onboard

# 或手动创建配置文件
mkdir -p ~/.nanobot
echo '{}' > ~/.nanobot/config.json
```

### 问题：无法连接AI服务

**原因**: API密钥未配置或无效

**解决**:
1. 打开配置文件：`~/.nanobot/config.json`
2. 添加API密钥：
```json
{
  "providers": {
    "zhipu": {
      "api_key": "your-api-key"
    }
  }
}
```

### 问题：消息发送后无响应

**原因**: AI服务超时或网络问题

**解决**:
1. 检查网络连接
2. 检查API密钥是否有效
3. 查看状态栏是否显示错误信息

## 未来计划

- [ ] 支持Markdown渲染
- [ ] 支持代码高亮
- [ ] 支持多会话管理
- [ ] 支持主题切换
- [ ] 支持插件扩展
- [ ] 支持语音输入
- [ ] 支持文件拖拽

## 许可证

与 Nanobot 主项目相同

## 联系方式

如有问题或建议，请提交 Issue 或 Pull Request。