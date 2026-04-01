package monitor;

import bus.OutboundMessage;
import config.channel.EmailMonitorConfig;
import config.channel.NotificationTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * 通知分发器
 * 根据分析结果发送通知到指定通道
 */
public class NotificationDispatcher {
    
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    
    private final EmailMonitorConfig config;
    private final Function<OutboundMessage, CompletionStage<Void>> sendCallback;

    public NotificationDispatcher(EmailMonitorConfig config, 
                                   Function<OutboundMessage, CompletionStage<Void>> sendCallback) {
        this.config = config;
        this.sendCallback = sendCallback;
    }

    /**
     * 分发通知
     */
    public CompletionStage<Void> dispatch(AnalyzeResult result) {
        if (!result.isShouldNotify()) {
            log.debug("Notification skipped: {}", result.getReason());
            return CompletableFuture.completedFuture(null);
        }

        List<String> targetNames = result.getTargets();
        if (targetNames == null || targetNames.isEmpty()) {
            log.warn("No targets specified for notification");
            return CompletableFuture.completedFuture(null);
        }

        // 找到目标配置
        List<NotificationTarget> targets = targetNames.stream()
            .map(this::findTarget)
            .filter(t -> t != null)
            .toList();

        if (targets.isEmpty()) {
            log.warn("No valid targets found for names: {}", targetNames);
            return CompletableFuture.completedFuture(null);
        }

        // 发送通知到每个目标
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = targets.stream()
            .map(target -> sendToTarget(target, result.getMessage()))
            .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    private NotificationTarget findTarget(String name) {
        return config.getNotificationTargets().stream()
            .filter(t -> name.equals(t.getName()))
            .findFirst()
            .orElse(null);
    }

    private CompletableFuture<Void> sendToTarget(NotificationTarget target, String message) {
        log.info("Sending notification to {} via {}", target.getName(), target.getChannel());
        
        OutboundMessage outbound = new OutboundMessage();
        outbound.setChannel(target.getChannel());
        
        // 优先使用 chatId（群聊），否则使用 openId（私聊）
        if (target.getChatId() != null && !target.getChatId().isBlank()) {
            outbound.setChatId(target.getChatId());
        } else {
            outbound.setChatId(target.getOpenId());
        }
        
        outbound.setContent(message);

        return sendCallback.apply(outbound)
            .toCompletableFuture()
            .exceptionally(ex -> {
                log.error("Failed to send notification to {}: {}", target.getName(), ex.getMessage());
                return null;
            });
    }
}