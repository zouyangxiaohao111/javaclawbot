package session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import utils.Helpers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 会话管理器（增强稳健版）
 *
 * 改进点：
 * 1. 保存前递归清洗非法 Unicode，避免 UTF-8 写文件时报 MalformedInputException
 * 2. 使用 tmp 文件 + 原子替换，避免写一半把正式文件写坏
 * 3. 对同一 session key 做串行化保存，避免并发写乱文件
 * 4. 加载时尽量容错：坏行跳过，严重损坏时备份文件
 * 5. 使用 sessionId 作为文件名，每次新会话生成新的 sessionId（对齐 OpenClaw）
 *
 * Session Key vs Session ID：
 * - sessionKey：用于标识会话路由（如 "cli:direct"），是固定的
 * - sessionId：用于标识具体的会话实例（如 "amber-atlas"），每次新会话生成新的
 */
@Slf4j
public final class SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManager.class.getName());

    @Getter
    private final Path workspace;
    private final Path sessionsDir;
    private final Path legacySessionsDir;

    /** 会话缓存：sessionKey -> Session */
    private final Map<String, Session> cache = new ConcurrentHashMap<>();

    /** sessionKey -> sessionId 映射（用于从 key 找到对应的文件） */
    private final Map<String, String> keyToIdMap = new ConcurrentHashMap<>();

    /** 每个 session key 一把锁，避免同一个文件被并发写 */
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionManager(Path workspace) {
        this.workspace = workspace;
        this.sessionsDir = Helpers.ensureDir(workspace.resolve("sessions"));
        this.legacySessionsDir = Path.of(System.getProperty("user.home"), ".javaclawbot", "sessions");
        
        // 加载 sessionKey -> sessionId 映射
        loadKeyToIdMap();
    }


    /**
     * 获取或创建会话
     *
     * 如果缓存中有，返回缓存的会话
     * 如果缓存中没有，尝试从文件加载
     * 如果文件也不存在，创建新会话（生成新的 sessionId）
     */
    public Session getOrCreate(String sessionKey) {
        return cache.computeIfAbsent(sessionKey, k -> {
            // 尝试从映射中找到对应的 sessionId
            String sessionId = keyToIdMap.get(k);
            
            if (sessionId != null) {
                // 有映射，尝试加载对应的文件
                Session loaded = loadBySessionId(k, sessionId);
                if (loaded != null) {
                    return loaded;
                }
            }
            
            // 没有映射或加载失败，尝试从旧文件加载（兼容旧版本）
            Session legacy = loadLegacy(k);
            if (legacy != null) {
                // 更新映射
                keyToIdMap.put(k, legacy.getSessionId());
                saveKeyToIdMap();
                return legacy;
            }
            
            // 创建新会话
            Session newSession = new Session(k);
            keyToIdMap.put(k, newSession.getSessionId());
            saveKeyToIdMap();
            return newSession;
        });
    }

    /**
     * 创建新会话（用于 /new 命令）
     *
     * 生成新的 sessionId，保存旧会话到文件，然后创建新会话
     */
    public Session createNew(String key) {
        // 先保存旧会话（如果存在）
        Session oldSession = cache.get(key);
        if (oldSession != null) {
            save(oldSession);
        }
        
        // 创建新会话
        Session newSession = new Session(key);
        
        // 更新映射和缓存
        keyToIdMap.put(key, newSession.getSessionId());
        saveKeyToIdMap();
        cache.put(key, newSession);
        
        return newSession;
    }

    /**
     * 恢复指定会话（用于 /resume 命令）
     *
     * 更新 sessionKey -> sessionId 映射，让当前 channel 指向指定的 sessionId
     *
     * @param sessionKey 当前会话键（如 channel:chatId）
     * @param sessionId 要恢复的 sessionId
     */
    public void resumeSession(String sessionKey, String sessionId) {
        if (sessionKey == null || sessionId == null) {
            throw new IllegalArgumentException("sessionKey and sessionId cannot be null");
        }

        // 更新映射
        keyToIdMap.put(sessionKey, sessionId);
        saveKeyToIdMap();

        log.info("Session resume: sessionKey={} now points to sessionId={}", sessionKey, sessionId);
    }

    /**
     * 从缓存中移除指定 key 的会话，强制下次 getOrCreate 从磁盘加载。
     */
    public void evictFromCache(String sessionKey) {
        cache.remove(sessionKey);
    }

    /**
     * 保存会话（原子写入 + 清洗非法字符 + 同 key 加锁）
     */
    public void save(Session session) {
        String key = session.getKey();
        String sessionId = session.getSessionId();
        
        ReentrantLock lock = sessionLocks.computeIfAbsent(key, k -> new ReentrantLock());

        try {
            lock.lock();
            Path target = getSessionPath(sessionId);
            Helpers.ensureDir(target.getParent());

            // 临时文件：与目标文件放同目录，保证 move 更稳
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");

            // 先清洗，避免坏字符在写文件阶段炸掉
            Map<String, Object> safeMetadata = castMap(deepSanitize(session.getMetadata()));
            List<Map<String, Object>> safeMessages = castListOfMap(deepSanitize(session.getMessages()));

            try (BufferedWriter w = Files.newBufferedWriter(
                    tmp,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                Map<String, Object> metaLine = new LinkedHashMap<>();
                metaLine.put("_type", "metadata");
                metaLine.put("key", sanitizeString(session.getKey()));
                metaLine.put("session_id", sanitizeString(session.getSessionId()));
                metaLine.put("created_at", session.getCreatedAt().toString());
                metaLine.put("updated_at", session.getUpdatedAt().toString());
                metaLine.put("metadata", safeMetadata);
                metaLine.put("last_consolidated", session.getLastConsolidated());
                // 保存 usage 数据（对齐 Claude Code）
                Map<String, Object> usageData = new LinkedHashMap<>();
                usageData.put("input_tokens", session.getInputTokens());
                usageData.put("output_tokens", session.getOutputTokens());
                usageData.put("cache_read", session.getCacheRead());
                usageData.put("cache_write", session.getCacheWrite());
                usageData.put("total_tokens", session.getTotalTokens());
                metaLine.put("usage", usageData);

                // 保存上一次对话的上下文大小（用于判断压缩必要性）
                Map<String, Object> lastCallData = new LinkedHashMap<>();
                lastCallData.put("input_tokens", session.getLastCallInput());
                lastCallData.put("output_tokens", session.getLastCallOutput());
                lastCallData.put("cache_read", session.getLastCallCacheRead());
                lastCallData.put("cache_write", session.getLastCallCacheWrite());
                metaLine.put("last_call", lastCallData);

                w.write(objectMapper.writeValueAsString(metaLine));
                w.write("\n");

                for (Map<String, Object> msg : safeMessages) {
                    w.write(objectMapper.writeValueAsString(msg));
                    w.write("\n");
                }

                w.flush();
            }

            // 原子替换：避免正式文件写一半损坏
            atomicReplace(tmp, target);

            // 更新映射
            keyToIdMap.put(key, sessionId);
            saveKeyToIdMap();

            // 缓存里也放"已清洗版本"，避免下次 save 继续炸
            session.setMessages(safeMessages);
            session.setMetadata(safeMetadata);
            cache.put(key, session);

        } catch (Exception e) {
            LOG.log(Level.WARNING, "保存会话失败：" + key + "，原因：" + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 使缓存失效
     */
    public void invalidate(String key) {
        cache.remove(key);
    }

    /**
     * 列出所有会话（只读取第一行 metadata）
     */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path path : stream) {
                try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String firstLine = r.readLine();
                    if (firstLine == null) continue;
                    firstLine = firstLine.trim();
                    if (firstLine.isEmpty()) continue;

                    Map<String, Object> data = objectMapper.readValue(
                            firstLine,
                            new TypeReference<Map<String, Object>>() {}
                    );
                    if (!"metadata".equals(data.get("_type"))) continue;

                    String key = (String) data.get("key");
                    String sessionId = (String) data.get("session_id");
                    if (key == null || key.isBlank()) {
                        key = stripExtension(path.getFileName().toString());
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("session_id", sessionId);
                    item.put("created_at", data.get("created_at"));
                    item.put("updated_at", data.get("updated_at"));
                    item.put("path", path.toString());
                    sessions.add(item);

                } catch (Exception ignore) {
                    // 与原逻辑一致：坏文件直接跳过
                }
            }
        } catch (Exception e) {
            return List.of();
        }

        sessions.sort((a, b) -> {
            String ua = String.valueOf(a.getOrDefault("updated_at", ""));
            String ub = String.valueOf(b.getOrDefault("updated_at", ""));
            return ub.compareTo(ua);
        });

        return sessions;
    }

    // =========================
    // 内部方法
    // =========================

    /**
     * 获取会话文件路径（使用 sessionId 作为文件名）
     */
    private Path getSessionPath(String sessionId) {
        String safeId = Helpers.safeFilename(sessionId);
        return sessionsDir.resolve(safeId + ".jsonl");
    }

    private Path getLegacySessionPath(String key) {
        String safeKey = Helpers.safeFilename(key.replace(":", "_"));
        return legacySessionsDir.resolve(safeKey + ".jsonl");
    }

    /**
     * 按 sessionId 加载会话
     */
    private Session loadBySessionId(String key, String sessionId) {
        Path path = getSessionPath(sessionId);
        if (!Files.exists(path)) {
            return null;
        }
        
        return loadFromPath(key, path);
    }

    /**
     * 从旧文件加载会话（兼容旧版本）
     */
    private Session loadLegacy(String key) {
        // 尝试新目录
        Path newPath = sessionsDir.resolve(Helpers.safeFilename(key.replace(":", "_")) + ".jsonl");
        if (Files.exists(newPath)) {
            Session session = loadFromPath(key, newPath);
            if (session != null) return session;
        }
        
        // 尝试旧目录
        Path legacyPath = getLegacySessionPath(key);
        if (Files.exists(legacyPath)) {
            try {
                Helpers.ensureDir(newPath.getParent());
                Files.move(legacyPath, newPath, StandardCopyOption.REPLACE_EXISTING);
                LOG.log(Level.INFO, "已迁移旧会话文件：{0}", key);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "迁移会话失败：" + key, e);
            }
            
            Session session = loadFromPath(key, newPath);
            if (session != null) return session;
        }
        
        return null;
    }

    /**
     * 从指定路径加载会话
     */
    private Session loadFromPath(String key, Path path) {
        if (!Files.exists(path)) return null;

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> usageData = new HashMap<>();
        Map<String, Object> lastCallData = new HashMap<>();
        LocalDateTime createdAt = null;
        String sessionId = null;
        int lastConsolidated = 0;

        int lineNo = 0;
        int badLines = 0;

        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    Map<String, Object> data = objectMapper.readValue(
                            line,
                            new TypeReference<Map<String, Object>>() {}
                    );

                    if ("metadata".equals(data.get("_type"))) {
                        // 读取 sessionId
                        Object sid = data.get("session_id");
                        if (sid instanceof String s && !s.isBlank()) {
                            sessionId = s;
                        }

                        Object md = data.get("metadata");
                        if (md instanceof Map<?, ?> m) {
                            metadata = castMap(deepSanitize(m));
                        } else {
                            metadata = new HashMap<>();
                        }

                        Object ca = data.get("created_at");
                        if (ca instanceof String s && !s.isBlank()) {
                            try {
                                createdAt = LocalDateTime.parse(s);
                            } catch (Exception ignore) {
                                createdAt = null;
                            }
                        }

                        Object lc = data.get("last_consolidated");
                        if (lc instanceof Number n) {
                            lastConsolidated = n.intValue();
                        } else {
                            lastConsolidated = 0;
                        }

                        // 加载 usage 数据
                        Object usageObj = data.get("usage");
                        if (usageObj instanceof Map<?, ?> u) {
                            usageData = castMap(deepSanitize(u));
                        }

                        // 加载 last_call 数据
                        Object lastCallObj = data.get("last_call");
                        if (lastCallObj instanceof Map<?, ?> lastCallMap) {
                            lastCallData = castMap(deepSanitize(lastCallMap));
                        }

                    } else {
                        messages.add(castMap(deepSanitize(data)));
                    }

                } catch (Exception ex) {
                    badLines++;
                    LOG.log(Level.WARNING,
                            "会话文件存在坏行，已跳过。key=" + key + ", line=" + lineNo + ", reason=" + ex.getMessage());
                }
            }

            if (badLines > 0) {
                LOG.log(Level.WARNING, "加载会话完成，但发现坏行：key={0}, badLines={1}", new Object[]{key, badLines});
            }

            // 如果没有 sessionId，生成一个新的
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = Session.generateSessionId();
            }

            // 创建 Session 并设置 usage 数据
            Session session = new Session(
                    key,
                    sessionId,
                    messages,
                    (createdAt != null) ? createdAt : LocalDateTime.now(),
                    LocalDateTime.now(),
                    metadata,
                    lastConsolidated
            );

            // 恢复 usage 数据
            if (!usageData.isEmpty()) {
                session.setInputTokens(getIntValue(usageData, "input_tokens", 0));
                session.setOutputTokens(getIntValue(usageData, "output_tokens", 0));
                session.setCacheRead(getIntValue(usageData, "cache_read", 0));
                session.setCacheWrite(getIntValue(usageData, "cache_write", 0));
                session.setTotalTokens(getIntValue(usageData, "total_tokens", 0));
            }

            // 恢复 last_call 数据
            if (!lastCallData.isEmpty()) {
                session.setLastCallInput(getIntValue(lastCallData, "input_tokens", 0));
                session.setLastCallOutput(getIntValue(lastCallData, "output_tokens", 0));
                session.setLastCallCacheRead(getIntValue(lastCallData, "cache_read", 0));
                session.setLastCallCacheWrite(getIntValue(lastCallData, "cache_write", 0));
            }

            return session;

        } catch (Exception e) {
            LOG.log(Level.WARNING, "加载会话失败：" + key + "，原因：" + e.getMessage(), e);
            backupCorruptedFile(path);
            return null;
        }
    }

    /**
     * 从 Map 中安全获取 int 值
     */
    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        try {
            Object val = map.get(key);
            if (val instanceof Number n) {
                return n.intValue();
            }
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 加载 sessionKey -> sessionId 映射
     */
    private void loadKeyToIdMap() {
        Path mapFile = sessionsDir.resolve("sessions.json");
        if (!Files.exists(mapFile)) return;
        
        try (BufferedReader r = Files.newBufferedReader(mapFile, StandardCharsets.UTF_8)) {
            Map<String, String> map = objectMapper.readValue(r, new TypeReference<Map<String, String>>() {});
            if (map != null) {
                keyToIdMap.putAll(map);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "加载会话映射失败", e);
        }
    }

    /**
     * 保存 sessionKey -> sessionId 映射
     * 失败时不影响内存中的映射数据，只记录警告
     */
    private void saveKeyToIdMap() {
        Path mapFile = sessionsDir.resolve("sessions.json");
        Path tmpFile = mapFile.resolveSibling("sessions.json.tmp");

        // 确保目录存在（防止并发删除等情况）
        Helpers.ensureDir(sessionsDir);

        // 先写入临时文件
        try (BufferedWriter w = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            objectMapper.writeValue(w, new HashMap<>(keyToIdMap));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "写入会话映射临时文件失败", e);
            // 清理临时文件（如果存在）
            deleteIfExists(tmpFile);
            return;
        }

        // 尝试原子移动
        try {
            Files.move(tmpFile, mapFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // 原子移动失败，尝试普通移动
            try {
                Files.move(tmpFile, mapFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "移动会话映射文件失败", ex);
                // 清理临时文件
                deleteIfExists(tmpFile);
            }
        }
    }

    /**
     * 安全删除文件（如果存在）
     */
    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "清理临时文件失败: " + path, e);
        }
    }

    /**
     * 原子替换文件
     */
    private void atomicReplace(Path tmp, Path target) throws Exception {
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException e) {
            // 某些文件系统不支持 ATOMIC_MOVE，则退化为普通 replace
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    /**
     * 备份损坏文件，避免后续继续覆盖
     */
    private void backupCorruptedFile(Path path) {
        try {
            if (!Files.exists(path)) return;
            String name = path.getFileName().toString();
            Path backup = path.resolveSibling(name + ".corrupted." + System.currentTimeMillis() + ".bak");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            LOG.log(Level.WARNING, "已备份损坏会话文件：{0}", backup);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "备份损坏会话文件失败：" + path, ex);
        }
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return (idx > 0) ? filename.substring(0, idx) : filename;
    }

    // =========================
    // 数据清洗：递归处理 Map/List/String
    // =========================

    /**
     * 深度清洗：
     * - String：清洗非法 surrogate
     * - Map：递归清洗 key/value
     * - List：递归清洗元素
     * - 其它类型原样返回
     */
    @SuppressWarnings("unchecked")
    private Object deepSanitize(Object value) {
        if (value == null) return null;

        if (value instanceof String s) {
            return sanitizeString(s);
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = sanitizeString(String.valueOf(e.getKey()));
                Object v = deepSanitize(e.getValue());
                cleaned.put(k, v);
            }
            return cleaned;
        }

        if (value instanceof List<?> list) {
            List<Object> cleaned = new ArrayList<>(list.size());
            for (Object item : list) {
                cleaned.add(deepSanitize(item));
            }
            return cleaned;
        }

        return value;
    }

    /**
     * 清洗字符串中的非法 Unicode 代理项，避免 UTF-8 编码失败
     */
    private String sanitizeString(String input) {
        if (input == null || input.isEmpty()) return input;

        StringBuilder sb = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (Character.isHighSurrogate(ch)) {
                // 高代理项后面必须紧跟低代理项
                if (i + 1 < input.length() && Character.isLowSurrogate(input.charAt(i + 1))) {
                    sb.append(ch).append(input.charAt(i + 1));
                    i++; // 跳过已配对的低代理项
                } else {
                    sb.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(ch)) {
                // 单独出现的低代理项非法
                sb.append('\uFFFD');
            } else {
                sb.append(ch);
            }
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castListOfMap(Object obj) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                result.add(castMap(item));
            }
        }
        return result;
    }
    
    // =========================
    // 会话维护功能
    // =========================
    
    /** 维护配置 */
    private SessionMaintenanceConfig maintenanceConfig = SessionMaintenanceConfig.DEFAULT;
    
    /** 警告消费者 */
    private java.util.function.Consumer<String> warningConsumer;
    
    /**
     * 设置维护配置
     */
    public void setMaintenanceConfig(SessionMaintenanceConfig config) {
        this.maintenanceConfig = config != null ? config : SessionMaintenanceConfig.DEFAULT;
    }
    
    /**
     * 设置警告消费者
     */
    public void setWarningConsumer(java.util.function.Consumer<String> consumer) {
        this.warningConsumer = consumer;
    }
    
    /**
     * 执行会话维护
     * 
     * @param activeSessionKey 当前活跃的会话键（不会被清理）
     * @return 维护报告
     */
    public MaintenanceReport performMaintenance(String activeSessionKey) {
        if (maintenanceConfig == null) {
            return new MaintenanceReport(0, 0, 0, 0);
        }
        
        List<Map<String, Object>> sessions = listSessions();
        int beforeCount = sessions.size();
        int pruned = 0;
        int capped = 0;
        long freedBytes = 0;
        
        // 检查活跃会话是否会被清理
        if (activeSessionKey != null && !activeSessionKey.isBlank()) {
            MaintenanceWarning warning = checkActiveSessionWarning(
                    sessions, activeSessionKey, maintenanceConfig);
            
            if (warning != null) {
                // 发送警告
                SessionMaintenanceWarning.checkAndWarn(
                        activeSessionKey,
                        maintenanceConfig.getPruneAfterMs(),
                        maintenanceConfig.getMaxEntries(),
                        warning.wouldPrune,
                        warning.wouldCap,
                        warningConsumer
                );
                
                // 如果是 warn 模式，不执行清理
                if (maintenanceConfig.getMode() == SessionMaintenanceConfig.Mode.WARN) {
                    return new MaintenanceReport(beforeCount, beforeCount, 0, 0);
                }
            }
        }
        
        // enforce 模式：执行清理
        if (maintenanceConfig.getMode() == SessionMaintenanceConfig.Mode.ENFORCE) {
            // 修剪过期会话
            pruned = pruneStaleSessions(sessions, maintenanceConfig.getPruneAfterMs(), activeSessionKey);
            
            // 限制会话数量
            capped = capSessionCount(sessions, maintenanceConfig.getMaxEntries(), activeSessionKey);
            
            // 磁盘预算检查
            freedBytes = enforceDiskBudget(maintenanceConfig.getDiskBudgetBytes());
        }
        
        int afterCount = listSessions().size();
        return new MaintenanceReport(beforeCount, afterCount, pruned, capped);
    }
    
    /**
     * 检查活跃会话是否会被清理
     */
    private MaintenanceWarning checkActiveSessionWarning(
            List<Map<String, Object>> sessions,
            String activeSessionKey,
            SessionMaintenanceConfig config
    ) {
        boolean wouldPrune = false;
        boolean wouldCap = false;
        
        // 检查是否过期
        for (Map<String, Object> session : sessions) {
            String key = (String) session.get("key");
            if (activeSessionKey.equals(key)) {
                Object updatedAt = session.get("updated_at");
                if (updatedAt instanceof String ua) {
                    try {
                        LocalDateTime updated = LocalDateTime.parse(ua);
                        long ageMs = java.time.Duration.between(updated, LocalDateTime.now()).toMillis();
                        if (ageMs > config.getPruneAfterMs()) {
                            wouldPrune = true;
                        }
                    } catch (Exception ignored) {}
                }
                break;
            }
        }
        
        // 检查是否超出数量限制
        int activeIndex = -1;
        for (int i = 0; i < sessions.size(); i++) {
            String key = (String) sessions.get(i).get("key");
            if (activeSessionKey.equals(key)) {
                activeIndex = i;
                break;
            }
        }
        
        if (activeIndex >= 0 && activeIndex >= config.getMaxEntries()) {
            wouldCap = true;
        }
        
        if (wouldPrune || wouldCap) {
            return new MaintenanceWarning(activeSessionKey, wouldPrune, wouldCap);
        }
        
        return null;
    }
    
    /**
     * 修剪过期会话
     */
    private int pruneStaleSessions(
            List<Map<String, Object>> sessions,
            long pruneAfterMs,
            String activeSessionKey
    ) {
        int pruned = 0;
        
        for (Map<String, Object> session : sessions) {
            String key = (String) session.get("key");
            if (key == null || key.equals(activeSessionKey)) continue;
            
            Object updatedAt = session.get("updated_at");
            if (updatedAt instanceof String ua) {
                try {
                    LocalDateTime updated = LocalDateTime.parse(ua);
                    long ageMs = java.time.Duration.between(updated, LocalDateTime.now()).toMillis();
                    if (ageMs > pruneAfterMs) {
                        // 删除会话文件
                        String sessionId = (String) session.get("session_id");
                        if (sessionId != null) {
                            Path sessionPath = getSessionPath(sessionId);
                            if (Files.exists(sessionPath)) {
                                Files.delete(sessionPath);
                                cache.remove(key);
                                keyToIdMap.remove(key);
                                pruned++;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        
        if (pruned > 0) {
            saveKeyToIdMap();
        }
        
        return pruned;
    }
    
    /**
     * 限制会话数量
     */
    private int capSessionCount(
            List<Map<String, Object>> sessions,
            int maxEntries,
            String activeSessionKey
    ) {
        int capped = 0;
        
        if (sessions.size() <= maxEntries) {
            return 0;
        }
        
        // 从最旧的开始删除
        for (int i = maxEntries; i < sessions.size(); i++) {
            String key = (String) sessions.get(i).get("key");
            if (key == null || key.equals(activeSessionKey)) continue;
            
            try {
                String sessionId = (String) sessions.get(i).get("session_id");
                if (sessionId != null) {
                    Path sessionPath = getSessionPath(sessionId);
                    if (Files.exists(sessionPath)) {
                        Files.delete(sessionPath);
                        cache.remove(key);
                        keyToIdMap.remove(key);
                        capped++;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        if (capped > 0) {
            saveKeyToIdMap();
        }
        
        return capped;
    }
    
    /**
     * 执行磁盘预算
     */
    private long enforceDiskBudget(long maxBytes) {
        try {
            long totalSize = 0;
            List<Path> sessionFiles = new ArrayList<>();
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
                for (Path path : stream) {
                    totalSize += Files.size(path);
                    sessionFiles.add(path);
                }
            }
            
            if (totalSize <= maxBytes) {
                return 0;
            }
            
            // 按修改时间排序（最旧的在前）
            sessionFiles.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                } catch (Exception e) {
                    return 0;
                }
            });
            
            long freed = 0;
            for (Path path : sessionFiles) {
                if (totalSize - freed <= maxBytes) break;
                
                long size = Files.size(path);
                Files.delete(path);
                freed += size;
            }
            
            return freed;
            
        } catch (Exception e) {
            LOG.log(Level.WARNING, "磁盘预算检查失败", e);
            return 0;
        }
    }


    /**
     * 维护报告
     */
    public record MaintenanceReport(
            int beforeCount,
            int afterCount,
            int pruned,
            int capped
    ) {}
    
    /**
     * 维护警告
     */
    private record MaintenanceWarning(
            String activeSessionKey,
            boolean wouldPrune,
            boolean wouldCap
    ) {}
}