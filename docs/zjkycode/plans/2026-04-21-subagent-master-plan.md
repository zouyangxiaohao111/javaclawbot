# Subagent 系统实施总计划

> **⚠️ 重要：实施要求**
> 在开始任何阶段的实施之前，必须先阅读：`docs/zjkycode/specs/2026-04-21-subagent-design.md`
>
> **复刻原则**：必须完整按照 Open-ClaudeCode 源码逐行实现，做到 **Java 语义等价**、**逻辑等价**。
> - 不是简化实现，不是参考实现，而是逐行对应实现
> - 每个 Java 类/方法必须标注对应的 TypeScript 源码
> - 禁止跳过认为"不需要"的功能

**目标**: 在 javaclawbot 中复刻 Open-ClaudeCode 的完整子代理系统

**架构**: 基于 TaskFramework 的统一任务管理 + Backend 抽象的多后端支持

**阶段概览**:

| 阶段 | 名称 | 主要源码参考 | 状态 |
|------|------|-------------|------|
| Phase 0 | 基础设施 | `src/Task.ts`, `src/utils/task/` | ✅ 已完成 |
| Phase 1 | Fork 核心 | `src/tools/AgentTool/forkSubagent.ts` | ✅ 已完成 |
| Phase 2 | 专用代理 | `src/tools/AgentTool/`, `src/tools/AgentTool/built-in/` | 🔄 待实施 |
| Phase 3 | 团队协作-基础 | `src/tools/shared/spawnMultiAgent.ts` | ⏳ 待实施 |
| Phase 4 | 分屏支持 | `src/tools/shared/spawnMultiAgent.ts` | ⏳ 待实施 |
| Phase 5 | 远程执行 | `src/tools/shared/spawnMultiAgent.ts` - teleportToRemote() | ⏳ 待实施 |
| Phase 6 | 清理 | - | ⏳ 待实施 |

---

## 复刻原则（必须遵守）

### 核心要求

1. **逐行对应**：每个 Java 类必须对应一个或多个 TypeScript 源文件
2. **语义等价**：Java 实现必须与 TypeScript 原型语义完全一致
3. **逻辑等价**：控制流、状态转换、边界处理必须完全一致
4. **命名对应**：Java 命名风格遵循 TypeScript 原始命名（适当转换为 Java 风格）
5. **禁止简化**：不能因为觉得某个功能"不需要"就跳过

### 实施检查清单

对于每个 Java 类，代码中必须包含：
```java
/**
 * 类名
 * 对应 Open-ClaudeCode: src/path/to/file.ts - TypeScriptSymbolName
 * 功能描述
 */
```

### 禁止事项

- ❌ 跳过认为"复杂"的功能
- ❌ 使用更简单的实现替代
- ❌ 简化逻辑分支
- ❌ 忽略边界情况
- ❌ 创建"类似"的功能而非等价功能

---

## Phase 0-1 状态

✅ **已完成**，详见：
- `docs/subagent/phase-0-summary.md`
- `docs/subagent/phase-1-summary.md`

---

## Phase 2: 专用代理

**计划文件**: `docs/zjkycode/plans/2026-04-21-subagent-phase-2.md`

**必须阅读**: `docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**主要源码**（必须全部实现）:
- `src/tools/AgentTool/loadAgentsDir.ts` - getAgentDefinitionsWithOverrides()
- `src/tools/AgentTool/builtInAgents.ts` - getBuiltInAgents()
- `src/tools/AgentTool/built-in/generalPurposeAgent.ts` - GENERAL_PURPOSE_AGENT
- `src/tools/AgentTool/built-in/exploreAgent.ts` - EXPLORE_AGENT
- `src/tools/AgentTool/built-in/planAgent.ts` - PLAN_AGENT
- `src/tools/AgentTool/AgentTool.tsx` - AgentTool 主入口
- `src/tools/AgentTool/runAgent.ts` - runAgent()

**交付物**: ~12 个文件

**依赖**: Phase 1

---

## Phase 3: 团队协作-基础

**计划文件**: `docs/zjkycode/plans/2026-04-21-subagent-phase-3.md`

**必须阅读**: `docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**主要源码**（必须全部实现）:
- `src/tools/shared/spawnMultiAgent.ts` - handleSpawn()
- `src/tools/shared/spawnMultiAgent.ts` - Backend 接口
- `src/tools/shared/spawnMultiAgent.ts` - BackendRouter
- `src/tools/shared/spawnMultiAgent.ts` - spawnInProcessTeammate()
- `src/tasks/LocalAgentTask/LocalAgentTask.tsx` - teammateRegistry

**交付物**: ~10 个文件

**依赖**: Phase 2

---

## Phase 4: 分屏支持

**计划文件**: `docs/zjkycode/plans/2026-04-21-subagent-phase-4.md`

**必须阅读**: `docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**主要源码**（必须全部实现）:
- `src/tools/shared/spawnMultiAgent.ts` - spawnTmuxTeammate()
- `src/tools/shared/spawnMultiAgent.ts` - spawnITerm2Teammate()
- `src/tools/shared/spawnMultiAgent.ts` - TmuxSession, TmuxPane
- `src/tools/shared/spawnMultiAgent.ts` - ITerm2Session, ITerm2Pane

**交付物**: ~8 个文件

**依赖**: Phase 3

---

## Phase 5: 远程执行

**计划文件**: `docs/zjkycode/plans/2026-04-21-subagent-phase-5.md`

**必须阅读**: `docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**主要源码**（必须全部实现）:
- `src/tools/shared/spawnMultiAgent.ts` - teleportToRemote()
- `src/tools/shared/spawnMultiAgent.ts` - CCRClient
- `src/tools/shared/spawnMultiAgent.ts` - RemoteSession
- `src/tools/shared/spawnMultiAgent.ts` - TeleportService

**交付物**: ~8 个文件

**依赖**: Phase 4

---

## Phase 6: 清理

**计划文件**: `docs/zjkycode/plans/2026-04-21-subagent-phase-6.md`

**必须阅读**: `docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 11 章

**主要任务**:
1. 删除旧的 SubagentManager 相关类
2. 删除旧的 LocalSubagentExecutor
3. 删除旧的 SessionsSpawnTool
4. 删除旧的 SubagentsControlTool
5. 集成测试
6. 更新 ToolRegistry 注册

**交付物**: ~5 个变更

**依赖**: 所有阶段

---

## 实施顺序

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 5 ──► Phase 6
   ✅           ✅         🔄
                        ▼
                   继续 Phase 2?
```

---

## 执行选择

每个阶段都可以选择：
1. **子代理驱动（推荐）** - 为每个任务调度子代理
2. **内联执行** - 在当前会话顺序执行

**建议**: 复杂阶段（Phase 2-5）使用子代理驱动，简单阶段（Phase 0-1, 6）使用内联执行。
