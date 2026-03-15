package providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Consumer;

/**
 * 流式响应支持
 *
 * 对齐 OpenClaw 的流式响应功能
 *
 * 功能：
 * - SSE 流式输出
 * - 增量内容回调
 * - 流式工具调用
 */
public class StreamingResponse {

    private static final Logger log = LoggerFactory.getLogger(StreamingResponse.class);

    /**
     * 流式内容块
     */
    public static class ContentDelta {
        public final String content;
        public final String reasoningContent;
        public final boolean isComplete;

        public ContentDelta(String content, String reasoningContent, boolean isComplete) {
            this.content = content;
            this.reasoningContent = reasoningContent;
            this.isComplete = isComplete;
        }

        public ContentDelta(String content) {
            this(content, null, false);
        }
    }

    /**
     * 流式工具调用块
     */
    public static class ToolCallDelta {
        public final String id;
        public final String name;
        public final String argumentsDelta;
        public final boolean isComplete;

        public ToolCallDelta(String id, String name, String argumentsDelta, boolean isComplete) {
            this.id = id;
            this.name = name;
            this.argumentsDelta = argumentsDelta;
            this.isComplete = isComplete;
        }
    }

    /**
     * 流式响应监听器
     */
    public interface StreamListener {
        void onContentDelta(ContentDelta delta);
        void onToolCallDelta(ToolCallDelta delta);
        void onComplete(LLMResponse response);
        void onError(Throwable error);
    }

    /**
     * 流式响应构建器
     */
    public static class Builder {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoningContent = new StringBuilder();
        private final List<ToolCallRequest> toolCalls = new ArrayList<>();
        private final Map<String, StringBuilder> toolCallArguments = new HashMap<>();
        private final Map<String, String> toolCallNames = new HashMap<>();
        private agent.Usage usage;
        private String model;
        private String finishReason;

        public Builder appendContent(String delta) {
            content.append(delta);
            return this;
        }

        public Builder appendReasoning(String delta) {
            reasoningContent.append(delta);
            return this;
        }

        public Builder startToolCall(String id, String name) {
            toolCallNames.put(id, name);
            toolCallArguments.put(id, new StringBuilder());
            return this;
        }

        public Builder appendToolCallArguments(String id, String delta) {
            StringBuilder args = toolCallArguments.get(id);
            if (args != null) {
                args.append(delta);
            }
            return this;
        }

        public Builder completeToolCall(String id) {
            String name = toolCallNames.get(id);
            StringBuilder args = toolCallArguments.get(id);
            if (name != null && args != null) {
                try {
                    Map<String, Object> parsedArgs = parseArguments(args.toString());
                    toolCalls.add(new ToolCallRequest(id, name, parsedArgs));
                } catch (Exception e) {
                    log.warn("Failed to parse tool call arguments: {}", e.getMessage());
                    toolCalls.add(new ToolCallRequest(id, name, Map.of("raw", args.toString())));
                }
            }
            return this;
        }

        public Builder usage(agent.Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public LLMResponse build() {
            Map<String, Integer> usageMap = null;
            if (usage != null) {
                usageMap = new HashMap<>();
                usageMap.put("input", (int) Math.min(usage.getInput(), Integer.MAX_VALUE));
                usageMap.put("output", (int) Math.min(usage.getOutput(), Integer.MAX_VALUE));
                usageMap.put("total", (int) Math.min(usage.getTotal(), Integer.MAX_VALUE));
            }
            return new LLMResponse(
                    content.toString(),
                    toolCalls,
                    finishReason,
                    usageMap,
                    reasoningContent.length() > 0 ? reasoningContent.toString() : null,
                    null
            );
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> parseArguments(String json) throws Exception {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        }
    }

    /**
     * 流式响应发布器
     */
    public static class StreamPublisher extends SubmissionPublisher<ContentDelta> {
        private final Builder builder = new Builder();
        private volatile boolean completed = false;

        public void emitContent(String delta) {
            builder.appendContent(delta);
            submit(new ContentDelta(delta));
        }

        public void emitReasoning(String delta) {
            builder.appendReasoning(delta);
            submit(new ContentDelta(null, delta, false));
        }

        public void complete(LLMResponse response) {
            if (!completed) {
                completed = true;
                submit(new ContentDelta(null, null, true));
                close();
            }
        }

        public void completeExceptionally(Throwable error) {
            if (!completed) {
                completed = true;
                closeExceptionally(error);
            }
        }

        public LLMResponse getCurrentResponse() {
            return builder.build();
        }
    }

    /**
     * SSE 事件格式化
     */
    public static String formatSSE(String event, String data) {
        return "event: " + event + "\n" +
               "data: " + data.replace("\n", "\ndata: ") + "\n\n";
    }

    /**
     * SSE 内容增量事件
     */
    public static String formatContentDelta(String delta) {
        return formatSSE("content_delta", "{\"content\":\"" + escapeJson(delta) + "\"}");
    }

    /**
     * SSE 完成事件
     */
    public static String formatComplete(LLMResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"content\":\"").append(escapeJson(response.getContent())).append("\"");
        Map<String, Integer> usage = response.getUsage();
        if (usage != null && !usage.isEmpty()) {
            sb.append(",\"usage\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : usage.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return formatSSE("complete", sb.toString());
    }

    /**
     * SSE 错误事件
     */
    public static String formatError(String error) {
        return formatSSE("error", "{\"error\":\"" + escapeJson(error) + "\"}");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}