# Subagent 系统完整设计文档

> 基于 Open-ClaudeCode 逆向工程的子代理系统

**版本**: 1.1
**日期**: 2026-04-21
**目标**: 在 javaclawbot 中复刻 Open-ClaudeCode 的完整子代理系统

---

## 1. 系统概述

### 1.1 设计目标

1. **Fork 子代理** - 轻量级 fork，继承父代理完整上下文，共享 Prompt Cache
2. **专用代理** - 支持 agent 定义（Explore、Plan、general-purpose 等）
3. **团队协作（Teammates）** - 支持 tmux/iTerm2 分屏或进程内运行
4. **工作目录隔离** - 通过 git worktree 实现隔离工作副本
5. **远程执行（CCR）** - 通过 Cloud Code Runtime 远程执行
6. **进度追踪** - 详细的 ProgressTracker 和通知机制

### 1.2 跨平台支持

| 平台 | In-Process | tmux | iTerm2 | ConPTY (预留) |
|------|-----------|------|--------|---------------|
| macOS | ✅ | ✅ | ✅ | - |
| Linux | ✅ | ✅ | ❌ | - |
| Windows | ✅ | ❌ | ❌ | 预留接口 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AgentTool（统一入口）                          │
│                                                                      │
│  调用方式：                                                            │
│  1. sessions_spawn → Fork Subagent 或 Named Subagent                │
│  2. Agent(name="worker", team_name="myteam") → Teammate            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
┌───────────────┐    ┌─────────────────┐    ┌───────────────────┐
│ Fork Subagent │    │ Named Subagent  │    │ Team Spawn        │
│ (轻量级 Fork) │    │ (专用代理)      │    │ (Teammate)       │
└───────┬───────┘    └────────┬────────┘    └─────────┬─────────┘
        │                     │                       │
        ▼                     ▼                       ▼
┌─────────────────────┐  ┌──────────┐         ┌─────────────────┐
│ ForkAgentExecutor   │  │ runAgent │         │ spawnTeammate   │
│ - 继承父上下文       │  │          │         │                 │
│ - 共享Cache         │  │ - 独立上下文│        │                 │
└───────┬─────────────┘  └──────────┘         └────────┬────────┘
        │                                           │
        │           ┌───────────────────────────────┘
        │           ▼
        │    ┌─────────────────────┐
        └───►│   BackendRouter     │
             │   (后端路由)         │
             └─────────┬───────────┘
                       │
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
┌───────────┐   ┌───────────┐   ┌───────────┐
│ InProcess │   │   tmux    │   │  iTerm2   │
│ Backend   │   │  Backend  │   │  Backend  │
└───────────┘   └───────────┘   └───────────┘
```

---

## 3. 核心类型定义

### 3.1 Task 和 TaskState

```java
// Task.java
// 对应 Open-ClaudeCode: src/Task.ts - Task 接口
public interface Task {
    String name();
    TaskType type();
    void kill(String taskId, SetAppState setAppState);
}

// TaskType.java
// 对应 Open-ClaudeCode: src/Task.ts - TaskType
public enum TaskType {
    LOCAL_AGENT,      // 本地代理任务
    REMOTE_AGENT,     // 远程代理任务
    IN_PROCESS_TEAMMATE,  // 进程内 Teammate
    LOCAL_WORKFLOW    // 本地工作流
}

// TaskStatus.java
// 对应 Open-ClaudeCode: src/Task.ts - TaskStatus
public enum TaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, KILLED;
    public boolean isTerminal() {...}
}
```

### 3.2 TaskStateBase

```java
// TaskState.java
// 对应 Open-ClaudeCode: src/Task.ts - TaskStateBase
public abstract class TaskState {
    String id;
    TaskType type;
    TaskStatus status;
    String description;
    String toolUseId;
    long startTime;
    long endTime;
    String outputFile;
    long outputOffset;
    boolean notified;
}
```

### 3.3 LocalAgentTaskState

```java
// LocalAgentTaskState.java
// 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - LocalAgentTaskState
public class LocalAgentTaskState extends TaskState {
    String agentId;
    String prompt;
    String selectedAgentType;
    String model;
    AtomicBoolean abortSignal;
    ProgressTracker progressTracker;
    List<Map<String, Object>> messages;
    boolean isBackgrounded;
    Map<String, String> pendingMessages;
}
```

### 3.4 AgentDefinition

```java
// AgentDefinition.java
// 对应 Open-ClaudeCode: src/tools/AgentTool/loadAgentsDir.ts - BuiltInAgentDefinition
public class AgentDefinition {
    String agentType;
    String whenToUse;
    List<String> tools;
    List<String> disallowedTools;
    String model;
    PermissionMode permissionMode;
    int maxTurns;
    boolean background;
    Isolation isolation;
    Supplier<String> getSystemPrompt;
    String source;
    String baseDir;
    boolean readOnly;
    boolean omitClaudeMd;
}

