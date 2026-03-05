package corn;

import java.util.ArrayList;
import java.util.List;

public class CronStore {

    private int version = 1;
    private List<CronJob> jobs = new ArrayList<>();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public List<CronJob> getJobs() { return jobs; }
    public void setJobs(List<CronJob> jobs) { this.jobs = jobs; }
}