---
name: finishing-a-development-branch
description: 当实施完成、所有测试通过、需要决定如何整合工作时使用 — 通过展示合并、PR 或清理的结构化选项来指导开发工作的完成
---

# 完成开发分支

## 概述

通过展示清晰的选项和处理选择的工作流来指导开发工作的完成。

**核心原则：** 验证测试 → 展示选项 → 执行选择 → 清理。

**开始时宣布：** "我正在使用 finishing-a-development-branch 技能来完成这项工作。"

## 流程

### 步骤 1：验证测试

**在展示选项之前，验证测试通过：**

```bash
# Run project's test suite
npm test / cargo test / pytest / go test ./...
```

**如果测试失败：**
```
Tests failing (<N> failures). Must fix before completing:

[Show failures]

Cannot proceed with merge/PR until tests pass.
```

停止。不要继续到步骤 2。

**如果测试通过：** 继续到步骤 2。

### 步骤 2：确定基础分支

```bash
# Try common base branches
git merge-base HEAD main 2>/dev/null || git merge-base HEAD master 2>/dev/null
```

或询问： "这个分支从 main 分出 — 正确吗？"

### 步骤 3：展示选项

准确展示这 4 个选项：

```
Implementation complete. What would you like to do?

1. Merge back to <base-branch> locally
2. Push and create a Pull Request
3. Keep the branch as-is (I'll handle it later)
4. Discard this work

Which option?
```

**不要添加解释** — 保持选项简洁。

### 步骤 4：执行选择

#### 选项 1：本地合并

```bash
# Switch to base branch
git checkout <base-branch>

# Pull latest
git pull

# Merge feature branch
git merge <feature-branch>

# Verify tests on merged result
<test command>

# If tests pass
git branch -d <feature-branch>
```

然后： 清理工作树（步骤 5）

#### 选项 2：推送并创建 PR

```bash
# Push branch
git push -u origin <feature-branch>

# Create PR
gh pr create --title "<title>" --body "$(cat <<'EOF'
## Summary
<2-3 bullets of what changed>

## Test Plan
- [ ] <verification steps>
EOF
)"
```

然后： 清理工作树（步骤 5）

#### 选项 3：保持原样

报告： "保持分支 <name>。工作树保留在 <path>。"

**不要清理工作树。**

#### 选项 4：丢弃

**先确认：**
```
This will permanently delete:
- Branch <name>
- All commits: <commit-list>
- Worktree at <path>

Type 'discard' to confirm.
```

等待确切确认。

如果确认：
```bash
git checkout <base-branch>
git branch -D <feature-branch>
```

然后： 清理工作树（步骤 5）

### 步骤 5：清理工作树

**对于选项 1、2、4：**

检查是否在工作树中：
```bash
git worktree list | grep $(git branch --show-current)
```

如果是：
```bash
git worktree remove <worktree-path>
```

**对于选项 3：** 保留工作树。

## 快速参考

| 选项 | 合并 | 推送 | 保留工作树 | 清理分支 |
|--------|-------|------|---------------|----------------|
| 1. 本地合并 | ✓ | - | - | ✓ |
| 2. 创建 PR | - | ✓ | ✓ | - |
| 3. 保持原样 | - | - | ✓ | - |
| 4. 丢弃 | - | - | - | ✓ (强制) |

## 常见错误

**跳过测试验证**
- **问题：** 合并损坏的代码，创建失败的 PR
- **修复：** 在提供选项之前总是验证测试

**开放式问题**
- **问题：** "我接下来应该做什么？" → 模糊
- **修复：** 准确展示 4 个结构化选项

**自动清理工作树**
- **问题：** 在可能需要时删除工作树（选项 2、3）
- **修复：** 只对选项 1 和 4 清理

**丢弃没有确认**
- **问题：** 意外删除工作
- **修复：** 要求输入 "discard" 确认

## 危险信号

**永远不要：**
- 在测试失败时继续
- 在不验证结果测试的情况下合并
- 在没有确认的情况下删除工作
- 在没有明确请求的情况下强制推送

**总是：**
- 在提供选项之前验证测试
- 准确展示 4 个选项
- 对选项 4 获取输入确认
- 只对选项 1 和 4 清理工作树

## 整合

**被调用：**
- **subagent-driven-development**（步骤 7）— 所有任务完成后
- **executing-plans**（步骤 5）— 所有批次完成后

**配对：**
- **using-git-worktrees** — 清理该技能创建的工作树