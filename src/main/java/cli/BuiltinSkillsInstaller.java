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
}