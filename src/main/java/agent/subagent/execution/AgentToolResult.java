package agent.subagent.execution;

import java.util.List;
import java.util.Map;

/**
 * AgentTool 执行结果
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/AgentTool.tsx - AgentToolResult
 */
public class AgentToolResult {

    /** 是否成功 */
    private final boolean success;

    /** 结果内容 */
    private final String content;

    /** 错误信息 */
    private final String error;

    /** 生成的工具调用 */
    private final List<Map<String, Object>> toolUses;

    /** 使用量统计 */
    private final Map<String, Object> usage;

    private AgentToolResult(Builder builder) {
        this.success = builder.success;
        this.content = builder.content;
        this.error = builder.error;
        this.toolUses = builder.toolUses;
        this.usage = builder.usage;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public String getError() {
        return error;
    }

    public List<Map<String, Object>> getToolUses() {
        return toolUses;
    }

    public Map<String, Object> getUsage() {
        return usage;
    }

    /**
     * 创建成功结果
     */
    public static AgentToolResult success(String content) {
        return builder()
                .success(true)
                .content(content)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static AgentToolResult failure(String error) {
        return builder()
                .success(false)
                .error(error)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean success;
        private String content;
        private String error;
        private List<Map<String, Object>> toolUses;
        private Map<String, Object> usage;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder toolUses(List<Map<String, Object>> toolUses) {
            this.toolUses = toolUses;
            return this;
        }

        public Builder usage(Map<String, Object> usage) {
            this.usage = usage;
            return this;
        }

        public AgentToolResult build() {
            return new AgentToolResult(this);
        }
    }

    @Override
    public String toString() {
        if (success) {
            return "AgentToolResult{success=true, content='" + content + "'}";
        } else {
            return "AgentToolResult{success=false, error='" + error + "'}";
        }
    }
}
