package context;

import cn.hutool.core.util.StrUtil;
import config.ConfigSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private final BootstrapConfig config;
    private final Consumer<String> warnHandler;

    public BootstrapLoader(Path workspace) {
        this(workspace, new BootstrapConfig(), null);
    }

    public BootstrapLoader(Path workspace, BootstrapConfig config, Consumer<String> warnHandler) {
        this.workspace = workspace;
        this.config = config != null ? config : new BootstrapConfig();
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
        if (config.getContextMode() != BootstrapConfig.ContextMode.LIGHTWEIGHT) {
            return files;
        }

        // lightweight 模式
        switch (config.getRunKind()) {
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
        int remainingTotalChars = config.getTotalMaxChars();
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
            int fileMaxChars = Math.min(config.getMaxChars(), remainingTotalChars);
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


    /**
     * 构建 字符串
     * 对齐 OpenClaw 的 buildAgentSystemPrompt 中的 contextFiles 处理
     */
    public String buildProjectContext(List<BootstrapFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }

        boolean hasSoulFile = files.stream()
                .anyMatch(f -> "SOUL.md".equalsIgnoreCase(f.getName()));

        List<String> lines = new ArrayList<>();
        lines.add("# 前置说明");
        lines.add("");
        lines.add("已加载的定义说明(在标签<pre_defined>)中):");
        if (hasSoulFile) {
            lines.add("若存在SOUL.md文件，需体现其人格特质与语气风格。避免生硬、通用的回复；除非更高优先级的指令覆盖，否则遵循其指导原则。");
        }
        lines.add("<pre_defined>");
        for (BootstrapFile file : files) {
            lines.add("## " + file.getPath());
            lines.add("");
            if (file.isMissing()) {
                lines.add("[MISSING] Expected at: " + file.getPath());
            } else {
                // 基础 AGENTS.md SOUL.md USER.md 直接读取, 省下工具调用
                if ("AGENTS.md".equalsIgnoreCase(file.getName())
                        || "SOUL.md".equalsIgnoreCase(file.getName())
                        || "USER.md".equalsIgnoreCase(file.getName())) {
                    lines.add(file.getContent());
                    continue;
                }
                lines.add("如需要读取,则调用read_file工具加载");
            }
            lines.add("");
        }
        lines.add("</pre_defined>");

        return String.join("\n", lines);
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
        return doGetContent("AGENTS.md");
    }

    public String loadIdentity() {
        return doGetContent("IDENTITY.md");
    }

    public String loadSoul() {
        return doGetContent("SOUL.md");
    }

    private String doGetContent(String name) {
        BootstrapFile file = resolveFile(name);
        if (file == null) {
            return "";
        }
        return StrUtil.isBlank(file.getContent()) ? "文件路径为:" + file.getPath() : file.getContent();
    }

    public String loadUser() {
        return doGetContent("USER.md");
    }
}