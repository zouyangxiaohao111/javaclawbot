package config.agent;

import config.Config;
import config.ConfigReloader;
import config.channel.ChannelsConfig;
import config.mcp.MCPServerConfig;
import config.provider.model.ModelConfig;
import config.tool.ExecToolConfig;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 运行时配置读取器
 *
 * 设计目标：
 * 1) 屏蔽 AgentLoop 对"启动快照配置"的直接依赖
 * 2) 通过 ConfigReloader 提供"按需刷新"的运行时配置
 * 3) 返回不可变 Snapshot，保证一次读取的一致性
 *
 * 说明：
 * - 这是"可演进最小改"的关键桥接层
 * - 现在先动态化最核心的 agent 运行参数
 * - 后续如果要彻底重构，这个类仍然可以保留
 */
public final class AgentRuntimeSettings {

    private final ConfigReloader reloader;

    public AgentRuntimeSettings(ConfigReloader reloader) {
        this.reloader = Objects.requireNonNull(reloader, "reloader");
    }

    public Config getCurrentConfig(){
        try {
            reloader.refreshIfChanged();
        } catch (Exception ignored) {
        }
        return reloader.getCurrentConfig();
    }

    /**
     * 获取当前运行时快照
     *
     * 行为：
     * - 先尝试按需刷新配置
     * - 再读取当前有效配置
     * - 组装为不可变 Snapshot 返回
     * - ModelConfig 参数优先级高于 AgentDefaults
     */
    public Snapshot snapshot() {
        try {
            reloader.refreshIfChanged();
        } catch (Exception ignored) {
        }

        Config cfg = reloader.getCurrentConfig();
        AgentDefaults defaults = cfg.getAgents().getDefaults();

        String model = defaults.getModel();
        ModelConfig modelConfig = cfg.obtainModelConfigByModel(model);

        // 参数优先级：ModelConfig > AgentDefaults
        Double temperature = cfg.obtainTemperature(model);

        // 参数优先级：ModelConfig > AgentDefaults
        Integer maxTokens = cfg.obtainMaxTokens(model);

        // 参数优先级：ModelConfig > AgentDefaults
        Integer contextWindow = cfg.obtainContextWindow(model);

        Map<String, Object> think = modelConfig != null ? modelConfig.getThink() : null;
        Map<String, Object> extraBody = modelConfig != null ? modelConfig.getExtraBody() : null;

        return new Snapshot(
                cfg.getWorkspacePath(),
                model,
                defaults.getMaxToolIterations(),
                temperature,
                maxTokens,
                contextWindow,
                defaults.getMemoryWindow(),
                defaults.getReasoningEffort(),
                think,
                extraBody,
                cfg.getTools().isRestrictToWorkspace(),
                cfg.getTools().getMcpServers(),
                cfg.getChannels()
        );
    }

    /**
     * 运行时不可变快照
     */
    public record Snapshot(
            Path workspace,
            String model,
            int maxIterations,
            double temperature,
            int maxTokens,
            /** 上下文窗口token大小 */
            int contentWindow,
            int memoryWindow,
            String reasoningEffort,
            /** 思考模式配置（来自 ModelConfig.think），启用时合并到请求 body */
            Map<String, Object> think,
            /** 额外请求参数（来自 ModelConfig.extraBody），直接合并到请求 body */
            Map<String, Object> extraBody,
            boolean restrictToWorkspace,
            Map<String, MCPServerConfig> mcpServers,
            ChannelsConfig channelsConfig
    ) {}
}