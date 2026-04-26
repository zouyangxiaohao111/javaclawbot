package context.auto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a compaction operation aligned with Open-ClaudeCode.
 *
 * Tracks all the components that make up the post-compact message sequence:
 * - boundaryMarker: The compact boundary system message
 * - summaryMessages: User messages containing the summary
 * - attachments: File, skill, and plan attachments
 * - hookResults: Results from session start hooks
 * - messagesToKeep: Messages preserved after compaction
 */
public class CompactionResult {

    /** The compact boundary marker message */
    private final Map<String, Object> boundaryMarker;

    /** User messages containing the summary */
    private final List<Map<String, Object>> summaryMessages;

    /** Attachments (files, skills, plans) */
    private final List<Map<String, Object>> attachments;

    /** Results from session start hooks */
    private final List<Map<String, Object>> hookResults;

    /** Messages preserved after compaction (optional) */
    private final List<Map<String, Object>> messagesToKeep;

    /** User display message (optional) */
    private final String userDisplayMessage;

    /** Token count before compaction */
    private final long preCompactTokenCount;

    /** Token count after compaction (API call total) */
    private final long postCompactTokenCount;

    /** True post-compact token count (estimated) */
    private final long truePostCompactTokenCount;

    /** Compaction usage metrics */
    private final CompactionUsage compactionUsage;

    private CompactionResult(Builder builder) {
        this.boundaryMarker = builder.boundaryMarker;
        this.summaryMessages = builder.summaryMessages;
        this.attachments = builder.attachments;
        this.hookResults = builder.hookResults;
        this.messagesToKeep = builder.messagesToKeep;
        this.userDisplayMessage = builder.userDisplayMessage;
        this.preCompactTokenCount = builder.preCompactTokenCount;
        this.postCompactTokenCount = builder.postCompactTokenCount;
        this.truePostCompactTokenCount = builder.truePostCompactTokenCount;
        this.compactionUsage = builder.compactionUsage;
    }

    public Map<String, Object> getBoundaryMarker() {
        return boundaryMarker;
    }

    public List<Map<String, Object>> getSummaryMessages() {
        return summaryMessages;
    }

    public List<Map<String, Object>> getAttachments() {
        return attachments;
    }

    public List<Map<String, Object>> getHookResults() {
        return hookResults;
    }

    public List<Map<String, Object>> getMessagesToKeep() {
        return messagesToKeep;
    }

    public String getUserDisplayMessage() {
        return userDisplayMessage;
    }

    public long getPreCompactTokenCount() {
        return preCompactTokenCount;
    }

    public long getPostCompactTokenCount() {
        return postCompactTokenCount;
    }

    public long getTruePostCompactTokenCount() {
        return truePostCompactTokenCount;
    }

    public CompactionUsage getCompactionUsage() {
        return compactionUsage;
    }

    /**
     * Build the post-compact messages array in correct order.
     * Order: boundaryMarker, summaryMessages, messagesToKeep, attachments, hookResults
     */
    public List<Map<String, Object>> buildPostCompactMessages() {
        List<Map<String, Object>> result = new ArrayList<>();

        if (boundaryMarker != null) {
            result.add(boundaryMarker);
        }

        if (summaryMessages != null) {
            result.addAll(summaryMessages);
        }

        if (messagesToKeep != null) {
            result.addAll(messagesToKeep);
        }

        if (attachments != null) {
            result.addAll(attachments);
        }

        if (hookResults != null) {
            result.addAll(hookResults);
        }

        return result;
    }

    /**
     * Compaction usage metrics
     */
    public static class CompactionUsage {
        private final int inputTokens;
        private final int outputTokens;
        private final int cacheReadInputTokens;
        private final int cacheCreationInputTokens;

        public CompactionUsage(int inputTokens, int outputTokens,
                              int cacheReadInputTokens, int cacheCreationInputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadInputTokens = cacheReadInputTokens;
            this.cacheCreationInputTokens = cacheCreationInputTokens;
        }

        public int getInputTokens() {
            return inputTokens;
        }

        public int getOutputTokens() {
            return outputTokens;
        }

        public int getCacheReadInputTokens() {
            return cacheReadInputTokens;
        }

        public int getCacheCreationInputTokens() {
            return cacheCreationInputTokens;
        }

        public int getTotalTokens() {
            return inputTokens + cacheReadInputTokens + cacheCreationInputTokens + outputTokens;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Map<String, Object> boundaryMarker;
        private List<Map<String, Object>> summaryMessages = new ArrayList<>();
        private List<Map<String, Object>> attachments = new ArrayList<>();
        private List<Map<String, Object>> hookResults = new ArrayList<>();
        private List<Map<String, Object>> messagesToKeep;
        private String userDisplayMessage;
        private long preCompactTokenCount;
        private long postCompactTokenCount;
        private long truePostCompactTokenCount;
        private CompactionUsage compactionUsage;

        public Builder boundaryMarker(Map<String, Object> boundaryMarker) {
            this.boundaryMarker = boundaryMarker;
            return this;
        }

        public Builder summaryMessages(List<Map<String, Object>> summaryMessages) {
            this.summaryMessages = summaryMessages != null ? summaryMessages : new ArrayList<>();
            return this;
        }

        public Builder attachments(List<Map<String, Object>> attachments) {
            this.attachments = attachments != null ? attachments : new ArrayList<>();
            return this;
        }

        public Builder hookResults(List<Map<String, Object>> hookResults) {
            this.hookResults = hookResults != null ? hookResults : new ArrayList<>();
            return this;
        }

        public Builder messagesToKeep(List<Map<String, Object>> messagesToKeep) {
            this.messagesToKeep = messagesToKeep;
            return this;
        }

        public Builder userDisplayMessage(String userDisplayMessage) {
            this.userDisplayMessage = userDisplayMessage;
            return this;
        }

        public Builder preCompactTokenCount(long preCompactTokenCount) {
            this.preCompactTokenCount = preCompactTokenCount;
            return this;
        }

        public Builder postCompactTokenCount(long postCompactTokenCount) {
            this.postCompactTokenCount = postCompactTokenCount;
            return this;
        }

        public Builder truePostCompactTokenCount(long truePostCompactTokenCount) {
            this.truePostCompactTokenCount = truePostCompactTokenCount;
            return this;
        }

        public Builder compactionUsage(CompactionUsage compactionUsage) {
            this.compactionUsage = compactionUsage;
            return this;
        }

        public CompactionResult build() {
            return new CompactionResult(this);
        }
    }
}
