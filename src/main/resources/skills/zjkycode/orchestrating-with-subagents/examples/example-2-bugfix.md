# Example 2: 修 MCP 通信 bug

**场景**：用户报告 MCP 通信偶发失败。

## 1. 用户报告

> 我用 javaclawbot 的 MCP 通信时，偶发"Connection reset"错误。约 5% 概率。

## 2. 主 agent 调用流程

### 2.1 必先调用

```python
load_skill("using-zjkycode")
load_skill("orchestrating-with-subagents")
```

### 2.2 创建 plan

调用 writing-plans 创建 `plan/task_plan.md`：

```markdown
# Task Plan: 修复 MCP 通信偶发失败

## Goal
定位并修复 MCP 通信的"Connection reset"问题。

## Current Phase
Phase 1: 根因调查

## Phases

### Phase 1: 根因调查 (status: in_progress)
- [ ] 复现 bug（5% 概率）
- [ ] 收集错误日志
- [ ] 定位根因

### Phase 2: 修复 (status: pending)
- [ ] 实施修复
- [ ] 写回归测试

### Phase 3: 验证 (status: pending)
- [ ] 压力测试
- [ ] 部署到测试环境

## Key Decisions
- 调查方法：日志 + 代码静态分析

## Errors Encountered
- (无)

## Session Handoff
- 当前进度: Phase 1
```

## 3. 第 1 步：读 plan

识别当前 Phase 1，下一个待办：复现 bug。

## 4. 第 2 步：查决策表

| 维度 | 评估 |
|---|---|
| 类别 | 调试 |
| 复杂度 | 中（涉及网络通信） |
| 独立性 | 中（依赖现有代码） |
| 风险 | 高（核心功能） |

→ 决策：**派 systematic-debugging subagent**

## 5. 第 3 步：派 subagent

```markdown
# Subagent Task Brief: debug-mcp-connection-reset

## 任务目标（Goal）
定位 javaclawbot MCP 通信的"Connection reset"偶发失败的根因。

## 当前阶段（Current Phase）
Phase 1: 根因调查（plan/task_plan.md）

## 范围（Scope）

### 必须做
- [ ] 用 systematic-debugging skill 的 4 阶段流程
- [ ] 复现 bug（5% 概率，需要大量尝试）
- [ ] 收集所有错误日志
- [ ] 定位根因（写 findings/debug-1.md）

### 不要做
- ❌ 不要修改任何代码
- ❌ 不要"猜测"根因——必须用数据

## 成功标准（Done When）
- [ ] findings/debug-1.md 包含根因分析
- [ ] findings/debug-1.md 包含最小复现步骤
- [ ] findings/debug-1.md 包含证据（日志、调用栈、统计）

## 相关文件（Required Reading）
- `src/main/java/providers/mcp/` — MCP 通信实现
- `src/main/java/utils/HttpClient.java` — 项目 HTTP 工具
- `docs/experience/` — 已有类似 bug 记录

## 依赖项（Dependencies）
- 无

## 约束（Constraints）
- 不要修改任何代码
- 不要跳过 systematic-debugging 的 4 阶段

## 返回契约（Return Contract）
完成后写：
- `findings/debug-1.md` — 根因分析

报告状态：**DONE**（必须）

## 验证命令（Verification）
- 无（纯调查任务）

## 反模式（Anti-Patterns）
- ❌ 不要猜测根因
- ❌ 不要跳过复现
- ❌ 不要"修一下试试"——必须先定位

## 触发相关 skill
- **必先调用 `systematic-debugging`**，按 4 阶段流程
```

主 agent 派 subagent。

## 6. subagent 实施

subagent 调用 systematic-debugging：

### 阶段 1：复现
- 跑 1000 次 MCP 调用
- 记录失败的 50 次（约 5%）
- 提取失败请求的共同特征

### 阶段 2：模式分析
- 失败的请求有什么共同点？
- 时间模式？大小模式？特定 endpoint？
- 发现：所有失败的请求都是 `Content-Length > 1MB`

