package agent.tool.shell;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Atomic replication of Claude Code shell/powershellDetection.ts.
 *
 * Original source: src/utils/shell/powershellDetection.ts
 *
 * Attempts to find PowerShell on the system via PATH.
 * Prefers pwsh (PowerShell Core 7+), falls back to powershell (5.1).
 *
 * On Linux, if PATH resolves to a snap launcher (/snap/...) — directly or
 * via a symlink chain like /usr/bin/pwsh → /snap/bin/pwsh — probe known
 * apt/rpm install locations instead: the snap launcher can hang in
 * subprocesses while snapd initializes confinement, but the underlying
 * binary at /opt/microsoft/powershell/7/pwsh is reliable.
 */
public final class PowerShellDetection {

    private PowerShellDetection() {}

    /**
     * PowerShell edition.
     *
     * Original source: src/utils/shell/powershellDetection.ts → PowerShellEdition
     */
    public enum PowerShellEdition {
        /** PowerShell 7+ (pwsh): supports &&, ||, ?:, ?? */
        CORE,
        /** Windows PowerShell 5.1 (powershell): no pipeline chain operators, stderr-sets-$? bug */
        DESKTOP
    }

    // Cached PowerShell path (memoized)
    // Original: powershellDetection.ts lines 59-60
    private static volatile CompletableFuture<String> cachedPowerShellPath = null;

    /**
     * Probe a path to see if it's a valid file.
     *
     * Original source: src/utils/shell/powershellDetection.ts → probePath()
     */
    private static String probePath(String p) {
        try {
            return Files.isRegularFile(Paths.get(p)) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Attempts to find PowerShell on the system via PATH.
     * Prefers pwsh (PowerShell Core 7+), falls back to powershell (5.1).
     *
     * Original source: src/utils/shell/powershellDetection.ts → findPowerShell()
     *
     * On Linux, handles snap launcher issues by probing direct binary paths.
     *
     * @return Path to PowerShell, or null if not found
     */
    public static String findPowerShell() {
        // Original: powershellDetection.ts lines 24-57

        // 1. Try pwsh (PowerShell Core 7+)
        String pwshPath = Shell.which("pwsh");
        if (pwshPath != null) {
            // Snap launcher hangs in subprocesses. Prefer the direct binary.
            // Original: powershellDetection.ts lines 28-49
            if (isLinux()) {
                String resolved = resolveSymlink(pwshPath);
                if (pwshPath.startsWith("/snap/") || resolved.startsWith("/snap/")) {
                    String direct = probePath("/opt/microsoft/powershell/7/pwsh");
                    if (direct == null) {
                        direct = probePath("/usr/bin/pwsh");
                    }
                    if (direct != null) {
                        String directResolved = resolveSymlink(direct);
                        if (!direct.startsWith("/snap/") && !directResolved.startsWith("/snap/")) {
                            return direct;
                        }
                    }
                }
            }
            return pwshPath;
        }

        // 2. Try powershell (Windows PowerShell 5.1)
        String powershellPath = Shell.which("powershell");
        if (powershellPath != null) {
            return powershellPath;
        }

        return null;
    }

    /**
     * Gets the cached PowerShell path. Returns a memoized path.
     *
     * Original source: src/utils/shell/powershellDetection.ts → getCachedPowerShellPath()
     *
     * @return Path to PowerShell, or null if not found
     */
    public static String getCachedPowerShellPath() {
        if (cachedPowerShellPath == null) {
            synchronized (PowerShellDetection.class) {
                if (cachedPowerShellPath == null) {
                    String path = findPowerShell();
                    cachedPowerShellPath = CompletableFuture.completedFuture(path);
                }
            }
        }
        return cachedPowerShellPath.join();
    }

    /**
     * Infers the PowerShell edition from the binary name without spawning.
     *
     * Original source: src/utils/shell/powershellDetection.ts → getPowerShellEdition()
     *
     * - pwsh / pwsh.exe → CORE (PowerShell 7+: supports &&, ||, ?:, ??)
     * - powershell / powershell.exe → DESKTOP (Windows PowerShell 5.1)
     *
     * @return CORE or DESKTOP, or null if PowerShell not found
     */
    public static PowerShellEdition getPowerShellEdition() {
        String p = getCachedPowerShellPath();
        if (p == null) return null;

        // basename without extension, case-insensitive
        // Original: powershellDetection.ts lines 91-99
        String base = p.substring(p.lastIndexOf('/') + 1)
                .substring(p.lastIndexOf('\\') + 1)  // Windows path separator
                .toLowerCase()
                .replace(".exe", "");

        return "pwsh".equals(base) ? PowerShellEdition.CORE : PowerShellEdition.DESKTOP;
    }

    /**
     * Resets the cached PowerShell path. Only for testing.
     *
     * Original source: src/utils/shell/powershellDetection.ts → resetPowerShellCache()
     */
    public static void resetPowerShellCache() {
        cachedPowerShellPath = null;
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private static String resolveSymlink(String path) {
        try {
            return Paths.get(path).toRealPath().toString();
        } catch (IOException e) {
            return path;
        }
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux");
    }
}
