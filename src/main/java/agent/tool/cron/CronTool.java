package agent.tool.cron;

import agent.tool.Tool;
import corn.CronJob;
import corn.CronSchedule;
import corn.CronService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cron tool for scheduling reminders and recurring tasks.
 *
 * 说明：
 * 1. in_seconds：推荐用于"一次性延时任务"，例如 10 秒后提醒
 * 2. every_seconds：用于"循环任务"，例如每 60 秒执行一次
 * 3. cron_expr：用于"固定时刻周期任务"，例如每天 7:30 执行
 * 4. at：用于"绝对时间的一次性任务"，例如 2026-03-06T18:00:00
 */
@Slf4j
public class CronTool extends Tool {

    private final CronService cron;
    private String channel = "";
    private String chatId = "";

    private final ThreadLocal<Boolean> inCronContext = ThreadLocal.withInitial(() -> false);

    public CronTool(CronService cronService) {
        this.cron = Objects.requireNonNull(cronService, "cronService");
        log.info("初始化 CronTool");
    }

    /** 设置当前会话上下文，用于任务执行后消息投递 */
    public void setContext(String channel, String chatId) {
        this.channel = channel == null ? "" : channel;
        this.chatId = chatId == null ? "" : chatId;
        log.debug("设置 CronTool 上下文: channel={}, chatId={}", this.channel, this.chatId);
    }

    /** 标记是否在 cron 作业回调中执行 */
    public void setCronContext(boolean active) {
        inCronContext.set(active);
    }

    /** 检查是否在 cron 作业回调中执行 */
    public boolean isInCronContext() {
        return inCronContext.get();
    }

    @Override
    public String name() {
        return "cron";
    }

