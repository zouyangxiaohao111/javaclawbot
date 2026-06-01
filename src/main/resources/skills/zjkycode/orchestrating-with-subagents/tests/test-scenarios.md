# Test Scenarios

用 subagent 测试 orchestrating-with-subagents skill 的 6 个场景。每个场景是"RED-GREEN-REFACTOR"的 TDD 应用。

## 如何运行测试

```bash
# 每个场景的运行方式：
# 1. 启动 Claude Code（加载 orchestrating-with-subagents skill）
# 2. 给一个特定 prompt（见每个场景的 "Test Prompt"）
# 3. 观察主 agent 行为
# 4. 验证预期行为
```

## 场景 1：低复杂任务应"不派"

**目的**：验证主 agent 严格按决策表，对低复杂任务不派 subagent。

**Test Prompt**：
```
帮我加个 log：在 MainWindow 类加一行 log.info("MainWindow initialized")
```

**预期行为**：
- ✅ 主 agent 调用 using-zjkycode → orchestrating-with-subagents
- ✅ 主 agent 查决策表：低复杂 + 高独立 + 低风险 → 决策 = 不派
- ✅ 主 agent 自己加那行 log（走实现路径）
- ✅ 仍然记录到 progress/

**失败行为**：
- ❌ 主 agent 没调用 skill
- ❌ 主 agent 不查决策表就派 subagent
- ❌ 主 agent 跳过 orchestrating-with-subagents

**判断**：行为匹配预期 = PASS

## 场景 2：中-高复杂任务应"派 1 个"

**目的**：验证主 agent 对中-高复杂任务派 1 个 subagent。

**Test Prompt**：
```
我要加个新功能 "Project Backup"：在 File 菜单加 "Backup Now" 按钮，点击后备份项目目录到 .backup/<timestamp>/。
要求：
- 创建 BackupService.java
- 写单元测试
- 集成到主窗口
```

**预期行为**：
- ✅ 调用 orchestrating-with-subagents
- ✅ 查决策表：中-高复杂 + 高独立 + 中风险 → 决策 = 派 1 个
- ✅ 派 1 个 subagent（不派多）
- ✅ 准备完整 context packet（11 字段）
- ✅ 等 subagent 完成后读 findings/

**失败行为**：
- ❌ 主 agent 自己实现（不派）
- ❌ 派多个 subagent（过度）
- ❌ context packet 不完整

**判断**：行为匹配预期 = PASS

## 场景 3：独立并行任务应"派多并行"

**目的**：验证主 agent 对独立任务派多并行。

**Test Prompt**：
```
并行实现 3 个独立 UI 组件：
1. SettingsPanel（设置面板）
2. AboutDialog（关于对话框）
3. NotificationCenter（通知中心）
3 个组件互相独立。
```

**预期行为**：
- ✅ 查决策表：3 个独立 UI 组件 → 决策 = 派多并行
- ✅ 调用 dispatching-parallel-agents
- ✅ 3 个 subagent 并发派发（不是串行）
- ✅ 每个 subagent 独立 context packet
- ✅ 主 agent 等所有 3 个完成

**失败行为**：
- ❌ 串行派（一次一个）
- ❌ 让主 agent 自己写
- ❌ 派 1 个 subagent 包揽 3 个组件

**判断**：行为匹配预期 = PASS

## 场景 4：UI 任务应"派 + visual-companion"

**目的**：验证主 agent 对 UI 任务派 visual-companion subagent。

**Test Prompt**：
```
设计一个 LoginDialog：包含用户名、密码、登录按钮，Apple 设计风格，圆角 8px，SF Pro 字体。
```

**预期行为**：
- ✅ 查决策表：UI/视觉 → 决策 = 派 + visual-companion
- ✅ 派 visual-companion subagent（不是普通的 implementer）
- ✅ context packet 包含设计风格需求（Apple、SF Pro、圆角 8px）
- ✅ subagent 输出设计 + 实施

**失败行为**：
- ❌ 派普通 subagent
- ❌ 不要求视觉验证

**判断**：行为匹配预期 = PASS

## 场景 5：调试任务应"派 systematic-debugging"

**目的**：验证主 agent 对调试任务派 systematic-debugging subagent。

**Test Prompt**：
```
我的 MCP 通信偶发失败，约 5% 概率。错误信息 "Connection reset"。请修复。
```

**预期行为**：
- ✅ 查决策表：调试 → 决策 = 派 systematic-debugging
- ✅ 派 systematic-debugging subagent（不派普通的）
- ✅ subagent 调用 systematic-debugging 的 4 阶段流程
- ✅ 先定位根因，不"修一下试试"
- ✅ 找到根因后才派实施 subagent 修复

**失败行为**：
- ❌ 派普通 subagent 直接修复
- ❌ 让主 agent 自己 debug
- ❌ 跳过 4 阶段流程

**判断**：行为匹配预期 = PASS

## 场景 6：决策表未覆盖时应"不派 + 升级"

**目的**：验证主 agent 对未覆盖场景不硬派不硬干。

**Test Prompt**：
```
帮我做一件从未做过的事：[自定义任务，不在决策表覆盖范围内]
```

**预期行为**：
- ✅ 查决策表：未覆盖
- ✅ 决策 = 不派
- ✅ 主 agent 升级到用户（不硬派不硬干）
- ✅ 升级格式：已尝试 + 失败原因 + 建议下一步

**失败行为**：
- ❌ 主 agent 凭感觉"这应该派" → 硬派
- ❌ 主 agent "反正简单自己干" → 硬干

**判断**：行为匹配预期 = PASS

## 测试结果记录模板

```markdown
## 测试运行：2026-06-01 HH:MM

| 场景 | 结果 | 备注 |
|---|---|---|
| 1. 低复杂任务 | PASS / FAIL | [具体观察] |
| 2. 中-高复杂任务 | PASS / FAIL | [具体观察] |
| 3. 独立并行任务 | PASS / FAIL | [具体观察] |
| 4. UI 任务 | PASS / FAIL | [具体观察] |
| 5. 调试任务 | PASS / FAIL | [具体观察] |
| 6. 决策表未覆盖 | PASS / FAIL | [具体观察] |

**总计**：6/6 PASS = skill 工作正常
**总计**：< 6/6 PASS = 需要修复
```

## 回归测试

修改 SKILL.md 后，必须重跑所有 6 个场景，确保无 regression。
