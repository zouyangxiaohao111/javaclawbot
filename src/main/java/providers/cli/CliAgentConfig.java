package providers.cli;

import providers.cli.model.CliProviderConfig;

import java.util.List;
import java.util.Map;

/**
 * CLI Agent 配置
 */
public record CliAgentConfig(
        String agentType,           // "claude" 或 "opencode"
        String workDir,             // 工作目录
        String model,               // 模型名称
        String mode,                // 权限模式
        List<String> allowedTools,
        List<String> disallowedTools,
        String routerUrl,
        String routerApiKey,
        List<CliProviderConfig> providers,
        Map<String, String> extraEnv,
        int timeoutSeconds,
        String platformPrompt,
        boolean disableVerbose
) {
    public CliAgentConfig {
        if (allowedTools == null) allowedTools = List.of();
        if (disallowedTools == null) disallowedTools = List.of();
        if (providers == null) providers = List.of();
        if (extraEnv == null) extraEnv = Map.of();
        if (timeoutSeconds <= 0) timeoutSeconds = 600;
    }

    /**
     * 创建默认配置
     */
    public static CliAgentConfig defaults(String agentType, String workDir) {
        return new CliAgentConfig(
                agentType, workDir, null, "default",
                List.of(), List.of(), null, null,
                List.of(), Map.of(), 600, null, false
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agentType;
        private String workDir = ".";
        private String model;
        private String mode = "default";
        private List<String> allowedTools = List.of();
        private List<String> disallowedTools = List.of();
        private String routerUrl;
        private String routerApiKey;
        private List<CliProviderConfig> providers = List.of();
        private Map<String, String> extraEnv = Map.of();
        private int timeoutSeconds = 600;
        private String platformPrompt;
        private boolean disableVerbose;

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder workDir(String workDir) {
            this.workDir = workDir;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools != null ? allowedTools : List.of();
            return this;
        }

        public Builder disallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools != null ? disallowedTools : List.of();
            return this;
        }

        public Builder routerUrl(String routerUrl) {
            this.routerUrl = routerUrl;
            return this;
        }

        public Builder routerApiKey(String routerApiKey) {
            this.routerApiKey = routerApiKey;
            return this;
        }

        public Builder providers(List<CliProviderConfig> providers) {
            this.providers = providers != null ? providers : List.of();
            return this;
        }

        public Builder extraEnv(Map<String, String> extraEnv) {
            this.extraEnv = extraEnv != null ? extraEnv : Map.of();
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder platformPrompt(String platformPrompt) {
            this.platformPrompt = platformPrompt;
            return this;
        }

        public Builder disableVerbose(boolean disableVerbose) {
            this.disableVerbose = disableVerbose;
            return this;
        }

        public CliAgentConfig build() {
            return new CliAgentConfig(
                    agentType, workDir, model, mode,
                    allowedTools, disallowedTools, routerUrl, routerApiKey,
                    providers, extraEnv, timeoutSeconds, platformPrompt, disableVerbose
            );
        }
    }
}