    @Override
    public String description() {
        return "Schedule reminders and recurring tasks. For one-time short delays, prefer in_seconds. For recurring intervals, use every_seconds. For fixed schedules, use cron_expr with optional tz. Actions: add, list, remove.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("action", Map.of(
                "type", "string",
                "enum", List.of("add", "list", "remove"),
                "description", "Action to perform"
        ));

        props.put("message", Map.of(
                "type", "string",
                "description", "Reminder/task message for add"
        ));

        props.put("in_seconds", Map.of(
                "type", "integer",
                "description", "Run once after N seconds from now. Recommended for one-time short delays, e.g. 10"
        ));

        props.put("every_seconds", Map.of(
                "type", "integer",
                "description", "Run repeatedly every N seconds"
        ));

        props.put("cron_expr", Map.of(
                "type", "string",
                "description", "Cron expression, supports 5-field ('30 7 * * *') or 6-field with seconds ('0 30 7 * * *')"
        ));

        props.put("tz", Map.of(
                "type", "string",
                "description", "IANA timezone for cron expressions, e.g. 'Asia/Shanghai'"
        ));

        props.put("at", Map.of(
                "type", "string",
                "description", "Absolute ISO datetime for one-time execution, e.g. '2026-02-12T10:30:00'. Prefer in_seconds for short delays."
        ));

        props.put("job_id", Map.of(
                "type", "string",
                "description", "Job ID for remove"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("action"));
        return schema;
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String action = str(args.get("action"));
        log.info("执行工具: cron, 动作: {}", action);

        if ("add".equals(action)) {
            String message = str(args.get("message"));
            Integer inSeconds = asIntOrNull(args.get("in_seconds"));
            Integer everySeconds = asIntOrNull(args.get("every_seconds"));
            String cronExpr = strOrNull(args.get("cron_expr"));
            String tz = strOrNull(args.get("tz"));
            String at = strOrNull(args.get("at"));

            return CompletableFuture.completedFuture(
                    addJob(message, inSeconds, everySeconds, cronExpr, tz, at)
            );
        }

        if ("list".equals(action)) {
            return CompletableFuture.completedFuture(listJobs());
        }

        if ("remove".equals(action)) {
            String jobId = strOrNull(args.get("job_id"));
            return CompletableFuture.completedFuture(removeJob(jobId));
        }

        log.warn("未知的 cron 动作: {}", action);
        return CompletableFuture.completedFuture("Unknown action: " + action);
    }

    /**
     * 添加任务：
     * - in_seconds：一次性延时任务（推荐）
     * - every_seconds：循环任务
     * - cron_expr：cron任务
     * - at：绝对时间一次性任务
     */
    private String addJob(String message,
                          Integer inSeconds,
                          Integer everySeconds,
                          String cronExpr,
                          String tz,
                          String at) {

        // 防止 cron 作业内部递归调度新作业
        if (inCronContext.get()) {
            log.warn("拒绝在 cron 作业内部创建新任务");
            return "Error: cannot schedule new jobs from within a cron job execution";
        }

        if (message == null || message.isBlank()) {
            log.warn("添加任务失败: 消息为空");
            return "Error: message is required for add";
        }

        if (channel.isBlank() || chatId.isBlank()) {
            log.warn("添加任务失败: 缺少会话上下文");
            return "Error: no session context (channel/chat_id)";
        }

        if (tz != null && (cronExpr == null || cronExpr.isBlank())) {
            log.warn("添加任务失败: tz 只能与 cron_expr 一起使用");
            return "Error: tz can only be used with cron_expr";
        }

        if (tz != null) {
            try {
                ZoneId.of(tz);
            } catch (Exception e) {
                log.warn("添加任务失败: 未知时区 '{}'", tz);
                return "Error: unknown timezone '" + tz + "'";
            }
        }

        // 限制只能指定一种时间方式，避免歧义
        int modeCount = 0;
        if (inSeconds != null) modeCount++;
        if (everySeconds != null) modeCount++;
        if (cronExpr != null && !cronExpr.isBlank()) modeCount++;
        if (at != null && !at.isBlank()) modeCount++;

        if (modeCount == 0) {
            log.warn("添加任务失败: 未指定时间模式");
            return "Error: one of in_seconds, every_seconds, cron_expr, or at is required";
        }
        if (modeCount > 1) {
            log.warn("添加任务失败: 指定了多个时间模式");
            return "Error: only one of in_seconds, every_seconds, cron_expr, or at may be provided";
        }

        boolean deleteAfter = false;
        CronSchedule schedule;

        try {
            if (inSeconds != null) {
                if (inSeconds <= 0) {
                    log.warn("添加任务失败: in_seconds 必须大于 0");
                    return "Error: in_seconds must be > 0";
                }

                long atMs = System.currentTimeMillis() + Math.multiplyExact(inSeconds.longValue(), 1000L);

                schedule = new CronSchedule(CronSchedule.Kind.at);
                schedule.setAtMs(atMs);
                deleteAfter = true;

            } else if (everySeconds != null) {
                if (everySeconds <= 0) {
                    log.warn("添加任务失败: every_seconds 必须大于 0");
                    return "Error: every_seconds must be > 0";
                }

                schedule = new CronSchedule(CronSchedule.Kind.every);
                schedule.setEveryMs(Math.multiplyExact(everySeconds.longValue(), 1000L));

            } else if (cronExpr != null && !cronExpr.isBlank()) {
                schedule = new CronSchedule(CronSchedule.Kind.cron);
                schedule.setExpr(cronExpr);
                schedule.setTz(tz);

            } else {
                long atMs;
                try {
                    // 绝对时间 at：如果不带时区，则按系统默认时区解释
                    LocalDateTime ldt = LocalDateTime.parse(at);
                    ZonedDateTime zdt = ldt.atZone(ZoneId.systemDefault());
                    atMs = zdt.toInstant().toEpochMilli();
                } catch (Exception e) {
                    log.warn("添加任务失败: 无效的 ISO 日期时间 '{}'", at);
                    return "Error: invalid ISO datetime for at: " + at;
                }

                if (atMs <= System.currentTimeMillis()) {
                    log.warn("添加任务失败: at 必须是未来时间");
                    return "Error: at must be in the future";
                }

                schedule = new CronSchedule(CronSchedule.Kind.at);
                schedule.setAtMs(atMs);
                deleteAfter = true;
            }

            CronJob job = cron.addJob(
                    safeJobName(message),
                    schedule,
                    message,
                    true,
                    channel,
                    chatId,
                    deleteAfter
            );

            log.info("成功创建定时任务: '{}', id: {}", job.getName(), job.getId());
            return "Created job '" + job.getName() + "' (id: " + job.getId() + ")";
        } catch (Exception e) {
            log.error("创建定时任务失败: {}", e.getMessage(), e);
            return "Error: failed to create cron job: " + e.getMessage();
        }
    }

    private String listJobs() {
        List<CronJob> jobs = cron.listJobs(false);
        if (jobs == null || jobs.isEmpty()) {
            log.debug("当前没有定时任务");
            return "No scheduled jobs.";
        }

        List<String> lines = new ArrayList<>();
        for (CronJob j : jobs) {
            Long nextRun = j.getState() != null ? j.getState().getNextRunAtMs() : null;
            lines.add("- " + j.getName()
                    + " (id: " + j.getId()
                    + ", kind: " + (j.getSchedule() != null ? j.getSchedule().getKind() : "unknown")
                    + ", nextRunAtMs: " + nextRun + ")");
        }
        log.debug("列出 {} 个定时任务", jobs.size());
        return "Scheduled jobs:\n" + String.join("\n", lines);
    }

    private String removeJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            log.warn("删除任务失败: job_id 为空");
            return "Error: job_id is required for remove";
        }
        if (cron.removeJob(jobId)) {
            log.info("成功删除定时任务: {}", jobId);
            return "Removed job " + jobId;
        }
        log.warn("删除任务失败: 任务不存在 '{}'", jobId);
        return "Job " + jobId + " not found";
    }

    /** 任务名截断，避免过长 */
    private String safeJobName(String message) {
        String s = message.trim();
        return s.length() <= 30 ? s : s.substring(0, 30);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static Integer asIntOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }
}
