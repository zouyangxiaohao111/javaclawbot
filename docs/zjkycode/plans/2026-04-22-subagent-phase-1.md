# Phase 1: Fork 核心 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标**：验证 Fork 子代理机制的完整实现，继承父代理完整上下文并共享 Prompt Cache

**架构**：ForkSubagentTool → ForkAgentExecutor → ForkContextBuilder → SubagentContext（隔离）

**技术栈**：Java 17+, picocli/JLine, Jackson

---

## ⚠️ 实施要求

**必须先阅读**：
- `docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 3.5 节和第 4.2 节
- Open-ClaudeCode 源码：
  - `src/utils/forkedAgent.ts` - Fork 上下文管理
  - `src/tools/AgentTool/forkSubagent.ts` - Fork 子代理核心逻辑

**复刻原则**：必须完整按照 Open-ClaudeCode 源码逐行实现，做到 Java 语义等价、逻辑等价。

---

## 文件结构

```
src/main/java/agent/subagent/
├── fork/
│   ├── ForkContext.java              # ✅ 已完成 → ForkedAgentParams
│   ├── ForkContextBuilder.java       # ✅ 已完成 → buildForkedAgentParams()
│   ├── CacheSafeParams.java          # ✅ 已完成 → CacheSafeParams
│   ├── ForkAgentDefinition.java      # ✅ 已完成 → FORK_AGENT, buildForkedMessages()
│   ├── ForkSubagentTool.java         # ✅ 已完成 → AgentTool 组件
│   └── ForkAgentExecutor.java        # ✅ 已完成 → runAgent()
├── context/
│   └── SubagentContext.java          # ✅ 已完成 → createSubagentContext()
├── definition/
│   ├── AgentDefinition.java          # ✅ 已完成 → BuiltInAgentDefinition
│   └── PermissionMode.java           # ✅ 已完成 → PermissionMode

