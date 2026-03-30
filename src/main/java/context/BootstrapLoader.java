package context;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import config.Config;
import config.ConfigIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Bootstrap 文件加载器
 * 对齐 OpenClaw 的 bootstrap-files.ts 和 pi-embedded-helpers/bootstrap.ts
 *
 * 功能：
 * 1. 加载工作区 bootstrap 文件
 * 2. 根据 contextMode/runKind 过滤
 * 3. 应用字符限制和截断
 */
public class BootstrapLoader {

    private static final Logger LOGGER = Logger.getLogger(BootstrapLoader.class.getName());

    /**
     * Bootstrap 文件列表（对齐 OpenClaw workspace.ts）
     */
    public static final List<String> BOOTSTRAP_FILE_NAMES = List.of(
            "AGENTS.md",
            "SOUL.md",
            "USER.md",
            "TOOLS.md",
            "IDENTITY.md",
            "HEARTBEAT.md",
            "BOOTSTRAP.md",
            "MEMORY.md"
    );

    private final Path workspace;
    private final BootstrapConfig bootstrapConfig;
    private final Consumer<String> warnHandler;

    public BootstrapLoader(Path workspace) {
        this(workspace, new BootstrapConfig(), null);
    }

    public BootstrapLoader(Path workspace, BootstrapConfig bootstrapConfig, Consumer<String> warnHandler) {
        this.workspace = workspace;
        this.bootstrapConfig = bootstrapConfig != null ? bootstrapConfig : new BootstrapConfig();
        this.warnHandler = warnHandler;
    }

