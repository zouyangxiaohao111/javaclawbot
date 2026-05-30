package agent.tool.file;

import agent.tool.Tool;
import agent.tool.ToolUseContext;
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
 * WriteTool_new -- full atomic port of Claude Code's FileWriteTool.
 *
 * Source mapping:
 *   FileWriteTool.ts  -> this class (validateInput + call)
 *   prompt.ts         -> description()
 *   types (inline)    -> parameters() [file_path + content]
 *
 * CRITICAL DIFFERENCE from old WriteFileTool:
 *   This is a TRUE Write tool (file_path + content), NOT an Edit tool.
 *   The old WriteFileTool was a merged Edit+Write with old_string/new_string/edits[]/dryRun.
 *   CC's Write tool only takes file_path + content (full file content).
 *   For string replacements, use EditTool instead.
 *
 * Key features ported:
 *   - Full file write (create new or overwrite existing)
 *   - read-before-write enforcement for EXISTING files (not required for new files)
 *   - file modification time staleness check with content fallback
 *   - Structured patch output for updates (unified diff)
 *   - Create vs update distinction in result message
 *   - Encoding preservation for existing files
 *   - "Prefer the Edit tool for modifying existing files" guidance
 *   - File not exist = new file creation (no read required)
 */
public final class WriteTool extends Tool {

    // ---- Port of prompt.ts constants ----
    static final String TOOL_NAME = "write_file";

    /** Port of constants.ts FILE_UNEXPECTEDLY_MODIFIED_ERROR (shared with EditTool) */
    private static final String FILE_UNEXPECTEDLY_MODIFIED_ERROR =
            "File has been unexpectedly modified. Read it again before attempting to write it.";

    private final Path workspace;
    private final Path allowedDir;
    private final FileStateCache fileStateCache;
    private final SessionFileStateManager sessionManager;
    private final FileBackupManager fileBackupManager;

