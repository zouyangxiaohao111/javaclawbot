package memory;

import org.slf4j.*;
import utils.Helpers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.*;

/**
 * 记忆索引管理器
 *
 * 对齐 OpenClaw 的 MemoryIndexManager
 *
 * 功能：
 * - 向量搜索：使用嵌入向量进行语义搜索
 * - 关键词搜索：使用 FTS5 进行全文搜索
 * - 混合搜索：结合向量和关键词搜索
 * - 文件监视：自动检测文件变更并更新索引
 * - 增量同步：只处理变更的文件
 */
public class MemoryIndexManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexManager.class);

    // ==================== 常量 ====================

    /** 向量表名 */
    private static final String VECTOR_TABLE = "chunks_vec";

    /** FTS 表名 */
    private static final String FTS_TABLE = "chunks_fts";

    /** 嵌入缓存表名 */
    private static final String EMBEDDING_CACHE_TABLE = "embedding_cache";

    /** 片段最大字符数 */
    private static final int SNIPPET_MAX_CHARS = 700;

    /** 默认分块 token 数 */
    private static final int DEFAULT_CHUNK_TOKENS = 400;

    /** 默认分块重叠 token 数 */
    private static final int DEFAULT_CHUNK_OVERLAP = 80;

    /** 默认最大结果数 */
    private static final int DEFAULT_MAX_RESULTS = 6;

    /** 默认最小分数 */
    private static final double DEFAULT_MIN_SCORE = 0.35;

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

    /** 分块 token 数 */
    private int chunkTokens = DEFAULT_CHUNK_TOKENS;

    /** 分块重叠 token 数 */
    private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;

    /** 最大结果数 */
    private int maxResults = DEFAULT_MAX_RESULTS;

    /** 最小分数 */
    private double minScore = DEFAULT_MIN_SCORE;

    /** 混合搜索配置 */
    private MemoryHybridSearch.HybridConfig hybridConfig = new MemoryHybridSearch.HybridConfig();

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
     */
    public synchronized void initialize() throws SQLException {
        if (dbConnection != null) {
            return;
        }

        // 确保目录存在
        Helpers.ensureDir(dbPath.getParent());

        // 连接数据库
        String dbUrl = "jdbc:sqlite:" + dbPath.toString();
        dbConnection = DriverManager.getConnection(dbUrl);

        // 设置 PRAGMA
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
            stmt.execute("PRAGMA journal_mode = WAL");
        }

        // 创建表结构
        ensureSchema();

        // 启动文件监视
        if (watchEnabled) {
            startWatcher();
        }

        log.info("记忆索引管理器初始化完成: {}", dbPath);
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

            // 文件表
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    path TEXT PRIMARY KEY,
                    source TEXT NOT NULL DEFAULT 'memory',
                    hash TEXT NOT NULL,
                    mtime INTEGER NOT NULL,
                    size INTEGER NOT NULL
                )
                """);

            // 分块表
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

            // 嵌入缓存表
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

            // 索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_source ON chunks(source)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embedding_cache_updated_at ON %s(updated_at)".formatted(EMBEDDING_CACHE_TABLE));

            // FTS 虚拟表
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
                    log.warn("FTS5 不可用，将使用纯 Java 关键词搜索: {}", e.getMessage());
                    ftsEnabled = false;
                }
            }
        }
    }

    // ==================== 搜索 ====================

    /**
     * 搜索记忆
     *
     * @param query 查询文本
     * @return 搜索结果
     */
    public List<MemoryHybridSearch.HybridResult> search(String query) throws SQLException {
        return search(query, maxResults, minScore, null);
    }

    /**
     * 搜索记忆
     *
     * @param query      查询文本
     * @param maxResults 最大结果数
     * @param minScore   最小分数
     * @param sessionKey 会话键（可选）
     * @return 搜索结果
     */
    public List<MemoryHybridSearch.HybridResult> search(
            String query,
            int maxResults,
            double minScore,
            String sessionKey
    ) throws SQLException {
        ensureInitialized();

        // 如果需要同步，先同步
        if (dirty) {
            sync();
        }

        String cleaned = query.trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        int candidates = Math.min(200, Math.max(1, maxResults * hybridConfig.candidateMultiplier));

        // 执行向量搜索
        List<MemoryHybridSearch.VectorResult> vectorResults = Collections.emptyList();
        if (vectorEnabled && embeddingProvider != null) {
            vectorResults = searchVector(cleaned, candidates);
        }

        // 执行关键词搜索
        List<MemoryHybridSearch.KeywordResult> keywordResults = Collections.emptyList();
        if (ftsEnabled && hybridConfig.enabled) {
            keywordResults = searchKeyword(cleaned, candidates);
        }

        // 合并结果
        List<MemoryHybridSearch.HybridResult> merged = MemoryHybridSearch.mergeHybridResults(
                vectorResults, keywordResults, hybridConfig, workspaceDir
        );

        // 过滤最小分数
        List<MemoryHybridSearch.HybridResult> filtered = merged.stream()
                .filter(r -> r.score >= minScore)
                .collect(Collectors.toList());

        // 截取最大结果数
        if (filtered.size() > maxResults) {
            return filtered.subList(0, maxResults);
        }

        return filtered;
    }

    /**
     * 向量搜索
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
                float[] chunkVec = VectorUtils.decode(chunk.embedding);
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
            }

            // 按相似度排序
            results.sort((a, b) -> Double.compare(b.vectorScore, a.vectorScore));

            // 截取结果
            if (results.size() > limit) {
                return results.subList(0, limit);
            }

            return results;

        } catch (Exception e) {
            log.error("向量搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 关键词搜索
     */
    private List<MemoryHybridSearch.KeywordResult> searchKeyword(String query, int limit) {
        String ftsQuery = MemoryHybridSearch.buildFtsQuery(query);
        if (ftsQuery == null) {
            return Collections.emptyList();
        }

        try {
            List<MemoryHybridSearch.KeywordResult> results = new ArrayList<>();

            if (ftsEnabled) {
                // 使用 SQLite FTS5
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
            } else {
                // 使用纯 Java 关键词匹配
                results = searchKeywordJava(query, limit);
            }

            return results;

        } catch (SQLException e) {
            log.error("关键词搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 纯 Java 关键词搜索（FTS5 不可用时的后备方案）
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
            return results.subList(0, limit);
        }

        return results;
    }

    // ==================== 同步 ====================

    /**
     * 同步记忆文件到索引
     */
    public synchronized void sync() throws SQLException {
        ensureInitialized();

        log.info("开始同步记忆索引...");

        try {
            // 获取所有记忆文件
            List<Path> memoryFiles = listMemoryFiles();

            // 获取已索引的文件
            Map<String, FileEntry> indexedFiles = loadIndexedFiles();

            // 检测变更
            List<Path> toAdd = new ArrayList<>();
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

            // 检测删除的文件
            for (String path : indexedFiles.keySet()) {
                if (!currentPaths.contains(path)) {
                    toRemove.add(path);
                }
            }

            // 删除过期的索引
            for (String path : toRemove) {
                removeFileFromIndex(path);
            }

            // 添加新的索引
            for (Path file : toAdd) {
                indexFile(file);
            }

            dirty = false;
            lastSyncTime = LocalDateTime.now();

            log.info("记忆索引同步完成: 新增 {}, 删除 {}", toAdd.size(), toRemove.size());

        } catch (IOException e) {
            throw new SQLException("同步记忆索引失败", e);
        }
    }

    /**
     * 检查文件是否变更
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
     * 索引单个文件
     */
    private void indexFile(Path file) throws IOException, SQLException {
        String relativePath = workspaceDir.relativize(file).toString();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String hash = hashText(content);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);

        // 分块
        List<TextChunk> chunks = chunkText(content);

        // 删除旧的分块
        removeFileFromIndex(relativePath);

        // 插入新的分块
        String model = embeddingProvider != null ? embeddingProvider.getModel() : "none";

        for (TextChunk chunk : chunks) {
            String chunkId = UUID.randomUUID().toString();
            String chunkHash = hashText(chunk.text);

            // 生成嵌入
            String embedding = "";
            if (embeddingProvider != null && vectorEnabled) {
                try {
                    float[] vec = embeddingProvider.embedQuery(chunk.text);
                    embedding = VectorUtils.encode(vec);
                } catch (Exception e) {
                    log.warn("生成嵌入失败: {}", e.getMessage());
                }
            }

            // 插入分块
            String insertSql = """
                INSERT INTO chunks (id, path, source, start_line, end_line, hash, model, text, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement stmt = dbConnection.prepareStatement(insertSql)) {
                stmt.setString(1, chunkId);
                stmt.setString(2, relativePath);
                stmt.setString(3, "memory");
                stmt.setInt(4, chunk.startLine);
                stmt.setInt(5, chunk.endLine);
                stmt.setString(6, chunkHash);
                stmt.setString(7, model);
                stmt.setString(8, chunk.text);
                stmt.setString(9, embedding);
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
                    stmt.setString(1, chunk.text);
                    stmt.setString(2, chunkId);
                    stmt.setString(3, relativePath);
                    stmt.setString(4, "memory");
                    stmt.setString(5, model);
                    stmt.setInt(6, chunk.startLine);
                    stmt.setInt(7, chunk.endLine);
                    stmt.executeUpdate();
                }
            }
        }

        // 更新文件记录
        String fileSql = """
            INSERT OR REPLACE INTO files (path, source, hash, mtime, size)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(fileSql)) {
            stmt.setString(1, relativePath);
            stmt.setString(2, "memory");
            stmt.setString(3, hash);
            stmt.setLong(4, mtime);
            stmt.setLong(5, size);
            stmt.executeUpdate();
        }
    }

    /**
     * 从索引中删除文件
     */
    private void removeFileFromIndex(String relativePath) throws SQLException {
        // 删除分块
        try (PreparedStatement stmt = dbConnection.prepareStatement("DELETE FROM chunks WHERE path = ?")) {
            stmt.setString(1, relativePath);
            stmt.executeUpdate();
        }

        // 删除 FTS 索引
        if (ftsEnabled) {
            try (PreparedStatement stmt = dbConnection.prepareStatement("DELETE FROM %s WHERE path = ?".formatted(FTS_TABLE))) {
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
     */
    private List<TextChunk> chunkText(String content) {
        List<TextChunk> chunks = new ArrayList<>();

        String[] lines = content.split("\n");
        int currentLine = 1;
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;
        int chunkStartLine = 1;

        for (String line : lines) {
            int lineTokens = estimateTokens(line);

            if (currentTokens + lineTokens > chunkTokens && currentChunk.length() > 0) {
                // 保存当前块
                chunks.add(new TextChunk(currentChunk.toString().trim(), chunkStartLine, currentLine - 1));

                // 开始新块（保留重叠）
                currentChunk = new StringBuilder();
                currentTokens = 0;
                chunkStartLine = currentLine;
            }

            currentChunk.append(line).append("\n");
            currentTokens += lineTokens;
            currentLine++;
        }

        // 保存最后一块
        if (currentChunk.length() > 0) {
            chunks.add(new TextChunk(currentChunk.toString().trim(), chunkStartLine, currentLine - 1));
        }

        return chunks;
    }

    /**
     * 估算 token 数
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 简单估算：字符数 / 4
        return text.length() / 4;
    }

    // ==================== 文件监视 ====================

    /**
     * 启动文件监视
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
                memoryDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
            }

            // 启动监视线程
            watchThread = new Thread(this::watchLoop, "memory-watcher");
            watchThread.setDaemon(true);
            watchThread.start();

            log.info("文件监视已启动");

        } catch (IOException e) {
            log.warn("启动文件监视失败: {}", e.getMessage());
        }
    }

    /**
     * 文件监视循环
     */
    private void watchLoop() {
        while (!closed.get() && watchService != null) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changedFile = (Path) event.context();
                    log.debug("检测到文件变更: {} {}", event.kind(), changedFile);

                    // 标记需要同步
                    dirty = true;
                }

                key.reset();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
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
            } catch (IOException e) {
                // 忽略
            }
            watchService = null;
        }
    }

    // ==================== 辅助方法 ====================

    private void ensureInitialized() throws SQLException {
        if (dbConnection == null) {
            initialize();
        }
    }

    private List<Path> listMemoryFiles() throws IOException {
        List<Path> files = new ArrayList<>();

        // MEMORY.md
        Path memoryFile = workspaceDir.resolve("MEMORY.md");
        if (Files.exists(memoryFile)) {
            files.add(memoryFile);
        }

        // memory/ 目录下的所有 .md 文件
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

    private String hashText(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    private String truncateSnippet(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= SNIPPET_MAX_CHARS) {
            return text;
        }
        return text.substring(0, SNIPPET_MAX_CHARS) + "...";
    }

    // ==================== Getter/Setter ====================

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

    public boolean isDirty() {
        return dirty;
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    // ==================== 关闭 ====================

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopWatcher();

            if (dbConnection != null) {
                try {
                    dbConnection.close();
                } catch (SQLException e) {
                    // 忽略
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