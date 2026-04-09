package convert;

import config.Config;
import config.agent.AgentDefaults;
import config.agent.AgentsConfig;
import config.channel.ChannelsConfig;
import config.gateway.GatewayConfig;
import config.plugin.PluginConfig;
import config.plugin.PluginsConfig;
import config.provider.FallbackConfig;
import config.provider.ProviderConfig;
import config.provider.ProvidersConfig;
import config.tool.QueueConfig;
import config.tool.ToolsConfig;
import context.BootstrapConfig;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.Collection;
import java.util.Map;

@Mapper
public interface ConfigConvert {

    ConfigConvert A = org.mapstruct.factory.Mappers.getMapper( ConfigConvert.class );

    /**
     * 深度拷贝配置，如果源值为空或集合为空，则保留目标值
     */
    default Config updateConfig( Config config , @MappingTarget Config target ){
        if ( config == null ) {
            return target;
        }

        // 深度拷贝各个子配置
        updateAgentsConfig( config.getAgents(), target.getAgents() );
        updateChannelsConfig( config.getChannels(), target.getChannels() );
        updateProvidersConfig( config.getProviders(), target.getProviders() );
        updateGatewayConfig( config.getGateway(), target.getGateway() );
        updateToolsConfig( config.getTools(), target.getTools() );
        updatePluginsConfig( config.getPlugins(), target.getPlugins() );

        // workspacePath 特殊处理
        if ( config.getWorkspacePath() != null ) {
            target.setWorkspacePath( config.getWorkspacePath() );
        }

        return target;
    }

    /**
     * 判断值是否为空（包括集合为空）
     */
    default boolean isEmpty( Object value ) {
        if ( value == null ) {
            return true;
        }
        if ( value instanceof Collection<?> collection ) {
            return collection.isEmpty();
        }
        if ( value instanceof Map<?, ?> map ) {
            return map.isEmpty();
        }
        if ( value instanceof String str ) {
            return str.isEmpty();
        }
        return false;
    }

    /**
     * 深度拷贝 AgentsConfig
     */
    default void updateAgentsConfig( AgentsConfig source, AgentsConfig target ) {
        if ( source == null ) {
            return;
        }
        updateAgentDefaults( source.getDefaults(), target.getDefaults() );
    }

    /**
     * 深度拷贝 AgentDefaults
     */
    default void updateAgentDefaults( AgentDefaults source, AgentDefaults target ) {
        if ( source == null ) {
            return;
        }

        // 基础字段：非空才拷贝
        if ( !isEmpty( source.getWorkspace() ) ) {
            target.setWorkspace( source.getWorkspace() );
        }
        if ( !isEmpty( source.getModel() ) ) {
            target.setModel( source.getModel() );
        }
        if ( !isEmpty( source.getProvider() ) ) {
            target.setProvider( source.getProvider() );
        }
        if ( !isEmpty( source.getReasoningEffort() ) ) {
            target.setReasoningEffort( source.getReasoningEffort() );
        }
        if ( !isEmpty( source.getWindowsBashPath() ) ) {
            target.setWindowsBashPath( source.getWindowsBashPath() );
        }

        // 数值字段：直接拷贝（数值类型没有空的概念）
        target.setDevelopment( source.isDevelopment() );
        target.setMaxToolIterations( source.getMaxToolIterations() );
        target.setMemoryWindow( source.getMemoryWindow() );
        target.setSkillMaxLoad( source.getSkillMaxLoad() );
        target.setConsolidateThreshold( source.getConsolidateThreshold() );
        target.setSoftTrimThreshold( source.getSoftTrimThreshold() );
        target.setMaxConcurrent( source.getMaxConcurrent() );

        // 子配置对象：深度拷贝
        updateFallbackConfig( source.getFallback(), target.getFallback() );
        updateHeartbeatConfig( source.getHeartbeat(), target.getHeartbeat() );
        updateQueueConfig( source.getQueue(), target.getQueue() );
        updateBootstrapConfig( source.getBootstrapConfig(), target.getBootstrapConfig() );
    }

