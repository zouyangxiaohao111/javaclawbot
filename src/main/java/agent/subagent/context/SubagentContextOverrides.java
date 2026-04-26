package agent.subagent.context;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 子代理上下文覆盖选项
 *
 * 对应 Open-ClaudeCode: src/utils/forkedAgent.ts - SubagentContextOverrides
 *
 * 用于 createSubagentContext() 创建隔离的 ToolUseContext。
 * 通过 override 选项可以：
 * - 覆盖特定字段（如 custom options、agentId、messages）
 * - 显式共享特定的回调函数（shareSetAppState 等）
 */
public class SubagentContextOverrides {

    /** 覆盖 options 对象（如自定义工具、模型等） */
    private Map<String, Object> options;

    /** 覆盖 agentId（子代理拥有自己的 ID） */
    private String agentId;

    /** 覆盖 agentType（子代理拥有特定类型） */
    private String agentType;

    /** 覆盖消息数组 */
    private List<Map<String, Object>> messages;

    /** 覆盖 readFileState（如使用新的缓存而非克隆） */
    private Map<String, Object> readFileState;

    /** 覆盖 abortController */
    private AtomicBoolean abortController;

    /** 覆盖 getAppState 函数 */
    private Supplier<Map<String, Object>> getAppState;

    /**
     * 显式共享父代理的 setAppState 回调。
     * 用于需要更新共享状态的可交互子代理。
     * 默认值: false（隔离的空操作）
     */
    private boolean shareSetAppState = false;

    /**
     * 显式共享父代理的 setResponseLength 回调。
     * 用于需要向父代理贡献响应指标的可交互子代理。
     * 默认值: false（隔离的空操作）
     */
    private boolean shareSetResponseLength = false;

    /**
     * 显式共享父代理的 abortController。
     * 用于应该随父代理一起中止的可交互子代理。
     * 注意: 仅在未提供 abortController override 时生效。
     * 默认值: false（新建链接到父代理的控制器）
     */
    private boolean shareAbortController = false;

    /** 关键系统提醒，在每个用户轮次重新注入 */
    private String criticalSystemReminder_EXPERIMENTAL;

    /** 当为 true 时，即使 hooks 自动批准也必须始终调用 canUseTool。
     *  由 speculation 用于 overlay 文件路径重写。*/
    private boolean requireCanUseTool = false;

    /** 覆盖 replacement state - 由 resumeAgentBackground 使用以线程化
     * 从恢复的 sidechain 重建的状态，以便重新替换相同的结果（prompt cache 稳定性）。*/
    private Object contentReplacementState;

    // =====================
    // Getters
    // =====================

    public Map<String, Object> getOptions() { return options; }
    public String getAgentId() { return agentId; }
    public String getAgentType() { return agentType; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public Map<String, Object> getReadFileState() { return readFileState; }
    public AtomicBoolean getAbortController() { return abortController; }
    public Supplier<Map<String, Object>> getGetAppState() { return getAppState; }
    public boolean isShareSetAppState() { return shareSetAppState; }
    public boolean isShareSetResponseLength() { return shareSetResponseLength; }
    public boolean isShareAbortController() { return shareAbortController; }
    public String getCriticalSystemReminder_EXPERIMENTAL() { return criticalSystemReminder_EXPERIMENTAL; }
    public boolean isRequireCanUseTool() { return requireCanUseTool; }
    public Object getContentReplacementState() { return contentReplacementState; }

    // =====================
    // Setters (fluent)
    // =====================

    public SubagentContextOverrides options(Map<String, Object> options) {
        this.options = options;
        return this;
    }

    public SubagentContextOverrides agentId(String agentId) {
        this.agentId = agentId;
        return this;
    }

    public SubagentContextOverrides agentType(String agentType) {
        this.agentType = agentType;
        return this;
    }

    public SubagentContextOverrides messages(List<Map<String, Object>> messages) {
        this.messages = messages;
        return this;
    }

    public SubagentContextOverrides readFileState(Map<String, Object> readFileState) {
        this.readFileState = readFileState;
        return this;
    }

    public SubagentContextOverrides abortController(AtomicBoolean abortController) {
        this.abortController = abortController;
        return this;
    }

    public SubagentContextOverrides getAppState(Supplier<Map<String, Object>> getAppState) {
        this.getAppState = getAppState;
        return this;
    }

    public SubagentContextOverrides shareSetAppState(boolean shareSetAppState) {
        this.shareSetAppState = shareSetAppState;
        return this;
    }

    public SubagentContextOverrides shareSetResponseLength(boolean shareSetResponseLength) {
        this.shareSetResponseLength = shareSetResponseLength;
        return this;
    }

    public SubagentContextOverrides shareAbortController(boolean shareAbortController) {
        this.shareAbortController = shareAbortController;
        return this;
    }

    public SubagentContextOverrides criticalSystemReminder_EXPERIMENTAL(String criticalSystemReminder_EXPERIMENTAL) {
        this.criticalSystemReminder_EXPERIMENTAL = criticalSystemReminder_EXPERIMENTAL;
        return this;
    }

    public SubagentContextOverrides requireCanUseTool(boolean requireCanUseTool) {
        this.requireCanUseTool = requireCanUseTool;
        return this;
    }

    public SubagentContextOverrides contentReplacementState(Object contentReplacementState) {
        this.contentReplacementState = contentReplacementState;
        return this;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SubagentContextOverrides instance = new SubagentContextOverrides();

        public Builder options(Map<String, Object> options) {
            instance.options = options;
            return this;
        }

        public Builder agentId(String agentId) {
            instance.agentId = agentId;
            return this;
        }

        public Builder agentType(String agentType) {
            instance.agentType = agentType;
            return this;
        }

        public Builder messages(List<Map<String, Object>> messages) {
            instance.messages = messages;
            return this;
        }

        public Builder readFileState(Map<String, Object> readFileState) {
            instance.readFileState = readFileState;
            return this;
        }

        public Builder abortController(AtomicBoolean abortController) {
            instance.abortController = abortController;
            return this;
        }

        public Builder getAppState(Supplier<Map<String, Object>> getAppState) {
            instance.getAppState = getAppState;
            return this;
        }

        public Builder shareSetAppState(boolean shareSetAppState) {
            instance.shareSetAppState = shareSetAppState;
            return this;
        }

        public Builder shareSetResponseLength(boolean shareSetResponseLength) {
            instance.shareSetResponseLength = shareSetResponseLength;
            return this;
        }

        public Builder shareAbortController(boolean shareAbortController) {
            instance.shareAbortController = shareAbortController;
            return this;
        }

        public Builder criticalSystemReminder_EXPERIMENTAL(String criticalSystemReminder_EXPERIMENTAL) {
            instance.criticalSystemReminder_EXPERIMENTAL = criticalSystemReminder_EXPERIMENTAL;
            return this;
        }

        public Builder requireCanUseTool(boolean requireCanUseTool) {
            instance.requireCanUseTool = requireCanUseTool;
            return this;
        }

        public Builder contentReplacementState(Object contentReplacementState) {
            instance.contentReplacementState = contentReplacementState;
            return this;
        }

        public SubagentContextOverrides build() {
            return instance;
        }
    }
}
