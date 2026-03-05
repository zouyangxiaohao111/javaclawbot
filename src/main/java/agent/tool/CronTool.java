package agent.tool;



import corn.CronJob;
import corn.CronSchedule;
import corn.CronService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cron tool for scheduling reminders and tasks.
 *
 * Java port of nanobot/agent/tools/cron.py
 */
public class CronTool extends Tool {

    private final CronService cron;
    private String channel = "";
    private String chatId = "";

    public CronTool(CronService cronService) {
        this.cron = Objects.requireNonNull(cronService, "cronService");
    }

    /** Set the current session context for delivery. */
    public void setContext(String channel, String chatId) {
        this.channel = channel == null ? "" : channel;
        this.chatId = chatId == null ? "" : chatId;
    }

    @Override
    public String name() {
        return "cron";
    }

    @Override
    public String description() {
        return "Schedule reminders and recurring tasks. Actions: add, list, remove.";
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
                "description", "Reminder message (for add)"
        ));
        props.put("every_seconds", Map.of(
                "type", "integer",
                "description", "Interval in seconds (for recurring tasks)"
        ));
        props.put("cron_expr", Map.of(
                "type", "string",
                "description", "Cron expression like '0 9 * * *' (for scheduled tasks)"
        ));
        props.put("tz", Map.of(
                "type", "string",
                "description", "IANA timezone for cron expressions (e.g. 'America/Vancouver')"
        ));
        props.put("at", Map.of(
                "type", "string",
                "description", "ISO datetime for one-time execution (e.g. '2026-02-12T10:30:00')"
        ));
        props.put("job_id", Map.of(
                "type", "string",
                "description", "Job ID (for remove)"
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

        if ("add".equals(action)) {
            String message = str(args.get("message"));
            Integer everySeconds = asIntOrNull(args.get("every_seconds"));
            String cronExpr = strOrNull(args.get("cron_expr"));
            String tz = strOrNull(args.get("tz"));
            String at = strOrNull(args.get("at"));
            return CompletableFuture.completedFuture(addJob(message, everySeconds, cronExpr, tz, at));
        }

        if ("list".equals(action)) {
            return CompletableFuture.completedFuture(listJobs());
        }

        if ("remove".equals(action)) {
            String jobId = strOrNull(args.get("job_id"));
            return CompletableFuture.completedFuture(removeJob(jobId));
        }

        return CompletableFuture.completedFuture("Unknown action: " + action);
    }

    private String addJob(String message, Integer everySeconds, String cronExpr, String tz, String at) {
        if (message == null || message.isBlank()) {
            return "Error: message is required for add";
        }
        if (channel.isBlank() || chatId.isBlank()) {
            return "Error: no session context (channel/chat_id)";
        }
        if (tz != null && (cronExpr == null || cronExpr.isBlank())) {
            return "Error: tz can only be used with cron_expr";
        }
        if (tz != null) {
            try {
                ZoneId.of(tz);
            } catch (Exception e) {
                return "Error: unknown timezone '" + tz + "'";
            }
        }

        boolean deleteAfter = false;
        CronSchedule schedule;

        if (everySeconds != null) {
            schedule = new CronSchedule(CronSchedule.Kind.every);
            schedule.setEveryMs(Long.valueOf(Math.multiplyExact(everySeconds, 1000)));
        } else if (cronExpr != null && !cronExpr.isBlank()) {
            schedule = new CronSchedule(CronSchedule.Kind.cron);
            schedule.setExpr(cronExpr);
            schedule.setTz(tz);
        } else if (at != null && !at.isBlank()) {
            long atMs;
            try {
                // Python datetime.fromisoformat(at) is "naive" (no tz) -> interpret in system default zone
                LocalDateTime ldt = LocalDateTime.parse(at);
                ZonedDateTime zdt = ldt.atZone(ZoneId.systemDefault());
                atMs = zdt.toInstant().toEpochMilli();
            } catch (Exception e) {
                return "Error: invalid ISO datetime for at: " + at;
            }
            schedule = new CronSchedule(CronSchedule.Kind.at);
            schedule.setAtMs(atMs);
            deleteAfter = true;
        } else {
            return "Error: either every_seconds, cron_expr, or at is required";
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

        return "Created job '" + job.getName() + "' (id: " + job.getId() + ")";
    }

    private String listJobs() {
        List<CronJob> jobs = cron.listJobs(false);
        if (jobs == null || jobs.isEmpty()) {
            return "No scheduled jobs.";
        }
        List<String> lines = new ArrayList<>();
        for (CronJob j : jobs) {
            lines.add("- " + j.getName() + " (id: " + j.getId() + ", " + j.getSchedule().getKind() + ")");
        }
        return "Scheduled jobs:\n" + String.join("\n", lines);
    }

    private String removeJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return "Error: job_id is required for remove";
        }
        if (cron.removeJob(jobId)) {
            return "Removed job " + jobId;
        }
        return "Job " + jobId + " not found";
    }

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