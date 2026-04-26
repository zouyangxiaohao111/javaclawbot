package context.auto;

import providers.CancelChecker;
import providers.LLMProvider;
import providers.LLMResponse;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Core Compact Service aligned with Open-ClaudeCode.
 *
 * Handles full conversation compaction via API summarization.
 *
 * Key features:
 * - Strip images before sending to summarization
 * - Support both fork-path (prompt cache sharing) and streaming fallback
 * - Create compact boundary with preserved segment
 * - Post-compact file attachment restoration
 */
public class CompactService {

    /** Compact API call timeout */
    private static final int COMPACT_API_TIMEOUT_SECONDS = 120;

    /** Maximum PTL retry attempts */
    private static final int MAX_PTL_RETRY_ATTEMPTS = 2;

    private CompactService() {}

    /**
     * Maximum files to restore after compaction.
     */
    public static final int POST_COMPACT_MAX_FILES_TO_RESTORE = 5;

    /**
     * Token budget for post-compact file attachments.
     */
    public static final int POST_COMPACT_TOKEN_BUDGET = 50_000;

    /**
     * Maximum tokens per file for post-compact restoration.
     */
    public static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 5_000;

    /**
     * Error messages
     */
    public static final String ERROR_MESSAGE_NOT_ENOUGH_MESSAGES =
            "Not enough messages to compact.";
    public static final String ERROR_MESSAGE_PROMPT_TOO_LONG =
            "Conversation too long. Press esc twice to go up a few messages and try again.";
    public static final String ERROR_MESSAGE_USER_ABORT =
            "API Error: Request was aborted.";
    public static final String ERROR_MESSAGE_INCOMPLETE_RESPONSE =
            "Compaction interrupted. This may be due to network issues — please try again.";

    /**
     * Pre-compact token count estimation.
     */
    public static long estimatePreCompactTokenCount(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;

        long total = 0;
        for (Map<String, Object> msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    /**
     * Estimate tokens for a message.
     */
    private static long estimateMessageTokens(Map<String, Object> message) {
        if (message == null) return 0;

        Object role = message.get("role");

        if ("user".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return roughTokenCountEstimation(s);
            }
            if (content instanceof List<?> list) {
                long sum = 0;
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        String type = map.get("type") instanceof String t ? t : null;
                        if ("text".equals(type) && map.get("text") instanceof String t) {
                            sum += roughTokenCountEstimation(t);
                        } else if ("image".equals(type) || "document".equals(type)) {
                            sum += 2000; // Image estimate
                        } else if ("tool_result".equals(type)) {
                            Object tc = map.get("content");
                            if (tc instanceof String s2) {
                                sum += roughTokenCountEstimation(s2);
                            }
                        }
                    }
                }
                return sum;
            }
        }

