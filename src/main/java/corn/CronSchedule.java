package corn;

import java.util.Objects;

public class CronSchedule {

    public enum Kind { at, every, cron }

    private Kind kind;
    private Long atMs;     // for at
    private Long everyMs;  // for every
    private String expr;   // for cron
    private String tz;     // timezone id (IANA)

    public CronSchedule() {}
    public CronSchedule(Kind kind) {
        this(kind, null, null, null, null);
    }

    public CronSchedule(Kind kind, Long atMs, Long everyMs, String expr, String tz) {
        this.kind = Objects.requireNonNull(kind);
        this.atMs = atMs;
        this.everyMs = everyMs;
        this.expr = expr;
        this.tz = tz;
    }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public Long getAtMs() { return atMs; }
    public void setAtMs(Long atMs) { this.atMs = atMs; }

    public Long getEveryMs() { return everyMs; }
    public void setEveryMs(Long everyMs) { this.everyMs = everyMs; }

    public String getExpr() { return expr; }
    public void setExpr(String expr) { this.expr = expr; }

    public String getTz() { return tz; }
    public void setTz(String tz) { this.tz = tz; }
}