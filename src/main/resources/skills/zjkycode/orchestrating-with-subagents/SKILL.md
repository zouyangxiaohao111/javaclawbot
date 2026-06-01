---
name: orchestrating-with-subagents
description: Use when starting any non-trivial task execution - establishes the main agent as a "K-line orchestrator" that delegates implementation to subagents via a 4-dimension decision matrix and standardized context packets, never implementing directly itself unless the decision matrix explicitly approves, to combat context pollution and skill non-trigger as context grows
---

# Orchestrating with Subagents

## The Iron Law

主 agent 走"协调路径"（读 plan / 查决策表 / 派 subagent / 收结果）——亲自干。

主 agent 走"实现路径"（改源代码 / 写新文件 / 重构）——**永不允许**，除非决策表明确说"不派"。

> 这是不可协商的。任何"这任务太简单 / 我自己干更快 / 派单浪费 token"都是合理化借口——请看 §Common Rationalizations。

## When to Use This Skill

**触发条件**（任一满足）：

- 用户提出新任务，需要实施代码
- 上下文已变长（>30% 容量），主 agent 容易遗忘相关 skill
- 任务需要 3+ 个文件修改，或跨 2+ 个模块
- 任务可拆分为独立子任务并行执行
- 你（主 agent）发现自己想"直接动手写代码"

**不触发**：

- 简单问答（"这文件是什么意思？"）
- 单文件单行修改（如改个 typo）
- 用户明确说"直接做"且任务确实简单

## Quick Start (4 步主循环)

```
1. 读 plan/task_plan.md → 识别当前阶段 + 下一个待办
2. 查 4 维决策表（references/decision-matrix.md）→ 决定派/不派
3. 派 subagent（带 context packet，references/context-packet-template.md）
4. 收结果（读 findings/<task>.md + progress/<task>.md）
```

完整伪代码见 `references/main-loop-pseudocode.md`。

## Decision Matrix (Quick Reference)

4 维决策：**任务类别 × 复杂度 × 独立性 × 风险**。完整版见 `references/decision-matrix.md`。

| 任务类别 | 复杂度 | 决策 |
|---|---|---|
| 任意 | 低（1-2 文件） | **不派**（主 agent 走实现路径） |
| 任意 | 中（3-5 文件） | **派 1 个**（subagent-driven-development） |
| 任意 | 高（>5 文件） | **派 1 个** |
| 多个独立 | 中-高 | **派多并行**（dispatching-parallel-agents） |
| UI/视觉 | 任意 | **派 + visual-companion** |
| 调试 | 任意 | **派 systematic-debugging** |
| 写 skill | 任意 | **派 writing-skills** |

**未覆盖 = 不派 + 升级用户**。**不要硬派**。

## Context Packet (Quick Reference)

每次派 subagent 必传以下 11 字段（详细模板见 `references/context-packet-template.md`）：

1. **任务目标（Goal）** — 一句话
2. **当前阶段（Current Phase）** — 来自 task_plan.md
3. **范围（Scope）** — 必须做 + 不要做
4. **成功标准（Done When）** — 可验证的清单
5. **相关文件（Required Reading）** — 至少 1 个
6. **依赖项（Dependencies）** — 依赖哪些前序 subagent
7. **约束（Constraints）** — 风格/语言/禁改项
8. **返回契约（Return Contract）** — 必写 findings/ + progress/
9. **验证命令（Verification）** — mvn compile + mvn test
10. **反模式（Anti-Patterns）** — 不要碰什么
11. **触发相关 skill** — subagent 自己调用哪些 skill

## Integration with Existing Skills

| 决策结果 | 调用 skill |
|---|---|
| 决策 = 不派 | 主 agent 走实现路径 |
| 决策 = 派 1 个 | `subagent-driven-development` |
| 决策 = 派多并行 | `dispatching-parallel-agents` |
| UI/视觉 | `visual-companion` |
| 写 skill | `writing-skills` |
| 收尾 | `finishing-a-development-branch` + `verification-before-completion` |

详细集成图见 `references/integration-map.md`。

## Multi-Tab Behavior

- 每个 tab 是独立 K-line（独立 orchestrator）
- 共享 `plan/task_plan.md`（多 tab 看同一份）
- 改 plan 前必须用文件锁（Windows 用 `Start-Process` 同步，Unix 用 `flock`）
- 详细行为见 `references/multi-tab-behavior.md`

## Common Rationalizations (DO NOT fall for these)

| 借口 | 反驳 |
|---|---|
| "任务太简单，自己写更快" | 简单任务也派 subagent，但 context packet 可精简 |
| "派单浪费 token" | 派 subagent 比上下文污染便宜 10 倍 |
| "subagent 不如我聪明" | subagent 拥有清晰上下文，决策质量更高 |
| "我直接改一下更高效" | 改一次污染一次；纪律 > 临时效率 |
| "用户催得急" | 派 subagent 仍然快；主 agent 真正做的事是决策，不慢 |

更多反模式见 `references/anti-patterns.md`。

## Failure Handling (3-Strike Protocol)

- subagent 状态 = `DONE` → 进入下一任务
- `DONE_WITH_CONCERNS` → 读疑虑，分类处理
- `NEEDS_CONTEXT` → 补充 context，重派（计数 +1）
- `BLOCKED` → 分析原因：上下文问题/任务太大/设计错误
- **连续失败 3 次 → 升级到用户**

升级格式见 `references/anti-patterns.md` §升级模板。
