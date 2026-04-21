package agent.tool.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ripgrep configuration - provides ripgrep binary path with fallback logic.
 * Port of Open Claude Code's ripgrep.ts getRipgrepConfig().
 *
 * Modes:
 * - system: Use system ripgrep (rg) if available
 * - builtin: Use vendored ripgrep binary bundled with the application
 *
 * Vendored binary is extracted to ~/.javaclawbot/vendor/ripgrep/ for persistence.
 */
public class RipgrepConfig {

    public enum Mode {
        SYSTEM,
        BUILTIN
    }

    private final Mode mode;
    private final String command;
    private final Path binaryPath;

    // Cached extracted binary path (persists across calls)
    private static Path cachedBuiltinPath = null;

    public RipgrepConfig(Mode mode, String command, Path binaryPath) {
        this.mode = mode;
        this.command = command;
        this.binaryPath = binaryPath;
    }

    public Mode getMode() {
        return mode;
    }

    public String getCommand() {
        return command;
    }

    public Path getBinaryPath() {
        return binaryPath;
    }

    /**
     * Get the ripgrep configuration with fallback logic.
     * Port of ripgrep.ts getRipgrepConfig().
     *
     * Strategy:
     * 1. Try to find system ripgrep (rg) in PATH
     * 2. If not found, use vendored ripgrep binary (extracted to ~/.javaclawbot/vendor/ripgrep/)
     */
    public static RipgrepConfig getRipgrepConfig() {
        // Try to find system ripgrep
        String systemRg = findSystemExecutable("rg");
        if (systemRg != null) {
            // SECURITY: Use command name 'rg' instead of systemPath to prevent PATH hijacking
            // If we used systemPath, a malicious ./rg.exe in current directory could be executed
            // Using just 'rg' lets the OS resolve it safely
            return new RipgrepConfig(Mode.SYSTEM, "rg", null);
        }

        // Fall back to vendored ripgrep
        Path vendorPath = getOrExtractVendoredRipgrep();
        if (vendorPath != null && Files.exists(vendorPath)) {
            return new RipgrepConfig(Mode.BUILTIN, vendorPath.toString(), vendorPath);
        }

        // Last resort: try system rg anyway (will fail with proper error message)
        return new RipgrepConfig(Mode.SYSTEM, "rg", null);
    }

    /**
     * Find system executable in PATH.
     */
    private static String findSystemExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }

        String[] dirs = path.split(System.getProperty("path.separator"));
        String exeName = System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;

        for (String dir : dirs) {
            Path fullPath = Paths.get(dir, exeName);
            if (Files.exists(fullPath) && Files.isExecutable(fullPath)) {
                return fullPath.toString();
            }
        }
        return null;
    }

    /**
     * Get platform string (darwin, win32, linux).
     */
    private static String getPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        } else if (os.contains("windows")) {
            return "win32";
        } else if (os.contains("linux")) {
            return "linux";
        }
        return null;
    }

    /**
     * Get arch directory string (x64-darwin, arm64-darwin, x64-win32, etc.).
     */
    private static String getArchDir(String platform) {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64-" + platform;
        }
        return "x64-" + platform;
    }

    /**
     * Get the data directory path (~/.javaclawbot).
     */
    private static Path getDataPath() {
        return Paths.get(System.getProperty("user.home"), ".javaclawbot");
    }

    /**
     * Get the vendored ripgrep binary path, extracting from JAR if needed.
     * The binary is extracted to ~/.javaclawbot/vendor/ripgrep/<arch>/rg (or rg.exe)
     */
    private static Path getOrExtractVendoredRipgrep() {
        String platform = getPlatform();
        if (platform == null) {
            return null;
        }

        String archDir = getArchDir(platform);
        String exeName = platform.equals("win32") ? "rg.exe" : "rg";

        // Return cached path if already extracted
        if (cachedBuiltinPath != null && Files.exists(cachedBuiltinPath)) {
            return cachedBuiltinPath;
        }

        // Check development mode paths first
        Path devPath = Path.of("src/main/resources/vendor/ripgrep/" + archDir + "/" + exeName);
        if (Files.exists(devPath)) {
            cachedBuiltinPath = devPath;
            return devPath;
        }

        // Target classes path (when running from IDE)
        Path classesPath = Path.of("target/classes/vendor/ripgrep/" + archDir + "/" + exeName);
        if (Files.exists(classesPath)) {
            cachedBuiltinPath = classesPath;
            return classesPath;
        }

        // Extract from JAR to ~/.javaclawbot/vendor/ripgrep/
        try {
            cachedBuiltinPath = extractVendoredRipgrep(platform, archDir, exeName);
            return cachedBuiltinPath;
        } catch (IOException e) {
            System.err.println("Failed to extract vendored ripgrep: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract vendored ripgrep binary from JAR to ~/.javaclawbot/vendor/ripgrep/
     */
    private static Path extractVendoredRipgrep(String platform, String archDir, String exeName) throws IOException {
        String resourcePath = "/vendor/ripgrep/" + archDir + "/" + exeName;

        // Create vendor directory: ~/.javaclawbot/vendor/ripgrep/<arch>/
        Path vendorDir = getDataPath().resolve("vendor").resolve("ripgrep").resolve(archDir);
        Files.createDirectories(vendorDir);

        Path targetPath = vendorDir.resolve(exeName);

        // Copy resource to target path
        try (InputStream is = RipgrepConfig.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Vendored ripgrep not found in classpath: " + resourcePath);
            }
            try (OutputStream os = Files.newOutputStream(targetPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }

        // Make executable (not needed on Windows but doesn't hurt)
        targetPath.toFile().setExecutable(true, false);

        return targetPath;
    }

    /**
     * Get the resolved ripgrep binary path for execution.
     * For BUILTIN mode, this returns the extracted binary path.
     */
    public Path getExecutablePath() throws IOException {
        if (mode == Mode.SYSTEM) {
            return Path.of(command);
        }

        if (binaryPath == null) {
            return Path.of(command);
        }

        // If binaryPath exists, use it
        if (Files.exists(binaryPath)) {
            return binaryPath;
        }

        throw new IOException("Ripgrep binary not found: " + binaryPath);
    }
}
