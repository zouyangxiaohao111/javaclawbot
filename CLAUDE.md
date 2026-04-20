# CLAUDE.md

1. 编码前先思考

不要假设。不要掩盖困惑。把权衡讲清楚。

在开始实现之前：

明确说明你的假设。如果不确定，就提问。
如果存在多种理解方式，把它们列出来——不要默默选择一个。
如果有更简单的方法，要指出来。在必要时提出异议。
如果有不清楚的地方，先停下来。指出困惑点并提问。
2. 简单优先

用最少的代码解决问题。不做任何臆测性的扩展。

不添加需求之外的功能。
不为一次性代码做抽象。
不添加未被要求的“灵活性”或“可配置性”。
不为不可能发生的情况编写错误处理。
如果你写了 200 行但其实可以用 50 行解决，就重写。

问自己：“资深工程师会觉得这太复杂吗？”如果答案是会，那就简化。

3. 手术式修改

只改必须改的部分。只清理你自己引入的问题。

在修改已有代码时：

不要“顺便优化”相邻的代码、注释或格式。
不要重构没有问题的部分。
保持现有风格一致，即使你有不同偏好。
如果发现无关的死代码，可以指出——但不要删除。

当你的修改引入“孤立项”时：

删除因你的修改而变得未使用的导入/变量/函数。
不要删除原本就存在的死代码，除非被要求。

检验标准：每一行修改都应能直接对应用户需求。

4. 以目标驱动执行

定义成功标准。循环迭代直到验证通过。

将任务转化为可验证的目标：

“添加校验” → “为非法输入编写测试，然后让测试通过”
“修复 bug” → “写一个能复现问题的测试，然后让它通过”
“重构 X” → “确保修改前后测试都通过”

对于多步骤任务，给出简要计划：

1. [步骤] → 验证：[检查点]
2. [步骤] → 验证：[检查点]
3. [步骤] → 验证：[检查点]

清晰的成功标准能让你独立迭代。模糊的标准（例如“让它能用”）则需要不断确认。


This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

javaclawbot is a Java-based AI agent framework (a fork of openclaw) providing multi-channel access (Telegram, Feishu, Discord, etc.), multi-model support (Anthropic Claude, OpenAI, DeepSeek, etc.), tool execution, session management, and CLI Agent integration.

## Build & Run Commands

```bash
# Build project (skip tests)
mvn clean package -DskipTests

# Run tests
mvn test

# Initialize configuration
java -cp target/classes:target/dependency/* cli.Commands onboard

# Start interactive CLI agent
java -cp target/classes:target/dependency/* cli.Commands agent

# Single message mode
java -cp target/classes:target/dependency/* cli.Commands agent -m "your message"

# Start gateway service (all channels)
java -cp target/classes:target/dependency/* cli.Commands gateway

# View status
java -cp target/classes:target/dependency/* cli.Commands status

# Cron management
java -cp target/classes:target/dependency/* cli.Commands cron list
java -cp target/classes:target/dependency/* cli.Commands cron add -n "name" -m "message" -e 3600
java -cp target/classes:target/dependency/* cli.Commands cron remove JOB_ID
```

## Architecture

### Core Components

- **AgentLoop** (`src/main/java/agent/AgentLoop.java`): Main agent execution loop that processes messages, invokes tools, and manages the conversation cycle. Handles tool call requests/responses iteratively.

- **LLMProvider** (`src/main/java/providers/LLMProvider.java`): Abstract base class for all LLM providers. Implements retry logic (delays: 1s, 2s, 4s), transient error detection (rate limits), and async chat with cancellation support.

- **ToolRegistry** (`src/main/java/agent/tool/ToolRegistry.java`): Central tool management. Tools are registered/unregistered dynamically. Validates parameters before execution, appends hints on errors.

- **ContextBuilder** (`src/main/java/context/ContextBuilder.java`): Builds system prompts combining: identity info, bootstrap files, memory context, and skills. Reads project instruction files (CODE-AGENT.md/CLAUDE.md) up to 200 lines.

- **SessionManager** (`src/main/java/session/SessionManager.java`): Manages conversation sessions with persistence, cost tracking, and memory compression.

### Tool System

Tools implement the `Tool` interface (`src/main/java/agent/tool/Tool.java`) with:
- `name()`: Tool identifier
- `description()`: Human-readable description
- `parameters()`: OpenAI-style parameter schema
- `execute(Map<String, Object>)`: Async execution returning `CompletableFuture<String>`
- `validateParams()`: Parameter validation

Built-in tools located in `src/main/java/agent/tool/`:
- `file/`: ReadFileTool, WriteTool, EditTool, GlobTool, GrepTool, ListFilesTool
- `shell/`: ExecTool (bash/powershell execution with safety checks)
- `web/`: WebSearchTool, WebFetchTool
- `mcp/`: MCP server integration
- `skill/`: SkillTool (skill management)
- `cron/`: CronTool (scheduled tasks)
- `cli/`: CliAgentTool (CLI Agent integration)
- `message/`: MessageTool, PruneMessagesTool

### Skills System

Skills are loaded from `SKILL.md` files in skill directories. SkillsLoader (`src/main/java/skills/SkillsLoader.java`) loads from:
1. Workspace skills directory (`~/.javaclawbot/workspace/skills/`)
2. Built-in skills directory (`src/main/resources/skills/`)

Skills can contain YAML frontmatter metadata and are invoked via the SkillTool.

### CLI Agent Integration

CLI Agent system (`src/main/java/providers/cli/`) enables calling Claude Code or OpenCode CLI through channels:
- `CliAgentPool.java`: Manages active CLI agent sessions per project
- `CliAgentCommandHandler.java`: Handles `/cc`, `/oc` commands from channels
- `ProjectRegistry.java`: Manages project bindings stored in `cli-projects.json`

Supported commands: `/cc <project> <prompt>`, `/oc <project> <prompt>`, `/bind`, `/unbind`, `/projects`

### Channels

Channel implementations in `src/main/java/channels/` inherit from `BaseChannel.java`:
- TelegramChannel, FeishuChannel, DingTalkChannel, EmailChannel, DiscordChannel, WhatsAppChannel, QQChannel

Messages flow through `MessageBus` (`src/main/java/bus/MessageBus.java`) with `InboundMessage` and `OutboundMessage`.

### Provider System

Provider implementations (`src/main/java/providers/`):
- `ProviderRegistry.java`: Maps provider names to implementations
- `ProviderFactory.java`: Creates provider instances from config
- `ModelFallbackManager.java`: Handles model fallback on errors
- `HotSwappableProvider.java`: Proxy allowing hot config reload

Custom providers: Anthropic, OpenAI, DeepSeek, Azure OpenAI, Custom (generic endpoint)

## Configuration

Config file at `~/.javaclawbot/config.json`:
- `agents.defaults`: model, max_tokens, temperature, workspace, development mode
- `providers`: api_key, api_base per provider
- `channels`: channel-specific config (tokens, app_ids)
- `tools`: exec timeout, web search API key, MCP servers
- `gateway`: host, port, heartbeat settings

## Development Mode

When `development: true` in config, the agent loads project instruction files (CODE-AGENT.md or CLAUDE.md) from the main project path (set via `/bind --main`). Only first 200 lines are loaded into context.

## Key Dependencies

- Java 17+, Maven 3.6+
- picocli (CLI), JLine (terminal), Jackson (JSON), Hutool (utils)
- cron-utils, TelegramBots, Lark SDK (Feishu), MCP SDK
- GraalJS (JavaScript execution for skills)