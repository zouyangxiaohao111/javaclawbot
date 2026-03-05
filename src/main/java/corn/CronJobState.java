package corn;

public class CronJobState {

    public enum LastStatus { ok, error, skipped }

    private Long nextRunAtMs;
    private Long lastRunAtMs;
    private LastStatus lastStatus;
    private String lastError;

    public Long getNextRunAtMs() { return nextRunAtMs; }
    public void setNextRunAtMs(Long nextRunAtMs) { this.nextRunAtMs = nextRunAtMs; }

    public Long getLastRunAtMs() { return lastRunAtMs; }
    public void setLastRunAtMs(Long lastRunAtMs) { this.lastRunAtMs = lastRunAtMs; }

    public LastStatus getLastStatus() { return lastStatus; }
    public void setLastStatus(LastStatus lastStatus) { this.lastStatus = lastStatus; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}