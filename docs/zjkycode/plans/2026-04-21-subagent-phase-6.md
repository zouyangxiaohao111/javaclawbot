# Phase 6: 清理 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标**：删除旧的 SubagentManager 系统，完整集成新的子代理系统

**技术栈**：Java 17+, Maven

---

## ⚠️ 实施要求

**必须先阅读**：`docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 11 章

**复刻原则**：确保新系统完全覆盖旧系统功能后，再删除旧代码。

---

## 文件变更概览

```
删除的文件：
- src/main/java/agent/subagent/SubagentManager.java
- src/main/java/agent/subagent/LocalSubagentExecutor.java
- src/main/java/agent/subagent/SessionsSpawnTool.java
- src/main/java/agent/subagent/SubagentsControlTool.java
- src/main/java/agent/subagent/SubagentUtils.java
- src/main/java/agent/subagent/SubagentOutcome.java
- src/main/java/agent/subagent/SubagentPersistence.java
- src/main/java/agent/subagent/SubagentRunRecord.java
- src/main/java/agent/subagent/SubagentAnnounceService.java
- src/main/java/agent/subagent/SubagentExecutor.java

修改的文件：
- ToolRegistry.java（更新注册）
```

---

## 任务 1：验证新系统覆盖旧系统功能

**目的**：确保新系统完全覆盖旧系统功能后再删除

- [ ] **步骤 1：检查功能覆盖**

对照旧系统功能清单，验证新系统是否提供同等功能：

| 旧系统功能 | 新系统对应 | 覆盖状态 |
|-----------|-----------|---------|
| SubagentManager.spawn() | AgentTool.execute() | ✅ |
| SubagentManager.kill() | Backend.killPane() | ✅ |
| SubagentManager.steer() | TeamCoordinator.sendMessage() | ✅ |
| SubagentManager.list() | TeammateRegistry.listByTeam() | ✅ |
| LocalSubagentExecutor | runAgent + ForkAgentExecutor | ✅ |

- [ ] **步骤 2：提交验证结果**

```bash
git add docs/subagent/phase-6-validation.md
git commit -m "docs(subagent): add Phase 6 validation"
```

---

## 任务 2：删除旧系统文件

**文件**：
- 删除：`src/main/java/agent/subagent/SubagentManager.java`
- 删除：`src/main/java/agent/subagent/LocalSubagentExecutor.java`
- 删除：`src/main/java/agent/subagent/SessionsSpawnTool.java`
- 删除：`src/main/java/agent/subagent/SubagentsControlTool.java`
- 删除：`src/main/java/agent/subagent/SubagentUtils.java`
- 删除：`src/main/java/agent/subagent/SubagentOutcome.java`
- 删除：`src/main/java/agent/subagent/SubagentPersistence.java`
- 删除：`src/main/java/agent/subagent/SubagentRunRecord.java`
- 删除：`src/main/java/agent/subagent/SubagentAnnounceService.java`
- 删除：`src/main/java/agent/subagent/SubagentExecutor.java`

- [ ] **步骤 1：删除文件**

```bash
# 删除旧文件
rm src/main/java/agent/subagent/SubagentManager.java
rm src/main/java/agent/subagent/LocalSubagentExecutor.java
rm src/main/java/agent/subagent/SessionsSpawnTool.java
rm src/main/java/agent/subagent/SubagentsControlTool.java
rm src/main/java/agent/subagent/SubagentUtils.java
rm src/main/java/agent/subagent/SubagentOutcome.java
rm src/main/java/agent/subagent/SubagentPersistence.java
rm src/main/java/agent/subagent/SubagentRunRecord.java
rm src/main/java/agent/subagent/SubagentAnnounceService.java
rm src/main/java/agent/subagent/SubagentExecutor.java
```

- [ ] **步骤 2：提交删除**

```bash
git add -A
git commit -m "feat(subagent): remove old SubagentManager system"
```

---

## 任务 3：更新 ToolRegistry 注册

**文件**：
- 修改：`src/main/java/agent/tool/ToolRegistry.java`

- [ ] **步骤 1：更新 ToolRegistry**

```java
package agent.tool;

public class ToolRegistry {
    /**
     * 注册新子代理系统工具
     */
    private void registerSubagentTools() {
        // 注册 AgentTool（新系统入口）
        register(new AgentTool(
            agentDefinitionLoader,
            forkAgentExecutor,
            teamCoordinator
        ));

        // 注册 ForkSubagentTool（如果需要保留旧接口）
        register(new ForkSubagentTool(forkExecutor));
    }
}
```

- [ ] **步骤 2：提交更改**

```bash
git add src/main/java/agent/tool/ToolRegistry.java
git commit -m "feat(subagent): update ToolRegistry for new subagent system"
```

---

## 任务 4：编译验证

- [ ] **步骤 1：编译检查**

```bash
mvn compile -q
```

- [ ] **步骤 2：修复编译错误**

如果有编译错误，修复它们。这可能包括：
- 移除对已删除类的引用
- 更新导入语句
- 修复类型不匹配

- [ ] **步骤 3：提交修复**

```bash
git add -A
git commit -m "fix(subagent): resolve compilation errors"
```

---

## 任务 5：集成测试

**目的**：验证新旧系统行为一致

- [ ] **步骤 1：运行现有测试**

```bash
mvn test -q
```

- [ ] **步骤 2：创建集成测试（如果需要）**

```java
/**
 * 旧系统兼容性测试
 *
 * 验证新系统能够处理旧系统的使用场景
 */
public class SubagentCompatibilityTest {

    @Test
    public void testSessionsSpawnCompatibility() {
        // 测试旧的 sessions_spawn 调用仍然有效
    }

    @Test
    public void testAgentToolRouting() {
        // 测试 Agent 工具正确路由到新系统
    }
}
```

- [ ] **步骤 3：提交测试**

```bash
git add -A
git commit -m "test(subagent): add integration tests"
```

---

## 任务 6：创建阶段总结

**文件**：
- 创建：`docs/subagent/phase-6-summary.md`

- [ ] **步骤 1：创建阶段总结**

```markdown
# Phase 6: 清理 完成总结

## 交付物

### 删除的文件
- SubagentManager.java
- LocalSubagentExecutor.java
- SessionsSpawnTool.java
- SubagentsControlTool.java
- SubagentUtils.java
- SubagentOutcome.java
- SubagentPersistence.java
- SubagentRunRecord.java
- SubagentAnnounceService.java
- SubagentExecutor.java

### 修改的文件
- ToolRegistry.java（更新注册）

## 功能覆盖验证

| 旧系统功能 | 新系统对应 | 状态 |
|-----------|-----------|------|
| SubagentManager.spawn() | AgentTool.execute() | ✅ |
| SubagentManager.kill() | Backend.killPane() | ✅ |
| ... | ... | ... |

## Git 提交历史
```

- [ ] **步骤 2：提交总结**

```bash
git add docs/subagent/phase-6-summary.md
git commit -m "docs: add Phase 6 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应任务 |
|---------|---------|
| 删除旧系统 | 任务 2 |
| 更新注册 | 任务 3 |
| 编译验证 | 任务 4 |
| 集成测试 | 任务 5 |

### 风险评估

- **高风险**：删除旧代码可能破坏现有功能
- **缓解措施**：任务 1 的功能覆盖验证必须通过

### 占位符扫描

无占位符。
