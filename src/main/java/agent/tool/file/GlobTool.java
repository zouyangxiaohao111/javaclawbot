package agent.tool.file;

import agent.tool.Tool;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import providers.cli.ProjectRegistry;
import utils.PathUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glob tool — atomic-level port of Claude Code's GlobTool.ts + glob.ts.
 * <p>
 * Uses ripgrep (rg --files --glob) for fast file pattern matching.
 * Returns matching file paths sorted by modification time, with a default limit of 100.
 * <p>
 * Line-by-line correspondence:
 * - GlobTool.ts inputSchema → parameters()
 * - GlobTool.ts call() → execute()
 * - glob.ts glob() → globFiles()
 * - glob.ts extractGlobBaseDirectory() → extractGlobBaseDirectory()
 * - GlobTool.ts mapToolResultToToolResultBlockParam → result formatting in execute()
 */
@Slf4j
public final class GlobTool extends Tool {

    // ---------- Line 60: maxResultSizeChars = 100_000 ----------
    private static final int MAX_RESULT_SIZE_CHARS = 100_000;

    // ---------- Line 157: globLimits default = 100 ----------
    // Default limit on number of results (matches Claude Code)
    private static final int DEFAULT_GLOB_LIMIT = 100;

    private final Path workspace;
    private final Path allowedDir;
    private final Supplier<ProjectRegistry> projectRegistrySupplier;

