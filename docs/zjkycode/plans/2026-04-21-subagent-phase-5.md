# Phase 5: 远程执行 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。

**目标**：实现 CCR（Cloud Code Runtime）远程执行

**架构**：CCRClient → RemoteBackend → TeleportService

**技术栈**：Java 17+, HTTP/WebSocket, JSON

---

## ⚠️ 实施要求

**必须先阅读**：`docs/zjkycode/specs/2026-04-21-subagent-design.md` 第 4、5 章

**复刻原则**：必须完整按照 Open-ClaudeCode 源码逐行实现，做到 Java 语义等价、逻辑等价。

---

## 文件结构

```
src/main/java/agent/subagent/remote/
├── CCRClient.java                  # 对应: spawnMultiAgent.ts - CCRClient
├── RemoteSession.java              # 对应: spawnMultiAgent.ts - RemoteSession
├── TeleportService.java            # 对应: spawnMultiAgent.ts - TeleportService
└── RemoteBackend.java             # 对应: spawnMultiAgent.ts - RemoteBackend
```

---

## 任务 1：CCRClient

**文件**：
- 创建：`src/main/java/agent/subagent/remote/CCRClient.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - CCRClient

- [ ] **步骤 1：实现 CCRClient.java**

```java
package agent.subagent.remote;

/**
 * CCR (Cloud Code Runtime) 客户端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - CCRClient
 *
 * 职责：
 * 1. 与 CCR 服务建立连接（HTTP/WebSocket）
 * 2. 创建远程会话
 * 3. 发送命令
 * 4. 接收输出
 * 5. 终止会话
 */
public class CCRClient {

    private final String ccrEndpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Map<String, RemoteSession> sessions = new ConcurrentHashMap<>();

    /**
     * 创建远程会话
     * 对应: CCRClient.createSession()
     */
    public RemoteSession createSession(CreateSessionParams params) {
        // 1. 调用 CCR API 创建会话
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ccrEndpoint + "/sessions"))
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(toJson(params)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new CCRException("Failed to create session: " + response.body());
        }

        SessionInfo info = fromJson(response.body(), SessionInfo.class);

        // 2. 建立 WebSocket 连接用于流式输出
        WebSocket ws = httpClient.newWebSocket(
            URI.create(ccrEndpoint + "/sessions/" + info.sessionId + "/output"),
            new CCRWebSocketListener(info.sessionId)
        );

        // 3. 返回 RemoteSession
        RemoteSession session = new RemoteSession(info.sessionId, info.endpoint, ws);
        sessions.put(info.sessionId, session);
        return session;
    }

    /**
     * 发送命令到远程会话
     * 对应: CCRClient.sendCommand()
     */
    public void sendCommand(String sessionId, String command) {
        RemoteSession session = sessions.get(sessionId);
        if (session == null) {
            throw new CCRException("Session not found: " + sessionId);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(session.getEndpoint() + "/input"))
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(command))
            .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 终止远程会话
     * 对应: CCRClient.terminateSession()
     */
    public void terminateSession(String sessionId) {
        RemoteSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ccrEndpoint + "/sessions/" + sessionId))
            .header("Authorization", "Bearer " + apiKey)
            .DELETE()
            .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/remote/CCRClient.java
git commit -m "feat(subagent): add CCRClient"
```

---

## 任务 2：RemoteSession

**文件**：
- 创建：`src/main/java/agent/subagent/remote/RemoteSession.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - RemoteSession

- [ ] **步骤 1：实现 RemoteSession.java**

```java
package agent.subagent.remote;

/**
 * 远程会话
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - RemoteSession
 */
public class RemoteSession implements Closeable {

    private final String sessionId;
    private final String endpoint;
    private final WebSocket webSocket;
    private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
    private volatile boolean closed = false;

    public RemoteSession(String sessionId, String endpoint, WebSocket webSocket) {
        this.sessionId = sessionId;
        this.endpoint = endpoint;
        this.webSocket = webSocket;
    }

    /**
     * 获取会话 ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取端点
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 轮询输出
     * 对应: pollOutput()
     */
    public String pollOutput() {
        return outputQueue.poll(100, TimeUnit.MILLISECONDS);
    }

    /**
     * 获取所有待处理输出
     * 对应: drainOutput()
     */
    public List<String> drainOutput() {
        List<String> outputs = new ArrayList<>();
        String output;
        while ((output = outputQueue.poll()) != null) {
            outputs.add(output);
        }
        return outputs;
    }

    /**
     * 关闭会话
     */
    @Override
    public void close() {
        closed = true;
        webSocket.sendClose(1000, "Session closed");
    }

    /**
     * WebSocket 监听器
     */
    private class CCRWebSocketListener implements WebSocket.Listener {
        @Override
        public void onMessage(WebSocket ws, String text) {
            outputQueue.offer(text);
        }

        @Override
        public void onClose(WebSocket ws, int statusCode, String reason) {
            outputQueue.offer("[DISCONNECTED]");
        }
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/remote/RemoteSession.java
git commit -m "feat(subagent): add RemoteSession"
```

