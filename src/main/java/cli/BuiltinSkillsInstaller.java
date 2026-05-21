package cli;

import org.jline.terminal.Terminal;
import utils.Helpers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 内置技能安装器
 *
 * 兼容：
 * - 开发期 file: classpath 目录
 * - 生产期 jar: classpath 资源
 */
public final class BuiltinSkillsInstaller {

    private BuiltinSkillsInstaller() {}

    private static final String CLASSPATH_SKILLS_ROOT = "skills";

    public static final class SkillResource {
        private final String name;
        private final String classpathDir;

        public SkillResource(String name, String classpathDir) {
            this.name = name;
            this.classpathDir = classpathDir;
        }

        public String getName() { return name; }
        public String getClasspathDir() { return classpathDir; }
    }

    public static final class InstallSummary {
        private final List<String> installed = new ArrayList<>();
        private final List<String> overwritten = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();
        private final List<String> pluginsInstalled = new ArrayList<>();
        private final List<String> pluginsFailed = new ArrayList<>();

        public List<String> getInstalled() { return installed; }
        public List<String> getOverwritten() { return overwritten; }
        public List<String> getSkipped() { return skipped; }
        public List<String> getFailed() { return failed; }
        public List<String> getPluginsInstalled() { return pluginsInstalled; }
        public List<String> getPluginsFailed() { return pluginsFailed; }
    }

    public static List<SkillResource> discoverBuiltinSkills() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        try {
            Enumeration<URL> roots = cl.getResources(CLASSPATH_SKILLS_ROOT);
            List<SkillResource> result = new ArrayList<>();

            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                result.addAll(scanSkillsFromUrl(url));
            }

            Map<String, SkillResource> dedup = new LinkedHashMap<>();
            for (SkillResource r : result) dedup.putIfAbsent(r.getName(), r);

            return dedup.values().stream()
                    .sorted(Comparator.comparing(SkillResource::getName, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            System.err.println("发现内置技能失败: " + e.getMessage());
            return List.of();
        }
    }

    public static TerminalPrompts.SelectionResult<SkillResource> promptSelection(
            Terminal terminal,
            List<SkillResource> allSkills
    ) {
        return TerminalPrompts.multiSelect(
                terminal,
                allSkills,
                SkillResource::getName,
                "选择要安装的内置技能",
                false,
                true
        );
    }

    public static InstallSummary installSelectedSkills(
            Path workspace,
            List<SkillResource> selected,
            boolean overwrite
    ) {
        InstallSummary summary = new InstallSummary();
        Path targetRoot = Helpers.getSkillsPath(workspace);

        if (selected == null || selected.isEmpty()) {
            return summary;
        }

        for (SkillResource skill : selected) {
            String name = skill.getName();
            Path targetDir = targetRoot.resolve(name);

            try {
                if (overwrite) {
                    deleteDirectoryIfExists(targetDir);
                    copyClasspathDirectory(skill.getClasspathDir(), targetDir);
                    summary.getOverwritten().add(name);
                } else {
                    if (Files.exists(targetDir)) {
                        summary.getSkipped().add(name);
                    } else {
                        copyClasspathDirectory(skill.getClasspathDir(), targetDir);
                        summary.getInstalled().add(name);
                    }
                }

                // Auto-install associated plugin (e.g. zjkycode -> zjkycode.js)
                if (installAssociatedPlugin(name, workspace)) {
                    summary.getPluginsInstalled().add(name);
                }
            } catch (Exception e) {
                summary.getFailed().add(name + " (" + e.getMessage() + ")");
            }
        }

        return summary;
    }

    /**
     * 安装单个技能到目标目录（覆盖模式）
     * 供 SkillSyncService 调用
     */
    public static InstallSummary installSkill(String skillName, String classpathDir, Path targetSkillsDir) {
        InstallSummary summary = new InstallSummary();
        Path targetDir = targetSkillsDir.resolve(skillName);

        try {
            deleteDirectoryIfExists(targetDir);
            copyClasspathDirectory(classpathDir, targetDir);
            summary.getOverwritten().add(skillName);
        } catch (Exception e) {
            summary.getFailed().add(skillName + " (" + e.getMessage() + ")");
        }

        return summary;
    }

