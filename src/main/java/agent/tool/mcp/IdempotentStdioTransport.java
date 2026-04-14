package agent.tool.mcp;

import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 包装 StdioClientTransport，修复 MCP SDK 1.0.0 的 SinkManyUnicast 缺陷。
 *
 * 问题：
 * StdioClientTransport 在构造函数中创建 inboundSink/outboundSink/errorSink，
 * 类型为 SinkManyUnicast（只允许一个 Subscriber）。
 * 但 MCP SDK 的 LifecycleInitializer 会为每个操作（initialize / listTools / callTool）
 * 创建新的 McpClientSession，每个 session 都会调用 transport.connect()，
 * 导致第二次 subscribe 时抛出 "sinks only allow a single Subscriber"。
 *
 * 方案：
 * 让 connect() 幂等 —— 只有第一次调用真正执行订阅，后续调用直接返回成功。
 */
public class IdempotentStdioTransport implements McpClientTransport {

    private final StdioClientTransport delegate;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public IdempotentStdioTransport(StdioClientTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> connect(java.util.function.Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        // 只有第一次调用真正连接，后续调用直接返回空 Mono
        if (!connected.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return delegate.connect(handler);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return delegate.sendMessage(message);
    }

    @Override
    public Mono<Void> closeGracefully() {
        // 关闭后重置标志，允许下次重连
        connected.set(false);
        return delegate.closeGracefully();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }
}
