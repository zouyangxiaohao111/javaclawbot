package agent.subagent;

import bus.InboundMessage;
import bus.MessageBus;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.GsonFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 子Agent完成公告服务
 *
 * 对应 OpenClaw: src/agents/subagent-announce.ts
 *
 * 核心职责：
 * 1. 当子Agent完成时，向父Agent发送完成公告
 * 2. 支持重试机制
 * 3. 支持嵌套层级传递
 */
public class SubagentAnnounceService {

    private static final Logger log = LoggerFactory.getLogger(SubagentAnnounceService.class);

    private final MessageBus messageBus;
    private final SubagentSystemPromptBuilder promptBuilder;

    /** 默认公告超时时间（毫秒） */
    private static final long DEFAULT_ANNOUNCE_TIMEOUT_MS = 90_000;

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 重试延迟（毫秒） */
    private static final long[] RETRY_DELAYS_MS = {500, 1_000, 1500};

    public SubagentAnnounceService(MessageBus messageBus, SubagentSystemPromptBuilder promptBuilder) {
        this.messageBus = messageBus;
        this.promptBuilder = promptBuilder;
    }

    /**
     * 发送子Agent完成公告
     *
     * @param record 运行记录
     * @return 发送结果
     */
    public CompletionStage<Void> announceCompletion(SubagentRunRecord record) {
        log.debug("公告子代理完成: {}, 具体信息:{}", record.getRunId(), GsonFactory.getGson().toJson(record));
        if (record == null) {
            return CompletableFuture.completedFuture(null);
        }

        String requesterSessionKey = record.getRequesterSessionKey();
        if (requesterSessionKey == null || requesterSessionKey.isBlank()) {
            log.warn("无法公告：运行 {} 没有请求者会话键", record.getRunId());
            return CompletableFuture.completedFuture(null);
        }

        // 构建公告消息
        String announceContent = promptBuilder.buildCompletionAnnounce(record);

        // 解析目标渠道和chatId
        String channel = "cli";
        String chatId = "direct";

        if (requesterSessionKey.contains(":")) {
            String[] parts = requesterSessionKey.split(":", 2);
            channel = parts[0];
            chatId = parts.length > 1 ? parts[1] : "direct";
        }

        // 创建入站消息
        InboundMessage msg = new InboundMessage(
                "system",
                "subagent_announce",
                channel + ":" + chatId,
                announceContent,
                null,
                Map.of(
                        "_subagent_announce", true,
                        "_run_id", record.getRunId(),
                        "_child_session_key", record.getChildSessionKey()
                )
        );

        log.info("公告子代理完成: {} -> {}", record.getRunId(), requesterSessionKey);

        // 发布到消息总线
        return messageBus.publishInbound(msg);
    }

    /**
     * 发送子Agent完成公告（带重试）
     *
     * @param record 运行记录
     * @return 发送结果
     */
    public CompletionStage<Void> announceWithRetry(SubagentRunRecord record) {
        return announceWithRetry(record, 0);
    }

    private CompletionStage<Void> announceWithRetry(SubagentRunRecord record, int retryCount) {
        return announceCompletion(record)
                .exceptionally(ex -> {
                    if (retryCount < MAX_RETRY_COUNT && isTransientError(ex)) {
                        long delayMs = RETRY_DELAYS_MS[Math.min(retryCount, RETRY_DELAYS_MS.length - 1)];
                        log.warn("公告失败（第 {}/{} 次），{} 毫秒后重试: {}",
                                retryCount + 1, MAX_RETRY_COUNT, delayMs, ex.getMessage());

                        // 延迟后重试
                        CompletableFuture<Void> retry = new CompletableFuture<>();
                        java.util.concurrent.CompletableFuture.delayedExecutor(
                                delayMs, java.util.concurrent.TimeUnit.MILLISECONDS,
                                java.util.concurrent.ForkJoinPool.commonPool()
                        ).execute(() -> {
                            announceWithRetry(record, retryCount + 1)
                                    .whenComplete((v, e) -> {
                                        if (e != null) retry.completeExceptionally(e);
                                        else retry.complete(null);
                                    });
                        });
                        return retry.join();
                    } else {
                        log.error("公告失败，已重试 {} 次: {}", retryCount + 1, ex.getMessage());
                        throw new RuntimeException("Announce failed", ex);
                    }
                });
    }

    /**
     * 判断是否为临时性错误
     */
    private boolean isTransientError(Throwable ex) {
        if (ex == null) return false;
        String message = ex.getMessage();
        if (message == null) return false;

        message = message.toLowerCase();

        // 临时性错误模式
        return message.contains("unavailable")
//                || message.contains("timeout")
                || message.contains("connection")
                || message.contains("network")
                || message.contains("econnreset")
                || message.contains("econnrefused");
    }

    /**
     * 构建子Agent启动通知
     *
     * @param record 运行记录
     * @return 通知消息
     */
    public String buildSpawnNotification(SubagentRunRecord record) {
        String label = (record.getLabel() != null && !record.getLabel().isBlank())
                ? record.getLabel()
                : truncate(record.getTask(), 30);

        return String.format("子代理 [%s] 已启动 (id: %s)。完成时会通知您。",
                label, record.getRunId());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}