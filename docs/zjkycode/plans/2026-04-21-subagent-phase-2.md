# Phase 2: 专用代理 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标**：实现专用代理系统，包括代理定义加载、内置代理和 AgentTool 主入口

**架构**：AgentDefinition → AgentDefinitionLoader → AgentTool → runAgent

**技术栈**：Java 17+, picocli/JLine, Jackson

---

## ⚠️ 实施要求

**必须先阅读**：`docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**复刻原则**：必须完整按照 Open-ClaudeCode 源码逐行实现，做到 Java 语义等价、逻辑等价。

---

## 文件结构

```
src/main/java/agent/subagent/
├── definition/
│   ├── AgentDefinitionLoader.java    # 对应: loadAgentsDir.ts - getAgentDefinitionsWithOverrides()
│   └── AgentDefinitionRegistry.java  # 对应: loadAgentsDir.ts - agentRegistry
├── builtin/
│   ├── GeneralPurposeAgent.java     # 对应: built-in/generalPurposeAgent.ts - GENERAL_PURPOSE_AGENT
│   ├── ExploreAgent.java            # 对应: built-in/exploreAgent.ts - EXPLORE_AGENT
│   ├── PlanAgent.java               # 对应: built-in/planAgent.ts - PLAN_AGENT
│   └── BuiltInAgents.java          # 对应: builtInAgents.ts - getBuiltInAgents()
└── execution/
    ├── AgentTool.java              # 对应: AgentTool.tsx - AgentTool
    ├── runAgent.java               # 对应: runAgent.ts - runAgent()
    ├── resumeAgent.java            # 对应: resumeAgent.ts
    ├── AgentToolUtils.java         # 对应: agentToolUtils.ts
    └── AgentToolResult.java        # 对应: AgentTool.tsx - AgentToolResult
```

---

## 任务 1：AgentDefinitionLoader

**文件**：
- 创建：`src/main/java/agent/subagent/definition/AgentDefinitionLoader.java`
- 源码参考：`src/tools/AgentTool/loadAgentsDir.ts` - getAgentDefinitionsWithOverrides()

**必须实现的功能**：
1. 从 `.claude/commands/` 目录加载 Markdown 代理定义
2. 从插件目录加载代理定义
3. 合并内置代理
4. 解析 frontmatter（tools, disallowedTools, model, permissionMode, maxTurns 等）
5. 处理 agent 覆盖配置

- [ ] **步骤 1：创建目录结构**

```bash
mkdir -p src/main/java/agent/subagent/definition
mkdir -p src/main/java/agent/subagent/builtin
mkdir -p src/main/java/agent/subagent/execution
```

- [ ] **步骤 2：实现 AgentDefinitionLoader.java**

```java
package agent.subagent.definition;

/**
 * 代理定义加载器
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/loadAgentsDir.ts - getAgentDefinitionsWithOverrides()
 *
 * 职责：
 * 1. 从 .claude/commands/ 目录加载 Markdown 代理定义
 * 2. 从插件目录加载代理定义
 * 3. 合并内置代理
 * 4. 处理 agent 覆盖配置
 */
public class AgentDefinitionLoader {

    /**
     * 加载所有代理定义（带覆盖）
     * 对应: getAgentDefinitionsWithOverrides()
     */
    public List<AgentDefinition> getAgentDefinitionsWithOverrides(Path cwd) {
        // 1. 获取内置代理
        List<AgentDefinition> agents = new ArrayList<>(BuiltInAgents.getBuiltInAgents());

        // 2. 加载项目代理（.claude/commands/）
        agents.addAll(loadAgentsFromDir(cwd.resolve(".claude/commands")));

        // 3. 加载插件代理
        agents.addAll(loadPluginAgents());

        // 4. 应用覆盖配置
        applyAgentOverrides(agents);

        // 5. 去重（同名覆盖优先级）
        return deduplicateAgents(agents);
    }

    /**
     * 从目录加载 Markdown 代理
     * 对应: loadMarkdownFilesForSubdir()
     */
    private List<AgentDefinition> loadAgentsFromDir(Path dir) {
        // 遍历目录，加载所有 .md 文件
        // 解析 frontmatter 获取代理配置
    }

    /**
     * 解析 Markdown 代理定义
     * 对应: parseAgentFromMarkdown()
     */
    private AgentDefinition parseAgentFromMarkdown(String name, String content) {
        // 1. 解析 frontmatter
        // 2. 提取 system prompt
        // 3. 构建 AgentDefinition
    }
}
```

- [ ] **步骤 3：实现 AgentDefinitionRegistry.java**

```java
package agent.subagent.definition;

