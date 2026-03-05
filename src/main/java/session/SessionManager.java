package session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import utils.Helpers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 会话管理器
 *
 * 功能：
 * - 会话以 JSONL 保存：第一行 metadata，其余行是消息
 * - 优先从当前工作区 sessions 目录读取
 * - 如果当前不存在但旧目录 ~/.nanobot/sessions 存在，则迁移到当前目录
 * - 提供缓存、保存、失效、列表功能
 */
public final class SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManager.class.getName());

    private final Path workspace;
    private final Path sessionsDir;
    private final Path legacySessionsDir;

    private final Map<String, Session> cache = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionManager(Path workspace) {
        this.workspace = workspace;
        this.sessionsDir = Helpers.ensureDir(workspace.resolve("sessions"));
        this.legacySessionsDir = Path.of(System.getProperty("user.home"), ".nanobot", "sessions");
    }

    /**
     * 获取或创建会话
     */
    public Session getOrCreate(String key) {
        Session s = cache.get(key);
        if (s != null) return s;

        Session loaded = load(key);
        if (loaded == null) loaded = new Session(key);

        cache.put(key, loaded);
        return loaded;
    }

    /**
     * 保存会话（覆盖写入）
     */
    public void save(Session session) {
        Path path = getSessionPath(session.getKey());

        try (BufferedWriter w = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {

            Map<String, Object> metaLine = new LinkedHashMap<>();
            metaLine.put("_type", "metadata");
            metaLine.put("key", session.getKey());
            metaLine.put("created_at", session.getCreatedAt().toString());
            metaLine.put("updated_at", session.getUpdatedAt().toString());
            metaLine.put("metadata", session.getMetadata());
            metaLine.put("last_consolidated", session.getLastConsolidated());

            w.write(objectMapper.writeValueAsString(metaLine));
            w.write("\n");

            for (Map<String, Object> msg : session.getMessages()) {
                w.write(objectMapper.writeValueAsString(msg));
                w.write("\n");
            }

            cache.put(session.getKey(), session);

        } catch (Exception e) {
            LOG.log(Level.WARNING, "保存会话失败：" + session.getKey() + "，原因：" + e.getMessage(), e);
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

                    Map<String, Object> data = objectMapper.readValue(firstLine, new TypeReference<Map<String, Object>>() {});
                    if (!"metadata".equals(data.get("_type"))) continue;

                    String key = (String) data.get("key");
                    if (key == null || key.isBlank()) {
                        key = stripExtension(path.getFileName().toString()).replaceFirst("_", ":");
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("created_at", data.get("created_at"));
                    item.put("updated_at", data.get("updated_at"));
                    item.put("path", path.toString());
                    sessions.add(item);

                } catch (Exception ignore) {
                    // 与 Python 一致：错误就跳过该文件
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

    // ----------------- 内部方法 -----------------

    private Path getSessionPath(String key) {
        String safeKey = Helpers.safeFilename(key.replace(":", "_"));
        return sessionsDir.resolve(safeKey + ".jsonl");
    }

    private Path getLegacySessionPath(String key) {
        String safeKey = Helpers.safeFilename(key.replace(":", "_"));
        return legacySessionsDir.resolve(safeKey + ".jsonl");
    }

    private Session load(String key) {
        Path path = getSessionPath(key);

        // 如果新目录不存在，但旧目录存在，则迁移
        if (!Files.exists(path)) {
            Path legacyPath = getLegacySessionPath(key);
            if (Files.exists(legacyPath)) {
                try {
                    Helpers.ensureDir(path.getParent());
                    Files.move(legacyPath, path, StandardCopyOption.REPLACE_EXISTING);
                    LOG.log(Level.INFO, "已迁移旧会话文件：{0}", key);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "迁移会话失败：" + key, e);
                }
            }
        }

        if (!Files.exists(path)) return null;

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        LocalDateTime createdAt = null;
        int lastConsolidated = 0;

        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Map<String, Object> data = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                if ("metadata".equals(data.get("_type"))) {
                    Object md = data.get("metadata");
                    if (md instanceof Map<?, ?> m) {
                        metadata = (Map<String, Object>) m;
                    } else {
                        metadata = new HashMap<>();
                    }

                    Object ca = data.get("created_at");
                    if (ca instanceof String s && !s.isBlank()) {
                        createdAt = LocalDateTime.parse(s);
                    }

                    Object lc = data.get("last_consolidated");
                    if (lc instanceof Number n) {
                        lastConsolidated = n.intValue();
                    } else {
                        lastConsolidated = 0;
                    }

                } else {
                    messages.add(data);
                }
            }

            return new Session(
                    key,
                    messages,
                    (createdAt != null) ? createdAt : LocalDateTime.now(),
                    LocalDateTime.now(),
                    metadata,
                    lastConsolidated
            );

        } catch (Exception e) {
            LOG.log(Level.WARNING, "加载会话失败：" + key + "，原因：" + e.getMessage(), e);
            return null;
        }
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return (idx > 0) ? filename.substring(0, idx) : filename;
    }
}