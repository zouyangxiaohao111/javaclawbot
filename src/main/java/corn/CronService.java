package corn;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;

public class CronService {

    /** onJob 回调：执行任务并返回响应文本（可为 null） */
    @FunctionalInterface
    public interface CronJobHandler {
        CompletionStage<String> handle(CronJob job);
    }

    private final Path storePath;
    private CronJobHandler onJob;

    private final ObjectMapper mapper;

    private volatile CronStore store; // lazy load
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nanobot-cron");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> timerFuture;
    private volatile boolean running = false;

    // cron-utils parser (5-field UNIX cron: min hour dom mon dow)
    private final CronParser cronParser =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    public CronService(Path storePath, CronJobHandler onJob) {
        this.storePath = Objects.requireNonNull(storePath, "storePath");
        this.onJob = onJob;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void setOnJob(CronJobHandler onJob) {
        this.onJob = onJob;
    }

    // ---------- helpers ----------

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static void validateScheduleForAdd(CronSchedule schedule) {
        if (schedule.getTz() != null && schedule.getKind() != CronSchedule.Kind.cron) {
            throw new IllegalArgumentException("tz can only be used with cron schedules");
        }
        if (schedule.getKind() == CronSchedule.Kind.cron && schedule.getTz() != null) {
            try {
                ZoneId.of(schedule.getTz());
            } catch (Exception e) {
                throw new IllegalArgumentException("unknown timezone '" + schedule.getTz() + "'");
            }
        }
    }

    private Long computeNextRun(CronSchedule schedule, long nowMs) {
        if (schedule == null || schedule.getKind() == null) return null;

        if (schedule.getKind() == CronSchedule.Kind.at) {
            Long at = schedule.getAtMs();
            return (at != null && at > nowMs) ? at : null;
        }

        if (schedule.getKind() == CronSchedule.Kind.every) {
            Long every = schedule.getEveryMs();
            if (every == null || every <= 0) return null;
            return nowMs + every;
        }

        if (schedule.getKind() == CronSchedule.Kind.cron) {
            String expr = schedule.getExpr();
            if (expr == null || expr.isBlank()) return null;
            try {
                ZoneId zone = (schedule.getTz() != null && !schedule.getTz().isBlank())
                        ? ZoneId.of(schedule.getTz())
                        : ZoneId.systemDefault();

                ZonedDateTime base = Instant.ofEpochMilli(nowMs).atZone(zone);

                Cron cron = cronParser.parse(expr);
                ExecutionTime et = ExecutionTime.forCron(cron);

                Optional<ZonedDateTime> next = et.nextExecution(base);
                return next.map(z -> z.toInstant().toEpochMilli()).orElse(null);
            } catch (Exception e) {
                return null; // 对齐 python：异常则 None
            }
        }

        return null;
    }

    // ---------- store load/save ----------

    private synchronized CronStore loadStore() {
        if (store != null) return store;

        if (Files.exists(storePath)) {
            try {
                store = mapper.readValue(storePath.toFile(), CronStore.class);
            } catch (Exception e) {
                store = new CronStore();
            }
        } else {
            store = new CronStore();
        }
        return store;
    }

    private synchronized void saveStore() {
        if (store == null) return;
        try {
            Files.createDirectories(storePath.getParent());
            mapper.writeValue(storePath.toFile(), store);
        } catch (IOException ignored) {
        }
    }

    // ---------- lifecycle ----------

    public CompletableFuture<Void> start() {
        running = true;
        loadStore();
        recomputeNextRuns();
        saveStore();
        armTimer();
        return CompletableFuture.completedFuture(null);
    }

    public void stop() {
        running = false;
        ScheduledFuture<?> f = timerFuture;
        if (f != null) f.cancel(true);
        timerFuture = null;
        scheduler.shutdownNow();
    }

    private void recomputeNextRuns() {
        CronStore s = loadStore();
        long now = nowMs();
        for (CronJob job : s.getJobs()) {
            if (job.isEnabled()) {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), now));
            }
        }
    }

    private Long getNextWakeMs() {
        CronStore s = loadStore();
        Long best = null;
        for (CronJob j : s.getJobs()) {
            if (!j.isEnabled()) continue;
            Long t = j.getState().getNextRunAtMs();
            if (t == null) continue;
            if (best == null || t < best) best = t;
        }
        return best;
    }

    private synchronized void armTimer() {
        ScheduledFuture<?> old = timerFuture;
        if (old != null) old.cancel(true);

        Long nextWake = getNextWakeMs();
        if (!running || nextWake == null) {
            timerFuture = null;
            return;
        }

        long delayMs = Math.max(0, nextWake - nowMs());
        timerFuture = scheduler.schedule(() -> {
            if (running) {
                onTimer().join();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Void> onTimer() {
        return CompletableFuture.runAsync(() -> {
            CronStore s = loadStore();
            long now = nowMs();

            List<CronJob> due = new ArrayList<>();
            for (CronJob j : s.getJobs()) {
                if (!j.isEnabled()) continue;
                Long next = j.getState().getNextRunAtMs();
                if (next != null && now >= next) due.add(j);
            }

            for (CronJob job : due) {
                executeJob(job).join();
            }

            saveStore();
            armTimer();
        });
    }

    private CompletableFuture<Void> executeJob(CronJob job) {
        long start = nowMs();

        CompletionStage<String> cs;
        try {
            if (onJob != null) {
                cs = onJob.handle(job);
            } else {
                cs = CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            cs = CompletableFuture.failedFuture(e);
        }

        return cs.handle((resp, ex) -> {
            if (ex == null) {
                job.getState().setLastStatus(CronJobState.LastStatus.ok);
                job.getState().setLastError(null);
            } else {
                job.getState().setLastStatus(CronJobState.LastStatus.error);
                job.getState().setLastError(String.valueOf(ex.getMessage()));
            }

            job.getState().setLastRunAtMs(start);
            job.setUpdatedAtMs(nowMs());

            // one-shot handling
            if (job.getSchedule().getKind() == CronSchedule.Kind.at) {
                if (job.isDeleteAfterRun()) {
                    CronStore s = loadStore();
                    s.getJobs().removeIf(j -> Objects.equals(j.getId(), job.getId()));
                } else {
                    job.setEnabled(false);
                    job.getState().setNextRunAtMs(null);
                }
            } else {
                job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), nowMs()));
            }

            return (Void) null; // 让泛型推断为 Void
        }).toCompletableFuture();
    }

    // ---------- public API (align python) ----------

    public List<CronJob> listJobs(boolean includeDisabled) {
        CronStore s = loadStore();
        List<CronJob> out = new ArrayList<>();
        for (CronJob j : s.getJobs()) {
            if (includeDisabled || j.isEnabled()) out.add(j);
        }
        out.sort(Comparator.comparingLong(j -> {
            Long t = j.getState().getNextRunAtMs();
            return t != null ? t : Long.MAX_VALUE;
        }));
        return out;
    }

    public CronJob addJob(
            String name,
            CronSchedule schedule,
            String message,
            boolean deliver,
            String channel,
            String to,
            boolean deleteAfterRun
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schedule, "schedule");

        validateScheduleForAdd(schedule);

        CronStore s = loadStore();
        long now = nowMs();

        CronJob job = new CronJob();
        job.setId(UUID.randomUUID().toString().substring(0, 8));
        job.setName(name);
        job.setEnabled(true);

        CronPayload payload = new CronPayload();
        payload.setKind(CronPayload.Kind.agent_turn);
        payload.setMessage(message != null ? message : "");
        payload.setDeliver(deliver);
        payload.setChannel(channel);
        payload.setTo(to);
        job.setPayload(payload);

        job.setSchedule(schedule);

        CronJobState state = new CronJobState();
        state.setNextRunAtMs(computeNextRun(schedule, now));
        job.setState(state);

        job.setCreatedAtMs(now);
        job.setUpdatedAtMs(now);
        job.setDeleteAfterRun(deleteAfterRun);

        s.getJobs().add(job);

        saveStore();
        armTimer();

        return job;
    }

    public boolean removeJob(String jobId) {
        CronStore s = loadStore();
        int before = s.getJobs().size();
        s.getJobs().removeIf(j -> Objects.equals(j.getId(), jobId));
        boolean removed = s.getJobs().size() < before;
        if (removed) {
            saveStore();
            armTimer();
        }
        return removed;
    }

    public CronJob enableJob(String jobId, boolean enabled) {
        CronStore s = loadStore();
        for (CronJob job : s.getJobs()) {
            if (Objects.equals(job.getId(), jobId)) {
                job.setEnabled(enabled);
                job.setUpdatedAtMs(nowMs());
                if (enabled) {
                    job.getState().setNextRunAtMs(computeNextRun(job.getSchedule(), nowMs()));
                } else {
                    job.getState().setNextRunAtMs(null);
                }
                saveStore();
                armTimer();
                return job;
            }
        }
        return null;
    }

    public CompletableFuture<Boolean> runJob(String jobId, boolean force) {
        CronStore s = loadStore();
        for (CronJob job : s.getJobs()) {
            if (Objects.equals(job.getId(), jobId)) {
                if (!force && !job.isEnabled()) return CompletableFuture.completedFuture(false);
                return executeJob(job).thenApply(v -> {
                    saveStore();
                    armTimer();
                    return true;
                });
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    public Map<String, Object> status() {
        CronStore s = loadStore();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", running);
        m.put("jobs", s.getJobs().size());
        m.put("next_wake_at_ms", getNextWakeMs());
        return m;
    }
}