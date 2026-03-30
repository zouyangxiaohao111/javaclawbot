package skills;

import agent.command.CommandQueueManager;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能加载器：
 * - 技能以目录形式存在，每个技能目录内包含 SKILL.md
 * - 支持从“工作区技能目录”和“内置技能目录”加载，工作区优先级更高
 * - 支持解析 SKILL.md 顶部的 YAML 头信息（仅支持简单 key: value）
 * - 支持从头信息中的 metadata 字段解析 JSON，并读取 javaclawbot/openclaw 下的配置
 * - 支持检查技能依赖（可执行命令、环境变量），并按需过滤不可用技能
 */
@Slf4j
public class SkillsLoader {

    /** 默认内置技能目录：尝试按类文件位置推导；失败则使用当前工作目录下 skills */
    public static final Path BUILTIN_SKILLS_DIR = defaultBuiltinSkillsDir();

    private final Path workspace;
    private final Path workspaceSkills;
    private final Path builtinSkills;

    /**
     * 构造方法
     * @param workspace 工作区根目录
     * @param builtinSkillsDir 内置技能目录（可为 null，表示使用默认目录）
     */
    public SkillsLoader(Path workspace, Path builtinSkillsDir) {
        this.workspace = Objects.requireNonNull(workspace, "workspace 不能为空");
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkills = (builtinSkillsDir != null) ? builtinSkillsDir : BUILTIN_SKILLS_DIR;
    }
    /**
     * 构造方法
     * @param workspace 工作区根目录
     */
    public SkillsLoader(Path workspace) {
        this(workspace, null);
    }

    /**
     * 列出所有技能
     * @param filterUnavailable 是否过滤依赖不满足的技能
     * @return 技能信息列表，每项包含：name、path、source
     */
    public List<String> listSkillNames(boolean filterUnavailable) {
        return listSkills(filterUnavailable).stream().map(s -> (String) s.get("name")).toList();
    }

