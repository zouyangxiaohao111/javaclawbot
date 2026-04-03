package agent.tool.shell;

/**
 * Atomic replication of Claude Code shell/resolveDefaultShell.ts.
 *
 * Original source: src/utils/shell/resolveDefaultShell.ts
 *
 * Resolve the default shell for input-box `!` commands.
 *
 * Resolution order (docs/design/ps-shell-selection.md §4.2):
 *   settings.defaultShell → 'bash'
 *
 * Platform default is 'bash' everywhere — we do NOT auto-flip Windows to
 * PowerShell (would break existing Windows users with bash hooks).
 */
public final class ResolveDefaultShell {

    private ResolveDefaultShell() {}

    /**
     * Resolve the default shell type.
     *
     * Original source: src/utils/shell/resolveDefaultShell.ts → resolveDefaultShell()
     *
     * Always returns "bash" — the default for all platforms.
     * In Claude Code, this reads from settings.defaultShell, but in Java
     * we don't have a settings system, so always default to bash.
     *
     * @return "bash" always
     */
    public static String resolveDefaultShell() {
        // Original: resolveDefaultShell.ts lines 12-14
        // return getInitialSettings().defaultShell ?? 'bash';
        return "bash";
    }
}
