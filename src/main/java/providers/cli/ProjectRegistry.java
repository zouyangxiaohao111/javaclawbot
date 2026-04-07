package providers.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.GsonFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目注册表 - 管理项目名称到路径的映射
 *
 * 支持命令:
 * - /bind p1=/path/to/project
 * - /unbind p1
 * - /projects
 */
public class ProjectRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final Map<String, String> projects = new ConcurrentHashMap<>();
    private final Path storagePath;
    private volatile boolean loaded = false;

    public ProjectRegistry(Path storagePath) {
        this.storagePath = storagePath;
    }

    /**
     * 绑定项目
     *
     * @param name 项目名称 (如 p1, web)
     * @param path 项目路径
     * @return true 如果成功
     */
    public boolean bind(String name, String path) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (path == null || path.isBlank()) {
            return false;
        }

        // 规范化名称 (去除空格，转小写)
        String normalizedName = name.trim().toLowerCase();

        // 规范化路径
        String normalizedPath = Path.of(path.trim()).normalize().toString();

        // 验证路径是否存在
        if (!Files.exists(Path.of(normalizedPath))) {
            log.warn("Project path does not exist: {}", normalizedPath);
            // 仍然允许绑定，因为路径可能稍后创建
        }

        projects.put(normalizedName, normalizedPath);
        save();

        log.info("Project bound: {} -> {}", normalizedName, normalizedPath);
        return true;
    }

    /**
     * 解绑项目
     *
     * @param name 项目名称
     * @return true 如果成功
     */
    public boolean unbind(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        String normalizedName = name.trim().toLowerCase();
        String removed = projects.remove(normalizedName);

        if (removed != null) {
            save();
            log.info("Project unbound: {}", normalizedName);
            return true;
        }

        return false;
    }

    /**
     * 获取项目路径
     *
     * @param name 项目名称
     * @return 项目路径，如果不存在返回 null
     */
    public String getPath(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return projects.get(name.trim().toLowerCase());
    }

    /**
     * 获取项目路径 (别名方法)
     */
    public String resolve(String name) {
        return getPath(name);
    }

    /**
     * 检查项目是否存在
     */
    public boolean exists(String name) {
        return getPath(name) != null;
    }

    /**
     * 列出所有项目
     *
     * @return 项目映射 (名称 -> 路径)
     */
    public Map<String, String> listAll() {
        return Collections.unmodifiableMap(new TreeMap<>(projects));
    }

    /**
     * 获取项目数量
     */
    public int size() {
        return projects.size();
    }

    /**
     * 持久化到文件
     */
    public synchronized void save() {
        if (storagePath == null) {
            return;
        }

        try {
            // 确保目录存在
            Path parent = storagePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // 构建存储格式
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("projects", new TreeMap<>(projects));
            data.put("updatedAt", LocalDateTime.now().toString());

            // 写入临时文件
            Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            String json = GsonFactory.getGson().toJson(data);
            Files.writeString(tempPath, json);

            // 原子替换
            Files.move(tempPath, storagePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            log.debug("Project registry saved to {}", storagePath);
        } catch (IOException e) {
            log.error("Failed to save project registry", e);
        }
    }

    /**
     * 从文件加载
     */
    @SuppressWarnings("unchecked")
    public synchronized void load() {
        if (loaded || storagePath == null || !Files.exists(storagePath)) {
            loaded = true;
            return;
        }

        try {
            String json = Files.readString(storagePath);
            Map<String, Object> data = GsonFactory.getGson().fromJson(json, Map.class);

            if (data != null && data.containsKey("projects")) {
                Map<String, String> loadedProjects = (Map<String, String>) data.get("projects");
                projects.clear();
                projects.putAll(loadedProjects);
                log.info("Loaded {} projects from {}", projects.size(), storagePath);
            }

            loaded = true;
        } catch (Exception e) {
            log.error("Failed to load project registry", e);
        }
    }

    /**
     * 重新加载
     */
    public void reload() {
        loaded = false;
        load();
    }

    /**
     * 清空所有项目
     */
    public void clear() {
        projects.clear();
        save();
    }

    /**
     * 格式化项目列表为字符串
     */
    public String formatList() {
        if (projects.isEmpty()) {
            return "📁 暂无绑定项目\n\n使用 /bind <名称>=<路径> 绑定项目\n示例: /bind p1=/home/user/project";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📁 已绑定项目 (").append(projects.size()).append("):\n");

        projects.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append("  • ").append(entry.getKey()).append(" → ").append(entry.getValue());
                    // 检查路径是否存在
                    if (!Files.exists(Path.of(entry.getValue()))) {
                        sb.append(" ⚠️ (路径不存在)");
                    }
                    sb.append("\n");
                });

        sb.append("\n命令:\n");
        sb.append("  /cc <项目> <提示词> - 使用 Claude Code\n");
        sb.append("  /opencode <项目> <提示词> - 使用 OpenCode");

        return sb.toString();
    }
}
