package context;

import config.Config;
import config.ConfigIO;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * 项目上下文管理器
 *
 * 功能：
 * 1. 从配置文件读取持久化的 projectPath
 * 2. 从 cwd 自动检测项目目录（.svn、CODE-AGENT.md、CLAUDE.md）
 * 3. 读取项目的 CODE-AGENT.md 或 CLAUDE.md 前200行
 * 4. 支持运行时切换项目并持久化
 */
@Slf4j
public class ProjectContext {

    private static final int MAX_LINES = 200;

    /** 项目目录标识文件/目录 */
    private static final List<String> PROJECT_MARKERS = List.of(
            ".svn",
            "CODE-AGENT.md",
            "CLAUDE.md"
    );

    private final Path workspace;
    private Path projectPath;
    private boolean development;

    public ProjectContext(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.development = false;
        this.projectPath = null;
        loadFromConfig();
    }

    /**
     * 从配置文件加载项目路径和开发者模式
     */
    private void loadFromConfig() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        this.development = config.getAgents().getDefaults().isDevelopment();

        String configuredPath = config.getAgents().getDefaults().getProjectPath();
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = expandPath(configuredPath);
            if (isValidProjectDir(p)) {
                this.projectPath = p;
                log.debug("Loaded projectPath from config: {}", p);
            } else {
                log.warn("Configured projectPath is not a valid project: {}", configuredPath);
            }
        }

        // 如果配置中没有，尝试 cwd 自动检测
        if (this.projectPath == null) {
            this.projectPath = detectFromCwd();
            if (this.projectPath != null) {
                log.debug("Detected project from cwd: {}", this.projectPath);
            }
        }
    }

    /**
     * 刷新配置（用于配置热更新）
     */
    public void refresh() {
        loadFromConfig();
    }

    /**
     * 设置项目路径并持久化到配置
     *
     * @param path 项目路径，null 或空表示清除
     */
    public void setProjectPath(String path) {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        // 支持windows的路径，将 \ 转义为 \\，否则会报错
        //path = path.replace("\\", "\\\\");

        if (path == null || path.isBlank() || "clear".equalsIgnoreCase(path)) {
            // 清除项目路径
            config.getAgents().getDefaults().setProjectPath(null);
            this.projectPath = null;
            log.info("Cleared projectPath from config");
        } else {
            Path p = expandPath(path);
            if (isValidProjectDir(p)) {
                config.getAgents().getDefaults().setProjectPath(p.toString());
                this.projectPath = p;
                log.info("Set projectPath to: {}", p);
            } else {
                log.warn("Invalid project path: {} (no .svn, CODE-AGENT.md, or CLAUDE.md found)", path);
            }
        }

        // 持久化到配置文件
        try {
            ConfigIO.saveConfig(config, ConfigIO.getConfigPath(workspace));
        } catch (Exception e) {
            log.error("Failed to save projectPath to config: {}", e.getMessage());
        }
    }

    /**
     * 获取当前项目路径
     */
    public Path getProjectPath() {
        return projectPath;
    }

    /**
     * 是否是开发者模式
     */
    public boolean isDevelopment() {
        return development;
    }

    /**
     * 是否有有效的项目路径
     */
    public boolean hasProject() {
        return projectPath != null && isValidProjectDir(projectPath);
    }

    /**
     * 构建项目上下文提示词
     *
     * @return 项目上下文文本，如果无项目或非开发模式返回空字符串
     */
    public String buildProjectContext() {
        if (!development || projectPath == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 项目路径信息
        sb.append("<project-context>\n");
        sb.append("当前项目: ").append(projectPath).append("\n");

        // 读取项目指令文件
        String content = readProjectInstruction();
        if (content != null && !content.isBlank()) {
            sb.append("\n# 项目指令文件（前 ").append(MAX_LINES).append(" 行）\n");
            sb.append(content);
            sb.append("\n（超过 ").append(MAX_LINES).append(" 行已截断，完整内容请使用 read_file 工具）\n");
        }

        sb.append("</project-context>");
        return sb.toString();
    }

    /**
     * 读取项目指令文件（CODE-AGENT.md 或 CLAUDE.md）前200行
     */
    private String readProjectInstruction() {
        if (projectPath == null) return null;

        // 优先读取 CODE-AGENT.md
        Path codeAgent = projectPath.resolve("CODE_AGENT.md");
        if (Files.isRegularFile(codeAgent)) {
            return readFileMaxLines(codeAgent);
        }

        // 其次读取 CLAUDE.md
        Path claude = projectPath.resolve("CLAUDE.md");
        if (Files.isRegularFile(claude)) {
            return readFileMaxLines(claude);
        }

        return null;
    }

    /**
     * 读取文件前 N 行
     */
    private String readFileMaxLines(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int limit = Math.min(lines.size(), MAX_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to read project instruction file: {} - {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 从当前工作目录自动检测项目
     */
    private Path detectFromCwd() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        // 检查 cwd 及其父目录（最多3层）
        Path current = cwd;
        for (int i = 0; i < 4 && current != null; i++) {
            if (isValidProjectDir(current)) {
                return current;
            }
            current = current.getParent();
        }

        return null;
    }

    /**
     * 判断是否是有效的项目目录（包含 .svn、CODE-AGENT.md 或 CLAUDE.md）
     */
    public static boolean isValidProjectDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }

        return true;
        /*for (String marker : PROJECT_MARKERS) {
            Path markerPath = dir.resolve(marker);
            if (Files.exists(markerPath)) {
                return true;
            }
        }

        return false;*/
    }

    /**
     * 展开路径（支持 ~ 和相对路径）
     */
    private Path expandPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String s = path.trim();

        // 支持 ~ 扩展
        if (s.startsWith("~/") || "~".equals(s)) {
            String home = System.getProperty("user.home", "");
            if ("~".equals(s)) {
                return Paths.get(home).toAbsolutePath().normalize();
            }
            return Paths.get(home, s.substring(2)).toAbsolutePath().normalize();
        }

        // 相对路径基于 cwd 展开
        Path p = Paths.get(s);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p);
        }

        return p.toAbsolutePath().normalize();
    }
}