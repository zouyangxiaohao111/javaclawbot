# javaclawbot

<p align="center">
  <strong>个人 AI 助手 - 多渠道、多模型、可扩展的智能代理框架</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-blue" alt="Java 17+">
  <img src="https://img.shields.io/badge/License-Apache%202.0-green" alt="License">
  <img src="https://img.shields.io/badge/Status-Active-brightgreen" alt="Status">
</p>

---

## 项目简介

javaclawbot 是一个基于 Java 构建的个人 AI 助手框架，提供多渠道接入、多模型支持、工具调用、会话管理、记忆存储等核心功能。设计理念参考了现代 AI Agent 架构，支持通过工具调用实现复杂任务的自动化执行。

### 核心特性

- **多渠道支持**：Telegram、WhatsApp、Discord、飞书、钉钉、QQ、Email、Slack、Matrix 等
- **多模型支持**：Anthropic Claude、OpenAI、OpenRouter、DeepSeek、Groq、智谱 GLM、阿里云通义、Google Gemini、Moonshot、MiniMax、硅基流动、火山引擎等
- **工具系统**：文件读写、命令执行、网络搜索、网页抓取、消息发送、子代理、定时任务
- **会话管理**：持久化会话、上下文窗口、记忆压缩与归档
- **项目上下文**：开发者模式下自动加载项目的 CODE-AGENT.md/CLAUDE.md，通过 `/bind --main` 设置主代理项目
- **CLI Agent 集成**：支持 Claude Code 和 OpenCode CLI，多项目并行管理
- **CLI 交互**：命令行交互模式，支持历史记录

---

## 快速开始

### 环境要求

- Java 17 或更高版本
- Maven 3.6+

### 安装

```bash
# 克隆项目
git clone https://github.com/your-org/javaclawbot.git
cd javaclawbot

# 构建项目
mvn clean package -DskipTests
```

### 初始化配置

```bash
# 初始化配置文件和工作空间
java -cp target/classes:target/dependency/* cli.Commands onboard
```

初始化后会在 `~/.javaclawbot/` 目录下创建：
- `config.json` - 配置文件
- `workspace/` - 工作空间

### 配置 API Key

编辑 `~/.javaclawbot/config.json`，添加你的 API Key：

```json
{
  "providers": {
    "anthropic": {
      "api_key": "your-anthropic-api-key"
    },
    "openai": {
      "api_key": "your-openai-api-key"
    }
  },
  "agents": {
    "defaults": {
      "model": "anthropic/claude-opus-4-5"
    }
  }
}
```

---

## 使用方式

### CLI 交互模式

```bash
# 启动交互模式
java -cp target/classes:target/dependency/* cli.Commands agent

# 单次对话
java -cp target/classes:target/dependency/* cli.Commands agent -m "你好，请介绍一下你自己"
```

### 启动网关服务

```bash
# 启动完整网关（包括所有渠道）
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
javaclawbot/
├── src/main/java/
│   ├── agent/              # 核心代理引擎
│   │   ├── AgentLoop.java      # 主循环引擎
│   │   ├── tool/               # 工具集
│   ├── bus/                # 消息总线
│   ├── channels/           # 渠道实现
│   ├── cli/                # 命令行接口
│   ├── config/             # 配置管理
│   ├── context/            # 上下文构建
│   │   ├── ContextBuilder.java # 上下文构建器
│   │   ├── BootstrapLoader.java# 引导文件加载
│   │   └── MemoryStore.java    # 记忆存储
│   ├── corn/               # 定时任务
│   ├── memory/             # 记忆系统
│   ├── providers/          # LLM 提供者
│   │   └── cli/            # CLI Agent (Claude Code / OpenCode)
│   ├── session/            # 会话管理
│   └── utils/              # 工具类
└── src/main/resources/     # 资源文件
```

---

## 配置说明

### 完整配置示例

```json
{
  "agents": {
    "defaults": {
      "workspace": "~/.javaclawbot/workspace",
      "model": "anthropic/claude-opus-4-5",
      "provider": "auto",
      "max_tokens": 8192,
      "temperature": 0.1,
      "max_tool_iterations": 40,
      "memory_window": 100,
      "development": false,
      "project_path": null
    }
  },
  "providers": {
    "anthropic": {
      "api_key": "",
      "api_base": "https://api.anthropic.com"
    },
    "openai": {
      "api_key": "",
      "api_base": "https://api.openai.com/v1"
    },
    "deepseek": {
      "api_key": "",
      "api_base": "https://api.deepseek.com"
    },
    "custom": {
      "api_key": "",
      "api_base": ""
    }
  },
  "channels": {
    "telegram": {
      "enabled": false,
      "token": "",
      "allow_from": []
    },
    "feishu": {
      "enabled": false,
      "app_id": "",
      "app_secret": "",
      "allow_from": []
    },
    "dingtalk": {
      "enabled": false,
      "client_id": "",
      "client_secret": ""
    }
  },
  "tools": {
    "web": {
      "search": {
        "api_key": "",
        "max_results": 5
      }
    },
    "exec": {
      "timeout": 60,
      "path_append": ""
    },
    "restrict_to_workspace": false
  },
  "gateway": {
    "host": "0.0.0.0",
    "port": 18790,
    "heartbeat": {
      "enabled": true,
      "interval_s": 1800
    }
  }
}
```