    /**
     * 深度拷贝 FallbackConfig
     */
    default void updateFallbackConfig( config.provider.FallbackConfig source, config.provider.FallbackConfig target ) {
        if ( source == null ) {
            return;
        }
        // enabled：直接拷贝
        target.setEnabled( source.isEnabled() );
        // mode：非空才拷贝
        if ( !isEmpty( source.getMode() ) ) {
            target.setMode( source.getMode() );
        }
        // targets：非空才拷贝
        if ( !isEmpty( source.getTargets() ) ) {
            target.setTargets( source.getTargets() );
        }
        // maxAttempts：大于 0 才拷贝
        if ( source.getMaxAttempts() > 0 ) {
            target.setMaxAttempts( source.getMaxAttempts() );
        }
    }

    /**
     * 深度拷贝 HeartbeatConfig
     */
    default void updateHeartbeatConfig( config.hearbet.HeartbeatConfig source, config.hearbet.HeartbeatConfig target ) {
        if ( source == null ) {
            return;
        }
        // enabled：直接拷贝
        target.setEnabled( source.isEnabled() );
        // every：非空才拷贝
        if ( !isEmpty( source.getEvery() ) ) {
            target.setEvery( source.getEvery() );
        }
        // prompt：非空才拷贝
        if ( !isEmpty( source.getPrompt() ) ) {
            target.setPrompt( source.getPrompt() );
        }
        // target：非空才拷贝
        if ( !isEmpty( source.getTarget() ) ) {
            target.setTarget( source.getTarget() );
        }
        // model：非空才拷贝
        if ( !isEmpty( source.getModel() ) ) {
            target.setModel( source.getModel() );
        }
        // ackMaxChars：大于 0 才拷贝
        if ( source.getAckMaxChars() > 0 ) {
            target.setAckMaxChars( source.getAckMaxChars() );
        }
        // isolatedSession：直接拷贝
        target.setIsolatedSession( source.isIsolatedSession() );
        // includeReasoning：直接拷贝
        target.setIncludeReasoning( source.isIncludeReasoning() );
        // activeHoursStart：非空才拷贝
        if ( !isEmpty( source.getActiveHoursStart() ) ) {
            target.setActiveHoursStart( source.getActiveHoursStart() );
        }
        // activeHoursEnd：非空才拷贝
        if ( !isEmpty( source.getActiveHoursEnd() ) ) {
            target.setActiveHoursEnd( source.getActiveHoursEnd() );
        }
    }

    /**
     * 深度拷贝 QueueConfig
     */
    default void updateQueueConfig( config.tool.QueueConfig source, config.tool.QueueConfig target ) {
        if ( source == null ) {
            return;
        }
        // mode：非空才拷贝
        if ( !isEmpty( source.getMode() ) ) {
            target.setMode( source.getMode() );
        }
        // debounceMs：大于 0 才拷贝
        if ( source.getDebounceMs() > 0 ) {
            target.setDebounceMs( source.getDebounceMs() );
        }
        // cap：大于 0 才拷贝
        if ( source.getCap() > 0 ) {
            target.setCap( source.getCap() );
        }
        // drop：非空才拷贝
        if ( !isEmpty( source.getDrop() ) ) {
            target.setDrop( source.getDrop() );
        }
    }

    /**
     * 深度拷贝 BootstrapConfig
     */
    default void updateBootstrapConfig( context.BootstrapConfig source, context.BootstrapConfig target ) {
        if ( source == null ) {
            return;
        }
        // maxChars：大于 0 才拷贝
        if ( source.getMaxChars() > 0 ) {
            target.setMaxChars( source.getMaxChars() );
        }
        // totalMaxChars：大于 0 才拷贝
        if ( source.getTotalMaxChars() > 0 ) {
            target.setTotalMaxChars( source.getTotalMaxChars() );
        }
        // contextMode：非空才拷贝
        if ( source.getContextMode() != null ) {
            target.setContextMode( source.getContextMode() );
        }
        // runKind：非空才拷贝
        if ( source.getRunKind() != null ) {
            target.setRunKind( source.getRunKind() );
        }
    }

