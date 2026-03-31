---
name: using-zjkycode
description: Use when starting any conversation - establishes how to find and use skills, requiring Skill tool invocation before ANY response including clarifying questions
---

<SUBAGENT-STOP>
If you were dispatched as a subagent to execute a specific task, skip this skill.
</SUBAGENT-STOP>

<EXTREMELY-IMPORTANT>
If you think there is even a 1% chance a skill might apply to what you are doing, you ABSOLUTELY MUST invoke the skill.

IF A SKILL APPLIES TO YOUR TASK, YOU DO NOT HAVE A CHOICE. YOU MUST USE IT.

This is not negotiable. This is not optional. You cannot rationalize your way out of this.
</EXTREMELY-IMPORTANT>

## 指令优先级

zjkycode skills 覆盖默认系统提示行为，但**用户指令始终优先**：

1. **用户明确指令**（CLAUDE.md、GEMINI.md、AGENTS.md、CODE_AGENT.md、直接请求）— 最高优先级
2. **zjkycode skills** — 在冲突处覆盖默认系统行为
3. **默认系统提示** — 最低优先级

如果 CLAUDE.md、GEMINI.md 、CODE_AGENT.md 或 AGENTS.md 说"不要使用 TDD"，而某个 skill 说"始终使用 TDD"，请遵循用户指令。用户拥有控制权。

## 如何访问 Skills

**在 Claude Code 中：** 使用 `Skill` 工具。当你调用 skill 时，其内容会被加载并呈现给你 — 直接遵循它。永远不要使用 Read 工具读取 skill 文件。

**在 javaclawbot 中：** 使用 `Skill` 工具。当你调用 skill 时，其内容会被加载并呈现给你 — 直接遵循它。永远不要使用 Read 工具读取 skill 文件。

# 使用 Skills

## 规则

**在任何响应或操作之前调用相关或请求的 skills。** 即使只有 1% 的可能性某个 skill 适用，你也应该调用该 skill 进行检查。如果调用的 skill 最终不适用于当前情况，你不需要使用它。

```dot
digraph skill_flow {
    "User message received" [shape=doublecircle];
    "About to EnterPlanMode?" [shape=doublecircle];
    "Already brainstormed?" [shape=diamond];
    "Invoke brainstorming skill" [shape=box];
    "Might any skill apply?" [shape=diamond];
    "Invoke Skill tool" [shape=box];
    "Announce: 'Using [skill] to [purpose]'" [shape=box];
    "Has checklist?" [shape=diamond];
    "Create TodoWrite todo per item" [shape=box];
    "Follow skill exactly" [shape=box];
    "Respond (including clarifications)" [shape=doublecircle];

    "About to EnterPlanMode?" -> "Already brainstormed?";
    "Already brainstormed?" -> "Invoke brainstorming skill" [label="no"];
    "Already brainstormed?" -> "Might any skill apply?" [label="yes"];
    "Invoke brainstorming skill" -> "Might any skill apply?";

    "User message received" -> "Might any skill apply?";
    "Might any skill apply?" -> "Invoke Skill tool" [label="yes, even 1%"];
    "Might any skill apply?" -> "Respond (including clarifications)" [label="definitely not"];
    "Invoke Skill tool" -> "Announce: 'Using [skill] to [purpose]'";
    "Announce: 'Using [skill] to [purpose]'" -> "Has checklist?";
    "Has checklist?" -> "Create TodoWrite todo per item" [label="yes"];
    "Has checklist?" -> "Follow skill exactly" [label="no"];
    "Create TodoWrite todo per item" -> "Follow skill exactly";
}
```

## 危险信号

这些想法意味着停止 — 你在找借口：

| 想法 | 现实 |
|------|------|
| "这只是个简单的问题" | 问题也是任务。检查 skills。 |
| "我需要先了解更多上下文" | Skill 检查在澄清问题之前进行。 |
| "让我先探索代码库" | Skills 告诉你如何探索。先检查。 |
| "我可以快速检查 git/文件" | 文件缺少对话上下文。检查 skills。 |
| "让我先收集信息" | Skills 告诉你如何收集信息。 |
| "这不需要正式的 skill" | 如果存在 skill，就使用它。 |
| "我记得这个 skill" | Skills 会演变。阅读当前版本。 |
| "这不算是一个任务" | 行动 = 任务。检查 skills。 |
| "这个 skill 太过了" | 简单的事情会变复杂。使用它。 |
| "我就先做这一件事" | 在做任何事之前检查。 |
| "这感觉很高效" | 无纪律的行动浪费时间。Skills 防止这种情况。 |
| "我知道那是什么意思" | 了解概念 ≠ 使用 skill。调用它。 |

## Skill 优先级

当多个 skills 可能适用时，使用此顺序：

1. **流程 skills 优先**（brainstorming、debugging）— 这些决定如何处理任务
2. **实现 skills 其次**（frontend-design、mcp-builder）— 这些指导执行

"让我们构建 X" → 先 brainstorming，然后实现 skills。
"修复这个 bug" → 先 debugging，然后领域特定 skills。

## Skill 类型

**严格的**（TDD、debugging）：精确遵循。不要偏离规范。

**灵活的**（patterns）：根据上下文调整原则。

Skill 本身会告诉你属于哪种类型。

## 用户指令

指令说的是做什么，而不是怎么做。"添加 X" 或 "修复 Y" 不意味着跳过工作流程。