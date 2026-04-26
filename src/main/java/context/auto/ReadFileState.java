package context.auto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Read file state tracking for post-compact file restoration.
 * Aligned with Open-ClaudeCode: src/utils/fileStateCache.ts
 *
 * Tracks recently read files (paths + timestamps) to enable re-attaching
 * their contents after a compaction so the model doesn't lose file context.
 *
 * Key behaviors:
 * - Track: called by FileReadTool after each successful read
 * - Snapshot + clear: called by compact flow to capture state before clearing
 * - Restore: called by CompactService to re-read tracked files within token budget
 */
public class ReadFileState {

    /** Max files to restore after compaction */
    public static final int POST_COMPACT_MAX_FILES_TO_RESTORE = 5;

    /** Token budget for post-compact file attachments */
    public static final int POST_COMPACT_TOKEN_BUDGET = 50_000;

    /** Max tokens per file for post-compact restoration */
    public static final int POST_COMPACT_MAX_TOKENS_PER_FILE = 10_000;

    /** Max tracked files */
    private static final int MAX_TRACKED_FILES = 100;

    /** File entry: path -> timestamp (epoch millis) */
    private final ConcurrentHashMap<String, Long> entries = new ConcurrentHashMap<>();

    /**
     * Track a file read.
     * Called by FileReadTool after a successful read.
     *
     * @param filePath Absolute path to the file
     */
    public void track(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        String normalized = Path.of(filePath).normalize().toString();
        entries.put(normalized, System.currentTimeMillis());

        // Simple eviction: if over max, remove oldest entries
        if (entries.size() > MAX_TRACKED_FILES) {
            evictOldest();
        }
    }

    /**
     * Track a file with explicit timestamp.
     *
     * @param filePath Absolute path to the file
     * @param timestampMs Modification time in millis
     */
    public void track(String filePath, long timestampMs) {
        if (filePath == null || filePath.isBlank()) return;
        String normalized = Path.of(filePath).normalize().toString();
        entries.put(normalized, timestampMs);
        if (entries.size() > MAX_TRACKED_FILES) {
            evictOldest();
        }
    }

    /**
     * Check if a file has been read.
     */
    public boolean has(String filePath) {
        if (filePath == null) return false;
        return entries.containsKey(Path.of(filePath).normalize().toString());
    }

    /**
     * Get the timestamp of a tracked file read.
     */
    public long getTimestamp(String filePath) {
        if (filePath == null) return 0;
        return entries.getOrDefault(Path.of(filePath).normalize().toString(), 0L);
    }

    /**
     * Remove a file from tracking.
     */
    public void remove(String filePath) {
        if (filePath == null) return;
        entries.remove(Path.of(filePath).normalize().toString());
    }

    /**
     * Get all tracked file paths.
     */
    public Set<String> getTrackedFiles() {
        return Set.copyOf(entries.keySet());
    }

