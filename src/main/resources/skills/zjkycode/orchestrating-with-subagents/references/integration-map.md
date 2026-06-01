# Integration Map

本 skill 与 zjkycode 现有 16 个 skill 的协作关系。

## 调用层级

```
Level 0 (基础协议)
└── using-zjkycode          # 必先调用

Level 1 (本 skill)
└── orchestrating-with-subagents  # 高层编排器（本 skill）

Level 2 (执行类 skill)
├── subagent-driven-development   # 派 1 个 subagent
├── dispatching-parallel-agents   # 派多并行
├── writing-plans                 # 写 task_plan.md
└── brainstorming                 # 接收任务时

Level 3 (子代理内部使用)
├── test-driven-development
├── systematic-debugging
├── requesting-code-review
└── verification-before-completion

Level 4 (收尾)
├── receiving-code-review
├── finishing-a-development-branch
└── craft-studio (UI 任务)
```

## 调用流程（典型场景）

### 场景 A：实现新功能

```
[用户] 我要实现 [功能]
  ↓
[using-zjkycode] 调用
  ↓
[brainstorming] 苏格拉底式提问 → 设计方案
  ↓
[writing-plans] 创建 plan/task_plan.md
  ↓
[orchestrating-with-subagents] 4 步主循环
  ├─ 读 plan
  ├─ 查决策表：派 1 个
  ├─ 派 subagent (subagent-driven-development)
  │   └─ subagent 内部用 test-driven-development
  │   └─ 完成后用 requesting-code-review
  └─ 收结果
  ↓
[verification-before-completion] 验证
  ↓
[finishing-a-development-branch] 收尾
```

### 场景 B：修 bug

```
[用户] [bug 描述]
  ↓
[using-zjkycode] 调用
  ↓
[orchestrating-with-subagents]
  ├─ 读 plan
  ├─ 查决策表：派 systematic-debugging
  └─ 收结果
```

### 场景 C：写新 skill

```
[用户] 写一个 [新 skill]
  ↓
[using-zjkycode] 调用
  ↓
[orchestrating-with-subagents]
  ├─ 读 plan
  ├─ 查决策表：派 writing-skills
  └─ 收结果
```

## 详细调用表

| 本 skill 的决策 | 调用的 skill | 调用时机 |
|---|---|---|
| 任意 | `using-zjkycode` | 会话开始时 |
| 决策 = 不派 | 无（主 agent 走实现） | — |
| 决策 = 派 1 个 | `subagent-driven-development` | 每个任务 |
| 决策 = 派多并行 | `dispatching-parallel-agents` | 独立任务场景 |
| 写 plan | `writing-plans` | brainstorming 后 |
| 接任务 | `brainstorming` | 复杂任务 |
| 实施 | `test-driven-development` | subagent 内部 |
| 失败 | `systematic-debugging` | 失败时 |
| 实施完成 | `requesting-code-review` | 实施 subagent 完成后 |
| UI 任务 | `craft-studio` | 涉及 UI/视觉 |
| 收尾 | `verification-before-completion` | 必跑 |
| 收尾 | `finishing-a-development-branch` | 完成后 |
| 收 review 反馈 | `receiving-code-review` | 收到 review |

## 反向调用（本 skill 被谁调用）

本 skill 是**入口 skill**之一，可以在以下情况被直接调用：
- 会话开始时（替代 using-zjkycode 直接进入执行模式）
- brainstorming 后自动调用
- 用户说"开始执行"时

## 优先级（同时适用多个 skill 时）

1. **using-zjkycode** 必最先调用
2. **orchestrating-with-subagents** 在执行阶段必调用
3. **其他 skill** 按上下文触发