### 模型选择

通过在模型名前添加前缀来指定提供者：

```
anthropic/claude-opus-4-5    # 使用 Anthropic
openai/gpt-4o                # 使用 OpenAI
deepseek/deepseek-chat       # 使用 DeepSeek
custom/your-model-name       # 使用自定义端点
```

---

## 工具系统

javaclawbot 内置以下工具：

| 工具 | 说明 |
|------|------|
| `read_file` | 读取文件内容 |
| `write_file` | 写入文件 |
| `edit_file` | 编辑文件（搜索替换） |
| `list_dir` | 列出目录内容 |
| `exec` | 执行系统命令 |
| `web_search` | 网络搜索（Brave API） |
| `web_fetch` | 抓取网页内容 |
| `message` | 发送消息到渠道 |
| `spawn` | 创建子代理任务 |
| `cron` | 管理定时任务 |

### MCP 服务器

支持配置 MCP (Model Context Protocol) 服务器扩展工具能力：

```json
{
  "tools": {
    "mcp_servers": {
      "my-server": {
        "command": "node",
        "args": ["server.js"],
        "env": {},
        "tool_timeout": 30
      }
    }
  }
}
```

---

## 渠道配置

### Telegram

```json
{
  "channels": {
    "telegram": {
      "enabled": true,
      "token": "YOUR_BOT_TOKEN",
      "allow_from": ["user_id_1", "user_id_2"],
      "proxy": "http://127.0.0.1:7890",
      "reply_to_message": true
    }
  }
}
```

### 飞书

```json
{
  "channels": {
    "feishu": {
      "enabled": true,
      "app_id": "YOUR_APP_ID",
      "app_secret": "YOUR_APP_SECRET",
      "encrypt_key": "",
      "verification_token": "",
      "react_emoji": "THUMBSUP",
      "allow_from": []
    }
  }
}
```

### 钉钉

```json
{
  "channels": {
    "dingtalk": {
      "enabled": true,
      "client_id": "YOUR_CLIENT_ID",
      "client_secret": "YOUR_CLIENT_SECRET",
      "allow_from": []
    }
  }
}
```

### Email

```json
{
  "channels": {
    "email": {
      "enabled": true,
      "imap_host": "imap.gmail.com",
      "imap_port": 993,
      "imap_username": "your@email.com",
      "imap_password": "app-password",
      "smtp_host": "smtp.gmail.com",
      "smtp_port": 587,
      "smtp_username": "your@email.com",
      "smtp_password": "app-password",
      "from_address": "your@email.com",
      "poll_interval_seconds": 30
    }
  }
}
```

---

## 定时任务

### 添加定时任务

```bash
# 每隔 3600 秒执行一次
java -cp ... cli.Commands cron add -n "每日提醒" -m "请提醒我喝水" -e 3600

# 使用 cron 表达式
java -cp ... cli.Commands cron add -n "早间问候" -m "早上好！" -c "0 9 * * *"

# 指定时区
java -cp ... cli.Commands cron add -n "定时任务" -m "执行任务" -c "0 9 * * *" --tz "Asia/Shanghai"

# 指定时间执行一次
java -cp ... cli.Commands cron add -n "一次性任务" -m "提醒我开会" --at "2026-03-10T09:00:00Z"
```

### 管理任务

```bash
# 列出所有任务
java -cp ... cli.Commands cron list

# 列出包括已禁用的任务
java -cp ... cli.Commands cron list -a

# 禁用任务
java -cp ... cli.Commands cron enable JOB_ID --disable

# 启用任务
java -cp ... cli.Commands cron enable JOB_ID

# 删除任务
java -cp ... cli.Commands cron remove JOB_ID

# 手动运行任务
java -cp ... cli.Commands cron run JOB_ID
```

---

## 会话命令

在对话中可使用以下命令：

| 命令 | 说明 |
|------|------|
| `/new` | 开始新对话（归档当前记忆） |
| `/stop` | 停止当前任务 |
| `/help` | 显示帮助信息 |

### 项目绑定命令

| 命令 | 说明 |
|------|------|
| `/bind <名称>=<路径> [--main]` | 绑定项目，`--main` 设为主代理项目 |
| `/bind --main <路径>` | 直接设置主代理项目（名称自动为 main） |
| `/unbind <名称>` | 解绑项目 |
| `/projects` | 列出所有绑定的项目 |

---

## CLI Agent 集成

javaclawbot 支持集成 Claude Code CLI 和 OpenCode CLI，允许通过飞书等渠道直接调用本地 CLI 工具。

### 支持的 CLI Agent

