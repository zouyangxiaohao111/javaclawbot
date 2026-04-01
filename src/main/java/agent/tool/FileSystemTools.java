package agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hslf.usermodel.HSLFNotes;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import utils.PathUtil;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 文件系统工具集合（对齐 MCP filesystem 的核心能力，并增强：
 *
 * 1) read_file：支持 head / tail / 指定范围行读取
 * 2) write_file：支持 overwrite / append / prepend / insert
 * 3) edit_file：支持 edits[] + dryRun
 * 4) list_dir：列目录
 * 5) read_word
 * 6) read_ppt
 * 7) read_ppt_structured：使用 Apache POI 读取 PPT 结构化内容（slide/title/body/notes）
 *
 * 说明：
 * - 路径安全：统一通过 PathUtil.resolvePath(workspace, allowedDir) 做白名单校验
 * - 文本文件按 UTF-8 处理
 * - Office 文档：
 *   - read_word / read_ppt：适合“全文读取 + 行裁剪”
 *   - read_ppt_structured：适合 agent 做结构化消费
 */
public final class FileSystemTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FileSystemTools() {}

    // ----------------------------
    // ReadFileTool
    // ----------------------------
    public static final class ReadFileTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ReadFileTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "Read the contents of a UTF-8 text file at the given path. Supports head/tail/start_line/end_line.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The file path to read"));
            props.put("head", Map.of("type", "number", "description", "First N lines (optional)"));
            props.put("tail", Map.of("type", "number", "description", "Last N lines (optional)"));
            props.put("start_line", Map.of("type", "number", "description", "Start line (1-based, optional)"));
            props.put("end_line", Map.of("type", "number", "description", "End line (1-based, inclusive, optional)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            Integer head = asIntOrNull(args.get("head"));
            Integer tail = asIntOrNull(args.get("tail"));
            Integer startLine = asIntOrNull(args.get("start_line"));
            Integer endLine = asIntOrNull(args.get("end_line"));

            String validate = validateLineReadArgs(head, tail, startLine, endLine);
            if (validate != null) {
                return CompletableFuture.completedFuture(validate);
            }

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) {
                    return CompletableFuture.completedFuture("Error: File not found: " + path);
                }
                if (!Files.isRegularFile(filePath)) {
                    return CompletableFuture.completedFuture("Error: Not a file: " + path);
                }

                String content = readFileSmart(filePath);
                return CompletableFuture.completedFuture(applyLineWindow(content, head, tail, startLine, endLine));

            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error reading file: " + e.getMessage());
            }
        }
    }

    // ----------------------------
    // WriteFileTool (Enhanced)
    // ----------------------------
    public static final class WriteFileTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public WriteFileTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "write_file";
        }

        @Override
        public String description() {
            return "Write content to a file. Supports overwrite/append/prepend/insert.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> insertProps = new LinkedHashMap<>();
            insertProps.put("char_offset", Map.of("type", "number", "description", "Insert at character offset (0-based)"));
            insertProps.put("line", Map.of("type", "number", "description", "Insert at line (1-based)"));
            insertProps.put("column", Map.of("type", "number", "description", "Insert at column (1-based)"));

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The file path to write to"));
            props.put("content", Map.of("type", "string", "description", "The content to write"));
            props.put("mode", Map.of(
                    "type", "string",
                    "description", "Write mode: overwrite | append | prepend | insert (default overwrite)",
                    "enum", List.of("overwrite", "append", "prepend", "insert")
            ));
            props.put("insert_at", Map.of(
                    "type", "object",
                    "description", "Required when mode=insert. Choose either char_offset or (line+column).",
                    "properties", insertProps
            ));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path", "content"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            String content = asString(args.get("content"));
            String mode = trimToNull(asString(args.get("mode")));
            if (mode == null) mode = "overwrite";

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                Path parent = filePath.getParent();
                if (parent != null) Files.createDirectories(parent);

                // Detect target line ending: match existing file, or use LF for new files
                // (LF is universal: Windows apps handle it, and it's native on Unix/macOS)
                String targetLineEnding;
                Charset targetCharset;
                boolean preserveBom;  // 是否保留 UTF-8 BOM

                if (Files.exists(filePath)) {
                    byte[] existingBytes = Files.readAllBytes(filePath);
                    targetLineEnding = detectLineEnding(smartDecode(existingBytes));
                    targetCharset = detectCharset(existingBytes);
                    preserveBom = hasUtf8Bom(existingBytes);
                } else {
                    // 新文件：使用 LF（跨平台兼容），UTF-8 无 BOM
                    targetLineEnding = "\n";  // 强制 LF，跨平台兼容
                    targetCharset = StandardCharsets.UTF_8;
                    preserveBom = false;  // 新文件不添加 BOM
                }
                // Normalize incoming content to match target line ending
                content = normalizeLineEndings(content, targetLineEnding);

                if ("append".equalsIgnoreCase(mode)) {
                    // 使用原文件编码追加（不重复写 BOM）
                    byte[] bytes = content.getBytes(targetCharset);
                    Files.write(filePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return CompletableFuture.completedFuture("Successfully appended " + bytes.length + " bytes to " + filePath + " (charset=" + targetCharset.name() + ")");
                }

                String old = Files.exists(filePath) ? readFileSmart(filePath) : "";

                String newContent;
                if ("overwrite".equalsIgnoreCase(mode)) {
                    newContent = content;
                } else if ("prepend".equalsIgnoreCase(mode)) {
                    newContent = content + old;
                } else if ("insert".equalsIgnoreCase(mode)) {
                    Object insertAtObj = args.get("insert_at");
                    if (!(insertAtObj instanceof Map<?, ?> insertAt)) {
                        return CompletableFuture.completedFuture("Error: mode=insert requires insert_at object");
                    }
                    InsertPos pos = parseInsertPos(insertAt);
                    if (pos == null) {
                        return CompletableFuture.completedFuture("Error: insert_at must provide either char_offset or (line and column)");
                    }
                    newContent = insertInto(old, content, pos);
                } else {
                    return CompletableFuture.completedFuture("Error: unknown mode: " + mode);
                }

                // 写入文件：如果需要保留 BOM，先写 BOM 再写内容
                if (preserveBom && targetCharset == StandardCharsets.UTF_8) {
                    // UTF-8 BOM: EF BB BF
                    byte[] bomBytes = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                    byte[] contentBytes = newContent.getBytes(StandardCharsets.UTF_8);
                    byte[] fullBytes = new byte[bomBytes.length + contentBytes.length];
                    System.arraycopy(bomBytes, 0, fullBytes, 0, bomBytes.length);
                    System.arraycopy(contentBytes, 0, fullBytes, bomBytes.length, contentBytes.length);
                    Files.write(filePath, fullBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    Files.writeString(
                            filePath,
                            newContent,
                            targetCharset,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );
                }

                String bomNote = preserveBom ? " (UTF-8 BOM preserved)" : "";
                return CompletableFuture.completedFuture("Successfully wrote to " + filePath + " (mode=" + mode + ", charset=" + targetCharset.name() + ", lineEnding=" + lineEndingName(targetLineEnding) + bomNote + ")");
            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error writing file: " + e.getMessage());
            }
        }

        /** 换行符名称 */
        private static String lineEndingName(String le) {
            if ("\r\n".equals(le)) return "CRLF";
            if ("\n".equals(le)) return "LF";
            if ("\r".equals(le)) return "CR";
            return "unknown";
        }

        /** 插入定位：二选一，charOffset 或 line/column */
        private static final class InsertPos {
            final Integer charOffset; // 0-based
            final Integer line;       // 1-based
            final Integer column;     // 1-based

            InsertPos(Integer charOffset, Integer line, Integer column) {
                this.charOffset = charOffset;
                this.line = line;
                this.column = column;
            }
        }

        private static InsertPos parseInsertPos(Map<?, ?> insertAt) {
            Integer charOffset = asIntOrNull(insertAt.get("char_offset"));
            Integer line = asIntOrNull(insertAt.get("line"));
            Integer column = asIntOrNull(insertAt.get("column"));

            if (charOffset != null) {
                if (charOffset < 0) charOffset = 0;
                return new InsertPos(charOffset, null, null);
            }

            if (line != null && column != null) {
                if (line < 1) line = 1;
                if (column < 1) column = 1;
                return new InsertPos(null, line, column);
            }
            return null;
        }

        /** 按 InsertPos 插入（尽量稳健：越界自动夹紧） */
        private static String insertInto(String base, String insert, InsertPos pos) {
            if (insert == null) insert = "";
            if (base == null) base = "";

            if (pos.charOffset != null) {
                int idx = Math.min(Math.max(0, pos.charOffset), base.length());
                return base.substring(0, idx) + insert + base.substring(idx);
            }

            List<String> lines = splitLinesPreserveNewline(base);
            int targetLine = Math.min(Math.max(1, pos.line), Math.max(1, lines.size()));

            int offset = 0;
            for (int i = 0; i < targetLine - 1 && i < lines.size(); i++) {
                offset += lines.get(i).length();
            }

            String lineStr = (targetLine - 1 < lines.size()) ? lines.get(targetLine - 1) : "";
            int col0 = Math.min(Math.max(0, pos.column - 1), lineStr.length());
            int idx = Math.min(base.length(), offset + col0);

            return base.substring(0, idx) + insert + base.substring(idx);
        }
    }

    // ----------------------------
    // EditFileTool (MCP-like: edits[] + dryRun)
    // ----------------------------
    public static final class EditFileTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public EditFileTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "edit_file";
        }

        @Override
        public String description() {
            return "Edit a file using edits[] (oldText->newText). Supports dryRun preview and multiple edits.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> editProps = new LinkedHashMap<>();
            editProps.put("oldText", Map.of("type", "string", "description", "Text to search (substring allowed)"));
            editProps.put("newText", Map.of("type", "string", "description", "Replacement text"));

            Map<String, Object> editsSchema = new LinkedHashMap<>();
            editsSchema.put("type", "array");
            editsSchema.put("description", "List of edit operations");
            editsSchema.put("items", Map.of("type", "object", "properties", editProps, "required", List.of("oldText", "newText")));

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The file path to edit"));
            props.put("edits", editsSchema);
            props.put("dryRun", Map.of("type", "boolean", "description", "Preview changes without applying (default false)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path", "edits"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            boolean dryRun = asBool(args.get("dryRun"), false);

            Object editsObj = args.get("edits");
            if (!(editsObj instanceof List<?> editsListRaw)) {
                return CompletableFuture.completedFuture("Error: edits must be an array");
            }

            List<EditOp> edits = new ArrayList<>();
            for (Object o : editsListRaw) {
                if (!(o instanceof Map<?, ?> m)) {
                    return CompletableFuture.completedFuture("Error: each edit must be an object");
                }
                String oldText = asString(m.get("oldText"));
                String newText = asString(m.get("newText"));
                edits.add(new EditOp(oldText, newText));
            }

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) return CompletableFuture.completedFuture("Error: File not found: " + path);
                if (!Files.isRegularFile(filePath)) return CompletableFuture.completedFuture("Error: Not a file: " + path);

                // 检测原文件编码
                Charset fileCharset = detectFileCharset(filePath);
                String original = readFileSmart(filePath);
                String updated = original;

                List<String> appliedNotes = new ArrayList<>();
                for (int i = 0; i < edits.size(); i++) {
                    EditOp op = edits.get(i);
                    ApplyResult r = applyOneEdit(updated, op.oldText, op.newText);
                    if (!r.applied) {
                        return CompletableFuture.completedFuture(r.errorMessage);
                    }
                    updated = r.newContent;
                    appliedNotes.add("Edit#" + (i + 1) + ": replaced " + r.replacedCount + " occurrence(s)");
                }

                String diff = unifiedDiff(
                        splitLinesPreserveNewline(original),
                        splitLinesPreserveNewline(updated),
                        path + " (before)",
                        path + " (after)"
                );

                if (dryRun) {
                    return CompletableFuture.completedFuture(
                            "DRY RUN: would apply " + edits.size() + " edits to " + path + "\n"
                                    + String.join("\n", appliedNotes) + "\n\n"
                                    + diff
                    );
                }

                Files.writeString(filePath, updated, fileCharset, StandardOpenOption.TRUNCATE_EXISTING);
                return CompletableFuture.completedFuture(
                        "Successfully edited " + filePath + " (charset=" + fileCharset.name() + ")\n"
                                + String.join("\n", appliedNotes) + "\n\n"
                                + diff
                );

            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error editing file: " + e.getMessage());
            }
        }

        private static final class EditOp {
            final String oldText;
            final String newText;

            EditOp(String oldText, String newText) {
                this.oldText = oldText == null ? "" : oldText;
                this.newText = newText == null ? "" : newText;
            }
        }

        private static final class ApplyResult {
            final boolean applied;
            final String newContent;
            final int replacedCount;
            final String errorMessage;

            private ApplyResult(boolean applied, String newContent, int replacedCount, String errorMessage) {
                this.applied = applied;
                this.newContent = newContent;
                this.replacedCount = replacedCount;
                this.errorMessage = errorMessage;
            }

            static ApplyResult ok(String newContent, int replacedCount) {
                return new ApplyResult(true, newContent, replacedCount, null);
            }

            static ApplyResult fail(String msg) {
                return new ApplyResult(false, null, 0, msg);
            }
        }

        /**
         * 应用单个 edit：
         * - 优先“子串精确匹配”
         * - 若找不到：返回最佳相似窗口提示
         * - oldText 为空：等价于 append
         */
        private static ApplyResult applyOneEdit(String content, String oldText, String newText) {
            if (content == null) content = "";
            if (oldText == null) oldText = "";
            if (newText == null) newText = "";

            if (oldText.isEmpty()) {
                return ApplyResult.ok(content + newText, 1);
            }

            int first = content.indexOf(oldText);
            if (first >= 0) {
                String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
                return ApplyResult.ok(updated, 1);
            }

            return ApplyResult.fail(notFoundMessage(oldText, content));
        }

        /**
         * oldText 找不到时的提示：在全文中找一个“最像 oldText 的窗口”，并输出 diff
         */
        private static String notFoundMessage(String oldText, String content) {
            List<String> lines = splitLinesPreserveNewline(content);
            List<String> oldLines = splitLinesPreserveNewline(oldText);

            int window = Math.max(1, oldLines.size());
            double bestRatio = 0.0;
            int bestStart = 0;

            int maxStart = Math.max(1, lines.size() - window + 1);
            String oldJoined = String.join("", oldLines);

            for (int i = 0; i < maxStart; i++) {
                String candidate = String.join("", lines.subList(i, Math.min(i + window, lines.size())));
                double ratio = similarity(oldJoined, candidate);
                if (ratio > bestRatio) {
                    bestRatio = ratio;
                    bestStart = i;
                }
            }

            if (bestRatio > 0.5) {
                List<String> actual = lines.subList(bestStart, Math.min(bestStart + window, lines.size()));
                String diff = unifiedDiff(
                        oldLines,
                        actual,
                        "oldText (provided)",
                        "bestMatch (actual, line " + (bestStart + 1) + ")"
                );
                return "Error: oldText not found in file.\n"
                        + "Best match (" + Math.round(bestRatio * 100) + "% similar) near line " + (bestStart + 1) + ":\n"
                        + diff;
            }

            return "Error: oldText not found in file. No similar text found. Verify the file content.";
        }

        /** normalized Levenshtein similarity in [0,1] */
        private static double similarity(String a, String b) {
            if (a == null) a = "";
            if (b == null) b = "";
            if (a.isEmpty() && b.isEmpty()) return 1.0;
            int dist = levenshtein(a, b);
            int max = Math.max(a.length(), b.length());
            return max == 0 ? 1.0 : 1.0 - ((double) dist / (double) max);
        }

        private static int levenshtein(String a, String b) {
            int n = a.length(), m = b.length();
            int[] prev = new int[m + 1];
            int[] curr = new int[m + 1];
            for (int j = 0; j <= m; j++) prev[j] = j;

            for (int i = 1; i <= n; i++) {
                curr[0] = i;
                char ca = a.charAt(i - 1);
                for (int j = 1; j <= m; j++) {
                    int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                    curr[j] = Math.min(
                            Math.min(curr[j - 1] + 1, prev[j] + 1),
                            prev[j - 1] + cost
                    );
                }
                int[] tmp = prev;
                prev = curr;
                curr = tmp;
            }
            return prev[m];
        }

        /**
         * 简化版 unified diff（行级别）
         */
        private static String unifiedDiff(List<String> oldLines, List<String> newLines, String fromFile, String toFile) {
            StringBuilder sb = new StringBuilder();
            sb.append("--- ").append(fromFile).append("\n");
            sb.append("+++ ").append(toFile).append("\n");
            sb.append("@@ -1,").append(oldLines.size()).append(" +1,").append(newLines.size()).append(" @@\n");

            int max = Math.max(oldLines.size(), newLines.size());
            for (int i = 0; i < max; i++) {
                String a = i < oldLines.size() ? oldLines.get(i) : null;
                String b = i < newLines.size() ? newLines.get(i) : null;

                if (Objects.equals(a, b)) {
                    if (a != null) sb.append(" ").append(a);
                } else {
                    if (a != null) sb.append("-").append(a);
                    if (b != null) sb.append("+").append(b);
                }
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
            return sb.toString();
        }
    }

    // ----------------------------
    // ReadWordTool (Pure POI)
    // ----------------------------
    public static final class ReadWordTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ReadWordTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "read_word";
        }

        @Override
        public String description() {
            return "Read text content from a Word document (.doc/.docx) using Apache POI. Supports head/tail/start_line/end_line.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The Word file path (.doc or .docx)"));
            props.put("head", Map.of("type", "number", "description", "First N lines (optional)"));
            props.put("tail", Map.of("type", "number", "description", "Last N lines (optional)"));
            props.put("start_line", Map.of("type", "number", "description", "Start line (1-based, optional)"));
            props.put("end_line", Map.of("type", "number", "description", "End line (1-based, inclusive, optional)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            Integer head = asIntOrNull(args.get("head"));
            Integer tail = asIntOrNull(args.get("tail"));
            Integer startLine = asIntOrNull(args.get("start_line"));
            Integer endLine = asIntOrNull(args.get("end_line"));

            String validate = validateLineReadArgs(head, tail, startLine, endLine);
            if (validate != null) {
                return CompletableFuture.completedFuture(validate);
            }

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) {
                    return CompletableFuture.completedFuture("Error: File not found: " + path);
                }
                if (!Files.isRegularFile(filePath)) {
                    return CompletableFuture.completedFuture("Error: Not a file: " + path);
                }

                String lower = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
                String text;
                if (lower.endsWith(".docx")) {
                    text = readDocxFullText(filePath);
                } else if (lower.endsWith(".doc")) {
                    text = readDocFullText(filePath);
                } else {
                    return CompletableFuture.completedFuture("Error: Unsupported Word format: " + path + " (only .doc / .docx)");
                }

                text = normalizeOfficeText(text);
                if (text.isBlank()) {
                    return CompletableFuture.completedFuture("Word document is empty or no readable text found: " + path);
                }

                return CompletableFuture.completedFuture(applyLineWindow(text, head, tail, startLine, endLine));

            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error reading Word file: " + e.getMessage());
            }
        }
    }

    // ----------------------------
    // ReadWordStructuredTool (.docx structured, Pure POI)
    // ----------------------------
    public static final class ReadWordStructuredTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ReadWordStructuredTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "read_word_structured";
        }

        @Override
        public String description() {
            return "Read a Word document into structured JSON by title/heading -> content. Reliable for .docx.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The Word file path (.docx recommended)"));
            props.put("include_empty_sections", Map.of("type", "boolean", "description", "Whether to keep headings with empty content (default false)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            boolean includeEmptySections = asBool(args.get("include_empty_sections"), false);

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) {
                    return CompletableFuture.completedFuture("Error: File not found: " + path);
                }
                if (!Files.isRegularFile(filePath)) {
                    return CompletableFuture.completedFuture("Error: Not a file: " + path);
                }

                String lower = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".docx")) {
                    return CompletableFuture.completedFuture(
                            "Error: read_word_structured currently supports .docx only. For .doc, use read_word for full-text extraction."
                    );
                }

                Map<String, Object> result = readDocxStructured(filePath, includeEmptySections);
                return CompletableFuture.completedFuture(
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result)
                );

            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error reading structured Word file: " + e.getMessage());
            }
        }

        private static Map<String, Object> readDocxStructured(Path filePath, boolean includeEmptySections) throws Exception {
            try (InputStream in = Files.newInputStream(filePath);
                 XWPFDocument doc = new XWPFDocument(in)) {

                List<Map<String, Object>> sections = new ArrayList<>();
                List<String> preamble = new ArrayList<>();
                WordSection current = null;

                for (XWPFParagraph para : doc.getParagraphs()) {
                    String text = normalizeOfficeText(safe(para.getText()));
                    if (text.isBlank()) {
                        continue;
                    }

                    HeadingInfo heading = detectHeading(doc, para, text);
                    if (heading != null) {
                        if (current != null) {
                            if (includeEmptySections || !current.content.isEmpty()) {
                                sections.add(current.toMap());
                            }
                        }

                        current = new WordSection(
                                heading.title,
                                heading.level,
                                heading.styleId,
                                heading.styleName
                        );
                    } else {
                        if (current == null) {
                            preamble.add(text);
                        } else {
                            current.content.add(text);
                        }
                    }
                }

                if (current != null) {
                    if (includeEmptySections || !current.content.isEmpty()) {
                        sections.add(current.toMap());
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("file", filePath.getFileName().toString());
                result.put("section_count", sections.size());
                result.put("preamble", preamble);
                result.put("sections", sections);
                return result;
            }
        }

        private static HeadingInfo detectHeading(XWPFDocument doc, XWPFParagraph para, String text) {
            String styleId = trimToNull(para.getStyle());
            String styleName = null;

            try {
                if (styleId != null && doc.getStyles() != null) {
                    XWPFStyle style = doc.getStyles().getStyle(styleId);
                    if (style != null) {
                        styleName = trimToNull(style.getName());
                    }
                }
            } catch (Exception ignored) {
            }

            Integer level = detectHeadingLevel(styleId, styleName);
            if (level != null) {
                return new HeadingInfo(text, level, styleId, styleName);
            }
            return null;
        }

        private static Integer detectHeadingLevel(String styleId, String styleName) {
            String a = normalizeStyleKey(styleId);
            String b = normalizeStyleKey(styleName);

            Integer fromA = parseHeadingLevelFromStyleKey(a);
            if (fromA != null) return fromA;

            Integer fromB = parseHeadingLevelFromStyleKey(b);
            if (fromB != null) return fromB;

            return null;
        }

        private static Integer parseHeadingLevelFromStyleKey(String s) {
            if (s == null || s.isBlank()) return null;

            if ("title".equals(s) || s.contains("doctitle")) {
                return 0;
            }

            String digits = extractTrailingDigitsAfterHeadingLikeToken(s);
            if (digits != null) {
                try {
                    return Integer.parseInt(digits);
                } catch (Exception ignored) {
                }
            }
            return null;
        }

        private static String extractTrailingDigitsAfterHeadingLikeToken(String s) {
            if (s == null) return null;

            String t = s.toLowerCase(Locale.ROOT)
                    .replace("标题", "heading")
                    .replace("_", "")
                    .replace("-", "")
                    .replace(" ", "");

            int idx = t.indexOf("heading");
            if (idx >= 0) {
                String tail = t.substring(idx + "heading".length());
                StringBuilder num = new StringBuilder();
                for (int i = 0; i < tail.length(); i++) {
                    char c = tail.charAt(i);
                    if (Character.isDigit(c)) {
                        num.append(c);
                    } else {
                        break;
                    }
                }
                if (num.length() > 0) {
                    return num.toString();
                }
            }
            return null;
        }

        private static String normalizeStyleKey(String s) {
            if (s == null) return null;
            return s.trim().toLowerCase(Locale.ROOT);
        }

        private static final class HeadingInfo {
            final String title;
            final int level;
            final String styleId;
            final String styleName;

            HeadingInfo(String title, int level, String styleId, String styleName) {
                this.title = title == null ? "" : title.trim();
                this.level = level;
                this.styleId = styleId;
                this.styleName = styleName;
            }
        }

        private static final class WordSection {
            final String title;
            final int level;
            final String styleId;
            final String styleName;
            final List<String> content = new ArrayList<>();

            WordSection(String title, int level, String styleId, String styleName) {
                this.title = title == null ? "" : title.trim();
                this.level = level;
                this.styleId = styleId;
                this.styleName = styleName;
            }

            Map<String, Object> toMap() {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", title);
                m.put("level", level);
                m.put("style_id", styleId == null ? "" : styleId);
                m.put("style_name", styleName == null ? "" : styleName);
                m.put("content", content);
                return m;
            }
        }
    }

    // ----------------------------
    // ReadPptTool (Pure POI)
    // ----------------------------
    public static final class ReadPptTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ReadPptTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "read_ppt";
        }

        @Override
        public String description() {
            return "Read text content from a PowerPoint document (.ppt/.pptx) using Apache POI. Supports head/tail/start_line/end_line.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The PowerPoint file path (.ppt or .pptx)"));
            props.put("head", Map.of("type", "number", "description", "First N lines (optional)"));
            props.put("tail", Map.of("type", "number", "description", "Last N lines (optional)"));
            props.put("start_line", Map.of("type", "number", "description", "Start line (1-based, optional)"));
            props.put("end_line", Map.of("type", "number", "description", "End line (1-based, inclusive, optional)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            Integer head = asIntOrNull(args.get("head"));
            Integer tail = asIntOrNull(args.get("tail"));
            Integer startLine = asIntOrNull(args.get("start_line"));
            Integer endLine = asIntOrNull(args.get("end_line"));

            String validate = validateLineReadArgs(head, tail, startLine, endLine);
            if (validate != null) {
                return CompletableFuture.completedFuture(validate);
            }

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) {
                    return CompletableFuture.completedFuture("Error: File not found: " + path);
                }
                if (!Files.isRegularFile(filePath)) {
                    return CompletableFuture.completedFuture("Error: Not a file: " + path);
                }

                String lower = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
                String text;
                if (lower.endsWith(".pptx")) {
                    text = readPptxFullText(filePath);
                } else if (lower.endsWith(".ppt")) {
                    text = readPptFullText(filePath);
                } else {
                    return CompletableFuture.completedFuture("Error: Unsupported PowerPoint format: " + path + " (only .ppt / .pptx)");
                }

                text = normalizeOfficeText(text);
                if (text.isBlank()) {
                    return CompletableFuture.completedFuture("PowerPoint document is empty or no readable text found: " + path);
                }

                return CompletableFuture.completedFuture(applyLineWindow(text, head, tail, startLine, endLine));

            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error reading PowerPoint file: " + e.getMessage());
            }
        }
    }

    // ----------------------------
    // ReadPptStructuredTool (Pure POI)
    // ----------------------------
    public static final class ReadPptStructuredTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ReadPptStructuredTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "read_ppt_structured";
        }

        @Override
        public String description() {
            return "Read a PowerPoint document (.ppt/.pptx) into structured JSON: slide/title/body/notes.";
        }

        @Override
        public Map<String, Object> parameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The PowerPoint file path (.ppt or .pptx)"));
            props.put("include_notes", Map.of("type", "boolean", "description", "Whether to include notes in result (default false)"));
            props.put("slide_start", Map.of("type", "number", "description", "Start slide index (1-based, optional)"));
            props.put("slide_end", Map.of("type", "number", "description", "End slide index (1-based, inclusive, optional)"));

            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("path"));
            return schema;
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            boolean includeNotes = asBool(args.get("include_notes"), false);
            Integer slideStart = asIntOrNull(args.get("slide_start"));
            Integer slideEnd = asIntOrNull(args.get("slide_end"));

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) {
                    return CompletableFuture.completedFuture("Error: File not found: " + path);
                }
                if (!Files.isRegularFile(filePath)) {
                    return CompletableFuture.completedFuture("Error: Not a file: " + path);
                }

                String lower = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
                Map<String, Object> result;
                if (lower.endsWith(".pptx")) {
                    result = readPptxStructured(filePath, includeNotes, slideStart, slideEnd);
                } else if (lower.endsWith(".ppt")) {
                    result = readPptStructured(filePath, includeNotes, slideStart, slideEnd);
                } else {
                    return CompletableFuture.completedFuture("Error: Unsupported PowerPoint format: " + path + " (only .ppt / .pptx)");
                }

                return CompletableFuture.completedFuture(
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result)
                );

            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error reading structured PowerPoint file: " + e.getMessage());
            }
        }

        private static Map<String, Object> readPptxStructured(Path filePath, boolean includeNotes, Integer slideStart, Integer slideEnd) throws Exception {
            try (InputStream in = Files.newInputStream(filePath);
                 XMLSlideShow ppt = new XMLSlideShow(in)) {

                List<XSLFSlide> slides = ppt.getSlides();
                int total = slides.size();

                int start = (slideStart == null) ? 1 : Math.max(1, slideStart);
                int end = (slideEnd == null) ? total : Math.max(1, slideEnd);
                start = Math.min(start, total == 0 ? 1 : total);
                end = Math.min(end, total == 0 ? 1 : total);

                List<Map<String, Object>> outSlides = new ArrayList<>();
                if (total > 0 && start <= end) {
                    for (int i = start - 1; i <= end - 1; i++) {
                        XSLFSlide slide = slides.get(i);

                        String title = normalizeSingleLine(safe(slide.getTitle()));
                        List<String> body = new ArrayList<>();

                        for (XSLFShape shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape textShape) {
                                String text = normalizeOfficeText(textShape.getText());
                                if (text.isBlank()) continue;

                                String[] blocks = text.split("\\n+");
                                for (String block : blocks) {
                                    String t = block == null ? "" : block.trim();
                                    if (!t.isEmpty()) {
                                        body.add(t);
                                    }
                                }
                            }
                        }

                        if (!title.isEmpty() && !body.isEmpty() && title.equals(normalizeSingleLine(body.get(0)))) {
                            body.remove(0);
                        }

                        Map<String, Object> slideObj = new LinkedHashMap<>();
                        slideObj.put("slide", i + 1);
                        slideObj.put("title", title);
                        slideObj.put("body", dedupKeepOrder(body));

                        if (includeNotes) {
                            slideObj.put("notes", extractPptxNotes(slide));
                        }

                        outSlides.add(slideObj);
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("file", filePath.getFileName().toString());
                result.put("slide_count", total);
                result.put("returned_slide_start", total == 0 ? 0 : start);
                result.put("returned_slide_end", total == 0 ? 0 : (start <= end ? end : 0));
                result.put("slides", outSlides);
                return result;
            }
        }

        private static Map<String, Object> readPptStructured(Path filePath, boolean includeNotes, Integer slideStart, Integer slideEnd) throws Exception {
            try (InputStream in = Files.newInputStream(filePath);
                 HSLFSlideShow ppt = new HSLFSlideShow(in)) {

                List<HSLFSlide> slides = ppt.getSlides();
                int total = slides.size();

                int start = (slideStart == null) ? 1 : Math.max(1, slideStart);
                int end = (slideEnd == null) ? total : Math.max(1, slideEnd);
                start = Math.min(start, total == 0 ? 1 : total);
                end = Math.min(end, total == 0 ? 1 : total);

                List<Map<String, Object>> outSlides = new ArrayList<>();
                if (total > 0 && start <= end) {
                    for (int i = start - 1; i <= end - 1; i++) {
                        HSLFSlide slide = slides.get(i);

                        String title = normalizeSingleLine(safe(slide.getTitle()));
                        List<String> body = new ArrayList<>();

                        for (HSLFShape shape : slide.getShapes()) {
                            if (shape instanceof HSLFTextShape textShape) {
                                String raw = safe(textShape.getText());
                                raw = normalizeOfficeText(raw);
                                if (raw.isBlank()) continue;

                                String[] blocks = raw.split("\\n+");
                                for (String block : blocks) {
                                    String t = block == null ? "" : block.trim();
                                    if (!t.isEmpty()) {
                                        body.add(t);
                                    }
                                }
                            }
                        }

                        if (!title.isEmpty() && !body.isEmpty() && title.equals(normalizeSingleLine(body.get(0)))) {
                            body.remove(0);
                        }

                        Map<String, Object> slideObj = new LinkedHashMap<>();
                        slideObj.put("slide", i + 1);
                        slideObj.put("title", title);
                        slideObj.put("body", dedupKeepOrder(body));

                        if (includeNotes) {
                            slideObj.put("notes", extractPptNotes(slide));
                        }

                        outSlides.add(slideObj);
                    }
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("file", filePath.getFileName().toString());
                result.put("slide_count", total);
                result.put("returned_slide_start", total == 0 ? 0 : start);
                result.put("returned_slide_end", total == 0 ? 0 : (start <= end ? end : 0));
                result.put("slides", outSlides);
                return result;
            }
        }

        private static String extractPptxNotes(XSLFSlide slide) {
            try {
                XSLFNotes notes = slide.getNotes();
                if (notes == null) return "";

                List<String> chunks = new ArrayList<>();
                for (XSLFShape shape : notes.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = normalizeOfficeText(textShape.getText());
                        if (!text.isBlank()) {
                            chunks.add(text);
                        }
                    }
                }
                return String.join("\n", chunks).trim();
            } catch (Exception ignored) {
                return "";
            }
        }

        private static String extractPptNotes(HSLFSlide slide) {
            try {
                HSLFNotes notes = slide.getNotes();
                if (notes == null) return "";

                List<String> chunks = new ArrayList<>();
                for (List<HSLFTextParagraph> paraGroup : notes.getTextParagraphs()) {
                    if (paraGroup == null || paraGroup.isEmpty()) {
                        continue;
                    }

                    String text = HSLFTextParagraph.getText(paraGroup);
                    text = normalizeOfficeText(text);

                    if (!text.isBlank()) {
                        chunks.add(text);
                    }
                }
                return String.join("\n", chunks).trim();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    // ----------------------------
    // ListDirTool
    // ----------------------------
    public static final class ListDirTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ListDirTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override
        public String name() {
            return "list_dir";
        }

        @Override
        public String description() {
            return "List the contents of a directory.";
        }

        @Override
        public Map<String, Object> parameters() {
            return schemaPathOnly("The directory path to list");
        }

        @Override
        public CompletionStage<String> execute(Map<String, Object> args) {
            String path = asString(args.get("path"));
            try {
                Path dirPath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(dirPath)) return CompletableFuture.completedFuture("Error: Directory not found: " + path);
                if (!Files.isDirectory(dirPath)) return CompletableFuture.completedFuture("Error: Not a directory: " + path);

                List<String> items = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
                    for (Path p : ds) items.add(p.getFileName().toString());
                }
                Collections.sort(items);

                if (items.isEmpty()) return CompletableFuture.completedFuture("Directory " + path + " is empty");

                List<String> out = new ArrayList<>();
                for (String name : items) {
                    Path p = dirPath.resolve(name);
                    String prefix = Files.isDirectory(p) ? "📁 " : "📄 ";
                    out.add(prefix + name);
                }
                return CompletableFuture.completedFuture(String.join("\n", out));
            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error listing directory: " + e.getMessage());
            }
        }
    }

    // ----------------------------
    // schema helpers
    // ----------------------------
    private static Map<String, Object> schemaPathOnly(String pathDesc) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", pathDesc));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("path"));
        return schema;
    }

    // ----------------------------
    // common helpers
    // ----------------------------
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

    private static boolean asBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }


    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeSingleLine(String s) {
        String t = safe(s).replace("\r", " ").replace("\n", " ").trim();
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        return t;
    }

    private static String normalizeOfficeText(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace("\r", "\n");
        while (t.contains("\n\n\n")) {
            t = t.replace("\n\n\n", "\n\n");
        }
        return t.trim();
    }

    private static String readDocxFullText(Path filePath) throws Exception {
        try (InputStream in = Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private static String readDocFullText(Path filePath) throws Exception {
        try (InputStream in = Files.newInputStream(filePath);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private static String readPptxFullText(Path filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(filePath);
             XMLSlideShow ppt = new XMLSlideShow(in)) {

            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                sb.append("=== Slide ").append(i + 1).append(" ===\n");

                String title = normalizeSingleLine(safe(slide.getTitle()));
                if (!title.isEmpty()) {
                    sb.append(title).append("\n");
                }

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = normalizeOfficeText(textShape.getText());
                        if (!text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static String readPptFullText(Path filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(filePath);
             HSLFSlideShow ppt = new HSLFSlideShow(in)) {

            List<HSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                HSLFSlide slide = slides.get(i);
                sb.append("=== Slide ").append(i + 1).append(" ===\n");

                String title = normalizeSingleLine(safe(slide.getTitle()));
                if (!title.isEmpty()) {
                    sb.append(title).append("\n");
                }

                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String text = normalizeOfficeText(textShape.getText());
                        if (!text.isBlank()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }



    /**
     * Detect the dominant line ending in the given text.
     * Returns "\r\n" if CRLF is found, "\n" otherwise.
     */
    public static String detectLineEnding(String text) {
        if (text == null || text.isEmpty()) return System.lineSeparator();
        boolean hasCRLF = text.contains("\r\n");
        if (hasCRLF) return "\r\n";
        if (text.indexOf('\n') >= 0) return "\n";
        // No newlines at all - use system default
        return System.lineSeparator();
    }

    /**
     * Normalize all line endings in text to the specified target.
     * Handles \r\n -> target, bare \n -> target, bare \r -> \n -> target.
     */
    public static String normalizeLineEndings(String text, String targetLineEnding) {
        if (text == null || text.isEmpty()) return text;
        if (targetLineEnding == null) targetLineEnding = System.lineSeparator();
        // First normalize everything to bare LF, then convert to target
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        if ("\r\n".equals(targetLineEnding)) {
            return normalized.replace("\n", "\r\n");
        }
        return normalized; // target is \n, already normalized
    }
    /** 按行切分，但保留每行末尾的 \n（最后一行可能没有） */
    public static List<String> splitLinesPreserveNewline(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    private static String validateLineReadArgs(Integer head, Integer tail, Integer startLine, Integer endLine) {
        boolean hasHeadTail = head != null || tail != null;
        boolean hasRange = startLine != null || endLine != null;

        if (head != null && tail != null) {
            return "Error: cannot specify both head and tail";
        }
        if (hasHeadTail && hasRange) {
            return "Error: cannot combine head/tail with start_line/end_line";
        }
        return null;
    }

    private static String applyLineWindow(String content, Integer head, Integer tail, Integer startLine, Integer endLine) {
        if (head != null) {
            return firstNLines(content, head);
        }
        if (tail != null) {
            return lastNLines(content, tail);
        }
        if (startLine != null || endLine != null) {
            return rangeLines(content, startLine, endLine);
        }
        return content;
    }

    /** 读取前 N 行 */
    public static String firstNLines(String content, int n) {
        if (n <= 0) return "";
        List<String> lines = splitLinesPreserveNewline(content);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.size()); i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** 读取后 N 行 */
    public static String lastNLines(String content, int n) {
        if (n <= 0) return "";
        List<String> lines = splitLinesPreserveNewline(content);
        int start = Math.max(0, lines.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * 读取指定行范围：
     * - start_line: 1-based，默认 1
     * - end_line: 1-based，包含该行，默认最后一行
     * - 自动夹紧到合法范围
     */
    private static String rangeLines(String content, Integer startLine, Integer endLine) {
        List<String> lines = splitLinesPreserveNewline(content);
        if (lines.isEmpty()) return "";

        int start = (startLine == null) ? 1 : Math.max(1, startLine);
        int end = (endLine == null) ? lines.size() : Math.max(1, endLine);

        start = Math.min(start, lines.size());
        end = Math.min(end, lines.size());

        if (start > end) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i <= end - 1; i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static List<String> dedupKeepOrder(List<String> input) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : input) {
            String t = normalizeSingleLine(s);
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * BOM 检测结果：包含编码和 BOM 长度
     */
    private static final class BomResult {
        final Charset charset;
        final int bomLength;  // BOM 字节数，用于跳过

        BomResult(Charset charset, int bomLength) {
            this.charset = charset;
            this.bomLength = bomLength;
        }
    }

    /**
     * 检测 BOM（字节顺序标记），返回编码和 BOM 长度
     *
     * BOM 标记：
     * - UTF-8:    EF BB BF       (3 bytes)
     * - UTF-16 BE: FE FF         (2 bytes)
     * - UTF-16 LE: FF FE         (2 bytes)
     * - UTF-32 BE: 00 00 FE FF   (4 bytes)
     * - UTF-32 LE: FF FE 00 00   (4 bytes)
     */
    private static BomResult detectBom(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return null;
        }

        // UTF-8 BOM: EF BB BF
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new BomResult(StandardCharsets.UTF_8, 3);
        }

        // UTF-32 BE: 00 00 FE FF (先检测，避免与 UTF-16 BE 混淆)
        if (bytes.length >= 4
                && bytes[0] == 0x00
                && bytes[1] == 0x00
                && bytes[2] == (byte) 0xFE
                && bytes[3] == (byte) 0xFF) {
            // Java 标准 Charset 没有 UTF-32，使用自定义名称
            return new BomResult(Charset.forName("UTF-32BE"), 4);
        }

        // UTF-32 LE: FF FE 00 00
        if (bytes.length >= 4
                && bytes[0] == (byte) 0xFF
                && bytes[1] == (byte) 0xFE
                && bytes[2] == 0x00
                && bytes[3] == 0x00) {
            return new BomResult(Charset.forName("UTF-32LE"), 4);
        }

        // UTF-16 BE: FE FF
        if (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new BomResult(StandardCharsets.UTF_16BE, 2);
        }

        // UTF-16 LE: FF FE (排除 UTF-32 LE 的情况)
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE
                && (bytes.length < 4 || bytes[2] != 0x00 || bytes[3] != 0x00)) {
            return new BomResult(StandardCharsets.UTF_16LE, 2);
        }

        return null;
    }

    /**
     * 检测文件编码（优先 BOM，再尝试 UTF-8/GBK）
     */
    private static Charset detectCharset(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;  // 默认 UTF-8（无 BOM）
        }

        // 优先检测 BOM
        BomResult bom = detectBom(bytes);
        if (bom != null) {
            return bom.charset;
        }

        // 无 BOM：尝试 UTF-8，失败则回退 GBK
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!containsReplacementChar(utf8, bytes)) {
            return StandardCharsets.UTF_8;
        }

        // 回退到 GBK（Windows 中文默认编码）
        return Charset.forName("GBK");
    }

    /**
     * 检测文件编码
     */
    private static Charset detectFileCharset(Path filePath) throws Exception {
        if (!Files.exists(filePath)) {
            return StandardCharsets.UTF_8;  // 新文件默认 UTF-8（无 BOM）
        }
        byte[] bytes = Files.readAllBytes(filePath);
        return detectCharset(bytes);
    }

    /**
     * 检测文件是否有 UTF-8 BOM
     */
    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes != null && bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
    }

    /**
     * 检测文件是否有 UTF-8 BOM
     */
    private static boolean hasUtf8Bom(Path filePath) throws Exception {
        if (!Files.exists(filePath)) return false;
        byte[] bytes = Files.readAllBytes(filePath);
        return hasUtf8Bom(bytes);
    }

    /**
     * 智能解码：优先 BOM，再 UTF-8，最后 GBK
     *
     * 解决 Windows 上中文编码问题：
     * - BOM 标记文件：直接使用对应编码
     * - UTF-8 无 BOM：正常解码
     * - GBK 文件：回退解码
     */
    private static String smartDecode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        // 优先检测 BOM
        BomResult bom = detectBom(bytes);
        if (bom != null) {
            // 使用 BOM 指定的编码，跳过 BOM 字节
            byte[] contentBytes = new byte[bytes.length - bom.bomLength];
            System.arraycopy(bytes, bom.bomLength, contentBytes, 0, contentBytes.length);
            return new String(contentBytes, bom.charset);
        }

        // 无 BOM：尝试 UTF-8
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!containsReplacementChar(utf8, bytes)) {
            return utf8;
        }

        // 回退到 GBK（Windows 中文默认编码）
        return new String(bytes, Charset.forName("GBK"));
    }

    /**
     * 检测 UTF-8 解码是否产生了替换字符（说明原始字节不是有效的 UTF-8）
     */
    private static boolean containsReplacementChar(String decoded, byte[] original) {
        // 如果解码结果包含替换字符，说明 UTF-8 解码失败，可能是 GBK
        if (decoded.contains("\uFFFD")) {
            return true;
        }
        // 注意：不能通过检测 0x81-0xFE 字节来判断 GBK，因为 UTF-8 多字节编码
        // 的后续字节（0x80-0xBF）也落在这个范围内，会导致 UTF-8 中文文件被误判为 GBK。
        // 正确的做法是只依赖替换字符检测：UTF-8 解码器遇到非法字节会插入 \uFFFD
        return false;
    }

    /**
     * 智能读取文件内容
     */
    private static String readFileSmart(Path filePath) throws Exception {
        byte[] bytes = Files.readAllBytes(filePath);
        return smartDecode(bytes);
    }

}