    /**
     * Get recent files sorted by recency (newest first), excluding plan/memory files.
     *
     * @param planFilePath Plan file path to exclude (can be null)
     * @return Recent files sorted by timestamp descending
     */
    public List<Map.Entry<String, Long>> getRecentFiles(String planFilePath) {
        return entries.entrySet().stream()
                .filter(e -> !shouldExcludeFromPostCompactRestore(e.getKey(), planFilePath))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Exclude plan files and memory files from post-compact restoration.
     * Aligned with Open-ClaudeCode: isMemoryFilePath() in claudemd.ts:1435-1452
     * and shouldExcludeFromPostCompactRestore() in compact.ts:1674-1705.
     *
     * Excludes:
     * - Plan files (exact match)
     * - CLAUDE.md / CODE-AGENT.md / CLAUDE.local.md anywhere
     * - All files under .javaclawbot/ directories (covers config, memory, rules, sessions)
     * - Session memory files (under session-memory/ directories)
     * - MEMORY.md files (auto-memory)
     */
    private static boolean isMemoryFilePath(String filePath) {
        if (filePath == null) return false;

        String normalized = filePath.replace('\\', '/');
        String fileName = Path.of(filePath).getFileName().toString();

        // 1. Instruction / memory files by name (any directory)
        if ("CLAUDE.md".equals(fileName)
                || "CODE-AGENT.md".equals(fileName)
                || "CLAUDE.local.md".equals(fileName)) {
            return true;
        }

        // 2. Auto-memory entrypoint
        if ("MEMORY.md".equals(fileName)) {
            return true;
        }

        // 3. All files under .javaclawbot/ (config, memory, rules, sessions, etc.)
        if (normalized.contains("/.javaclawbot/")) {
            return true;
        }

        // 4. Session memory files
        if (normalized.contains("/session-memory/")) {
            return true;
        }

        return false;
    }

    /**
     * Check if a file path should be excluded from post-compact restoration.
     *
     * @param filePath Absolute or relative file path
     * @param planFilePath Plan file path to exclude (can be null, checked via isMemoryFilePath)
     * @return true if the file should be excluded
     */
    private static boolean shouldExcludeFromPostCompactRestore(String filePath, String planFilePath) {
        // Exclude plan file by exact match
        if (planFilePath != null) {
            String normalizedPlan = Path.of(planFilePath).normalize().toString();
            String normalizedPath = Path.of(filePath).normalize().toString();
            if (normalizedPath.equals(normalizedPlan)) return true;
        }

        // Exclude all memory files by path pattern
        return isMemoryFilePath(filePath);
    }

    /**
     * Create a snapshot of the current state for pre-compact capture.
     *
     * @return Deep copy as a plain Map (safe to use after clearing the original)
     */
    public Map<String, Long> snapshot() {
        return new LinkedHashMap<>(entries);
    }

    /**
     * Clear all tracked files.
     * Called after compaction to reset tracking for the new context.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Restore from a snapshot (e.g., after session resume).
     */
    public void restoreFrom(Map<String, Long> snapshot) {
        if (snapshot != null) {
            entries.putAll(snapshot);
        }
    }

    /**
     * Get number of tracked files.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Read file content within token budget.
     *
     * @param filePath Absolute file path
     * @param maxTokens Maximum tokens (~4 chars per token)
     * @return Content string truncated to budget, or null if file can't be read
     */
    public static String readFileWithinBudget(Path filePath, int maxTokens) {
        try {
            if (!Files.isRegularFile(filePath)) return null;

            String content = Files.readString(filePath);
            int maxChars = maxTokens * 4;

            if (content.length() <= maxChars) return content;
            return content.substring(0, maxChars) + "\n\n[... content truncated to fit token budget ...]";
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Create post-compact file attachments from tracked state.
     * Re-reads the most recently accessed files within token budget.
     *
     * @param snapshot Pre-compact state snapshot
     * @param planFilePath Plan file path to exclude (can be null)
     * @param preservedReadPaths File paths already visible in preserved messages (skip these)
     * @return List of attachment messages
     */
    public static List<Map<String, Object>> createPostCompactFileAttachments(
            Map<String, Long> snapshot,
            String planFilePath,
            Set<String> preservedReadPaths) {

        List<Map<String, Object>> attachments = new ArrayList<>();
        if (snapshot == null || snapshot.isEmpty()) return attachments;

        Set<String> preserved = preservedReadPaths != null ? preservedReadPaths : Set.of();

        // Sort by recency, filter excluded files and already-preserved files, take top N
        List<Map.Entry<String, Long>> recentFiles = snapshot.entrySet().stream()
                .filter(e -> !shouldExcludeFromPostCompactRestore(e.getKey(), planFilePath))
                .filter(e -> !preserved.contains(Path.of(e.getKey()).normalize().toString()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(POST_COMPACT_MAX_FILES_TO_RESTORE)
                .collect(Collectors.toList());

        long totalTokens = 0;

        for (Map.Entry<String, Long> entry : recentFiles) {
            Path path = Path.of(entry.getKey());
            String content = readFileWithinBudget(path, POST_COMPACT_MAX_TOKENS_PER_FILE);
            if (content == null || content.isEmpty()) continue;

            // Estimate tokens
            long estimatedTokens = (content.length() / 4) + 1;
            if (totalTokens + estimatedTokens > POST_COMPACT_TOKEN_BUDGET) break;
            totalTokens += estimatedTokens;

            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("role", "attachment");
            attachment.put("type", "file_reference");
            attachment.put("filename", path.toString());
            attachment.put("displayPath", path.getFileName().toString());
            attachment.put("content", Map.of(
                    "type", "text",
                    "file", Map.of(
                            "filePath", path.toString(),
                            "content", content
                    )
            ));
            attachment.put("timestamp", Instant.now().toString());
            attachments.add(attachment);
        }

        return attachments;
    }

    /**
     * Collect file paths from messages that contain Read tool results.
     * Used to avoid re-injecting files already visible in preserved messages.
     */
    public static Set<String> collectReadToolFilePaths(List<Map<String, Object>> messages) {
        Set<String> paths = new java.util.HashSet<>();

        if (messages == null) return paths;

        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) continue;

            Object contentObj = msg.get("content");
            if (!(contentObj instanceof List<?> content)) continue;

            for (Object block : content) {
                if (!(block instanceof Map<?, ?> map)) continue;
                if (!"tool_use".equals(map.get("type"))) continue;

                String name = map.get("name") instanceof String s ? s : null;
                if (!"Read".equals(name) && !"read".equals(name)) continue;

                Object input = map.get("input");
                if (input instanceof Map<?, ?> inputMap) {
                    Object filePath = inputMap.get("file_path");
                    if (filePath == null) filePath = inputMap.get("path");
                    if (filePath instanceof String fp) {
                        paths.add(Path.of(fp).normalize().toString());
                    }
                }
            }
        }

        return paths;
    }

    /**
     * Evict oldest entries to keep size under limit.
     */
    private void evictOldest() {
        if (entries.size() <= MAX_TRACKED_FILES) return;

        // Sort by timestamp, remove oldest
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        int toRemove = entries.size() - MAX_TRACKED_FILES;
        for (int i = 0; i < toRemove && i < sorted.size(); i++) {
            entries.remove(sorted.get(i).getKey());
        }
    }
}
