package session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 会话成本和使用统计
 * 
 * 对齐 OpenClaw 的 session-cost-usage.ts
 */
public class SessionCostUsage {

    private static final Logger log = LoggerFactory.getLogger(SessionCostUsage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 加载成本使用摘要
     */
    public static CostUsageSummary loadCostUsageSummary(Path sessionsDir, long startMs, long endMs) {
        MutableTotals totals = new MutableTotals();
        Map<String, MutableTotals> dailyMap = new LinkedHashMap<>();

        if (sessionsDir == null || !Files.exists(sessionsDir)) {
            return new CostUsageSummary(System.currentTimeMillis(), 0, List.of(), totals.toImmutable());
        }

        try {
            Files.list(sessionsDir)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() >= startMs;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(p -> scanUsageFile(p, startMs, endMs, totals, dailyMap));
        } catch (Exception e) {
            log.warn("加载会话使用统计失败: {}", e.getMessage());
        }

        List<DailyUsage> daily = dailyMap.entrySet().stream()
                .map(e -> new DailyUsage(e.getKey(), e.getValue().toImmutable()))
                .sorted(Comparator.comparing(DailyUsage::date))
                .toList();

        long days = Math.max(1, (endMs - startMs) / (24 * 60 * 60 * 1000) + 1);
        return new CostUsageSummary(System.currentTimeMillis(), (int) days, daily, totals.toImmutable());
    }

    /**
     * 加载会话成本摘要
     */
    public static SessionCostSummary loadSessionCostSummary(Path sessionFile) {
        if (sessionFile == null || !Files.exists(sessionFile)) {
            return null;
        }

        MutableTotals totals = new MutableTotals();
        Long firstActivity = null;
        Long lastActivity = null;
        Set<String> activityDates = new LinkedHashSet<>();
        Map<String, int[]> dailyTokensMap = new LinkedHashMap<>();
        MutableMessageCounts messageCounts = new MutableMessageCounts();
        Map<String, Integer> toolUsageMap = new LinkedHashMap<>();
        List<Long> latencyValues = new ArrayList<>();
        Long lastUserTimestamp = null;
        final long MAX_LATENCY_MS = 12 * 60 * 60 * 1000;

        try (BufferedReader r = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    Map<String, Object> parsed = MAPPER.readValue(line, 
                            new TypeReference<Map<String, Object>>() {});

                    if ("metadata".equals(parsed.get("_type"))) continue;

                    Object role = parsed.get("role");
                    Long timestamp = parseTimestamp(parsed);

                    if (timestamp != null) {
                        if (firstActivity == null || timestamp < firstActivity) firstActivity = timestamp;
                        if (lastActivity == null || timestamp > lastActivity) lastActivity = timestamp;
                    }

                    if ("user".equals(role)) {
                        messageCounts.user++;
                        messageCounts.total++;
                        lastUserTimestamp = timestamp;
                    } else if ("assistant".equals(role)) {
                        messageCounts.assistant++;
                        messageCounts.total++;
                        if (timestamp != null && lastUserTimestamp != null) {
                            long latencyMs = timestamp - lastUserTimestamp;
                            if (latencyMs > 0 && latencyMs <= MAX_LATENCY_MS) {
                                latencyValues.add(latencyMs);
                            }
                        }
                    }

                    Object toolCalls = parsed.get("tool_calls");
                    if (toolCalls instanceof List<?> list) {
                        messageCounts.toolCalls += list.size();
                        for (Object tc : list) {
                            if (tc instanceof Map<?, ?> map && map.get("function") instanceof Map<?, ?> fnMap) {
                                if (fnMap.get("name") instanceof String n) {
                                    toolUsageMap.merge(n, 1, Integer::sum);
                                }
                            }
                        }
                    }

                    Object usageObj = parsed.get("usage");
                    if (usageObj instanceof Map<?, ?> usageMap) {
                        int input = toInt(usageMap.get("input_tokens"), usageMap.get("input"));
                        int output = toInt(usageMap.get("output_tokens"), usageMap.get("output"));
                        int cacheRead = toInt(usageMap.get("cache_read_tokens"), usageMap.get("cacheRead"));
                        int cacheWrite = toInt(usageMap.get("cache_write_tokens"), usageMap.get("cacheWrite"));
                        
                        totals.input += input;
                        totals.output += output;
                        totals.cacheRead += cacheRead;
                        totals.cacheWrite += cacheWrite;
                        totals.totalTokens += input + output + cacheRead + cacheWrite;

                        if (timestamp != null) {
                            String dayKey = formatDayKey(timestamp);
                            activityDates.add(dayKey);
                            int[] daily = dailyTokensMap.getOrDefault(dayKey, new int[]{0});
                            daily[0] += input + output + cacheRead + cacheWrite;
                            dailyTokensMap.put(dayKey, daily);
                        }
                    }
                } catch (Exception e) { /* skip */ }
            }
        } catch (Exception e) {
            log.warn("加载会话成本摘要失败: {}", e.getMessage());
            return null;
        }

