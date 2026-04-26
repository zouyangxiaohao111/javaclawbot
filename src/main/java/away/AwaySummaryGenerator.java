package away;

import providers.CancelChecker;
import providers.LLMProvider;
import providers.LLMResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Away Summary Generator
 * 对齐 Open-ClaudeCode: src/services/awaySummary.ts
 *
 * 生成"离开时发生了什么"的摘要
 */
public class AwaySummaryGenerator {

    private final LLMProvider provider;

    public AwaySummaryGenerator(LLMProvider provider) {
        this.provider = provider;
    }

    /**
     * 生成 Away Summary
     * 对齐: generateAwaySummary() in awaySummary.ts
     *
     * @param messages 当前消息列表
     * @param sessionMemoryContent Session Memory 内容（可选）
     * @param cancelChecker 取消检查器
     * @return 生成的摘要，或 null
     */
    public String generate(
            List<Map<String, Object>> messages,
            String sessionMemoryContent,
            CancelChecker cancelChecker) {

        if (messages == null || messages.isEmpty()) {
            return null;
        }

        // 构建最近消息（取最后 N 条）
        List<Map<String, Object>> recentMessages = extractRecentMessages(messages);

        // 构建 prompt
        String prompt = buildAwaySummaryPrompt(recentMessages, sessionMemoryContent);

        try {
            // 构建消息列表
            List<Map<String, Object>> requestMessages = new ArrayList<>();
            requestMessages.add(Map.of("role", "system", "content", "你是一个助手。"));
            requestMessages.add(Map.of("role", "user", "content", prompt));

            // 调用 LLM
            LLMResponse response = provider.chatWithRetry(
                    requestMessages,
                    null,  // no tools
                    provider.getDefaultModel(),
                    4096,  // max output
                    0.3,   // lower temperature
                    null,   // no reasoning effort
                    null,   // no think config
                    null,   // no extra body
                    cancelChecker
            ).toCompletableFuture().get();

            if (response != null && !"error".equals(response.getFinishReason())) {
                return response.getContent();
            }
        } catch (Exception e) {
            // 忽略错误，返回 null
        }

        return null;
    }

    /**
     * 提取最近消息
     */
    private List<Map<String, Object>> extractRecentMessages(List<Map<String, Object>> messages) {
        // 取最后 20 条消息
        int start = Math.max(0, messages.size() - 20);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    /**
     * 构建 Away Summary prompt
     */
    private String buildAwaySummaryPrompt(List<Map<String, Object>> recentMessages, String sessionMemoryContent) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an assistant summarizing what happened during a user's absence.\n\n");

        if (sessionMemoryContent != null && !sessionMemoryContent.isBlank()) {
            prompt.append("## Session Background (from session memory):\n");
            prompt.append(sessionMemoryContent);
            prompt.append("\n\n");
        }

        prompt.append("## Recent Conversation (what happened while away):\n");
        for (Map<String, Object> msg : recentMessages) {
            String role = msg.get("role") instanceof String r ? r : "unknown";
            Object content = msg.get("content");

            if ("user".equals(role)) {
                if (content instanceof String s && !s.isBlank()) {
                    prompt.append("User: ").append(s).append("\n");
                }
            } else if ("assistant".equals(role)) {
                if (content instanceof String s && !s.isBlank()) {
                    // 截断过长的回复
                    String truncated = s.length() > 500 ? s.substring(0, 500) + "..." : s;
                    prompt.append("Assistant: ").append(truncated).append("\n");
                }
                // 工具调用
                Object toolCalls = msg.get("tool_calls");
                if (toolCalls instanceof List<?> calls && !calls.isEmpty()) {
                    prompt.append("Assistant used ").append(calls.size()).append(" tool(s)\n");
                }
            } else if ("tool".equals(role)) {
                prompt.append("[Tool result]\n");
            }
        }

        prompt.append("\n## Summary Task:\n");
        prompt.append("Generate a concise summary of what was accomplished or happened during the user's absence. ");
        prompt.append("Focus on:\n");
        prompt.append("- What tasks were completed\n");
        prompt.append("- What important decisions were made\n");
        prompt.append("- What errors or issues were encountered and resolved\n");
        prompt.append("- Any pending tasks or next steps\n");
        prompt.append("\nRespond in the user's language if identifiable.");

        return prompt.toString();
    }

}
