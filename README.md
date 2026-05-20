# NexusAI

<p align="center">
  <strong>AI Agent 管理平台 - 多渠道、多模型、可扩展的智能代理框架</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-blue" alt="Java 17+">
  <img src="https://img.shields.io/badge/License-Apache%202.0-green" alt="License">
  <img src="https://img.shields.io/badge/Version-2.3.11-brightgreen" alt="Version">
  <img src="https://img.shields.io/badge/GUI-JavaFX-orange" alt="GUI">
</p>

---

## 项目简介

NexusAI（原 javaclawbot）是一款基于 Java 构建的 **AI Agent 管理平台**，提供图形化界面（GUI）来配置和管理 AI 助手。系统采用左侧导航 + 右侧内容区的经典布局，支持多渠道接入、多模型配置、技能管理、MCP 工具扩展、数据库查询、定时任务等核心功能。

### 核心特性

- **图形化界面**：基于 JavaFX 的桌面应用，直观的配置和对话界面
- **多渠道支持**：Telegram、飞书、钉钉、Discord、Email、Slack、Matrix 等
- **多模型支持**：DeepSeek、智谱 GLM、阿里云通义、MiniMax、火山引擎等
- **技能系统**：插件式技能扩展，支持从 ClawHub/Skills.sh 市场安装
- **MCP 工具扩展**：通过 Model Context Protocol 连接外部工具和数据源
- **数据库集成**：支持 MySQL、PostgreSQL、Oracle 等主流数据库，内置四层安全围栏
- **定时任务**：Cron 表达式或固定间隔自动触发 AI 任务
- **多会话管理**：标签页式多会话并行，独立上下文，切换自如
- **CLI Agent 集成**：支持 Claude Code 和 OpenCode CLI，多项目并行管理

---

## 快速开始

### 环境要求

- Java 17 或更高版本
- Maven 3.6+
- Node.js（可选，用于 MCP 服务器）

### 安装

**方式一：一键安装包（推荐）**

Windows 用户下载 `NexusAI-Setup-2.3.0.exe`，自动检测并配置环境依赖。

**方式二：手动构建**

```bash
# 克隆项目
git clone https://github.com/your-org/javaclawbot.git
cd javaclawbot

# 构建项目
mvn clean package -DskipTests

# 启动 GUI
java -jar target/NexusAI.jar
```

### 首次启动

首次启动会自动：
1. 创建配置目录 `~/.javaclawbot/`
2. 生成默认 `config.json` 配置文件
3. 初始化工作空间和内置技能

---

## 功能模块

### 对话页面

与 AI 交互的主界面。支持 Markdown 渲染、代码高亮、文件上传、@ 提及等功能。

- **输出窗口**：显示 AI 回复，支持 Markdown 渲染和代码高亮
- **输入框**：支持 `ALT+↑/↓` 导航历史消息
- **状态栏**：显示当前模型名称和上下文使用率（绿色充足/黄色注意/红色即将超限）
- **多标签页**：支持多会话并行，每个标签独立上下文

### 模型配置

配置 AI 大模型的 API 凭证和参数。

| 配置项 | 说明 |
|--------|------|
| Provider | 选择 AI 服务商（DeepSeek、阿里云等） |
| API Key | 服务商提供的 API 密钥 |
| API Base URL | API 端点地址，默认为官方地址 |
| Max Tokens | 单次最大输出长度，默认 65536 |
| Temperature | 随机性参数，0~1，推荐 0.3 |
| 上下文窗口 | 模型能同时处理的最大 Token 数，默认 512000 |

### Agent 配置

设置 Agent 的行为参数。

| 配置项 | 说明 |
|--------|------|
| 主模型 | Agent 使用的核心 AI 模型 |
| 快速模型 | 用于标题生成等轻量级任务，节省成本 |
| 最大迭代次数 | Agent 循环思考的最大次数，推荐 500 |
| 自动压缩上下文 | 对话过长时自动总结历史，强烈建议开启 |

### 技能管理

技能是 AI Agent 的能力插件。通过技能市场或手动安装扩展 Agent 能力。

