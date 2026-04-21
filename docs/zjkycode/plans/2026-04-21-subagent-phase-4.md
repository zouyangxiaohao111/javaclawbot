# Phase 4: 分屏支持 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标**：实现 tmux 和 iTerm2 分屏后端

**架构**：BackendRouter → TmuxBackend / ITerm2Backend

**技术栈**：Java 17+, tmux, iTerm2 CLI

---

## ⚠️ 实施要求

**必须先阅读**：`docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**复刻原则**：必须完整按照 Open-ClaudeCode 源码逐行实现，做到 Java 语义等价、逻辑等价。

---

## 文件结构

```
src/main/java/agent/subagent/team/backends/
├── tmux/
│   ├── TmuxBackend.java             # 对应: spawnMultiAgent.ts - spawnTmuxTeammate()
│   ├── TmuxSession.java             # 对应: spawnMultiAgent.ts - TmuxSession
│   └── TmuxPane.java               # 对应: spawnMultiAgent.ts - TmuxPane
└── iterm2/
    ├── ITerm2Backend.java           # 对应: spawnMultiAgent.ts - spawnITerm2Teammate()
    ├── ITerm2Session.java          # 对应: spawnMultiAgent.ts - ITerm2Session
    └── ITerm2Pane.java            # 对应: spawnMultiAgent.ts - ITerm2Pane
```

---

## 任务 1：TmuxBackend

**文件**：
- 创建：`src/main/java/agent/subagent/team/backends/tmux/TmuxBackend.java`
- 创建：`src/main/java/agent/subagent/team/backends/tmux/TmuxSession.java`
- 创建：`src/main/java/agent/subagent/team/backends/tmux/TmuxPane.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - spawnTmuxTeammate(), TmuxSession, TmuxPane

- [ ] **步骤 1：实现 TmuxSession.java**

```java
package agent.subagent.team.backends.tmux;

/**
 * Tmux 会话
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - TmuxSession
 */
public class TmuxSession {

    private final String sessionId;
    private final String sessionName;
    private final Path workdir;
    private final List<TmuxPane> panes = new ArrayList<>();

    /**
     * 创建 tmux 会话
     * 对应: new TmuxSession()
     */
    public TmuxSession(String sessionName, Path workdir) {
        this.sessionId = UUID.randomUUID().toString();
        this.sessionName = sessionName;
        this.workdir = workdir;

        // 执行 tmux new-session -s sessionName -c workdir
        execTmux("new-session", "-s", sessionName, "-c", workdir.toString());
    }

    /**
     * 创建分屏
     * 对应: splitWindow()
     */
    public TmuxPane splitWindow(String direction) {
        String paneId = UUID.randomUUID().toString();
        String[] cmd = direction.equals("vertical")
            ? new String[]{"split-window", "-v", "-t", sessionName}
            : new String[]{"split-window", "-h", "-t", sessionName};

        execTmux(cmd);
        TmuxPane pane = new TmuxPane(paneId, sessionName, direction);
        panes.add(pane);
        return pane;
    }

    /**
     * 发送命令到会话
     */
    public void sendKeys(String command) {
        execTmux("send-keys", "-t", sessionName, command, "Enter");
    }

    /**
     * 关闭会话
     */
    public void kill() {
        execTmux("kill-session", "-t", sessionName);
    }

    private void execTmux(String... args) {
        // 执行 tmux 命令
    }
}
```

- [ ] **步骤 2：实现 TmuxPane.java**

```java
package agent.subagent.team.backends.tmux;

/**
 * Tmux Pane
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - TmuxPane
 */
public class TmuxPane {

    private final String paneId;
    private final String sessionName;
    private final String direction;
    private final StringBuilder outputBuffer = new StringBuilder();

    /**
     * 捕获 pane 内容
     * 对应: capturePane()
     */
    public String capturePane() {
        // 执行 tmux capture-pane -t sessionName:paneIndex -p
    }

    /**
     * 发送命令到 pane
     * 对应: sendCommand()
     */
    public void sendCommand(String command) {
        // 执行 tmux send-keys -t sessionName:paneIndex command Enter
    }

    /**
     * 调整 pane 大小
     * 对应: resizePane()
     */
    public void resizePane(int width, int height) {
        // 执行 tmux resize-pane -t sessionName:paneIndex -x width -y height
    }
}
```

- [ ] **步骤 3：实现 TmuxBackend.java**

