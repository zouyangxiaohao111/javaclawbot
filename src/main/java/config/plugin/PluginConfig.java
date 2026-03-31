package config.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个插件配置
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PluginConfig {

    /** 插件名称（对应 plugins/ 目录下的文件名，不含扩展名） */
    private String name;

    /** 是否启用，默认 true */
    private boolean enabled = true;

    /** 插件文件路径（可选，默认使用 workspace/plugins/{name}.js 或 .py） */
    private String path;

    /** 执行优先级（数字越小越先执行），默认 100 */
    private int priority = 100;

    public PluginConfig() {}

    public PluginConfig(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }
}