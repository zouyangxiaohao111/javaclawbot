package agent.tool;

import agent.subagent.team.InProcessTeammateTaskState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 工具使用上下文
 *
 * 包含工具执行所需的所有上下文信息
 *
 * 对应 Open-ClaudeCode: src/Tool.ts - ToolUseContext
 */
public class ToolUseContext {
    // ==================== 基础字段 ====================
    /** 可用工具列表 */
    private List<Map<String, Object>> tools;

    /** 工具视图（用于工具查找，支持优先级：local > mcp > shared） */
    private ToolView toolView;

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

    /** Agent 类型 */
    private String agentType;

    /** AbortController */
    private AtomicBoolean abortController;

    /** 当前消息列表 */
    private List<Map<String, Object>> messages;

    /** Session ID */
    private String sessionId;

    // ==================== AppState 回调 ====================
    /** AppState */
    private Object appState;

    /** setAppState 回调 */
    private Object setAppState;

    /** setAppStateForTasks 回调 */
    private Object setAppStateForTasks;

    // ==================== 上下文隔离字段 (子代理需要) ====================
    /** 嵌套内存附件触发器 */
    private Set<String> nestedMemoryAttachmentTriggers;

    /** 已加载的嵌套内存路径 */
    private Set<String> loadedNestedMemoryPaths;

    /** 动态技能目录触发器 */
    private Set<String> dynamicSkillDirTriggers;

    /** 通过发现加载的技能名称 */
    private Set<String> discoveredSkillNames;

    /** 工具决策状态 */
    private Object toolDecisions;

    /** 内容替换状态 (用于 prompt cache 稳定性) */
    private Object contentReplacementState;

    /** 本地拒绝跟踪状态 */
    private Object localDenialTracking;

    // ==================== 回调函数 ====================
    /** 设置进行中的工具调用 ID */
    private Consumer<Set<String>> setInProgressToolUseIDs;

    /** 设置响应长度 */
    private Consumer<Long> setResponseLength;

    /** 推送 API 指标条目 */
    private Consumer<Long> pushApiMetricsEntry;

    /** 更新文件历史状态 */
    private Runnable updateFileHistoryState;

    /** 更新归属状态 */
    private java.util.function.Function<Object, Object> updateAttributionState;

    // ==================== 查询追踪 ====================
    /** 查询追踪状态 */
    private QueryTracking queryTracking;

    /**
     * 查询追踪数据类
     */
    public static class QueryTracking {
        private String chainId;
        private int depth;

        public QueryTracking() {
            this.chainId = UUID.randomUUID().toString();
            this.depth = 0;
        }

        public QueryTracking(String chainId, int depth) {
            this.chainId = chainId;
            this.depth = depth;
        }

        public String getChainId() { return chainId; }
        public void setChainId(String chainId) { this.chainId = chainId; }
        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }

