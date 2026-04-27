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
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Grep tool — atomic-level port of Claude Code's GrepTool.ts.
 * <p>
 * Uses ripgrep (rg) for fast regex-based file content search.
 * Supports three output modes:
 * - files_with_matches (default): returns file paths sorted by modification time
 * - content: returns matching lines with optional context
 * - count: returns match counts per file
 * <p>
 * Line-by-line correspondence with src/tools/GrepTool/GrepTool.ts:
 * - inputSchema → parameters()
 * - VCS_DIRECTORIES_TO_EXCLUDE → VCS_DIRECTORIES_TO_EXCLUDE
 * - DEFAULT_HEAD_LIMIT → DEFAULT_HEAD_LIMIT
 * - applyHeadLimit → applyHeadLimit()
 * - formatLimitInfo → formatLimitInfo()
 * - call() → execute()
 * - mapToolResultToToolResultBlockParam → result formatting in execute()
 */
@Slf4j
public final class GrepTool extends Tool {

    // ---------- Line 95-102: VCS_DIRECTORIES_TO_EXCLUDE ----------
    private static final List<String> VCS_DIRECTORIES_TO_EXCLUDE = List.of(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl"
    );

    // ---------- Line 107-108: DEFAULT_HEAD_LIMIT ----------
    // Default cap on grep results when head_limit is unspecified.
    // 250 is generous enough for exploratory searches while preventing context bloat.
    // Pass head_limit=0 explicitly for unlimited.
    private static final int DEFAULT_HEAD_LIMIT = 250;

    // ---------- Line 164: maxResultSizeChars = 20_000 ----------
    // 20K chars - tool result persistence threshold (matches Claude Code)
    private static final int MAX_RESULT_SIZE_CHARS = 20_000;

    private final Path workspace;
    private final Path allowedDir;
    private final Supplier<ProjectRegistry> projectRegistrySupplier;

