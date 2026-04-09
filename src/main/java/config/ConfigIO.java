package config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import convert.ConfigConvert;
import utils.GsonFactory;
import utils.Helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置加载与保存工具
 *
 * 功能对齐 Python 源码：
 * - get_config_path：默认 ~/.javaclawbot/config.json
 * - get_data_dir：调用 helpers.get_data_path（Java 侧对应 Helpers.getDataPath）
 * - load_config：
 *   - 如果配置文件存在：读取 JSON -> 迁移旧格式 -> 反序列化为 Config
 *   - 读取失败：打印警告并返回默认 Config()
 * - save_config：
 *   - 确保目录存在
 *   - 序列化时使用别名键（等价于 Python 的 model_dump(by_alias=True)）
 *
 * 说明：
 * - Python 的保存输出是 by_alias=True，通常意味着 JSON 使用 snake_case 键。
 * - Java 侧通过 Jackson 的 SNAKE_CASE 命名策略实现同等效果：
 *   - 读取：允许 snake_case JSON 映射到 Java 驼峰字段
 *   - 保存：将 Java 驼峰字段序列化为 snake_case JSON
 */
public final class ConfigIO {

    private ConfigIO() {}

    /**
     * 获取默认配置文件路径：~/.javaclawbot/config.json
     */
    public static Path getConfigPath() {
        return Paths.get(System.getProperty("user.home"), ".javaclawbot", "config.json");
    }

    /**
     * 获取默认配置文件路径：~/.javaclawbot/workspace
     */
    public static Path getDefaultWorkSpacePath() {
        return Paths.get(System.getProperty("user.home"), ".javaclawbot", "workspace");
    }
    /**
     * 获取指定的工作空间对应的配置文件路径
     */
    public static Path getConfigPath(Path workspacePath) {
        Path path = workspacePath.resolve("..").resolve("config.json").normalize();
        // 文件不存在使用默认的
        if (!Files.exists(path)) {
            return getConfigPath();
        }
        return path;
    }

    /**
     * 获取 javaclawbot 数据目录（对齐 Python 的 get_data_dir）
     *
     * Python 版本：
     * - from javaclawbot.utils.helpers import get_data_path
     * - return get_data_path()
     *
     * Java 版本：
     * - 对应 utils.Helpers.getDataPath()
     */
    public static Path getDataDir() {
        return Helpers.getDataPath();
    }

    /**
     * 加载配置（对齐 Python 的 load_config）
     *
     * @param configPath 可选；为空则使用默认路径
     * @return 配置对象；失败时返回默认配置
     */
    public static Config loadConfig(Path configPath) {
        Path path = (configPath != null) ? configPath : getConfigPath();

        if (Files.exists(path)) {
            try {
                lastConfigFingerprint = getConfigFingerprint(path);
                String json = Files.readString(path, StandardCharsets.UTF_8);
                Map<String, Object> data = parseJsonToMap(json);

                // 迁移旧配置结构
                data = migrateConfig(data);

                // 反序列化为配置对象
                ObjectMapper mapper = objectMapper();
                Config config = mapper.convertValue(data, Config.class);
                return ConfigConvert.A.updateConfig(config, new Config());
            } catch (Exception e) {
                // 对齐 Python：打印警告并回退默认配置
                System.out.println("警告: 从 " + path + " 加载配置失败: " + e.getMessage());
                System.out.println("使用默认配置。");
            }
        }

        // 对齐 Python：文件不存在或读取失败则返回默认配置
        return new Config();
    }

    /**
     * 上一次文件修改时间
     */
    private static volatile String lastConfigFingerprint;

    private static String getConfigFingerprint(Path path) {
        try {
            if (path == null) {
                path = getConfigPath();
            }

            if (!Files.exists(path)) {
                return "MISSING";
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算配置文件指纹失败: " + e.getMessage(), e);
        }
    }

    public static synchronized boolean isConfigChanged(Path workspace) {
        Path json = getConfigPath(workspace);

        try {
            String currentFingerprint = getConfigFingerprint(json);

            if (lastConfigFingerprint == null) {
                lastConfigFingerprint = currentFingerprint;
                return true; // 首次检查，视为有变化
            }

            if (!java.util.Objects.equals(lastConfigFingerprint, currentFingerprint)) {
                lastConfigFingerprint = currentFingerprint;
                return true;
            }

            return false;
        } catch (Exception e) {
            System.out.println("警告: 检测配置文件变更失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 保存配置（对齐 Python 的 save_config）
     *
     * @param config     配置对象
     * @param configPath 可选；为空则使用默认路径
     */
    public static void saveConfig(Config config, Path configPath) throws IOException {
        Path path = (configPath != null) ? configPath : getConfigPath();
        Files.createDirectories(path.getParent());

        // 对齐 Python：model_dump(by_alias=True) -> 使用 SNAKE_CASE 输出 key
        ObjectMapper mapper = objectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);

        Files.writeString(
                path,
                json,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /**
     * 配置迁移（对齐 Python 的 _migrate_config）
     *
     * Python 语义：
     * - tools = data.get("tools", {})
     * - exec_cfg = tools.get("exec", {})
     * - if "restrictToWorkspace" in exec_cfg and "restrictToWorkspace" not in tools:
     *     tools["restrictToWorkspace"] = exec_cfg.pop("restrictToWorkspace")
     * - return data
     *
     * 注意：
     * - 这里严格按 Python 的逻辑：只有当 data 内确实存在 tools（且为对象）时，才会迁移。
     * - 不会为了迁移而强行创建 tools/exec 结构（避免引入 Python 不存在的副作用）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> migrateConfig(Map<String, Object> data) {
        if (data == null) return null;

        Object toolsObj = data.get("tools");
        if (!(toolsObj instanceof Map<?, ?>)) {
            // Python: tools = {}（新对象，不写回 data），所以这里等价于“不做任何事”
            return data;
        }
        Map<String, Object> tools = (Map<String, Object>) toolsObj;

        Object execObj = tools.get("exec");
        if (!(execObj instanceof Map<?, ?>)) {
            // Python: exec_cfg = {}，条件不成立，不迁移
            return data;
        }
        Map<String, Object> execCfg = (Map<String, Object>) execObj;

        if (execCfg.containsKey("restrictToWorkspace") && !tools.containsKey("restrictToWorkspace")) {
            // tools["restrictToWorkspace"] = exec_cfg.pop("restrictToWorkspace")
            Object v = execCfg.remove("restrictToWorkspace");
            tools.put("restrictToWorkspace", v);
        }

        return data;
    }

    // -------------------------
    // JSON 与映射工具
    // -------------------------

    /**
     * Jackson ObjectMapper：
     * - 反序列化：允许 snake_case JSON 映射到 Java 驼峰字段
     * - 序列化：输出 snake_case key（对齐 Python by_alias=True 的常见行为）
     * - 忽略未知字段：提升向前兼容
     * - 不转义非 ASCII：对齐 Python json.dump(ensure_ascii=False)
     */
    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 忽略未知字段（对齐“容错读取”）
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // snake_case <-> camelCase（对齐 by_alias=True 常见输出）
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        // 仅输出非空字段
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 对齐 ensure_ascii=False：不要强制转义非 ASCII 字符
        mapper.configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);

        // 允许大小写不敏感（可选的容错能力）
        mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);

        return mapper;
    }

    /**
     * JSON 字符串解析为 Map
     */
    private static Map<String, Object> parseJsonToMap(String json) throws IOException {
        return GsonFactory.getGson().fromJson(json, Map.class);
    }
}