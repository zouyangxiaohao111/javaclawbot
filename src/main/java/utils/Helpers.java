package utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * javaclawbot 工具方法
 *
 * 功能：
 * - 确保目录存在
 * - 获取数据目录与工作区目录
 * - 获取会话目录、技能目录
 * - 生成时间戳
 * - 截断字符串
 * - 将字符串转换为安全文件名
 * - 解析会话键（channel:chat_id）
 */
public final class Helpers {

    private Helpers() {}

    /**
     * 确保目录存在（不存在则递归创建）
     */
    public static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException("创建目录失败：" + path, e);
        }
        return path;
    }

    /**
     * 获取数据目录：~/.javaclawbot
     */
    public static Path getDataPath() {
        return ensureDir(Path.of(System.getProperty("user.home"), ".javaclawbot"));
    }

    /**
     * 获取工作区目录
     *
     * @param workspace 可选；为空则使用 ~/.javaclawbot/workspace
     */
    public static Path getWorkspacePath(String workspace) {
        Path path;
        if (workspace != null && !workspace.isBlank()) {
            path = expandUserHome(workspace);
        } else {
            path = Path.of(System.getProperty("user.home"), ".javaclawbot", "workspace");
        }
        return ensureDir(path);
    }

    /**
     * 获取会话存储目录：~/.javaclawbot/sessions
     */
    public static Path getSessionsPath() {
        return ensureDir(getDataPath().resolve("sessions"));
    }

    /**
     * 获取技能目录：{workspace}/skills
     *
     * @param workspace 可选；为空则使用默认工作区
     */
    public static Path getSkillsPath(Path workspace) {
        Path ws = (workspace != null) ? workspace : getWorkspacePath(null);
        return ensureDir(ws.resolve("skills"));
    }

    /**
     * 当前时间戳（ISO 格式）
     */
    public static String timestamp() {
        return LocalDateTime.now().toString();
    }

    /**
     * 截断字符串（超长则追加后缀）
     */
    public static String truncateString(String s, int maxLen, String suffix) {
        if (s == null) return "";
        if (suffix == null) suffix = "...";
        if (maxLen < 0) maxLen = 0;

        if (s.length() <= maxLen) return s;

        int keep = Math.max(0, maxLen - suffix.length());
        return s.substring(0, keep) + suffix;
    }

    /**
     * 转换为安全文件名（一比一实现）
     *
     * 规则：
     * - 将 <>:"/\\|?* 这些字符替换为下划线 _
     * - 再做 trim（去掉首尾空白）
     */
    public static String safeFilename(String name) {
        if (name == null) return "";
        String unsafe = "<>:\"/\\|?*";
        String out = name;
        for (int i = 0; i < unsafe.length(); i++) {
            char c = unsafe.charAt(i);
            out = out.replace(c, '_');
        }
        return out.trim();
    }

    /**
     * 解析会话键：channel:chat_id
     */
    public static String[] parseSessionKey(String key) {
        if (key == null) throw new IllegalArgumentException("会话键为空");
        String[] parts = key.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效会话键：" + key);
        }
        return parts;
    }

    /**
     * 将以 ~ 开头的路径展开为用户目录
     */
    private static Path expandUserHome(String path) {
        String p = path.trim();
        if (p.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(p.substring(2));
        }
        return Path.of(p);
    }


    public static String safeTruncate(String s, int maxCodePoints) {
        if (s == null || s.isEmpty()) return s;
        int total = s.codePointCount(0, s.length());
        if (total <= maxCodePoints) return s;

        int end = s.offsetByCodePoints(0, maxCodePoints);
        return s.substring(0, end);
    }
}