    public GrepTool(Path workspace, Path allowedDir, Supplier<ProjectRegistry> projectRegistrySupplier) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.projectRegistrySupplier = projectRegistrySupplier;
    }

    // ---------- Line 169: userFacingName → "Search" ----------
    @Override
    public String name() {
        return "Grep";
    }

    // ---------- Line 7-17: getDescription() ----------
    @Override
    public String description() {
        return "A powerful search tool built on ripgrep\n"
                + "\n"
                + "Usage:\n"
                + "- ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.\n"
                + "- Supports full regex syntax (e.g., \"log.*Error\", \"function\\s+\\w+\")\n"
                + "- Filter files with glob parameter (e.g., \"*.js\", \"**/*.tsx\") or type parameter (e.g., \"js\", \"py\", \"rust\")\n"
                + "- Output modes: \"content\" shows matching lines, \"files_with_matches\" shows only file paths (default), \"count\" shows match counts\n"
                + "- Pattern syntax: Uses ripgrep (not grep) - literal braces need escaping (use `interface\\{\\}` to find `interface{}` in Go code)\n"
                + "- Multiline matching: By default patterns match within single lines only. For cross-line patterns like `struct \\{[\\s\\S]*?field`, use multiline: true\n";
    }

    @Override
    public int maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    // ---------- Line 33-90: inputSchema ----------
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of(
                "type", "string",
                "description", "The regular expression pattern to search for in file contents"
        ));
        props.put("path", Map.of(
                "type", "string",
                "description", "File or directory to search in (rg PATH). Defaults to current project directory(main agent project). if current project dir is null -> workspace directory"
        ));
        props.put("glob", Map.of(
                "type", "string",
                "description", "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob"
        ));
        props.put("output_mode", Map.of(
                "type", "string",
                "description", "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".",
                "enum", List.of("content", "files_with_matches", "count")
        ));
        props.put("-B", Map.of(
                "type", "number",
                "description", "Number of lines to show before each match (rg -B). Requires output_mode: \"content\", ignored otherwise."
        ));
        props.put("-A", Map.of(
                "type", "number",
                "description", "Number of lines to show after each match (rg -A). Requires output_mode: \"content\", ignored otherwise."
        ));
        props.put("-C", Map.of(
                "type", "number",
                "description", "Alias for context."
        ));
        props.put("context", Map.of(
                "type", "number",
                "description", "Number of lines to show before and after each match (rg -C). Requires output_mode: \"content\", ignored otherwise."
        ));
        props.put("-n", Map.of(
                "type", "boolean",
                "description", "Show line numbers in output (rg -n). Requires output_mode: \"content\", ignored otherwise. Defaults to true."
        ));
        props.put("-i", Map.of(
                "type", "boolean",
                "description", "Case insensitive search (rg -i)"
        ));
        props.put("type", Map.of(
                "type", "string",
                "description", "File type to search (rg --type). Common types: js, py, rust, go, java, etc. More efficient than include for standard file types."
        ));
        props.put("head_limit", Map.of(
                "type", "number",
                "description", "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults to 250 when unspecified. Pass 0 for unlimited (use sparingly — large result sets waste context)."
        ));
        props.put("offset", Map.of(
                "type", "number",
                "description", "Skip first N lines/entries before applying head_limit, equivalent to \"| tail -n +N | head -N\". Works across all output modes. Defaults to 0."
        ));
        props.put("multiline", Map.of(
                "type", "boolean",
                "description", "Enable multiline mode where . matches newlines and patterns can span lines (rg -U --multiline-dotall). Default: false."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("pattern"));
        return schema;
    }

    // ---------- Line 110-128: applyHeadLimit ----------
    /**
     * Apply head_limit and offset to a list of items.
     * Port of GrepTool.ts applyHeadLimit().
     */
    private static <T> HeadLimitResult<T> applyHeadLimit(List<T> items, Integer limit, int offset) {
        // Explicit 0 = unlimited escape hatch
        if (limit != null && limit == 0) {
            return new HeadLimitResult<>(items.subList(Math.min(offset, items.size()), items.size()), null);
        }
        int effectiveLimit = (limit != null) ? limit : DEFAULT_HEAD_LIMIT;
        int from = Math.min(offset, items.size());
        int to = Math.min(offset + effectiveLimit, items.size());
        List<T> sliced = items.subList(from, to);
        // Only report appliedLimit when truncation actually occurred
        boolean wasTruncated = items.size() - offset > effectiveLimit;
        return new HeadLimitResult<>(sliced, wasTruncated ? effectiveLimit : null);
    }

    // ---------- Line 130-142: formatLimitInfo ----------
    /**
     * Format limit/offset information for display in tool results.
     * Port of GrepTool.ts formatLimitInfo().
     */
    private static String formatLimitInfo(Integer appliedLimit, Integer appliedOffset) {
        List<String> parts = new ArrayList<>();
        if (appliedLimit != null) parts.add("limit: " + appliedLimit);
        if (appliedOffset != null && appliedOffset > 0) parts.add("offset: " + appliedOffset);
        return String.join(", ", parts);
    }

    // ---------- Line 310-577: call() ----------
    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        try {
            // ---------- Line 312-326: parse input ----------
            String pattern = asString(args.get("pattern"));
            String path = asString(args.get("path"));
            String glob = asString(args.get("glob"));
            String type = asString(args.get("type"));
            String outputMode = asString(args.get("output_mode"));
            if (outputMode.isEmpty()) outputMode = "files_with_matches";

            Integer contextBefore = asIntOrNull(args.get("-B"));
            Integer contextAfter = asIntOrNull(args.get("-A"));
            Integer contextC = asIntOrNull(args.get("-C"));
            Integer contextUnified = asIntOrNull(args.get("context"));
            boolean showLineNumbers = asBool(args.get("-n"), true);
            boolean caseInsensitive = asBool(args.get("-i"), false);
            Integer headLimit = asIntOrNull(args.get("head_limit"));
            int offset = asIntOrDefault(args.get("offset"), 0);
            boolean multiline = asBool(args.get("multiline"), false);

            // ---------- Line 329: resolve absolute path ----------
            Path absolutePath;
            if (path != null && !path.isEmpty()) {
                //absolutePath = PathUtil.resolvePath(path, workspace, allowedDir);
                absolutePath = Paths.get(path);
            } else {
                // 先从主项目中配置
                ProjectRegistry registry = projectRegistrySupplier.get();
                String mainProjectPath = registry.getMainProjectPath();
                if (StrUtil.isBlank(mainProjectPath)) {
                    absolutePath = workspace;
                }else {
                    absolutePath = Paths.get(mainProjectPath);
                }
            }

            // Validate path exists
            if (!Files.exists(absolutePath)) {
                return CompletableFuture.completedFuture(
                        "Error: Path does not exist: " + path + ". Current working directory: " + workspace);
            }

            // ---------- Line 330: build args ----------
            List<String> rgArgs = new ArrayList<>();
            rgArgs.add("--hidden");

            // ---------- Line 333-335: exclude VCS directories ----------
            for (String dir : VCS_DIRECTORIES_TO_EXCLUDE) {
                rgArgs.add("--glob");
                rgArgs.add("!" + dir);
            }

            // ---------- Line 338: max-columns ----------
            rgArgs.add("--max-columns");
            rgArgs.add("500");

            // ---------- Line 341-343: multiline ----------
            if (multiline) {
                rgArgs.add("-U");
                rgArgs.add("--multiline-dotall");
            }

            // ---------- Line 346-348: case insensitive ----------
            if (caseInsensitive) {
                rgArgs.add("-i");
            }

            // ---------- Line 351-355: output mode flags ----------
            if ("files_with_matches".equals(outputMode)) {
                rgArgs.add("-l");
            } else if ("count".equals(outputMode)) {
                rgArgs.add("-c");
            }

            // ---------- Line 358-360: line numbers ----------
            if (showLineNumbers && "content".equals(outputMode)) {
                rgArgs.add("-n");
            }

            // ---------- Line 363-376: context flags ----------
            if ("content".equals(outputMode)) {
                if (contextUnified != null) {
                    rgArgs.add("-C");
                    rgArgs.add(contextUnified.toString());
                } else if (contextC != null) {
                    rgArgs.add("-C");
                    rgArgs.add(contextC.toString());
                } else {
                    if (contextBefore != null) {
                        rgArgs.add("-B");
                        rgArgs.add(contextBefore.toString());
                    }
                    if (contextAfter != null) {
                        rgArgs.add("-A");
                        rgArgs.add(contextAfter.toString());
                    }
                }
            }

            // ---------- Line 379-384: pattern with -e flag for dash-prefixed patterns ----------
            if (pattern.startsWith("-")) {
                rgArgs.add("-e");
                rgArgs.add(pattern);
            } else {
                rgArgs.add(pattern);
            }

            // ---------- Line 387-389: type filter ----------
            if (type != null && !type.isEmpty()) {
                rgArgs.add("--type");
                rgArgs.add(type);
            }

            // ---------- Line 391-409: glob patterns ----------
            if (glob != null && !glob.isEmpty()) {
                // Split on commas and spaces, but preserve patterns with braces
                List<String> globPatterns = new ArrayList<>();
                String[] rawPatterns = glob.split("\\s+");

                for (String rawPattern : rawPatterns) {
                    // If pattern contains braces, don't split further
                    if (rawPattern.contains("{") && rawPattern.contains("}")) {
                        globPatterns.add(rawPattern);
                    } else {
                        // Split on commas for patterns without braces
                        for (String p : rawPattern.split(",")) {
                            if (!p.isEmpty()) globPatterns.add(p);
                        }
                    }
                }

                for (String globPattern : globPatterns) {
                    if (!globPattern.isEmpty()) {
                        rgArgs.add("--glob");
                        rgArgs.add(globPattern);
                    }
                }
            }

            // ---------- Line 441: execute ripgrep ----------
            List<String> results = ripGrep(rgArgs, absolutePath.toString());

            // ---------- Line 443-476: content mode ----------
            if ("content".equals(outputMode)) {
                // Apply head_limit first — relativize is per-line work, so
                // avoid processing lines that will be discarded
                HeadLimitResult<String> limited = applyHeadLimit(results, headLimit, offset);

                List<String> finalLines = new ArrayList<>();
                for (String line : limited.items) {
                    // Lines have format: /absolute/path:line_content or /absolute/path:num:content
                    // Handle Windows paths like C:/path/file.java:10:content
                    LineSplitResult split = splitGrepOutputLine(line);
                    if (!split.rest.isEmpty()) {
                        finalLines.add(toRelativePath(split.path) + split.rest);
                    } else {
                        finalLines.add(line);
                    }
                }

                String content = String.join("\n", finalLines);
                String limitInfo = formatLimitInfo(limited.appliedLimit, offset > 0 ? offset : null);

                // ---------- Line 254-309: mapToolResultToToolResultBlockParam ----------
                String resultContent = content.isEmpty() ? "No matches found" : content;
                if (!limitInfo.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            resultContent + "\n\n[Showing results with pagination = " + limitInfo + "]");
                }
                return CompletableFuture.completedFuture(resultContent);
            }

            // ---------- Line 478-524: count mode ----------
            if ("count".equals(outputMode)) {
                HeadLimitResult<String> limited = applyHeadLimit(results, headLimit, offset);

                List<String> finalCountLines = new ArrayList<>();
                for (String line : limited.items) {
                    // Lines have format: /absolute/path:count
                    int colonIndex = line.lastIndexOf(':');
                    if (colonIndex > 0) {
                        String filePath = line.substring(0, colonIndex);
                        String count = line.substring(colonIndex);
                        finalCountLines.add(toRelativePath(filePath) + count);
                    } else {
                        finalCountLines.add(line);
                    }
                }

                // Parse count output to extract total matches and file count
                int totalMatches = 0;
                int fileCount = 0;
                for (String line : finalCountLines) {
                    int colonIndex = line.lastIndexOf(':');
                    if (colonIndex > 0) {
                        String countStr = line.substring(colonIndex + 1);
                        try {
                            totalMatches += Integer.parseInt(countStr.trim());
                            fileCount += 1;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                String rawContent = finalCountLines.isEmpty() ? "No matches found" : String.join("\n", finalCountLines);
                String limitInfo = formatLimitInfo(limited.appliedLimit, offset > 0 ? offset : null);
                String occurrenceWord = totalMatches == 1 ? "occurrence" : "occurrences";
                String fileWord = fileCount == 1 ? "file" : "files";
                String summary = "\n\nFound " + totalMatches + " total " + occurrenceWord + " across " + fileCount + " " + fileWord + ".";
                if (!limitInfo.isEmpty()) {
                    summary += " with pagination = " + limitInfo;
                }
                return CompletableFuture.completedFuture(rawContent + summary);
            }

            // ---------- Line 526-576: files_with_matches mode (default) ----------
            // Sort by modification time
            List<PathSize> stats = new ArrayList<>();
            for (String result : results) {
                try {
                    Path p = Path.of(result);
                    FileTime mtime = Files.getLastModifiedTime(p);
                    stats.add(new PathSize(p, mtime.toMillis()));
                } catch (IOException e) {
                    // File deleted between ripgrep's scan and stat — sort as mtime 0
                    stats.add(new PathSize(Path.of(result), 0L));
                }
            }

            // Sort by modification time (newest first), then by filename as tiebreaker
            stats.sort((a, b) -> {
                int timeComparison = Long.compare(b.mtimeMs, a.mtimeMs);
                if (timeComparison == 0) {
                    return a.path.toString().compareTo(b.path.toString());
                }
                return timeComparison;
            });

            List<String> sortedMatches = stats.stream()
                    .map(ps -> ps.path.toString())
                    .collect(Collectors.toList());

            // Apply head_limit to sorted file list
            HeadLimitResult<String> limited = applyHeadLimit(sortedMatches, headLimit, offset);

            // Convert absolute paths to relative paths to save tokens
            List<String> relativeMatches = new ArrayList<>();
            for (String abs : limited.items) {
                relativeMatches.add(toRelativePath(abs));
            }

            int numFiles = relativeMatches.size();

            // ---------- Line 293-308: files_with_matches result ----------
            if (numFiles == 0) {
                return CompletableFuture.completedFuture("No files found");
            }

            String limitInfo = formatLimitInfo(limited.appliedLimit, offset > 0 ? offset : null);
            String fileWord = numFiles == 1 ? "file" : "files";
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(numFiles).append(" ").append(fileWord);
            if (!limitInfo.isEmpty()) {
                sb.append(" ").append(limitInfo);
            }
            sb.append("\n");
            sb.append(String.join("\n", relativeMatches));
            return CompletableFuture.completedFuture(sb.toString());

        } catch (SecurityException se) {
            return CompletableFuture.completedFuture("Error: " + se.getMessage());
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error searching files: " + e.getMessage());
        }
    }

    // ======================== ripgrep execution ========================

    /**
     * Execute ripgrep with the given arguments.
     * Port of ripgrep.ts ripGrep() — core execution logic.
     *
     * Line 345-463 of ripgrep.ts:
     * - Builds process with rg command
     * - Handles stdout/stderr
     * - Exit code 0 = matches found, 1 = no matches (both success)
     * - Returns list of output lines (empty list for no matches)
     */
    private List<String> ripGrep(List<String> args, String target) throws Exception {
        // Get ripgrep configuration with fallback logic
        RipgrepConfig config = RipgrepConfig.getRipgrepConfig();

        // Build command: rg [args] [target]
        List<String> command = new ArrayList<>();
        command.add(config.getExecutablePath().toString());
        command.addAll(args);
        command.add(target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        try {
            // 20s timeout (matching Claude Code default)
            Process process = pb.start();

            // Read stdout
            List<String> lines = readProcessOutput(process);

            // Wait with timeout
            boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                // For timeouts with no results, throw so caller knows search didn't complete
                if (lines.isEmpty()) {
                    throw new RuntimeException("Ripgrep search timed out after 20 seconds. Try searching a more specific path or pattern.");
                }
                // For timeouts with partial results, drop last line (may be incomplete)
                if (!lines.isEmpty()) {
                    lines.remove(lines.size() - 1);
                }
                return lines;
            }

            int exitCode = process.exitValue();

            // Exit code 0 = matches found, 1 = no matches (both are success)
            if (exitCode == 0 || exitCode == 1) {
                return lines;
            }

            // Exit code 2 = ripgrep error; check for EAGAIN
            if (exitCode == 2) {
                // Check stderr
                String stderr = getStderr(process);
                if (stderr.contains("os error 11") || stderr.contains("Resource temporarily unavailable")) {
                    // Retry with single-threaded mode (-j 1)
                    return ripGrepWithSingleThread(args, target);
                }
            }

            // For other errors, return whatever we got
            return lines;

        } catch (IOException e) {
            // Check if this is ENOENT (ripgrep not found)
            if (e.getMessage() != null && e.getMessage().contains("Cannot run program") && e.getMessage().contains("error=2")) {
                log.warn("System ripgrep not found, falling back to vendored ripgrep");
                return ripGrepWithBuiltin(args, target);
            }
            throw e;
        }
    }

    /**
     * Retry ripgrep with single-threaded mode for EAGAIN errors.
     */
    private List<String> ripGrepWithSingleThread(List<String> args, String target) throws Exception {
        RipgrepConfig config = RipgrepConfig.getRipgrepConfig();

        List<String> command = new ArrayList<>();
        command.add(config.getExecutablePath().toString());
        command.add("-j");
        command.add("1");
        command.addAll(args);
        command.add(target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        List<String> lines = readProcessOutput(process);
        process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        int exitCode = process.exitValue();
        if (exitCode == 0 || exitCode == 1) {
            return lines;
        }
        return lines;
    }

    /**
     * Execute ripgrep using the vendored (builtin) ripgrep binary.
     */
    private List<String> ripGrepWithBuiltin(List<String> args, String target) throws Exception {
        RipgrepConfig config = RipgrepConfig.getRipgrepConfig();

        // Use the binary path from config
        String rgCmd = config.getExecutablePath().toString();

        List<String> command = new ArrayList<>();
        command.add(rgCmd);
        command.addAll(args);
        command.add(target);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // Read stdout
        List<String> lines = readProcessOutput(process);

        // Wait with timeout
        boolean finished = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            if (lines.isEmpty()) {
                throw new RuntimeException("Ripgrep search timed out after 20 seconds. Try searching a more specific path or pattern.");
            }
            if (!lines.isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines;
        }

        int exitCode = process.exitValue();
        if (exitCode == 0 || exitCode == 1) {
            return lines;
        }

        // For other errors, return whatever we got
        return lines;
    }

    /**
     * Read process stdout and return as list of lines.
     */
    private static List<String> readProcessOutput(Process process) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Strip trailing \r for Windows compatibility
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Get stderr from a process.
     */
    private static String getStderr(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
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

    /**
     * Split ripgrep content output line into path and rest (line number + content).
     * Handles Windows drive letters (e.g., C:/path/file.java:10:content).
     * On Unix: splits at first colon (after /path/file.java)
     * On Windows: splits at colon after filename (skipping drive letter colon)
     */
    private static LineSplitResult splitGrepOutputLine(String line) {
        // Try to extract filename to find the correct colon position
        int filenameColonIndex = -1;
        try {
            // Handle both Unix and Windows path separators
            String normalized = line.replace('\\', '/');
            int lastSlash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf(':'));
            if (lastSlash >= 0 && lastSlash < line.length() - 1) {
                String filename = line.substring(lastSlash + 1);
                int colonAfterFilename = line.indexOf(':', lastSlash + 1);
                if (colonAfterFilename > lastSlash) {
                    filenameColonIndex = colonAfterFilename;
                }
            }
        } catch (Exception ignored) {
        }

        if (filenameColonIndex > 0) {
            return new LineSplitResult(line.substring(0, filenameColonIndex), line.substring(filenameColonIndex));
        }

        // Fallback: use first colon
        int colonIndex = line.indexOf(':');
        if (colonIndex > 0) {
            return new LineSplitResult(line.substring(0, colonIndex), line.substring(colonIndex));
        }
        return new LineSplitResult(line, "");
    }

    private static class LineSplitResult {
        final String path;
        final String rest;
        LineSplitResult(String path, String rest) {
            this.path = path;
            this.rest = rest;
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Integer asIntOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int asIntOrDefault(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static boolean asBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    // ======================== inner classes ========================

    /** Result of applyHeadLimit — port of GrepTool.ts line 110-128 */
    private static final class HeadLimitResult<T> {
        final List<T> items;
        final Integer appliedLimit; // null when no truncation occurred

        HeadLimitResult(List<T> items, Integer appliedLimit) {
            this.items = items;
            this.appliedLimit = appliedLimit;
        }
    }

    /** Path + mtime pair for sorting */
    private static final class PathSize {
        final Path path;
        final long mtimeMs;

        PathSize(Path path, long mtimeMs) {
            this.path = path;
            this.mtimeMs = mtimeMs;
        }
    }
}
