package agent.subagent.spawn;

import agent.tool.ToolUseContext;
import agent.tool.ToolView;

import java.util.concurrent.CompletableFuture;

/**
 * Backend 接口 - 队友进程后端
 * 对应 Open-ClaudeCode: tmux backend / iTerm2 backend / InProcess backend
 */
public interface Backend {

    /**
     * 检查后端是否可用
     */
    boolean isAvailable();

    /**
     * 创建队友
     */
    CompletableFuture<SpawnResult> spawn(SpawnConfig config, ToolUseContext toolUseContext);

    /**
     * 终止队友
     */
    CompletableFuture<Boolean> terminate(String sessionName);

    /**
     * 设置 ToolView（用于创建 fallback 上下文）
     * 默认实现不做任何操作，子类如 InProcessBackend 可覆盖
     */
    default void setToolView(ToolView toolView) {
        // 默认不操作，子类可覆盖
    }
}
