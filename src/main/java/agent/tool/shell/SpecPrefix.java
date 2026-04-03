package agent.tool.shell;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Atomic replication of Claude Code shell/specPrefix.ts.
 *
 * Original source: src/utils/shell/specPrefix.ts
 *
 * Fig-spec-driven command prefix extraction.
 * Given a command name + args array + its @withfig/autocomplete spec, walks
 * the spec to find how deep into the args a meaningful prefix extends.
 * `git -C /repo status --short` → `git status` (spec says -C takes a value,
 * skip it, find `status` as a known subcommand).
 *
 * Pure over (string, string[], CommandSpec) — no parser dependency.
 */
public final class SpecPrefix {

    private SpecPrefix() {}

    // ========================================================================
    // DEPTH_RULES — overrides for commands whose fig specs aren't available
    // ========================================================================

    /**
     * Overrides for commands whose fig specs aren't available at runtime.
     *
     * Original source: src/utils/shell/specPrefix.ts → DEPTH_RULES
     *
     * Without these, calculateDepth falls back to 2, producing overly broad prefixes.
     */
    public static final Map<String, Integer> DEPTH_RULES = Map.ofEntries(
            Map.entry("rg", 2),
            Map.entry("pre-commit", 2),
            Map.entry("gcloud", 4),
            Map.entry("gcloud compute", 6),
            Map.entry("gcloud beta", 6),
            Map.entry("aws", 4),
            Map.entry("az", 4),
            Map.entry("kubectl", 3),
            Map.entry("docker", 3),
            Map.entry("dotnet", 3),
            Map.entry("git push", 2)
    );

    // ========================================================================
    // CommandSpec — stub for the fig autocomplete spec type
    // ========================================================================

    /**
     * Stub for the @withfig/autocomplete CommandSpec type.
     *
     * Original source: src/utils/bash/registry.ts → CommandSpec
     *
     * In the full Claude Code, this is loaded from @withfig/autocomplete specs.
     * In Java, this is a stub that can be populated from external spec sources.
     */
    public record CommandSpec(
            List<CommandArg> args,
            List<CommandOption> options,
            List<CommandSubcommand> subcommands
    ) {
        public CommandSpec {
            args = args != null ? args : List.of();
            options = options != null ? options : List.of();
            subcommands = subcommands != null ? subcommands : List.of();
        }
    }

    /**
     * Command argument spec.
     */
    public record CommandArg(
            boolean isCommand,
            boolean isModule,
            boolean isVariadic,
            boolean isOptional,
            boolean isDangerous
    ) {}

    /**
     * Command option spec.
     */
    public record CommandOption(
            List<String> name,
            CommandArg args
    ) {}

    /**
     * Command subcommand spec.
     */
    public record CommandSubcommand(
            List<String> name,
            List<CommandArg> args,
            List<CommandOption> options,
            List<CommandSubcommand> subcommands
    ) {}

    // ========================================================================
    // URL_PROTOCOLS — Original source: specPrefix.ts line 16
    // ========================================================================

    private static final List<String> URL_PROTOCOLS = List.of(
            "http://", "https://", "ftp://"
    );

    // ========================================================================
    // buildPrefix — Atomic replication of specPrefix.ts buildPrefix()
    // ========================================================================