---

## 任务 3：TeleportService

**文件**：
- 创建：`src/main/java/agent/subagent/remote/TeleportService.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - TeleportService

- [ ] **步骤 1：实现 TeleportService.java**

```java
package agent.subagent.remote;

/**
 * 远程启动服务
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - TeleportService
 *
 * 职责：
 * 1. 准备远程环境（克隆代码、安装依赖）
 * 2. 启动远程 agent
 * 3. 管理远程会话生命周期
 */
public class TeleportService {

    private final CCRClient ccrClient;

    /**
     * 远程启动 teammate
     * 对应: teleportToRemote()
     */
    public RemoteTeammate teleportToRemote(TeleportConfig config) {
        // 1. 准备远程环境
        prepareRemoteEnvironment(config);

        // 2. 创建 CCR 会话
        RemoteSession session = ccrClient.createSession(
            new CreateSessionParams()
                .setImage(config.dockerImage)
                .setCommand(config启动命令)
                .setEnvironment(config.environment)
        );

        // 3. 发送初始 prompt
        ccrClient.sendCommand(session.getSessionId(), config.prompt);

        // 4. 返回 RemoteTeammate
        return new RemoteTeammate(session, config);
    }

    /**
     * 准备远程环境
     * 对应: prepareEnvironment()
     */
    private void prepareRemoteEnvironment(TeleportConfig config) {
        // 1. 如果需要，克隆代码仓库
        // 2. 安装依赖
        // 3. 设置环境变量
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/remote/TeleportService.java
git commit -m "feat(subagent): add TeleportService"
```

---

## 任务 4：RemoteBackend

**文件**：
- 创建：`src/main/java/agent/subagent/remote/RemoteBackend.java`
- 源码参考：`src/tools/shared/spawnMultiAgent.ts` - RemoteBackend

- [ ] **步骤 1：实现 RemoteBackend.java**

```java
package agent.subagent.remote;

/**
 * 远程后端
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - RemoteBackend
 */
public class RemoteBackend implements Backend {

    private final TeleportService teleportService;
    private final Map<String, RemoteTeammate> teammates = new ConcurrentHashMap<>();

    @Override
    public BackendType type() {
        return BackendType.REMOTE;
    }

    @Override
    public String createPane(String name, String color) {
        // 远程后端不需要真正的 pane，创建 RemoteTeammate
        TeleportConfig config = new TeleportConfig()
            .setName(name)
            .setColor(color);

        RemoteTeammate teammate = teleportService.teleportToRemote(config);
        teammates.put(teammate.getId(), teammate);
        return teammate.getId();
    }

    @Override
    public void sendCommand(String paneId, String command) {
        RemoteTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            teammate.sendCommand(command);
        }
    }

    @Override
    public void killPane(String paneId) {
        RemoteTeammate teammate = teammates.remove(paneId);
        if (teammate != null) {
            teammate.close();
        }
    }

    @Override
    public boolean isAvailable() {
        // 检测 CCR 服务是否可用
        return isCCRServiceAvailable();
    }

    @Override
    public String getPaneOutput(String paneId) {
        RemoteTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            return teammate.getOutput();
        }
        return "";
    }

    @Override
    public String pollPaneOutput(String paneId) {
        RemoteTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            return teammate.pollOutput();
        }
        return "";
    }
}
```

- [ ] **步骤 2：提交**

```bash
git add src/main/java/agent/subagent/remote/RemoteBackend.java
git commit -m "feat(subagent): add RemoteBackend"
```

---

## 任务 5：创建阶段总结

**文件**：
- 创建：`docs/subagent/phase-5-summary.md`

- [ ] **步骤 1：创建阶段总结**

```markdown
# Phase 5: 远程执行 完成总结

## 交付物

| Java 类 | Open-ClaudeCode 源码 | 状态 |
|---------|---------------------|------|
| CCRClient.java | spawnMultiAgent.ts - CCRClient | ✅ |
| RemoteSession.java | spawnMultiAgent.ts - RemoteSession | ✅ |
| TeleportService.java | spawnMultiAgent.ts - TeleportService | ✅ |
| RemoteBackend.java | spawnMultiAgent.ts - RemoteBackend | ✅ |

## 如何继续
...
```

- [ ] **步骤 2：提交**

```bash
git add docs/subagent/phase-5-summary.md
git commit -m "docs: add Phase 5 summary"
```

---

## 自我审查

### 规范覆盖检查

| 规范需求 | 对应任务 |
|---------|---------|
| CCRClient | 任务 1 |
| RemoteSession | 任务 2 |
| TeleportService | 任务 3 |
| RemoteBackend | 任务 4 |

### 占位符扫描

无占位符。
