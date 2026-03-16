package context;

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
     * 构建 Project Context 字符串
     * 对齐 OpenClaw 的 buildAgentSystemPrompt 中的 contextFiles 处理
     */
    public String buildProjectContext(List<BootstrapFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }

        boolean hasSoulFile = files.stream()
                .anyMatch(f -> "SOUL.md".equalsIgnoreCase(f.getName()));

        List<String> lines = new ArrayList<>();
        lines.add("# Project Context");
        lines.add("");
        lines.add("The following project context files have been loaded:");
        if (hasSoulFile) {
            lines.add("If SOUL.md is present, embody its persona and tone. Avoid stiff, generic replies; follow its guidance unless higher-priority instructions override it.");
        }
        lines.add("");

        for (BootstrapFile file : files) {
            lines.add("## " + file.getPath());
            lines.add("");
            if (file.isMissing()) {
                lines.add("[MISSING] Expected at: " + file.getPath());
            } else {
                lines.add(file.getContent());
            }
            lines.add("");
        }

        return String.join("\n", lines);
    }

    private void warn(String message) {
        if (warnHandler != null) {
            warnHandler.accept(message);
        } else {
            LOGGER.warning(message);
        }
    }
}