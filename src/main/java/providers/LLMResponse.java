package providers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型响应
 *
 * 对齐 Python:
 * - content: str | None
 * - tool_calls: list[ToolCallRequest]
 * - finish_reason: str = "stop"
 * - usage: dict[str, int]
 * - reasoning_content: str | None
 * - thinking_blocks: list[dict] | None
 */
public final class LLMResponse {

    /** 模型回复内容（可为 null） */
    private String content;

    /** 工具调用列表（默认空列表） */
    private List<ToolCallRequest> toolCalls = new ArrayList<>();

    /** 结束原因（stop/length/error 等），默认 stop */
    private String finishReason = "stop";

    /** 用量统计（提示/补全/总计），默认空 */
    private Map<String, Integer> usage = new HashMap<>();

    /** 部分模型的推理内容（可为 null） */
    private String reasoningContent;

    /** Anthropic 扩展思考块等（可为 null）。元素是任意 JSON 对象 */
    private List<Map<String, Object>> thinkingBlocks;

    public LLMResponse() {}

    public LLMResponse(String content) {
        this.content = content;
    }

    public LLMResponse(
            String content,
            List<ToolCallRequest> toolCalls,
            String finishReason,
            Map<String, Integer> usage,
            String reasoningContent,
            List<Map<String, Object>> thinkingBlocks
    ) {
        this.content = content;
        if (toolCalls != null) this.toolCalls = toolCalls;
        if (finishReason != null && !finishReason.isBlank()) this.finishReason = finishReason;
        if (usage != null) this.usage = usage;
        this.reasoningContent = reasoningContent;
        this.thinkingBlocks = thinkingBlocks;
    }

    /**
     * 回复内容（可能为 null）
     */
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 工具调用列表
     */
    public List<ToolCallRequest> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallRequest> toolCalls) {
        this.toolCalls = (toolCalls == null) ? new ArrayList<>() : toolCalls;
    }

    /**
     * 结束原因
     */
    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        if (finishReason != null && !finishReason.isBlank()) {
            this.finishReason = finishReason;
        }
    }

    /**
     * 用量统计
     */
    public Map<String, Integer> getUsage() {
        return usage;
    }

    public void setUsage(Map<String, Integer> usage) {
        this.usage = (usage == null) ? new HashMap<>() : usage;
    }

    /**
     * 推理内容（可为 null）
     */
    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    /**
     * 思考块列表（可为 null）
     */
    public List<Map<String, Object>> getThinkingBlocks() {
        return thinkingBlocks;
    }

    public void setThinkingBlocks(List<Map<String, Object>> thinkingBlocks) {
        this.thinkingBlocks = thinkingBlocks;
    }

    /**
     * 是否包含工具调用（对齐 Python: has_tool_calls 属性）
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}