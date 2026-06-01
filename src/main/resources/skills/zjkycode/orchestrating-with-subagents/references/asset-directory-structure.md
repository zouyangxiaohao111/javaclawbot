# Asset Directory Structure

主 agent 和 subagent 之间的"消息总线"是**文件系统**。所有状态都持久化在 3 个目录。

## 目录结构

```
project/
├── plan/                           # 主 agent 维护
│   ├── task_plan.md                # 阶段+任务+状态（多 tab 共享）
│   ├── decisions.md                # 关键决策日志
│   └── contracts/                  # subagent 间的接口契约
│       ├── task-1-to-task-2.md
│       └── task-2-to-task-3.md
├── findings/                       # subagent 写，主 agent 读
│   ├── research-1.md
│   ├── impl-task-1.md
│   ├── code-review-1.md
│   └── impl-task-2-failed.md
├── progress/                       # 子任务执行日志
│   ├── task-1-progress.md
│   └── task-2-progress.md
└── docs/zjkycode/
    ├── specs/                      # 设计文档
    └── plans/                      # 实施计划
```

## task_plan.md 模板

```markdown
# Task Plan

## Goal
[一句话最终目标]

## Current Phase
Phase N: [阶段名]

## Phases

### Phase 1: [阶段名] (status: complete)
- [x] 任务 1.1
- [x] 任务 1.2

### Phase 2: [阶段名] (status: in_progress)
- [ ] 任务 2.1
- [ ] 任务 2.2

### Phase 3: [阶段名] (status: pending)
- [ ] 任务 3.1

## Key Decisions
- 决策 1: [决定] | 理由: [为什么] | 日期: [YYYY-MM-DD]
- 决策 2: [决定] | 理由: [为什么] | 日期: [YYYY-MM-DD]

## Errors Encountered
- 错误 1: [描述] | 尝试次数: N | 解决方案: [方法]

## Session Handoff
- 当前进度: Phase 2
- 下次开会读: plan/ + progress/ + findings/ 相关文件
- 上次开会: [YYYY-MM-DD]
```

## findings/<task>.md 模板

```markdown
# Findings: [任务 ID]

## 完成内容
[简要描述]

## 关键决策
- 决策 1: [决定] | 理由
- 决策 2: [决定] | 理由

## 修改的文件
- `path/to/file1.java` — 修改内容
- `path/to/file2.md` — 修改内容

## 关键代码片段
[重要的代码片段]

## 验证证据
- `mvn compile` 通过
- `mvn test -Dtest=X` 通过
- 其他证据

## 疑虑（如果有）
- 疑虑 1: [描述] | 影响: [评估] | 建议: [解决]
```

## progress/<task>.md 模板

```markdown
# Progress: [任务 ID]

## [YYYY-MM-DD HH:MM] 开始
**任务**: [任务描述]
**Context**: [context packet 摘要]

## [YYYY-MM-DD HH:MM] 步骤
**动作**: [做了什么]
**结果**: [成功/失败，附数据]

## [YYYY-MM-DD HH:MM] 错误
**错误**: [描述]
**尝试 1**: [方案] → [结果]
**尝试 2**: [方案] → [结果]
**解决**: [最终方案]

## [YYYY-MM-DD HH:MM] 完成
**状态**: DONE / DONE_WITH_CONCERNS / FAILED
**总结**: [一句话]
```

## plan/decisions.md 模板

```markdown
# Decisions Log

## 决策 1: [决定]
- **日期**: [YYYY-MM-DD]
- **原因**: [为什么这么决定]
- **替代方案**: [考虑过的其他方案]
- **影响**: [这个决定影响了什么]

## 决策 2: [决定]
...
```

## plan/contracts/<from>-to-<to>.md 模板

```markdown
# Contract: [任务 from] → [任务 to]

## 接口定义
```java
// Java 接口示例
public interface [InterfaceName] {
    [方法签名]
}
```

## 数据契约
- 输入: [类型 + 格式]
- 输出: [类型 + 格式]

## 错误处理
- 异常: [列出]
- 重试: [策略]
```

## 目录创建时机

| 目录 | 创建时机 | 由谁创建 |
|---|---|---|
| `plan/` | brainstorming 后 | 主 agent |
| `findings/` | 派第一个 subagent 前 | 主 agent |
| `progress/` | 派第一个 subagent 前 | 主 agent |
| `plan/contracts/` | 写 writing-plans 时 | 主 agent |

## 维护规则

- **主 agent** 维护：`plan/`（所有）
- **subagent** 写：`findings/`、`progress/`（按 context packet）
- **永不**：subagent 写 plan/、主 agent 写 findings/