    /**
     * 深度拷贝 ChannelsConfig
     */
    default void updateChannelsConfig( ChannelsConfig source, ChannelsConfig target ) {
        if ( source == null ) {
            return;
        }
        // 基础布尔字段直接拷贝
        target.setSendProgress( source.isSendProgress() );
        target.setSendToolHints( source.isSendToolHints() );

        // 各渠道配置：非空才拷贝（使用 target 的默认值作为判断）
        if ( source.getWhatsapp() != null && !isAllFieldsEmpty( source.getWhatsapp() ) ) {
            target.setWhatsapp( source.getWhatsapp() );
        }
        if ( source.getTelegram() != null && !isAllFieldsEmpty( source.getTelegram() ) ) {
            target.setTelegram( source.getTelegram() );
        }
        if ( source.getDiscord() != null && !isAllFieldsEmpty( source.getDiscord() ) ) {
            target.setDiscord( source.getDiscord() );
        }
        if ( source.getFeishu() != null && !isAllFieldsEmpty( source.getFeishu() ) ) {
            target.setFeishu( source.getFeishu() );
        }
        if ( source.getMochat() != null && !isAllFieldsEmpty( source.getMochat() ) ) {
            target.setMochat( source.getMochat() );
        }
        if ( source.getDingtalk() != null && !isAllFieldsEmpty( source.getDingtalk() ) ) {
            target.setDingtalk( source.getDingtalk() );
        }
        if ( source.getEmail() != null && !isAllFieldsEmpty( source.getEmail() ) ) {
            target.setEmail( source.getEmail() );
        }
        if ( source.getEmailMonitor() != null && !isAllFieldsEmpty( source.getEmailMonitor() ) ) {
            target.setEmailMonitor( source.getEmailMonitor() );
        }
        if ( source.getSlack() != null && !isAllFieldsEmpty( source.getSlack() ) ) {
            target.setSlack( source.getSlack() );
        }
        if ( source.getQq() != null && !isAllFieldsEmpty( source.getQq() ) ) {
            target.setQq( source.getQq() );
        }
        if ( source.getMatrix() != null && !isAllFieldsEmpty( source.getMatrix() ) ) {
            target.setMatrix( source.getMatrix() );
        }
    }

    /**
     * 判断对象所有字段是否为空（简化判断，用于渠道配置）
     */
    default boolean isAllFieldsEmpty( Object obj ) {
        if ( obj == null ) {
            return true;
        }
        // 使用反射检查所有字段是否为空
        try {
            for ( java.lang.reflect.Field field : obj.getClass().getDeclaredFields() ) {
                field.setAccessible( true );
                Object value = field.get( obj );
                if ( !isEmpty( value ) ) {
                    // 排除默认布尔值 false
                    if ( value instanceof Boolean && !((Boolean) value) ) {
                        continue;
                    }
                    return false;
                }
            }
        } catch ( Exception e ) {
            // 反射失败时返回 false，允许拷贝
            return false;
        }
        return true;
    }

    /**
     * 深度拷贝 ProvidersConfig
     */
    default void updateProvidersConfig( ProvidersConfig source, ProvidersConfig target ) {
        if ( source == null ) {
            return;
        }
        // 各 Provider 配置：非空才拷贝
        updateProviderConfig( source.getByName( "custom" ), target.getByName( "custom" ) );
        updateProviderConfig( source.getByName( "anthropic" ), target.getByName( "anthropic" ) );
        updateProviderConfig( source.getByName( "openai" ), target.getByName( "openai" ) );
        updateProviderConfig( source.getByName( "openrouter" ), target.getByName( "openrouter" ) );
        updateProviderConfig( source.getByName( "deepseek" ), target.getByName( "deepseek" ) );
        updateProviderConfig( source.getByName( "groq" ), target.getByName( "groq" ) );
        updateProviderConfig( source.getByName( "zhipu" ), target.getByName( "zhipu" ) );
        updateProviderConfig( source.getByName( "dashscope" ), target.getByName( "dashscope" ) );
        updateProviderConfig( source.getByName( "vllm" ), target.getByName( "vllm" ) );
        updateProviderConfig( source.getByName( "gemini" ), target.getByName( "gemini" ) );
        updateProviderConfig( source.getByName( "moonshot" ), target.getByName( "moonshot" ) );
        updateProviderConfig( source.getByName( "minimax" ), target.getByName( "minimax" ) );
        updateProviderConfig( source.getByName( "aihubmix" ), target.getByName( "aihubmix" ) );
        updateProviderConfig( source.getByName( "siliconflow" ), target.getByName( "siliconflow" ) );
        updateProviderConfig( source.getByName( "volcengine" ), target.getByName( "volcengine" ) );
        updateProviderConfig( source.getByName( "openaiCodex" ), target.getByName( "openaiCodex" ) );
        updateProviderConfig( source.getByName( "githubCopilot" ), target.getByName( "githubCopilot" ) );
    }

