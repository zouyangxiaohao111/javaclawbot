package bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bus.InboundMessage;
import bus.OutboundMessage;

import java.util.concurrent.*;

public class MessageBus {
    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);

    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public CompletionStage<Void> publishInbound(InboundMessage msg) {
        try {
            inbound.put(msg);
            //log.info("入栈消息已发布到消息总线 {}", msg);
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletionStage<Void> publishOutbound(OutboundMessage msg) {
        try {
            outbound.put(msg);
            //log.info("出栈消息已发布到消息总线 {}", msg);
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    public InboundMessage consumeInbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        InboundMessage poll = inbound.poll(timeout, unit);
        if (poll == null) {
            return null;
        }
        //log.info("入栈消息已被消费: {}", poll);
        return poll;
    }

    public OutboundMessage consumeOutbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        OutboundMessage poll = outbound.poll(timeout, unit);
        if (poll == null) {
            return null;
        }
        //log.info("出栈消息已被消费: {}", poll);
        return poll;
    }
}