// PermissionMode.java
// 对应 Open-ClaudeCode: src/utils/permissions/PermissionMode.ts
public enum PermissionMode {
    BYPASS_PERMISSIONS, ACCEPT_EDITS, PLAN, BUBBLE
}
```

### 3.5 Fork 相关类型

```java
// ForkContext.java
// 对应 Open-ClaudeCode: src/utils/forkedAgent.ts - ForkedAgentParams
public class ForkContext {
    String parentAgentId;
    String directive;
    Map<String, Object> parentAssistantMessage;
    String parentSystemPrompt;
    List<Map<String, Object>> parentMessages;
    Map<String, String> userContext;
    Map<String, String> systemContext;
}

// CacheSafeParams.java
// 对应 Open-ClaudeCode: src/utils/forkedAgent.ts - CacheSafeParams
public class CacheSafeParams {
    String systemPrompt;
    Map<String, String> userContext;
    Map<String, String> systemContext;
    ToolUseContext toolUseContext;
    List<Map<String, Object>> forkContextMessages;
}

// SubagentContext.java
// 对应 Open-ClaudeCode: src/utils/forkedAgent.ts - createSubagentContext()
public class SubagentContext {
    Map<String, Object> fileStateCache;
    AtomicBoolean abortSignal;
    ProgressTracker progressTracker;
    boolean shouldAvoidPermissionPrompts;
    ToolUseContext toolUseContext;
    String agentId;
    String parentAgentId;
}
```

---

## 4. 源码路径映射表

### Phase 0: 基础设施

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `types/TaskType.java` | `src/Task.ts` - TaskType | 任务类型枚举 |
| `types/TaskStatus.java` | `src/Task.ts` - TaskStatus | 任务状态枚举 |
| `types/TaskState.java` | `src/Task.ts` - TaskStateBase | 任务状态基类 |
| `types/Task.java` | `src/Task.ts` - Task 接口 | 任务接口 |
| `types/AppState.java` | `src/state/AppState.ts` | 应用状态 |
| `types/SetAppState.java` | `src/Task.ts` - SetAppState | 状态更新接口 |
| `lifecycle/LocalAgentTaskState.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - LocalAgentTaskState | 本地任务状态 |
| `framework/ProgressTracker.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - ProgressTracker | 进度追踪器 |
| `framework/TaskRegistry.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - registerAsyncAgent() | 任务注册表 |
| `framework/TaskFramework.java` | `src/utils/task/framework.ts` - TaskFramework | 任务框架核心 |
| `framework/TaskPersistence.java` | `src/utils/task/diskOutput.ts` | 任务持久化 |

### Phase 1: Fork 核心

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `fork/ForkContext.java` | `src/utils/forkedAgent.ts` - ForkedAgentParams | Fork 上下文 |
| `fork/ForkContextBuilder.java` | `src/utils/forkedAgent.ts` - buildForkedAgentParams() | Fork 上下文构建器 |
| `fork/CacheSafeParams.java` | `src/utils/forkedAgent.ts` - CacheSafeParams | Cache 安全参数 |
| `fork/ForkAgentDefinition.java` | `src/tools/AgentTool/forkSubagent.ts` - FORK_AGENT | Fork 代理定义 |
| `fork/ForkAgentDefinition.buildForkedMessages()` | `src/tools/AgentTool/forkSubagent.ts` - buildForkedMessages() | Fork 消息构建 |
| `fork/ForkAgentDefinition.buildChildMessage()` | `src/tools/AgentTool/forkSubagent.ts` - buildChildMessage() | Fork 子消息 |
| `fork/ForkSubagentTool.java` | `src/tools/AgentTool/AgentTool.tsx` - AgentTool 组件 | Fork 入口 Tool |
| `fork/ForkAgentExecutor.java` | `src/tools/AgentTool/runAgent.ts` - runAgent() | Fork 执行器 |
| `context/SubagentContext.java` | `src/utils/forkedAgent.ts` - createSubagentContext() | 上下文隔离 |
| `definition/AgentDefinition.java` | `src/tools/AgentTool/loadAgentsDir.ts` - BuiltInAgentDefinition | 代理定义基类 |
| `definition/PermissionMode.java` | `src/utils/permissions/PermissionMode.ts` | 权限模式 |
| `tool/ToolUseContext.java` | `src/Tool.ts` - ToolUseContext | 工具使用上下文 |

