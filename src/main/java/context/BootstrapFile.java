package context;

import java.nio.file.Path;

/**
 * Bootstrap 文件数据结构
 * 对齐 OpenClaw 的 WorkspaceBootstrapFile
 */
public class BootstrapFile {

    private final String name;
    private final Path path;
    private final String content;
    private final boolean missing;

    public BootstrapFile(String name, Path path, String content, boolean missing) {
        this.name = name;
        this.path = path;
        this.content = content;
        this.missing = missing;
    }

    public static BootstrapFile existing(String name, Path path, String content) {
        return new BootstrapFile(name, path, content, false);
    }

    public static BootstrapFile missing(String name, Path path) {
        return new BootstrapFile(name, path, null, true);
    }

    public String getName() {
        return name;
    }

    public Path getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }

    public boolean isMissing() {
        return missing;
    }

    @Override
    public String toString() {
        return "BootstrapFile{" +
                "name='" + name + '\'' +
                ", path=" + path +
                ", missing=" + missing +
                '}';
    }
}