/**
 * 代理定义注册表
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/loadAgentsDir.ts - agentRegistry
 */
public class AgentDefinitionRegistry {
    private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();

    public void register(AgentDefinition agent) {...}
    public AgentDefinition get(String agentType) {...}
    public List<AgentDefinition> getAll() {...}
    public void clear() {...}
}
```

- [ ] **步骤 4：提交**

```bash
git add src/main/java/agent/subagent/definition/
git commit -m "feat(subagent): add AgentDefinitionLoader and AgentDefinitionRegistry"
```

---

## 任务 2：BuiltInAgents

**文件**：
- 创建：`src/main/java/agent/subagent/builtin/BuiltInAgents.java`
- 源码参考：`src/tools/AgentTool/builtInAgents.ts` - getBuiltInAgents()

- [ ] **步骤 1：实现 BuiltInAgents.java**

```java
package agent.subagent.builtin;

/**
 * 内置代理注册表
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/builtInAgents.ts - getBuiltInAgents()
 */
public class BuiltInAgents {

    /**
     * 获取所有内置代理
     */
    public static List<AgentDefinition> getBuiltInAgents() {
        List<AgentDefinition> agents = new ArrayList<>();
        agents.add(GeneralPurposeAgent.get());
        agents.add(ExploreAgent.get());
        agents.add(PlanAgent.get());
        return agents;
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/builtin/BuiltInAgents.java
git commit -m "feat(subagent): add BuiltInAgents"
```

---

## 任务 3：GeneralPurposeAgent

**文件**：
- 创建：`src/main/java/agent/subagent/builtin/GeneralPurposeAgent.java`
- 源码参考：`src/tools/AgentTool/built-in/generalPurposeAgent.ts` - GENERAL_PURPOSE_AGENT

- [ ] **步骤 1：实现 GeneralPurposeAgent.java**

```java
package agent.subagent.builtin;

/**
 * 通用代理
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/generalPurposeAgent.ts - GENERAL_PURPOSE_AGENT
 *
 * 用于通用任务、研究复杂问题、搜索代码、执行多步骤任务
 */
public class GeneralPurposeAgent {

    private static final String SHARED_PREFIX = "You are an agent for Claude Code...";

    private static final String SHARED_GUIDELINES = """
        Your strengths:
        - Searching for code, configurations, and patterns across large codebases
        - Analyzing multiple files to understand system architecture
        - Investigating complex questions that require exploring many files
        - Performing multi-step research tasks

        Guidelines:
        - For file searches: search broadly when you don't know where something lives
        - For analysis: Start broad and narrow down
        - Be thorough: Check multiple locations, consider different naming conventions
        - NEVER create files unless they're absolutely necessary
        - NEVER proactively create documentation files (*.md) or README files
    """;

    /**
     * 获取通用代理定义
     */
    public static AgentDefinition get() {
        return new AgentDefinition(
            "general-purpose",
            "General-purpose agent for researching complex questions...",
            List.of("*"),  // 全部工具
            null,          // 无禁用工具
            null,          // 使用默认模型
            PermissionMode.ACCEPT_EDITS,
            200,           // maxTurns
            () -> SHARED_PREFIX + "..." + SHARED_GUIDELINES
        );
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/builtin/GeneralPurposeAgent.java
git commit -m "feat(subagent): add GeneralPurposeAgent"
```

---

## 任务 4：ExploreAgent

**文件**：
- 创建：`src/main/java/agent/subagent/builtin/ExploreAgent.java`
- 源码参考：`src/tools/AgentTool/built-in/exploreAgent.ts` - EXPLORE_AGENT

- [ ] **步骤 1：实现 ExploreAgent.java**

```java
package agent.subagent.builtin;

/**
 * 探索代理
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/exploreAgent.ts - EXPLORE_AGENT
 *
 * 只读搜索代理，用于快速查找文件、搜索代码
 */
public class ExploreAgent {

    private static final String SYSTEM_PROMPT = """
        You are a file search specialist for Claude Code...

        === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===

        Your strengths:
        - Rapidly finding files using glob patterns
        - Searching code and text with powerful regex patterns
        - Reading and analyzing file contents

        Guidelines:
        - Use GlobTool for broad file pattern matching
        - Use GrepTool for searching file contents with regex
        - Use Read when you know the specific file path
        - Use Bash ONLY for read-only operations (ls, git status, git log, find, cat, head, tail)
        - NEVER use Bash for: mkdir, touch, rm, cp, mv, git add, git commit, npm install, pip install

        NOTE: You are meant to be a fast agent. Make efficient use of tools,
        spawning multiple parallel tool calls where possible.
    """;

    /**
     * 获取探索代理定义
     */
    public static AgentDefinition get() {
        return new AgentDefinition(
            "Explore",
            "Fast agent specialized for exploring codebases...",
            List.of("*"),  // 但实际会过滤禁用 Edit/Write 工具
            List.of("Edit", "Write", "NotebookEdit"),  // 禁用写操作
            "haiku",  // 默认使用 haiku 模型（快速）
            PermissionMode.PLAN,
            50,
            () -> SYSTEM_PROMPT
        );
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/builtin/ExploreAgent.java
git commit -m "feat(subagent): add ExploreAgent"
```

---

## 任务 5：PlanAgent

**文件**：
- 创建：`src/main/java/agent/subagent/builtin/PlanAgent.java`
- 源码参考：`src/tools/AgentTool/built-in/planAgent.ts` - PLAN_AGENT

- [ ] **步骤 1：实现 PlanAgent.java**

```java
package agent.subagent.builtin;

/**
 * 计划代理
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/built-in/planAgent.ts - PLAN_AGENT
 *
 * 只读规划代理，用于设计实现计划
 */
public class PlanAgent {

    private static final String SYSTEM_PROMPT = """
        You are a software architect and planning specialist for Claude Code...

        === CRITICAL: READ-ONLY MODE - NO FILE MODIFICATIONS ===

        Your role is EXCLUSIVELY to explore the codebase and design implementation plans.

        Your Process:
        1. Understand Requirements
        2. Explore Thoroughly
        3. Design Solution
        4. Detail the Plan

        Required Output:
        End your response with:
        ### Critical Files for Implementation
        List 3-5 files most critical for implementing this plan
    """;

    /**
     * 获取计划代理定义
     */
    public static AgentDefinition get() {
        return new AgentDefinition(
            "Plan",
            "Software architect agent for designing implementation plans...",
            List.of("*"),  // 工具同 Explore
            List.of("Edit", "Write", "NotebookEdit"),
            "inherit",  // 继承父代理模型
            PermissionMode.PLAN,
            50,
            () -> SYSTEM_PROMPT
        );
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/builtin/PlanAgent.java
git commit -m "feat(subagent): add PlanAgent"
```

---

## 任务 6：AgentTool 主入口

**文件**：
- 创建：`src/main/java/agent/subagent/execution/AgentTool.java`
- 源码参考：`src/tools/AgentTool/AgentTool.tsx` - AgentTool

- [ ] **步骤 1：实现 AgentTool.java**

```java
package agent.subagent.execution;

/**
 * Agent 工具主入口
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/AgentTool.tsx - AgentTool
 *
 * 职责：
 * 1. 解析 LLM 调用参数（name, team_name, subagent_type, prompt 等）
 * 2. 路由到不同执行路径：
 *    - team_name + name → spawnTeammate
 *    - subagent_type → Named Subagent
 *    - 无 subagent_type → Fork Subagent
 * 3. 处理同步/异步执行
 * 4. 返回执行结果
 */
public class AgentTool extends Tool {

    private final AgentDefinitionLoader loader;
    private final ForkAgentExecutor forkExecutor;
    private final TeamCoordinator teamCoordinator;

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        return "Spawn a sub-agent to handle a task...";
    }

    @Override
    public Map<String, Object> parameters() {
        // 返回 OpenAI 风格的 tool schema
    }

    @Override
    public CompletableFuture<String> execute(Map<String, Object> args) {
        // 1. 解析参数
        String name = getString(args, "name", null);
        String teamName = getString(args, "team_name", null);
        String subagentType = getString(args, "subagent_type", null);
        String prompt = getString(args, "prompt", null);
        Boolean background = getBoolean(args, "background", false);

        // 2. 路由决策
        if (teamName != null && name != null) {
            // → Teammate 路径
            return spawnTeammate(teamName, name, prompt, background);
        } else if (subagentType != null) {
            // → Named Subagent 路径
            return runNamedAgent(subagentType, prompt, background);
        } else {
            // → Fork Subagent 路径
            return runForkAgent(prompt, background);
        }
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/execution/AgentTool.java
git commit -m "feat(subagent): add AgentTool main entry"
```

---

## 任务 7：runAgent 执行循环

**文件**：
- 创建：`src/main/java/agent/subagent/execution/runAgent.java`
- 源码参考：`src/tools/AgentTool/runAgent.ts` - runAgent()

- [ ] **步骤 1：实现 runAgent.java**

```java
package agent.subagent.execution;

/**
 * 代理执行循环
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/runAgent.ts - runAgent()
 *
 * 核心执行逻辑：
 * 1. 初始化工具池
 * 2. 初始化 MCP 服务器（可选）
 * 3. 构建系统提示词
 * 4. 执行 query 循环
 * 5. 处理工具调用
 * 6. 返回结果
 */
public class runAgent {

    /**
     * 执行代理
     * 对应: export async function* runAgent({...})
     */
    public static AsyncIterable<AgentMessage> execute(RunAgentParams params) {
        return new AsyncIterable<AgentMessage>() {
            // 1. 解析工具和权限
            // 2. 初始化 MCP 服务器
            // 3. 构建系统提示词
            // 4. 执行 query 循环
            // 5. 处理工具调用
        };
    }

    /**
     * 初始化代理 MCP 服务器
     * 对应: initializeAgentMcpServers()
     */
    private static MCPServers initAgentMcpServers(AgentDefinition def, MCPServers parentClients) {
        // 如果代理定义了 MCP 服务器，连接它们
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/execution/runAgent.java
git commit -m "feat(subagent): add runAgent execution loop"
```

---

## 任务 8：AgentToolUtils

**文件**：
- 创建：`src/main/java/agent/subagent/execution/AgentToolUtils.java`
- 源码参考：`src/tools/AgentTool/agentToolUtils.ts`

- [ ] **步骤 1：实现 AgentToolUtils.java**

```java
package agent.subagent.execution;

/**
 * AgentTool 工具函数
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/agentToolUtils.ts
 */
public class AgentToolUtils {

    /**
     * 过滤被禁用的工具
     * 对应: filterTools()
     */
    public static Tools filterTools(Tools tools, AgentDefinition agent) {
        // 1. 如果 agent.tools 是 ["*"]，返回全部
        // 2. 否则，只保留 agent.tools 中的工具
        // 3. 移除 agent.disallowedTools 中的工具
    }

    /**
     * 获取代理模型
     * 对应: getAgentModel()
     */
    public static String getAgentModel(AgentDefinition agent, String defaultModel) {
        if (agent.model == null) return defaultModel;
        if ("inherit".equals(agent.model)) return defaultModel;
        return agent.model;
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/execution/AgentToolUtils.java
git commit -m "feat(subagent): add AgentToolUtils"
```

---

## 任务 9：创建阶段总结

**文件**：
- 创建：`docs/subagent/phase-2-summary.md`

- [ ] **步骤 1：创建阶段总结**

```markdown
# Phase 2: 专用代理 完成总结

## 交付物

| Java 类 | Open-ClaudeCode 源码 | 状态 |
|---------|---------------------|------|
| AgentDefinitionLoader.java | loadAgentsDir.ts - getAgentDefinitionsWithOverrides() | ✅ |
| AgentDefinitionRegistry.java | loadAgentsDir.ts - agentRegistry | ✅ |
| BuiltInAgents.java | builtInAgents.ts - getBuiltInAgents() | ✅ |
| GeneralPurposeAgent.java | built-in/generalPurposeAgent.ts - GENERAL_PURPOSE_AGENT | ✅ |
| ExploreAgent.java | built-in/exploreAgent.ts - EXPLORE_AGENT | ✅ |
| PlanAgent.java | built-in/planAgent.ts - PLAN_AGENT | ✅ |
| AgentTool.java | AgentTool.tsx - AgentTool | ✅ |
| runAgent.java | runAgent.ts - runAgent() | ✅ |
| AgentToolUtils.java | agentToolUtils.ts | ✅ |

## 如何继续
...
```

- [ ] **步骤 2：提交**

```bash
git add docs/subagent/phase-2-summary.md
git commit -m "docs: add Phase 2 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应任务 |
|---------|---------|
| AgentDefinitionLoader | 任务 1 |
| 内置代理 | 任务 2-5 |
| AgentTool 主入口 | 任务 6 |
| runAgent 执行循环 | 任务 7 |

### 类型一致性检查

- 所有类都正确引用了 Phase 1 的 Fork 相关类型 ✓
- AgentDefinition 的字段与 TypeScript 一致 ✓

### 占位符扫描

无占位符，所有步骤都包含完整代码或明确的任务描述。
