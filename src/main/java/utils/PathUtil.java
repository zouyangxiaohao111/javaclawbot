package utils;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathUtil {
    private PathUtil() {}

    /**
     * Resolve path against workspace (if relative) and enforce directory restriction.
     * Mirrors Python _resolve_path().
     */
    public static Path resolvePath(String path, Path workspace, Path allowedDir) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is empty");
        }

        Path p = expandUser(path);

        if (!p.isAbsolute() && workspace != null) {
            p = workspace.resolve(p);
        }

        Path resolved = p.toAbsolutePath().normalize();

        if (allowedDir != null) {
            Path allow = allowedDir.toAbsolutePath().normalize();
            if (!resolved.startsWith(allow)) {
                throw new SecurityException("Path " + path + " is outside allowed directory " + allowedDir);
            }
        }

        return resolved;
    }

    private static Path expandUser(String raw) {
        String s = raw.trim();
        if (s.startsWith("~")) {
            String home = System.getProperty("user.home", "");
            if (s.equals("~")) return Paths.get(home);
            if (s.startsWith("~/") || s.startsWith("~\\")) {
                return Paths.get(home).resolve(s.substring(2));
            }
            // "~user" not supported (keep as-is)
        }
        return Paths.get(s);
    }
}