package agent.subagent.fork;

import agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

/**
 * Fork 上下文构建器
 *
 * 用于从父代理的上下文构建 Fork 子代理所需的参数
 */
public class ForkContextBuilder {

    private String parentAgentId;
    private String directive;
    private Map<String, Object> parentAssistantMessage;
    private String parentSystemPrompt;
    private List<Map<String, Object>> parentMessages;
    private Map<String, String> userContext;
    private Map<String, String> systemContext;
    private ToolUseContext toolUseContext;

    public ForkContextBuilder parentAgentId(String parentAgentId) {
        this.parentAgentId = parentAgentId;
        return this;
    }

    public ForkContextBuilder directive(String directive) {
        this.directive = directive;
        return this;
    }

    public ForkContextBuilder parentAssistantMessage(Map<String, Object> parentAssistantMessage) {
        this.parentAssistantMessage = parentAssistantMessage;
        return this;
    }

    public ForkContextBuilder parentSystemPrompt(String parentSystemPrompt) {
        this.parentSystemPrompt = parentSystemPrompt;
        return this;
    }

    public ForkContextBuilder parentMessages(List<Map<String, Object>> parentMessages) {
        this.parentMessages = parentMessages;
        return this;
    }

    public ForkContextBuilder userContext(Map<String, String> userContext) {
        this.userContext = userContext;
        return this;
    }

    public ForkContextBuilder systemContext(Map<String, String> systemContext) {
        this.systemContext = systemContext;
        return this;
    }

    public ForkContextBuilder toolUseContext(ToolUseContext toolUseContext) {
        this.toolUseContext = toolUseContext;
        return this;
    }

    /**
     * 构建 ForkContext
     */
    public ForkContext build() {
        return ForkContext.builder()
                .parentAgentId(parentAgentId)
                .directive(directive)
                .parentAssistantMessage(parentAssistantMessage)
                .parentSystemPrompt(parentSystemPrompt)
                .parentMessages(parentMessages)
                .userContext(userContext)
                .systemContext(systemContext)
                .build();
    }

    /**
     * 构建 CacheSafeParams
     */
    public CacheSafeParams buildCacheSafeParams() {
        return new CacheSafeParams(
                parentSystemPrompt,
                userContext,
                systemContext,
                toolUseContext,
                parentMessages
        );
    }

    /**
     * 构建 Fork 子代理的完整消息列表
     */
    public List<Map<String, Object>> buildForkedMessages() {
        return ForkAgentDefinition.buildForkedMessages(directive, parentMessages);
    }
}
