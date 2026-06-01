# Example 4: 3 个独立 UI 组件并行

**场景**：用户要求同时实现 3 个独立的 UI 组件（不互相依赖）。

## 1. 用户需求

> 我要加 3 个独立的 UI 组件：
> 1. SettingsPanel - 设置面板
> 2. AboutDialog - 关于对话框
> 3. NotificationCenter - 通知中心
>
> 3 个组件互相独立，可以并行做。

## 2. 编排器流程

### 2.1 写 plan

`plan/task_plan.md`：

```markdown
# Task Plan: 实现 3 个独立 UI 组件

## Goal
并行实现 SettingsPanel/AboutDialog/NotificationCenter 3 个 UI 组件

## Current Phase
Phase 1: 实施

## Phases

### Phase 1: 并行实施 (status: in_progress)
- [ ] 实现 SettingsPanel
- [ ] 实现 AboutDialog
- [ ] 实现 NotificationCenter

### Phase 2: 集成 + 测试 (status: pending)
- [ ] 集成到主窗口
- [ ] 写测试

## Key Decisions
- 3 个组件**完全独立**（不共享文件、不共享状态）
- 用 parallel dispatch

## Session Handoff
- 当前进度: Phase 1
```

### 2.2 决策

| 维度 | SettingsPanel | AboutDialog | NotificationCenter |
|---|---|---|---|
| 类别 | UI | UI | UI |
| 复杂度 | 中 | 低 | 中 |
| 独立性 | 高 | 高 | 高 |
| 风险 | 中 | 低 | 中 |

3 个都是"高独立" → **派多并行**

## 3. 派并行 subagent

调用 `dispatching-parallel-agents`，3 个 subagent 并发。

context packet（3 个类似，以 SettingsPanel 为例）：

```markdown
# Subagent Task Brief: impl-SettingsPanel

## 任务目标（Goal）
实现 SettingsPanel UI 组件，显示应用设置。

## 当前阶段（Current Phase）
Phase 1: 并行实施（plan/task_plan.md）

## 范围（Scope）

### 必须做
- [ ] 创建 `src/main/java/gui/components/SettingsPanel.java`
- [ ] 实现 UI：标签、输入框、保存/取消按钮
- [ ] 用 JavaFX
- [ ] 写单元测试

### 不要做
- ❌ 不要修改其他组件
- ❌ 不要修改主窗口
- ❌ 不要实现设置逻辑（只做 UI 框架）

## 成功标准（Done When）
- [ ] SettingsPanel.java 存在
- [ ] UI 显示：标签 + 输入框 + 按钮
- [ ] mvn test 通过
- [ ] mvn compile 通过

## 相关文件（Required Reading）
- `docs/zjkycode/specs/2026-06-01-orchestrating-with-subagents-design.md` — 架构
- `src/main/java/gui/components/` — 现有组件（参考风格）

## 依赖项
- 无（独立任务）

## 约束
- Java 17 + JavaFX
- SLF4J 必加数据流日志
- 风格参考 `src/main/java/gui/components/`

## 返回契约
- `findings/impl-SettingsPanel.md`
- `progress/impl-SettingsPanel-progress.md`

## 验证命令
- `mvn compile`
- `mvn test -Dtest=SettingsPanelTest`

## 反模式
- ❌ 不要修改其他组件
- ❌ 不要实现设置逻辑
- ❌ 不要碰 `src/main/java/utils/`

## 触发相关 skill
- test-driven-development
- craft-studio (UI 任务)
- verification-before-completion
```

AboutDialog 和 NotificationCenter 类似 context packet，但 Description 不同。

## 4. 3 个 subagent 并发执行

```python
# 主 agent 派单
Agent("impl-SettingsPanel")
Agent("impl-AboutDialog")
Agent("impl-NotificationCenter")
# 3 个并发执行
```

3 个 subagent 同时工作。

## 5. 收结果

主 agent 等所有 3 个完成，依次读 findings/。

| 任务 | 状态 | findings/ |
|---|---|---|
| SettingsPanel | DONE | impl-SettingsPanel.md |
| AboutDialog | DONE | impl-AboutDialog.md |
| NotificationCenter | DONE | impl-NotificationCenter.md |

主 agent 验证：
- 3 个文件都存在
- mvn test 全量通过
- 无文件冲突

## 6. Phase 2：集成

派 subagent 集成到主窗口：

```markdown
# Subagent Task Brief: integrate-3-components

## 任务目标
将 3 个 UI 组件集成到主窗口 MainWindow。

## 范围

### 必须做
- [ ] 修改 MainWindow.java，添加菜单项触发 3 个组件
- [ ] Settings: 菜单 "File > Settings"
- [ ] About: 菜单 "Help > About"
- [ ] Notification: 工具栏按钮
- [ ] 写测试

### 不要做
- ❌ 不要修改 3 个组件本身
- ❌ 不要实现设置逻辑

## 成功标准
- [ ] mvn test 全量通过
- [ ] 3 个组件可以从主窗口打开

## 返回契约
- `findings/integrate.md`
```

## 7. 收尾

- 派 final code review subagent
- 派 verification-before-completion subagent
- 更新 CHANGELOG.md

## 关键要点

1. **独立任务并行**——dispatching-parallel-agents
2. **每个 subagent 隔离上下文**——不互相干扰
3. **完成后整合**——主 agent 读所有 findings/
4. **冲突检测**——多个 subagent 改同一文件会冲突（但本例 3 个不冲突）