**技能市场**：
- [ClawHub](https://clawhub.ai) - 21,000+ 技能，中文友好
- [Skills.sh](https://skills.sh) - 110,000+ 技能，全球最大

### MCP 管理

通过 Model Context Protocol 连接外部工具，如联网搜索、图像理解、代码分析等。

### 数据库配置

让 AI 能够查询和操作数据库，内置四层安全围栏：
1. **SQL 注入防护** - 参数化查询杜绝拼接注入
2. **操作分类检测** - 自动识别只读/破坏性操作
3. **用户确认拦截** - 破坏性 SQL 必须人工确认
4. **事务保护** - 自动提交/回滚，支持手动事务控制

**支持的数据库**：MySQL、PostgreSQL、MariaDB、Oracle、SQL Server、H2、SQLite

### 渠道配置

连接外部通讯平台，使 AI 能通过飞书、Telegram、Discord 等渠道与用户交互。

### 定时任务

按计划自动触发 AI 执行任务，支持 Cron 表达式、固定间隔、特定时间三种模式。

---

## 使用方式

### GUI 模式（推荐）

```bash
# 启动图形界面
java -jar target/NexusAI.jar
```

### CLI 模式

```bash
# 启动交互模式
java -cp target/classes:target/dependency/* cli.Commands agent

# 单次对话
java -cp target/classes:target/dependency/* cli.Commands agent -m "你好"

# 启动网关服务
java -cp target/classes:target/dependency/* cli.Commands gateway
```

### 命令列表

| 命令 | 说明 |
|------|------|
| `onboard` | 初始化配置和工作空间 |
| `gateway` | 启动网关服务 |
| `agent` | 与代理交互 |
| `status` | 查看系统状态 |
| `channels status` | 查看渠道状态 |
| `cron list` | 查看定时任务 |
| `cron add` | 添加定时任务 |
| `cron remove` | 删除定时任务 |

---

## 项目架构

```
NexusAI/
├── src/main/java/
│   ├── agent/              # 核心代理引擎
│   │   ├── AgentLoop.java      # 主循环引擎
│   │   └── tool/               # 工具集
│   ├── gui/                # GUI 界面
│   │   └── ui/
│   │       ├── Launcher.java       # 启动入口
│   │       ├── MainStage.java      # 主窗口
│   │       ├── pages/              # 页面组件
│   │       └── components/         # UI 组件
│   ├── bus/                # 消息总线
│   ├── channels/           # 渠道实现
│   ├── cli/                # 命令行接口
│   ├── config/             # 配置管理
│   ├── context/            # 上下文构建
│   ├── memory/             # 记忆系统
│   ├── providers/          # LLM 提供者
│   └── utils/              # 工具类
└── src/main/resources/     # 资源文件
```

---

## 配置说明

配置文件位于 `~/.javaclawbot/config.json`，GUI 启动后可在设置页面可视化编辑。

### 主要配置项

```json
{
  "agents": {
    "defaults": {
      "model": "deepseek/deepseek-v4-pro",
      "max_tokens": 65536,
      "temperature": 0.3,
      "max_tool_iterations": 500,
      "development": true
    }
  },
  "providers": {
    "deepseek": {
      "api_key": "your-api-key",
      "api_base": "https://api.deepseek.com"
    }
  },
  "channels": {
    "feishu": {
      "enabled": true,
      "app_id": "YOUR_APP_ID",
      "app_secret": "YOUR_APP_SECRET"
    }
  }
}
```

---

## 开发指南

### 项目依赖

- **JavaFX** - GUI 框架
- **picocli** - 命令行框架
- **Jackson** - JSON 序列化
- **HikariCP** - 数据库连接池
- **cron-utils** - Cron 表达式处理
- **TelegramBots** - Telegram Bot API
- **Lark SDK** - 飞书开放平台 SDK

### 构建

```bash
# 编译
mvn compile

# 打包
mvn package

# 运行
java -jar target/NexusAI.jar
```

---

## 安全建议

1. **API Key 保护**：不要将 API Key 提交到版本控制系统
2. **数据库安全**：生产环境建议使用只读账号连接数据库
3. **渠道白名单**：使用 `allow_from` 限制消息来源
4. **技能来源**：仅从可信来源安装技能，安装前查看 SKILL.md

---

## 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

---

## 开源协议

本项目采用 [Apache 2.0](LICENSE) 协议开源。

---

## 致谢

感谢所有开源项目的贡献者，本项目参考并使用了以下技术：
- OpenClaw / nanobot
- OpenAI API 规范
- Anthropic Claude API
- 各大消息平台开放 API
