package config;// =========================
    // 根配置 + 提供者匹配逻辑（与 Python 同语义）
    // =========================

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.agent.AgentsConfig;
import config.channel.ChannelsConfig;
import config.gateway.GatewayConfig;
import config.provider.ProviderConfig;
import config.provider.ProvidersConfig;
import config.tool.ToolsConfig;
import providers.ProviderRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;

@JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public  class Config {
        private AgentsConfig agents = new AgentsConfig();
        private ChannelsConfig channels = new ChannelsConfig();
        private ProvidersConfig providers = new ProvidersConfig();
        private GatewayConfig gateway = new GatewayConfig();
        private ToolsConfig tools = new ToolsConfig();

        public AgentsConfig getAgents() { return agents; }
        public void setAgents(AgentsConfig agents) { this.agents = (agents != null) ? agents : new AgentsConfig(); }

        public ChannelsConfig getChannels() { return channels; }
        public void setChannels(ChannelsConfig channels) { this.channels = (channels != null) ? channels : new ChannelsConfig(); }

        public ProvidersConfig getProviders() { return providers; }
        public void setProviders(ProvidersConfig providers) { this.providers = (providers != null) ? providers : new ProvidersConfig(); }

        public GatewayConfig getGateway() { return gateway; }
        public void setGateway(GatewayConfig gateway) { this.gateway = (gateway != null) ? gateway : new GatewayConfig(); }

        public ToolsConfig getTools() { return tools; }
        public void setTools(ToolsConfig tools) { this.tools = (tools != null) ? tools : new ToolsConfig(); }

        /** 展开后的工作区路径（支持 ~） */
        public Path getWorkspacePath() {
            String raw = getAgents().getDefaults().getWorkspace();
            return expandUser(raw);
        }
        public void setWorkspacePath(Path workspacePath) {
            if (workspacePath != null) {
                getAgents().getDefaults().setWorkspace(workspacePath.toString());
            }
        }
        public ProviderConfig getProvider(String model) {
            MatchResult r = matchProvider(model);
            return r.config;
        }

        public String getProviderName(String model) {
            MatchResult r = matchProvider(model);
            return r.name;
        }

        public String getApiKey(String model) {
            ProviderConfig p = getProvider(model);
            return (p != null) ? p.getApiKey() : null;
        }

        public String getApiBase(String model) {
            MatchResult r = matchProvider(model);
            ProviderConfig p = r.config;
            String name = r.name;

            if (p != null && p.getApiBase() != null && !p.getApiBase().isBlank()) {
                return p.getApiBase();
            }

            if (name != null) {
                ProviderRegistry.ProviderSpec spec = ProviderRegistry.findByName(name);
                if (spec != null && spec.isGateway() && spec.getDefaultApiBase() != null && !spec.getDefaultApiBase().isBlank()) {
                    return spec.getDefaultApiBase();
                }
            }
            return null;
        }

        private static final class MatchResult {
            private final ProviderConfig config;
            private final String name;

            private MatchResult(ProviderConfig config, String name) {
                this.config = config;
                this.name = name;
            }
        }

        /**
         * 匹配提供者配置与名称（对应 Python 的 _match_provider）
         */
        private MatchResult matchProvider(String model) {
            String forced = getAgents().getDefaults().getProvider();
            if (forced != null && !"auto".equals(forced)) {
                ProviderConfig p = getProviders().getByName(forced);
                return (p != null) ? new MatchResult(p, forced) : new MatchResult(null, null);
            }

            String modelLower = (model != null ? model : getAgents().getDefaults().getModel());
            modelLower = (modelLower != null ? modelLower : "");
            modelLower = modelLower.toLowerCase(java.util.Locale.ROOT);

            String modelNormalized = modelLower.replace("-", "_");

            String modelPrefix = "";
            int idx = modelLower.indexOf('/');
            if (idx >= 0) modelPrefix = modelLower.substring(0, idx);
            String normalizedPrefix = modelPrefix.replace("-", "_");

            String finalModelLower = modelLower;
            java.util.function.Function<String, Boolean> kwMatches = (kw) -> {
                if (kw == null) return false;
                String k = kw.toLowerCase(java.util.Locale.ROOT);
                return finalModelLower.contains(k) || modelNormalized.contains(k.replace("-", "_"));
            };

            // 规则：显式前缀优先
            for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                ProviderConfig p = getProviders().getByName(spec.getName());
                if (p != null && !modelPrefix.isBlank() && normalizedPrefix.equals(spec.getName())) {
                    if (spec.isOauth() || (p.getApiKey() != null && !p.getApiKey().isBlank())) {
                        return new MatchResult(p, spec.getName());
                    }
                }
            }

            // 规则：按关键字匹配
            for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                ProviderConfig p = getProviders().getByName(spec.getName());
                if (p == null) continue;

                boolean hit = false;
                for (String kw : spec.getKeywords()) {
                    if (Boolean.TRUE.equals(kwMatches.apply(kw))) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    if (spec.isOauth() || (p.getApiKey() != null && !p.getApiKey().isBlank())) {
                        return new MatchResult(p, spec.getName());
                    }
                }
            }

            // 兜底：先网关再其它（OAuth 不参与兜底）
            for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                if (spec.isOauth()) continue;
                ProviderConfig p = getProviders().getByName(spec.getName());
                if (p != null && p.getApiKey() != null && !p.getApiKey().isBlank()) {
                    return new MatchResult(p, spec.getName());
                }
            }

            return new MatchResult(null, null);
        }

        private static Path expandUser(String p) {
            if (p == null) return Paths.get("").toAbsolutePath().normalize();
            String s = p.trim();
            if (s.startsWith("~/") || "~".equals(s)) {
                String home = System.getProperty("user.home", "");
                if ("~".equals(s)) return Paths.get(home).normalize();
                return Paths.get(home, s.substring(2)).normalize();
            }
            return Paths.get(s).normalize();
        }
    }