        /**
         * 创建子追踪链（深度 +1）
         */
        public QueryTracking child() {
            return new QueryTracking(this.chainId, this.depth + 1);
        }
    }

    // ==================== UI 回调 (子代理为 undefined) ====================
    /** 添加通知回调 */
    private Object addNotification;

    /** 设置工具 JSX 回调 */
    private Object setToolJSX;

    /** 设置流模式回调 */
    private Object setStreamMode;

    /** 设置 SDK 状态回调 */
    private Object setSDKStatus;

    /** 打开消息选择器回调 */
    private Object openMessageSelector;

    // ==================== 其他字段 ====================
    /** 文件读取限制 */
    private Object fileReadingLimits;

    /** 用户修改状态 */
    private Object userModified;

    /** 关键系统提醒 (实验性) */
    private String criticalSystemReminder_EXPERIMENTAL;

    /** 是否需要调用 canUseTool 检查 */
    private Boolean requireCanUseTool;

    /** 非交互会话标志 */
    private Boolean isNonInteractiveSession;

    private ToolUseContext(Builder builder) {
        // 基础字段
        this.tools = builder.tools;
        this.toolView = builder.toolView;
        this.mainLoopModel = builder.mainLoopModel;
        this.mcpClients = builder.mcpClients;
        this.toolPermissionContext = builder.toolPermissionContext;
        this.customSystemPrompt = builder.customSystemPrompt;
        this.appendSystemPrompt = builder.appendSystemPrompt;
        this.workspace = builder.workspace;
        this.restrictToWorkspace = builder.restrictToWorkspace;
        this.agentId = builder.agentId;
        this.agentType = builder.agentType;
        this.abortController = builder.abortController;
        this.messages = builder.messages;
        this.sessionId = builder.sessionId;

        // AppState 回调
        this.appState = builder.appState;
        this.setAppState = builder.setAppState;
        this.setAppStateForTasks = builder.setAppStateForTasks;

        // 上下文隔离字段
        this.nestedMemoryAttachmentTriggers = builder.nestedMemoryAttachmentTriggers;
        this.loadedNestedMemoryPaths = builder.loadedNestedMemoryPaths;
        this.dynamicSkillDirTriggers = builder.dynamicSkillDirTriggers;
        this.discoveredSkillNames = builder.discoveredSkillNames;
        this.toolDecisions = builder.toolDecisions;
        this.contentReplacementState = builder.contentReplacementState;
        this.localDenialTracking = builder.localDenialTracking;

        // 回调函数
        this.setInProgressToolUseIDs = builder.setInProgressToolUseIDs;
        this.setResponseLength = builder.setResponseLength;
        this.pushApiMetricsEntry = builder.pushApiMetricsEntry;
        this.updateFileHistoryState = builder.updateFileHistoryState;
        this.updateAttributionState = builder.updateAttributionState;

        // 查询追踪
        this.queryTracking = builder.queryTracking;

        // UI 回调
        this.addNotification = builder.addNotification;
        this.setToolJSX = builder.setToolJSX;
        this.setStreamMode = builder.setStreamMode;
        this.setSDKStatus = builder.setSDKStatus;
        this.openMessageSelector = builder.openMessageSelector;

        // 其他字段
        this.fileReadingLimits = builder.fileReadingLimits;
        this.userModified = builder.userModified;
        this.criticalSystemReminder_EXPERIMENTAL = builder.criticalSystemReminder_EXPERIMENTAL;
        this.requireCanUseTool = builder.requireCanUseTool;
        this.isNonInteractiveSession = builder.isNonInteractiveSession;
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

    /**
     * 根据工具名称获取工具
     * 优先从 toolView 查找（支持优先级：local > mcp > shared）
     */
    public Tool getTool(String toolName) {
        if (toolName == null) return null;

        // 优先从 toolView 查找
        if (toolView != null) {
            Object tool = toolView.get(toolName);
            if (tool instanceof Tool) {
                return (Tool) tool;
            }
        }

        // toolView 没有找到，返回 null（无法从 tools 定义获取 Tool 实例）
        return null;
    }

    /**
     * 检查是否是进程内队友
     */
    public boolean isInProcessTeammate() {
        // 检查 agentId 是否包含 in_process_teammate 前缀
        return agentId != null && agentId.startsWith("in_process_teammate");
    }

    /**
     * 获取 AppState
     * 对应 Open-ClaudeCode: toolUseContext.getAppState()
     */
    public Object getAppState() {
        return appState;
    }

    /**
     * 获取 setAppStateForTasks
     * 对应 Open-ClaudeCode: toolUseContext.setAppStateForTasks
     */
    public Object getSetAppStateForTasks() {
        return setAppStateForTasks != null ? setAppStateForTasks : setAppState;
    }

    /**
     * 获取 setAppState
     * 对应 Open-ClaudeCode: toolUseContext.setAppState
     */
    public Object getSetAppState() {
        return setAppState;
    }

    /**
     * 获取 Session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    // ==================== 新增字段的 Getter ====================

    public String getAgentType() { return agentType; }
    public Set<String> getNestedMemoryAttachmentTriggers() { return nestedMemoryAttachmentTriggers; }
    public Set<String> getLoadedNestedMemoryPaths() { return loadedNestedMemoryPaths; }
    public Set<String> getDynamicSkillDirTriggers() { return dynamicSkillDirTriggers; }
    public Set<String> getDiscoveredSkillNames() { return discoveredSkillNames; }
    public Object getToolDecisions() { return toolDecisions; }
    public Object getContentReplacementState() { return contentReplacementState; }
    public Object getLocalDenialTracking() { return localDenialTracking; }
    public Consumer<Set<String>> getSetInProgressToolUseIDs() { return setInProgressToolUseIDs; }
    public Consumer<Long> getSetResponseLength() { return setResponseLength; }
    public Consumer<Long> getPushApiMetricsEntry() { return pushApiMetricsEntry; }
    public Runnable getUpdateFileHistoryState() { return updateFileHistoryState; }
    public java.util.function.Function<Object, Object> getUpdateAttributionState() { return updateAttributionState; }
    public QueryTracking getQueryTracking() { return queryTracking; }
    public Object getAddNotification() { return addNotification; }
    public Object getSetToolJSX() { return setToolJSX; }
    public Object getSetStreamMode() { return setStreamMode; }
    public Object getSetSDKStatus() { return setSDKStatus; }
    public Object getOpenMessageSelector() { return openMessageSelector; }
    public Object getFileReadingLimits() { return fileReadingLimits; }
    public Object getUserModified() { return userModified; }
    public String getCriticalSystemReminder_EXPERIMENTAL() { return criticalSystemReminder_EXPERIMENTAL; }
    public Boolean getRequireCanUseTool() { return requireCanUseTool; }
    public Boolean getIsNonInteractiveSession() { return isNonInteractiveSession; }
    public ToolView getToolView() { return toolView; }

    // ==================== Setters (用于 Builder 链式调用) ====================

    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }
    public void setToolView(ToolView toolView) { this.toolView = toolView; }
    public void setNestedMemoryAttachmentTriggers(Set<String> nestedMemoryAttachmentTriggers) { this.nestedMemoryAttachmentTriggers = nestedMemoryAttachmentTriggers; }
    public void setLoadedNestedMemoryPaths(Set<String> loadedNestedMemoryPaths) { this.loadedNestedMemoryPaths = loadedNestedMemoryPaths; }
    public void setDynamicSkillDirTriggers(Set<String> dynamicSkillDirTriggers) { this.dynamicSkillDirTriggers = dynamicSkillDirTriggers; }
    public void setDiscoveredSkillNames(Set<String> discoveredSkillNames) { this.discoveredSkillNames = discoveredSkillNames; }
    public void setQueryTracking(QueryTracking queryTracking) { this.queryTracking = queryTracking; }
    public void setIsNonInteractiveSession(Boolean isNonInteractiveSession) { this.isNonInteractiveSession = isNonInteractiveSession; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        // 基础字段
        private List<Map<String, Object>> tools;
        private ToolView toolView;
        private String mainLoopModel;
        private List<Object> mcpClients;
        private Object toolPermissionContext;
        private String customSystemPrompt;
        private String appendSystemPrompt;
        private String workspace;
        private boolean restrictToWorkspace;
        private String agentId;
        private String agentType;
        private AtomicBoolean abortController;
        private List<Map<String, Object>> messages;
        private String sessionId;

        // AppState 回调
        private Object appState;
        private Object setAppState;
        private Object setAppStateForTasks;

        // 上下文隔离字段
        private Set<String> nestedMemoryAttachmentTriggers;
        private Set<String> loadedNestedMemoryPaths;
        private Set<String> dynamicSkillDirTriggers;
        private Set<String> discoveredSkillNames;
        private Object toolDecisions;
        private Object contentReplacementState;
        private Object localDenialTracking;

        // 回调函数
        private Consumer<Set<String>> setInProgressToolUseIDs;
        private Consumer<Long> setResponseLength;
        private Consumer<Long> pushApiMetricsEntry;
        private Runnable updateFileHistoryState;
        private java.util.function.Function<Object, Object> updateAttributionState;

        // 查询追踪
        private QueryTracking queryTracking;

        // UI 回调
        private Object addNotification;
        private Object setToolJSX;
        private Object setStreamMode;
        private Object setSDKStatus;
        private Object openMessageSelector;

        // 其他字段
        private Object fileReadingLimits;
        private Object userModified;
        private String criticalSystemReminder_EXPERIMENTAL;
        private Boolean requireCanUseTool;
        private Boolean isNonInteractiveSession;

        public Builder tools(List<Map<String, Object>> tools) {
            this.tools = tools;
            return this;
        }

        public Builder toolView(ToolView toolView) {
            this.toolView = toolView;
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

        public Builder agentType(String agentType) {
            this.agentType = agentType;
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

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder appState(Object appState) {
            this.appState = appState;
            return this;
        }

        public Builder setAppState(Object setAppState) {
            this.setAppState = setAppState;
            return this;
        }

        public Builder setAppStateForTasks(Object setAppStateForTasks) {
            this.setAppStateForTasks = setAppStateForTasks;
            return this;
        }

        public Builder nestedMemoryAttachmentTriggers(Set<String> nestedMemoryAttachmentTriggers) {
            this.nestedMemoryAttachmentTriggers = nestedMemoryAttachmentTriggers;
            return this;
        }

        public Builder loadedNestedMemoryPaths(Set<String> loadedNestedMemoryPaths) {
            this.loadedNestedMemoryPaths = loadedNestedMemoryPaths;
            return this;
        }

        public Builder dynamicSkillDirTriggers(Set<String> dynamicSkillDirTriggers) {
            this.dynamicSkillDirTriggers = dynamicSkillDirTriggers;
            return this;
        }

        public Builder discoveredSkillNames(Set<String> discoveredSkillNames) {
            this.discoveredSkillNames = discoveredSkillNames;
            return this;
        }

        public Builder toolDecisions(Object toolDecisions) {
            this.toolDecisions = toolDecisions;
            return this;
        }

        public Builder contentReplacementState(Object contentReplacementState) {
            this.contentReplacementState = contentReplacementState;
            return this;
        }

        public Builder localDenialTracking(Object localDenialTracking) {
            this.localDenialTracking = localDenialTracking;
            return this;
        }

        public Builder setInProgressToolUseIDs(Consumer<Set<String>> setInProgressToolUseIDs) {
            this.setInProgressToolUseIDs = setInProgressToolUseIDs;
            return this;
        }

        public Builder setResponseLength(Consumer<Long> setResponseLength) {
            this.setResponseLength = setResponseLength;
            return this;
        }

        public Builder pushApiMetricsEntry(Consumer<Long> pushApiMetricsEntry) {
            this.pushApiMetricsEntry = pushApiMetricsEntry;
            return this;
        }

        public Builder updateFileHistoryState(Runnable updateFileHistoryState) {
            this.updateFileHistoryState = updateFileHistoryState;
            return this;
        }

        public Builder updateAttributionState(java.util.function.Function<Object, Object> updateAttributionState) {
            this.updateAttributionState = updateAttributionState;
            return this;
        }

        public Builder queryTracking(QueryTracking queryTracking) {
            this.queryTracking = queryTracking;
            return this;
        }

        public Builder addNotification(Object addNotification) {
            this.addNotification = addNotification;
            return this;
        }

        public Builder setToolJSX(Object setToolJSX) {
            this.setToolJSX = setToolJSX;
            return this;
        }

        public Builder setStreamMode(Object setStreamMode) {
            this.setStreamMode = setStreamMode;
            return this;
        }

        public Builder setSDKStatus(Object setSDKStatus) {
            this.setSDKStatus = setSDKStatus;
            return this;
        }

        public Builder openMessageSelector(Object openMessageSelector) {
            this.openMessageSelector = openMessageSelector;
            return this;
        }

        public Builder fileReadingLimits(Object fileReadingLimits) {
            this.fileReadingLimits = fileReadingLimits;
            return this;
        }

        public Builder userModified(Object userModified) {
            this.userModified = userModified;
            return this;
        }

        public Builder criticalSystemReminder_EXPERIMENTAL(String criticalSystemReminder_EXPERIMENTAL) {
            this.criticalSystemReminder_EXPERIMENTAL = criticalSystemReminder_EXPERIMENTAL;
            return this;
        }

        public Builder requireCanUseTool(Boolean requireCanUseTool) {
            this.requireCanUseTool = requireCanUseTool;
            return this;
        }

        public Builder isNonInteractiveSession(Boolean isNonInteractiveSession) {
            this.isNonInteractiveSession = isNonInteractiveSession;
            return this;
        }

        public ToolUseContext build() {
            return new ToolUseContext(this);
        }
    }
}