### 阶段 3：假设
- 假设：HTTP 客户端的缓冲区只有 1MB
- 证据：HttpClient.java:42 看到 `MAX_BODY_SIZE = 1024 * 1024`

### 阶段 4：根因
- 确认根因：`HttpClient.MAX_BODY_SIZE` 限制
- 大响应被截断，对端收到 incomplete body，主动 reset 连接

subagent 写 `findings/debug-1.md`：

```markdown
# Findings: debug-mcp-connection-reset

## 根因
`HttpClient.MAX_BODY_SIZE = 1024 * 1024` (1MB) 限制
当 MCP 响应体 > 1MB 时，body 被截断
对端收到不完整 body，主动 reset 连接

## 复现步骤
1. 准备一个会返回 > 1MB 响应的 MCP 请求
2. 跑 1000 次
3. 100% 失败（不是 5% 概率——是大响应必失败）

注：之前 5% 概率是因为大部分请求响应 < 1MB

## 证据
- 失败请求的 `Content-Length` 都 > 1MB
- 错误日志显示 "Read past EOF"
- 源码 `HttpClient.java:42`: `MAX_BODY_SIZE = 1024 * 1024`

## 修复建议
- 增加 `MAX_BODY_SIZE` 到 10MB 或更高
- 或：流式读取大响应（推荐）

## 验证
修复后，跑 1000 次 > 1MB 响应，0 失败。
```

## 7. 第 4 步：收结果

主 agent 读 `findings/debug-1.md`：
- 状态 = DONE ✅
- 根因：HttpClient.MAX_BODY_SIZE 限制
- 主 agent 更新 plan/task_plan.md，进入 Phase 2

## 8. 派实施 subagent 修 bug

主 agent 派 subagent 修复：

```markdown
# Subagent Task Brief: fix-mcp-connection-reset

## 任务目标（Goal）
按 findings/debug-1.md 的建议，修复 HttpClient 的大响应问题。

## 当前阶段（Current Phase）
Phase 2: 修复

## 范围（Scope）

### 必须做
- [ ] 修改 HttpClient.java 支持 > 1MB 响应
- [ ] 写回归测试（测 10MB 响应不失败）
- [ ] 写 findings/fix-1.md

### 不要做
- ❌ 不要重写整个 HttpClient
- ❌ 不要改变 API 签名

## 成功标准（Done When）
- [ ] mvn test 通过
- [ ] 新增回归测试：MCP_HttpClient_LargeResponse
- [ ] 修复后无 regression

## 相关文件（Required Reading）
- `findings/debug-1.md` — 根因分析
- `src/main/java/utils/HttpClient.java` — 待修改文件

## 依赖项（Dependencies）
- findings/debug-1.md

## 约束（Constraints）
- 保持 API 兼容
- 优先用流式读取

## 返回契约（Return Contract）
完成后写：
- `findings/fix-1.md` — 修复内容
- `progress/fix-1-progress.md` — 执行日志

报告状态：**DONE**

## 验证命令（Verification）
- `mvn compile` 通过
- `mvn test -Dtest=MCP_HttpClient_LargeResponse` 通过
- `mvn test` 全量通过（无 regression）

## 反模式（Anti-Patterns）
- ❌ 不要改 HTTP 协议
- ❌ 不要碰 utils/ 下的其他文件
- ❌ 不要 skip logging

## 触发相关 skill
- 必先调用 `test-driven-development`
- 完成后调用 `verification-before-completion`
```

## 9. 收尾

修复后：
- mvn test 通过
- 派 final code review subagent
- 派 verification-before-completion subagent
- 更新 CHANGELOG.md

## 关键要点

1. **bug 修复也要派 subagent**——不亲自 debug
2. **systematic-debugging 4 阶段**是必走流程
3. **先定位再修复**——不"试一下"
4. **回归测试是必须**——不是可选
