package context.auto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Groups messages at API-round boundaries aligned with Open-ClaudeCode.
 *
 * A boundary fires when a NEW assistant response begins (different
 * message.id from the prior assistant). For well-formed conversations
 * this is an API-safe split point — the API contract requires every
 * tool_use to be resolved before the next assistant turn, so pairing
 * validity falls out of the assistant-id boundary.
 *
 * This enables:
 * 1. Reactive compact to operate on single-prompt agentic sessions
 * 2. Truncation from the head when prompt-too-long during compaction
 */
public class MessageGrouping {

    private MessageGrouping() {}

    /**
     * Group messages by API round boundaries.
     *
     * @param messages The message list to group
     * @return List of message groups, each representing one API round-trip
     */
    public static List<List<Map<String, Object>>> groupMessagesByApiRound(
            List<Map<String, Object>> messages) {

        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();

        // message.id of the most recently seen assistant
        String lastAssistantId = null;

        for (Map<String, Object> msg : messages) {
            String currentAssistantId = getAssistantMessageId(msg);

            // Boundary fires when assistant message.id changes and we have content
            if ("assistant".equals(msg.get("role"))
                    && currentAssistantId != null
                    && !currentAssistantId.equals(lastAssistantId)
                    && !current.isEmpty()) {
                groups.add(new ArrayList<>(current));
                current = new ArrayList<>();
            }

            current.add(msg);

            if ("assistant".equals(msg.get("role"))) {
                lastAssistantId = currentAssistantId;
            }
        }

        if (!current.isEmpty()) {
            groups.add(current);
        }

        return groups;
    }

    /**
     * Get the message.id from a message.
     * Returns null if not present.
     */
    private static String getAssistantMessageId(Map<String, Object> msg) {
        if (msg == null) return null;

        // Check nested message.id structure
        Object messageObj = msg.get("message");
        if (messageObj instanceof Map<?, ?> message) {
            Object id = message.get("id");
            if (id instanceof String s) {
                return s;
            }
        }

        // Direct id field
        Object id = msg.get("id");
        if (id instanceof String s) {
            return s;
        }

        return null;
    }

    /**
     * Get the last assistant message from a list.
     */
    public static Map<String, Object> getLastAssistantMessage(List<Map<String, Object>> messages) {
        if (messages == null) return null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Get messages after the last compact boundary.
     *
     * @param messages The message list
     * @return Messages after the last compact boundary, or all messages if no boundary
     */
    public static List<Map<String, Object>> getMessagesAfterCompactBoundary(
            List<Map<String, Object>> messages) {

        int boundaryIndex = CompactBoundary.findLastCompactBoundaryIndex(messages);

        if (boundaryIndex < 0) {
            return new ArrayList<>(messages);
        }

        return new ArrayList<>(messages.subList(boundaryIndex + 1, messages.size()));
    }

    /**
     * Drop the oldest API-round groups until tokenGap is covered.
     * Falls back to dropping 20% of groups when gap is unparseable.
     * Returns null when nothing can be dropped without leaving an empty summarize set.
     *
     * This is used when compact request itself hits prompt-too-long (CC-1180).
     */
    public static List<Map<String, Object>> truncateHeadForPTLRetry(
            List<Map<String, Object>> messages,
            Map<String, Object> ptlResponse,
            int tokenGap) {

        // Strip PTL retry marker if present
        List<Map<String, Object>> input = messages;
        if (!messages.isEmpty()) {
            Map<String, Object> first = messages.get(0);
            if ("user".equals(first.get("role"))
                    && Boolean.TRUE.equals(first.get("isMeta"))
                    && "[earlier conversation truncated for compaction retry]".equals(first.get("content"))) {
                input = new ArrayList<>(messages.subList(1, messages.size()));
            }
        }

        List<List<Map<String, Object>>> groups = groupMessagesByApiRound(input);

        if (groups.size() < 2) {
            return null;
        }

        int dropCount;
        if (tokenGap > 0) {
            // Calculate tokens needed to drop
            int acc = 0;
            dropCount = 0;
            for (List<Map<String, Object>> g : groups) {
                acc += estimateGroupTokens(g);
                dropCount++;
                if (acc >= tokenGap) break;
            }
        } else {
            // Fallback: drop 20%
            dropCount = Math.max(1, (int) Math.floor(groups.size() * 0.2));
        }

        // Keep at least one group
        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) return null;

        List<Map<String, Object>> sliced = new ArrayList<>();
        for (int i = dropCount; i < groups.size(); i++) {
            sliced.addAll(groups.get(i));
        }

        // Prepend synthetic user marker if needed (group[0] was assistant)
        if (!sliced.isEmpty() && "assistant".equals(sliced.get(0).get("role"))) {
            List<Map<String, Object>> result = new ArrayList<>();
            result.add(createMetaUserMessage("[earlier conversation truncated for compaction retry]"));
            result.addAll(sliced);
            return result;
        }

        return sliced;
    }

    private static final String PTL_RETRY_MARKER = "[earlier conversation truncated for compaction retry]";

    private static Map<String, Object> createMetaUserMessage(String content) {
        return Map.of(
                "role", "user",
                "content", content,
                "isMeta", true
        );
    }

    /**
     * Rough token estimation for a group of messages.
     */
    private static int estimateGroupTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    /**
     * Rough token estimation for a message (approximately 4 chars per token).
     */
    private static int estimateMessageTokens(Map<String, Object> msg) {
        if (msg == null) return 0;

        Object role = msg.get("role");

        if ("user".equals(role)) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                return s.length() / 4;
            }
            if (content instanceof List<?> list) {
                return estimateContentTokens(list) / 4;
            }
        }

        if ("assistant".equals(role)) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                return s.length() / 4;
            }
            if (content instanceof List<?> list) {
                return estimateContentTokens(list) / 4;
            }
        }

        if ("tool".equals(role)) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                return s.length() / 4;
            }
        }

        return 256 / 4; // Default rough estimate
    }

    private static int estimateContentTokens(List<?> content) {
        int total = 0;
        for (Object block : content) {
            if (block instanceof Map<?, ?> map) {
                Object type = map.get("type");
                if ("text".equals(type) && map.get("text") instanceof String t) {
                    total += t.length();
                } else if ("tool_use".equals(type)) {
                    Object input = map.get("input");
                    if (input != null) {
                        total += input.toString().length();
                    }
                    Object name = map.get("name");
                    if (name instanceof String n) {
                        total += n.length();
                    }
                } else if ("tool_result".equals(type) && map.get("content") instanceof String t) {
                    total += t.length();
                }
            }
        }
        return total;
    }
}
