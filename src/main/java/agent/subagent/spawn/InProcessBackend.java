package agent.subagent.spawn;


import agent.subagent.execution.RunAgent;
import agent.tool.ToolView;
import agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * InProcess 后端实现
 * 对应 Open-ClaudeCode: InProcess backend
 *
 * 在当前进程中执行代理（用于不支持 tmux/iTerm2 的环境）
 */
public class InProcessBackend implements Backend {

    private static final Logger log = LoggerFactory.getLogger(InProcessBackend.class);

    /** ToolView 引用（通过 setToolView 注入） */
    private ToolView toolView;

    @Override
    public boolean isAvailable() {
        return true; // 始终可用
    }

    @Override
    public CompletableFuture<SpawnResult> spawn(SpawnConfig config, ToolUseContext parentContext) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String sessionName = config.getSessionName();

                log.info("InProcess spawn: name={}, prompt={}", config.getName(), config.getPrompt());

                // 在当前进程中执行代理
                String result;
                switch (config.getAgentType()) {
                    case "Explore":
                        result = RunAgent.runExplore(config.getPrompt(), config.isBackground(), parentContext);
                        break;
                    case "Plan":
                        result = RunAgent.runPlan(config.getPrompt(), config.isBackground(), parentContext);
                        break;
                    case "general-purpose":
                    default:
                        result = RunAgent.runGeneralPurpose(config.getPrompt(), config.isBackground(), parentContext);
                        break;
                }

                log.info("InProcess spawn completed: name={}, result={}", config.getName(), result);
                return SpawnResult.success(sessionName, result);

            } catch (Exception e) {
                log.error("InProcess spawn error", e);
                return SpawnResult.failure("InProcess spawn error: " + e.getMessage());
            }
        });
    }

    @Override
    public void setToolView(ToolView toolView) {
        this.toolView = toolView;
    }

    /**
     * 创建基于注入 ToolView 的 fallback 上下文。
     */
    private ToolUseContext createFallbackContext(String agentType) {
        String agentId = "inprocess-" + UUID.randomUUID().toString().substring(0, 8);
        return ToolUseContext.builder()
                .agentId(agentId)
                .agentType(agentType)
                .tools(toolView.getDefinitions())
                .toolView(toolView)
                .nestedMemoryAttachmentTriggers(new HashSet<>())
                .loadedNestedMemoryPaths(new HashSet<>())
                .dynamicSkillDirTriggers(new HashSet<>())
                .discoveredSkillNames(new HashSet<>())
                .queryTracking(new ToolUseContext.QueryTracking())
                .build();
    }

    @Override
    public CompletableFuture<Boolean> terminate(String sessionName) {
        // InProcess 无法终止，只能标记
        log.warn("InProcess terminate not supported: {}", sessionName);
        return CompletableFuture.completedFuture(false);
    }
}
