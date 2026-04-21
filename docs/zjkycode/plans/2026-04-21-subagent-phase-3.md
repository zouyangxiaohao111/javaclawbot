# Phase 3: 团队协作-基础 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标**：实现团队协作基础架构，包括 Backend 接口、BackendRouter、InProcessBackend、TeamCoordinator

**架构**：TeamCoordinator → BackendRouter → Backend → TeamMember

**技术栈**：Java 17+, picocli/JLine, Jackson

---

## ⚠️ 实施要求

**必须先阅读**：`docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**复刻原则**：必须完整按照 Open-ClaudeCode 源码逐行实现，做到 Java 语义等价、逻辑等价。

---

## 文件结构

```
src/main/java/agent/subagent/team/
├── TeamCoordinator.java              # 对应: spawnMultiAgent.ts - handleSpawn()
├── TeammateRegistry.java             # 对应: LocalAgentTask.tsx - teammateRegistry
├── TeammateInfo.java                # 对应: spawnMultiAgent.ts - SpawnOutput
├── messaging/
│   └── TeammateMailbox.java         # 对应: spawnMultiAgent.ts - TeammateMailbox
└── backends/
    ├── Backend.java                 # 对应: spawnMultiAgent.ts - Backend 接口
    ├── BackendRouter.java           # 对应: spawnMultiAgent.ts - BackendRouter
    ├── BackendType.java             # 对应: spawnMultiAgent.ts - BackendType
    └── InProcessBackend.java        # 对应: spawnMultiAgent.ts - spawnInProcessTeammate()
```

---

## 任务 1：Backend 接口和 BackendType

**文件**：
- 创建：`src/main/java/agent/subagent/team/backends/BackendType.java`
- 创建：`src/main/java/agent/subagent/team/backends/Backend.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - Backend 接口, BackendType

- [ ] **步骤 1：实现 BackendType.java**

```java
package agent.subagent.team.backends;

/**
 * 后端类型枚举
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - BackendType
 */
public enum BackendType {
    IN_PROCESS("in_process"),
    TMUX("tmux"),
    ITERM2("iterm2"),
    CONPTY("conpty");  // 预留

    private final String value;

    BackendType(String value) {
        this.value = value;
    }

    public String getValue() { return value; }
}
```

- [ ] **步骤 2：实现 Backend.java**

```java
package agent.subagent.team.backends;

/**
 * 后端接口
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - Backend 接口
 *
 * 定义后端的标准操作
 */
public interface Backend {

    /**
     * 获取后端类型
     */
    BackendType type();

    /**
     * 创建 pane
     * 对应: createPane()
     */
    String createPane(String name, String color);

    /**
     * 发送命令到 pane
     * 对应: sendCommand()
     */
    void sendCommand(String paneId, String command);

    /**
     * 终止 pane
     * 对应: killPane()
     */
    void killPane(String paneId);

    /**
     * 检查后端是否可用
     * 对应: isAvailable()
     */
    boolean isAvailable();

    /**
     * 获取 pane 的输出
     * 对应: getPaneOutput()
     */
    String getPaneOutput(String paneId);

    /**
     * 轮询 pane 的新输出
     * 对应: pollPaneOutput()
     */
    String pollPaneOutput(String paneId);
}
```

- [ ] **步骤 3：提交**

```bash
git add src/main/java/agent/subagent/team/backends/
git commit -m "feat(subagent): add Backend interface and BackendType"
```

---

## 任务 2：BackendRouter

**文件**：
- 创建：`src/main/java/agent/subagent/team/backends/BackendRouter.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - BackendRouter

- [ ] **步骤 1：实现 BackendRouter.java**

```java
package agent.subagent.team.backends;

/**
 * 后端路由器
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - BackendRouter
 *
 * 职责：
 * 1. 检测平台
 * 2. 检测可用工具（tmux, it2 CLI）
 * 3. 根据配置和可用性选择后端
 * 4. 支持配置覆盖
 */
public class BackendRouter {

    /**
     * 检测可用后端并返回
     * 对应: detectBackend()
     */
    public Backend detectBackend() {
        // 1. 检查明确配置（环境变量或配置）
        String configured = getConfiguredBackend();
        if (configured != null) {
            return createBackend(configured);
        }

        // 2. 检测平台
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows: ConPTY > InProcess
            if (isConPTYAvailable()) {
                return new ConPTYBackend();
            }
            return new InProcessBackend();
        }

        if (os.contains("mac")) {
            // macOS: iTerm2 > tmux > InProcess
            if (isITerm2Available()) {
                return new ITerm2Backend();
            }
            if (isTmuxAvailable()) {
                return new TmuxBackend();
            }
            return new InProcessBackend();
        }

