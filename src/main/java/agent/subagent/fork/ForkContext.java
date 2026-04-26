package agent.subagent.fork;

import java.util.Map;

/**
 * Fork 上下文
 *
 * 包含 Fork 子代理所需的所有上下文信息：
 * - 父代理 ID
 * - Fork 指令
 * - 父代理最后一条消息
 * - 父代理系统提示词
 */
public class ForkContext {
    /** 父代理 ID */
    private final String parentAgentId;

    /** Fork 指令（子代理要执行的任务） */
    private final String directive;

    /** 父代理最后一条 assistant 消息（包含所有 tool_use） */
    private final Map<String, Object> parentAssistantMessage;

    /** 父代理系统提示词 */
    private final String parentSystemPrompt;

    /** 父代理消息历史 */
    private final java.util.List<Map<String, Object>> parentMessages;

    /** 用户上下文 */
    private final Map<String, String> userContext;

    /** 系统上下文 */
    private final Map<String, String> systemContext;

    /** 可选的标签/描述 */
    private final String label;

    private ForkContext(Builder builder) {
        this.parentAgentId = builder.parentAgentId;
        this.directive = builder.directive;
        this.parentAssistantMessage = builder.parentAssistantMessage;
        this.parentSystemPrompt = builder.parentSystemPrompt;
        this.parentMessages = builder.parentMessages;
        this.userContext = builder.userContext;
        this.systemContext = builder.systemContext;
        this.label = builder.label;
    }

    public String getParentAgentId() { return parentAgentId; }
    public String getDirective() { return directive; }
    public Map<String, Object> getParentAssistantMessage() { return parentAssistantMessage; }
    public String getParentSystemPrompt() { return parentSystemPrompt; }
    public java.util.List<Map<String, Object>> getParentMessages() { return parentMessages; }
    public Map<String, String> getUserContext() { return userContext; }
    public Map<String, String> getSystemContext() { return systemContext; }
    public String getLabel() { return label; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String parentAgentId;
        private String directive;
        private Map<String, Object> parentAssistantMessage;
        private String parentSystemPrompt;
        private java.util.List<Map<String, Object>> parentMessages;
        private Map<String, String> userContext;
        private Map<String, String> systemContext;
        private String label;

        public Builder parentAgentId(String parentAgentId) {
            this.parentAgentId = parentAgentId;
            return this;
        }

        public Builder directive(String directive) {
            this.directive = directive;
            return this;
        }

        public Builder parentAssistantMessage(Map<String, Object> parentAssistantMessage) {
            this.parentAssistantMessage = parentAssistantMessage;
            return this;
        }

        public Builder parentSystemPrompt(String parentSystemPrompt) {
            this.parentSystemPrompt = parentSystemPrompt;
            return this;
        }

        public Builder parentMessages(java.util.List<Map<String, Object>> parentMessages) {
            this.parentMessages = parentMessages;
            return this;
        }

        public Builder userContext(Map<String, String> userContext) {
            this.userContext = userContext;
            return this;
        }

        public Builder systemContext(Map<String, String> systemContext) {
            this.systemContext = systemContext;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public ForkContext build() {
            return new ForkContext(this);
        }
    }
}