    /**
     * 列出指定path下的所有技能
     * @return 技能信息列表，每项包含：name、path、source
     */
    public List<Map<String, String>> listSkills(Path skillPath) {
        List<Map<String, String>> skills = new ArrayList<>();

        // 工作区技能（优先级最高）
        if (Files.exists(skillPath) && Files.isDirectory(skillPath)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(workspaceSkills)) {
                for (Path skillDir : ds) {
                    if (Files.isDirectory(skillDir)) {
                        Path skillFile = skillDir.resolve("SKILL.md");
                        if (Files.exists(skillFile) && Files.isRegularFile(skillFile)) {
                            skills.add(skillInfo(skillDir.getFileName().toString(), skillFile, "workspace"));
                        }
                    }
                }
            } catch (IOException ignored) {
                // 列表获取失败时保持“尽力而为”的行为
            }
        }
        return skills;
    }


    /**
     * 列出所有技能
     * @param filterUnavailable 是否过滤依赖不满足的技能
     * @return 技能信息列表，每项包含：name、path、source
     */
    public List<Map<String, String>> listSkills(boolean filterUnavailable) {
        List<Map<String, String>> skills = new ArrayList<>();

        // 工作区技能（优先级最高）
        if (Files.exists(workspaceSkills) && Files.isDirectory(workspaceSkills)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(workspaceSkills)) {
                for (Path skillDir : ds) {
                    if (Files.isDirectory(skillDir)) {
                        Path skillFile = skillDir.resolve("SKILL.md");
                        if (Files.exists(skillFile) && Files.isRegularFile(skillFile)) {
                            skills.add(skillInfo(skillDir.getFileName().toString(), skillFile, "workspace"));
                        }
                    }
                }
            } catch (IOException ignored) {
                // 列表获取失败时保持“尽力而为”的行为
            }
        }

        // 内置技能
        if (builtinSkills != null && Files.exists(builtinSkills) && Files.isDirectory(builtinSkills)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(builtinSkills)) {
                for (Path skillDir : ds) {
                    if (Files.isDirectory(skillDir)) {
                        Path skillFile = skillDir.resolve("SKILL.md");
                        if (Files.exists(skillFile) && Files.isRegularFile(skillFile)) {
                            String name = skillDir.getFileName().toString();
                            boolean already = skills.stream().anyMatch(s -> name.equals(s.get("name")));
                            if (!already) {
                                skills.add(skillInfo(name, skillFile, "builtin"));
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // 同上：尽力而为
            }
        }

        if (!filterUnavailable) {
            return skills;
        }

        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> s : skills) {
            Map<String, Object> meta = getSkillMeta(s.get("name"));
            if (checkRequirements(meta)) {
                filtered.add(s);
            }
        }
        return filtered;
    }


    /**
     * 用户AI技能调用，卸载技能
     * @param name
     * @return
     */
    /*public String agentUninstallSkill(String name) {
        var alwaysSkills = getAlwaysSkills();
        if (alwaysSkills.contains(name)) {
            log.error("常驻技能：{} 不允许被卸载,常驻技能有：{}", name, new Gson().toJson(alwaysSkills));
            return "常驻技能" + name + " 不允许被卸载,常驻技能有：" + new Gson().toJson(alwaysSkills);
        }
        loadSkillQueue.remove(name);
        return "技能" + name + "已被卸载，下次对话就不会出现该技能，直到你重新装载";
    }*/

    private static final String skill_format = "Base directory for this skill: %s\n\n%s";
    /**
     * 按名称加载技能内容
     * @param name 技能目录名
     * @return 文件内容；不存在返回 null
     */
    public String loadSkill(String name) {
        // 先查工作区
        Path workspaceSkill = workspaceSkills.resolve(name).resolve("SKILL.md");
        if (Files.exists(workspaceSkill) && Files.isRegularFile(workspaceSkill)) {
            String context = stripFrontmatter(readUtf8(workspaceSkill));
            return String.format(skill_format, workspaceSkill.getParent(), context);
        }

        // 再查内置
        if (builtinSkills != null) {
            Path builtinSkill = builtinSkills.resolve(name).resolve("SKILL.md");
            if (Files.exists(builtinSkill) && Files.isRegularFile(builtinSkill)) {
                String context = stripFrontmatter(readUtf8(builtinSkill));
                return String.format(skill_format, builtinSkill.getParent(), context);
            }
        }

        return "";
    }



    /**
     * 构建技能摘要（XML 格式）
     * - 每个技能包含：名称、描述、位置、是否可用
     * - 若不可用，会输出缺失的依赖项说明
     * @return XML 字符串；没有技能则返回空字符串
     */
    public String buildSkillsSummary() {
        List<Map<String, String>> allSkills = listSkills(false);
        if (allSkills.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("<skills>");

        for (Map<String, String> s : allSkills) {
            String name = escapeXml(s.get("name"));
            String path = s.get("path");
            String desc = escapeXml(getSkillDescription(s.get("name")));

            Map<String, Object> skillMeta = getSkillMeta(s.get("name"));
            boolean available = checkRequirements(skillMeta);

            lines.add("  <skill available=\"" + (available ? "true" : "false") + "\">");
            lines.add("    <name>" + name + "</name>");
            lines.add("    <description>" + desc + "</description>");
            lines.add("    <location>" + path + "</location>");

            if (!available) {
                String missing = getMissingRequirements(skillMeta);
                if (missing != null && !missing.isBlank()) {
                    lines.add("    <requires>" + escapeXml(missing) + "</requires>");
                }
            }
            lines.add("  </skill>");
        }
        lines.add("</skills>");
        return String.join("\n", lines);
    }



    /**
     * 构建技能摘要（XML 格式）
     * - 每个技能包含：名称、描述、位置、是否可用
     * - 若不可用，会输出缺失的依赖项说明
     * @return XML 字符串；没有技能则返回空字符串
     */
    public String buildSkillsSimpleSummary() {
        List<Map<String, String>> allSkills = listSkills(false);
        if (allSkills.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("<system-reminder>\n");
        lines.add("以下为可用技能,其中 available=\"false\" 的技能需要先安装依赖。\n");

        for (Map<String, String> s : allSkills) {
            StringBuilder sb = new StringBuilder();
            sb.append("- ");
            String name = s.get("name");
            String path = s.get("path");
            sb.append(name).append(", path:").append(path).append(", ");

            // 获取元数据
            Map<String, Object> skillMeta = getSkillMeta(s.get("name"));
            boolean available = checkRequirements(skillMeta);
            sb.append("available: ").append(available).append(" :");

            // 获取说明
            String desc = getSkillDescription(name);
            sb.append(desc).append("\n");

            // 添加内容
            lines.add(sb.toString());
        }
        lines.add("</system-reminder>\n");
        return String.join("\n", lines);
    }

    /**
     * 返回标记为 always=true 且依赖满足的技能名称列表
     * - always 可以在 JSON 的 javaclawbot/openclaw 中出现
     * - 也可以直接出现在 YAML 头信息中
     */
    public List<String> getAlwaysSkills() {
        List<String> result = new ArrayList<>();
        for (Map<String, String> s : listSkills(true)) {
            Map<String, String> meta = getSkillMetadata(s.get("name"));
            if (meta == null) meta = Collections.emptyMap();

            Map<String, Object> skillMeta = parseJavaClawBotMetadata(meta.getOrDefault("metadata", ""));
            Object always1 = skillMeta.get("always");
            String always2 = meta.get("always");

            boolean isAlways = false;
            if (always1 instanceof Boolean b) {
                isAlways = b;
            } else if (always1 instanceof String str) {
                isAlways = "true".equalsIgnoreCase(str.trim());
            }
            if (!isAlways && always2 != null) {
                isAlways = "true".equalsIgnoreCase(always2.trim());
            }

            if (isAlways) {
                result.add(s.get("name"));
            }
        }
        return result;
    }

    /**
     * 读取技能文件 YAML 头信息（只支持简单 key: value）
     * @param name 技能名称
     * @return 元数据 Map；没有头信息或文件不存在返回 null
     */
    public Map<String, String> getSkillMetadata(String name) {
        String content = loadSkill(name);
        if (content == null) {
            return null;
        }

        if (content.startsWith("---")) {
            Pattern p = Pattern.compile("^---\\n(.*?)\\n---", Pattern.DOTALL);
            Matcher m = p.matcher(content);
            if (m.find()) {
                String front = m.group(1);
                Map<String, String> metadata = new LinkedHashMap<>();
                for (String line : front.split("\\n")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0) {
                        String key = line.substring(0, idx).trim();
                        String value = line.substring(idx + 1).trim();
                        value = stripQuotes(value);
                        if (!key.isEmpty()) {
                            metadata.put(key, value);
                        }
                    }
                }
                return metadata;
            }
        }

        return null;
    }

    // ==========================
    // 内部方法
    // ==========================

    private Map<String, String> skillInfo(String name, Path skillFile, String source) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("path", skillFile.toString());
        m.put("source", source);
        return m;
    }

    /**
     * 生成缺失依赖项的说明字符串
     * - 命令行工具：在 PATH 中找不到
     * - 环境变量：不存在或为空
     */
    public String getMissingRequirements(Map<String, Object> skillMeta) {
        List<String> missing = new ArrayList<>();
        Map<String, Object> requires = asMap(skillMeta.get("requires"));

        List<Object> bins = asList(requires.get("bins"));
        for (Object b : bins) {
            if (b == null) continue;
            String bin = String.valueOf(b);
            if (which(bin) == null) {
                missing.add("命令行工具: " + bin);
            }
        }

        List<Object> envs = asList(requires.get("env"));
        for (Object e : envs) {
            if (e == null) continue;
            String env = String.valueOf(e);
            String v = System.getenv(env);
            if (v == null || v.isEmpty()) {
                missing.add("环境变量: " + env);
            }
        }

        return String.join(", ", missing);
    }

    /**
     * 获取技能描述：
     * - 优先读取 YAML 头信息 description
     * - 没有则使用技能名称作为描述
     */
    public String getSkillDescription(String name) {
        Map<String, String> meta = getSkillMetadata(name);
        if (meta != null) {
            String d = meta.get("description");
            if (d != null && !d.isBlank()) {
                return d;
            }
        }
        return name;
    }

    /**
     * 去掉 YAML 头信息，返回剩余正文
     */
    private String stripFrontmatter(String content) {
        if (content.startsWith("---")) {
            Pattern p = Pattern.compile("^---\\n.*?\\n---\\n", Pattern.DOTALL);
            Matcher m = p.matcher(content);
            if (m.find()) {
                return content.substring(m.end()).trim();
            }
        }
        return content;
    }

    /**
     * 解析 YAML 头信息中的 metadata（JSON 字符串）：
     * - 如果是对象，优先取 javaclawbot，其次取 openclaw
     * - 解析失败或结构不符合则返回空 Map
     */
    private Map<String, Object> parseJavaClawBotMetadata(String raw) {
        if (raw == null) return new LinkedHashMap<>();
        String s = raw.trim();
        if (s.isEmpty()) return new LinkedHashMap<>();
        try {
            Object parsed = MiniJson.parse(s);
            if (!(parsed instanceof Map)) return new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) parsed;

            Object jcb = data.get("javaclawbot");
            if (jcb instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) jcb;
                return m;
            }
            Object nb = data.get("javaclawbot");
            if (nb instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) nb;
                return m;
            }
            Object oc = data.get("openclaw");
            if (oc instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) oc;
                return m;
            }
            return new LinkedHashMap<>();
        } catch (RuntimeException ex) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 检查技能依赖是否满足：
     * - requires.bins：必须在 PATH 中能找到
     * - requires.env：环境变量必须存在且不为空
     */
    private boolean checkRequirements(Map<String, Object> skillMeta) {
        Map<String, Object> requires = asMap(skillMeta.get("requires"));

        List<Object> bins = asList(requires.get("bins"));
        for (Object b : bins) {
            if (b == null) continue;
            String bin = String.valueOf(b);
            if (which(bin) == null) {
                return false;
            }
        }

        List<Object> envs = asList(requires.get("env"));
        for (Object e : envs) {
            if (e == null) continue;
            String env = String.valueOf(e);
            String v = System.getenv(env);
            if (v == null || v.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 获取技能的 javaclawbot/openclaw 元数据
     */
    public Map<String, Object> getSkillMeta(String name) {
        Map<String, String> meta = getSkillMetadata(name);
        if (meta == null) meta = Collections.emptyMap();
        return parseJavaClawBotMetadata(meta.getOrDefault("metadata", ""));
    }

    // ==========================
    // 通用工具
    // ==========================

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stripQuotes(String value) {
        if (value == null) return null;
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            if (v.length() >= 2) return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String readUtf8(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return new ArrayList<>();
    }

    /**
     * 在系统 PATH 中查找可执行文件
     * @param command 命令名或路径
     * @return 找到则返回绝对路径；否则返回 null
     */
    private static Path which(String command) {
        if (command == null || command.isBlank()) return null;

        // 如果已经是路径形式
        Path cmdPath = Paths.get(command);
        if (cmdPath.getParent() != null) {
            if (Files.exists(cmdPath) && Files.isRegularFile(cmdPath) && Files.isExecutable(cmdPath)) {
                return cmdPath.toAbsolutePath().normalize();
            }
            if (isWindows() && !hasExtension(command)) {
                for (String ext : windowsExts()) {
                    Path p = Paths.get(command + ext);
                    if (Files.exists(p) && Files.isRegularFile(p) && Files.isExecutable(p)) {
                        return p.toAbsolutePath().normalize();
                    }
                }
            }
            return null;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) return null;

        String[] dirs = pathEnv.split(Pattern.quote(File.pathSeparator));
        boolean win = isWindows();
        List<String> exts = win ? windowsExts() : List.of("");

        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            Path base = Paths.get(dir.trim()).resolve(command);

            if (win) {
                if (hasExtension(command)) {
                    if (Files.exists(base) && Files.isRegularFile(base) && Files.isExecutable(base)) {
                        return base.toAbsolutePath().normalize();
                    }
                } else {
                    for (String ext : exts) {
                        Path candidate = Paths.get(base.toString() + ext);
                        if (Files.exists(candidate) && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                            return candidate.toAbsolutePath().normalize();
                        }
                    }
                }
            } else {
                if (Files.exists(base) && Files.isRegularFile(base) && Files.isExecutable(base)) {
                    return base.toAbsolutePath().normalize();
                }
            }
        }

        return null;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean hasExtension(String s) {
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        int dot = s.lastIndexOf('.');
        return dot > slash;
    }

    /**
     * 获取 Windows 下常见可执行后缀
     */
    private static List<String> windowsExts() {
        String pathext = System.getenv("PATHEXT");
        if (pathext != null && !pathext.isBlank()) {
            String[] parts = pathext.split(";");
            List<String> exts = new ArrayList<>();
            for (String p : parts) {
                if (p != null && !p.isBlank()) exts.add(p.trim().toLowerCase(Locale.ROOT));
            }
            if (!exts.isEmpty()) return exts;
        }
        return List.of(".exe", ".cmd", ".bat", ".com");
    }

    /**
     * 推导默认内置技能目录：
     * - 优先按类文件所在位置向上两级再拼接 skills
     * - 失败则使用当前工作目录下 skills
     */
    private static Path defaultBuiltinSkillsDir() {
        try {
            URI uri = SkillsLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path base = Paths.get(uri);

            Path dir = Files.isDirectory(base) ? base : base.getParent();
            if (dir != null) {
                Path p = dir.getParent();
                if (p != null) p = p.getParent();
                if (p != null) return p.resolve("skills").normalize();
            }
        } catch (URISyntaxException ignored) {
        }
        return Paths.get(System.getProperty("user.dir")).resolve("skills").normalize();
    }

    // ==========================
    // 轻量 JSON 解析器（仅用于读取 metadata）
    // 支持：对象、数组、字符串、数字、true/false/null
    // ==========================
    private static final class MiniJson {

        static Object parse(String s) {
            if (s == null) throw new IllegalArgumentException("JSON 不能为空");
            Parser p = new Parser(s);
            Object v = p.parseValue();
            p.skipWs();
            if (!p.eof()) {
                throw new RuntimeException("JSON 解析后仍有剩余内容，位置: " + p.pos);
            }
            return v;
        }

        private static final class Parser {
            private final String s;
            private int pos;

            Parser(String s) {
                this.s = s;
                this.pos = 0;
            }

            boolean eof() {
                return pos >= s.length();
            }

            void skipWs() {
                while (!eof()) {
                    char c = s.charAt(pos);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                    else break;
                }
            }

            char peek() {
                return s.charAt(pos);
            }

            char next() {
                return s.charAt(pos++);
            }

            Object parseValue() {
                skipWs();
                if (eof()) throw new RuntimeException("JSON 意外结束");

                char c = peek();
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();

                throw new RuntimeException("JSON 非法字符 '" + c + "'，位置: " + pos);
            }

            Map<String, Object> parseObject() {
                Map<String, Object> obj = new LinkedHashMap<>();
                expect('{');
                skipWs();
                if (!eof() && peek() == '}') {
                    next();
                    return obj;
                }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    obj.put(key, val);
                    skipWs();
                    if (eof()) throw new RuntimeException("对象未正确结束");
                    char c = next();
                    if (c == '}') break;
                    if (c != ',') throw new RuntimeException("期望 ',' 或 '}'，位置: " + (pos - 1));
                }
                return obj;
            }

            List<Object> parseArray() {
                List<Object> arr = new ArrayList<>();
                expect('[');
                skipWs();
                if (!eof() && peek() == ']') {
                    next();
                    return arr;
                }
                while (true) {
                    Object val = parseValue();
                    arr.add(val);
                    skipWs();
                    if (eof()) throw new RuntimeException("数组未正确结束");
                    char c = next();
                    if (c == ']') break;
                    if (c != ',') throw new RuntimeException("期望 ',' 或 ']'，位置: " + (pos - 1));
                }
                return arr;
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!eof()) {
                    char c = next();
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (eof()) throw new RuntimeException("转义序列意外结束");
                        char e = next();
                        switch (e) {
                            case '"', '\\', '/' -> sb.append(e);
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                if (pos + 4 > s.length()) throw new RuntimeException("Unicode 转义不完整");
                                String hex = s.substring(pos, pos + 4);
                                pos += 4;
                                try {
                                    int code = Integer.parseInt(hex, 16);
                                    sb.append((char) code);
                                } catch (NumberFormatException ex) {
                                    throw new RuntimeException("Unicode 转义非法: " + hex);
                                }
                            }
                            default -> throw new RuntimeException("非法转义: \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new RuntimeException("字符串未正确结束");
            }

            Boolean parseBoolean() {
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                throw new RuntimeException("布尔值非法，位置: " + pos);
            }

            Object parseNull() {
                if (s.startsWith("null", pos)) {
                    pos += 4;
                    return null;
                }
                throw new RuntimeException("null 非法，位置: " + pos);
            }

            Number parseNumber() {
                int start = pos;
                if (peek() == '-') pos++;
                while (!eof()) {
                    char c = peek();
                    if (c >= '0' && c <= '9') pos++;
                    else break;
                }
                boolean isFloat = false;
                if (!eof() && peek() == '.') {
                    isFloat = true;
                    pos++;
                    while (!eof()) {
                        char c = peek();
                        if (c >= '0' && c <= '9') pos++;
                        else break;
                    }
                }
                if (!eof() && (peek() == 'e' || peek() == 'E')) {
                    isFloat = true;
                    pos++;
                    if (!eof() && (peek() == '+' || peek() == '-')) pos++;
                    while (!eof()) {
                        char c = peek();
                        if (c >= '0' && c <= '9') pos++;
                        else break;
                    }
                }
                String num = s.substring(start, pos);
                try {
                    if (isFloat) return Double.parseDouble(num);
                    long lv = Long.parseLong(num);
                    if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) return (int) lv;
                    return lv;
                } catch (NumberFormatException ex) {
                    throw new RuntimeException("数字非法: " + num);
                }
            }

            void expect(char ch) {
                skipWs();
                if (eof()) throw new RuntimeException("期望 '" + ch + "' 但已结束");
                char c = next();
                if (c != ch) throw new RuntimeException("期望 '" + ch + "' 但得到 '" + c + "'，位置: " + (pos - 1));
            }
        }
    }
}