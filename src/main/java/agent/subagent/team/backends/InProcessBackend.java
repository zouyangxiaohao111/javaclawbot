package agent.subagent.team.backends;

import agent.subagent.team.TeamCoordinator;
import agent.subagent.team.TeammateInfo;
import agent.subagent.team.messaging.TeammateMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内后端
 *
 * 对应 Open-ClaudeCode: src/utils/swarm/spawnInProcess.ts - spawnInProcessTeammate()
 *
 * 在当前进程内启动 teammate，使用 ThreadLocal 隔离上下文
 *
 * 职责：
 * 1. 创建 TeammateContext（ThreadLocal 隔离）
 * 2. 创建 linked AbortController
 * 3. 注册 InProcessTeammateTaskState
 * 4. 返回 spawn 结果
 */
public class InProcessBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(InProcessBackend.class);

    /** 运行中的 teammate */
    private final Map<String, InProcessTeammate> teammates = new ConcurrentHashMap<>();

    /** ThreadLocal 上下文存储 */
    private static final ThreadLocal<TeammateContext> currentContext = new ThreadLocal<>();

    @Override
    public BackendType type() {
        return BackendType.IN_PROCESS;
    }

    @Override
    public String displayName() {
        return "in-process";
    }

    @Override
    public boolean supportsHideShow() {
        return false;  // In-process 不支持隐藏/显示
    }

    @Override
    public boolean isRunningInside() {
        return true;  // 始终在进程内运行
    }

    @Override
    public CreatePaneResult createPane(String name, String color) {
        String paneId = UUID.randomUUID().toString();
        InProcessTeammate teammate = new InProcessTeammate(paneId, name, color);
        teammates.put(paneId, teammate);
        log.info("Created InProcessTeammate: paneId={}, name={}", paneId, name);
        return CreatePaneResult.first(paneId);
    }

    @Override
    public void sendCommand(String paneId, String command) {
        InProcessTeammate teammate = teammates.get(paneId);
        if (teammate != null) {
            teammate.receiveCommand(command);
        } else {
            log.warn("Teammate not found: paneId={}", paneId);
        }
    }

    @Override
    public void killPane(String paneId) {
        InProcessTeammate teammate = teammates.remove(paneId);
        if (teammate != null) {
            teammate.stop();
            log.info("Stopped InProcessTeammate: paneId={}", paneId);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
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

    @Override
    public void setPaneBorderColor(String paneId, String color) {
        // In-process 不支持设置边框颜色
        log.debug("setPaneBorderColor: no-op for in-process backend");
    }

    @Override
    public void setPaneTitle(String paneId, String name, String color) {
        // In-process 不支持设置标题
        log.debug("setPaneTitle: no-op for in-process backend");
    }

    @Override
    public void enablePaneBorderStatus() {
        // In-process 不支持
        log.debug("enablePaneBorderStatus: no-op for in-process backend");
    }

    @Override
    public void rebalancePanes(boolean hasLeader) {
        // In-process 不支持
        log.debug("rebalancePanes: no-op for in-process backend");
    }

    @Override
    public boolean hidePane(String paneId) {
        log.debug("hidePane: no-op for in-process backend");
        return false;
    }

    @Override
    public boolean showPane(String paneId, String targetWindowOrPane) {
        log.debug("showPane: no-op for in-process backend");
        return false;
    }

    /**
     * 获取所有运行中的 teammate
     */
    public Map<String, InProcessTeammate> getTeammates() {
        return teammates;
    }

    /**
     * 设置当前线程的上下文
     * 对应: AsyncLocalStorage.bind()
     */
    public static void setContext(TeammateContext context) {
        currentContext.set(context);
    }

    /**
     * 获取当前线程的上下文
     * 对应: AsyncLocalStorage.getStore()
     */
    public static TeammateContext getContext() {
        return currentContext.get();
    }

    /**
     * 清除当前线程的上下文
     * 对应: AsyncLocalStorage.exit()
     */
    public static void clearContext() {
        currentContext.remove();
    }

    /**
     * 使用上下文执行任务
     * 对应: AsyncLocalStorage.run()
     *
     * @param context 上下文
     * @param task 要执行的任务
     * @param <T> 返回类型
     * @return 任务结果
     */
    public static <T> T runWithContext(TeammateContext context, java.util.concurrent.Callable<T> task) throws Exception {
        try {
            setContext(context);
            return task.call();
        } finally {
            clearContext();
        }
    }

    /**
     * Teammate 上下文
     *
     * 对应 Open-ClaudeCode: createTeammateContext()
     */
    public static class TeammateContext {
        private final String agentId;
        private final String agentName;
        private final String teamName;
        private final String color;
        private final boolean planModeRequired;
        private final String parentSessionId;
        private final AbortController abortController;
        private final String taskId;
        private volatile String status = "running";
        private volatile boolean isIdle = true;
        private volatile boolean shutdownRequested = false;
        private agent.tool.ToolUseContext toolUseContext;

        public TeammateContext(String agentId, String agentName, String teamName,
                               String color, boolean planModeRequired,
                               String parentSessionId, AbortController abortController,
                               String taskId) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.teamName = teamName;
            this.color = color;
            this.planModeRequired = planModeRequired;
            this.parentSessionId = parentSessionId;
            this.abortController = abortController;
            this.taskId = taskId;
        }

        public String getAgentId() { return agentId; }
        public String getAgentName() { return agentName; }
        public String getTeamName() { return teamName; }
        public String getColor() { return color; }
        public boolean isPlanModeRequired() { return planModeRequired; }
        public String getParentSessionId() { return parentSessionId; }
        public AbortController getAbortController() { return abortController; }
        public String getTaskId() { return taskId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public boolean isIdle() { return isIdle; }
        public void setIdle(boolean idle) { this.isIdle = idle; }
        public boolean isShutdownRequested() { return shutdownRequested; }
        public void setShutdownRequested(boolean shutdownRequested) { this.shutdownRequested = shutdownRequested; }
        public agent.tool.ToolUseContext getToolUseContext() { return toolUseContext; }
        public void setToolUseContext(agent.tool.ToolUseContext toolUseContext) { this.toolUseContext = toolUseContext; }
    }

    /**
     * AbortController 模拟
     *
     * 对应 Open-ClaudeCode: createAbortController()
     */
    public static class AbortController {
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final AtomicReference<Object> reason = new AtomicReference<>();
        private final CountDownLatch abortLatch = new CountDownLatch(1);

        public void abort() {
            abort(reason.get());
        }

        public void abort(Object reason) {
            if (aborted.compareAndSet(false, true)) {
                this.reason.set(reason);
                abortLatch.countDown();
            }
        }

        public boolean isAborted() {
            return aborted.get();
        }

        public Object getReason() {
            return reason.get();
        }

        /**
         * 等待中止信号
         * @param timeout 超时时间
         * @param unit 时间单位
         * @return 是否在超时前收到中止信号
         */
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return abortLatch.await(timeout, unit);
        }
    }

    /**
     * 创建子 AbortController
     *
     * 对应 Open-ClaudeCode: createChildAbortController()
     *
     * 子控制器中止不影响父控制器
     */
    public static AbortController createChildAbortController(AbortController parent) {
        AbortController child = new AbortController();

        // 如果父已中止，子立即中止
        if (parent.isAborted()) {
            child.abort(parent.getReason());
            return child;
        }

        // 添加监听器，当父中止时子也中止
        Thread observer = new Thread(() -> {
            try {
                parent.await(30, TimeUnit.DAYS);
                if (!child.isAborted()) {
                    child.abort(parent.getReason());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        observer.setDaemon(true);
        observer.start();

        return child;
    }

    /**
     * 进程内 teammate
     *
     * 对应 Open-ClaudeCode: InProcessTeammateTaskState
     */
    public class InProcessTeammate {
        private final String id;
        private final String name;
        private final String color;
        private final String taskId;
        private final TeammateContext context;
        private final AbortController abortController;
        private final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;
        private volatile String status = "running";
        private volatile boolean awaitingPlanApproval = false;

        public InProcessTeammate(String id, String name, String color) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.taskId = generateTaskId();
            this.abortController = new AbortController();

            // 创建上下文
            this.context = new TeammateContext(
                formatAgentId(name, ""), // teamName 稍后设置
                name,
                "", // teamName 稍后设置
                color,
                false, // planModeRequired 稍后设置
                getSessionId(),
                abortController,
                taskId
            );
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getColor() { return color; }
        public String getTaskId() { return taskId; }
        public TeammateContext getContext() { return context; }
        public AbortController getAbortController() { return abortController; }
        public boolean isRunning() { return running; }
        public String getStatus() { return status; }

        public void receiveCommand(String command) {
            log.info("InProcessTeammate {} received command: {}", name, command);
            status = "processing";

            // 检查中止信号
            if (abortController.isAborted()) {
                log.info("InProcessTeammate {} aborted", name);
                status = "aborted";
                return;
            }

            try {
                // 使用上下文执行命令 - 对应 Open-ClaudeCode: runWithTeammateContext()
                InProcessBackend.runWithContext(context, () -> {
                    // 执行 runAgent 并收集输出
                    // 对应 Open-ClaudeCode: inProcessRunner.ts 中的 runInProcessTeammate
                    String result = executeRunAgent(command);
                    outputQueue.offer(result);
                    return null;
                });
            } catch (Exception e) {
                log.error("Error executing command", e);
                outputQueue.offer("Error: " + e.getMessage());
            }

            status = "idle";
        }

        /**
         * 执行 runAgent 并收集输出
         * 对应 Open-ClaudeCode: inProcessRunner.ts - runInProcessTeammate()
         */
        private String executeRunAgent(String command) {
            try {
                // 使用 RunAgent.runGeneralPurpose 执行
                // 对应 Open-ClaudeCode: inProcessRunner.ts 中的 runAgent 调用
                // 从 TeammateContext 获取 ToolUseContext
                agent.tool.ToolUseContext toolUseContext = context.getToolUseContext();
                String result = agent.subagent.execution.RunAgent.runGeneralPurpose(command, false, toolUseContext);
                return result != null ? result : "";
            } catch (Exception e) {
                log.error("Error executing runAgent", e);
                return "Error executing runAgent: " + e.getMessage();
            }
        }

        /**
         * 构建系统提示
         * 对应 Open-ClaudeCode: inProcessRunner.ts 中的系统提示构建
         */
        private String buildSystemPrompt() {
            // 获取默认系统提示
            String defaultPrompt = agent.subagent.builtin.general.GeneralPurposeAgent.getSystemPrompt();

            // 添加队友系统提示附加内容
            String teammateAddendum = "\n\n# Teammate Instructions\n" +
                "You are running as a teammate in a multi-agent team. " +
                "You should collaborate with other teammates and follow the team lead's instructions. " +
                "Use the available tools to complete your assigned task.";

            return defaultPrompt + teammateAddendum;
        }

        public void stop() {
            running = false;
            abortController.abort();
            status = "killed";
        }

        public String getOutput() {
            StringBuilder sb = new StringBuilder();
            outputQueue.forEach(sb::append);
            outputQueue.clear();
            return sb.toString();
        }

        public String pollOutput() {
            try {
                return outputQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private String generateTaskId() {
        return "in_process_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String formatAgentId(String name, String teamName) {
        return teamName != null && !teamName.isEmpty() ? name + "@" + teamName : name;
    }

    /**
     * 获取 Session ID
     * 对应 Open-ClaudeCode: 从 AppState 或 ToolUseContext 获取 sessionId
     */
    private String getSessionId() {
        // 从 ToolUseContext 获取 sessionId
        TeammateContext ctx = getContext();
        if (ctx != null && ctx.getToolUseContext() != null) {
            String sessionId = ctx.getToolUseContext().getSessionId();
            if (sessionId != null) {
                return sessionId;
            }
        }
        // 回退到生成随机 sessionId
        return "session-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // =====================
    // Mailbox Polling and Task Coordination
    // =====================

    /**
     * 轮询新消息
     * 对应 Open-ClaudeCode: readMailbox()
     *
     * @param teammateName teammate 名称
     * @param teamName 团队名称
     * @return 新消息列表
     */
    public List<TeammateMailbox.MailboxMessage> pollNewMessages(String teammateName, String teamName) {
        TeammateMailbox mailbox = getMailbox();
        if (mailbox == null) {
            return Collections.emptyList();
        }
        return mailbox.pollNewMessages(teammateName, teamName);
    }

    /**
     * 等待下一条消息或关闭请求
     * 对应 Open-ClaudeCode: inProcessRunner.ts - waitForNextPromptOrShutdown()
     *
     * 轮询 teammate 的信箱，等待：
     * - 新的 prompt 消息
     * - 关闭请求
     * - 中止信号
     *
     * @param identity TeammateContext
     * @param abortController 中止控制器
     * @param taskId 任务 ID
     * @param pollIntervalMs 轮询间隔（毫秒）
     * @return WaitResult 包含消息类型和内容
     */
    public WaitResult waitForNextPromptOrShutdown(
            TeammateContext identity,
            AbortController abortController,
            String taskId,
            long pollIntervalMs
    ) {
        TeammateMailbox mailbox = getMailbox();
        if (mailbox == null) {
            log.warn("Mailbox not available for waitForNextPromptOrShutdown");
            return WaitResult.aborted();
        }

        int pollCount = 0;
        while (!abortController.isAborted()) {
            // 等待间隔（首次立即检查）
            if (pollCount > 0) {
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return WaitResult.aborted();
                }
            }
            pollCount++;

            // 检查中止信号
            if (abortController.isAborted()) {
                log.debug("{} aborted while waiting", identity.getAgentName());
                return WaitResult.aborted();
            }

            // 读取所有消息
            List<TeammateMailbox.MailboxMessage> messages = mailbox.readAll(
                    identity.getAgentName(),
                    identity.getTeamName()
            );

            // 优先检查关闭请求
            for (int i = 0; i < messages.size(); i++) {
                TeammateMailbox.MailboxMessage msg = messages.get(i);
                TeammateMailbox.ShutdownRequestInfo shutdown = mailbox.isShutdownRequest(msg.getText());
                if (shutdown != null) {
                    log.info("{} received shutdown request from {}", identity.getAgentName(), shutdown.from);
                    return WaitResult.shutdownRequest(shutdown.from, shutdown.reason, msg.getText());
                }
            }

            // 查找下一条未读消息
            for (TeammateMailbox.MailboxMessage msg : messages) {
                // 检查是否是来自团队领导的普通消息
                if ("team-lead".equals(msg.getFrom()) || "user".equals(msg.getFrom())) {
                    log.debug("{} received message from {}", identity.getAgentName(), msg.getFrom());
                    return WaitResult.newMessage(msg.getText(), msg.getFrom(), msg.getColor(), msg.getSummary());
                }
            }
        }

        log.debug("{} exiting poll loop (aborted, polls={})", identity.getAgentName(), pollCount);
        return WaitResult.aborted();
    }

    /**
     * 发送空闲通知给团队领导
     * 对应 Open-ClaudeCode: inProcessRunner.ts - sendIdleNotification()
     *
     * @param agentName 代理名称
     * @param agentColor 代理颜色
     * @param teamName 团队名称
     * @param idleReason 空闲原因 (available, interrupted, failed)
     * @param summary 摘要
     */
    public void sendIdleNotification(
            String agentName,
            String agentColor,
            String teamName,
            String idleReason,
            String summary
    ) {
        TeammateMailbox mailbox = getMailbox();
        if (mailbox == null) {
            log.warn("Mailbox not available for sendIdleNotification");
            return;
        }

        TeammateMailbox.MailboxMessage notification = TeammateMailbox.createIdleNotification(
                agentName, idleReason, summary, null, null, null
        );

        // 发送给团队领导（TEAM_LEAD_NAME）
        mailbox.write("team-lead", notification, teamName);
        log.debug("Sent idle notification from {} to team-lead", agentName);
    }

    /**
     * 获取信箱实例
     */
    private TeammateMailbox getMailbox() {
        // 尝试从上下文获取
        TeammateContext ctx = getContext();
        if (ctx != null && ctx.getToolUseContext() != null) {
            // 从 ToolUseContext 获取 mailbox（如果可用）
            // 注意：这需要 ToolUseContext 提供获取 mailbox 的方法
        }
        // 回退到使用团队协调器的 mailbox
        return teamMailbox;
    }

    /** 团队信箱（由 TeamCoordinator 设置） */
    private TeammateMailbox teamMailbox;

    /**
     * 设置团队信箱
     */
    public void setTeamMailbox(TeammateMailbox mailbox) {
        this.teamMailbox = mailbox;
    }

    /**
     * 等待结果
     */
    public static class WaitResult {
        public enum Type {
            NEW_MESSAGE,
            SHUTDOWN_REQUEST,
            ABORTED
        }

        private final Type type;
        private final String message;
        private final String from;
        private final String color;
        private final String summary;
        private final String originalMessage;
        private final String reason;

        private WaitResult(Type type, String message, String from, String color, String summary, String originalMessage, String reason) {
            this.type = type;
            this.message = message;
            this.from = from;
            this.color = color;
            this.summary = summary;
            this.originalMessage = originalMessage;
            this.reason = reason;
        }

        public static WaitResult newMessage(String message, String from, String color, String summary) {
            return new WaitResult(Type.NEW_MESSAGE, message, from, color, summary, null, null);
        }

        public static WaitResult shutdownRequest(String from, String reason, String originalMessage) {
            return new WaitResult(Type.SHUTDOWN_REQUEST, null, from, null, null, originalMessage, reason);
        }

        public static WaitResult aborted() {
            return new WaitResult(Type.ABORTED, null, null, null, null, null, null);
        }

        public Type getType() { return type; }
        public String getMessage() { return message; }
        public String getFrom() { return from; }
        public String getColor() { return color; }
        public String getSummary() { return summary; }
        public String getOriginalMessage() { return originalMessage; }
        public String getReason() { return reason; }

        public boolean isAborted() { return type == Type.ABORTED; }
        public boolean isShutdownRequest() { return type == Type.SHUTDOWN_REQUEST; }
        public boolean isNewMessage() { return type == Type.NEW_MESSAGE; }
    }
}