        List<DailyUsage> dailyBreakdown = dailyTokensMap.entrySet().stream()
                .map(e -> new DailyUsage(e.getKey(), 
                        new CostUsageTotals(e.getValue()[0], 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
                .sorted(Comparator.comparing(DailyUsage::date))
                .toList();

        LatencyStats latency = computeLatencyStats(latencyValues);

        ToolUsage toolUsage = null;
        if (!toolUsageMap.isEmpty()) {
            List<ToolCallCount> tools = toolUsageMap.entrySet().stream()
                    .map(e -> new ToolCallCount(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(ToolCallCount::count).reversed())
                    .toList();
            toolUsage = new ToolUsage(
                    toolUsageMap.values().stream().mapToInt(Integer::intValue).sum(),
                    toolUsageMap.size(), tools);
        }

        return new SessionCostSummary(
                sessionFile.getFileName().toString().replace(".jsonl", ""),
                sessionFile.toString(), firstActivity, lastActivity,
                firstActivity != null && lastActivity != null ? lastActivity - firstActivity : null,
                activityDates.stream().sorted().toList(), dailyBreakdown,
                messageCounts.toImmutable(), toolUsage, latency, totals.toImmutable());
    }

    private static void scanUsageFile(Path filePath, long startMs, long endMs,
            MutableTotals totals, Map<String, MutableTotals> dailyMap) {
        try (BufferedReader r = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {});
                    if ("metadata".equals(parsed.get("_type"))) continue;

                    Long timestamp = parseTimestamp(parsed);
                    if (timestamp == null || timestamp < startMs || timestamp > endMs) continue;

                    Object usageObj = parsed.get("usage");
                    if (usageObj instanceof Map<?, ?> usageMap) {
                        int input = toInt(usageMap.get("input_tokens"), usageMap.get("input"));
                        int output = toInt(usageMap.get("output_tokens"), usageMap.get("output"));
                        int cacheRead = toInt(usageMap.get("cache_read_tokens"), usageMap.get("cacheRead"));
                        int cacheWrite = toInt(usageMap.get("cache_write_tokens"), usageMap.get("cacheWrite"));
                        
                        totals.input += input;
                        totals.output += output;
                        totals.cacheRead += cacheRead;
                        totals.cacheWrite += cacheWrite;
                        totals.totalTokens += input + output + cacheRead + cacheWrite;

                        String dayKey = formatDayKey(timestamp);
                        MutableTotals daily = dailyMap.getOrDefault(dayKey, new MutableTotals());
                        daily.input += input;
                        daily.output += output;
                        daily.cacheRead += cacheRead;
                        daily.cacheWrite += cacheWrite;
                        daily.totalTokens += input + output + cacheRead + cacheWrite;
                        dailyMap.put(dayKey, daily);
                    }
                } catch (Exception e) { /* skip */ }
            }
        } catch (Exception e) {
            log.debug("扫描使用文件失败: {}", e.getMessage());
        }
    }

    private static int toInt(Object... values) {
        for (Object v : values) if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static Long parseTimestamp(Map<String, Object> entry) {
        Object raw = entry.get("timestamp");
        if (raw instanceof String s) {
            try {
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) { return null; }
        }
        if (raw instanceof Number n) return n.longValue();
        return null;
    }

    private static String formatDayKey(long timestamp) {
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static LatencyStats computeLatencyStats(List<Long> values) {
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = values.stream().sorted().toList();
        int count = sorted.size();
        long avg = sorted.stream().mapToLong(Long::longValue).sum() / count;
        int p95Index = (int) Math.max(0, Math.ceil(count * 0.95) - 1);
        return new LatencyStats(count, avg, sorted.get(p95Index), sorted.get(0), sorted.get(count - 1));
    }

    // ==================== 数据类 ====================

    public record CostUsageSummary(long updatedAt, int days, List<DailyUsage> daily, CostUsageTotals totals) {}
    public record CostUsageTotals(int input, int output, int cacheRead, int cacheWrite, int totalTokens,
            double totalCost, double inputCost, double outputCost, double cacheReadCost, double cacheWriteCost, int missingCostEntries) {}
    public record DailyUsage(String date, CostUsageTotals totals) {}
    public record SessionCostSummary(String sessionId, String sessionFile, Long firstActivity, Long lastActivity,
            Long durationMs, List<String> activityDates, List<DailyUsage> dailyBreakdown,
            MessageCounts messageCounts, ToolUsage toolUsage, LatencyStats latency, CostUsageTotals totals) {}
    public record MessageCounts(int total, int user, int assistant, int toolCalls, int toolResults, int errors) {}
    public record ToolUsage(int totalCalls, int uniqueTools, List<ToolCallCount> tools) {}
    public record ToolCallCount(String name, int count) {}
    public record LatencyStats(int count, long avgMs, long p95Ms, long minMs, long maxMs) {}

    private static class MutableTotals {
        int input, output, cacheRead, cacheWrite, totalTokens;
        double totalCost, inputCost, outputCost, cacheReadCost, cacheWriteCost;
        int missingCostEntries;
        CostUsageTotals toImmutable() {
            return new CostUsageTotals(input, output, cacheRead, cacheWrite, totalTokens,
                    totalCost, inputCost, outputCost, cacheReadCost, cacheWriteCost, missingCostEntries);
        }
    }

    private static class MutableMessageCounts {
        int total, user, assistant, toolCalls, toolResults, errors;
        MessageCounts toImmutable() { return new MessageCounts(total, user, assistant, toolCalls, toolResults, errors); }
    }
}