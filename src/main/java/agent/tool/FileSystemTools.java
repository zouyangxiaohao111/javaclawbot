package agent.tool;

import utils.PathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 文件系统工具集合（对齐 MCP filesystem 的核心能力，并增强 write_file 支持 append/insert）
 *
 * 设计目标：
 * 1) read_file：读取文件（可扩展 head/tail）
 * 2) write_file：支持 overwrite/append/prepend/insert（解决“只能全量替换”的问题）
 * 3) edit_file：支持 edits 数组 + dryRun，尽量贴近 MCP filesystem 的 edit_file 使用体验
 * 4) list_dir：列目录
 *
 * 注意：
 * - 路径安全：统一通过 PathUtil.resolvePath(workspace, allowedDir) 进行白名单校验
 * - 编码：全部按 UTF-8 文本处理（媒体文件如需 base64，可另外加 read_media_file 工具）
 */
public final class FileSystemTools {

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

        @Override public String name() { return "read_file"; }

        @Override public String description() {
            return "Read the contents of a UTF-8 text file at the given path. (Optional: head/tail lines)";
        }

        @Override public Map<String, Object> parameters() {
            // 兼容增强：增加 head/tail（可选），但你也可以不传
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("path", Map.of("type", "string", "description", "The file path to read"));
            props.put("head", Map.of("type", "number", "description", "First N lines (optional)"));
            props.put("tail", Map.of("type", "number", "description", "Last N lines (optional)"));

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

            // MCP 规则：head 和 tail 不能同时存在（这里也按这个约束）
            if (head != null && tail != null) {
                return CompletableFuture.completedFuture("Error: cannot specify both head and tail");
            }

            try {
                Path filePath = PathUtil.resolvePath(path, workspace, allowedDir);
                if (!Files.exists(filePath)) return CompletableFuture.completedFuture("Error: File not found: " + path);
                if (!Files.isRegularFile(filePath)) return CompletableFuture.completedFuture("Error: Not a file: " + path);

                String content = Files.readString(filePath, StandardCharsets.UTF_8);

                if (head != null) {
                    return CompletableFuture.completedFuture(firstNLines(content, head));
                }
                if (tail != null) {
                    return CompletableFuture.completedFuture(lastNLines(content, tail));
                }
                return CompletableFuture.completedFuture(content);
            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error reading file: " + e.getMessage());
            }
        }