    public static void printSummary(InstallSummary summary) {
        System.out.println();
        System.out.println("Built-in skills installation result:");

        for (String s : summary.getInstalled()) {
            System.out.println("  ✓ Installed: " + s);
        }
        for (String s : summary.getOverwritten()) {
            System.out.println("  ✓ Overwritten: " + s);
        }
        for (String s : summary.getSkipped()) {
            System.out.println("  - Skipped existing: " + s);
        }
        for (String s : summary.getFailed()) {
            System.out.println("  ✗ Failed: " + s);
        }
        for (String s : summary.getPluginsInstalled()) {
            System.out.println("  ★ Plugin auto-installed: " + s);
        }
        for (String s : summary.getPluginsFailed()) {
            System.out.println("  ⚠ Plugin failed: " + s);
        }

        if (summary.getInstalled().isEmpty()
                && summary.getOverwritten().isEmpty()
                && summary.getSkipped().isEmpty()
                && summary.getFailed().isEmpty()
                && summary.getPluginsInstalled().isEmpty()) {
            System.out.println("  (nothing selected)");
        }
    }

    private static List<SkillResource> scanSkillsFromUrl(URL url) throws Exception {
        if (url == null) return List.of();
        String protocol = url.getProtocol();

        if ("file".equalsIgnoreCase(protocol)) {
            return scanFromFileUrl(url);
        }
        if ("jar".equalsIgnoreCase(protocol)) {
            return scanFromJarUrl(url);
        }
        return List.of();
    }

