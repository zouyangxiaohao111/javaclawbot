package agent.tool.plan;

import agent.subagent.definition.PermissionMode;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped plan mode state holder.
 *
 * Tracks plan mode status per session: current mode, pre-plan mode (for restore),
 * and the word slug for plan file naming.
 *
 * Corresponds to Open-ClaudeCode: plan mode state in bootstrap/state.ts
 */
public class PlanModeState {

    /** Current permission mode per session */
    private static final ConcurrentHashMap<String, PermissionMode> sessionModes = new ConcurrentHashMap<>();

    /** Mode before entering plan mode (for restoration) */
    private static final ConcurrentHashMap<String, PermissionMode> sessionPreModes = new ConcurrentHashMap<>();

    /** Word slug per session */
    private static final ConcurrentHashMap<String, String> sessionWordSlugs = new ConcurrentHashMap<>();

    private PlanModeState() {}

    /**
     * Enter plan mode for a session.
     * Saves the current mode as prePlanMode, sets mode to PLAN,
     * and generates a word slug.
     *
     * @return the generated word slug
     */
    public static String enterPlanMode(String sessionId) {
        PermissionMode current = sessionModes.getOrDefault(sessionId, PermissionMode.BYPASS_PERMISSIONS);
        sessionPreModes.put(sessionId, current);
        sessionModes.put(sessionId, PermissionMode.PLAN);

        String wordSlug = WordSlugGenerator.generateWordSlug();
        sessionWordSlugs.put(sessionId, wordSlug);
        return wordSlug;
    }

    /**
     * Exit plan mode for a session.
     * Restores the pre-plan mode and clears the plan mode marker.
     * The word slug is preserved (needed for reading the plan file).
     */
    public static void exitPlanMode(String sessionId) {
        PermissionMode preMode = sessionPreModes.remove(sessionId);
        if (preMode != null) {
            sessionModes.put(sessionId, preMode);
        } else {
            sessionModes.remove(sessionId);
        }
    }

    public static boolean isPlanMode(String sessionId) {
        return PermissionMode.PLAN == sessionModes.get(sessionId);
    }

    public static PermissionMode getCurrentMode(String sessionId) {
        return sessionModes.getOrDefault(sessionId, PermissionMode.BYPASS_PERMISSIONS);
    }

    public static String getWordSlug(String sessionId) {
        return sessionWordSlugs.get(sessionId);
    }

    /**
     * Restore plan mode state on session resume.
     * Used when /resume reconnects a session that was in plan mode.
     *
     * @param sessionId the session ID (sessionKey)
     * @param wordSlug the plan word slug extracted from session messages
     */
    public static void restorePlanMode(String sessionId, String wordSlug) {
        PermissionMode current = sessionModes.getOrDefault(sessionId, PermissionMode.BYPASS_PERMISSIONS);
        sessionPreModes.put(sessionId, current);
        sessionModes.put(sessionId, PermissionMode.PLAN);
        sessionWordSlugs.put(sessionId, wordSlug);
    }

    /** Clear all state for a session (called on /clear) */
    public static void clearSession(String sessionId) {
        sessionModes.remove(sessionId);
        sessionPreModes.remove(sessionId);
        sessionWordSlugs.remove(sessionId);
    }
}