    /**
     * Build a command prefix by walking args with the spec.
     *
     * Original source: src/utils/shell/specPrefix.ts → buildPrefix()
     *
     * @param command The command name (e.g., "git")
     * @param args    The argument tokens
     * @param spec    The fig autocomplete spec (can be null)
     * @return The prefix string (e.g., "git status")
     */
    public static CompletableFuture<String> buildPrefix(
            String command,
            String[] args,
            CommandSpec spec
    ) {
        return CompletableFuture.supplyAsync(() -> {
            int maxDepth = calculateDepth(command, args, spec);
            List<String> parts = new ArrayList<>();
            parts.add(command);
            boolean hasSubcommands = spec != null && spec.subcommands() != null && !spec.subcommands().isEmpty();
            boolean foundSubcommand = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isEmpty() || parts.size() >= maxDepth) break;

                if (arg.startsWith("-")) {
                    // Special case: python -c should stop after -c
                    if ("-c".equals(arg) && ("python".equalsIgnoreCase(command) || "python3".equalsIgnoreCase(command))) {
                        break;
                    }

                    // Check for isCommand/isModule flags
                    if (spec != null && spec.options() != null) {
                        CommandOption option = findOption(spec.options(), arg);
                        if (option != null && option.args() != null &&
                                (option.args().isCommand() || option.args().isModule())) {
                            parts.add(arg);
                            continue;
                        }
                    }

                    // For commands with subcommands, skip global flags to find subcommand
                    if (hasSubcommands && !foundSubcommand) {
                        if (flagTakesArg(arg, i + 1 < args.length ? args[i + 1] : null, spec)) {
                            i++;
                        }
                        continue;
                    }
                    break; // Stop at flags
                }

                if (shouldStopAtArg(arg, Arrays.copyOf(args, i), spec)) break;
                if (hasSubcommands && !foundSubcommand) {
                    foundSubcommand = isKnownSubcommand(arg, spec);
                }
                parts.add(arg);
            }

            return String.join(" ", parts);
        });
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Calculate the maximum depth for prefix extraction.
     *
     * Original source: src/utils/shell/specPrefix.ts → calculateDepth()
     */
    static int calculateDepth(String command, String[] args, CommandSpec spec) {
        String firstSubcommand = findFirstSubcommand(args, spec);
        String commandLower = command.toLowerCase();
        String key = firstSubcommand != null
                ? commandLower + " " + firstSubcommand.toLowerCase()
                : commandLower;

        if (DEPTH_RULES.containsKey(key)) return DEPTH_RULES.get(key);
        if (DEPTH_RULES.containsKey(commandLower)) return DEPTH_RULES.get(commandLower);
        if (spec == null) return 2;

        // Check for isCommand/isModule flags
        if (spec.options() != null) {
            for (String arg : args) {
                if (arg == null || !arg.startsWith("-")) continue;
                CommandOption option = findOption(spec.options(), arg);
                if (option != null && option.args() != null &&
                        (option.args().isCommand() || option.args().isModule())) {
                    return 3;
                }
            }
        }

        // Subcommand depth calculation
        if (firstSubcommand != null && spec.subcommands() != null) {
            CommandSubcommand sub = findSubcommand(spec.subcommands(), firstSubcommand);
            if (sub != null) {
                if (sub.args() != null && !sub.args().isEmpty()) {
                    if (sub.args().stream().anyMatch(a -> a.isCommand())) return 3;
                    if (sub.args().stream().anyMatch(a -> a.isVariadic())) return 2;
                }
                if (sub.subcommands() != null && !sub.subcommands().isEmpty()) return 4;
                if (sub.args() == null || sub.args().isEmpty()) return 2;
                return 3;
            }
        }

        // Root-level args
        if (spec.args() != null && !spec.args().isEmpty()) {
            if (spec.args().stream().anyMatch(a -> a.isCommand())) {
                return 2;
            }
            if (spec.subcommands() == null || spec.subcommands().isEmpty()) {
                if (spec.args().stream().anyMatch(a -> a.isVariadic())) return 1;
                CommandArg first = spec.args().get(0);
                if (first != null && !first.isOptional()) return 2;
            }
        }

        return (spec.args() != null && spec.args().stream().anyMatch(a -> a.isDangerous())) ? 3 : 2;
    }

    /**
     * Find the first subcommand by skipping flags and their values.
     *
     * Original source: src/utils/shell/specPrefix.ts → findFirstSubcommand()
     */
    static String findFirstSubcommand(String[] args, CommandSpec spec) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isEmpty()) continue;
            if (arg.startsWith("-")) {
                if (flagTakesArg(arg, i + 1 < args.length ? args[i + 1] : null, spec)) i++;
                continue;
            }
            if (spec == null || spec.subcommands() == null || spec.subcommands().isEmpty()) return arg;
            if (isKnownSubcommand(arg, spec)) return arg;
        }
        return null;
    }

    /**
     * Check if an argument matches a known subcommand.
     *
     * Original source: src/utils/shell/specPrefix.ts → isKnownSubcommand()
     */
    static boolean isKnownSubcommand(String arg, CommandSpec spec) {
        if (spec == null || spec.subcommands() == null) return false;
        String argLower = arg.toLowerCase();
        return spec.subcommands().stream().anyMatch(sub -> {
            Object name = sub.name();
            if (name instanceof List) {
                return ((List<?>) name).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .anyMatch(n -> n.equalsIgnoreCase(argLower));
            }
            return false;
        });
    }



    /**
     * Check if a flag takes an argument.
     *
     * Original source: src/utils/shell/specPrefix.ts → flagTakesArg()
     */
    static boolean flagTakesArg(String flag, String nextArg, CommandSpec spec) {
        if (spec != null && spec.options() != null) {
            CommandOption option = findOption(spec.options(), flag);
            if (option != null) return option.args() != null;
        }
        // Heuristic: if next arg isn't a flag and isn't a known subcommand
        if (spec != null && spec.subcommands() != null && !spec.subcommands().isEmpty()) {
            if (nextArg != null && !nextArg.startsWith("-")) {
                return !isKnownSubcommand(nextArg, spec);
            }
        }
        return false;
    }

    /**
     * Check if we should stop building prefix at this argument.
     *
     * Original source: src/utils/shell/specPrefix.ts → shouldStopAtArg()
     */
    static boolean shouldStopAtArg(String arg, String[] prevArgs, CommandSpec spec) {
        if (arg.startsWith("-")) return true;

        int dotIndex = arg.lastIndexOf('.');
        boolean hasExtension = dotIndex > 0 && dotIndex < arg.length() - 1
                && !arg.substring(dotIndex + 1).contains(":");
        boolean hasFile = arg.contains("/") || hasExtension;
        boolean hasUrl = URL_PROTOCOLS.stream().anyMatch(arg::startsWith);

        if (!hasFile && !hasUrl) return false;

        // Check if we're after a -m flag for python modules
        if (spec != null && spec.options() != null && prevArgs.length > 0) {
            String lastArg = prevArgs[prevArgs.length - 1];
            if ("-m".equals(lastArg)) {
                CommandOption option = findOption(spec.options(), "-m");
                if (option != null && option.args() != null && option.args().isModule()) {
                    return false;
                }
            }
        }

        return true;
    }

    // ========================================================================
    // Private utility methods
    // ========================================================================

    private static CommandOption findOption(List<CommandOption> options, String flag) {
        for (CommandOption opt : options) {
            if (opt.name() != null) {
                for (String name : opt.name()) {
                    if (flag.equals(name)) return opt;
                }
            }
        }
        return null;
    }

    private static CommandSubcommand findSubcommand(List<CommandSubcommand> subcommands, String name) {
        String nameLower = name.toLowerCase();
        for (CommandSubcommand sub : subcommands) {
            if (sub.name() != null) {
                for (String n : sub.name()) {
                    if (n.equalsIgnoreCase(nameLower)) return sub;
                }
            }
        }
        return null;
    }
}
