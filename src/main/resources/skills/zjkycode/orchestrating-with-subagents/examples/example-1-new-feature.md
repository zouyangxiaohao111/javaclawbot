# Example 1: 实现 JavaFX 新功能

**场景**：用户要求实现"项目文件历史时间线视图"功能。

## 1. 用户需求

> 我想加个"项目时间线"功能，让用户能看到所有 git commit 的可视化时间线。每个 commit 一个卡片，显示 commit message、author、timestamp、影响的文件数。

## 2. 主 agent 调用流程

### 2.1 必先调用

```python
# 必先调用 using-zjkycode
load_skill("using-zjkycode")
```

### 2.2 进入 brainstorming（如需要）

这是复杂任务，调用 brainstorming 苏格拉底式提问：

- Q: 时间线数据从哪来？（git log？项目数据库？）
- Q: 可视化用 JavaFX 控件还是 WebView？
- Q: 性能要求？1000+ commits 怎么办？
- ...

brainstorming 后确定方案。

### 2.3 写 plan

调用 writing-plans 创建 `plan/task_plan.md`：

```markdown
# Task Plan: 项目时间线视图

## Goal
实现 JavaFX TimelineView，可视化显示项目 git commit 历史。

## Current Phase
Phase 1

## Phases

### Phase 1: 调研 + 设计 (status: in_progress)
- [ ] 调研 git log API
- [ ] 设计 TimelineView UI 布局
- [ ] 决定数据存储方案

### Phase 2: 实现 TimelineView (status: pending)
- [ ] 实现 TimelineView.java
- [ ] 实现 TimelineCard.java
- [ ] 实现 TimelineService.java（读 git log）

### Phase 3: 集成 + 测试 (status: pending)
- [ ] 集成到主窗口
- [ ] 写单元测试
- [ ] 写 UI 测试

## Key Decisions
- 数据源：git log（`ProcessBuilder git log`）
- 可视化：JavaFX ListView + 自定义 cell
- 性能：分页加载

## Errors Encountered
- (无)

## Session Handoff
- 当前进度: Phase 1
- 下次开会读: plan/task_plan.md + findings/research-1.md
```

### 2.4 进入编排器（orchestrating-with-subagents）

```python
load_skill("orchestrating-with-subagents")
```

主 agent 开始 4 步循环。

## 3. 第 1 步：读 plan

主 agent 读 `plan/task_plan.md`：
- 识别当前 Phase 1
- 下一个待办：调研 git log API

## 4. 第 2 步：查决策表

查 `references/decision-matrix.md`：

| 维度 | 评估 | 值 |
|---|---|---|
| 类别 | 调研 | 任意 |
| 复杂度 | 中（涉及 JavaFX API + git） | 中 |
| 独立性 | 高（不依赖其他任务） | 高 |
| 风险 | 中（影响后续设计） | 中 |

→ 决策：**派 1 个 subagent**

## 5. 第 3 步：派 subagent

调用 `subagent-driven-development`，传 context packet：

```markdown
# Subagent Task Brief: research-git-log-api

## 任务目标（Goal）
调研如何在 Java 应用中读取 git log，输出 API 选型建议。

## 当前阶段（Current Phase）
Phase 1: 调研 + 设计（plan/task_plan.md）

## 范围（Scope）

### 必须做
- [ ] 调研 3+ 种 Java 读 git log 的方法（JGit、ProcessBuilder、libgit2）
- [ ] 输出对比表（性能、易用性、依赖大小）
- [ ] 给出推荐方案 + 理由

### 不要做
- ❌ 不要修改任何代码
- ❌ 不要实施任何功能

## 成功标准（Done When）
- [ ] findings/research-1.md 包含对比表
- [ ] findings/research-1.md 推荐方案 + 理由 ≥ 200 字
- [ ] findings/research-1.md 包含示例代码片段

## 相关文件（Required Reading）
- `docs/zjkycode/specs/2026-06-01-orchestrating-with-subagents-design.md` — 当前架构
- `src/main/java/utils/` — 项目已有的工具类

## 依赖项（Dependencies）
- 无

## 约束（Constraints）
- 语言：Java 17
- 推荐方案必须考虑 Maven 依赖管理
- 不要引入 GPL 协议依赖

## 返回契约（Return Contract）
完成后写：
- `findings/research-1.md` — 调研结果

报告状态：**DONE**（必须）

## 验证命令（Verification）
- 无（纯调研任务，不需 mvn）

## 反模式（Anti-Patterns）
- ❌ 不要只是"我觉得"——要有具体数据
- ❌ 不要超过 5 种方案——保持精简
- ❌ 不要推荐未在 Maven Central 验证过的库

## 触发相关 skill
无
```

主 agent 派 subagent。

## 6. subagent 实施

subagent 实施调研任务，写 `findings/research-1.md`：

```markdown
# Findings: research-git-log-api

## 完成内容
调研了 3 种 Java 读 git log 的方法，输出对比 + 推荐 JGit。

## 关键决策
- **推荐 JGit**：官方 Eclipse 维护，API 成熟，文档完整
- **次选 ProcessBuilder**：轻量，但需自己解析输出

## 方案对比

| 方案 | 性能 | 易用性 | 依赖大小 | 协议 | 推荐 |
|---|---|---|---|---|---|
| JGit | 中 | 高 | ~3MB | EDL (BSD-like) | ✅ |
| ProcessBuilder | 高 | 低 | 0 | N/A | ⚠️ |
| libgit2 (java binding) | 高 | 中 | ~5MB | GPL | ❌ |

## 示例代码

```java
// JGit 示例
try (Git git = Git.open(new File("/path/to/repo"))) {
    Iterable<RevCommit> commits = git.log().call();
    for (RevCommit commit : commits) {
        System.out.println(commit.getName() + ": " + commit.getShortMessage());
    }
}
```

## 验证证据
- JGit 已在 Maven Central 验证
- 性能测试：1000 commits 用 JGit 约 200ms
- 依赖：org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050846-r

## 疑虑
- 无
```

## 7. 第 4 步：收结果

主 agent 读 `findings/research-1.md`：

- 状态 = DONE ✅
- 关键决策：JGit
- 主 agent 更新 `plan/task_plan.md`：Phase 1 任务"调研 git log API"标记完成

## 8. 继续循环

主 agent 回到第 1 步，读 plan，识别下一个待办（设计 TimelineView UI 布局）...

循环直到所有 Phase 完成。

## 9. 收尾

```python
load_skill("verification-before-completion")
load_skill("finishing-a-development-branch")
```

更新 CHANGELOG.md、pom.xml 版本、git commit。

## 关键要点

1. **本 skill 不直接写代码**——只做协调
2. **subagent 写 findings/**——主 agent 读 findings/
3. **决策表严格遵守**——不硬派不硬干
4. **状态文件化**——plan/ + findings/ + progress/
5. **完整 context packet**——11 字段必填
