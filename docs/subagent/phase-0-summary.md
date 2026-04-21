# Phase 0: 基础设施 完成总结

## 交付物

### 类型定义 (`src/main/java/agent/subagent/types/`)
| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `TaskType.java` | `src/Task.ts` - TaskType | 任务类型枚举（LOCAL_AGENT, REMOTE_AGENT, IN_PROCESS_TEAMMATE, LOCAL_WORKFLOW） |
| `TaskStatus.java` | `src/Task.ts` - TaskStatus | 任务状态枚举（PENDING, RUNNING, COMPLETED, FAILED, KILLED） |
| `SetAppState.java` | `src/Task.ts` - SetAppState | 状态更新函数接口 |
| `AppState.java` | `src/state/AppState.ts` | 应用状态类 |
| `TaskState.java` | `src/Task.ts` - TaskStateBase | 任务状态基类 |
| `Task.java` | `src/Task.ts` - Task 接口 | Task 接口 |

### 生命周期 (`src/main/java/agent/subagent/lifecycle/`)
| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `LocalAgentTaskState.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - LocalAgentTaskState | 本地代理任务状态实现 |

### 框架 (`src/main/java/agent/subagent/framework/`)
| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `ProgressTracker.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - ProgressTracker | 进度追踪器 |
| `TaskRegistry.java` | `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - registerAsyncAgent() | 任务注册表（单例） |
| `TaskFramework.java` | `src/utils/task/framework.ts` - TaskFramework | 任务框架核心 |
| `TaskPersistence.java` | `src/utils/task/diskOutput.ts` | 任务持久化 |

## 关键设计决策

1. **单例 Registry** - TaskRegistry 使用单例模式，与 Open-ClaudeCode 的设计一致
2. **观察者模式** - 通过 TaskObserver 接口支持任务事件监听
3. **ReadWriteLock** - TaskPersistence 使用读写锁提高并发性能
4. **线程池调度** - TaskFramework 使用 ScheduledExecutorService 进行轮询和清理
5. **消息类型** - 使用 `Map<String, Object>` 替代专门的 Message 类，与 javaclawbot 现有模式一致

## 核心类图

```
TaskFramework
    │
    ├──► TaskRegistry (单例)
    │         │
    │         ├──► register()
    │         ├──► markStarted/Completed/Failed/Killed()
    │         └──► cleanupCompleted()
    │
    └──► TaskPersistence
              │
              └──► saveAll/loadAll()

LocalAgentTaskState (extends TaskState)
    │
    ├──► ProgressTracker
    └──► AtomicBoolean (abortSignal)
```

## 如何继续

Phase 1（Fork 核心）依赖此阶段的内容：

1. `TaskState` 基类 - 所有任务状态都继承它
2. `TaskRegistry` - Fork 执行器需要注册任务
3. `ProgressTracker` - Fork 执行器需要追踪进度

## 下一阶段

**Phase 1: Fork 核心**

| Java 类 | Open-ClaudeCode 源码 | 说明 |
|---------|---------------------|------|
| `fork/ForkContext.java` | `src/utils/forkedAgent.ts` - ForkedAgentParams | Fork 上下文 |
| `fork/CacheSafeParams.java` | `src/utils/forkedAgent.ts` - CacheSafeParams | Cache 共享参数 |
| `fork/ForkAgentDefinition.java` | `src/tools/AgentTool/forkSubagent.ts` - FORK_AGENT | Fork 代理定义 |
| `fork/ForkSubagentTool.java` | `src/tools/AgentTool/AgentTool.tsx` | Fork 入口 Tool |
| `fork/ForkAgentExecutor.java` | `src/tools/AgentTool/runAgent.ts` - runAgent() | Fork 执行器 |
| `context/SubagentContext.java` | `src/utils/forkedAgent.ts` - createSubagentContext() | 子代理上下文隔离 |
| `definition/AgentDefinition.java` | `src/tools/AgentTool/loadAgentsDir.ts` - BuiltInAgentDefinition | 代理定义基类 |
| `definition/PermissionMode.java` | `src/utils/permissions/PermissionMode.ts` | 权限模式 |

## 已知限制

1. **当前 TaskFramework 的轮询是简单超时检测**，尚未实现真正的任务输出检测
2. **尚未实现与消息总线的集成**
3. **尚未实现任务通知的 XML 格式生成**
4. **TaskPersistence 的 JSON 序列化需要确保子类正确注册**

## Git 提交历史

```
957fe0d feat(subagent): add type definitions for task framework
49ef725 feat(subagent): add LocalAgentTaskState
b841640 feat(subagent): add ProgressTracker
a6938f9 feat(subagent): add TaskRegistry
522664d feat(subagent): add TaskFramework core
762b2cd feat(subagent): add TaskPersistence
5fe2d84 docs: add Phase 0 summary
```

## 文件结构

```
src/main/java/agent/subagent/
├── types/
│   ├── TaskType.java
│   ├── TaskStatus.java
│   ├── SetAppState.java
│   ├── AppState.java
│   ├── TaskState.java
│   └── Task.java
├── framework/
│   ├── ProgressTracker.java
│   ├── TaskRegistry.java
│   ├── TaskFramework.java
│   └── TaskPersistence.java
└── lifecycle/
    └── LocalAgentTaskState.java
```