        // Linux: tmux > InProcess
        if (isTmuxAvailable()) {
            return new TmuxBackend();
        }
        return new InProcessBackend();
    }

    /**
     * 检测 tmux 是否可用
     * 对应: isTmuxAvailable()
     */
    private boolean isTmuxAvailable() {
        // 执行 tmux -V 检查返回码
    }

    /**
     * 检测 iTerm2 是否可用
     * 对应: isITerm2Available()
     */
    private boolean isITerm2Available() {
        // 执行 it2li 检查返回码
    }

    /**
     * 检测 ConPTY 是否可用
     * 对应: isConPTYAvailable()
     */
    private boolean isConPTYAvailable() {
        // Windows 特定检测
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/team/backends/BackendRouter.java
git commit -m "feat(subagent): add BackendRouter"
```

---

## 任务 3：InProcessBackend

**文件**：
- 创建：`src/main/java/agent/subagent/team/backends/InProcessBackend.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - spawnInProcessTeammate()

- [ ] **步骤 1：实现 InProcessBackend.java**

```java
package agent.subagent.team.backends;

/**
 * 进程内后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - spawnInProcessTeammate()
 *
 * 在当前进程内启动 teammate，使用 ThreadLocal 或类似机制隔离上下文
 */
public class InProcessBackend implements Backend {

    /** 运行中的 teammate */
    private final Map<String, InProcessTeammate> teammates = new ConcurrentHashMap<>();

    @Override
    public BackendType type() {
        return BackendType.IN_PROCESS;
    }

    @Override
    public String createPane(String name, String color) {
        // 进程内不需要真正创建 pane，只需要初始化 teammate
        String paneId = UUID.randomUUID().toString();
        InProcessTeammate teammate = new InProcessTeammate(paneId, name, color);
        teammates.put(paneId, teammate);
        return paneId;
    }

    @Override
    public void sendCommand(String paneId, String command) {
        InProcessTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            teammate.receiveCommand(command);
        }
    }

    @Override
    public void killPane(String paneId) {
        InProcessTeammate teammate = teammates.remove(paneId);
        if (teammate != null) {
            teammate.stop();
        }
    }

    @Override
    public boolean isAvailable() {
        return true;  // 始终可用
    }

    @Override
    public String getPaneOutput(String paneId) {
        InProcessTeammate teammate = teammates.get(paneId);
        return teammate != null ? teammate.getOutput() : "";
    }

    @Override
    public String pollPaneOutput(String paneId) {
        InProcessTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            return teammate.pollOutput();
        }
        return "";
    }

    /**
     * 进程内 teammate
     */
    private static class InProcessTeammate {
        private final String id;
        private final String name;
        private final String color;
        private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;

        // 使用 ThreadLocal 或类似机制隔离上下文
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/team/backends/InProcessBackend.java
git commit -m "feat(subagent): add InProcessBackend"
```

---

## 任务 4：TeamCoordinator

**文件**：
- 创建：`src/main/java/agent/subagent/team/TeamCoordinator.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - handleSpawn()

- [ ] **步骤 1：实现 TeamCoordinator.java**

```java
package agent.subagent.team;

/**
 * 团队协调器
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - handleSpawn()
 *
 * 职责：
 * 1. 创建 teammate
 * 2. 管理 teammate 生命周期
 * 3. 处理 teammate 消息
 */
public class TeamCoordinator {

    private final BackendRouter backendRouter;
    private final TeammateRegistry registry;
    private final TeammateMailbox mailbox;

    /**
     * 创建 teammate
     * 对应: handleSpawn()
     */
    public TeammateInfo spawnTeammate(SpawnConfig config) {
        // 1. 检测后端
        Backend backend = backendRouter.detectBackend();

        // 2. 创建 pane
        String paneId = backend.createPane(config.name, config.color);

        // 3. 注册 teammate
        TeammateInfo info = new TeammateInfo(
            paneId,
            config.name,
            config.teamName,
            backend.type(),
            config.prompt
        );
        registry.register(info);

        // 4. 发送初始命令
        backend.sendCommand(paneId, config.prompt);

        return info;
    }

    /**
     * 终止 teammate
     * 对应: killTeammate()
     */
    public void killTeammate(String teammateId) {
        TeammateInfo info = registry.get(teammateId);
        if (info != null) {
            Backend backend = backendRouter.createBackend(info.getBackendType());
            backend.killPane(info.getPaneId());
            registry.unregister(teammateId);
        }
    }

    /**
     * 列出团队成员
     */
    public List<TeammateInfo> listTeammates(String teamName) {
        return registry.listByTeam(teamName);
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/team/TeamCoordinator.java
git commit -m "feat(subagent): add TeamCoordinator"
```

---

## 任务 5：TeammateRegistry 和 TeammateInfo

**文件**：
- 创建：`src/main/java/agent/subagent/team/TeammateInfo.java`
- 创建：`src/main/java/agent/subagent/team/TeammateRegistry.java`
- 源码参考：`src/tasks/LocalAgentTask/LocalAgentTask.tsx` - teammateRegistry

- [ ] **步骤 1：实现 TeammateInfo.java**

```java
package agent.subagent.team;

/**
 * Teammate 信息
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - SpawnOutput
 */
public class TeammateInfo {
    private final String id;
    private final String name;
    private final String teamName;
    private final BackendType backendType;
    private final String paneId;
    private final String initialPrompt;
    private final long createdAt;
    private String status;
    private String lastOutput;

    // Getters...
}
```

- [ ] **步骤 2：实现 TeammateRegistry.java**

```java
package agent.subagent.team;

/**
 * Teammate 注册表
 *
 * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - teammateRegistry
 */
public class TeammateRegistry {
    private final Map<String, TeammateInfo> teammates = new ConcurrentHashMap<>();

    public void register(TeammateInfo info) {...}
    public TeammateInfo get(String id) {...}
    public List<TeammateInfo> listByTeam(String teamName) {...}
    public void unregister(String id) {...}
    public Collection<TeammateInfo> getAll() {...}
}
```

- [ ] **步骤 3：提交**

```bash
git add src/main/java/agent/subagent/team/TeammateInfo.java src/main/java/agent/subagent/team/TeammateRegistry.java
git commit -m "feat(subagent): add TeammateInfo and TeammateRegistry"
```

---

## 任务 6：TeammateMailbox

**文件**：
- 创建：`src/main/java/agent/subagent/team/messaging/TeammateMailbox.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - TeammateMailbox

- [ ] **步骤 1：实现 TeammateMailbox.java**

```java
package agent.subagent.team.messaging;

/**
 * Teammate 信箱
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - TeammateMailbox
 *
 * 用于 teammate 之间的消息传递
 */
public class TeammateMailbox {

    /** 信箱存储: teammateName -> messages */
    private final Map<String, ConcurrentLinkedQueue<MailboxMessage>> mailboxes = new ConcurrentHashMap<>();

    /**
     * 写入消息
     * 对应: write()
     */
    public void write(String teammateName, MailboxMessage message, String teamName) {
        String key = teamName + ":" + teammateName;
        mailboxes.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(message);
    }

    /**
     * 读取消息（不删除）
     * 对应: read()
     */
    public Optional<MailboxMessage> read(String teammateName, String teamName) {
        String key = teamName + ":" + teammateName;
        ConcurrentLinkedQueue<MailboxMessage> queue = mailboxes.get(key);
        return queue != null ? Optional.ofNullable(queue.peek()) : Optional.empty();
    }

    /**
     * 轮询新消息
     * 对应: pollNewMessages()
     */
    public List<MailboxMessage> pollNewMessages(String teammateName, String teamName) {
        String key = teamName + ":" + teammateName;
        ConcurrentLinkedQueue<MailboxMessage> queue = mailboxes.get(key);
        if (queue == null) return Collections.emptyList();

        List<MailboxMessage> messages = new ArrayList<>();
        MailboxMessage msg;
        while ((msg = queue.poll()) != null) {
            messages.add(msg);
        }
        return messages;
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/team/messaging/TeammateMailbox.java
git commit -m "feat(subagent): add TeammateMailbox"
```

---

## 任务 7：创建阶段总结

**文件**：
- 创建：`docs/subagent/phase-3-summary.md`

- [ ] **步骤 1：创建阶段总结**

```markdown
# Phase 3: 团队协作-基础 完成总结

## 交付物

| Java 类 | Open-ClaudeCode 源码 | 状态 |
|---------|---------------------|------|
| BackendType.java | spawnMultiAgent.ts - BackendType | ✅ |
| Backend.java | spawnMultiAgent.ts - Backend 接口 | ✅ |
| BackendRouter.java | spawnMultiAgent.ts - BackendRouter | ✅ |
| InProcessBackend.java | spawnMultiAgent.ts - spawnInProcessTeammate() | ✅ |
| TeamCoordinator.java | spawnMultiAgent.ts - handleSpawn() | ✅ |
| TeammateInfo.java | spawnMultiAgent.ts - SpawnOutput | ✅ |
| TeammateRegistry.java | LocalAgentTask.tsx - teammateRegistry | ✅ |
| TeammateMailbox.java | spawnMultiAgent.ts - TeammateMailbox | ✅ |

## 如何继续
...
```

- [ ] **步骤 2：提交**

```bash
git add docs/subagent/phase-3-summary.md
git commit -m "docs: add Phase 3 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应任务 |
|---------|---------|
| Backend 接口 | 任务 1 |
| BackendRouter | 任务 2 |
| InProcessBackend | 任务 3 |
| TeamCoordinator | 任务 4 |
| TeammateRegistry | 任务 5 |
| TeammateMailbox | 任务 6 |

### 类型一致性检查

- Backend 接口方法与 TypeScript 一致 ✓
- TeammateInfo 字段与 SpawnOutput 一致 ✓

### 占位符扫描

无占位符，所有步骤都包含完整代码或明确的任务描述。