### Phase 2: 专用代理

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `definition/AgentDefinitionLoader.java` | `src/tools/AgentTool/loadAgentsDir.ts` - getAgentDefinitionsWithOverrides() | 代理加载器 |
| `builtin/GeneralPurposeAgent.java` | `src/tools/AgentTool/built-in/generalPurposeAgent.ts` - GENERAL_PURPOSE_AGENT | 通用代理 |
| `builtin/ExploreAgent.java` | `src/tools/AgentTool/built-in/exploreAgent.ts` - EXPLORE_AGENT | 探索代理 |
| `builtin/PlanAgent.java` | `src/tools/AgentTool/built-in/planAgent.ts` - PLAN_AGENT | 计划代理 |
| `builtin/BuiltInAgents.java` | `src/tools/AgentTool/builtInAgents.ts` - getBuiltInAgents() | 内置代理注册 |
| `execution/AgentTool.java` | `src/tools/AgentTool/AgentTool.tsx` - AgentTool | 主入口 Tool |
| `execution/runAgent.java` | `src/tools/AgentTool/runAgent.ts` - runAgent() | 代理执行循环 |
| `execution/resumeAgent.java` | `src/tools/AgentTool/resumeAgent.ts` | 代理恢复 |
| `execution/AgentToolUtils.java` | `src/tools/AgentTool/agentToolUtils.ts` | 工具函数 |
| `execution/AgentToolResult.java` | `src/tools/AgentTool/AgentTool.tsx` - AgentToolResult | 执行结果 |

