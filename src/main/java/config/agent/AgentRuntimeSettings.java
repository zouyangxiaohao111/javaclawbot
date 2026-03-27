package config.agent;

import config.Config;
import config.ConfigReloader;
import config.ConfigSchema;
import config.channel.ChannelsConfig;
import config.mcp.MCPServerConfig;
import config.tool.ExecToolConfig;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 运行时配置读取器
 *
 * 设计目标：
 * 1) 屏蔽 AgentLoop 对“启动快照配置”的直接依赖
 * 2) 通过 ConfigReloader 提供“按需刷新”的运行时配置
 * 3) 返回不可变 Snapshot，保证一次读取的一致性
 *
 * 说明：
 * - 这是“可演进最小改”的关键桥接层
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
     */
    public Snapshot snapshot() {
        try {
            reloader.refreshIfChanged();
        } catch (Exception ignored) {
        }

        Config cfg = reloader.getCurrentConfig();

        return new Snapshot(
                cfg.getWorkspacePath(),
                cfg.getAgents().getDefaults().getModel(),
                cfg.getAgents().getDefaults().getMaxToolIterations(),
                cfg.getAgents().getDefaults().getTemperature(),
                cfg.getAgents().getDefaults().getMaxTokens(),
                cfg.getAgents().getDefaults().getMemoryWindow(),
                cfg.getAgents().getDefaults().getReasoningEffort(),
                cfg.getTools().getWeb().getSearch().getApiKey(),
                cfg.getTools().getExec(),
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
            int memoryWindow,
            String reasoningEffort,
            String braveApiKey,
            ExecToolConfig execConfig,
            boolean restrictToWorkspace,
            Map<String, MCPServerConfig> mcpServers,
            ChannelsConfig channelsConfig
    ) {}
}