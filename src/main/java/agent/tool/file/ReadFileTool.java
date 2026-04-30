package agent.tool.file;

import agent.tool.Tool;
import utils.PathUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static agent.tool.file.FileSystemTools.*;

// ----------------------------
// ReadFileTool — Port of Claude Code's FileReadTool
//
// Original sources:
//   - tools/FileReadTool/FileReadTool.ts (main implementation)
//   - tools/FileReadTool/prompt.ts (description)
//   - tools/FileReadTool/limits.ts (size/token limits)
//   - utils/readFileInRange.ts (line range reading)
//   - utils/fileStateCache.ts (read state tracking)
//
// Changes from original Java implementation:
//   - Added cat-n format output (line_number + tab + content)
//   - Added file_path parameter (alias for path)
//   - Added offset + limit parameters (Claude Code style)
//   - Added FileStateCache integration (read-before-write + dedup)
//   - Added MAX_LINES_TO_READ = 2000 default
//   - Added file_unchanged dedup
//   - Added BOM stripping
//   - Updated description to match Claude Code prompt
//   - Preserved existing head/tail/start_line/end_line for backward compat
// ----------------------------
public final class ReadFileTool extends Tool {
    /**
     * Port of Claude Code limits.ts: DEFAULT_MAX_OUTPUT_TOKENS * 2 (chars)
     */
    private static final int MAX_OUTPUT_CHARS = 50_000;
    /**
     * Port of Claude Code prompt.ts: MAX_LINES_TO_READ
     */
    private static final int MAX_LINES_TO_READ = 2000;
    /**
     * Port of Claude Code limits.ts: maxSizeBytes (256KB)
     */
    private static final long MAX_FILE_SIZE_BYTES = 256L * 1024;

    /**
     * Port of Claude Code prompt.ts: FILE_UNCHANGED_STUB
     */
    private static final String FILE_UNCHANGED_STUB =
            "File unchanged since last read. The content from the earlier read_file tool_result in this conversation is still current - refer to that instead of re-reading.";