    private static List<SkillResource> scanFromFileUrl(URL url) throws Exception {
        Path root = Paths.get(url.toURI());
        if (!Files.isDirectory(root)) return List.of();

        List<SkillResource> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                // 所有 skills 目录下的子目录都视为技能，不再要求 SKILL.md
                String name = p.getFileName().toString();
                out.add(new SkillResource(name, CLASSPATH_SKILLS_ROOT + "/" + name));
            }
        }
        return out;
    }

    private static List<SkillResource> scanFromJarUrl(URL url) throws Exception {
        URI uri = url.toURI();
        String uriStr = uri.toString();
        int sep = uriStr.indexOf("!/");
        if (sep < 0) return List.of();

        URI jarUri = URI.create(uriStr.substring(0, sep));

        try (FileSystem fs = openOrGetJarFileSystem(jarUri)) {
            Path root = fs.getPath("/" + CLASSPATH_SKILLS_ROOT);
            if (!Files.isDirectory(root)) return List.of();

            List<SkillResource> out = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path p : ds) {
                    if (!Files.isDirectory(p)) continue;
                    // 所有 skills 目录下的子目录都视为技能，不再要求 SKILL.md
                    String name = p.getFileName().toString();
                    out.add(new SkillResource(name, CLASSPATH_SKILLS_ROOT + "/" + name));
                }
            }
            return out;
        }
    }

    public static void copyClasspathDirectory(String classpathDir, Path targetDir) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        URL url = cl.getResource(classpathDir);
        if (url == null) {
            throw new IOException("Classpath resource not found: " + classpathDir);
        }

        String protocol = url.getProtocol();
        if ("file".equalsIgnoreCase(protocol)) {
            copyFromFileSystemDir(Paths.get(url.toURI()), targetDir);
            return;
        }

        if ("jar".equalsIgnoreCase(protocol)) {
            URI uri = url.toURI();
            String uriStr = uri.toString();
            int sep = uriStr.indexOf("!/");
            if (sep < 0) throw new IOException("无效的 jar uri: " + uriStr);

            URI jarUri = URI.create(uriStr.substring(0, sep));
            try (FileSystem fs = openOrGetJarFileSystem(jarUri)) {
                Path sourceDir = fs.getPath("/" + classpathDir);
                copyFromFileSystemDir(sourceDir, targetDir);
                return;
            }
        }

        throw new IOException("不支持的协议: " + protocol);
    }

    private static void copyFromFileSystemDir(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            throw new IOException("Source dir not found: " + sourceDir);
        }

        try (Stream<Path> stream = Files.walk(sourceDir)) {
            for (Path source : stream.collect(Collectors.toList())) {
                Path relative = sourceDir.relativize(source);
                Path target = targetDir.resolve(relative.toString());

                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static void deleteDirectoryIfExists(Path dir) throws IOException {
        if (dir == null || Files.notExists(dir)) return;

        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }

    private static FileSystem openOrGetJarFileSystem(URI jarUri) throws IOException {
        try {
            return FileSystems.newFileSystem(jarUri, Map.of());
        } catch (FileSystemAlreadyExistsException e) {
            return FileSystems.getFileSystem(jarUri);
        }
    }

    // ========== Plugin Auto-Install ==========

    private static final String[] PLUGIN_EXTENSIONS = {"js", "mjs", "cjs", "py"};

    /**
     * Find associated plugin resource on classpath for a given skill name.
     * Searches templates/plugins/{skillName}.{ext} for each supported extension.
     *
     * @return the classpath resource path, or null if not found
     */
    public static String findAssociatedPlugin(String skillName) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        for (String ext : PLUGIN_EXTENSIONS) {
            String resource = "templates/plugins/" + skillName + "." + ext;
            if (cl.getResource(resource) != null) {
                return resource;
            }
        }
        return null;
    }

    /**
     * Auto-install associated plugin for a skill.
     * If templates/plugins/{skillName}.{js|mjs|cjs|py} exists on classpath,
     * copies it to workspace/plugins/{skillName}.{ext}.
     *
     * @return true if a plugin was found and installed
     */
    static boolean installAssociatedPlugin(String skillName, Path workspace) {
        String pluginResource = findAssociatedPlugin(skillName);
        if (pluginResource == null) return false;

        String ext = pluginResource.substring(pluginResource.lastIndexOf('.'));
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        try {
            Path pluginsDir = workspace.resolve("plugins");
            Files.createDirectories(pluginsDir);
            Path target = pluginsDir.resolve(skillName + ext);

            try (InputStream is = cl.getResourceAsStream(pluginResource)) {
                if (is == null) {
                    throw new IOException("Plugin resource not found: " + pluginResource);
                }
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            }

            System.out.println("  ✓ Auto-installed plugin: " + skillName + ext);
            return true;
        } catch (Exception e) {
            System.err.println("  ⚠ Failed to install plugin for " + skillName + ": " + e.getMessage());
            throw new RuntimeException("Plugin install failed: " + e.getMessage(), e);
        }
    }

    // ========== Skill Update Detection ==========

    /**
     * 检测哪些内置技能有更新（内置版本比已安装版本多了文件/文件夹）。
     *
     * @param workspace 工作区根目录
     * @return 有更新的技能名称列表
     */
    public static List<String> detectSkillUpdates(Path workspace) {
        List<String> updated = new ArrayList<>();
        Path skillsRoot = Helpers.getSkillsPath(workspace);

        List<SkillResource> builtinSkills = discoverBuiltinSkills();
        for (SkillResource skill : builtinSkills) {
            Path installedDir = skillsRoot.resolve(skill.getName());
            if (!Files.isDirectory(installedDir)) {
                continue; // 未安装的跳过，由 installSelectedSkills 处理
            }
            try {
                Set<String> builtinFiles = listClasspathDirectoryFiles(skill.getClasspathDir());
                Set<String> installedFiles = listDirectoryFiles(installedDir);
                // 如果内置版本有已安装版本没有的文件 → 有更新
                if (!installedFiles.containsAll(builtinFiles)) {
                    updated.add(skill.getName());
                }
            } catch (Exception e) {
                System.err.println("检测技能更新失败 " + skill.getName() + ": " + e.getMessage());
            }
        }
        return updated;
    }

    /**
     * 列出 classpath 目录下所有文件的相对路径集合（递归）。
     */
    public static Set<String> listClasspathDirectoryFiles(String classpathDir) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        URL url = cl.getResource(classpathDir);
        if (url == null) return Set.of();

        String protocol = url.getProtocol();
        if ("file".equalsIgnoreCase(protocol)) {
            return listDirectoryFiles(Paths.get(url.toURI()));
        }
        if ("jar".equalsIgnoreCase(protocol)) {
            URI uri = url.toURI();
            String uriStr = uri.toString();
            int sep = uriStr.indexOf("!/");
            if (sep < 0) return Set.of();
            URI jarUri = URI.create(uriStr.substring(0, sep));
            try (FileSystem fs = openOrGetJarFileSystem(jarUri)) {
                Path root = fs.getPath("/" + classpathDir);
                return listDirectoryFiles(root);
            }
        }
        return Set.of();
    }

    /**
     * 列出本地目录下所有文件的相对路径集合（递归）。
     */
    private static Set<String> listDirectoryFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return Set.of();
        Set<String> files = new HashSet<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(p -> files.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        return files;
    }

    // ========== Scripts Sync ==========

    private static final String CLASSPATH_SCRIPTS_ROOT = "scripts";

    // ========== Plugins Sync ==========

    private static final String CLASSPATH_PLUGINS_ROOT = "templates/plugins";

    /**
     * 同步 classpath templates/plugins/ 下的所有插件到 workspace/plugins/
     *
     * 与 installAssociatedPlugin 不同，此方法全量同步所有插件文件，
     * 不依赖技能名称匹配。排除 example.* 示例文件。
     *
     * @param workspace 工作区根目录
     * @param overwrite 是否覆盖已存在的文件
     * @return 同步结果摘要
     */
    public static SyncResult syncPlugins(Path workspace, boolean overwrite) {
        SyncResult result = new SyncResult();
        Path targetDir = workspace.resolve("plugins");

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        try {
            Files.createDirectories(targetDir);

            URL pluginsUrl = cl.getResource(CLASSPATH_PLUGINS_ROOT);
            if (pluginsUrl == null) {
                return result;
            }

            List<FileEntry> entries = scanPluginFiles(pluginsUrl);
            for (FileEntry entry : entries) {
                // 排除示例文件
                if (entry.fileName.startsWith("example.")) {
                    continue;
                }
                Path target = targetDir.resolve(entry.fileName);
                try {
                    if (Files.exists(target) && !overwrite) {
                        result.skipped.add(entry.fileName);
                        continue;
                    }
                    try (InputStream is = cl.getResourceAsStream(entry.classpathPath)) {
                        if (is != null) {
                            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                            result.installed.add(entry.fileName);
                        } else {
                            result.failed.add(entry.fileName + " (resource not found)");
                        }
                    }
                } catch (Exception e) {
                    result.failed.add(entry.fileName + " (" + e.getMessage() + ")");
                }
            }
        } catch (Exception e) {
            System.err.println("Plugins sync failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * 仅同步新增插件（不覆盖已有）
     */
    public static SyncResult syncPlugins(Path workspace) {
        return syncPlugins(workspace, false);
    }

    private static List<FileEntry> scanPluginFiles(URL url) throws Exception {
        if (url == null) return List.of();
        String protocol = url.getProtocol();

        if ("file".equalsIgnoreCase(protocol)) {
            return scanFileEntries(Paths.get(url.toURI()), "");
        }
        if ("jar".equalsIgnoreCase(protocol)) {
            URI uri = url.toURI();
            String uriStr = uri.toString();
            int sep = uriStr.indexOf("!/");
            if (sep < 0) return List.of();
            URI jarUri = URI.create(uriStr.substring(0, sep));
            try (FileSystem fs = openOrGetJarFileSystem(jarUri)) {
                Path root = fs.getPath("/" + CLASSPATH_PLUGINS_ROOT);
                return scanFileEntries(root, "");
            }
        }
        return List.of();
    }

    /**
     * 通用文件扫描 + 结果类（plugins / scripts 共用）
     */

    public static final class SyncResult {
        private final List<String> installed = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();

        public List<String> getInstalled() { return installed; }
        public List<String> getSkipped() { return skipped; }
        public List<String> getFailed() { return failed; }
        public boolean hasInstalled() { return !installed.isEmpty(); }
    }

    private static List<FileEntry> scanFileEntries(Path dir, String prefix) throws IOException {
        List<FileEntry> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String relPath = prefix.isEmpty() ? name : prefix + "/" + name;

                if (Files.isDirectory(p)) {
                    result.addAll(scanFileEntries(p, relPath));
                } else if (Files.isRegularFile(p)) {
                    result.add(new FileEntry(name, CLASSPATH_PLUGINS_ROOT + "/" + relPath));
                }
            }
        }
        return result;
    }

    private static final class FileEntry {
        final String fileName;
        final String classpathPath;

        FileEntry(String fileName, String classpathPath) {
            this.fileName = fileName;
            this.classpathPath = classpathPath;
        }
    }

    /**
     * 同步 classpath scripts/ 下的所有脚本到 workspace/scripts/
     *
     * 用途：将项目内置脚本（如 install-gitnexus.js）部署到工作空间，
     * 方便用户在运行时直接调用。
     *
     * 行为：
     * - 扫描 classpath 上 scripts/ 目录
     * - 将每个文件复制到 workspace/scripts/，保留相对路径结构
     * - 已存在的文件默认跳过（不覆盖），避免破坏用户修改
     *
     * @param workspace 工作区根目录
     * @param overwrite 是否覆盖已存在的文件
     * @return 同步结果摘要
     */
    public static SyncScriptsResult syncScripts(Path workspace, boolean overwrite) {
        SyncScriptsResult result = new SyncScriptsResult();
        Path targetDir = workspace.resolve("scripts");

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = BuiltinSkillsInstaller.class.getClassLoader();

        try {
            Files.createDirectories(targetDir);

            URL scriptsUrl = cl.getResource(CLASSPATH_SCRIPTS_ROOT);
            if (scriptsUrl == null) {
                // 没有内置 scripts 目录，正常情况
                return result;
            }

            List<ScriptEntry> entries = scanScriptsFromUrl(scriptsUrl);
            for (ScriptEntry entry : entries) {
                Path target = targetDir.resolve(entry.relativePath);
                try {
                    if (Files.exists(target) && !overwrite) {
                        result.skipped.add(entry.relativePath);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream is = cl.getResourceAsStream(entry.classpathPath)) {
                        if (is != null) {
                            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                            result.installed.add(entry.relativePath);
                        } else {
                            result.failed.add(entry.relativePath + " (resource not found)");
                        }
                    }
                } catch (Exception e) {
                    result.failed.add(entry.relativePath + " (" + e.getMessage() + ")");
                }
            }
        } catch (Exception e) {
            System.err.println("Scripts sync failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * 仅同步新增脚本（不覆盖已有）
     */
    public static SyncScriptsResult syncScripts(Path workspace) {
        return syncScripts(workspace, false);
    }

    private static List<ScriptEntry> scanScriptsFromUrl(URL url) throws Exception {
        if (url == null) return List.of();
        String protocol = url.getProtocol();

        if ("file".equalsIgnoreCase(protocol)) {
            return scanScriptFiles(Paths.get(url.toURI()), "");
        }
        if ("jar".equalsIgnoreCase(protocol)) {
            URI uri = url.toURI();
            String uriStr = uri.toString();
            int sep = uriStr.indexOf("!/");
            if (sep < 0) return List.of();
            URI jarUri = URI.create(uriStr.substring(0, sep));
            try (FileSystem fs = openOrGetJarFileSystem(jarUri)) {
                Path root = fs.getPath("/" + CLASSPATH_SCRIPTS_ROOT);
                return scanScriptFiles(root, "");
            }
        }
        return List.of();
    }

    private static List<ScriptEntry> scanScriptFiles(Path dir, String prefix) throws IOException {
        List<ScriptEntry> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String relPath = prefix.isEmpty() ? name : prefix + "/" + name;

                if (Files.isDirectory(p)) {
                    result.addAll(scanScriptFiles(p, relPath));
                } else if (Files.isRegularFile(p)) {
                    result.add(new ScriptEntry(
                        relPath,
                        CLASSPATH_SCRIPTS_ROOT + "/" + relPath
                    ));
                }
            }
        }
        return result;
    }

    /**
     * 打印 scripts 同步结果
     */
    public static void printScriptsSyncResult(SyncScriptsResult result) {
        if (result.installed.isEmpty() && result.skipped.isEmpty() && result.failed.isEmpty()) {
            return; // 没有任何脚本，静默
        }

        System.out.println();
        System.out.println("Built-in scripts sync result:");
        for (String s : result.installed) {
            System.out.println("  ✓ Script installed: " + s);
        }
        for (String s : result.skipped) {
            System.out.println("  - Script skipped (exists): " + s);
        }
        for (String s : result.failed) {
            System.out.println("  ✗ Script failed: " + s);
        }
    }

    public static final class SyncScriptsResult {
        private final List<String> installed = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> failed = new ArrayList<>();

        public List<String> getInstalled() { return installed; }
        public List<String> getSkipped() { return skipped; }
        public List<String> getFailed() { return failed; }
        public boolean hasInstalled() { return !installed.isEmpty(); }
    }

    private static final class ScriptEntry {
        final String relativePath;
        final String classpathPath;

        ScriptEntry(String relativePath, String classpathPath) {
            this.relativePath = relativePath;
            this.classpathPath = classpathPath;
        }
    }
}