### Phase 3: 团队协作 - 基础

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `team/TeamCoordinator.java` | `src/tools/shared/spawnMultiAgent.ts` - handleSpawn() | 团队协调器 |
| `team/TeammateRegistry.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - teammateRegistry | Teammate 注册 |
| `team/TeammateInfo.java` | `src/tools/shared/spawnMultiAgent.ts` - SpawnOutput | Teammate 信息 |
| `team/messaging/TeammateMailbox.java` | `src/tools/shared/spawnMultiAgent.ts` - TeammateMailbox | Teammate 信箱 |
| `team/backends/Backend.java` | `src/tools/shared/spawnMultiAgent.ts` - Backend 接口 | 后端接口 |
| `team/backends/BackendRouter.java` | `src/tools/shared/spawnMultiAgent.ts` - BackendRouter | 后端路由 |
| `team/backends/BackendType.java` | `src/tools/shared/spawnMultiAgent.ts` - BackendType | 后端类型 |
| `team/backends/InProcessBackend.java` | `src/tools/shared/spawnMultiAgent.ts` - spawnInProcessTeammate() | 进程内后端 |

### Phase 4: 分屏支持

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `team/backends/tmux/TmuxBackend.java` | `src/tools/shared/spawnMultiAgent.ts` - spawnTmuxTeammate() | tmux 后端 |
| `team/backends/tmux/TmuxSession.java` | `src/tools/shared/spawnMultiAgent.ts` - TmuxSession | tmux 会话 |
| `team/backends/tmux/TmuxPane.java` | `src/tools/shared/spawnMultiAgent.ts` - TmuxPane | tmux pane |
| `team/backends/iterm2/ITerm2Backend.java` | `src/tools/shared/spawnMultiAgent.ts` - spawnITerm2Teammate() | iTerm2 后端 |
| `team/backends/iterm2/ITerm2Session.java` | `src/tools/shared/spawnMultiAgent.ts` - ITerm2Session | iTerm2 会话 |
| `team/backends/iterm2/ITerm2Pane.java` | `src/tools/shared/spawnMultiAgent.ts` - ITerm2Pane | iTerm2 pane |
| `team/backends/windows/ConPTYBackend.java` | 预留接口 | Windows ConPTY（未来） |

### Phase 5: 远程执行

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `remote/CCRClient.java` | `src/tools/shared/spawnMultiAgent.ts` - teleportToRemote() | CCR 客户端 |
| `remote/RemoteSession.java` | `src/tools/shared/spawnMultiAgent.ts` - RemoteSession | 远程会话 |
| `remote/TeleportService.java` | `src/tools/shared/spawnMultiAgent.ts` - TeleportService | 远程启动服务 |
| `remote/RemoteBackend.java` | `src/tools/shared/spawnMultiAgent.ts` - RemoteBackend | 远程后端 |

---

## 5. 关键源码文件列表

### 5.1 AgentTool 核心

| Open-ClaudeCode 路径 | 功能 |
|---------------------|------|
| `src/tools/AgentTool/AgentTool.tsx` | 主入口组件，包含 Tool 定义和 call() 方法 |
| `src/tools/AgentTool/runAgent.ts` | 代理执行循环 |
| `src/tools/AgentTool/forkSubagent.ts` | Fork 子代理核心逻辑 |
| `src/tools/AgentTool/resumeAgent.ts` | 代理恢复逻辑 |
| `src/tools/AgentTool/loadAgentsDir.ts` | 代理定义加载 |
| `src/tools/AgentTool/builtInAgents.ts` | 内置代理注册 |
| `src/tools/AgentTool/agentToolUtils.ts` | 工具函数 |
| `src/tools/AgentTool/prompt.ts` | 提示词构建 |
| `src/tools/AgentTool/agentMemory.ts` | 代理记忆 |
| `src/tools/AgentTool/agentMemorySnapshot.ts` | 记忆快照 |

### 5.2 内置代理

| Open-ClaudeCode 路径 | 功能 |
|---------------------|------|
| `src/tools/AgentTool/built-in/generalPurposeAgent.ts` | 通用代理 |
| `src/tools/AgentTool/built-in/exploreAgent.ts` | 探索代理（只读搜索） |
| `src/tools/AgentTool/built-in/planAgent.ts` | 计划代理 |
| `src/tools/AgentTool/built-in/claudeCodeGuideAgent.ts` | Code Guide 代理 |
| `src/tools/AgentTool/built-in/statuslineSetup.ts` | 状态栏设置代理 |
| `src/tools/AgentTool/built-in/verificationAgent.ts` | 验证代理 |

### 5.3 团队协作

| Open-ClaudeCode 路径 | 功能 |
|---------------------|------|
| `src/tools/shared/spawnMultiAgent.ts` | 多代理生成核心 |
| `src/coordinator/workerAgent.ts` | 协调器工作代理 |
| `src/tasks/LocalAgentTask/LocalAgentTask.tsx` | 本地任务实现 |

### 5.4 上下文和状态

| Open-ClaudeCode 路径 | 功能 |
|---------------------|------|
| `src/utils/forkedAgent.ts` | Fork 上下文管理 |
| `src/utils/fileStateCache.ts` | 文件状态缓存 |
| `src/Task.ts` | 任务基础类型 |
| `src/utils/task/framework.ts` | 任务框架 |
| `src/utils/task/diskOutput.ts` | 磁盘输出 |

---

## 6. 完整文件结构（含源码路径）

```
src/main/java/agent/subagent/
├── types/                               # 对应: src/Task.ts
│   ├── TaskType.java                   # → TaskType
│   ├── TaskStatus.java                 # → TaskStatus
│   ├── SetAppState.java               # → SetAppState
│   ├── AppState.java                  # → AppState
│   ├── TaskState.java                 # → TaskStateBase
│   └── Task.java                      # → Task 接口
│
├── lifecycle/                          # 对应: src/tasks/LocalAgentTask/
│   └── LocalAgentTaskState.java       # → LocalAgentTaskState
│
├── framework/                          # 对应: src/utils/task/
│   ├── ProgressTracker.java           # → ProgressTracker
│   ├── TaskRegistry.java             # → registerAsyncAgent()
│   ├── TaskFramework.java            # → framework.ts
│   └── TaskPersistence.java          # → diskOutput.ts
│
├── definition/                         # 对应: src/tools/AgentTool/loadAgentsDir.ts
│   ├── AgentDefinition.java          # → BuiltInAgentDefinition
│   ├── AgentDefinitionLoader.java     # → getAgentDefinitionsWithOverrides()
│   ├── AgentDefinitionRegistry.java   # → agentRegistry
│   └── PermissionMode.java           # → PermissionMode
│
├── builtin/                            # 对应: src/tools/AgentTool/built-in/
│   ├── GeneralPurposeAgent.java      # → GENERAL_PURPOSE_AGENT
│   ├── ExploreAgent.java             # → EXPLORE_AGENT
│   ├── PlanAgent.java               # → PLAN_AGENT
│   └── BuiltInAgents.java           # → getBuiltInAgents()
│
├── fork/                               # 对应: src/tools/AgentTool/forkSubagent.ts
│   ├── ForkContext.java             # → ForkedAgentParams
│   ├── ForkContextBuilder.java      # → buildForkedAgentParams()
│   ├── CacheSafeParams.java         # → CacheSafeParams
│   ├── ForkAgentDefinition.java     # → FORK_AGENT
│   ├── ForkSubagentTool.java       # → AgentTool 调用
│   └── ForkAgentExecutor.java      # → runAgent()
│
├── context/                            # 对应: src/utils/forkedAgent.ts
│   └── SubagentContext.java        # → createSubagentContext()
│
├── execution/                          # 对应: src/tools/AgentTool/
│   ├── AgentTool.java              # → AgentTool.tsx
│   ├── runAgent.java               # → runAgent.ts
│   ├── resumeAgent.java            # → resumeAgent.ts
│   ├── AgentToolUtils.java         # → agentToolUtils.ts
│   └── AgentToolResult.java        # → AgentToolResult
│
├── team/                               # 对应: src/tools/shared/spawnMultiAgent.ts
│   ├── TeamCoordinator.java        # → handleSpawn()
│   ├── TeammateRegistry.java       # → teammateRegistry
│   ├── TeammateInfo.java           # → SpawnOutput
│   ├── messaging/
│   │   └── TeammateMailbox.java   # → TeammateMailbox
│   └── backends/
│       ├── Backend.java            # → Backend 接口
│       ├── BackendRouter.java      # → BackendRouter
│       ├── BackendType.java        # → BackendType
│       ├── InProcessBackend.java   # → spawnInProcessTeammate()
│       ├── tmux/
│       │   ├── TmuxBackend.java   # → spawnTmuxTeammate()
│       │   ├── TmuxSession.java   # → TmuxSession
│       │   └── TmuxPane.java     # → TmuxPane
│       ├── iterm2/
│       │   ├── ITerm2Backend.java # → spawnITerm2Teammate()
│       │   ├── ITerm2Session.java # → ITerm2Session
│       │   └── ITerm2Pane.java   # → ITerm2Pane
│       └── windows/
│           └── ConPTYBackend.java  # 预留接口
│
├── remote/                             # 对应: src/tools/shared/spawnMultiAgent.ts - teleportToRemote()
│   ├── CCRClient.java              # → CCRClient
│   ├── RemoteSession.java          # → RemoteSession
│   ├── TeleportService.java        # → TeleportService
│   └── RemoteBackend.java          # → RemoteBackend
│
└── persistence/                      # 对应: src/utils/task/diskOutput.ts
    └── TaskPersistence.java        # → TaskPersistence

