package corn;

public class CronJob {

    private String id;
    private String name;
    private boolean enabled = true;

    private CronSchedule schedule = new CronSchedule(CronSchedule.Kind.every);
    private CronPayload payload = new CronPayload();
    private CronJobState state = new CronJobState();

    private long createdAtMs = 0;
    private long updatedAtMs = 0;

    private boolean deleteAfterRun = false;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public CronSchedule getSchedule() { return schedule; }
    public void setSchedule(CronSchedule schedule) { this.schedule = schedule; }

    public CronPayload getPayload() { return payload; }
    public void setPayload(CronPayload payload) { this.payload = payload; }

    public CronJobState getState() { return state; }
    public void setState(CronJobState state) { this.state = state; }

    public long getCreatedAtMs() { return createdAtMs; }
    public void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    public long getUpdatedAtMs() { return updatedAtMs; }
    public void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }

    public boolean isDeleteAfterRun() { return deleteAfterRun; }
    public void setDeleteAfterRun(boolean deleteAfterRun) { this.deleteAfterRun = deleteAfterRun; }
}