    /**
     * 深度拷贝 ProviderConfig
     */
    default void updateProviderConfig( ProviderConfig source, ProviderConfig target ) {
        if ( source == null || target == null ) {
            return;
        }
        // apiKey：非空才拷贝
        if ( !isEmpty( source.getApiKey() ) ) {
            target.setApiKey( source.getApiKey() );
        }
        // apiBase：非空才拷贝
        if ( !isEmpty( source.getApiBase() ) ) {
            target.setApiBase( source.getApiBase() );
        }
        // modelConfigs：非空才拷贝
        if ( !isEmpty( source.getModelConfigs() ) ) {
            target.setModelConfigs( source.getModelConfigs() );
        }
        // extraHeaders：非空才拷贝
        if ( !isEmpty( source.getExtraHeaders() ) ) {
            target.setExtraHeaders( source.getExtraHeaders() );
        }
    }

    /**
     * 深度拷贝 GatewayConfig
     */
    default void updateGatewayConfig( GatewayConfig source, GatewayConfig target ) {
        if ( source == null ) {
            return;
        }
        // host：非空才拷贝
        if ( !isEmpty( source.getHost() ) ) {
            target.setHost( source.getHost() );
        }
        // port：大于 0 才拷贝
        if ( source.getPort() > 0 ) {
            target.setPort( source.getPort() );
        }
        // heartbeat：深度拷贝
        updateHeartbeatConfig( source.getHeartbeat(), target.getHeartbeat() );
    }

    /**
     * 深度拷贝 ToolsConfig
     */
    default void updateToolsConfig( ToolsConfig source, ToolsConfig target ) {
        if ( source == null ) {
            return;
        }
        // 基础布尔字段直接拷贝
        target.setRestrictToWorkspace( source.isRestrictToWorkspace() );

        // web：非空才拷贝
        if ( source.getWeb() != null && !isAllFieldsEmpty( source.getWeb() ) ) {
            target.setWeb( source.getWeb() );
        }
        // exec：非空才拷贝
        if ( source.getExec() != null && !isAllFieldsEmpty( source.getExec() ) ) {
            target.setExec( source.getExec() );
        }
        // mcpServers：非空才拷贝
        if ( !isEmpty( source.getMcpServers() ) ) {
            target.setMcpServers( source.getMcpServers() );
        }
    }

    /**
     * 深度拷贝 PluginsConfig
     */
    default void updatePluginsConfig( PluginsConfig source, PluginsConfig target ) {
        if ( source == null ) {
            return;
        }
        // items：非空才拷贝
        if ( !isEmpty( source.getItems() ) ) {
            // 合并策略：逐个检查插件配置
            Map<String, PluginConfig> sourceItems = source.getItems();
            Map<String, PluginConfig> targetItems = target.getItems();

            for ( Map.Entry<String, PluginConfig> entry : sourceItems.entrySet() ) {
                String key = entry.getKey();
                PluginConfig sourcePlugin = entry.getValue();
                PluginConfig targetPlugin = targetItems.get( key );

                if ( targetPlugin == null ) {
                    // 目标不存在，直接添加
                    targetItems.put( key, sourcePlugin );
                } else {
                    // 目标存在，深度拷贝
                    updatePluginConfig( sourcePlugin, targetPlugin );
                }
            }
        }
    }

    /**
     * 深度拷贝 PluginConfig
     */
    default void updatePluginConfig( PluginConfig source, PluginConfig target ) {
        if ( source == null ) {
            return;
        }
        // name：非空才拷贝
        if ( !isEmpty( source.getName() ) ) {
            target.setName( source.getName() );
        }
        // enabled：直接拷贝
        target.setEnabled( source.isEnabled() );
        // path：非空才拷贝
        if ( !isEmpty( source.getPath() ) ) {
            target.setPath( source.getPath() );
        }
        // priority：直接拷贝
        target.setPriority( source.getPriority() );
    }
}
