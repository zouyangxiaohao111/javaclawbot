package agent.subagent.context;

import agent.tool.ToolUseContext;
import agent.subagent.framework.ProgressTracker;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 子代理上下文隔离
 *
 * 为子代理创建隔离的上下文，防止对父代理状态的干扰。
 *
 * 隔离的内容：
 * - 文件状态缓存（FileStateCache）
 * - 中止控制器（AbortController）
 * - AppState 访问（禁止权限提示）
 * - 状态修改回调（设为空操作）
 */
public class SubagentContext {

    /** 隔离的文件状态缓存 */
    private final Map<String, Object> fileStateCache;

    /** 中止控制器 */
    private final AtomicBoolean abortSignal;

    /** 父代理的 AbortController（如果共享） */
    private final AtomicBoolean parentAbortSignal;

    /** 是否已中止 */
    private final AtomicReference<Boolean> aborted = new AtomicReference<>(false);

    /** 进度追踪器 */
    private final ProgressTracker progressTracker;

    /** 禁止权限提示的 AppState */
    private final boolean shouldAvoidPermissionPrompts;

    /** 隔离的工具使用上下文 */
    private final ToolUseContext toolUseContext;

    /** 子代理 ID */
    private final String agentId;

    /** 父代理 ID */
    private final String parentAgentId;

    private SubagentContext(Builder builder) {
        this.fileStateCache = builder.fileStateCache;
        this.abortSignal = builder.abortSignal;
        this.parentAbortSignal = builder.parentAbortSignal;
        this.progressTracker = builder.progressTracker;
        this.shouldAvoidPermissionPrompts = builder.shouldAvoidPermissionPrompts;
        this.toolUseContext = builder.toolUseContext;
        this.agentId = builder.agentId;
        this.parentAgentId = builder.parentAgentId;
    }

    public Map<String, Object> getFileStateCache() { return fileStateCache; }
    public AtomicBoolean getAbortSignal() { return abortSignal; }
    public ProgressTracker getProgressTracker() { return progressTracker; }
    public boolean shouldAvoidPermissionPrompts() { return shouldAvoidPermissionPrompts; }
    public ToolUseContext getToolUseContext() { return toolUseContext; }
    public String getAgentId() { return agentId; }
    public String getParentAgentId() { return parentAgentId; }

    /**
     * 检查是否已请求中止
     */
    public boolean isAborted() {
        // 检查自身中止信号
        if (aborted.get()) return true;

        // 检查父代理中止信号（如果有）
        if (parentAbortSignal != null && parentAbortSignal.get()) {
            abort();
            return true;
        }

        // 检查自身中止信号
        if (abortSignal != null && abortSignal.get()) {
            abort();
            return true;
        }

        return false;
    }

    /**
     * 请求中止
     */
    public void abort() {
        aborted.set(true);
        abortSignal.set(true);
    }

    /**
     * 创建隔离的文件状态缓存的副本
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cloneFileStateCache(Map<String, Object> parentCache) {
        if (parentCache == null) {
            return new java.util.concurrent.ConcurrentHashMap<>();
        }
        return new java.util.concurrent.ConcurrentHashMap<>(parentCache);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, Object> fileStateCache;
        private AtomicBoolean abortSignal;
        private AtomicBoolean parentAbortSignal;
        private ProgressTracker progressTracker;
        private boolean shouldAvoidPermissionPrompts = true;
        private ToolUseContext toolUseContext;
        private String agentId;
        private String parentAgentId;

        public Builder fileStateCache(Map<String, Object> fileStateCache) {
            this.fileStateCache = fileStateCache;
            return this;
        }

        public Builder abortSignal(AtomicBoolean abortSignal) {
            this.abortSignal = abortSignal;
            return this;
        }

        public Builder parentAbortSignal(AtomicBoolean parentAbortSignal) {
            this.parentAbortSignal = parentAbortSignal;
            return this;
        }

        public Builder progressTracker(ProgressTracker progressTracker) {
            this.progressTracker = progressTracker;
            return this;
        }

        public Builder shouldAvoidPermissionPrompts(boolean shouldAvoidPermissionPrompts) {
            this.shouldAvoidPermissionPrompts = shouldAvoidPermissionPrompts;
            return this;
        }

        public Builder toolUseContext(ToolUseContext toolUseContext) {
            this.toolUseContext = toolUseContext;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder parentAgentId(String parentAgentId) {
            this.parentAgentId = parentAgentId;
            return this;
        }

        public SubagentContext build() {
            // 设置默认值
            if (abortSignal == null) {
                abortSignal = new AtomicBoolean(false);
            }
            if (progressTracker == null) {
                progressTracker = new agent.subagent.framework.ProgressTracker();
            }
            if (fileStateCache == null) {
                fileStateCache = new java.util.concurrent.ConcurrentHashMap<>();
            }
            return new SubagentContext(this);
        }
    }
}
