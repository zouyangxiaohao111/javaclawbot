package agent.subagent.fork;

import agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

/**
 * Cache 安全参数
 *
 * 这些参数必须与父代理的请求完全相同，才能共享 Prompt Cache。
 * 用于 Fork 子代理的请求构造。
 */
public class CacheSafeParams {
    /** 系统提示词 - 必须与父代理完全相同 */
    private final String systemPrompt;

    /** 用户上下文 - 预置到消息前 */
    private final Map<String, String> userContext;

    /** 系统上下文 - 追加到系统提示词 */
    private final Map<String, String> systemContext;

    /** 工具使用上下文 */
    private final ToolUseContext toolUseContext;

    /** Fork 上下文消息 - 父代理的完整消息历史 */
    private final List<Map<String, Object>> forkContextMessages;

    public CacheSafeParams(
            String systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            ToolUseContext toolUseContext,
            List<Map<String, Object>> forkContextMessages
    ) {
        this.systemPrompt = systemPrompt;
        this.userContext = userContext;
        this.systemContext = systemContext;
        this.toolUseContext = toolUseContext;
        this.forkContextMessages = forkContextMessages;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public Map<String, String> getUserContext() { return userContext; }
    public Map<String, String> getSystemContext() { return systemContext; }
    public ToolUseContext getToolUseContext() { return toolUseContext; }
    public List<Map<String, Object>> getForkContextMessages() { return forkContextMessages; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String systemPrompt;
        private Map<String, String> userContext;
        private Map<String, String> systemContext;
        private ToolUseContext toolUseContext;
        private List<Map<String, Object>> forkContextMessages;

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
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

        public Builder toolUseContext(ToolUseContext toolUseContext) {
            this.toolUseContext = toolUseContext;
            return this;
        }

        public Builder forkContextMessages(List<Map<String, Object>> forkContextMessages) {
            this.forkContextMessages = forkContextMessages;
            return this;
        }

        public CacheSafeParams build() {
            return new CacheSafeParams(
                    systemPrompt, userContext, systemContext,
                    toolUseContext, forkContextMessages
            );
        }
    }
}
