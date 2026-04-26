package context.auto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact boundary marker aligned with Open-ClaudeCode.
 *
 * A compact boundary message marks the transition point between pre-compact
 * and post-compact context. It carries metadata about what was compacted
 * and preserves segment information for the session loader.
 */
public class CompactBoundary {

    private CompactBoundary() {}

    /**
     * Create a compact boundary message.
     *
     * @param trigger "auto" or "manual"
     * @param preCompactTokenCount Token count before compaction
     * @param lastPreCompactUuid UUID of the last message before compaction
     * @return A system message representing the boundary
     */
    public static Map<String, Object> createCompactBoundaryMessage(
            String trigger,
            long preCompactTokenCount,
            String lastPreCompactUuid) {
        return createCompactBoundaryMessage(trigger, preCompactTokenCount, lastPreCompactUuid, null, 0);
    }

    /**
     * Create a compact boundary message with optional user feedback.
     */
    public static Map<String, Object> createCompactBoundaryMessage(
            String trigger,
            long preCompactTokenCount,
            String lastPreCompactUuid,
            String userFeedback,
            int messagesSummarized) {

        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("role", "system");
        boundary.put("type", "compact_boundary");
        boundary.put("timestamp", LocalDateTime.now().toString());

        Map<String, Object> compactMetadata = new LinkedHashMap<>();
        compactMetadata.put("trigger", trigger);
        compactMetadata.put("preCompactTokenCount", preCompactTokenCount);
        if (lastPreCompactUuid != null) {
            compactMetadata.put("lastPreCompactUuid", lastPreCompactUuid);
        }
        if (userFeedback != null) {
            compactMetadata.put("userFeedback", userFeedback);
        }
        if (messagesSummarized > 0) {
            compactMetadata.put("messagesSummarized", messagesSummarized);
        }
        // preservedSegment will be added later via annotateBoundaryWithPreservedSegment

        boundary.put("compactMetadata", compactMetadata);

        return boundary;
    }

    /**
     * Annotate a boundary with preserved segment metadata.
     * This is used to track which messages are preserved after compaction.
     *
     * @param boundary The boundary message to annotate
     * @param anchorUuid UUID of the message immediately before the preserved segment
     * @param headUuid UUID of the first preserved message
     * @param tailUuid UUID of the last preserved message
     * @return Annotated boundary
     */
    public static Map<String, Object> annotateBoundaryWithPreservedSegment(
            Map<String, Object> boundary,
            String anchorUuid,
            String headUuid,
            String tailUuid) {

        Map<String, Object> compactMetadata = getOrCreateCompactMetadata(boundary);

        Map<String, Object> preservedSegment = new LinkedHashMap<>();
        preservedSegment.put("anchorUuid", anchorUuid);
        preservedSegment.put("headUuid", headUuid);
        preservedSegment.put("tailUuid", tailUuid);

        compactMetadata.put("preservedSegment", preservedSegment);

        boundary.put("compactMetadata", compactMetadata);
        return boundary;
    }

    /**
     * Add pre-compact discovered tools to the boundary.
     */
    public static Map<String, Object> addPreCompactDiscoveredTools(
            Map<String, Object> boundary,
            List<String> toolNames) {

        Map<String, Object> compactMetadata = getOrCreateCompactMetadata(boundary);
        compactMetadata.put("preCompactDiscoveredTools", new ArrayList<>(toolNames));

        boundary.put("compactMetadata", compactMetadata);
        return boundary;
    }

    /**
     * Check if a message is a compact boundary.
     */
    public static boolean isCompactBoundaryMessage(Map<String, Object> message) {
        if (message == null) return false;

        Object role = message.get("role");
        Object type = message.get("type");

        if (!"system".equals(role)) return false;

        // Check type field or compactMetadata
        if ("compact_boundary".equals(type)) return true;

        Map<String, Object> metadata = getCompactMetadata(message);
        return metadata != null;
    }

    /**
     * Get compact metadata from a message.
     */
    public static Map<String, Object> getCompactMetadata(Map<String, Object> message) {
        if (message == null) return null;

        Object metadata = message.get("compactMetadata");
        if (metadata instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m;
            return result;
        }
        return null;
    }

    /**
     * Find the last compact boundary message index.
     */
    public static int findLastCompactBoundaryIndex(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isCompactBoundaryMessage(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrCreateCompactMetadata(Map<String, Object> boundary) {
        Map<String, Object> metadata = getCompactMetadata(boundary);
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            boundary.put("compactMetadata", metadata);
        }
        return metadata;
    }
}