    public GlobTool(Path workspace, Path allowedDir, Supplier<ProjectRegistry> projectRegistrySupplier) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.projectRegistrySupplier = projectRegistrySupplier;
    }

    // ---------- Line 57-58: name = 'Glob' ----------
    @Override
    public String name() {
        return "Glob";
    }

    // ---------- Line 3-7: DESCRIPTION ----------
    @Override
    public String description() {
        return "- Fast file pattern matching tool that works with any codebase size\n"
                + "- Supports glob patterns like \"**/*.js\" or \"src/**/*.ts\"\n"
                + "- Returns matching file paths sorted by modification time\n"
                + "- Use this tool when you need to find files by name patterns\n"
                + "- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead";
    }

    @Override
    public int maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    // ---------- Line 26-36: inputSchema ----------
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of(
                "type", "string",
                "description", "The glob pattern to match files against"
        ));
        props.put("path", Map.of(
                "type", "string",
                "description", "The directory to search in.  Defaults to current project directory(main agent project). if current project dir is null -> workspace directory IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    // ---------- Line 94-134: validateInput ----------
    // Path validation is done inline in execute()

    // ---------- Line 154-176: call() ----------
    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        try {
            String pattern = asString(args.get("pattern"));
            String path = asString(args.get("path"));

            log.debug("Glob pattern={}, path={}", pattern, path);

            // ---------- Line 88-89: getPath ----------
            Path searchDir;
            if (path != null && !path.isEmpty()) {
                searchDir = Paths.get(path);
            } else {
                // 先从主项目中配置
                ProjectRegistry registry = projectRegistrySupplier.get();
                String mainProjectPath = registry.getMainProjectPath();
                if (StrUtil.isBlank(mainProjectPath)) {
                    searchDir = workspace;
                }else {
                    searchDir = Paths.get(mainProjectPath);
                }
            }
            /*if (path != null && !path.isEmpty()) {
                searchDir = PathUtil.resolvePath(path, workspace, allowedDir);
            } else {
                searchDir = workspace;
            }*/

            // ---------- Line 94-110: validateInput ----------
            if (path != null && !path.isEmpty()) {
                if (!Files.exists(searchDir)) {
                    return CompletableFuture.completedFuture(
                            "Error: Directory does not exist: " + path + ". Current working directory: " + workspace);
                }
                if (!Files.isDirectory(searchDir)) {
                    return CompletableFuture.completedFuture(
                            "Error: Path is not a directory: " + path);
                }
            }

            // ---------- Line 154: start timer ----------
            long startTime = System.currentTimeMillis();

            // ---------- Line 157: limit ----------
            int limit = DEFAULT_GLOB_LIMIT;

            // ---------- Line 158-164: call glob() ----------
            GlobResult globResult = globFiles(pattern, searchDir, limit);

            // ---------- Line 166: relativize paths ----------
            List<String> filenames = new ArrayList<>();
            for (String abs : globResult.files) {
                filenames.add(toRelativePath(abs));
            }

            // ---------- Line 167-172: build output ----------
            long durationMs = System.currentTimeMillis() - startTime;
            int numFiles = filenames.size();
            boolean truncated = globResult.truncated;

            log.debug("Glob 搜索完成, 找到 {} 个文件, 耗时 {} ms", numFiles, durationMs);

            // ---------- Line 177-197: mapToolResultToToolResultBlockParam ----------
            if (numFiles == 0) {
                return CompletableFuture.completedFuture("No files found");
            }

            List<String> outputLines = new ArrayList<>(filenames);
            if (truncated) {
                outputLines.add("(Results are truncated. Consider using a more specific path or pattern.)");
            }

            return CompletableFuture.completedFuture(String.join("\n", outputLines));

        } catch (SecurityException se) {
            return CompletableFuture.completedFuture("Error: " + se.getMessage());
        } catch (Exception e) {
            log.error("工具执行失败: Glob, 错误: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture("Error searching files: " + e.getMessage());
        }
    }

    // ======================== glob.ts: glob() ========================

    /**
     * Port of glob.ts glob() function (line 66-130).
     * Uses ripgrep --files --glob for file pattern matching.
     *
     * Returns files sorted by modification time (oldest first from rg, we then
     * reverse to get newest first matching Claude Code behavior).
     */
    private GlobResult globFiles(String filePattern, Path cwd, int limit) throws Exception {
        String searchDir = cwd.toString();
        String searchPattern = filePattern;

        // ---------- Line 78-84: handle absolute paths ----------
        // 修复: 不能直接用 Path.of(pattern) 因为 glob 字符 (*, ?, [, {) 在 Windows 上是非法路径字符
        // 先提取基础目录，再判断是否绝对路径
        ExtractBaseResult baseResult = extractGlobBaseDirectory(filePattern);
        if (baseResult.baseDir != null && !baseResult.baseDir.isEmpty()) {
            Path basePath = Paths.get(baseResult.baseDir);
            if (basePath.isAbsolute()) {
                searchDir = baseResult.baseDir;
                searchPattern = baseResult.relativePattern;
            }
        }

        // ---------- Line 100-107: build ripgrep args ----------
        // --files: list files instead of searching content
        // --glob: filter by pattern
        // --sort=modified: sort by modification time (oldest first)
        // --no-ignore: don't respect .gitignore (default true in Claude Code)
        // --hidden: include hidden files (default true in Claude Code)
        List<String> args = new ArrayList<>();
        args.add("--files");
        args.add("--glob");
        args.add(searchPattern);
        args.add("--sort=modified");
        args.add("--no-ignore");
        args.add("--hidden");

        // ---------- Line 119: execute ripgrep ----------
        List<String> allPaths = ripGlobFiles(args, searchDir);

        // ---------- Line 122-124: convert to absolute paths ----------
        List<String> absolutePaths = new ArrayList<>();
        for (String p : allPaths) {
            Path resolved = Path.of(p);
            if (!resolved.isAbsolute()) {
                resolved = cwd.resolve(p);
            }
            absolutePaths.add(resolved.toString());
        }

        // ---------- Line 126-128: apply limit ----------
        boolean truncated = absolutePaths.size() > limit;
        // --sort=modified returns oldest first; we want newest first (like Claude Code)
        Collections.reverse(absolutePaths);
        List<String> files;
        if (absolutePaths.size() > limit) {
            files = absolutePaths.subList(0, limit);
        } else {
            files = absolutePaths;
        }

        return new GlobResult(files, truncated);
    }

    // ======================== glob.ts: extractGlobBaseDirectory() ========================

    /**
     * Port of glob.ts extractGlobBaseDirectory() (line 17-64).
     * Extracts the static base directory from a glob pattern.
     * The base directory is everything before the first glob special character (* ? [ {).
     */
    private static ExtractBaseResult extractGlobBaseDirectory(String pattern) {
        // Find the first glob special character: *, ?, [, {
        Pattern globCharPattern = Pattern.compile("[*?\\[{]");
        Matcher matcher = globCharPattern.matcher(pattern);

        if (!matcher.find()) {
            // No glob characters - this is a literal path
            // Return the directory portion and filename as pattern
            int lastSep = Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'));
            if (lastSep >= 0) {
                return new ExtractBaseResult(pattern.substring(0, lastSep), pattern.substring(lastSep + 1));
            }
            return new ExtractBaseResult("", pattern);
        }

        // Get everything before the first glob character
        String staticPrefix = pattern.substring(0, matcher.start());

        // Find the last path separator in the static prefix
        int lastSepIndex = Math.max(staticPrefix.lastIndexOf('/'), staticPrefix.lastIndexOf('\\'));

        if (lastSepIndex == -1) {
            // No path separator before the glob - pattern is relative to cwd
            return new ExtractBaseResult("", pattern);
        }

        String baseDir = staticPrefix.substring(0, lastSepIndex);
        String relativePattern = pattern.substring(lastSepIndex + 1);

        // Handle root directory patterns (e.g., /*.txt on Unix)
        if (baseDir.isEmpty() && lastSepIndex == 0) {
            baseDir = "/";
        }

        // Handle Windows drive root paths (e.g., C:/*.txt)
        if (baseDir.matches("^[A-Za-z]:$")) {
            baseDir = baseDir + "\\";
        }

        return new ExtractBaseResult(baseDir, relativePattern);
    }

    // ======================== ripgrep execution for glob ========================

    /**
     * Execute ripgrep --files for glob matching.
     * Simpler than GrepTool's ripGrep since we only need file listing.
     */
    private List<String> ripGlobFiles(List<String> args, String target) throws Exception {
        // Get ripgrep configuration with fallback logic
        RipgrepConfig config = RipgrepConfig.getRipgrepConfig();

        List<String> command = new ArrayList<>();
        command.add(config.getExecutablePath().toString());
        command.addAll(args);
        command.add(target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (!line.isEmpty()) lines.add(line);
                }
            }

            boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                if (lines.isEmpty()) {
                    throw new RuntimeException("Glob search timed out after 20 seconds. Try a more specific path or pattern.");
                }
                return lines;
            }

            int exitCode = process.exitValue();
            // Exit code 0 = files found, 1 = no files (both success)
            if (exitCode == 0 || exitCode == 1) {
                return lines;
            }

            return lines;

        } catch (IOException e) {
            // Check if this is ENOENT (ripgrep not found)
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program") && e.getMessage().contains("error=2")) {
                log.warn("System ripgrep not found, falling back to vendored ripgrep");
                return ripGlobFilesWithBuiltin(args, target);
            }
            throw e;
        }
    }

    /**
     * Execute ripgrep --files using vendored ripgrep binary.
     */
    private List<String> ripGlobFilesWithBuiltin(List<String> args, String target) throws Exception {
        RipgrepConfig config = RipgrepConfig.getRipgrepConfig();
        String rgCmd = config.getExecutablePath().toString();

        List<String> command = new ArrayList<>();
        command.add(rgCmd);
        command.addAll(args);
        command.add(target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (!line.isEmpty()) lines.add(line);
            }
        }

        boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            if (lines.isEmpty()) {
                throw new RuntimeException("Glob search timed out after 20 seconds. Try a more specific path or pattern.");
            }
            return lines;
        }

        int exitCode = process.exitValue();
        if (exitCode == 0 || exitCode == 1) {
            return lines;
        }

        return lines;
    }

    // ======================== helpers ========================

    /**
     * Convert absolute path to relative path under workspace.
     * Port of path.ts toRelativePath().
     */
    private String toRelativePath(String absolutePath) {
        try {
            Path abs = Path.of(absolutePath);
            if (abs.startsWith(workspace)) {
                Path relative = workspace.relativize(abs);
                return relative.toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        return absolutePath;
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ======================== inner classes ========================

    /** Result of globFiles — port of glob.ts line 72 */
    private static final class GlobResult {
        final List<String> files;
        final boolean truncated;

        GlobResult(List<String> files, boolean truncated) {
            this.files = files;
            this.truncated = truncated;
        }
    }

    /** Result of extractGlobBaseDirectory — port of glob.ts line 17-18 */
    private static final class ExtractBaseResult {
        final String baseDir;
        final String relativePattern;

        ExtractBaseResult(String baseDir, String relativePattern) {
            this.baseDir = baseDir;
            this.relativePattern = relativePattern;
        }
    }
}
