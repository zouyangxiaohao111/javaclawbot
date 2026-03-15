package memory;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * 时间衰减计算
 *
 * 对齐 OpenClaw 的 temporal-decay.ts
 *
 * 根据内容的年龄降低其相关性分数，使较新的内容优先显示
 */
public class MemoryTemporalDecay {

    /**
     * 时间衰减配置
     */
    public static class TemporalDecayConfig {
        /** 是否启用时间衰减，默认 false */
        public boolean enabled = false;
        /** 半衰期（天），默认 30 天 */
        public int halfLifeDays = 30;

        public TemporalDecayConfig() {}

        public TemporalDecayConfig(boolean enabled, int halfLifeDays) {
            this.enabled = enabled;
            this.halfLifeDays = Math.max(1, halfLifeDays);
        }
    }

    /** 默认配置 */
    public static final TemporalDecayConfig DEFAULT_CONFIG = new TemporalDecayConfig(false, 30);

    /** 一天的毫秒数 */
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 日期格式的记忆文件路径正则：memory/2024-01-15.md */
    private static final Pattern DATED_MEMORY_PATH_RE = Pattern.compile(
            "(?:^|/)memory/(\\d{4})-(\\d{2})-(\\d{2})\\.md$"
    );

    private MemoryTemporalDecay() {
        // 工具类，禁止实例化
    }

    /**
     * 计算衰减系数 λ
     *
     * 公式: λ = ln(2) / halfLifeDays
     */
    public static double toDecayLambda(int halfLifeDays) {
        if (halfLifeDays <= 0) {
            return 0;
        }
        return Math.log(2) / halfLifeDays;
    }

    /**
     * 计算时间衰减乘数
     *
     * 公式: e^(-λ * ageInDays)
     *
     * @param ageInDays    年龄（天）
     * @param halfLifeDays 半衰期（天）
     * @return 衰减乘数，范围 (0, 1]
     */
    public static double calculateTemporalDecayMultiplier(double ageInDays, int halfLifeDays) {
        double lambda = toDecayLambda(halfLifeDays);
        double clampedAge = Math.max(0, ageInDays);

        if (lambda <= 0 || !Double.isFinite(clampedAge)) {
            return 1.0;
        }

        return Math.exp(-lambda * clampedAge);
    }

    /**
     * 应用时间衰减到分数
     *
     * @param score        原始分数
     * @param ageInDays    年龄（天）
     * @param halfLifeDays 半衰期（天）
     * @return 衰减后的分数
     */
    public static double applyTemporalDecayToScore(double score, double ageInDays, int halfLifeDays) {
        return score * calculateTemporalDecayMultiplier(ageInDays, halfLifeDays);
    }

    /**
     * 从文件路径解析记忆日期
     *
     * 支持格式：memory/2024-01-15.md
     *
     * @param filePath 文件路径
     * @return 解析出的日期，如果无法解析则返回 null
     */
    public static LocalDateTime parseMemoryDateFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        String normalized = filePath.replace("\\", "/").replaceFirst("^\\./", "");
        Matcher matcher = DATED_MEMORY_PATH_RE.matcher(normalized);

        if (!matcher.find()) {
            return null;
        }

        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));

            return LocalDateTime.of(year, month, day, 0, 0);
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    /**
     * 检查是否为常青记忆路径
     *
     * 常青记忆不会衰减，如 MEMORY.md 或 memory/topics/*.md
     */
    public static boolean isEvergreenMemoryPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }

        String normalized = filePath.replace("\\", "/").replaceFirst("^\\./", "");

        // 根目录的 MEMORY.md 是常青的
        if (normalized.equals("MEMORY.md") || normalized.equals("memory.md")) {
            return true;
        }

        // 不在 memory/ 目录下的不是常青的
        if (!normalized.startsWith("memory/")) {
            return false;
        }

        // 日期格式的记忆文件会衰减
        return !DATED_MEMORY_PATH_RE.matcher(normalized).matches();
    }

    /**
     * 提取文件的时间戳
     *
     * 优先级：
     * 1. 从路径解析日期（如 memory/2024-01-15.md）
     * 2. 对于常青记忆，返回 null（不衰减）
     * 3. 使用文件修改时间
     *
     * @param filePath    文件路径
     * @param source      来源（memory 或 sessions）
     * @param workspaceDir 工作目录
     * @return 时间戳，如果无法确定或不应衰减则返回 null
     */
    public static LocalDateTime extractTimestamp(String filePath, String source, Path workspaceDir) {
        // 尝试从路径解析日期
        LocalDateTime fromPath = parseMemoryDateFromPath(filePath);
        if (fromPath != null) {
            return fromPath;
        }

        // 常青记忆不衰减
        if ("memory".equals(source) && isEvergreenMemoryPath(filePath)) {
            return null;
        }

        // 使用文件修改时间
        if (workspaceDir == null) {
            return null;
        }

        Path absolutePath = Paths.get(filePath).isAbsolute()
                ? Paths.get(filePath)
                : workspaceDir.resolve(filePath);

        try {
            FileTime mtime = Files.getLastModifiedTime(absolutePath);
            return LocalDateTime.ofInstant(mtime.toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 计算时间戳到现在的年龄（天）
     */
    public static double ageInDaysFromTimestamp(LocalDateTime timestamp, LocalDateTime now) {
        if (timestamp == null || now == null) {
            return 0;
        }

        Duration duration = Duration.between(timestamp, now);
        long ageMs = Math.max(0, duration.toMillis());
        return (double) ageMs / DAY_MS;
    }

    /**
     * 对混合搜索结果应用时间衰减
     *
     * @param results      搜索结果
     * @param config       时间衰减配置
     * @param workspaceDir 工作目录
     * @return 应用衰减后的结果
     */
    public static <T extends TemporalDecayItem> List<T> applyTemporalDecayToHybridResults(
            List<T> results,
            TemporalDecayConfig config,
            Path workspaceDir
    ) {
        if (config == null) {
            config = DEFAULT_CONFIG;
        }

        if (!config.enabled || results == null || results.isEmpty()) {
            return results == null ? Collections.emptyList() : new ArrayList<>(results);
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, LocalDateTime> timestampCache = new HashMap<>();

        List<T> decayedResults = new ArrayList<>();

        for (T entry : results) {
            String cacheKey = entry.getSource() + ":" + entry.getPath();

            LocalDateTime timestamp = timestampCache.get(cacheKey);
            if (timestamp == null) {
                timestamp = extractTimestamp(entry.getPath(), entry.getSource(), workspaceDir);
                timestampCache.put(cacheKey, timestamp);
            }

            if (timestamp == null) {
                // 无法确定时间，不衰减
                decayedResults.add(entry);
                continue;
            }

            double ageInDays = ageInDaysFromTimestamp(timestamp, now);
            double decayedScore = applyTemporalDecayToScore(entry.getScore(), ageInDays, config.halfLifeDays);

            // 创建新的结果项（保持原始数据，只更新分数）
            entry.setScore(decayedScore);
            decayedResults.add(entry);
        }

        return decayedResults;
    }

    /**
     * 时间衰减项目接口
     */
    public interface TemporalDecayItem {
        String getPath();
        String getSource();
        double getScore();
        void setScore(double score);
    }
}