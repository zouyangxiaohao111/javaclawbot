package providers.cli;

import providers.cli.model.FileAttachment;
import providers.cli.model.ImageAttachment;
import providers.cli.model.PermissionResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CLI Agent 会话接口
 */
public interface CliAgentSession {

    /**
     * 发送消息
     */
    CompletableFuture<Void> send(String prompt, List<ImageAttachment> images, List<FileAttachment> files);

    /**
     * 响应权限请求
     */
    CompletableFuture<Void> respondPermission(String requestId, PermissionResult result);

    /**
     * 获取事件流
     */
    Flux<CliEvent> events();

    /**
     * 当前会话 ID
     */
    String currentSessionId();

    /**
     * 进程是否存活
     */
    boolean isAlive();

    /**
     * 关闭会话
     */
    CompletableFuture<Void> close();

    /**
     * 获取配置
     */
    CliAgentConfig getConfig();
}