        private static String firstNLines(String content, int n) {
            if (n <= 0) return "";
            List<String> lines = splitLinesPreserveNewline(content);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(n, lines.size()); i++) sb.append(lines.get(i));
            return sb.toString();
        }

        private static String lastNLines(String content, int n) {
            if (n <= 0) return "";
            List<String> lines = splitLinesPreserveNewline(content);
            int start = Math.max(0, lines.size() - n);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.size(); i++) sb.append(lines.get(i));
            return sb.toString();
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

        @Override public String name() { return "write_file"; }

        @Override public String description() {
            return "Write content to a file. Supports overwrite/append/prepend/insert.";
        }

        @Override public Map<String, Object> parameters() {
            Map<String, Object> insertProps = new LinkedHashMap<>();
            insertProps.put("char_offset", Map.of("type", "number", "description", "Insert at character offset (0-based)"));
            insertProps.put("line", Map.of("type", "number", "description", "Insert at line (1-based)"));
            insertProps.put("column", Map.of("type", "number", "description", "Insert at column (1-based)"));

            Map<String, Object> insertSchema = new LinkedHashMap<>();
            insertSchema.put("type", "object");
            insertSchema.put("properties", insertProps);

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

                // append 模式：直接追加写入，不再整文件替换
                if ("append".equalsIgnoreCase(mode)) {
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    Files.write(filePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return CompletableFuture.completedFuture("Successfully appended " + bytes.length + " bytes to " + filePath);
                }

                // overwrite / prepend / insert 需要先读取旧内容（若不存在则为空）
                String old = Files.exists(filePath) ? Files.readString(filePath, StandardCharsets.UTF_8) : "";

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

                Files.writeString(
                        filePath,
                        newContent,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );

                return CompletableFuture.completedFuture("Successfully wrote to " + filePath + " (mode=" + mode + ")");
            } catch (SecurityException se) {
                return CompletableFuture.completedFuture("Error: " + se.getMessage());
            } catch (Exception e) {
                return CompletableFuture.completedFuture("Error writing file: " + e.getMessage());
            }
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

            // line/column 必须同时给
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

            // line/column 插入：把 base 当作文本（\n），计算目标字符下标
            List<String> lines = splitLinesPreserveNewline(base);
            int targetLine = Math.min(Math.max(1, pos.line), Math.max(1, lines.size()));

            // 计算该行起始 offset
            int offset = 0;
            for (int i = 0; i < targetLine - 1 && i < lines.size(); i++) {
                offset += lines.get(i).length();
            }

            // 该行内容（含末尾 \n 或不含）
            String lineStr = (targetLine - 1 < lines.size()) ? lines.get(targetLine - 1) : "";
            // column 是 1-based，且不应超过该行长度+1
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

        @Override public String name() { return "edit_file"; }

        @Override public String description() {
            return "Edit a file using edits[] (oldText->newText). Supports dryRun preview and multiple edits.";
        }

        @Override public Map<String, Object> parameters() {
            // 对齐 MCP 的字段风格：edits + dryRun
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

            // 解析 edits
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

                String original = Files.readString(filePath, StandardCharsets.UTF_8);
                String updated = original;

                // 逐个应用 edit（顺序很重要：和 MCP 的“multiple simultaneous edits with correct positioning”理念一致）
                // 这里采用“应用后再找下一个”的策略，保证偏移不会错乱
                List<String> appliedNotes = new ArrayList<>();
                for (int i = 0; i < edits.size(); i++) {
                    EditOp op = edits.get(i);
                    ApplyResult r = applyOneEdit(updated, op.oldText, op.newText);
                    if (!r.applied) {
                        // 找不到则返回“最佳匹配窗口 + diff”提示（沿用你原先的体验）
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

                Files.writeString(filePath, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                return CompletableFuture.completedFuture(
                        "Successfully edited " + filePath + "\n"
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
         * - 优先“子串精确匹配”；
         * - 若找不到：做一次“最佳相似窗口”提示（不自动替换，避免误改）；
         * - oldText 为空：等价于“追加 newText 到文件末尾”（常用于 append 场景）
         */
        private static ApplyResult applyOneEdit(String content, String oldText, String newText) {
            if (content == null) content = "";
            if (oldText == null) oldText = "";
            if (newText == null) newText = "";

            // 允许 oldText 为空：表示 append（这能解决“只添加就报错”的常见工具调用）
            if (oldText.isEmpty()) {
                return ApplyResult.ok(content + newText, 1);
            }

            int first = content.indexOf(oldText);
            if (first >= 0) {
                // 默认只替换“首次出现”，避免误替换多处（如果你希望全量替换，可扩展成参数控制）
                String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
                return ApplyResult.ok(updated, 1);
            }

            // 找不到：输出“最佳匹配窗口 + diff”（不落盘）
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

        // ---- similarity & diff helpers ----

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
                int[] tmp = prev; prev = curr; curr = tmp;
            }
            return prev[m];
        }

        /**
         * 简化版 unified diff（行级别）：
         * - 不实现完整 Myers，但足够用于工具“预览变化 / 定位差异”
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
    // ListDirTool
    // ----------------------------
    public static final class ListDirTool extends Tool {
        private final Path workspace;
        private final Path allowedDir;

        public ListDirTool(Path workspace, Path allowedDir) {
            this.workspace = workspace;
            this.allowedDir = allowedDir;
        }

        @Override public String name() { return "list_dir"; }

        @Override public String description() {
            return "List the contents of a directory.";
        }

        @Override public Map<String, Object> parameters() {
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

    /** 按行切分，但保留每行末尾的 \n（最后一行可能没有） */
    private static List<String> splitLinesPreserveNewline(String s) {
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
}