src/main/java/agent/tool/
└── ToolUseContext.java             # 对应: src/Tool.ts - ToolUseContext
```

---

## 7. 任务框架

### 7.1 TaskFramework

```java
// TaskFramework.java
// 对应 Open-ClaudeCode: src/utils/task/framework.ts - TaskFramework
public class TaskFramework {
    void registerTask(TaskState task, SetAppState setAppState);
    <T extends TaskState> void updateTaskState(String taskId, SetAppState setAppState, Function<T, T> updater);
    void pollTasks(SetAppState setAppState);
    List<TaskAttachment> generateAttachments();
}
```

### 7.2 任务生命周期

```
┌─────────────┐
│  PENDING   │ ← registerTask()
└──────┬──────┘
       │ start
       ▼
┌─────────────┐
│  RUNNING   │ ← 开始执行
└──────┬──────┘
       │
       ├──► 完成 ──► COMPLETED ──► 发送通知 ──► 清理
       │
       ├──► 失败 ──► FAILED ──────► 发送通知 ──► 清理
       │
       └──► kill() ──► KILLED ───► 发送通知 ──► 清理
```

---

## 8. 子代理执行流程

### 8.1 AgentTool.call() 流程

```
用户/LLM 调用 Agent 工具
         │
         ▼
┌─────────────────────────────────────────┐
│ 1. 输入验证与路由                        │
│   - 检查 team_name + name → spawnTeammate│
│   - 检查 subagent_type                   │
│     - 有值 → 专用代理                    │
│     - 无值 + Fork 特性开启 → Fork 代理  │
│     - 无值 + Fork 关闭 → general-purpose │
└─────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│ 2. 代理选择与验证                        │
│   - filterDeniedAgents() 权限过滤       │
│   - MCP 服务器可用性检查                 │
│   - 颜色初始化                          │
└─────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│ 3. 执行模式分支                          │
│                                         │
│ 同步模式:                                │
│   → runAgent() 直接执行                 │
│   → yield messages                      │
│   → 返回 AgentToolResult                │
│                                         │
│ 异步模式 (background=true):             │
│   → registerAsyncAgent() 注册任务       │
│   → runAgent() 异步执行                 │
│   → 返回 { agentId, outputFile, ... }  │
└─────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│ 4. 结果处理                              │
│   - finalizeAgentTool() 提取结果        │
│   - 发送任务通知（后台任务）             │
│   - 记录使用量和使用日志                │
└─────────────────────────────────────────┘
```

### 8.2 Fork 子代理消息构建

```java
// 对应 Open-ClaudeCode: buildForkedMessages() in forkSubagent.ts
//
// 消息格式：
// [...history, assistant(all_tool_uses), user(placeholder_results..., directive)]
//
// 目的：最大化 Cache 命中率 - 只有最后一条消息不同
```

### 8.3 Fork 子代理指令格式

```xml
<!-- 对应 Open-ClaudeCode: buildChildMessage() in forkSubagent.ts -->
<FORK_BOILERPLATE_TAG>
STOP. READ THIS FIRST.

