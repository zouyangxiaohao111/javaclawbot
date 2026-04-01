---
name: requesting-code-review
description: 在完成任务、实施主要功能或合并前使用，以验证工作符合需求
---

# 请求代码审查

派发 zjkycode:code-reviewer 子代理，在问题级联之前捕获它们。审查者获得精心构建的上下文进行评估——绝不是你会话的历史记录。这让审查者专注于工作成果，而非你的思考过程，同时也为你自己的后续工作保留上下文。

**核心原则：** 尽早审查，经常审查。

## 何时请求审查

**必须：**
- 在子代理驱动开发中的每个任务后
- 完成主要功能后
- 合并到主分支前

**可选但有价值：**
- 卡住时（新视角）
- 重构前（基线检查）
- 修复复杂 bug 后

## 如何请求

**1. 获取 git SHA：**
```bash
BASE_SHA=$(git rev-parse HEAD~1)  # 或 origin/main
HEAD_SHA=$(git rev-parse HEAD)
```

**2. 派发 code-reviewer 子代理：**

使用 Task 工具，类型为 zjkycode:code-reviewer，填写 `code-reviewer.md` 中的模板

**占位符：**
- `{WHAT_WAS_IMPLEMENTED}` - 你刚刚构建的内容
- `{PLAN_OR_REQUIREMENTS}` - 它应该做什么
- `{BASE_SHA}` - 起始提交
- `{HEAD_SHA}` - 结束提交
- `{DESCRIPTION}` - 简要摘要

**3. 根据反馈行动：**
- 立即修复严重问题
- 在继续前修复重要问题
- 记录次要问题以备后用
- 如果审查者错误则反驳（附理由）

## 示例

```
[刚完成任务 2：添加验证功能]

你：让我在继续之前请求代码审查。

BASE_SHA=$(git log --oneline | grep "Task 1" | head -1 | awk '{print $1}')
HEAD_SHA=$(git rev-parse HEAD)

[派发 zjkycode:code-reviewer 子代理]
  WHAT_WAS_IMPLEMENTED: 对话索引的验证和修复功能
  PLAN_OR_REQUIREMENTS: docs/zjkycode/plans/deployment-plan.md 中的任务 2
  BASE_SHA: a7981ec
  HEAD_SHA: 3df7661
  DESCRIPTION: 添加了 verifyIndex() 和 repairIndex()，包含 4 种问题类型

[子代理返回]：
  优点：干净的架构，真实的测试
  问题：
    重要：缺少进度指示器
    次要：报告间隔的魔法数字（100）
  评估：准备继续

你：[修复进度指示器]
[继续任务 3]
```

## 与工作流集成

**子代理驱动开发：**
- 每个任务后审查
- 在问题累积前捕获
- 在进入下一个任务前修复

**执行计划：**
- 每批（3 个任务）后审查
- 获取反馈，应用，继续

**临时开发：**
- 合并前审查
- 卡住时审查

## 危险信号

**绝不：**
- 因为"很简单"而跳过审查
- 忽略严重问题
- 带着未修复的重要问题继续
- 与有效的技术反馈争论

**如果审查者错误：**
- 用技术推理反驳
- 展示证明其有效的代码/测试
- 请求澄清

参见模板：requesting-code-review/code-reviewer.md