| Agent | 命令前缀 | 说明 |
|-------|---------|------|
| Claude Code | `/cc`, `/claude`, `/claudecode` | Anthropic 官方 CLI |
| OpenCode | `/oc`, `/opencode` | 开源代码助手 |

### 项目绑定命令

| 命令 | 说明 |
|------|------|
| `/bind <名称>=<路径>` | 绑定项目 |
| `/bind <名称>=<路径> --main` | 绑定项目并设为主代理项目 |
| `/bind --main <路径>` | 直接设置主代理项目（名称自动为 main） |
| `/unbind <名称>` | 解绑项目 |
| `/projects` | 列出所有绑定的项目（标注主代理项目 ⭐） |

### CLI Agent 管理命令

| 命令 | 说明 |
|------|------|
| `/cc <项目> <提示词>` | 使用 Claude Code |
| `/oc <项目> <提示词>` | 使用 OpenCode |
| `/status [项目]` | 查看 Agent 状态 |
| `/stop <项目> [类型]` | 停止项目的 Agent（可指定 claude/opencode） |
| `/cli-stopall` | 停止所有 CLI Agent |

### 使用示例

```bash
# 绑定普通项目
/bind p1=/home/user/myproject
/bind webapp=/var/www/html

# 绑定并设为主代理项目（开发者模式会读取其 CODE-AGENT.md）
/bind main=/home/user/myproject --main

# 直接设置主代理项目
/bind --main /home/user/myproject

# 列出所有项目（主代理项目会标注 ⭐）
/projects

# 使用 Claude Code
/cc p1 帮我分析代码结构
/cc p1 修复这个 bug

# 使用 OpenCode
/oc p1 写一个单元测试

# 查看状态
/status p1

# 停止特定项目的 Agent
/stop p1

# 停止特定项目特定类型的 Agent
/stop p1 claude

# 停止所有 CLI Agent
/cli-stopall
```

### 权限自动处理

CLI Agent 会自动处理工具权限请求：
- **自动允许**: 读取工具 (Read, Glob, Grep)、编辑工具 (Edit, Write)、网络工具 (WebSearch, WebFetch)、安全的 Bash 命令
- **自动拒绝**: 危险命令 (rm -rf, mkfs, dd, format 等)
- **询问用户**: 其他操作需要用户确认

用户可通过回复 `y` 或 `n` 来确认或拒绝权限请求。

### 输出格式

CLI Agent 的输出会带有项目前缀和 Agent 类型标识：
```
[CC/p1] ▶ Read src/main.java
[CC/p1]   ✓ 成功读取文件
[CC/p1] ✅ 完成 (tokens: 1500/800)

[OpenCode/p2] ▶ Bash npm test
[OpenCode/p2] ✅ 完成 (tokens: 2000/500)
```

### 主代理上下文同步

CLI Agent 的输出会自动记录到主代理的对话历史中，主代理可以了解 CLI Agent 的活动并回答相关问题（如 "刚才 CLI 做了什么？"）。

---

## 项目上下文（开发者模式）

开发者模式下，javaclawbot 会自动加载主代理项目的指令文件（`CODE-AGENT.md` 或 `CLAUDE.md`），将其前 200 行加入上下文，帮助 AI 更好理解项目结构。

### 启用开发者模式

在配置文件中设置：

```json
{
  "agents": {
    "defaults": {
      "development": true
    }
  }
}
```

### 设置主代理项目

使用 `/bind --main` 设置主代理项目：

```bash
# 方式一：绑定并设为主代理
/bind main=/home/user/my-project --main

# 方式二：直接设置
/bind --main /home/user/my-project
```

主代理项目的路径会存储在 `cli-projects.json` 中，与 CLI Agent 共享项目配置。

### 项目指令文件

- 优先读取 `CODE-AGENT.md`
- 其次读取 `CLAUDE.md`
- 仅读取前 200 行，完整内容可通过 `read_file` 工具获取

---

## 开发指南

### 项目依赖

- **picocli** - 命令行框架
- **JLine** - 终端交互
- **Jackson** - JSON 序列化
- **Hutool** - Java 工具库
- **cron-utils** - Cron 表达式处理
- **TelegramBots** - Telegram Bot API
- **Lark SDK** - 飞书开放平台 SDK

### 构建

```bash
# 编译
mvn compile

# 打包
mvn package

# 运行测试
mvn config.json
```

### 扩展渠道

1. 创建继承 `BaseChannel` 的类
2. 实现必要方法：`start()`, `stop()`, `send()`
3. 在 `ChannelManager` 中注册

### 扩展工具

1. 实现 `Tool` 接口
2. 在 `ToolRegistry` 中注册

---

## 安全建议

1. **API Key 保护**：不要将 API Key 提交到版本控制系统
2. **工作空间限制**：设置 `restrict_to_workspace: true` 限制文件操作范围
3. **命令执行**：谨慎使用 `exec` 工具，避免执行危险命令
4. **渠道白名单**：使用 `allow_from` 限制消息来源

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
- https://github.com/HKUDS/nanobot
- OpenAI API 规范
- Anthropic Claude API
- 各大消息平台开放 API
