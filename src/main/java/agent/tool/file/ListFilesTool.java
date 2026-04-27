package agent.tool.file;

import agent.tool.Tool;
import cn.hutool.core.util.StrUtil;
import providers.cli.ProjectRegistry;
import utils.PathUtil;

import java.nio.file.*;
import java.util.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static agent.tool.file.FileSystemTools.asString;
import static agent.tool.file.FileSystemTools.schemaPathOnly;

/**
 * ListFilesTool - 列出指定目录下的所有文件和文件夹
 *
 * 以树形结构展示，方便 AI 理解目录结构：
 * -- dir1
 * --- subdir1
 * ---- file1.txt
 * ---- file2.txt
 * --- file3.txt
 * -- dir2
 * --- file4.txt
 * - file5.txt
 */
public final class ListFilesTool extends Tool {
    private final Path workspace;
    private final Path allowedDir;
    private final Supplier<ProjectRegistry> projectRegistrySupplier;

    public ListFilesTool(Path workspace, Path allowedDir, Supplier<ProjectRegistry> projectRegistrySupplier) {
        this.workspace = workspace;
        this.allowedDir = allowedDir;
        this.projectRegistrySupplier = projectRegistrySupplier;
    }

    public ListFilesTool(Path workspace, Path allowedDir) {
        this(workspace, allowedDir, null);
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List all files and directories under a given path recursively.\n" +
               "Returns a tree-like structure showing the directory hierarchy.\n" +
               "Directories are shown first, followed by files within each directory.\n" +
               "Usage: Provide a path to list its contents recursively.";
    }

    @Override
    public Map<String, Object> parameters() {
        return schemaPathOnly("The directory path to list recursively");
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        String path = asString(args.get("path"));
        try {
            Path dirPath;
            if (path != null && !path.isEmpty()) {
                dirPath = PathUtil.resolvePath(path, workspace, allowedDir);
            } else {
                // 默认路径逻辑：优先使用主项目路径
                if (projectRegistrySupplier != null) {
                    ProjectRegistry registry = projectRegistrySupplier.get();
                    String mainProjectPath = registry.getMainProjectPath();
                    if (StrUtil.isNotBlank(mainProjectPath)) {
                        dirPath = Paths.get(mainProjectPath);
                    } else {
                        dirPath = workspace;
                    }
                } else {
                    dirPath = workspace;
                }
            }
            if (!Files.exists(dirPath)) {
                return CompletableFuture.completedFuture("Error: Directory not found: " + (path != null ? path : dirPath.toString()));
            }
            if (!Files.isDirectory(dirPath)) {
                return CompletableFuture.completedFuture("Error: Not a directory: " + path);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(dirPath.getFileName().toString()).append("/\n");
            listDirectory(dirPath, "", sb);

            if (sb.length() == 0) {
                return CompletableFuture.completedFuture("Directory " + path + " is empty");
            }

            return CompletableFuture.completedFuture(sb.toString());
        } catch (SecurityException se) {
            return CompletableFuture.completedFuture("Error: " + se.getMessage());
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error listing directory: " + e.getMessage());
        }
    }

    /**
     * 递归列出目录内容
     *
     * @param dir 当前目录
     * @param indent 当前缩进
     * @param sb 输出缓冲
     */
    private void listDirectory(Path dir, String indent, StringBuilder sb) {
        // 收集并排序：目录在前，文件在后
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                } else {
                    files.add(p);
                }
            });
        } catch (Exception e) {
            sb.append(indent).append("-- [Error reading directory]\n");
            return;
        }

        // 排序
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

        // 输出目录
        for (Path d : dirs) {
            String name = d.getFileName().toString();
            sb.append(indent).append("-- ").append(name).append("/\n");
            listDirectory(d, indent + "-", sb);
        }

        // 输出文件
        for (Path f : files) {
            String name = f.getFileName().toString();
            sb.append(indent).append("- ").append(name).append("\n");
        }
    }
}