    public WriteTool(Path workspace, Path allowedDir, FileStateCache fileStateCache, FileBackupManager fileBackupManager) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.fileStateCache = fileStateCache != null ? fileStateCache : new FileStateCache.NoOp();
        this.fileBackupManager = fileBackupManager;
        this.sessionManager = new SessionFileStateManager();
    }

    public WriteTool(Path workspace, Path allowedDir, FileStateCache fileStateCache) {
        this(workspace, allowedDir, fileStateCache, null);
    }

    /** Backward-compatible constructor (no cache enforcement) */
    public WriteTool(Path workspace, Path allowedDir) {
        this(workspace, allowedDir, new FileStateCache.NoOp(), null);
    }

    public WriteTool(Path workspace, Path allowedDir, SessionFileStateManager sessionManager) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.fileStateCache = new FileStateCache.NoOp();
        this.sessionManager = sessionManager != null ? sessionManager : new SessionFileStateManager();
        this.fileBackupManager = null;
    }

    public WriteTool(Path workspace, Path allowedDir, SessionFileStateManager sessionManager, FileBackupManager fileBackupManager) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.fileStateCache = new FileStateCache.NoOp();
        this.sessionManager = sessionManager != null ? sessionManager : new SessionFileStateManager();
        this.fileBackupManager = fileBackupManager;
    }

    // ---- Port of TOOL_NAME ----
    @Override
    public String name() {
        return TOOL_NAME;
    }

    // ---- Port of prompt.ts getWriteToolDescription() ----
    @Override
    public String description() {
        return String.join("\n", List.of(
            "Writes a file to the local filesystem.",
            "",
            "Usage:",
            "- This tool will overwrite the existing file if there is one at the provided path.",
            "- If this is an existing file, you MUST use the read_file tool first to read the file's contents. This tool will fail if you did not read the file first.",
            "- Prefer the Edit tool for modifying existing files -- it only sends the diff. Only use this tool to create new files or for complete rewrites.",
            "- NEVER create documentation files (*.md) or README files unless explicitly requested by the User.",
            "- Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked."
        ));
    }

    @Override
    public int maxResultSizeChars() {
        return 100_000;
    }

    // ---- Port of FileWriteTool.ts inputSchema ----
    // CC schema: { file_path: string, content: string }
    // NOTE: NO old_string/new_string/edits[]/dryRun -- this is a pure Write tool
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file_path", Map.of(
                "type", "string",
                "description", "The absolute path to the file to write (must be absolute, not relative)"
        ));
        props.put("content", Map.of(
                "type", "string",
                "description", "The content to write to the file"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("file_path", "content"));
        return schema;
    }

    /**
     * Port of FileWriteTool.ts validateInput() + call().
     *
     * validateInput checks (in order):
     *   1. checkTeamMemSecrets (skipped - no team memory in Java agent)
     *   2. Permission deny rule check (skipped - Java uses allowedDir whitelist)
     *   3. UNC path skip (skipped - Java resolves paths differently)
     *   4. File existence check: if file doesn't exist, return {result: true} (new file)
     *   5. Read-before-write check for existing files (errorCode: 2)
     *   6. Mtime staleness check (errorCode: 3)
     *
     * call() steps:
     *   1. Discover skills from path (skipped)
     *   2. Create parent directories
     *   3. File history backup (skipped)
     *   4. Load current state (readFileSyncWithMetadata)
     *   5. Mtime staleness check (runtime throw)
     *   6. Detect encoding from existing file
     *   7. Write content (LF line endings - port of CC's writeTextContent with 'LF')
     *   8. LSP notification (skipped)
     *   9. Update FileStateCache
     *   10. Generate structured patch for updates
     *   11. Return result with create/update distinction
     */
    @Override
    public CompletionStage<String> execute(Map<String, Object> args, ToolUseContext parentUseContext) {
        FileStateCache activeCache = this.fileStateCache;
        if (parentUseContext != null && parentUseContext.getSessionId() != null && sessionManager != null) {
            activeCache = sessionManager.getFileCache(parentUseContext.getSessionId());
        }
        return doExecute(args, activeCache);
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        return doExecute(args, this.fileStateCache);
    }

    private CompletionStage<String> doExecute(Map<String, Object> args, FileStateCache afc) {
        String filePath = FileSystemTools.asString(args.get("file_path"));
        String content = FileSystemTools.asString(args.get("content"));

        if (filePath == null) {
            return CompletableFuture.completedFuture("Error: file_path is required");
        }
        if (content == null) content = "";

        try {
            Path resolvedPath = PathUtil.resolvePath(filePath, workspace, allowedDir);

            // ---- validateInput: check if file exists ----
            boolean fileExists = Files.exists(resolvedPath);
            long fileMtimeMs = 0;

            if (fileExists) {
                fileMtimeMs = Files.getLastModifiedTime(resolvedPath).toMillis();
            }

            // ---- validateInput: read-before-write for existing files (errorCode: 2) ----
            // Port of CC: if file exists, MUST have been read first
            // For new files (ENOENT), return {result: true} immediately
            if (fileExists) {
                FileStateCache.FileState readState = afc.getState(resolvedPath);
                if (readState == null || readState.isPartialView) {
                    return CompletableFuture.completedFuture(
                            "Error: File has not been read yet. Read it first before writing to it. " +
                            "Use the read_file tool to read " + filePath);
                }

                // ---- validateInput: mtime staleness check (errorCode: 3) ----
                // Port of CC: reuse mtime from the stat above
                long lastWriteTime = fileMtimeMs;
                if (lastWriteTime > readState.timestamp) {
                    // Timestamp indicates modification, but on Windows timestamps can change
                    // without content changes (cloud sync, antivirus, etc.). For full reads,
                    // compare content as a fallback to avoid false positives.
                    boolean isFullRead = readState.offset == null && readState.limit == null;
                    if (!isFullRead) {
                        return CompletableFuture.completedFuture(
                                "Error: File has been modified since read, either by the user or by a linter. Read it again before attempting to write it.");
                    }
                    // For full reads, compare content as fallback
                    // Need to read current content to compare
                    byte[] currentBytes = Files.readAllBytes(resolvedPath);
                    String currentContent = FileSystemTools.smartDecode(currentBytes)
                            .replace("\r\n", "\n").replace("\r", "\n");
                    String cachedContent = readState.content != null
                            ? readState.content.replace("\r\n", "\n").replace("\r", "\n") : "";
                    if (!currentContent.equals(cachedContent)) {
                        return CompletableFuture.completedFuture(
                                "Error: File has been modified since read, either by the user or by a linter. Read it again before attempting to write it.");
                    }
                }
            }

            // ---- call: load current state for patch generation ----
            // Port of CC's readFileSyncWithMetadata
            String oldContent = null;
            Charset encoding = StandardCharsets.UTF_8;

            if (fileExists) {
                try {
                    byte[] existingBytes = Files.readAllBytes(resolvedPath);
                    oldContent = FileSystemTools.smartDecode(existingBytes)
                            .replace("\r\n", "\n").replace("\r", "\n");
                    encoding = FileSystemTools.detectCharset(existingBytes);
                } catch (Exception e) {
                    // If we can't read, treat as new file
                    oldContent = null;
                }
            } else {
                encoding = StandardCharsets.UTF_8;
            }

            // ---- call: recheck mtime staleness (runtime throw) ----
            // Port of CC's critical section: avoid async ops between staleness check and write
            if (fileExists && afc.getState(resolvedPath) != null) {
                long lastWriteTime = Files.getLastModifiedTime(resolvedPath).toMillis();
                FileStateCache.FileState lastRead = afc.getState(resolvedPath);
                if (lastRead != null && lastWriteTime > lastRead.timestamp) {
                    boolean isFullRead = lastRead.offset == null && lastRead.limit == null;
                    if (!isFullRead || (oldContent != null && !oldContent.equals(
                            lastRead.content != null ? lastRead.content.replace("\r\n", "\n").replace("\r", "\n") : ""))) {
                        throw new RuntimeException(FILE_UNEXPECTEDLY_MODIFIED_ERROR);
                    }
                }
            }

            // ---- call: create parent directories ----
            // Port of CC: await fs.mkdir(dirname(fullFilePath))
            Path parent = resolvedPath.getParent();
            if (parent != null) Files.createDirectories(parent);

            // ---- backup original file before write ----
            if (fileBackupManager != null && oldContent != null) {
                String tcId = agent.tool.ToolCallContext.getToolCallId();
                fileBackupManager.backup(resolvedPath, oldContent, tcId);
            }

            // ---- call: writeTextContent ----
            // Port of CC: writeTextContent(fullFilePath, content, enc, 'LF')
            // CC explicitly uses 'LF' for line endings on Write: "the model sent explicit line
            // endings in `content` and meant them. Do not rewrite them."
            String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
            writeFileContent(resolvedPath, normalizedContent, encoding);

            // ---- call: update FileStateCache ----
            // Port of CC: readFileState.set(fullFilePath, {content, timestamp, offset, limit})
            long newMtime = Files.getLastModifiedTime(resolvedPath).toMillis();
            long newSize = Files.size(resolvedPath);
            afc.invalidate(resolvedPath);
            afc.markRead(resolvedPath, normalizedContent, newMtime, newSize);

            // ---- call: generate result ----
            // Port of CC's mapToolResultToToolResultBlockParam
            if (oldContent != null) {
                // Update existing file
                String patch = generatePatch(resolvedPath.getFileName().toString(),
                        oldContent, normalizedContent);

                StringBuilder result = new StringBuilder();
                result.append("The file ").append(filePath).append(" has been updated successfully.");
                if (!patch.isEmpty()) {
                    result.append("\n\n").append(patch);
                }
                return CompletableFuture.completedFuture(result.toString());
            } else {
                // Create new file
                return CompletableFuture.completedFuture(
                        "File created successfully at: " + filePath);
            }

        } catch (SecurityException se) {
            return CompletableFuture.completedFuture("Error: " + se.getMessage());
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error writing file: " + e.getMessage());
        }
    }

    // ========== Helper methods ==========

    /**
     * Generate a unified diff patch between old and new content.
     * Port of CC's getPatchForDisplay (simplified).
     */
    private static String generatePatch(String fileName, String oldContent, String newContent) {
        List<String> oldLines = FileSystemTools.splitLinesPreserveNewline(oldContent);
        List<String> newLines = FileSystemTools.splitLinesPreserveNewline(newContent);
        return FileSystemTools.unifiedDiff(oldLines, newLines, fileName + " (before)", fileName + " (after)");
    }

    /**
     * Write file content. Port of CC's writeTextContent.
     * Uses the detected encoding for existing files, UTF-8 for new files.
     */
    private static void writeFileContent(Path path, String content, Charset encoding) throws Exception {
        Files.writeString(path, content, encoding,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
