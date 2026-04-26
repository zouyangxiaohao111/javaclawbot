package context.auto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plan file manager aligned with Open-ClaudeCode: src/utils/plans.ts
 *
 * Manages plan files for session-scoped planning context.
 * Plans are stored in ~/.javaclawbot/plans/ with session-keyed filenames.
 *
 * Key functions:
 * - getPlan(sessionId): read plan content
 * - getPlanFilePath(sessionId): resolve plan file path
 * - hasPlan(sessionId): check plan existence
 * - writePlan(sessionId, content): write plan to disk
 * - deletePlan(sessionId): remove plan file
 */
public class PlanFileManager {

    private static final Logger log = LoggerFactory.getLogger(PlanFileManager.class);

    /** Plans directory relative to user home */
    private static final String PLANS_DIR = ".javaclawbot/plans";

    private PlanFileManager() {}

    /**
     * Get the plans directory.
     * Creates the directory if it doesn't exist.
     */
    public static Path getPlansDirectory() {
        Path dir = Path.of(System.getProperty("user.home"), PLANS_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create plans directory: {}", dir, e);
        }
        return dir;
    }

    /**
     * Get the plan file path for a session.
     *
     * @param sessionId Session identifier
     * @return Path to the plan file
     */
    public static Path getPlanFilePath(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        // Sanitize session ID for use as filename (remove path separators)
        String safeName = sessionId.replaceAll("[\\\\/:*?\"<>|]", "_");
        return getPlansDirectory().resolve(safeName + ".md");
    }

    /**
     * Get the plan file path for a subagent.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier
     * @return Path to the agent-specific plan file
     */
    public static Path getAgentPlanFilePath(String sessionId, String agentId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (agentId == null || agentId.isBlank()) return getPlanFilePath(sessionId);

        String safeSession = sessionId.replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeAgent = agentId.replaceAll("[\\\\/:*?\"<>|]", "_");
        return getPlansDirectory().resolve(safeSession + "-agent-" + safeAgent + ".md");
    }

    /**
     * Check if a plan exists for the session.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier (can be null for main session)
     * @return true if a plan file exists and is non-empty
     */
    public static boolean hasPlan(String sessionId, String agentId) {
        Path path = agentId != null
                ? getAgentPlanFilePath(sessionId, agentId)
                : getPlanFilePath(sessionId);
        if (path == null) return false;
        try {
            return Files.exists(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the plan content for a session.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier (can be null for main session)
     * @return Plan content, or null if no plan exists
     */
    public static String getPlan(String sessionId, String agentId) {
        Path path = agentId != null
                ? getAgentPlanFilePath(sessionId, agentId)
                : getPlanFilePath(sessionId);
        if (path == null) return null;

        try {
            if (!Files.exists(path)) return null;
            String content = Files.readString(path);
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            log.warn("Failed to read plan file: {}", path, e);
            return null;
        }
    }

    /**
     * Write plan content to disk.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier (can be null)
     * @param content Plan content
     */
    public static boolean writePlan(String sessionId, String agentId, String content) {
        Path path = agentId != null
                ? getAgentPlanFilePath(sessionId, agentId)
                : getPlanFilePath(sessionId);
        if (path == null) return false;

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content != null ? content : "");
            return true;
        } catch (IOException e) {
            log.warn("Failed to write plan file: {}", path, e);
            return false;
        }
    }

    /**
     * Delete a plan file.
     *
     * @param sessionId Session identifier
     * @param agentId Agent identifier (can be null)
     */
    public static boolean deletePlan(String sessionId, String agentId) {
        Path path = agentId != null
                ? getAgentPlanFilePath(sessionId, agentId)
                : getPlanFilePath(sessionId);
        if (path == null) return false;

        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete plan file: {}", path, e);
            return false;
        }
    }

    /**
     * Get the plan display path (relative to user home, for display).
     */
    public static String getPlanDisplayPath(String sessionId, String agentId) {
        Path path = agentId != null
                ? getAgentPlanFilePath(sessionId, agentId)
                : getPlanFilePath(sessionId);
        if (path == null) return null;

        Path home = Path.of(System.getProperty("user.home"));
        try {
            return home.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    // =====================
    // Word-slug based plan file methods
    // Used by EnterPlanModeTool / ExitPlanModeTool
    // =====================

    /**
     * Get the plan file path for a word slug.
     */
    public static Path getPlanFilePathBySlug(String wordSlug) {
        if (wordSlug == null || wordSlug.isBlank()) {
            return null;
        }
        String safeName = wordSlug.replaceAll("[\\\\/:*?\"<>|]", "_");
        return getPlansDirectory().resolve(safeName + ".md");
    }

    /**
     * Check if a plan exists for a word slug.
     */
    public static boolean hasPlanBySlug(String wordSlug) {
        Path path = getPlanFilePathBySlug(wordSlug);
        if (path == null) return false;
        try {
            return Files.exists(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the plan content for a word slug.
     */
    public static String getPlanBySlug(String wordSlug) {
        Path path = getPlanFilePathBySlug(wordSlug);
        if (path == null) return null;
        try {
            if (!Files.exists(path)) return null;
            String content = Files.readString(path);
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            log.warn("Failed to read plan file by slug: {}", path, e);
            return null;
        }
    }

    /**
     * Write plan content for a word slug.
     */
    public static boolean writePlanBySlug(String wordSlug, String content) {
        Path path = getPlanFilePathBySlug(wordSlug);
        if (path == null) return false;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content != null ? content : "");
            return true;
        } catch (IOException e) {
            log.warn("Failed to write plan file by slug: {}", path, e);
            return false;
        }
    }

    /**
     * Delete a plan file by word slug.
     */
    public static boolean deletePlanBySlug(String wordSlug) {
        Path path = getPlanFilePathBySlug(wordSlug);
        if (path == null) return false;
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete plan file by slug: {}", path, e);
            return false;
        }
    }
}
