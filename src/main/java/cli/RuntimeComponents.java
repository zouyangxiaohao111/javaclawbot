package cli;

import config.Config;
import config.agent.AgentRuntimeSettings;
import config.ConfigReloader;
import config.ConfigSchema;
import lombok.Getter;
import providers.LLMProvider;

@Getter
public final class RuntimeComponents {
    final ConfigReloader reloader;
    final Config config;
    final LLMProvider provider;
    final AgentRuntimeSettings runtimeSettings;

    public RuntimeComponents(
            ConfigReloader reloader,
            Config config,
            LLMProvider provider,
            AgentRuntimeSettings runtimeSettings
    ) {
        this.reloader = reloader;
        this.config = config;
        this.provider = provider;
        this.runtimeSettings = runtimeSettings;
    }
}