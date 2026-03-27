package config;

import cli.RuntimeComponents;
import config.agent.AgentRuntimeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.HotSwappableProvider;
import providers.LLMProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 配置热加载器
 *
 * 作用：
 * 1. 维护最近一次成功加载的配置快照
 * 2. 检查 config.json 是否发生变化
 * 3. 若新配置加载失败，则保留旧配置继续运行
 */
public final class ConfigReloader {

    private static final Logger log = LoggerFactory.getLogger(ConfigReloader.class);

    private final Path configPath;
    private volatile Config currentConfig;
    private volatile long lastModifiedMillis = -1L;
    private final AtomicLong version = new AtomicLong(0);
    private final ReentrantLock reloadLock = new ReentrantLock();

    public ConfigReloader(Path configPath) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.currentConfig = ConfigIO.loadConfig(configPath);
        this.lastModifiedMillis = readLastModified(configPath);
        this.version.set(1L);
    }

    public Config getCurrentConfig() {
        Config cfg = currentConfig;
        return cfg != null ? cfg : new Config();
    }

    public long getVersion() {
        return version.get();
    }

    public boolean refreshIfChanged() {
        long nowModified = readLastModified(configPath);

        if (nowModified == lastModifiedMillis) {
            return false;
        }

        reloadLock.lock();
        try {
            long latest = readLastModified(configPath);
            if (latest == lastModifiedMillis) {
                return false;
            }

            try {
                Config newConfig = ConfigIO.loadConfig(configPath);
                if (newConfig == null) {
                    log.warn("配置重载返回空，保留之前的配置: {}", configPath);
                    return false;
                }

                this.currentConfig = newConfig;
                this.lastModifiedMillis = latest;
                long v = this.version.incrementAndGet();

                log.info("配置重载成功。版本={}, 路径={}", v, configPath);
                return true;
            } catch (Exception e) {
                log.warn("配置重载失败，保留之前的快照。路径={}, 错误={}",
                        configPath, e.toString());
                return false;
            }
        } finally {
            reloadLock.unlock();
        }
    }

    private static long readLastModified(Path path) {
        try {
            if (path == null || Files.notExists(path)) {
                return -1L;
            }
            FileTime ft = Files.getLastModifiedTime(path);
            return ft.toMillis();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * 共享运行时组件（使用默认配置路径）
     */
    public static RuntimeComponents createRuntimeComponents() {
        return createRuntimeComponents(null, null);
    }

    /**
     * 创建运行时组件（支持自定义配置路径和 workspace 路径）
     *
     * 对齐 Python 的 _load_runtime_config(config, workspace)：
     * - config: 指定配置文件路径，null 则使用默认 ~/.javaclawbot/config.json
     * - workspace: 覆盖配置中的 workspace 路径
     *
     * @param configPath    自定义配置文件路径，null 则使用默认路径
     * @param workspacePath 自定义 workspace 路径，null 则使用配置中的路径
     */
    public static RuntimeComponents createRuntimeComponents(Path configPath, Path workspacePath) {
        Path effectiveConfigPath;
        // 新增允许多实例运行
        if (workspacePath != null) {
            effectiveConfigPath = (configPath != null) ? configPath : ConfigIO.getConfigPath(workspacePath);
        }else {
            effectiveConfigPath = (configPath != null) ? configPath : ConfigIO.getConfigPath();
        }

        ConfigReloader reloader = new ConfigReloader(effectiveConfigPath);
        Config config = reloader.getCurrentConfig();

        // 覆盖 workspace 路径（对齐 Python 的 _load_runtime_config）
        if (workspacePath != null) {
            config.setWorkspacePath(workspacePath);
        }

        LLMProvider provider = new HotSwappableProvider(reloader);
        AgentRuntimeSettings runtimeSettings = new AgentRuntimeSettings(reloader);

        return new RuntimeComponents(reloader, config, provider, runtimeSettings);
    }
}