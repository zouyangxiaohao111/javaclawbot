package memory;

import cn.hutool.core.collection.CollUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.Helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 记忆索引管理器
 *
 * 对齐 OpenClaw 的 MemoryIndexManager，并在此基础上做了工程化增强：
 *
 * 主要能力：
 * 1. 基于 SQLite 的记忆索引存储
 * 2. 支持 FTS5 全文搜索
 * 3. FTS5 不可用时自动降级为纯 Java 关键词搜索
 * 4. 可选向量搜索（embedding）
 * 5. 混合搜索（向量 + 关键词）
 * 6. 监视 memory / sessions 目录变更并自动触发同步
 * 7. 增量同步，只处理变化文件
 *
 * 本修正版重点修复：
 * - 修复“FTS 不可用时 Java fallback 根本不会执行”的逻辑 bug
 * - 为 sync 增加事务，避免半写入状态
 * - 同步结束后执行 WAL checkpoint，避免误判主库为空
 * - watcher 增加防抖，避免频繁重复同步
 * - 向量搜索跳过坏 embedding，避免一条坏数据拖垮整次搜索
 * - 真正实现 chunk overlap
 */
public class MemoryIndexManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexManager.class);

    // ==================== 常量 ====================

    /** FTS 表名 */
    private static final String FTS_TABLE = "chunks_fts";

    /** 嵌入缓存表名（当前保留结构，未来可扩展） */
    private static final String EMBEDDING_CACHE_TABLE = "embedding_cache";

    /** 搜索结果片段最大字符数 */
    private static final int SNIPPET_MAX_CHARS = 700;

    /** 默认分块 token 数 */
    private static final int DEFAULT_CHUNK_TOKENS = 400;

    /** 默认重叠 token 数 */
    private static final int DEFAULT_CHUNK_OVERLAP = 80;

    /** 默认最大结果数 */
    private static final int DEFAULT_MAX_RESULTS = 6;

    /** 默认最小分数 */
    private static final double DEFAULT_MIN_SCORE = 0.35;

    /** watcher 防抖窗口：同一批次频繁文件事件，合并成一次 sync */
    private static final long WATCH_DEBOUNCE_MS = 1200L;
    /**
     * 文件监控间隔(秒)
     */
    private static final long WATCH_INTERVAL_SECONDS = 10;

    // ==================== 配置 ====================

    /** 工作目录 */
    private final Path workspaceDir;

    /** 数据库路径 */
    private final Path dbPath;

    /** 嵌入提供者 */
    private EmbeddingProvider embeddingProvider;

    /** 是否启用向量搜索 */
    private boolean vectorEnabled = true;

    /** 是否启用 FTS */
    private boolean ftsEnabled = true;

    /** 是否启用文件监视 */
    private boolean watchEnabled = true;

    /** 分块目标 token 数 */
    private int chunkTokens = DEFAULT_CHUNK_TOKENS;

    /** 分块重叠 token 数 */
    private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;

    /** 最大结果数 */
    private int maxResults = DEFAULT_MAX_RESULTS;

    /** 最小分数 */
    private double minScore = DEFAULT_MIN_SCORE;

    /** 混合搜索配置 */
    private MemoryHybridSearch.HybridConfig hybridConfig = new MemoryHybridSearch.HybridConfig();

    /** 搜索来源（memory / sessions） */
    private Set<String> sources = new HashSet<>(Collections.singletonList("memory"));

    /** sessions 目录（可选） */
    private Path sessionsDir;

    // ==================== 状态 ====================

    /** 数据库连接 */
    private Connection dbConnection;

    /** 是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 是否需要同步 */
    private volatile boolean dirty = true;

    /** 文件监视器 */
    private WatchService watchService;

    /** 文件监视线程 */
    private Thread watchThread;

    /** 最后同步时间 */
    private LocalDateTime lastSyncTime;

    /** 最近一次文件事件时间，用于 watcher 防抖 */
    private volatile long lastWatchEventAt = 0L;

    // ==================== 构造函数 ====================

    public MemoryIndexManager(Path workspaceDir) {
        this.workspaceDir = Objects.requireNonNull(workspaceDir, "工作目录不能为空");
        this.dbPath = workspaceDir.resolve(".memory").resolve("memory_index.db");
    }

    public MemoryIndexManager(Path workspaceDir, Path dbPath) {
        this.workspaceDir = Objects.requireNonNull(workspaceDir, "工作目录不能为空");
        this.dbPath = Objects.requireNonNull(dbPath, "数据库路径不能为空");
    }

    // ==================== 初始化 ====================

    /**
     * 初始化索引管理器
     *
     * 说明：
     * - 只初始化一次
     * - 创建 SQLite 连接
     * - 配置 WAL
     * - 创建表结构
     * - 启动 watcher
     */
    public synchronized void initialize() throws SQLException {
        if (dbConnection != null) {
            return;
        }

        // 确保数据库目录存在
        Helpers.ensureDir(dbPath.getParent());

        // 连接数据库
        String dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        dbConnection = DriverManager.getConnection(dbUrl);

        // 配置 SQLite
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA temp_store = MEMORY");
        }

        // 创建表结构
        ensureSchema();

        // 启动文件监视
        if (watchEnabled) {
            startWatcher();
        }

        log.info("记忆索引管理器初始化完成: {}", dbPath.toAbsolutePath());
    }

    /**
     * 创建数据库表结构
     */
    private void ensureSchema() throws SQLException {
        try (Statement stmt = dbConnection.createStatement()) {
            // 元数据表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);

            // 文件表：记录每个文件的 hash/mtime/size，用于增量同步判断
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    path TEXT PRIMARY KEY,
                    source TEXT NOT NULL DEFAULT 'memory',
                    hash TEXT NOT NULL,
                    mtime INTEGER NOT NULL,
                    size INTEGER NOT NULL
                )
                """);

            // 分块表：实际搜索的主表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    path TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT 'memory',
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    hash TEXT NOT NULL,
                    model TEXT NOT NULL,
                    text TEXT NOT NULL,
                    embedding TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);

            // 嵌入缓存表（当前保留结构，后续可做 embedding cache）
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    provider TEXT NOT NULL,
                    model TEXT NOT NULL,
                    provider_key TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    embedding TEXT NOT NULL,
                    dims INTEGER,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (provider, model, provider_key, hash)
                )
                """.formatted(EMBEDDING_CACHE_TABLE));

            // 常规索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_source ON chunks(source)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embedding_cache_updated_at ON %s(updated_at)".formatted(EMBEDDING_CACHE_TABLE));

            // FTS5 虚拟表
            if (ftsEnabled) {
                try {
                    stmt.execute("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS %s USING fts5(
                            text,
                            id UNINDEXED,
                            path UNINDEXED,
                            source UNINDEXED,
                            model UNINDEXED,
                            start_line UNINDEXED,
                            end_line UNINDEXED
                        )
                        """.formatted(FTS_TABLE));
                    log.info("FTS5 全文搜索已启用");
                } catch (SQLException e) {
                    log.warn("FTS5 不可用，将自动降级为纯 Java 关键词搜索: {}", e.getMessage());
                    ftsEnabled = false;
                }
            }
        }
    }

    // ==================== 搜索 ====================

    /**
     * 使用默认参数搜索
     */
    public List<MemoryHybridSearch.HybridResult> search(String query) throws SQLException {
        return search(query, maxResults, minScore, null);
    }

    /**
     * 搜索记忆
     *
     * 关键修复：
     * 以前这里写成了 if (ftsEnabled && hybridConfig.enabled)，
     * 导致 FTS5 一旦不可用，Java fallback 也根本不会执行。
     *
     * 现在改成：
     * - 只要 hybridConfig.enabled，就调用 searchKeyword()
     * - searchKeyword() 内部自行决定走 FTS5 还是 Java fallback
     */
    public List<MemoryHybridSearch.HybridResult> search(
            String query,
            int maxResults,
            double minScore,
            String sessionKey
    ) throws SQLException {
        ensureInitialized();

        // 如果有脏数据，先同步
        if (dirty) {
            sync();
        }

        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        int candidates = Math.min(200, Math.max(1, maxResults * hybridConfig.candidateMultiplier));

        // 向量搜索
        List<MemoryHybridSearch.VectorResult> vectorResults = Collections.emptyList();
        boolean isVector = vectorEnabled && embeddingProvider != null;
        if (isVector) {
            vectorResults = searchVector(cleaned, candidates);
        }

        // 关键词搜索：不再受 ftsEnabled 外层阻断
        List<MemoryHybridSearch.KeywordResult> keywordResults = Collections.emptyList();
        if (hybridConfig.enabled) {
            keywordResults = searchKeyword(cleaned, candidates);
        }

        // 合并结果
        List<MemoryHybridSearch.HybridResult> merged = MemoryHybridSearch.mergeHybridResults(
                vectorResults,
                keywordResults,
                hybridConfig,
                workspaceDir, isVector
        );

        // 按分数过滤 + 截断
        return merged.stream()
                .filter(r -> r.score >= minScore)
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 向量搜索
     *
     * 改进点：
     * - 跳过空 embedding
     * - 跳过损坏 embedding
     * - 单条 chunk 解码失败不影响整个搜索
     */
    private List<MemoryHybridSearch.VectorResult> searchVector(String query, int limit) {
        if (embeddingProvider == null) {
            return Collections.emptyList();
        }

        try {
            // 生成查询向量
            float[] queryVec = embeddingProvider.embedQuery(query);
            if (queryVec == null || queryVec.length == 0) {
                return Collections.emptyList();
            }

            // 从数据库加载所有分块
            List<ChunkEntry> chunks = loadChunks();

            // 计算相似度
            List<MemoryHybridSearch.VectorResult> results = new ArrayList<>();

            for (ChunkEntry chunk : chunks) {
                try {
                    if (chunk.embedding == null || chunk.embedding.isBlank()) {
                        continue;
                    }

                    float[] chunkVec = VectorUtils.decode(chunk.embedding);
                    if (chunkVec == null || chunkVec.length == 0) {
                        continue;
                    }

                    double similarity = VectorUtils.cosineSimilarity(queryVec, chunkVec);

                    results.add(new MemoryHybridSearch.VectorResult(
                            chunk.id,
                            chunk.path,
                            chunk.startLine,
                            chunk.endLine,
                            chunk.source,
                            truncateSnippet(chunk.text),
                            similarity
                    ));
                } catch (Exception ex) {
                    log.warn("跳过损坏 embedding，chunkId={}, path={}", chunk.id, chunk.path, ex);
                }
            }

            results.sort((a, b) -> Double.compare(b.vectorScore, a.vectorScore));

            if (results.size() > limit) {
                return new ArrayList<>(results.subList(0, limit));
            }
            return results;

        } catch (Exception e) {
            log.error("向量搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 关键词搜索统一入口
     *
     * 说明：
     * - 如果 FTS5 可用，则走 SQLite FTS5
     * - 如果 FTS5 不可用，则自动走纯 Java fallback
     */
    private List<MemoryHybridSearch.KeywordResult> searchKeyword(String query, int limit) {
        String ftsQuery = MemoryHybridSearch.buildFtsQuery(query);
        if (ftsQuery == null) {
            return Collections.emptyList();
        }

        try {
            if (ftsEnabled) {
                var res = searchKeywordByFts(ftsQuery, limit);

                if (CollUtil.isEmpty(res)) {
                    log.info("sqllite FTS查询为空,启用纯java搜索");
                    return searchKeywordJava(query, limit);
                }
                return res;
            } else {
                return searchKeywordJava(query, limit);
            }
        } catch (SQLException e) {
            log.error("关键词搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 使用 SQLite FTS5 进行关键词搜索
     */
    private List<MemoryHybridSearch.KeywordResult> searchKeywordByFts(String ftsQuery, int limit) throws SQLException {
        List<MemoryHybridSearch.KeywordResult> results = new ArrayList<>();

        String sql = """
            SELECT id, path, source, start_line, end_line, text,
                   bm25(%s) AS rank
            FROM %s
            WHERE %s MATCH ?
            ORDER BY rank ASC
            LIMIT ?
            """.formatted(FTS_TABLE, FTS_TABLE, FTS_TABLE);

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, ftsQuery);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    double rank = rs.getDouble("rank");
                    double textScore = MemoryHybridSearch.bm25RankToScore(rank);

                    results.add(new MemoryHybridSearch.KeywordResult(
                            rs.getString("id"),
                            rs.getString("path"),
                            rs.getInt("start_line"),
                            rs.getInt("end_line"),
                            rs.getString("source"),
                            truncateSnippet(rs.getString("text")),
                            textScore
                    ));
                }
            }
        }

        return results;
    }

    /**
     * 纯 Java 关键词搜索
     *
     * 说明：
     * - 当前使用简单 Jaccard 相似度
     * - 当 FTS5 不可用时作为后备方案
     */
    private List<MemoryHybridSearch.KeywordResult> searchKeywordJava(String query, int limit) throws SQLException {
        // 分词
        Set<String> queryTokens = MemoryMMR.tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChunkEntry> chunks = loadChunks();
        List<MemoryHybridSearch.KeywordResult> results = new ArrayList<>();

        for (ChunkEntry chunk : chunks) {
            Set<String> chunkTokens = MemoryMMR.tokenize(chunk.text);

            // 计算 Jaccard 相似度
            double similarity = MemoryMMR.jaccardSimilarity(queryTokens, chunkTokens);

            if (similarity > 0) {
                results.add(new MemoryHybridSearch.KeywordResult(
                        chunk.id,
                        chunk.path,
                        chunk.startLine,
                        chunk.endLine,
                        chunk.source,
                        truncateSnippet(chunk.text),
                        similarity
                ));
            }
        }

        // 按分数排序
        results.sort((a, b) -> Double.compare(b.textScore, a.textScore));

        // 截取结果
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }

        return results;
    }

    // ==================== 同步 ====================

    /**
     * 同步记忆文件到索引
     *
     * 关键增强：
     * 1. 整个 sync 放入事务
     * 2. 同步结束后 checkpoint WAL
     * 3. 日志更完整，方便定位问题
     */
    public synchronized void sync() throws SQLException {
        ensureInitialized();

        log.info("开始同步记忆索引...");

        boolean oldAutoCommit = dbConnection.getAutoCommit();

        try {
            dbConnection.setAutoCommit(false);

            // 1) 扫描 memory 文件
            List<Path> memoryFiles = listMemoryFiles();
            log.info("扫描到 memory 文件 {} 个: {}",
                    memoryFiles.size(),
                    memoryFiles.stream()
                            .map(p -> workspaceDir.relativize(p).toString())
                            .collect(Collectors.toList()));

            // 2) 扫描 session 文件
            List<SessionFiles.SessionFileEntry> sessionFiles = Collections.emptyList();
            if (sources.contains("sessions") && sessionsDir != null) {
                sessionFiles = listAndBuildSessionFiles();
                log.info("扫描到 session 文件 {} 个", sessionFiles.size());
            }

            // 3) 加载当前已索引文件
            Map<String, FileEntry> indexedFiles = loadIndexedFiles();
            log.info("当前数据库已索引文件 {} 个", indexedFiles.size());

            // 4) 计算增量变更
            List<Path> toAdd = new ArrayList<>();
            List<SessionFiles.SessionFileEntry> sessionsToAdd = new ArrayList<>();
            List<String> toRemove = new ArrayList<>();

            Set<String> currentPaths = new HashSet<>();

            for (Path file : memoryFiles) {
                String relativePath = workspaceDir.relativize(file).toString();
                currentPaths.add(relativePath);

                FileEntry indexed = indexedFiles.get(relativePath);
                if (indexed == null || isFileChanged(file, indexed)) {
                    toAdd.add(file);
                }
            }

            // 处理 sessions 文件
            for (SessionFiles.SessionFileEntry session : sessionFiles) {
                currentPaths.add(session.path);

                FileEntry indexed = indexedFiles.get(session.path);
                if (indexed == null || !indexed.hash.equals(session.hash)) {
                    sessionsToAdd.add(session);
                }
            }

            // 检测删除的文件
            for (String path : indexedFiles.keySet()) {
                if (!currentPaths.contains(path)) {
                    toRemove.add(path);
                }
            }

            log.info("本次同步计划: memory新增/更新 {} 个, sessions新增/更新 {} 个, 删除 {} 个",
                    toAdd.size(), sessionsToAdd.size(), toRemove.size());

            // 5) 删除过期索引
            for (String path : toRemove) {
                log.info("删除旧索引: {}", path);
                removeFileFromIndex(path);
            }

            // 6) 索引 memory 文件
            for (Path file : toAdd) {
                log.info("开始索引 memory 文件: {}", file);
                indexFile(file);
            }

            // 7) 索引 session 文件
            for (SessionFiles.SessionFileEntry session : sessionsToAdd) {
                log.info("开始索引 session 文件: {}", session.path);
                indexSessionFile(session);
            }

            // 8) 提交事务
            dbConnection.commit();

            // 9) 做一次 WAL checkpoint，避免主库文件大小看起来像“没数据”
            checkpointWal();

            dirty = false;
            lastSyncTime = LocalDateTime.now();

            log.info("记忆索引同步完成: memory 新增/更新 {}, sessions 新增/更新 {}, 删除 {}",
                    toAdd.size(), sessionsToAdd.size(), toRemove.size());

        } catch (Exception e) {
            try {
                dbConnection.rollback();
            } catch (SQLException rollbackEx) {
                log.error("回滚同步事务失败", rollbackEx);
            }
            throw new SQLException("同步记忆索引失败", e);
        } finally {
            try {
                dbConnection.setAutoCommit(oldAutoCommit);
            } catch (SQLException e) {
                log.warn("恢复 autoCommit 失败", e);
            }
        }
    }

    /**
     * 同步指定 session 文件
     *
     * 这里同样使用事务，确保一批 session 文件要么全部成功，要么全部回滚。
     */
    public synchronized void syncSessions(List<String> sessionFiles) throws SQLException {
        if (sessionFiles == null || sessionFiles.isEmpty()) {
            return;
        }

        ensureInitialized();

        boolean oldAutoCommit = dbConnection.getAutoCommit();

        try {
            dbConnection.setAutoCommit(false);

            log.info("开始同步会话文件: {} 个", sessionFiles.size());

            for (String sessionFile : sessionFiles) {
                Path absPath = sessionsDir != null ? sessionsDir.resolve(sessionFile) : null;
                if (absPath == null || !Files.exists(absPath)) {
                    continue;
                }

                SessionFiles.SessionFileEntry entry = SessionFiles.buildSessionEntry(absPath, sessionsDir);
                if (entry != null) {
                    indexSessionFile(entry);
                }
            }

            dbConnection.commit();
            checkpointWal();

            log.info("会话文件同步完成");

        } catch (Exception e) {
            try {
                dbConnection.rollback();
            } catch (SQLException rollbackEx) {
                log.error("回滚会话同步事务失败", rollbackEx);
            }
            throw new SQLException("同步会话文件失败", e);
        } finally {
            try {
                dbConnection.setAutoCommit(oldAutoCommit);
            } catch (SQLException e) {
                log.warn("恢复 autoCommit 失败", e);
            }
        }
    }

    /**
     * 检查文件是否变化
     *
     * 这里只比较 mtime + size，速度快，适合作为增量同步预判断。
     * 真正写入时会重新计算 hash。
     */
    private boolean isFileChanged(Path file, FileEntry entry) throws IOException {
        if (!Files.exists(file)) {
            return true;
        }

        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);

        return mtime != entry.mtime || size != entry.size;
    }

    /**
     * 索引单个 memory 文件
     */
    private void indexFile(Path file) throws IOException, SQLException {
        String relativePath = workspaceDir.relativize(file).toString();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String hash = hashText(content);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);

        log.info("索引 memory 文件: {}, 大小={} bytes", relativePath, size);

        List<TextChunk> chunks = chunkText(content);
        log.info("文件 {} 分块完成，共 {} 块", relativePath, chunks.size());

        // 先删旧索引，再写新索引
        removeFileFromIndex(relativePath);

        // 插入新的分块
        String model = embeddingProvider != null ? embeddingProvider.getModel() : "none";
        int inserted = 0;

        for (TextChunk chunk : chunks) {
            String chunkId = UUID.randomUUID().toString();
            String chunkHash = hashText(chunk.text);

            // 生成嵌入
            String embedding = "";
            if (embeddingProvider != null && vectorEnabled) {
                try {
                    float[] vec = embeddingProvider.embedQuery(chunk.text);
                    if (vec != null && vec.length > 0) {
                        embedding = VectorUtils.encode(vec);
                    }
                } catch (Exception e) {
                    log.warn("生成嵌入失败，文件={}, 行号={}-{}, 原因={}",
                            relativePath, chunk.startLine, chunk.endLine, e.getMessage());
                }
            }

            insertChunk(
                    chunkId,
                    relativePath,
                    "memory",
                    chunk.startLine,
                    chunk.endLine,
                    chunkHash,
                    model,
                    chunk.text,
                    embedding
            );
            inserted++;
        }

        // 更新文件记录
        updateFileRecord(relativePath, "memory", hash, mtime, size);

        log.info("文件 {} 索引完成，成功写入 {} 个 chunks", relativePath, inserted);
    }

    /**
     * 索引单个 session 文件
     */
    private void indexSessionFile(SessionFiles.SessionFileEntry session) throws SQLException {
        log.info("索引 session 文件: {}", session.path);

        removeFileFromIndex(session.path);

        // 分块
        List<TextChunk> chunks = chunkText(session.content);
        log.info("session 文件 {} 分块完成，共 {} 块", session.path, chunks.size());

        // 插入新的分块
        String model = embeddingProvider != null ? embeddingProvider.getModel() : "none";
        int inserted = 0;

        for (TextChunk chunk : chunks) {
            String chunkId = UUID.randomUUID().toString();
            String chunkHash = hashText(chunk.text);

            // 映射行号到 JSONL 源行号
            int jsonlStartLine = mapToSourceLine(session.lineMap, chunk.startLine);
            int jsonlEndLine = mapToSourceLine(session.lineMap, chunk.endLine);

            // 生成嵌入
            String embedding = "";
            if (embeddingProvider != null && vectorEnabled) {
                try {
                    float[] vec = embeddingProvider.embedQuery(chunk.text);
                    if (vec != null && vec.length > 0) {
                        embedding = VectorUtils.encode(vec);
                    }
                } catch (Exception e) {
                    log.warn("生成嵌入失败，session={}, 行号={}-{}, 原因={}",
                            session.path, jsonlStartLine, jsonlEndLine, e.getMessage());
                }
            }

            insertChunk(
                    chunkId,
                    session.path,
                    "sessions",
                    jsonlStartLine,
                    jsonlEndLine,
                    chunkHash,
                    model,
                    chunk.text,
                    embedding
            );
            inserted++;
        }

        // 更新文件记录
        updateFileRecord(session.path, "sessions", session.hash, session.mtimeMs, session.size);

        log.info("session 文件 {} 索引完成，成功写入 {} 个 chunks", session.path, inserted);
    }

    /**
     * 内容行号映射回源文件行号
     */
    private int mapToSourceLine(int[] lineMap, int contentLine) {
        int idx = contentLine - 1;
        if (lineMap != null && idx >= 0 && idx < lineMap.length) {
            return lineMap[idx];
        }
        return contentLine;
    }

    /**
     * 插入一个 chunk
     *
     * 说明：
     * - 主表 chunks 一定会写
     * - 如果 FTS5 可用，则同步写入 FTS 表
     */
    private void insertChunk(
            String id,
            String path,
            String source,
            int startLine,
            int endLine,
            String hash,
            String model,
            String text,
            String embedding
    ) throws SQLException {

        String insertSql = """
            INSERT INTO chunks (id, path, source, start_line, end_line, hash, model, text, embedding, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(insertSql)) {
            stmt.setString(1, id);
            stmt.setString(2, path);
            stmt.setString(3, source);
            stmt.setInt(4, startLine);
            stmt.setInt(5, endLine);
            stmt.setString(6, hash);
            stmt.setString(7, model);
            stmt.setString(8, text);
            stmt.setString(9, embedding != null ? embedding : "");
            stmt.setLong(10, System.currentTimeMillis());
            stmt.executeUpdate();
        }

        // 插入 FTS 索引
        if (ftsEnabled) {
            String ftsSql = """
                INSERT INTO %s (text, id, path, source, model, start_line, end_line)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.formatted(FTS_TABLE);

            try (PreparedStatement stmt = dbConnection.prepareStatement(ftsSql)) {
                stmt.setString(1, text);
                stmt.setString(2, id);
                stmt.setString(3, path);
                stmt.setString(4, source);
                stmt.setString(5, model);
                stmt.setInt(6, startLine);
                stmt.setInt(7, endLine);
                stmt.executeUpdate();
            }
        }
    }

    /**
     * 更新文件记录
     */
    private void updateFileRecord(String path, String source, String hash, long mtime, long size) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO files (path, source, hash, mtime, size)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, path);
            stmt.setString(2, source);
            stmt.setString(3, hash);
            stmt.setLong(4, mtime);
            stmt.setLong(5, size);
            stmt.executeUpdate();
        }
    }

    /**
     * 从索引中删除某个文件
     *
     * 注意：
     * - 先删 chunks
     * - 再删 FTS
     * - 最后删 files
     */
    private void removeFileFromIndex(String relativePath) throws SQLException {
        // 删除分块
        try (PreparedStatement stmt = dbConnection.prepareStatement("DELETE FROM chunks WHERE path = ?")) {
            stmt.setString(1, relativePath);
            stmt.executeUpdate();
        }

        // 删除 FTS 索引
        if (ftsEnabled) {
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                    "DELETE FROM %s WHERE path = ?".formatted(FTS_TABLE))) {
                stmt.setString(1, relativePath);
                stmt.executeUpdate();
            }
        }

        // 删除文件记录
        try (PreparedStatement stmt = dbConnection.prepareStatement("DELETE FROM files WHERE path = ?")) {
            stmt.setString(1, relativePath);
            stmt.executeUpdate();
        }
    }

    /**
     * 分块文本
     *
     * 修复点：
     * - 旧版虽然有 chunkOverlap 参数，但根本没实际使用
     * - 本版按“行列表 + token 估算”真正实现 overlap
     *
     * 实现思路：
     * 1. 先按行组织
     * 2. 当累计 token 超过 chunkTokens 时切块
     * 3. 新块起点会向后回退，保留约 chunkOverlap token 的重叠区
     */
    private List<TextChunk> chunkText(String content) {
        List<TextChunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }

        String[] lines = content.split("\n", -1);
        int[] lineTokens = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lineTokens[i] = Math.max(1, estimateTokens(lines[i]));
        }

        int startIdx = 0;

        while (startIdx < lines.length) {
            int tokenSum = 0;
            int endIdx = startIdx;

            // 尽量向后扩，直到接近 chunkTokens
            while (endIdx < lines.length) {
                int next = tokenSum + lineTokens[endIdx];
                if (next > chunkTokens && endIdx > startIdx) {
                    break;
                }
                tokenSum = next;
                endIdx++;
            }

            // 构建当前块 [startIdx, endIdx)
            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                sb.append(lines[i]);
                if (i < endIdx - 1) {
                    sb.append('\n');
                }
            }

            String chunkText = sb.toString().trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new TextChunk(chunkText, startIdx + 1, endIdx));
            }

            // 已到末尾，结束
            if (endIdx >= lines.length) {
                break;
            }

            // 计算 overlap：从 endIdx 往前回退若干行，使累计 token 接近 chunkOverlap
            int overlapTokens = 0;
            int nextStart = endIdx;

            while (nextStart > startIdx) {
                int prevLineIdx = nextStart - 1;
                int nextTokens = overlapTokens + lineTokens[prevLineIdx];
                if (nextTokens > chunkOverlap && nextStart < endIdx) {
                    break;
                }
                overlapTokens = nextTokens;
                nextStart--;
            }

            // 防止死循环
            if (nextStart <= startIdx) {
                nextStart = Math.min(endIdx, startIdx + 1);
            }

            startIdx = nextStart;
        }

        return chunks;
    }

    /**
     * 粗略估算 token 数
     *
     * 当前策略较简单：字符数 / 4
     * 对中文/英文混合文本并不精确，但足够用于分块近似控制。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    // ==================== 文件监视 ====================

    /**
     * 启动文件监视
     *
     * 说明：
     * - 当前只监视 memory 根目录 / sessions 根目录
     * - 非递归监视；子目录变化不会自动感知
     * - 但 sync 仍会递归扫描文件，所以不会丢数据，只是自动触发可能不够及时
     */
    private void startWatcher() {
        if (watchService != null) {
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();

            // 监视 memory 目录
            Path memoryDir = workspaceDir.resolve("memory");
            if (Files.exists(memoryDir)) {
                memoryDir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                );
            }

            // 监视 sessions 目录
            if (sessionsDir != null && Files.exists(sessionsDir)) {
                sessionsDir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                );
            }

            watchThread = new Thread(this::watchLoop, "memory-watcher");
            watchThread.setDaemon(true);
            watchThread.start();

            log.info("文件监视已启动");

        } catch (IOException e) {
            log.warn("启动文件监视失败: {}", e.getMessage());
        }
    }

    /**
     * watcher 循环
     *
     * 改进点：
     * - 旧版是每来一个事件就立即 sync，保存一次文件可能触发多次全量同步
     * - 本版做了简单防抖：同一批连续事件合并成一次 sync
     */
    private void watchLoop() {
        while (!closed.get() && watchService != null) {
            try {
                WatchKey key = watchService.poll(WATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                boolean hasEvent = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changedFile = (Path) event.context();
                    log.debug("检测到文件变更: {} {}", event.kind(), changedFile);
                    dirty = true;
                    lastWatchEventAt = System.currentTimeMillis();
                    hasEvent = true;
                }

                key.reset();

                if (!hasEvent) {
                    continue;
                }

                // 简单防抖：等待短时间，吸收同一批连续变更
                // by zcw 太过频繁了 太耗费性能, 改为当查询接入在进行索引
                /*Thread.sleep(WATCH_DEBOUNCE_MS);

                long now = System.currentTimeMillis();
                if (now - lastWatchEventAt >= WATCH_DEBOUNCE_MS && dirty) {
                    try {
                        sync();
                    } catch (SQLException e) {
                        log.error("watcher 自动同步记忆索引失败", e);
                    }
                }*/

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("文件监视循环异常", e);
            }
        }
    }

    /**
     * 停止文件监视
     */
    private void stopWatcher() {
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 确保已初始化
     */
    private void ensureInitialized() throws SQLException {
        if (dbConnection == null) {
            initialize();
        }
    }

    /**
     * 执行 WAL checkpoint
     *
     * 作用：
     * - 把 WAL 中的内容刷回主库
     * - 避免你看到 .db 很小、.wal 很大时误判“数据库没数据”
     */
    private void checkpointWal() {
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.debug("WAL checkpoint 完成");
        } catch (SQLException e) {
            log.warn("执行 WAL checkpoint 失败: {}", e.getMessage());
        }
    }

    /**
     * 列出所有 memory 文件
     *
     * 范围：
     * - workspace/memory/MEMORY.md | workspace/memory/*.md
     * -
     */
    private List<Path> listMemoryFiles() throws IOException {
        List<Path> files = new ArrayList<>();

        Path memoryFile = workspaceDir.resolve("MEMORY.md");
        if (Files.exists(memoryFile)) {
            files.add(memoryFile);
        }

        Path memoryDir = workspaceDir.resolve("memory");
        if (Files.exists(memoryDir)) {
            try (Stream<Path> stream = Files.walk(memoryDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(files::add);
            }
        }

        return files;
    }

    /**
     * 构建 session 文件条目
     */
    private List<SessionFiles.SessionFileEntry> listAndBuildSessionFiles() {
        if (sessionsDir == null || !Files.exists(sessionsDir)) {
            return Collections.emptyList();
        }

        List<SessionFiles.SessionFileEntry> entries = new ArrayList<>();
        List<Path> files = SessionFiles.listSessionFiles(sessionsDir);

        for (Path file : files) {
            SessionFiles.SessionFileEntry entry = SessionFiles.buildSessionEntry(file, sessionsDir);
            if (entry != null) {
                entries.add(entry);
            }
        }

        return entries;
    }

    /**
     * 加载已索引文件元信息
     */
    private Map<String, FileEntry> loadIndexedFiles() throws SQLException {
        Map<String, FileEntry> files = new HashMap<>();

        String sql = "SELECT path, source, hash, mtime, size FROM files";

        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                files.put(rs.getString("path"), new FileEntry(
                        rs.getString("path"),
                        rs.getString("source"),
                        rs.getString("hash"),
                        rs.getLong("mtime"),
                        rs.getLong("size")
                ));
            }
        }

        return files;
    }

    /**
     * 加载所有 chunks
     *
     * 注意：
     * 当前向量搜索 / Java fallback 搜索都会全量加载 chunks。
     * 后续如果数据量继续增大，可以再做分页或更深层优化。
     */
    private List<ChunkEntry> loadChunks() throws SQLException {
        List<ChunkEntry> chunks = new ArrayList<>();

        String sql = "SELECT id, path, source, start_line, end_line, text, embedding FROM chunks";

        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                chunks.add(new ChunkEntry(
                        rs.getString("id"),
                        rs.getString("path"),
                        rs.getString("source"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        rs.getString("text"),
                        rs.getString("embedding")
                ));
            }
        }

        return chunks;
    }

    /**
     * 计算文本 hash
     */
    private String hashText(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(Objects.hashCode(text));
        }
    }

    /**
     * 截断展示片段
     */
    private String truncateSnippet(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= SNIPPET_MAX_CHARS) {
            return text;
        }
        return text.substring(0, SNIPPET_MAX_CHARS) + "...";
    }

    // ==================== Getter / Setter ====================

    public void setEmbeddingProvider(EmbeddingProvider provider) {
        this.embeddingProvider = provider;
        this.dirty = true;
    }

    public void setVectorEnabled(boolean enabled) {
        this.vectorEnabled = enabled;
    }

    public void setFtsEnabled(boolean enabled) {
        this.ftsEnabled = enabled;
    }

    public void setWatchEnabled(boolean enabled) {
        this.watchEnabled = enabled;
    }

    public void setChunkTokens(int tokens) {
        this.chunkTokens = Math.max(1, tokens);
    }

    public void setChunkOverlap(int overlap) {
        this.chunkOverlap = Math.max(0, overlap);
    }

    public void setMaxResults(int max) {
        this.maxResults = Math.max(1, max);
    }

    public void setMinScore(double score) {
        this.minScore = Math.max(0, Math.min(1, score));
    }

    public void setHybridConfig(MemoryHybridSearch.HybridConfig config) {
        this.hybridConfig = config != null ? config : new MemoryHybridSearch.HybridConfig();
    }

    /**
     * 设置搜索来源
     */
    public void setSources(Set<String> sources) {
        this.sources = sources != null
                ? new HashSet<>(sources)
                : new HashSet<>(Collections.singletonList("memory"));
        this.dirty = true;
    }

    /**
     * 设置 session 目录
     */
    public void setSessionsDir(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public Set<String> getSources() {
        return Collections.unmodifiableSet(sources);
    }

    public Path getSessionsDir() {
        return sessionsDir;
    }

    // ==================== 文件读取 ====================

    /**
     * 安全读取文件片段
     */
    public MemoryTypes.ReadFileResult readFile(String relPath, Integer from, Integer lines) {
        if (relPath == null || relPath.isEmpty()) {
            return MemoryTypes.ReadFileResult.error("", "Path is required");
        }

        try {
            Path file = workspaceDir.resolve(relPath);

            // 防路径穿越
            if (!file.normalize().startsWith(workspaceDir.normalize())) {
                return MemoryTypes.ReadFileResult.error(relPath, "Access denied: path outside workspace");
            }

            if (!Files.isRegularFile(file)) {
                return MemoryTypes.ReadFileResult.error(relPath, "File not found: " + relPath);
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);

            if (from != null || lines != null) {
                String[] allLines = content.split("\n");
                int startLine = from != null ? Math.max(1, from) : 1;
                int lineCount = lines != null ? lines : allLines.length;

                StringBuilder result = new StringBuilder();
                for (int i = startLine - 1; i < Math.min(startLine - 1 + lineCount, allLines.length); i++) {
                    if (i >= 0 && i < allLines.length) {
                        if (result.length() > 0) {
                            result.append('\n');
                        }
                        result.append(allLines[i]);
                    }
                }
                content = result.toString();
            }

            return new MemoryTypes.ReadFileResult(relPath, content);

        } catch (IOException e) {
            return MemoryTypes.ReadFileResult.error(relPath, "Failed to read file: " + e.getMessage());
        }
    }

    // ==================== 状态查询 ====================

    /**
     * 返回当前索引状态
     */
    public MemoryTypes.MemoryProviderStatus status() {
        try {
            ensureInitialized();

            int fileCount = 0;
            int chunkCount = 0;
            List<MemoryTypes.SourceCount> sourceCounts = new ArrayList<>();

            try (Statement stmt = dbConnection.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM files")) {
                    if (rs.next()) {
                        fileCount = rs.getInt(1);
                    }
                }

                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
                    if (rs.next()) {
                        chunkCount = rs.getInt(1);
                    }
                }

                try (ResultSet rs = stmt.executeQuery(
                        "SELECT source, COUNT(DISTINCT path) as files, COUNT(*) as chunks " +
                                "FROM chunks GROUP BY source")) {
                    while (rs.next()) {
                        sourceCounts.add(new MemoryTypes.SourceCount(
                                MemoryTypes.MemorySource.fromValue(rs.getString("source")),
                                rs.getInt("files"),
                                rs.getInt("chunks")
                        ));
                    }
                }
            }

            return MemoryTypes.MemoryProviderStatus.builder()
                    .backend("builtin")
                    .provider(embeddingProvider != null ? embeddingProvider.getId() : "none")
                    .model(embeddingProvider != null ? embeddingProvider.getModel() : null)
                    .files(fileCount)
                    .chunks(chunkCount)
                    .dirty(dirty)
                    .workspaceDir(workspaceDir.toString())
                    .dbPath(dbPath.toString())
                    .sources(sources.stream()
                            .map(MemoryTypes.MemorySource::fromValue)
                            .collect(Collectors.toList()))
                    .sourceCounts(sourceCounts)
                    .fts(new MemoryTypes.FtsStatus(ftsEnabled, ftsEnabled, null))
                    .vector(new MemoryTypes.VectorStatus(
                            vectorEnabled && embeddingProvider != null,
                            vectorEnabled && embeddingProvider != null,
                            null,
                            null,
                            embeddingProvider != null ? embeddingProvider.getDimensions() : null
                    ))
                    .build();

        } catch (SQLException e) {
            return MemoryTypes.MemoryProviderStatus.builder()
                    .backend("builtin")
                    .provider("error")
                    .dirty(true)
                    .workspaceDir(workspaceDir.toString())
                    .dbPath(dbPath != null ? dbPath.toString() : null)
                    .fts(new MemoryTypes.FtsStatus(false, false, e.getMessage()))
                    .build();
        }
    }

    /**
     * 探测 embedding 能否正常生成
     */
    public MemoryTypes.MemoryEmbeddingProbeResult probeEmbeddingAvailability() {
        if (embeddingProvider == null) {
            return MemoryTypes.MemoryEmbeddingProbeResult.error("No embedding provider configured");
        }

        try {
            float[] embedding = embeddingProvider.embedQuery("test");
            if (embedding != null && embedding.length > 0) {
                return MemoryTypes.MemoryEmbeddingProbeResult.ok();
            } else {
                return MemoryTypes.MemoryEmbeddingProbeResult.error("Embedding returned empty result");
            }
        } catch (Exception e) {
            return MemoryTypes.MemoryEmbeddingProbeResult.error(e.getMessage());
        }
    }

    /**
     * 探测向量搜索是否可用
     */
    public boolean probeVectorAvailability() {
        return vectorEnabled && embeddingProvider != null;
    }

    // ==================== 关闭 ====================

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopWatcher();

            if (dbConnection != null) {
                try {
                    checkpointWal();
                } catch (Exception ignored) {
                }

                try {
                    dbConnection.close();
                } catch (SQLException ignored) {
                }

                dbConnection = null;
            }

            if (embeddingProvider != null) {
                embeddingProvider.close();
            }

            log.info("记忆索引管理器已关闭");
        }
    }

    // ==================== 内部类 ====================

    /**
     * 已索引文件元信息
     */
    private static class FileEntry {
        final String path;
        final String source;
        final String hash;
        final long mtime;
        final long size;

        FileEntry(String path, String source, String hash, long mtime, long size) {
            this.path = path;
            this.source = source;
            this.hash = hash;
            this.mtime = mtime;
            this.size = size;
        }
    }

    /**
     * chunk 数据对象
     */
    private static class ChunkEntry {
        final String id;
        final String path;
        final String source;
        final int startLine;
        final int endLine;
        final String text;
        final String embedding;

        ChunkEntry(String id, String path, String source, int startLine, int endLine, String text, String embedding) {
            this.id = id;
            this.path = path;
            this.source = source;
            this.startLine = startLine;
            this.endLine = endLine;
            this.text = text;
            this.embedding = embedding;
        }
    }

    /**
     * 文本分块对象
     */
    private static class TextChunk {
        final String text;
        final int startLine;
        final int endLine;

        TextChunk(String text, int startLine, int endLine) {
            this.text = text;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}