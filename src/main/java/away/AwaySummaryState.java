package away;

/**
 * Away Summary State
 *
 * 追踪 AwaySummary 的状态机状态
 */
public class AwaySummaryState {

    /**
     * 是否正在加载/任务执行中
     */
    private boolean loading = false;

    /**
     * 用户是否已离开
     */
    private boolean away = false;

    /**
     * 离开开始时间
     */
    private long awayStartTime = 0;

    /**
     * 待处理的离开摘要（任务完成时用户已离开）
     */
    private boolean pendingAwaySummary = false;

    /**
     * 摘要是否已准备好
     */
    private boolean summaryReady = false;

    /**
     * 已生成的摘要内容
     */
    private String generatedSummary = null;

    /**
     * 任务完成时间
     */
    private long taskCompleteTime = 0;

    public boolean isLoading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public boolean isAway() {
        return away;
    }

    public void setAway(boolean away) {
        this.away = away;
    }

    public long getAwayStartTime() {
        return awayStartTime;
    }

    public void setAwayStartTime(long awayStartTime) {
        this.awayStartTime = awayStartTime;
    }

    public boolean isPendingAwaySummary() {
        return pendingAwaySummary;
    }

    public void setPendingAwaySummary(boolean pendingAwaySummary) {
        this.pendingAwaySummary = pendingAwaySummary;
    }

    public boolean isSummaryReady() {
        return summaryReady;
    }

    public void setSummaryReady(boolean summaryReady) {
        this.summaryReady = summaryReady;
    }

    public String getGeneratedSummary() {
        return generatedSummary;
    }

    public void setGeneratedSummary(String generatedSummary) {
        this.generatedSummary = generatedSummary;
    }

    public long getTaskCompleteTime() {
        return taskCompleteTime;
    }

    public void setTaskCompleteTime(long taskCompleteTime) {
        this.taskCompleteTime = taskCompleteTime;
    }

    /**
     * 重置状态
     */
    public void reset() {
        this.loading = false;
        this.away = false;
        this.awayStartTime = 0;
        this.pendingAwaySummary = false;
        this.summaryReady = false;
        this.generatedSummary = null;
        this.taskCompleteTime = 0;
    }
}
