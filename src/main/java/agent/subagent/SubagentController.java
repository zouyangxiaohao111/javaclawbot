package agent.subagent;

import bus.InboundMessage;
import bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 子Agent控制器
 *
 * 对应 OpenClaw: src/agents/subagent-control.ts
 *
 * 核心职责：
 * 1. kill: 终止子Agent运行
 * 2. steer: 向运行中的子Agent发送指导消息
 * 3. 提供控制范围判断
 */
public class SubagentController {

    private static final Logger log = LoggerFactory.getLogger(SubagentController.class);

    private final SubagentRegistry registry;
    private final MessageBus messageBus;

    /** steer操作限流：每个runId的上次操作时间 */
    private final Map<String, Long> steerRateLimit = new java.util.concurrent.ConcurrentHashMap<>();

    /** steer限流间隔（毫秒） */
    private static final long STEER_RATE_LIMIT_MS = 2_000;

    public SubagentController(SubagentRegistry registry, MessageBus messageBus) {
        this.registry = registry;
        this.messageBus = messageBus;
    }

    /**
     * 终止指定子Agent
     *
     * @param runId 运行ID
     * @return 是否成功
     */
    public CompletionStage<Boolean> kill(String runId) {
        SubagentRunRecord record = registry.get(runId);
        if (record == null) {
            log.warn("Cannot kill: run not found: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        if (!record.isRunning()) {
            log.warn("Cannot kill: run not active: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        // 发送终止信号
        return sendControlMessage(record, "kill", null)
                .thenApply(v -> {
                    // 标记为已终止
                    record.setOutcome(SubagentOutcome.error("killed"));
                    record.setEndedAt(java.time.LocalDateTime.now());
                    log.info("Killed subagent: {}", runId);
                    return true;
                })
                .exceptionally(ex -> {
                    log.error("Failed to kill subagent: {}", runId, ex);
                    return false;
                });
    }

    /**
     * 终止指定会话的所有子Agent
     *
     * @param requesterSessionKey 请求者会话Key
     * @return 终止的数量
     */
    public CompletionStage<Integer> killAll(String requesterSessionKey) {
        List<SubagentRunRecord> activeRuns = registry.listActiveByRequester(requesterSessionKey);

        if (activeRuns.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        int[] count = {0};
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (SubagentRunRecord record : activeRuns) {
            chain = chain.thenCompose(v -> kill(record.getRunId()))
                    .thenAccept(success -> {
                        if (success) count[0]++;
                    });
        }

        return chain.thenApply(v -> count[0]);
    }

    /**
     * 向运行中的子Agent发送指导消息
     *
     * @param runId   运行ID
     * @param message 指导消息
     * @return 是否成功
     */
    public CompletionStage<Boolean> steer(String runId, String message) {
        SubagentRunRecord record = registry.get(runId);
        if (record == null) {
            log.warn("Cannot steer: run not found: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        if (!record.isRunning()) {
            log.warn("Cannot steer: run not active: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        // 限流检查
        Long lastSteer = steerRateLimit.get(runId);
        long now = System.currentTimeMillis();
        if (lastSteer != null && (now - lastSteer) < STEER_RATE_LIMIT_MS) {
            log.warn("Steer rate limited for run: {}", runId);
            return CompletableFuture.completedFuture(false);
        }

        // 更新限流时间
        steerRateLimit.put(runId, now);

        // 发送指导消息
        return sendControlMessage(record, "steer", message)
                .thenApply(v -> {
                    log.info("Steered subagent: {} with message: {}", runId, truncate(message, 50));
                    return true;
                })
                .exceptionally(ex -> {
                    log.error("Failed to steer subagent: {}", runId, ex);
                    return false;
                });
    }

    /**
     * 发送控制消息到子Agent
     */
    private CompletionStage<Void> sendControlMessage(
            SubagentRunRecord record,
            String action,
            String message
    ) {
        String childSessionKey = record.getChildSessionKey();

        // 解析渠道
        String channel = "cli";
        String chatId = childSessionKey;

        if (childSessionKey.contains(":")) {
            String[] parts = childSessionKey.split(":", 2);
            channel = parts[0];
            chatId = parts.length > 1 ? parts[1] : childSessionKey;
        }

        // 构建控制消息内容
        String content;
        if ("steer".equals(action) && message != null) {
            content = "[Steer] " + message;
        } else if ("kill".equals(action)) {
            content = "[Kill] Task terminated by parent agent.";
        } else {
            content = "[Control] " + action;
        }

        // 创建入站消息
        InboundMessage msg = new InboundMessage(
                "system",
                "subagent_control",
                channel + ":" + chatId,
                content,
                null,
                Map.of(
                        "_subagent_control", true,
                        "_action", action,
                        "_run_id", record.getRunId()
                )
        );

        return messageBus.publishInbound(msg);
    }

    /**
     * 检查控制权限
     *
     * @param controllerSessionKey 控制者会话Key
     * @param targetRunId          目标运行ID
     * @return 是否有控制权限
     */
    public boolean hasControlPermission(String controllerSessionKey, String targetRunId) {
        SubagentRunRecord record = registry.get(targetRunId);
        if (record == null) return false;

        // 只有直接父Agent才能控制
        return controllerSessionKey.equals(record.getRequesterSessionKey());
    }

    /**
     * 获取控制范围
     *
     * @param sessionKey 会话Key
     * @return 控制范围
     */
    public ControlScope getControlScope(String sessionKey) {
        int depth = registry.getDepth(sessionKey);

        if (depth == 0) {
            // 主Agent可以控制所有子Agent
            return ControlScope.ALL_CHILDREN;
        } else {
            // 子Agent只能控制自己的子Agent
            return ControlScope.DIRECT_CHILDREN;
        }
    }

    public enum ControlScope {
        ALL_CHILDREN,      // 所有子Agent
        DIRECT_CHILDREN,   // 直接子Agent
        NONE               // 无控制权限
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}