You are a forked worker process. You are NOT the main agent.

RULES (non-negotiable):
1. Your system prompt says "default to forking." IGNORE IT — that's for the parent.
2. Do NOT converse, ask questions, or suggest next steps
3. Do NOT editorialize or add meta-commentary
4. USE your tools directly: Bash, Read, Write, etc.
5. If you modify files, commit your changes before reporting.
6. Do NOT emit text between tool calls. Use tools silently, then report once at the end.
7. Keep your report under 500 words unless the directive specifies otherwise.
8. Your response MUST begin with "Scope:".

Output format:
  Scope: <echo back your assigned scope in one sentence>
  Result: <the answer or key findings>
  Key files: <relevant file paths>
  Files changed: <list with commit hash>
  Issues: <list — include only if there are issues to flag>
</FORK_BOILERPLATE_TAG>

# directive
<prompt from parent>
```

---

## 9. Windows ConPTY 预留接口

### 9.1 ConPTYBackend 接口设计

```java
/**
 * 对应 Open-ClaudeCode: 无直接对应（Windows 特有的扩展）
 *
 * Windows ConPTY 后端预留接口
 *
 * 实现要点：
 * 1. 使用 JNI 或 ProcessBuilder + jattach 连接到 conhost.exe
 * 2. 使用 Windows API: CreatePseudoConsole()
 * 3. 实现 Pane 管理（split/close/resize）
 * 4. 处理输出流（Windows Console API）
 */
