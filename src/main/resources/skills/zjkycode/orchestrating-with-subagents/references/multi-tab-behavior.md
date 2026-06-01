# Multi-Tab Behavior

多个 Claude Code / AI 会话同时编辑同一个项目时的协调机制。

## 核心规则

- **每个 tab 独立 K-line**：每个 tab 是独立 orchestrator 实例
- **共享 plan 目录**：所有 tab 看到同一份 `plan/task_plan.md`
- **文件锁同步**：主 agent 用 OS 级文件锁防止同时修改 task_plan.md
- **决策独立**：每个 tab 独立做决策

## 行为规则

### 读 plan
- 无需锁（多 tab 同时读 OK）
- 主 agent 任何时候都可以读 plan/task_plan.md

### 写 plan
- **必须**用文件锁
- 锁的粒度：单个文件（task_plan.md 一个锁）
- 锁的实现：操作系统级 flock（Unix）或 PowerShell 同步原语（Windows）

### 写 findings/ 和 progress/
- 无需锁（多 subagent 写不同文件）
- 命名约定：`<task-id>.md`（task-id 唯一，避免冲突）

### 派 subagent
- 无需锁
- 但 subagent 写 plan/contracts/ 时需要锁（如果该契约文件存在）

## Windows 下的文件锁实现

PowerShell 同步原语：

```powershell
# 锁文件
$lockFile = "plan/task_plan.md.lock"
$mutex = New-Object System.Threading.Mutex($false, "Global\TaskPlanMutex")

try {
    $mutex.WaitOne() | Out-Null
    # 读 plan/task_plan.md
    # 修改
    # 写回
} finally {
    $mutex.ReleaseMutex()
}
```

**注意**：Windows 的 `New-Object Mutex` 是跨进程的，多 tab 可用。

## Unix 下的文件锁实现

```bash
# 锁文件
flock plan/task_plan.md.lock -c "
    # 读 plan/task_plan.md
    # 修改
    # 写回
"

# 锁失败处理
flock -w 5 plan/task_plan.md.lock -c "..." || {
    echo "其他 tab 正在编辑此 plan，请稍后重试"
    sleep 5
    flock -w 5 plan/task_plan.md.lock -c "..." || {
        echo "无法获取锁，暂停"
        # 升级到用户
    }
}
```

## 冲突检测

每个 tab 启动时（SessionStart hook）：

1. 读 `plan/decisions.md` 找最近的"last modified by [tab-id]"
2. 如果其他 tab 在 5 分钟内修改过 plan，发出警告
3. 询问用户："检测到其他 tab 正在编辑此 plan，是否继续？"

## 跨 tab 通信

通过 plan/decisions.md：

```markdown
# Decisions Log

## 决策 1: 派 subagent X
- **日期**: 2026-06-01 14:30
- **Tab**: tab-A
- **原因**: 实现功能 Y
- **影响**: 占用 plan/task_plan.md 任务 2.1

## 决策 2: 切换任务
- **日期**: 2026-06-01 14:35
- **Tab**: tab-B
- **原因**: 用户在 tab-B 临时改需求
- **影响**: 任务 2.1 被替换为 2.3
```

## 跨 tab 失败的场景

### 场景：两个 tab 同时派 subagent
- tab-A 派 subagent 实施任务 2.1
- tab-B 派 subagent 实施任务 2.1
- 两个 subagent 都修改同一文件 → 冲突

**缓解**：
- 派 subagent 前，主 agent 查 `plan/decisions.md` 最近 5 分钟的"派单"记录
- 如果发现任务已被其他 tab 派，跳过或排队

### 场景：两个 tab 同时改 plan
- tab-A 读 plan，识别 Phase 2
- tab-B 读 plan，识别 Phase 2（同一份）
- tab-A 标记任务 2.1 完成，写回 plan
- tab-B 标记任务 2.1 完成（基于旧的读），写回 plan
- 结果：任务 2.1 完成两次（无害但日志混乱）

**缓解**：
- 写 plan 用文件锁
- 锁内完整读-改-写

## 单 tab 时的简化

如果只有一个 tab 在工作，文件锁不会真的冲突。但**仍然建议用锁**——保持代码习惯一致，未来加 tab 时无需改。