```java
package agent.subagent.team.backends.tmux;

/**
 * Tmux 后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - spawnTmuxTeammate()
 */
public class TmuxBackend implements Backend {

    private final Map<String, TmuxSession> sessions = new ConcurrentHashMap<>();

    @Override
    public BackendType type() {
        return BackendType.TMUX;
    }

    @Override
    public String createPane(String name, String color) {
        // 1. 创建或获取 session
        String sessionName = "claude-" + name;
        TmuxSession session = sessions.computeIfAbsent(sessionName,
            k -> new TmuxSession(sessionName, Paths.get(System.getProperty("user.dir"))));

        // 2. 创建分屏 pane
        TmuxPane pane = session.splitWindow("horizontal");

        return pane.getPaneId();
    }

    @Override
    public void sendCommand(String paneId, String command) {
        // 查找 pane 并发送命令
    }

    @Override
    public void killPane(String paneId) {
        // 终止指定 pane
    }

    @Override
    public boolean isAvailable() {
        // 检测 tmux 是否可用
        return isTmuxInstalled();
    }

    @Override
    public String getPaneOutput(String paneId) {
        // 获取 pane 输出
    }

    @Override
    public String pollPaneOutput(String paneId) {
        // 轮询 pane 新输出
    }

    private boolean isTmuxInstalled() {
        try {
            ProcessResult r = Runtime.getRuntime().exec(new String[]{"tmux", "-V"});
            return r.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **步骤 4：提交**

```bash
git add src/main/java/agent/subagent/team/backends/tmux/
git commit -m "feat(subagent): add TmuxBackend"
```

---

## 任务 2：ITerm2Backend

**文件**：
- 创建：`src/main/java/agent/subagent/team/backends/iterm2/ITerm2Backend.java`
- 创建：`src/main/java/agent/subagent/team/backends/iterm2/ITerm2Session.java`
- 创建：`src/main/java/agent/subagent/team/backends/iterm2/ITerm2Pane.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - spawnITerm2Teammate(), ITerm2Session, ITerm2Pane

- [ ] **步骤 1：实现 ITerm2Session.java**

```java
package agent.subagent.team.backends.iterm2;

/**
 * iTerm2 会话
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - ITerm2Session
 */
public class ITerm2Session {

    private final String sessionId;
    private final String sessionName;

    /**
     * 创建 iTerm2 分屏
     * 对应: splitVertical(), splitHorizontal()
     */
    public ITerm2Pane splitVertical(ITerm2Pane parent) {
        // 使用 it2-api 脚本创建垂直分屏
    }

    public ITerm2Pane splitHorizontal(ITerm2Pane parent) {
        // 使用 it2-api 脚本创建水平分屏
    }

    /**
     * 发送命令
     */
    public void sendCommand(String command) {
        // 使用 it2-profi/send-text 发送命令
    }

    /**
     * 关闭会话
     */
    public void close() {
        // 使用 it2-close-tab 关闭
    }
}
```

- [ ] **步骤 2：实现 ITerm2Pane.java**

```java
package agent.subagent.team.backends.iterm2;

/**
 * iTerm2 Pane
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - ITerm2Pane
 */
public class ITerm2Pane {

    private final String paneId;
    private final String sessionId;
    private final String direction;

    /**
     * 捕获 pane 内容
     */
    public String capture() {
        // 使用 it2-profi/capture 获取内容
    }

    /**
     * 发送文本
     */
    public void sendText(String text) {
        // 使用 it2-profi/send-text 发送
    }
}
```

- [ ] **步骤 3：实现 ITerm2Backend.java**

```java
package agent.subagent.team.backends.iterm2;

/**
 * iTerm2 后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - spawnITerm2Teammate()
 */
public class ITerm2Backend implements Backend {

    private final Map<String, ITerm2Session> sessions = new ConcurrentHashMap<>();

    @Override
    public BackendType type() {
        return BackendType.ITERM2;
    }

    @Override
    public String createPane(String name, String color) {
        // 1. 使用 it2-api 创建新标签页或分屏
        // 2. 返回 pane ID
    }

    @Override
    public void sendCommand(String paneId, String command) {
        // 使用 it2-profi/send-text 发送命令
    }

    @Override
    public void killPane(String paneId) {
        // 使用 it2-close-pane 关闭
    }

    @Override
    public boolean isAvailable() {
        return isITerm2Installed();
    }

    private boolean isITerm2Installed() {
        // 检查 it2li 或 it2-api 是否可用
    }
}
```

- [ ] **步骤 4：提交**

```bash
git add src/main/java/agent/subagent/team/backends/iterm2/
git commit -m "feat(subagent): add ITerm2Backend"
```

---

## 任务 3：创建阶段总结

**文件**：
- 创建：`docs/subagent/phase-4-summary.md`

- [ ] **步骤 1：创建阶段总结**

```markdown
# Phase 4: 分屏支持 完成总结

## 交付物

| Java 类 | Open-ClaudeCode 源码 | 状态 |
|---------|---------------------|------|
| TmuxSession.java | spawnMultiAgent.ts - TmuxSession | ✅ |
| TmuxPane.java | spawnMultiAgent.ts - TmuxPane | ✅ |
| TmuxBackend.java | spawnMultiAgent.ts - spawnTmuxTeammate() | ✅ |
| ITerm2Session.java | spawnMultiAgent.ts - ITerm2Session | ✅ |
| ITerm2Pane.java | spawnMultiAgent.ts - ITerm2Pane | ✅ |
| ITerm2Backend.java | spawnMultiAgent.ts - spawnITerm2Teammate() | ✅ |

## 如何继续
...
```

- [ ] **步骤 2：提交**

```bash
git add docs/subagent/phase-4-summary.md
git commit -m "docs: add Phase 4 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应任务 |
|---------|---------|
| TmuxBackend | 任务 1 |
| ITerm2Backend | 任务 2 |

### 占位符扫描

无占位符。
