package config.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.Config;
import config.ConfigIO;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件组配置
 *
 * config.json 示例：
 * <pre>
 * {
 *   "plugins": {
 *     "items": {
 *       "weather": { "enabled": true, "priority": 10 },
 *       "translator": { "enabled": false },
 *       "reminder": { "enabled": true, "priority": 20 }
 *     }
 *   }
 * }
 * </pre>
 */
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PluginsConfig {

    /** 插件配置映射：key 为插件名称，value 为插件配置 */
    private Map<String, PluginConfig> items = new HashMap<>();

    public Map<String, PluginConfig> getItems() {
        return items;
    }

    public void setItems(Map<String, PluginConfig> items) {
        this.items = (items != null) ? items : new HashMap<>();
    }

    /**
     * 获取指定插件的配置，若不存在则返回默认配置（enabled=true）
     */
    public PluginConfig getPluginConfig(String name, Config configJson, Path configPath) {
        if (name == null || name.isBlank()) {
            return new PluginConfig();
        }
        PluginConfig config = items.get(name);
        if (config == null) {
            // 未配置的插件默认启用
            config = new PluginConfig(name);
            config.setEnabled(true);
            // 添加至配置文件
            items.put(name, config);
            log.info("Plugin config added: {}", name);
            try {
                configJson.setPlugins(this);
                ConfigIO.saveConfig(configJson, configPath);
            } catch (IOException e) {
                log.error("插件报错失败");
            }
        }
        return config;
    }

    /**
     * 判断指定插件是否启用
     */
    public boolean isPluginEnabled(String name, Config configJson, Path configPath) {
        return getPluginConfig(name, configJson, configPath).isEnabled();
    }
}