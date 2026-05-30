package agent.tool.file;

import context.auto.ReadFileState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-session FileStateCache and ReadFileState instances.
 *
 * In a multi-tab environment, a single AgentLoop serves multiple sessions.
 * Each session needs its own file state cache to avoid cross-session contamination:
 * - Tab A reads foo.java -> cached in session A's FileStateCache
 * - Tab B reads foo.java -> should NOT see session A's cache entry
 *
 * Thread-safe: uses ConcurrentHashMap with computeIfAbsent for lazy initialization.
 */
public class SessionFileStateManager {

    private final ConcurrentHashMap<String, FileStateCache> fileCaches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReadFileState> readStates = new ConcurrentHashMap<>();

    /**
     * Get the FileStateCache for a specific session.
     * Creates a new instance if one doesn't exist yet.
     *
     * @param sessionKey the session identifier
     * @return the session-specific FileStateCache, never null
     */
    public FileStateCache getFileCache(String sessionKey) {
        if (sessionKey == null) {
            return new FileStateCache.NoOp();
        }
        return fileCaches.computeIfAbsent(sessionKey, k -> new FileStateCache());
    }

    /**
     * Get the ReadFileState for a specific session.
     * Creates a new instance if one doesn't exist yet.
     *
     * @param sessionKey the session identifier
     * @return the session-specific ReadFileState, never null
     */
    public ReadFileState getReadState(String sessionKey) {
        if (sessionKey == null) {
            return new ReadFileState();
        }
        return readStates.computeIfAbsent(sessionKey, k -> new ReadFileState());
    }

    /**
     * Clear both caches for a specific session.
     * Called on /new, /clear, context compress, context pruning, /resume.
     *
     * @param sessionKey the session identifier
     */
    public void clearSession(String sessionKey) {
        if (sessionKey == null) return;
        FileStateCache cache = fileCaches.get(sessionKey);
        if (cache != null) {
            cache.clear();
        }
        ReadFileState state = readStates.get(sessionKey);
        if (state != null) {
            state.clear();
        }
    }

    /**
     * Clear all sessions' caches. Use with caution.
     */
    public void clearAll() {
        fileCaches.values().forEach(FileStateCache::clear);
        readStates.values().forEach(ReadFileState::clear);
    }

    /**
     * Remove a session entirely (e.g., when a tab is closed).
     *
     * @param sessionKey the session identifier
     */
    public void removeSession(String sessionKey) {
        if (sessionKey == null) return;
        FileStateCache cache = fileCaches.remove(sessionKey);
        if (cache != null) {
            cache.clear();
        }
        ReadFileState state = readStates.remove(sessionKey);
        if (state != null) {
            state.clear();
        }
    }

    /**
     * Check if a session has an active cache.
     *
     * @param sessionKey the session identifier
     * @return true if the session has a cache entry
     */
    public boolean hasSession(String sessionKey) {
        return sessionKey != null && fileCaches.containsKey(sessionKey);
    }

    /**
     * Get the number of active sessions.
     */
    public int sessionCount() {
        return fileCaches.size();
    }
}