        if ("assistant".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return roughTokenCountEstimation(s);
            }
            if (content instanceof List<?> list) {
                long sum = 0;
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        String type = map.get("type") instanceof String t ? t : null;
                        if ("text".equals(type) && map.get("text") instanceof String t) {
                            sum += roughTokenCountEstimation(t);
                        } else if ("tool_use".equals(type)) {
                            Object input = map.get("input");
                            if (input != null) {
                                sum += roughTokenCountEstimation(input.toString());
                            }
                        } else if ("thinking".equals(type) && map.get("thinking") instanceof String t) {
                            sum += roughTokenCountEstimation(t);
                        }
                    }
                }
                return sum;
            }
        }

        if ("tool".equals(role)) {
            Object content = message.get("content");
            if (content instanceof String s) {
                return roughTokenCountEstimation(s);
            }
        }

        return 64; // Default estimate
    }

    private static long roughTokenCountEstimation(String text) {
        if (text == null) return 0;
        return (text.length() / 4) + 1;
    }

    /**
     * Extract discovered tool names from messages.
     */
    public static List<String> extractDiscoveredToolNames(List<Map<String, Object>> messages) {
        Set<String> tools = new java.util.HashSet<>();

        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) continue;

            Object content = msg.get("content");
            if (!(content instanceof List<?> list)) continue;

            for (Object block : list) {
                if (block instanceof Map<?, ?> map && "tool_use".equals(map.get("type"))) {
                    if (map.get("name") instanceof String name) {
                        tools.add(name);
                    }
                }
            }
        }

        List<String> sorted = new ArrayList<>(tools);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /**
     * Strip images from messages before sending to compaction API.
     */
    public static List<Map<String, Object>> stripImagesFromMessages(
            List<Map<String, Object>> messages) {

        return MicroCompactService.stripImagesFromMessages(messages);
    }

    /**
     * Strip reinjected attachments from messages.
     * skill_discovery and skill_listing are re-injected post-compaction.
     */
    public static List<Map<String, Object>> stripReinjectedAttachments(
            List<Map<String, Object>> messages) {

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            // Skip skill_discovery and skill_listing attachments
            if ("attachment".equals(msg.get("role"))) {
                Object attachment = msg.get("attachment");
                if (attachment instanceof Map<?, ?> att) {
                    String type = att.get("type") instanceof String t ? t : null;
                    if ("skill_discovery".equals(type) || "skill_listing".equals(type)) {
                        continue;
                    }
                }
            }
            result.add(msg);
        }

        return result;
    }

    /**
     * Build the compact prompt user message.
     */
    public static Map<String, Object> buildCompactSummaryRequest(String customInstructions) {
        String prompt = CompactPrompt.getCompactPrompt(customInstructions);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", prompt);
        msg.put("timestamp", LocalDateTime.now().toString());

        return msg;
    }

    /**
     * Format the compact summary response.
     */
    public static String formatCompactSummary(String rawSummary) {
        return CompactPrompt.formatCompactSummary(rawSummary);
    }

    /**
     * Build the summary user message.
     */
    public static Map<String, Object> buildSummaryUserMessage(
            String summary,
            boolean suppressFollowUpQuestions,
            String transcriptPath) {

        String content = CompactPrompt.getCompactUserSummaryMessage(
                summary,
                suppressFollowUpQuestions,
                transcriptPath,
                true  // recent messages preserved
        );

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        msg.put("isCompactSummary", true);
        msg.put("isVisibleInTranscriptOnly", true);
        msg.put("timestamp", LocalDateTime.now().toString());

        return msg;
    }

    /**
     * Find the last assistant message with text content.
     */
    public static String getLastAssistantText(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;

            Object content = msg.get("content");
            if (content instanceof String s && !s.isEmpty()) {
                return s;
            }

            if (content instanceof List<?> list) {
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                        if (map.get("text") instanceof String t && !t.isEmpty()) {
                            return t;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if summary starts with PTL error.
     */
    public static boolean startsWithPromptTooLongError(String summary) {
        if (summary == null) return false;
        return summary.startsWith("Prompt too long") ||
               summary.startsWith("Conversation too long") ||
               summary.contains("prompt_too_long");
    }

    /**
     * Create post-compact file attachments for recently read files.
     *
     * Uses ReadFileState snapshot to re-read the most recently accessed files,
     * preserving file context across compaction within token budget.
     *
     * Aligned with Open-ClaudeCode: createPostCompactFileAttachments() in compact.ts:1415-1464
     *
     * @param readFileSnapshot Pre-compact snapshot of read file state
     * @param planFilePath Plan file path to exclude from restoration (can be null)
     * @param preservedMessages Messages kept after compaction (skip already-visible files)
     * @return List of file attachment messages
     */
    public static List<Map<String, Object>> createPostCompactFileAttachments(
            Map<String, Long> readFileSnapshot,
            String planFilePath,
            List<Map<String, Object>> preservedMessages) {

        if (readFileSnapshot == null || readFileSnapshot.isEmpty()) {
            return new ArrayList<>();
        }

        // Collect file paths already visible in preserved messages
        java.util.Set<String> preservedReadPaths = preservedMessages != null
                ? ReadFileState.collectReadToolFilePaths(preservedMessages)
                : java.util.Set.of();

        return ReadFileState.createPostCompactFileAttachments(
                readFileSnapshot, planFilePath, preservedReadPaths);
    }

    /**
     * Create async agent attachments for tasks still running.
     * Lists background agent tasks so the model doesn't re-create them.
     *
     * @param tasks Map of task ID to task state
     * @param currentAgentId Current agent ID to exclude self
     * @return List of task status attachment messages
     */
    public static List<Map<String, Object>> createAsyncAgentAttachmentsIfNeeded(
            Map<String, ?> tasks,
            String currentAgentId) {

        List<Map<String, Object>> attachments = new ArrayList<>();
        if (tasks == null || tasks.isEmpty()) return attachments;

        for (Map.Entry<String, ?> entry : tasks.entrySet()) {
            Object taskObj = entry.getValue();
            if (!(taskObj instanceof Map<?, ?> task)) continue;

            // Check type is local_agent
            String type = task.get("type") instanceof String t ? t : null;
            if (!"local_agent".equals(type)) continue;

            // Skip already retrieved
            Object retrieved = task.get("retrieved");
            if (retrieved instanceof Boolean b && b) continue;

            // Check status is running or pending
            String status = task.get("status") instanceof String s ? s : null;
            if (!"running".equals(status) && !"pending".equals(status)) continue;

            // Exclude self
            String agentId = task.get("agentId") instanceof String a ? a : null;
            if (currentAgentId != null && currentAgentId.equals(agentId)) continue;

            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("role", "attachment");
            attachment.put("type", "task_status");
            attachment.put("attachment", Map.of(
                    "type", "task_status",
                    "taskId", entry.getKey(),
                    "taskType", "local_agent",
                    "status", status,
                    "agentId", agentId != null ? agentId : "",
                    "agentType", task.get("agentType") instanceof String at ? at : ""
            ));
            attachment.put("timestamp", java.time.Instant.now().toString());
            attachments.add(attachment);
        }

        return attachments;
    }

    /**
     * Create plan file attachment if a plan file exists.
     * Preserves plan context across compaction.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier (can be null)
     * @return Plan attachment message, or null if no plan exists
     */
    public static Map<String, Object> createPlanAttachmentIfNeeded(String sessionId, String agentId) {
        String plan = PlanFileManager.getPlan(sessionId, agentId);
        if (plan == null || plan.isBlank()) return null;

        Path planPath = agentId != null
                ? PlanFileManager.getAgentPlanFilePath(sessionId, agentId)
                : PlanFileManager.getPlanFilePath(sessionId);
        if (planPath == null) return null;

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("role", "attachment");
        attachment.put("type", "plan_file_reference");
        attachment.put("attachment", Map.of(
                "type", "plan_file_reference",
                "planFilePath", planPath.toString(),
                "planContent", plan
        ));
        attachment.put("timestamp", java.time.Instant.now().toString());
        return attachment;
    }

    /**
     * Create plan mode attachment if currently in plan mode.
     * Reminds the model to continue in plan mode after compaction.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier (can be null)
     * @param isPlanMode Whether the current mode is plan
     * @return Plan mode attachment, or null if not in plan mode
     */
    public static Map<String, Object> createPlanModeAttachmentIfNeeded(
            String sessionId, String agentId, boolean isPlanMode) {

        if (!isPlanMode) return null;

        String planFilePath = PlanFileManager.getPlanDisplayPath(sessionId, agentId);
        boolean planExists = PlanFileManager.hasPlan(sessionId, agentId);

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("role", "attachment");
        attachment.put("type", "plan_mode");
        attachment.put("attachment", Map.of(
                "type", "plan_mode",
                "reminderType", "full",
                "isSubAgent", agentId != null,
                "planFilePath", planFilePath != null ? planFilePath : "",
                "planExists", planExists
        ));
        attachment.put("timestamp", java.time.Instant.now().toString());
        return attachment;
    }

    /**
     * Create skill attachment for invoked skills.
     * Preserves skill context across compaction so the model remembers
     * which skills were loaded and their instructions.
     *
     * @param invokedSkills Map of skill name to skill content
     * @return Skill attachment message, or null if no skills invoked
     */
    public static Map<String, Object> createSkillAttachmentIfNeeded(
            Map<String, String> invokedSkills) {

        if (invokedSkills == null || invokedSkills.isEmpty()) return null;

        StringBuilder content = new StringBuilder();
        int totalChars = 0;
        int maxChars = 25_000 * 4; // ~25K token budget for all skills

        for (Map.Entry<String, String> skill : invokedSkills.entrySet()) {
            String skillContent = skill.getValue();
            if (skillContent == null || skillContent.isBlank()) continue;

            // Truncate individual skill content
            int remaining = maxChars - totalChars;
            if (remaining <= 0) break;

            if (skillContent.length() > remaining) {
                skillContent = skillContent.substring(0, remaining) + "\n\n[... skill content truncated ...]";
            }

            content.append("## ").append(skill.getKey()).append("\n");
            content.append(skillContent).append("\n\n");
            totalChars += skillContent.length();
        }

        if (content.isEmpty()) return null;

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("role", "attachment");
        attachment.put("type", "skill_listing");
        attachment.put("attachment", Map.of(
                "type", "skill_listing",
                "skills", content.toString()
        ));
        attachment.put("timestamp", java.time.Instant.now().toString());
        return attachment;
    }

    /**
     * Estimate true post-compact token count.
     */
    public static long estimateTruePostCompactTokenCount(
            Map<String, Object> boundaryMarker,
            List<Map<String, Object>> summaryMessages,
            List<Map<String, Object>> attachments,
            List<Map<String, Object>> hookMessages) {

        long total = 0;

        if (boundaryMarker != null) {
            total += estimateMessageTokens(boundaryMarker);
        }

        if (summaryMessages != null) {
            for (Map<String, Object> msg : summaryMessages) {
                total += estimateMessageTokens(msg);
            }
        }

        if (attachments != null) {
            for (Map<String, Object> att : attachments) {
                total += estimateMessageTokens(att);
            }
        }

        if (hookMessages != null) {
            for (Map<String, Object> msg : hookMessages) {
                total += estimateMessageTokens(msg);
            }
        }

        return total;
    }

    /**
     * Get messages after the last compact boundary.
     */
    public static List<Map<String, Object>> getMessagesAfterCompactBoundary(
            List<Map<String, Object>> messages) {

        return MessageGrouping.getMessagesAfterCompactBoundary(messages);
    }

    /**
     * Build post-compact messages array in correct order.
     * Order: boundaryMarker, summaryMessages, messagesToKeep, attachments, hookResults
     */
    public static List<Map<String, Object>> buildPostCompactMessages(
            CompactionResult result) {

        return result.buildPostCompactMessages();
    }

    /**
     * Retry truncation for PTL during compact.
     */
    public static List<Map<String, Object>> truncateHeadForPTLRetry(
            List<Map<String, Object>> messages,
            Map<String, Object> summaryResponse,
            int tokenGap) {

        return MessageGrouping.truncateHeadForPTLRetry(messages, summaryResponse, tokenGap);
    }

    // ==================== streamCompactSummary (Direct LLM API call) ====================

    /**
     * Result of streamCompactSummary operation.
     */
    public static class CompactSummaryResult {
        private final String summary;
        private final String error;
        private final boolean success;
        private final int ptlRetryCount;

        private CompactSummaryResult(String summary, String error, boolean success, int ptlRetryCount) {
            this.summary = summary;
            this.error = error;
            this.success = success;
            this.ptlRetryCount = ptlRetryCount;
        }

        public static CompactSummaryResult success(String summary, int ptlRetryCount) {
            return new CompactSummaryResult(summary, null, true, ptlRetryCount);
        }

        public static CompactSummaryResult error(String error) {
            return new CompactSummaryResult(null, error, false, 0);
        }

        public String getSummary() { return summary; }
        public String getError() { return error; }
        public boolean isSuccess() { return success; }
        public int getPtlRetryCount() { return ptlRetryCount; }
    }

    /**
     * Stream compact summary - direct LLM API call for compaction.
     *
     * This is the aligned implementation of Open-ClaudeCode's streamCompactSummary.
     * It makes a direct LLM API call without tools to generate the summary.
     *
     * Key features:
     * - Fork path: uses prompt cache sharing via runForkedAgent (if available)
     * - Streaming fallback: uses queryModelWithStreaming
     * - PTL retry: truncates head and retries on prompt-too-long errors
     *
     * @param provider LLM provider for direct API calls
     * @param messages Messages to summarize (should be stripped of images already)
     * @param customInstructions Optional custom instructions
     * @param onProgress Optional progress callback (called with delta text)
     * @param cancelChecker Optional cancel checker
     * @return CompactSummaryResult with summary or error
     */
    public static CompactSummaryResult streamCompactSummary(
            LLMProvider provider,
            List<Map<String, Object>> messages,
            String customInstructions,
            Consumer<String> onProgress,
            CancelChecker cancelChecker) {

        if (provider == null) {
            return CompactSummaryResult.error("LLM provider not available");
        }

        String model = provider.getDefaultModel();

        // Build the compact prompt
        String compactPrompt = CompactPrompt.getCompactPrompt(customInstructions);

        // Build the request messages
        List<Map<String, Object>> requestMessages = new ArrayList<>(messages);
        Map<String, Object> promptMsg = new LinkedHashMap<>();
        promptMsg.put("role", "user");
        promptMsg.put("content", compactPrompt);
        promptMsg.put("timestamp", LocalDateTime.now().toString());
        requestMessages.add(promptMsg);

        // Sanitize content
        requestMessages = LLMProvider.sanitizeEmptyContent(requestMessages);

        // PTL retry state
        int ptlRetryCount = 0;
        List<Map<String, Object>> currentMessages = requestMessages;

        while (ptlRetryCount <= MAX_PTL_RETRY_ATTEMPTS) {
            if (cancelChecker != null && cancelChecker.isCancelled()) {
                return CompactSummaryResult.error(ERROR_MESSAGE_USER_ABORT);
            }

            try {
                // Make direct LLM API call without tools
                LLMResponse response = provider.chatWithRetry(
                        currentMessages,
                        null,  // no tools
                        model,
                        8192,  // max output tokens for summary
                        0.3,   // lower temperature for deterministic summary
                        null,  // no reasoning effort
                        null,  // no think config
                        null,  // no extra body
                        cancelChecker
                ).get(COMPACT_API_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (response == null) {
                    return CompactSummaryResult.error("No response from LLM");
                }

                if ("error".equals(response.getFinishReason())) {
                    String content = response.getContent();
                    if (isPromptTooLongError(content)) {
                        ptlRetryCount++;
                        if (ptlRetryCount > MAX_PTL_RETRY_ATTEMPTS) {
                            return CompactSummaryResult.error(ERROR_MESSAGE_PROMPT_TOO_LONG);
                        }

                        // Truncate head and retry
                        int tokenGap = estimateTokenGap(content, currentMessages);
                        List<Map<String, Object>> truncated = MessageGrouping.truncateHeadForPTLRetry(
                                currentMessages.subList(0, currentMessages.size() - 1),  // exclude prompt message
                                null,
                                tokenGap
                        );

                        if (truncated == null || truncated.isEmpty()) {
                            return CompactSummaryResult.error(ERROR_MESSAGE_PROMPT_TOO_LONG);
                        }

                        // Rebuild messages with prompt
                        currentMessages = new ArrayList<>(truncated);
                        currentMessages.add(promptMsg);
                        continue;
                    }
                    return CompactSummaryResult.error(content != null ? content : "LLM error");
                }

                String summary = response.getContent();
                if (summary == null || summary.isBlank()) {
                    return CompactSummaryResult.error("Empty summary received");
                }

                // Report progress if callback provided
                if (onProgress != null) {
                    onProgress.accept(summary);
                }

                return CompactSummaryResult.success(summary, ptlRetryCount);

            } catch (TimeoutException e) {
                return CompactSummaryResult.error("Compaction timed out");
            } catch (CancellationException e) {
                return CompactSummaryResult.error(ERROR_MESSAGE_USER_ABORT);
            } catch (CompletionException e) {
                Throwable root = e.getCause() != null ? e.getCause() : e;
                if (root instanceof CancellationException) {
                    return CompactSummaryResult.error(ERROR_MESSAGE_USER_ABORT);
                }
                return CompactSummaryResult.error("Compaction failed: " + root.getMessage());
            } catch (Exception e) {
                return CompactSummaryResult.error("Compaction failed: " + e.getMessage());
            }
        }

        return CompactSummaryResult.error(ERROR_MESSAGE_PROMPT_TOO_LONG);
    }

    /**
     * Check if error is prompt-too-long.
     */
    private static boolean isPromptTooLongError(String error) {
        if (error == null) return false;
        String lower = error.toLowerCase();
        return lower.contains("prompt too long") ||
               lower.contains("prompt_too_long") ||
               lower.contains("conversation too long") ||
               lower.contains("too many tokens") ||
               lower.contains("maximum context length");
    }

    /**
     * Estimate token gap from PTL error.
     */
    private static int estimateTokenGap(String error, List<Map<String, Object>> messages) {
        // Try to parse token count from error message
        // Common patterns: "N tokens", "approximately N", etc.
        if (error == null) return 5000;  // default fallback

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)\\s*tokens?");
        java.util.regex.Matcher matcher = pattern.matcher(error);
        if (matcher.find()) {
            try {
                int reportedTokens = Integer.parseInt(matcher.group(1));
                // Add buffer
                return (int) (reportedTokens * 0.15) + 1000;
            } catch (NumberFormatException ignored) {}
        }

        // Default: truncate ~15% of messages
        int totalTokens = (int) estimatePreCompactTokenCount(messages);
        return Math.max(5000, (int) (totalTokens * 0.15));
    }

}