    /**
     * 加载所有 bootstrap 文件
     */
    public List<BootstrapFile> loadAllFiles() {
        List<BootstrapFile> files = new ArrayList<>();

        for (String fileName : BOOTSTRAP_FILE_NAMES) {
            Path filePath = workspace.resolve(fileName);

            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    files.add(BootstrapFile.existing(fileName, filePath, content));
                } catch (IOException e) {
                    warn("Failed to read bootstrap file: " + fileName + " - " + e.getMessage());
                    files.add(BootstrapFile.missing(fileName, filePath));
                }
            } else {
                // 不添加缺失文件到列表（与 OpenClaw 行为一致）
            }
        }

        return files;
    }

    /**
     * 加载所有 bootstrap 文件
     */
    public BootstrapFile loadFile(String name) {
        BootstrapFile file = null;

        Path filePath = workspace.resolve(name);

        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            try {
                String content = Files.readString(filePath);
                file = BootstrapFile.existing(name, filePath, content);
            } catch (IOException e) {
                warn("Failed to read bootstrap file: " + name + " - " + e.getMessage());
                file = BootstrapFile.missing(name, filePath);
            }
        }
        return file;
    }

    /**
     * 根据 contextMode 和 runKind 过滤文件
     * 对齐 OpenClaw 的 applyContextModeFilter
     */
    public List<BootstrapFile> applyContextModeFilter(List<BootstrapFile> files) {
        if (bootstrapConfig.getContextMode() != BootstrapConfig.ContextMode.LIGHTWEIGHT) {
            return files;
        }

        // lightweight 模式
        switch (bootstrapConfig.getRunKind()) {
            case HEARTBEAT:
                // heartbeat 只加载 HEARTBEAT.md
                return files.stream()
                        .filter(f -> "HEARTBEAT.md".equals(f.getName()))
                        .toList();
            case CRON:
            case DEFAULT:
            default:
                // cron/default lightweight 模式返回空
                return List.of();
        }
    }

    /**
     * 应用字符限制和截断
     * 对齐 OpenClaw 的 buildBootstrapContextFiles
     */
    public List<BootstrapFile> applyCharLimits(List<BootstrapFile> files) {
        int remainingTotalChars = bootstrapConfig.getTotalMaxChars();
        List<BootstrapFile> result = new ArrayList<>();

        for (BootstrapFile file : files) {
            if (remainingTotalChars <= 0) {
                warn("Bootstrap total char budget exhausted, skipping remaining files");
                break;
            }

            if (file.isMissing()) {
                // 缺失文件只记录路径
                String missingText = "[MISSING] Expected at: " + file.getPath();
                if (missingText.length() <= remainingTotalChars) {
                    remainingTotalChars -= missingText.length();
                    result.add(BootstrapFile.missing(file.getName(), file.getPath()));
                }
                continue;
            }

            if (remainingTotalChars < BootstrapConfig.MIN_BOOTSTRAP_FILE_BUDGET_CHARS) {
                warn("Remaining bootstrap budget is " + remainingTotalChars + " chars, skipping additional files");
                break;
            }

            // 文件最大字符
            int fileMaxChars = Math.min(bootstrapConfig.getMaxChars(), remainingTotalChars);
            String trimmedContent = trimContent(file.getContent(), file.getName(), fileMaxChars);

            if (trimmedContent != null && !trimmedContent.isEmpty()) {
                remainingTotalChars -= trimmedContent.length();
                result.add(BootstrapFile.existing(file.getName(), file.getPath(), trimmedContent));
            }
        }

        return result;
    }

    /**
     * 截断内容
     * 对齐 OpenClaw 的 trimBootstrapContent
     */
    private String trimContent(String content, String fileName, int maxChars) {
        if (content == null) {
            return null;
        }

        String trimmed = content.stripTrailing();

        if (trimmed.length() <= maxChars) {
            return trimmed;
        }

        // 需要截断
        int headChars = (int) Math.floor(maxChars * BootstrapConfig.BOOTSTRAP_HEAD_RATIO);
        int tailChars = (int) Math.floor(maxChars * BootstrapConfig.BOOTSTRAP_TAIL_RATIO);

        String head = trimmed.substring(0, headChars);
        String tail = trimmed.substring(trimmed.length() - tailChars);

        String marker = String.format(
                "\n[...truncated, read %s for full content...]\n…(truncated %s: kept %d+%d chars of %d)…\n",
                fileName, fileName, headChars, tailChars, trimmed.length()
        );

        String result = head + marker + tail;

        // 确保不超过预算
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars);
        }

        warn("Bootstrap file " + fileName + " truncated from " + trimmed.length() + " to " + result.length() + " chars");
        return result;
    }

    /**
     * 完整的加载流程
     */
    public List<BootstrapFile> resolveBootstrapFiles() {
        List<BootstrapFile> files = loadAllFiles();
        files = applyContextModeFilter(files);
        files = applyCharLimits(files);
        return files;
    }

    /**
     * 完整的加载流程
     */
    public BootstrapFile resolveFile(String name) {
        BootstrapFile file = loadFile(name);
        if (file == null) {
            return null;
        }
        file = applyContextModeFilter(List.of(file)).get(0);
        file = applyCharLimits(List.of(file)).get(0);
        return file;
    }


    private void warn(String message) {
        if (warnHandler != null) {
            warnHandler.accept(message);
        } else {
            LOGGER.warning(message);
        }
    }

    /**
     * 是否需要引导
     * @return
     */
    public boolean isNeedBootstrap() {
        Path filePath = workspace.resolve("BOOTSTRAP.md");
        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            // 存在文件代表需要引导
            return true;
        }
        // 不存在代表已经引导成功了
        return false;
    }

    public String loadAgents() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        String content;
        // 是否开发者
        if (config.getAgents().getDefaults().isDevelopment()){
            content = ResourceUtil.readUtf8Str("templates/AGENTS_DEV.md");
            content = ifBlankDoGetNew(content, "AGENTS.md");
        }else {
            content = doGetContent("AGENTS.md");
        }
        content = ifBlankDoGetNew(content, "AGENTS.md");
        // Python：workspace.expanduser().resolve()
        String workspacePath = workspace.toAbsolutePath().normalize().toString();

        String os = System.getProperty("os.name", "Unknown");
        String arch = System.getProperty("os.arch", "Unknown");
        String version = System.getProperty("os.version", "Unknown");
        String javaVersion = System.getProperty("java.version", "Unknown");

        // Python：macOS 特判 Darwin；这里按 Java 的 os.name 简单特判
        String system = os;
        if (system.toLowerCase(Locale.ROOT).contains("mac") || system.toLowerCase(Locale.ROOT).contains("darwin")) {
            system = "macOS";
        }
        String runtime = system + " " + arch + ", 版本:" + version + ", Java " + javaVersion;
        return content.replace("{runtime}", runtime).replace("{workspace}", workspacePath);
    }

    private String ifBlankDoGetNew(String content, String name) {
        if (StrUtil.isBlank(content)) {
            content = doGetContent(name);
        }
        return content;
    }


    public String loadIdentity() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        String content = "";
        if (config.getAgents().getDefaults().isDevelopment()){
            content = ResourceUtil.readUtf8Str("templates/IDENTITY_DEV.md");
            content = ifBlankDoGetNew(content, "IDENTITY.md");
        }else {
            content = doGetContent("IDENTITY.md");
        }
        content = ifBlankDoGetNew(content, "IDENTITY.md");
        return content;
    }

    public String loadSoul() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        String content = "";
        if (config.getAgents().getDefaults().isDevelopment()){
            content = ResourceUtil.readUtf8Str("templates/SOUL_DEV.md");
            content = ifBlankDoGetNew(content, "SOUL.md");
        }else {
            content = doGetContent("SOUL.md");
        }

        if (StrUtil.isBlank(content)) {
            content = doGetContent("SOUL.md");
        }
        return content;
    }

    private String doGetContent(String name) {
        BootstrapFile file = resolveFile(name);
        if (file == null) {
            return "";
        }
        return StrUtil.isBlank(file.getContent()) ? "文件路径为:" + file.getPath() : file.getContent();
    }

    public String loadUser() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        String content = "";
        if (config.getAgents().getDefaults().isDevelopment()){
            content = ResourceUtil.readUtf8Str("templates/USER_DEV.md");
            content = ifBlankDoGetNew(content, "USER.md");
        }else {
            content = doGetContent("USER.md");
        }
        content = ifBlankDoGetNew(content, "USER.md");
        return content;
    }

    public String loadTool() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        String content = "";
        if (config.getAgents().getDefaults().isDevelopment()){
            content = ResourceUtil.readUtf8Str("templates/TOOLS_DEV.md");
            content = ifBlankDoGetNew(content, "TOOLS.md");
        }else {
            content = doGetContent("TOOLS.md");
        }
        content = ifBlankDoGetNew(content, "TOOLS.md");

        return content;
    }
}