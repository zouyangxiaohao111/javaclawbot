package agent.tool.file;

import agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import utils.PathUtil;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * EditTool_new -- full atomic port of Claude Code's FileEditTool.
 *
 * Source mapping:
 *   FileEditTool.ts    -> this class (validateInput + call)
 *   prompt.ts          -> description()
 *   types.ts           -> parameters()
 *   constants.ts       -> TOOL_NAME, FILE_UNEXPECTEDLY_MODIFIED_ERROR
 *   utils.ts           -> getPatchForEdit, applyEditToFile (via FileSystemTools)
 *
 * Key features ported:
 *   - Exact string replacements in existing files
 *   - read-before-write enforcement via FileStateCache
 *   - file modification time staleness check with content fallback
 *   - Quote normalization (curly vs straight) via findActualString/preserveQuoteStyle
 *   - Uniqueness check when replace_all=false
 *   - .ipynb redirect error
 *   - Structured patch output (unified diff)
 *   - Encoding/line ending/BOM preservation
 *   - File size limit (1 GiB)
 *   - File not found with suggestion
 *   - stripTrailingWhitespace for non-markdown files
 */
@lombok.extern.slf4j.Slf4j
public final class EditTool extends Tool {

    // ---- Port of constants.ts ----
    static final String TOOL_NAME = "edit_file";
    static final String FILE_UNEXPECTEDLY_MODIFIED_ERROR =
            "File has been unexpectedly modified. Read it again before attempting to write it.";
    static final String FILE_NOT_FOUND_CWD_NOTE = "NOTE: file_path must be an absolute path.";

    /** Port of MAX_EDIT_FILE_SIZE = 1 GiB (V8 string length guard) */
    private static final long MAX_EDIT_FILE_SIZE = 1024L * 1024 * 1024;

    private final Path workspace;
    private final Path allowedDir;
    private final FileStateCache fileStateCache;

