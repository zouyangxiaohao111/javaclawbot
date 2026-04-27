package agent.tool.file;

import java.nio.file.Path;
import java.util.*;

/**
 * LRU cache tracking file read state for read-before-write enforcement
 * and file_unchanged deduplication.
 *
 * Port of Claude Code's utils/fileStateCache.ts
 */
public class FileStateCache {

    /**
     * Cached state for a file that was read via the Read tool.
     * Port of Claude Code's FileState type.
     */
    public static final class FileState {
        public final String content;
        public final long timestamp;       // System.currentTimeMillis() at read time
        public final long mtimeMs;         // file modification time at read time
        public final long sizeBytes;       // file size at read time
        public final Integer offset;       // offset param used (null = full read)
        public final Integer limit;        // limit param used (null = full read)
        public final boolean isPartialView; // true when auto-injected content didn't match disk

        public FileState(String content, long timestamp, long mtimeMs, long sizeBytes,
                         Integer offset, Integer limit, boolean isPartialView) {
            this.content = content;
            this.timestamp = timestamp;
            this.mtimeMs = mtimeMs;
            this.sizeBytes = sizeBytes;
            this.offset = offset;
            this.limit = limit;
            this.isPartialView = isPartialView;
        }

        public FileState(String content, long mtimeMs, long sizeBytes) {
            this(content, System.currentTimeMillis(), mtimeMs, sizeBytes, null, null, false);
        }
    }

    /** Default max entries (matches Claude Code's READ_FILE_STATE_CACHE_SIZE = 100) */
    private static final int DEFAULT_MAX_ENTRIES = 100;

    /** Default size limit 25MB (matches Claude Code's DEFAULT_MAX_CACHE_SIZE_BYTES) */
    private static final long DEFAULT_MAX_CACHE_SIZE_BYTES = 25L * 1024 * 1024;

    private final int maxEntries;
    private final long maxSizeBytes;
    private long currentSizeBytes;
    private final LinkedHashMap<Path, FileState> cache;

    public FileStateCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_CACHE_SIZE_BYTES);
    }

    public FileStateCache(int maxEntries, long maxSizeBytes) {
        this.maxEntries = maxEntries;
        this.maxSizeBytes = maxSizeBytes;
        this.currentSizeBytes = 0;
        // access-order LRU
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** Normalize path for consistent cache hits */
    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** Calculate size of a FileState entry (based on content length) */
    private static long entrySize(FileState state) {
        return state.content != null ? Math.max(1, state.content.length() * 2L) : 1;
    }

    /** Evict entries until size constraint is met */
    private void evictIfNeeded(long incomingSize) {
        while ((currentSizeBytes + incomingSize > maxSizeBytes || cache.size() >= maxEntries)
                && !cache.isEmpty()) {
            Iterator<Map.Entry<Path, FileState>> it = cache.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<Path, FileState> eldest = it.next();
                currentSizeBytes -= entrySize(eldest.getValue());
                it.remove();
            }
        }
    }

    /** Record that a file was read with its content and metadata */
    public synchronized void markRead(Path path, String content, long mtimeMs, long sizeBytes) {
        Path key = normalize(path);
        FileState state = new FileState(content, mtimeMs, sizeBytes);
        FileState existing = cache.get(key);
        if (existing != null) {
            currentSizeBytes -= entrySize(existing);
        }
        long size = entrySize(state);
        evictIfNeeded(size);
        cache.put(key, state);
        currentSizeBytes += size;
    }

    /** Record with full state including offset/limit/partial view */
    public synchronized void markRead(Path path, String content, long mtimeMs, long sizeBytes,
                         Integer offset, Integer limit, boolean isPartialView) {
        Path key = normalize(path);
        FileState state = new FileState(content, System.currentTimeMillis(), mtimeMs, sizeBytes,
                offset, limit, isPartialView);
        FileState existing = cache.get(key);
        if (existing != null) {
            currentSizeBytes -= entrySize(existing);
        }
        long size = entrySize(state);
        evictIfNeeded(size);
        cache.put(key, state);
        currentSizeBytes += size;
    }

    /** Check if a file has been read via the Read tool */
    public synchronized boolean hasRead(Path path) {
        return cache.containsKey(normalize(path));
    }

    /** Get cached state, or null if not read */
    public synchronized FileState getState(Path path) {
        return cache.get(normalize(path));
    }

    /** Check if file is unchanged since last read (for file_unchanged dedup) */
    public synchronized boolean isUnchanged(Path path) {
        FileState state = cache.get(normalize(path));
        if (state == null) return false;
        try {
            long currentMtime = java.nio.file.Files.getLastModifiedTime(normalize(path)).toMillis();
            return currentMtime == state.mtimeMs;
        } catch (Exception e) {
            return false;
        }
    }

    /** Remove entry (typically after a write) */
    public synchronized void invalidate(Path path) {
        Path key = normalize(path);
        FileState existing = cache.remove(key);
        if (existing != null) {
            currentSizeBytes -= entrySize(existing);
        }
    }

    /** Clear all entries */
    public synchronized void clear() {
        cache.clear();
        currentSizeBytes = 0;
    }

    /** Number of cached entries */
    public synchronized int size() {
        return cache.size();
    }

    /** Current cache size in bytes */
    public synchronized long calculatedSize() {
        return currentSizeBytes;
    }

    /** All cached paths */
    public synchronized Set<Path> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(cache.keySet()));
    }

    /**
     * NoOp implementation that disables read-before-write enforcement.
     * Useful for backward-compatible constructors and subagent contexts.
     */
    public static final class NoOp extends FileStateCache {
        public NoOp() {
            super(1, 1);
        }

        @Override
        public void markRead(Path path, String content, long mtimeMs, long sizeBytes) {}

        @Override
        public void markRead(Path path, String content, long mtimeMs, long sizeBytes,
                             Integer offset, Integer limit, boolean isPartialView) {}

        @Override
        public boolean hasRead(Path path) {
            return true; // Always allow writes
        }

        @Override
        public FileState getState(Path path) {
            return null;
        }

        @Override
        public boolean isUnchanged(Path path) {
            return false; // Never dedup
        }

        @Override
        public void invalidate(Path path) {}

        @Override
        public void clear() {}

        @Override
        public int size() { return 0; }
    }
}
