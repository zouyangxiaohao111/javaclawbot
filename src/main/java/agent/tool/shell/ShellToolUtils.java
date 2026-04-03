package agent.tool.shell;

import java.util.List;

/**
 * Atomic replication of Claude Code shell/shellToolUtils.ts.
 *
 * Original source: src/utils/shell/shellToolUtils.ts
 *
 * Shared utilities for shell tools (BashTool, PowerShellTool):
 * - Tool name constants
 * - PowerShell tool enablement gate
 */
public final class ShellToolUtils {

    private ShellToolUtils() {}

    // ========================================================================
    // Tool name constants
    // ========================================================================

    /**
     * Bash tool name.
     *
     * Original source: src/tools/BashTool/toolName.ts → BASH_TOOL_NAME
     */
    public static final String BASH_TOOL_NAME = "Bash";

    /**
     * PowerShell tool name.
     *
     * Original source: src/tools/PowerShellTool/toolName.ts → POWERSHELL_TOOL_NAME
     */
    public static final String POWERSHELL_TOOL_NAME = "PowerShell";

    /**
     * All shell tool names.
     *
     * Original source: src/utils/shell/shellToolUtils.ts → SHELL_TOOL_NAMES
     */
    public static final List<String> SHELL_TOOL_NAMES = List.of(BASH_TOOL_NAME, POWERSHELL_TOOL_NAME);

    // ========================================================================
    // isPowerShellToolEnabled
    // ========================================================================

    /**
     * Runtime gate for PowerShellTool.
     *
     * Original source: src/utils/shell/shellToolUtils.ts → isPowerShellToolEnabled()
     *
     * Windows-only (the permission engine uses Win32-specific path normalizations).
     * Ant defaults on (opt-out via env=0); external defaults off (opt-in via env=1).
     *
     * Used by tools.ts (tool-list visibility), processBashCommand (! routing),
     * and promptShellExecution (skill frontmatter routing) so the gate is
     * consistent across all paths that invoke PowerShellTool.call().
     *
     * @return true if PowerShell tool is enabled on this platform
     */
    public static boolean isPowerShellToolEnabled() {
        // Original: shellToolUtils.ts lines 17-22

        // Only on Windows
        if (!isWindows()) return false;

        // Check USER_TYPE env var
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            // Ant: default on, opt-out via CLAUDE_CODE_USE_POWERSHELL_TOOL=0
            String psEnv = System.getenv("CLAUDE_CODE_USE_POWERSHELL_TOOL");
            return psEnv == null || !"0".equals(psEnv);
        }

        // External: default off, opt-in via CLAUDE_CODE_USE_POWERSHELL_TOOL=1
        String psEnv = System.getenv("CLAUDE_CODE_USE_POWERSHELL_TOOL");
        return "1".equals(psEnv);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