    public EditTool(Path workspace, Path allowedDir, FileStateCache fileStateCache) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.fileStateCache = fileStateCache != null ? fileStateCache : new FileStateCache.NoOp();
    }

    /** Backward-compatible constructor (no cache enforcement) */
    public EditTool(Path workspace, Path allowedDir) {
        this(workspace, allowedDir, new FileStateCache.NoOp());
    }

    // ---- Port of TOOL_NAME ----
    @Override
    public String name() {
        return TOOL_NAME;
    }

    // ---- Port of prompt.ts getEditToolDescription() ----
    @Override
    public String description() {
        return String.join("\n", List.of(
            "Performs exact string replacements in files.",
            "",
            "Usage:",
            "- You must use your `read_file` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.",
            "- When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: spaces + line number + tab. Everything after that tab is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.",
            "- ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.",
            "- Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.",
            "- The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.",
            "- Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance."
        ));
    }

    @Override
    public int maxResultSizeChars() {
        return 100_000;
    }

    // ---- Port of types.ts inputSchema ----
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_path", Map.of(
                "type", "string",
                "description", "The absolute path to the file to modify"
        ));
        props.put("old_string", Map.of(
                "type", "string",
                "description", "The text to replace"
        ));
        props.put("new_string", Map.of(
                "type", "string",
                "description", "The text to replace it with (must be different from old_string)"
        ));
        props.put("replace_all", Map.of(
                "type", "boolean",
                "description", "Replace all occurrences of old_string (default false)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("file_path", "old_string", "new_string"));
        return schema;
    }

    /**
     * Port of FileEditTool.ts validateInput() + call().
     *
     * Execution flow mirrors the TS source exactly:
     *
     * validateInput checks (in order):
     *   1. checkTeamMemSecrets (skipped - no team memory in Java agent)
     *   2. old_string === new_string -> error (behavior: ask, errorCode: 1)
     *   3. Permission deny rule check (skipped - Java uses allowedDir whitelist)
     *   4. UNC path skip (skipped - Java resolves paths differently)
     *   5. File size > MAX_EDIT_FILE_SIZE -> error (errorCode: 10)
     *   6. Read file content with encoding detection
     *   7. File doesn't exist + non-empty old_string -> error (errorCode: 4)
     *   8. File exists + empty old_string + non-empty content -> error (errorCode: 3)
     *   9. .ipynb redirect -> error (errorCode: 5)
     *   10. Read-before-write check -> error (errorCode: 6, meta: isFilePathAbsolute)
     *   11. Mtime staleness check -> error (errorCode: 7, content fallback for full reads)
     *   12. findActualString -> error if not found (errorCode: 8)
     *   13. Multiple matches + !replace_all -> error (errorCode: 9)
     *   14. validateInputForSettingsFileEdit (skipped - no settings files in Java agent)
     *
     * call() steps:
     *   1. Discover skills from path (skipped)
     *   2. diagnosticTracker.beforeFileEdited (skipped)
     *   3. Create parent directories
     *   4. File history backup (skipped - no file history in Java agent)
     *   5. Load current state (readFileForEdit)
     *   6. Mtime staleness check (runtime throw)
     *   7. findActualString + preserveQuoteStyle
     *   8. getPatchForEdit (structured diff)
     *   9. writeTextContent (preserve encoding/line endings/BOM)
     *   10. LSP notification (skipped)
     *   11. Update FileStateCache (re-mark as read)
     *   12. Return result
     */
    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String filePath = FileSystemTools.asString(args.get("file_path"));
        String oldString = FileSystemTools.asString(args.get("old_string"));
        String newString = FileSystemTools.asString(args.get("new_string"));
        boolean replaceAll = FileSystemTools.asBool(args.get("replace_all"), false);

        log.info("执行工具: edit_file, 参数: file_path={}, replace_all={}", filePath, replaceAll);

        if (filePath == null) {
            return CompletableFuture.completedFuture("Error: file_path is required");
        }
        if (oldString == null) oldString = "";
        if (newString == null) newString = "";

        // ---- validateInput: old_string === new_string (errorCode: 1) ----
        if (oldString.equals(newString)) {
            return CompletableFuture.completedFuture(
                    "Error: No changes to make: old_string and new_string are exactly the same.");
        }

        try {
            Path resolvedPath = PathUtil.resolvePath(filePath, workspace, allowedDir);
            log.debug("解析文件路径: {}", resolvedPath);

            // ---- validateInput: file size check (errorCode: 10) ----
            if (Files.exists(resolvedPath)) {
                long fileSize = Files.size(resolvedPath);
                if (fileSize > MAX_EDIT_FILE_SIZE) {
                    log.warn("文件过大无法编辑: {}, 大小: {} MB", resolvedPath, fileSize / (1024.0 * 1024.0));
                    return CompletableFuture.completedFuture(
                            String.format("Error: File is too large to edit (%.1f MB). Maximum editable file size is 1024 MB.",
                                    fileSize / (1024.0 * 1024.0)));
                }
            }

            // ---- readFileForEdit: read file content with encoding detection ----
            // Port of CC's readFileForEdit() helper function
            log.debug("读取文件内容: {}", resolvedPath);
            ReadFileResult readResult = readFileForEdit(resolvedPath);
            String fileContent = readResult.content;
            boolean fileExists = readResult.fileExists;
            Charset fileCharset = readResult.encoding;
            String targetLineEnding = readResult.lineEndings;
            boolean preserveBom = readResult.hasBom;

            // ---- validateInput: file doesn't exist (errorCode: 4) ----
            if (!fileExists) {
                if (oldString.isEmpty()) {
                    // Empty old_string on nonexistent file = new file creation (valid)
                } else {
                    // Port of CC's findSimilarFile + suggestPathUnderCwd
                    String suggestion = findSimilarFile(resolvedPath);
                    String message = "Error: File does not exist. " + FILE_NOT_FOUND_CWD_NOTE;
                    if (suggestion != null) {
                        message += " Did you mean " + suggestion + "?";
                    }
                    return CompletableFuture.completedFuture(message);
                }
            }

            // ---- validateInput: existing file with empty old_string (errorCode: 3) ----
            if (fileExists && oldString.isEmpty()) {
                if (!fileContent.trim().isEmpty()) {
                    return CompletableFuture.completedFuture(
                            "Error: Cannot create new file - file already exists.");
                }
            }

            // ---- validateInput: .ipynb redirect (errorCode: 5) ----
            if (resolvedPath.toString().endsWith(".ipynb")) {
                return CompletableFuture.completedFuture(
                        "Error: File is a Jupyter Notebook. Use the NotebookEditTool to edit this file.");
            }

            // ---- validateInput: read-before-write enforcement (errorCode: 6) ----
            // Port of: if (!readTimestamp || readTimestamp.isPartialView)
            FileStateCache.FileState readState = fileStateCache.getState(resolvedPath);
            if (readState == null || readState.isPartialView) {
                return CompletableFuture.completedFuture(
                        "Error: File has not been read yet. Read it first before writing to it. " +
                        "Use the read_file tool to read " + filePath);
            }

            // ---- validateInput: mtime staleness check (errorCode: 7) ----
            // Port of CC's dual-path staleness check:
            //   validateInput uses readTimestamp.timestamp vs getFileModificationTime
            //   call() also rechecks and throws FILE_UNEXPECTEDLY_MODIFIED_ERROR
            if (fileExists) {
                if (readState != null) {
                    long currentMtime = Files.getLastModifiedTime(resolvedPath).toMillis();
                    if (currentMtime > readState.timestamp) {
                        // Timestamp indicates modification, but on Windows timestamps can change
                        // without content changes (cloud sync, antivirus, etc.). For full reads,
                        // compare content as a fallback to avoid false positives.
                        boolean isFullRead = readState.offset == null && readState.limit == null;
                        boolean contentUnchanged = isFullRead &&
                                normalizeContentForCompare(fileContent).equals(
                                        normalizeContentForCompare(readState.content));
                        if (!contentUnchanged) {
                            return CompletableFuture.completedFuture(
                                    "Error: " + FILE_UNEXPECTEDLY_MODIFIED_ERROR);
                        }
                    }
                }
            }

            // ---- Normalize line endings for matching (port of CC's .replaceAll('\r\n', '\n')) ----
            String normalizedContent = fileContent.replace("\r\n", "\n").replace("\r", "\n");

            // ---- Port of normalizeFileEditInput: stripTrailingWhitespace ----
            // CC strips trailing whitespace from new_string for non-markdown files
            String normalizedNewString = newString.replace("\r\n", "\n").replace("\r", "\n");
            boolean isMarkdown = resolvedPath.toString().toLowerCase().endsWith(".md")
                    || resolvedPath.toString().toLowerCase().endsWith(".mdx");
            if (!isMarkdown) {
                normalizedNewString = stripTrailingWhitespace(normalizedNewString);
            }

            String normalizedOldString = oldString.replace("\r\n", "\n").replace("\r", "\n");

            // ---- validateInput: findActualString with quote normalization (errorCode: 8) ----
            String actualOldString = FileSystemTools.findActualString(normalizedContent, normalizedOldString);
            if (actualOldString == null) {
                return CompletableFuture.completedFuture(
                        "Error: String to replace not found in file.\nString: " + oldString);
            }

            // ---- validateInput: uniqueness check (errorCode: 9) ----
            if (!replaceAll) {
                int matches = countMatches(normalizedContent, actualOldString);
                if (matches > 1) {
                    return CompletableFuture.completedFuture(
                            "Error: Found " + matches + " matches of the string to replace, but replace_all is false. " +
                            "To replace all occurrences, set replace_all to true. " +
                            "To replace only one occurrence, please provide more context to uniquely identify the instance.\n" +
                            "String: " + oldString);
                }
            }

            // ---- call: preserveQuoteStyle ----
            String actualNewString = FileSystemTools.preserveQuoteStyle(
                    normalizedOldString, actualOldString, normalizedNewString);

            // ---- call: getPatchForEdit (structured patch) ----
            // Port of CC's getPatchForEdit which returns {patch, updatedFile}
            String updatedContent;
            if (normalizedOldString.isEmpty()) {
                updatedContent = actualNewString;
            } else if (replaceAll) {
                updatedContent = normalizedContent.replace(actualOldString, actualNewString);
            } else {
                int idx = normalizedContent.indexOf(actualOldString);
                updatedContent = normalizedContent.substring(0, idx)
                        + actualNewString
                        + normalizedContent.substring(idx + actualOldString.length());
            }

            // Generate unified diff patch (port of CC's getPatchFromContents)
            String patchOutput = generatePatch(resolvedPath.getFileName().toString(),
                    normalizedContent, updatedContent);

            // ---- call: writeTextContent (preserve encoding/line endings/BOM) ----
            log.debug("写入文件内容: {}", resolvedPath);
            String finalContent = FileSystemTools.normalizeLineEndings(updatedContent, targetLineEnding);
            Path parent = resolvedPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            writeFilePreserving(resolvedPath, finalContent, fileCharset, preserveBom);
            log.debug("文件写入成功: {}", resolvedPath);

            // ---- call: update FileStateCache (re-mark as read) ----
            // Port of: readFileState.set(absoluteFilePath, {content, timestamp, offset, limit})
            long newMtime = Files.getLastModifiedTime(resolvedPath).toMillis();
            long newSize = Files.size(resolvedPath);
            fileStateCache.invalidate(resolvedPath);
            fileStateCache.markRead(resolvedPath, updatedContent, newMtime, newSize);

            // ---- mapToolResultToToolResultBlockParam ----
            // Port of CC's mapToolResultToToolResultBlockParam
            StringBuilder result = new StringBuilder();
            if (replaceAll) {
                result.append("The file ").append(filePath).append(" has been updated. All occurrences were successfully replaced.");
            } else {
                result.append("The file ").append(filePath).append(" has been updated successfully.");
            }

            // Append structured patch (port of CC's structuredPatch in output)
            if (!patchOutput.isEmpty()) {
                result.append("\n\n").append(patchOutput);
            }

            return CompletableFuture.completedFuture(result.toString());

        } catch (SecurityException se) {
            log.error("工具执行失败: edit_file, 安全异常: {}", se.getMessage(), se);
            return CompletableFuture.completedFuture("Error: " + se.getMessage());
        } catch (Exception e) {
            log.error("工具执行失败: edit_file, 错误: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture("Error editing file: " + e.getMessage());
        }
    }

    // ========== Helper methods (ports from CC utils) ==========

    /**
     * Port of CC's readFileForEdit() helper.
     * Reads file with encoding detection, returns structured result.
     */
    private static ReadFileResult readFileForEdit(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String content = FileSystemTools.smartDecode(bytes);
            Charset encoding = FileSystemTools.detectCharset(bytes);
            String lineEndings = FileSystemTools.detectLineEnding(content);
            boolean hasBom = FileSystemTools.hasUtf8Bom(bytes);
            return new ReadFileResult(content, true, encoding, lineEndings, hasBom);
        } catch (Exception e) {
            if (Files.exists(path)) {
                // File exists but can't be read - rethrow as runtime
                throw new RuntimeException("Failed to read file: " + path, e);
            }
            return new ReadFileResult("", false, StandardCharsets.UTF_8, "\n", false);
        }
    }

    /**
     * Result of readFileForEdit, mirroring CC's return type:
     * { content, fileExists, encoding, lineEndings }
     */
    private static final class ReadFileResult {
        final String content;
        final boolean fileExists;
        final Charset encoding;
        final String lineEndings;
        final boolean hasBom;

        ReadFileResult(String content, boolean fileExists, Charset encoding, String lineEndings, boolean hasBom) {
            this.content = content;
            this.fileExists = fileExists;
            this.encoding = encoding;
            this.lineEndings = lineEndings;
            this.hasBom = hasBom;
        }
    }

    /**
     * Port of CC's findSimilarFile().
     * When a file is not found, look for files with the same name but different extension.
     */
    private static String findSimilarFile(Path path) {
        try {
            String fileName = path.getFileName().toString();
            int dotIdx = fileName.lastIndexOf('.');
            String baseName = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
            Path parent = path.getParent();
            if (parent == null || !Files.exists(parent)) return null;

            try (var stream = Files.newDirectoryStream(parent)) {
                for (var entry : stream) {
                    if (!Files.isRegularFile(entry)) continue;
                    String entryName = entry.getFileName().toString();
                    int entryDot = entryName.lastIndexOf('.');
                    String entryBase = entryDot > 0 ? entryName.substring(0, entryDot) : entryName;
                    if (entryBase.equals(baseName) && !entryName.equals(fileName)) {
                        return entry.toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Port of CC's stripTrailingWhitespace().
     * Strips trailing whitespace from each line while preserving line endings.
     */
    static String stripTrailingWhitespace(String str) {
        if (str == null || str.isEmpty()) return str;
        String[] parts = str.split("(\\r\\n|\\n|\\r)", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                // Reconstruct line ending between parts
                // Since split consumed the separator, we need to detect it
                // Simple approach: use \n (already normalized at this point)
                result.append("\n");
            }
            result.append(parts[i].stripTrailing());
        }
        return result.toString();
    }

    /**
     * Port of CC's applyEditToFile().
     * Handles the edge case where new_string is empty and old_string doesn't end with \n.
     */
    static String applyEditToFile(String originalContent, String oldString, String newString, boolean replaceAll) {
        if (newString.isEmpty()) {
            // Port of CC's stripTrailingNewline logic
            boolean stripTrailingNewline = !oldString.endsWith("\n")
                    && originalContent.contains(oldString + "\n");
            if (stripTrailingNewline) {
                return replaceAll
                        ? originalContent.replace(oldString + "\n", "")
                        : originalContent.replaceFirst(escapeRegex(oldString) + "\n", "");
            }
        }
        if (replaceAll) {
            return originalContent.replace(oldString, newString);
        }
        int idx = originalContent.indexOf(oldString);
        if (idx < 0) return originalContent;
        return originalContent.substring(0, idx) + newString + originalContent.substring(idx + oldString.length());
    }

    /**
     * Generate a unified diff patch between old and new content.
     * Port of CC's getPatchFromContents (simplified - uses FileSystemTools.unifiedDiff).
     */
    private static String generatePatch(String fileName, String oldContent, String newContent) {
        List<String> oldLines = FileSystemTools.splitLinesPreserveNewline(oldContent);
        List<String> newLines = FileSystemTools.splitLinesPreserveNewline(newContent);
        return FileSystemTools.unifiedDiff(oldLines, newLines, fileName + " (before)", fileName + " (after)");
    }

    /** Count non-overlapping occurrences of a substring. */
    private static int countMatches(String content, String search) {
        if (search.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(search, idx)) >= 0) {
            count++;
            idx += search.length();
        }
        return count;
    }

    /** Normalize content for comparison. */
    private static String normalizeContentForCompare(String content) {
        if (content == null) return "";
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Escape special regex characters. */
    private static String escapeRegex(String s) {
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("+", "\\+")
                .replace("?", "\\?").replace("{", "\\{").replace("}", "\\}")
                .replace("[", "\\[").replace("]", "\\]").replace("(", "\\(")
                .replace(")", "\\)").replace("|", "\\|").replace("^", "\\^")
                .replace("$", "\\$").replace(".", "\\.");
    }

    /** Write file preserving BOM for UTF-8. Port of CC's writeTextContent. */
    private static void writeFilePreserving(Path path, String content, Charset charset, boolean preserveBom) throws Exception {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        if (preserveBom && charset == StandardCharsets.UTF_8) {
            byte[] bomBytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            byte[] fullBytes = new byte[bomBytes.length + contentBytes.length];
            System.arraycopy(bomBytes, 0, fullBytes, 0, bomBytes.length);
            System.arraycopy(contentBytes, 0, fullBytes, bomBytes.length, contentBytes.length);
            Files.write(path, fullBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            Files.writeString(path, content, charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
