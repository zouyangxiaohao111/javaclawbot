package agent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具使用上下文
 *
 * 包含工具执行所需的所有上下文信息
 */
public class ToolUseContext {
    /** 可用工具列表 */
    private List<Map<String, Object>> tools;

    /** 主循环模型 */
    private String mainLoopModel;

    /** MCP 客户端 */
    private List<Object> mcpClients;

    /** 工具权限上下文 */
    private Object toolPermissionContext;

    /** 自定义系统提示词 */
    private String customSystemPrompt;

    /** 追加到系统提示词的内容 */
    private String appendSystemPrompt;

    /** 工作目录 */
    private String workspace;

    /** 是否限制在工作目录内 */
    private boolean restrictToWorkspace;

    /** Agent ID */
    private String agentId;

    /** AbortController */
    private AtomicBoolean abortController;

    /** 当前消息列表 */
    private List<Map<String, Object>> messages;

    private ToolUseContext(Builder builder) {
        this.tools = builder.tools;
        this.mainLoopModel = builder.mainLoopModel;
        this.mcpClients = builder.mcpClients;
        this.toolPermissionContext = builder.toolPermissionContext;
        this.customSystemPrompt = builder.customSystemPrompt;
        this.appendSystemPrompt = builder.appendSystemPrompt;
        this.workspace = builder.workspace;
        this.restrictToWorkspace = builder.restrictToWorkspace;
        this.agentId = builder.agentId;
        this.abortController = builder.abortController;
        this.messages = builder.messages;
    }

    public List<Map<String, Object>> getTools() { return tools; }
    public String getMainLoopModel() { return mainLoopModel; }
    public List<Object> getMcpClients() { return mcpClients; }
    public Object getToolPermissionContext() { return toolPermissionContext; }
    public String getCustomSystemPrompt() { return customSystemPrompt; }
    public String getAppendSystemPrompt() { return appendSystemPrompt; }
    public String getWorkspace() { return workspace; }
    public boolean isRestrictToWorkspace() { return restrictToWorkspace; }
    public String getAgentId() { return agentId; }
    public AtomicBoolean getAbortController() { return abortController; }
    public List<Map<String, Object>> getMessages() { return messages; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Map<String, Object>> tools;
        private String mainLoopModel;
        private List<Object> mcpClients;
        private Object toolPermissionContext;
        private String customSystemPrompt;
        private String appendSystemPrompt;
        private String workspace;
        private boolean restrictToWorkspace;
        private String agentId;
        private AtomicBoolean abortController;
        private List<Map<String, Object>> messages;

        public Builder tools(List<Map<String, Object>> tools) {
            this.tools = tools;
            return this;
        }

        public Builder mainLoopModel(String mainLoopModel) {
            this.mainLoopModel = mainLoopModel;
            return this;
        }

        public Builder mcpClients(List<Object> mcpClients) {
            this.mcpClients = mcpClients;
            return this;
        }

        public Builder toolPermissionContext(Object toolPermissionContext) {
            this.toolPermissionContext = toolPermissionContext;
            return this;
        }

        public Builder customSystemPrompt(String customSystemPrompt) {
            this.customSystemPrompt = customSystemPrompt;
            return this;
        }

        public Builder appendSystemPrompt(String appendSystemPrompt) {
            this.appendSystemPrompt = appendSystemPrompt;
            return this;
        }

        public Builder workspace(String workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder restrictToWorkspace(boolean restrictToWorkspace) {
            this.restrictToWorkspace = restrictToWorkspace;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder abortController(AtomicBoolean abortController) {
            this.abortController = abortController;
            return this;
        }

        public Builder messages(List<Map<String, Object>> messages) {
            this.messages = messages;
            return this;
        }

        public ToolUseContext build() {
            return new ToolUseContext(this);
        }
    }
}