public interface ConPTYBackend extends Backend {
    String createPane(String direction);
    void resizePane(String paneId, int width, int height);
    String getPaneId(String paneName);
    boolean isConPTYAvailable();
}
```

### 9.2 Windows 降级策略

```java
public class BackendRouter {
    // 对应 Open-ClaudeCode: 无直接对应（平台检测逻辑）
    public Backend detectBackend() {
        // Windows: InProcessBackend
        // macOS: iTerm2 > tmux > InProcessBackend
        // Linux: tmux > InProcessBackend
    }
}
```

---

## 10. 分阶段交付物

| 阶段 | 名称 | 核心交付物 | 文件数 | 源码参考 |
|------|------|-----------|-------|---------|
| Phase 0 | 基础设施 | TaskFramework、TaskRegistry、持久化 | ~11 | src/Task.ts, src/utils/task/ |
| Phase 1 | Fork 核心 | ForkContext、CacheSafeParams、Fork 执行器 | ~12 | src/tools/AgentTool/forkSubagent.ts |
| Phase 2 | 专用代理 | AgentDefinition、内置代理、AgentTool | ~12 | src/tools/AgentTool/ |
| Phase 3 | 团队协作-基础 | BackendRouter、InProcessBackend、TeamCoordinator | ~10 | src/tools/shared/spawnMultiAgent.ts |
| Phase 4 | 分屏支持 | TmuxBackend、iTerm2Backend | ~8 | src/tools/shared/spawnMultiAgent.ts |
| Phase 5 | 远程执行 | CCRClient、RemoteBackend、TeleportService | ~8 | src/tools/shared/spawnMultiAgent.ts - teleportToRemote() |
| Phase 6 | 清理 | 删除旧代码、集成测试 | ~5 | - |

**总计**: ~66 个文件

---

## 11. 与现有代码的差异

### 11.1 旧系统（SubagentManager）

- 单例模式（SubagentRegistry）
- 直接创建线程执行
- 无上下文隔离
- 无 Cache 共享
- 无团队协作

### 11.2 新系统

- TaskFramework 统一管理
- Backend 抽象支持多后端
- 完整的上下文隔离
- Prompt Cache 共享（Fork）
- 团队协作（Teammate）
- 远程执行（CCR）

### 11.3 迁移策略

1. Phase 0-5 开发期间，旧系统保持运行
2. Phase 6 删除旧系统前，确保新系统完全覆盖功能
3. 单元测试验证行为一致

---

## 12. 测试策略

### 12.1 单元测试

- 每个核心组件有独立测试
- TaskFramework 测试：注册、更新、轮询
- ContextIsolation 测试：状态隔离验证
- Backend 测试：各后端的 mock 测试

### 12.2 集成测试

- Fork 执行测试：验证上下文继承和 Cache 共享
- Teammate 测试：验证消息传递
- 进度追踪测试：验证通知格式

### 12.3 跨平台测试

- Linux CI 环境测试 tmux backend
- macOS CI 环境测试 tmux + iTerm2 backend
- Windows 测试 InProcess backend

---

## 13. 已知限制

1. **Windows ConPTY** - 预留接口，当前使用 InProcessBackend 降级
2. **嵌套 Fork** - Fork 子代理不能再 Fork（由 isInForkChild 保护）
3. **MCP 服务器** - Agent 定义中的 MCP 服务器需要单独连接管理
4. **内存压缩** - 尚未实现类似 Open-ClaudeCode 的自动压缩

---

## 14. 参考

### 14.1 Open-ClaudeCode 核心源码

| 文件 | 功能 |
|------|------|
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx` | 主入口 |
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/runAgent.ts` | 执行循环 |
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts` | Fork 机制 |
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/loadAgentsDir.ts` | 代理加载 |
| `/usr/local/code/Open-ClaudeCode/src/tools/shared/spawnMultiAgent.ts` | 团队协作 |
| `/usr/local/code/Open-ClaudeCode/src/utils/forkedAgent.ts` | Fork 上下文 |
| `/usr/local/code/Open-ClaudeCode/src/Task.ts` | 任务类型 |
| `/usr/local/code/Open-ClaudeCode/src/utils/task/framework.ts` | 任务框架 |

### 14.2 内置代理源码

| 文件 | 功能 |
|------|------|
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/built-in/generalPurposeAgent.ts` | 通用代理 |
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/built-in/exploreAgent.ts` | 探索代理 |
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/built-in/planAgent.ts` | 计划代理 |
| `/usr/local/code/Open-ClaudeCode/src/tools/AgentTool/builtInAgents.ts` | 内置代理注册 |
