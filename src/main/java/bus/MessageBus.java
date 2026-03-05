package bus;

import bus.InboundMessage;
import bus.OutboundMessage;

import java.util.concurrent.*;

public class MessageBus {

    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public CompletionStage<Void> publishInbound(InboundMessage msg) {
        try {
            inbound.put(msg);
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletionStage<Void> publishOutbound(OutboundMessage msg) {
        try {
            outbound.put(msg);
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    public InboundMessage consumeInbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        return inbound.poll(timeout, unit);
    }

    public OutboundMessage consumeOutbound(long timeout, TimeUnit unit)
            throws InterruptedException {
        return outbound.poll(timeout, unit);
    }
}