    /**
     * Supported image extensions and their MIME types
     */
    private static final Map<String, String> IMAGE_MIME_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp",
            "ico", "image/x-icon",
            "svg", "image/svg+xml"
    );

    /**
     * Maximum image file size to read (10MB)
     */
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    private final Path workspace;
    private final Path allowedDir;
    private final FileStateCache fileStateCache;

    public ReadFileTool(Path workspace, Path allowedDir) {
        this(workspace, allowedDir, new FileStateCache.NoOp());
    }

    public ReadFileTool(Path workspace, Path allowedDir, FileStateCache fileStateCache) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.fileStateCache = fileStateCache != null ? fileStateCache : new FileStateCache.NoOp();
    }

    @Override
    public String name() {
        return "read_file";
    }

    /**
     * Port of Claude Code's FileReadTool/prompt.ts renderPromptTemplate()
     */
    @Override
    public String description() {
        return String.join("\n", List.of(
                "Reads a file from the local filesystem. You can access any file directly by using this tool.",
                "Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.",
                "",
                "Usage:",
                "- The file_path parameter must be an absolute path, not a relative path",
                "- By default, it reads up to " + MAX_LINES_TO_READ + " lines starting from the beginning of the file",
                "- You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file by not providing these parameters",
                "- Any lines longer than 2000 characters will be truncated",
                "- Results are returned using cat -n format, with line numbers starting at 1",
                "- This tool can only read files, not directories. To read a directory, use an ls command via the Bash tool.",
                // "- This tool can read image files (jpg, jpeg, png, gif, webp, bmp, ico, svg) and returns them as base64 data URIs with MIME type prefix (max 10MB).",
                // [DISABLED] 暂时禁用图片读取功能
                "- You will regularly be asked to read screenshots. If the user provides a path to a screenshot, ALWAYS use this tool to view the file at the path. This tool will work with all temporary file paths.",
                "- If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents."
        ));
    }

    /**
     * Manages its own size limits (port of Claude Code: maxResultSizeChars = Infinity)
     */
    @Override
    public int maxResultSizeChars() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_path", Map.of("type", "string", "description", "The absolute path to the file to read"));
        // Backward compat alias
        props.put("path", Map.of("type", "string", "description", "(alias for file_path) The file path to read"));
        // Claude Code style params
        props.put("offset", Map.of("type", "number", "description", "The line number to start reading from (0-based, optional)"));
        props.put("limit", Map.of("type", "number", "description", "The number of lines to read (optional)"));
        // Legacy params (backward compat)
        props.put("head", Map.of("type", "number", "description", "First N lines (optional, legacy - use limit)"));
        props.put("tail", Map.of("type", "number", "description", "Last N lines (optional)"));
        props.put("start_line", Map.of("type", "number", "description", "Start line (1-based, optional, legacy - use offset)"));
        props.put("end_line", Map.of("type", "number", "description", "End line (1-based, inclusive, optional)"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        // Neither file_path nor path is strictly required (both are optional, at least one needed)
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        // Support both file_path (Claude Code) and path (legacy)
        String filePath = asString(args.get("file_path"));
        if (filePath == null || filePath.isBlank()) filePath = asString(args.get("path"));
        if (filePath == null || filePath.isBlank()) {
            return CompletableFuture.completedFuture("Error: file_path is required");
        }

        // Claude Code style: offset + limit
        Integer offset = asIntOrNull(args.get("offset"));
        Integer limit = asIntOrNull(args.get("limit"));

        // Legacy params
        Integer head = asIntOrNull(args.get("head"));
        Integer tail = asIntOrNull(args.get("tail"));
        Integer startLine = asIntOrNull(args.get("start_line"));
        Integer endLine = asIntOrNull(args.get("end_line"));

        String validate = validateLineReadArgs(head, tail, startLine, endLine);
        if (validate != null) {
            return CompletableFuture.completedFuture(validate);
        }

        boolean hasClaudeCodeParams = offset != null || limit != null;
        boolean hasLegacyParams = head != null || tail != null || startLine != null || endLine != null;
        boolean hasLineLimit = hasClaudeCodeParams || hasLegacyParams;

        try {
            Path resolvedPath = PathUtil.resolvePath(filePath, workspace, allowedDir);
            if (!Files.exists(resolvedPath)) {
                return CompletableFuture.completedFuture("Error: File not found: " + filePath);
            }
            if (!Files.isRegularFile(resolvedPath)) {
                return CompletableFuture.completedFuture("Error: Not a file: " + filePath);
            }

            // --- Check if file is an image ---
            // [DISABLED] 暂时禁用图片读取功能
            // String extension = getFileExtension(resolvedPath.toString());
            // String mimeType = IMAGE_MIME_TYPES.get(extension);
            // if (mimeType != null) {
            //     // Handle image file: read as binary and encode to base64
            //     return readImageAsBase64(resolvedPath, mimeType);
            // }

            // --- Port of Claude Code: file_unchanged dedup ---
            if (!hasLineLimit && fileStateCache.isUnchanged(resolvedPath)) {
                FileStateCache.FileState state = fileStateCache.getState(resolvedPath);
                if (state != null && !state.isPartialView) {
                    return CompletableFuture.completedFuture(FILE_UNCHANGED_STUB);
                }
            }

            // Read pre-check: check file size when no line limit
            if (!hasLineLimit) {
                long fileSize = Files.size(resolvedPath);
                if (fileSize > MAX_FILE_SIZE_BYTES) {
                    long totalLines = countLinesFast(resolvedPath);
                    return CompletableFuture.completedFuture(
                            String.format("Error: File too large (%.1f KB, %d lines). " +
                                            "Use offset/limit parameters to read specific sections, " +
                                            "e.g. offset=0, limit=200 for first 200 lines.",
                                    fileSize / 1024.0, totalLines));
                }
            }

            String content = readFileSmart(resolvedPath);

            // Strip BOM (port of Claude Code readFileInRange)
            if (content != null && content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }

            // Apply line range
            int actualOffset = -1; // track actual offset for cat-n numbering
            if (hasClaudeCodeParams) {
                // Claude Code style: offset (0-based) + limit
                int from = offset != null ? Math.max(0, offset) : 0;
                actualOffset = from;
                List<String> allLines = splitLinesPreserveNewline(content);
                if (from >= allLines.size()) {
                    return CompletableFuture.completedFuture("");
                }
                int to = allLines.size();
                if (limit != null) {
                    to = Math.min(from + limit, allLines.size());
                }
                // Extract lines and join (strip trailing \n from each line for display)
                List<String> selected = allLines.subList(from, to);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < selected.size(); i++) {
                    String line = selected.get(i);
                    if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    sb.append(from + i + 1).append("\t").append(line);
                    if (i < selected.size() - 1) sb.append("\n");
                }
                content = sb.toString();
            } else if (hasLegacyParams) {
                // Legacy style: apply line window, then format as cat-n
                List<String> allLines = splitLinesPreserveNewline(content);
                content = applyLineWindow(content, head, tail, startLine, endLine);
                // For legacy mode, determine the offset for line numbering
                if (startLine != null) {
                    actualOffset = startLine - 1;
                } else if (head != null) {
                    actualOffset = 0;
                } else if (tail != null) {
                    actualOffset = Math.max(0, allLines.size() - tail);
                } else {
                    actualOffset = 0;
                }
                // Format as cat-n
                content = formatCatN(content, actualOffset);
            } else {
                // No line limit: check MAX_LINES_TO_READ
                List<String> allLines = splitLinesPreserveNewline(content);
                if (allLines.size() > MAX_LINES_TO_READ) {
                    // Read first MAX_LINES_TO_READ lines with cat-n format
                    actualOffset = 0;
                    List<String> selected = allLines.subList(0, MAX_LINES_TO_READ);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < selected.size(); i++) {
                        String line = selected.get(i);
                        if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
                        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                        sb.append(i + 1).append("\t").append(line);
                        if (i < selected.size() - 1) sb.append("\n");
                    }
                    content = sb.toString() + "\n\n... (" + (allLines.size() - MAX_LINES_TO_READ) +
                            " more lines below. Use offset/limit to read more.)";
                } else {
                    // Full file in cat-n format
                    content = formatCatN(content, 0);
                }
            }

            // Post-read check: output char count
            if (content.length() > MAX_OUTPUT_CHARS) {
                return CompletableFuture.completedFuture(
                        String.format("Error: Selected content too large (%d chars). " +
                                        "Use offset/limit to narrow the range.",
                                content.length()));
            }

            // --- Port of Claude Code: update FileStateCache ---
            long mtimeMs = Files.getLastModifiedTime(resolvedPath).toMillis();
            long sizeBytes = Files.size(resolvedPath);
            fileStateCache.markRead(resolvedPath, content, mtimeMs, sizeBytes);

            return CompletableFuture.completedFuture(content);

        } catch (SecurityException se) {
            return CompletableFuture.completedFuture("Error: " + se.getMessage());
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error reading file: " + e.getMessage());
        }
    }

    /**
     * Read an image file and return as base64 data URI
     */
    private CompletableFuture<String> readImageAsBase64(Path filePath, String mimeType) {
        try {
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_IMAGE_SIZE_BYTES) {
                return CompletableFuture.completedFuture(
                        String.format("Error: Image file too large (%.1f MB, max 10 MB)", fileSize / (1024.0 * 1024.0)));
            }

            byte[] bytes = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String dataUri = "data:" + mimeType + ";base64," + base64;

            return CompletableFuture.completedFuture(dataUri);
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error reading image file: " + e.getMessage());
        }
    }

    /**
     * Get file extension in lowercase
     */
    private static String getFileExtension(String filename) {
        if (filename == null) return null;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= filename.length() - 1) return null;
        return filename.substring(lastDot + 1).toLowerCase();
    }

    /**
     * Format content in cat-n format: line_number + tab + content. Port of Claude Code ReadTool output.
     */
    private static String formatCatN(String content, int offset) {
        List<String> lines = splitLinesPreserveNewline(content);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            sb.append(offset + i + 1).append("\t").append(line);
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Fast line count (port of Claude Code readFileInRange stat-based)
     */
    private static long countLinesFast(Path filePath) throws Exception {
        try (var lines = Files.lines(filePath)) {
            return lines.count();
        }
    }
}