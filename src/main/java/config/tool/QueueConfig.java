
package config.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueueConfig {
    /**
     * 队列模式：collect/steer/followup/interrupt
     */
    private String mode = "collect";

    /**
     * 去抖动时间（毫秒）
     */
    private int debounceMs = 1000;

    /**
     * 队列容量
     */
    private int cap = 20;

    /**
     * 溢出策略：old/new/summarize
     */
    private String drop = "summarize";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getDebounceMs() {
        return debounceMs;
    }

    public void setDebounceMs(int debounceMs) {
        this.debounceMs = debounceMs;
    }

    public int getCap() {
        return cap;
    }

    public void setCap(int cap) {
        this.cap = cap;
    }

    public String getDrop() {
        return drop;
    }

    public void setDrop(String drop) {
        this.drop = drop;
    }
}