src/main/java/agent/tool/
└── ToolUseContext.java               # ✅ 已完成 → ToolUseContext
```

---

## 任务 1：验证 ForkContext 完整性

**文件：** `src/main/java/agent/subagent/fork/ForkContext.java`
**源码参考：** `src/utils/forkedAgent.ts` - ForkedAgentParams

**必须验证的功能**：
1. ✅ parentAgentId - 父代理 ID
2. ✅ directive - Fork 指令
3. ✅ parentAssistantMessage - 父代理最后一条消息
4. ✅ parentSystemPrompt - 父代理系统提示词
5. ✅ parentMessages - 父代理消息历史
6. ✅ userContext - 用户上下文
7. ✅ systemContext - 系统上下文

- [ ] **步骤 1：对比 TypeScript 源码**

读取 `src/utils/forkedAgent.ts` 中的 `ForkedAgentParams` 类型定义，验证 Java 实现是否完整对应。

```bash
cat /usr/local/code/Open-ClaudeCode/src/utils/forkedAgent.ts | grep -A 20 "type ForkedAgentParams"
```

- [ ] **步骤 2：验证 Builder 模式**

确认 Builder 包含所有字段的 setter 方法。

- [ ] **步骤 3：提交（如有修改）**

```bash
git add src/main/java/agent/subagent/fork/ForkContext.java
git commit -m "chore(subagent): verify ForkContext completeness"
```

---

## 任务 2：验证 CacheSafeParams 完整性

**文件：** `src/main/java/agent/subagent/fork/CacheSafeParams.java`
**源码参考：** `src/utils/forkedAgent.ts` - CacheSafeParams

**必须验证的功能**：
1. ✅ systemPrompt - 系统提示词
2. ✅ userContext - 用户上下文
3. ✅ systemContext - 系统上下文
4. ✅ toolUseContext - 工具使用上下文
5. ✅ forkContextMessages - Fork 上下文消息

- [ ] **步骤 1：对比 TypeScript 源码**

```bash
cat /usr/local/code/Open-ClaudeCode/src/utils/forkedAgent.ts | grep -A 15 "type CacheSafeParams"
```

- [ ] **步骤 2：验证编译**

```bash
mvn compile -q
```

---

## 任务 3：验证 ForkAgentDefinition 完整性

**文件：** `src/main/java/agent/subagent/fork/ForkAgentDefinition.java`
**源码参考：** `src/tools/AgentTool/forkSubagent.ts`

**必须验证的功能**：

### 3.1 FORK_AGENT 定义
- [ ] ✅ agentType = "fork"
- [ ] ✅ whenToUse 描述
- [ ] ✅ tools = ["*"]
- [ ] ✅ maxTurns = 200
- [ ] ✅ model = "inherit"
- [ ] ✅ permissionMode = BUBBLE

### 3.2 buildForkedMessages()
- [ ] ✅ 克隆 assistant 消息
- [ ] ✅ 提取所有 tool_use 块
- [ ] ✅ 为每个 tool_use 生成 placeholder 结果
- [ ] ✅ 构建 user 消息包含所有 placeholder + directive

### 3.3 buildChildMessage()
- [ ] ✅ FORK_BOILERPLATE_TAG
- [ ] ✅ 规则 1-10（对应 TypeScript 源码）
- [ ] ✅ 输出格式（Scope/Result/Key files/Files changed/Issues）

### 3.4 isInForkChild()
- [ ] ✅ 检测 FORK_BOILERPLATE_TAG

- [ ] **步骤 1：对比 TypeScript 规则**

检查 Java 的规则是否与 TypeScript 源码一致：
```bash
cat /usr/local/code/Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts | grep -A 15 "RULES"
```

- [ ] **步骤 2：验证编译**

```bash
mvn compile -q
```

---

## 任务 4：验证 SubagentContext 完整性

**文件：** `src/main/java/agent/subagent/context/SubagentContext.java`
**源码参考：** `src/utils/forkedAgent.ts` - createSubagentContext()

**必须验证的功能**：
1. ✅ fileStateCache - 文件状态缓存（克隆）
2. ✅ abortSignal - 中止信号
3. ✅ progressTracker - 进度追踪器
4. ✅ shouldAvoidPermissionPrompts - 权限提示控制
5. ✅ toolUseContext - 工具使用上下文
6. ✅ agentId - Agent ID
7. ✅ parentAgentId - 父代理 ID

- [ ] **步骤 1：对比 TypeScript 源码**

```bash
cat /usr/local/code/Open-ClaudeCode/src/utils/forkedAgent.ts | grep -A 60 "export function createSubagentContext"
```

- [ ] **步骤 2：验证编译**

```bash
mvn compile -q
```

---

## 任务 5：验证 ForkAgentExecutor 完整性

**文件：** `src/main/java/agent/subagent/fork/ForkAgentExecutor.java`
**源码参考：** `src/tools/AgentTool/runAgent.ts` - runAgent()

**必须验证的功能**：
1. ✅ runAgent() 执行循环
2. ✅ 使用 cacheSafeParams
3. ✅ 工具调用处理
4. ✅ 结果返回
5. ✅ forkLabel 支持

- [ ] **步骤 1：读取实现代码**

```bash
wc -l src/main/java/agent/subagent/fork/ForkAgentExecutor.java
```

- [ ] **步骤 2：验证编译**

```bash
mvn compile -q
```

---

## 任务 6：验证 ToolUseContext 完整性

**文件：** `src/main/java/agent/tool/ToolUseContext.java`
**源码参考：** `src/Tool.ts` - ToolUseContext

**必须验证的功能**：
1. ✅ tools - 可用工具列表
2. ✅ mainLoopModel - 主循环模型
3. ✅ mcpClients - MCP 客户端
4. ✅ toolPermissionContext - 工具权限上下文
5. ✅ customSystemPrompt - 自定义系统提示词
6. ✅ appendSystemPrompt - 追加系统提示词
7. ✅ workspace - 工作目录
8. ✅ agentId - Agent ID
9. ✅ abortController - 中止控制器
10. ✅ messages - 消息列表

- [ ] **步骤 1：对比 TypeScript 源码**

```bash
cat /usr/local/code/Open-ClaudeCode/src/Tool.ts | grep -A 80 "export type ToolUseContext"
```

- [ ] **步骤 2：验证编译**

```bash
mvn compile -q
```

---

## 任务 7：验证 AgentDefinition 完整性

**文件：** `src/main/java/agent/subagent/definition/AgentDefinition.java`
**源码参考：** `src/tools/AgentTool/loadAgentsDir.ts` - BuiltInAgentDefinition

**必须验证的字段**：
1. ✅ agentType
2. ✅ whenToUse
3. ✅ tools
4. ✅ disallowedTools
5. ✅ model
6. ✅ permissionMode
7. ✅ maxTurns
8. ✅ background
9. ✅ isolation
10. ✅ getSystemPrompt
11. ✅ source
12. ✅ baseDir
13. ✅ readOnly
14. ✅ omitClaudeMd

- [ ] **步骤 1：对比 TypeScript 源码**

```bash
cat /usr/local/code/Open-ClaudeCode/src/tools/AgentTool/loadAgentsDir.ts | grep -A 30 "interface BuiltInAgentDefinition"
```

- [ ] **步骤 2：验证编译**

```bash
mvn compile -q
```

---

## 任务 8：Phase 1 总结

**文件：** `docs/subagent/phase-1-summary.md`

- [ ] **步骤 1：创建/更新总结文档**

根据实际完成情况，创建 Phase 1 总结文档，包含：
1. 所有交付物列表
2. Git 提交历史
3. 与 Open-ClaudeCode 的对应关系
4. 关键设计决策
5. 已知限制
6. 如何继续 Phase 2

- [ ] **步骤 2：提交总结**

```bash
git add docs/subagent/phase-1-summary.md
git commit -m "docs: update Phase 1 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应文件 | 状态 |
|---------|---------|------|
| ForkedAgentParams | fork/ForkContext.java | ✅ |
| buildForkedAgentParams() | fork/ForkContextBuilder.java | ✅ |
| CacheSafeParams | fork/CacheSafeParams.java | ✅ |
| FORK_AGENT | fork/ForkAgentDefinition.java | ✅ |
| buildForkedMessages() | fork/ForkAgentDefinition.java | ✅ |
| buildChildMessage() | fork/ForkAgentDefinition.java | ✅ |
| AgentTool | fork/ForkSubagentTool.java | ✅ |
| runAgent() | fork/ForkAgentExecutor.java | ✅ |
| createSubagentContext() | context/SubagentContext.java | ✅ |
| BuiltInAgentDefinition | definition/AgentDefinition.java | ✅ |
| PermissionMode | definition/PermissionMode.java | ✅ |
| ToolUseContext | tool/ToolUseContext.java | ✅ |

### 编译验证

- [ ] 所有文件编译通过

### 占位符扫描

无占位符，所有步骤都包含完整验证指令。

---

## 执行选择

**计划完成并保存到 `docs/zjkycode/plans/2026-04-22-subagent-phase-1.md`。两种执行选项：**

**1. 子代理驱动（推荐）** - 我为每个任务调度一个新子代理，在任务之间审查，快速迭代

**2. 内联执行** - 使用 executing-plans 在此会话中执行任务，带检查点的批量执行

**选择哪种方式？**
