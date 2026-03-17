package cli;

import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shell 补全命令
 * 
 * 对齐 OpenClaw 的 completion-cli.ts
 * 
 * 支持：
 * - zsh
 * - bash
 * - fish
 * - powershell
 */
@Command(
    name = "completion",
    description = "生成 shell 补全脚本"
)
public class CompletionCmd implements Runnable {

    private static final Set<String> SUPPORTED_SHELLS = Set.of("zsh", "bash", "fish", "powershell");

    @Option(names = {"-s", "--shell"}, description = "生成补全的 shell (默认: 自动检测)")
    String shell;

    @Option(names = {"-i", "--install"}, description = "安装补全脚本到 shell 配置文件")
    boolean install;

    @Option(names = {"-y", "--yes"}, description = "跳过确认 (非交互式)")
    boolean yes;

    @Option(names = {"--write-state"}, description = "写入补全脚本到状态目录")
    boolean writeState;

    @Override
    public void run() {
        // 自动检测 shell
        if (shell == null || shell.isBlank()) {
            shell = detectShellFromEnv();
        }

        if (!SUPPORTED_SHELLS.contains(shell)) {
            System.err.println("不支持的 shell: " + shell);
            System.err.println("支持的 shell: " + String.join(", ", SUPPORTED_SHELLS));
            return;
        }

        // 写入状态目录
        if (writeState) {
            writeCompletionCache(shell);
            return;
        }

        // 安装到 profile
        if (install) {
            installCompletion(shell, yes);
            return;
        }

        // 输出到 stdout
        String script = generateCompletionScript(shell);
        System.out.println(script);
    }

    /**
     * 从环境变量检测 shell
     */
    static String detectShellFromEnv() {
        String shellPath = System.getenv("SHELL");
        if (shellPath == null || shellPath.isBlank()) {
            // Windows
            String psModule = System.getenv("PSModulePath");
            if (psModule != null) {
                return "powershell";
            }
            return "zsh"; // 默认
        }

        String shellName = Paths.get(shellPath).getFileName().toString().toLowerCase();
        if (shellName.contains("zsh")) return "zsh";
        if (shellName.contains("bash")) return "bash";
        if (shellName.contains("fish")) return "fish";
        if (shellName.contains("pwsh") || shellName.contains("powershell")) return "powershell";

        return "zsh";
    }

    /**
     * 生成补全脚本
     */
    String generateCompletionScript(String shell) {
        return switch (shell) {
            case "zsh" -> generateZshCompletion();
            case "bash" -> generateBashCompletion();
            case "fish" -> generateFishCompletion();
            case "powershell" -> generatePowerShellCompletion();
            default -> "";
        };
    }

    /**
     * 生成 zsh 补全脚本
     */
    private String generateZshCompletion() {
        return """
            #compdef javaclawbot
            
            _javaclawbot_root_completion() {
              local -a commands
              local -a options
              
              _arguments -C \\
                "*::arg:->args"
            
              case $state in
                (args)
                  case $line[1] in
                    (onboard) _javaclawbot_onboard ;;
                    (gateway) _javaclawbot_gateway ;;
                    (agent) _javaclawbot_agent ;;
                    (status) _javaclawbot_status ;;
                    (channels) _javaclawbot_channels ;;
                    (cron) _javaclawbot_cron ;;
                    (provider) _javaclawbot_provider ;;
                    (cost) _javaclawbot_cost ;;
                    (completion) _javaclawbot_completion ;;
                  esac
                  ;;
              esac
            }
            
            _javaclawbot_onboard() {
              _arguments \\
                '--quickstart[Quick start mode]' \\
                '--advanced[Advanced mode]' \\
                '--accept-risk[Accept risk warning]'
            }
            
            _javaclawbot_gateway() {
              _arguments \\
                '-p+[Gateway port]:port' \\
                '--port+[Gateway port]:port' \\
                '-w+[Workspace directory]:workspace:_directories' \\
                '--workspace+[Workspace directory]:workspace:_directories' \\
                '-c+[Config file path]:config:_files' \\
                '--config+[Config file path]:config:_files' \\
                '-v[Verbose output]' \\
                '--verbose[Verbose output]'
            }
            
            _javaclawbot_agent() {
              _arguments \\
                '-m+[Message to send]:message' \\
                '--message+[Message to send]:message' \\
                '-s+[Session ID]:session' \\
                '--session+[Session ID]:session' \\
                '-w+[Workspace directory]:workspace:_directories' \\
                '--workspace+[Workspace directory]:workspace:_directories' \\
                '-c+[Config file path]:config:_files' \\
                '--config+[Config file path]:config:_files' \\
                '--markdown[Render output as Markdown]' \\
                '--no-markdown[Do not render as Markdown]' \\
                '--logs[Show runtime logs]' \\
                '--no-logs[Hide runtime logs]'
            }
            
            _javaclawbot_status() {
              _arguments
            }
            
            _javaclawbot_channels() {
              local -a subcommands
              subcommands=(
                'status:Show channel status'
                'login:Link device via QR code'
              )
              _describe 'subcommand' subcommands
            }
            
            _javaclawbot_cron() {
              local -a subcommands
              subcommands=(
                'list:List scheduled jobs'
                'add:Add a scheduled job'
                'remove:Remove a scheduled job'
                'enable:Enable or disable a job'
                'run:Manually run a job'
              )
              _describe 'subcommand' subcommands
            }
            
            _javaclawbot_provider() {
              local -a subcommands
              subcommands=(
                'login:Authenticate with an OAuth provider'
              )
              _describe 'subcommand' subcommands
            }
            
            _javaclawbot_cost() {
              _arguments \\
                '-d+[Number of days to show]:days' \\
                '--days+[Number of days to show]:days' \\
                '-s+[Show cost for specific session]:session' \\
                '--session+[Show cost for specific session]:session'
            }
            
            _javaclawbot_completion() {
              _arguments \\
                '-s+[Shell type]:shell:(zsh bash fish powershell)' \\
                '--shell+[Shell type]:shell:(zsh bash fish powershell)' \\
                '-i[Install to shell profile]' \\
                '--install[Install to shell profile]' \\
                '-y[Skip confirmation]' \\
                '--yes[Skip confirmation]' \\
                '--write-state[Write to state directory]'
            }
            
            compdef _javaclawbot_root_completion javaclawbot
            """;
    }

    /**
     * 生成 bash 补全脚本
     */
    private String generateBashCompletion() {
        return """
            _javaclawbot_completion() {
                local cur prev opts
                COMPREPLY=()
                cur="${COMP_WORDS[COMP_CWORD]}"
                prev="${COMP_WORDS[COMP_CWORD-1]}"
                
                # Top-level commands
                opts="onboard gateway agent status channels cron provider cost completion --help --version"
                
                # Subcommands
                case "${prev}" in
                    channels)
                        COMPREPLY=( $(compgen -W "status login" -- ${cur}) )
                        return 0
                        ;;
                    cron)
                        COMPREPLY=( $(compgen -W "list add remove enable run" -- ${cur}) )
                        return 0
                        ;;
                    provider)
                        COMPREPLY=( $(compgen -W "login" -- ${cur}) )
                        return 0
                        ;;
                    --shell|-s)
                        COMPREPLY=( $(compgen -W "zsh bash fish powershell" -- ${cur}) )
                        return 0
                        ;;
                esac
                
                # File completion for certain options
                case "${prev}" in
                    --config|-c|--workspace|-w)
                        COMPREPLY=( $(compgen -f -- ${cur}) )
                        return 0
                        ;;
                esac
                
                if [[ ${cur} == -* ]] ; then
                    # Option completion based on command
                    case "${COMP_WORDS[1]}" in
                        gateway)
                            opts="-p --port -w --workspace -c --config -v --verbose"
                            ;;
                        agent)
                            opts="-m --message -s --session -w --workspace -c --config --markdown --no-markdown --logs --no-logs"
                            ;;
                        cost)
                            opts="-d --days -s --session"
                            ;;
                        completion)
                            opts="-s --shell -i --install -y --yes --write-state"
                            ;;
                        onboard)
                            opts="--quickstart --advanced --accept-risk"
                            ;;
                    esac
                    COMPREPLY=( $(compgen -W "${opts}" -- ${cur}) )
                    return 0
                fi
                
                COMPREPLY=( $(compgen -W "${opts}" -- ${cur}) )
            }
            
            complete -F _javaclawbot_completion javaclawbot
            """;
    }

    /**
     * 生成 fish 补全脚本
     */
    private String generateFishCompletion() {
        return """
            # javaclawbot completion for fish
            
            # Root commands
            complete -c javaclawbot -n __fish_use_subcommand -a onboard -d 'Initialize javaclawbot configuration'
            complete -c javaclawbot -n __fish_use_subcommand -a gateway -d 'Start the javaclawbot gateway'
            complete -c javaclawbot -n __fish_use_subcommand -a agent -d 'Interact with the agent directly'
            complete -c javaclawbot -n __fish_use_subcommand -a status -d 'Show javaclawbot status'
            complete -c javaclawbot -n __fish_use_subcommand -a channels -d 'Manage channels'
            complete -c javaclawbot -n __fish_use_subcommand -a cron -d 'Manage scheduled tasks'
            complete -c javaclawbot -n __fish_use_subcommand -a provider -d 'Manage providers'
            complete -c javaclawbot -n __fish_use_subcommand -a cost -d 'Show session cost and usage'
            complete -c javaclawbot -n __fish_use_subcommand -a completion -d 'Generate shell completion'
            
            # Global options
            complete -c javaclawbot -n __fish_use_subcommand -l help -d 'Show help'
            complete -c javaclawbot -n __fish_use_subcommand -l version -d 'Show version'
            
            # onboard options
            complete -c javaclawbot -n '__fish_seen_subcommand_from onboard' -l quickstart -d 'Quick start mode'
            complete -c javaclawbot -n '__fish_seen_subcommand_from onboard' -l advanced -d 'Advanced mode'
            complete -c javaclawbot -n '__fish_seen_subcommand_from onboard' -l accept-risk -d 'Accept risk warning'
            
            # gateway options
            complete -c javaclawbot -n '__fish_seen_subcommand_from gateway' -s p -l port -d 'Gateway port'
            complete -c javaclawbot -n '__fish_seen_subcommand_from gateway' -s w -l workspace -d 'Workspace directory' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from gateway' -s c -l config -d 'Config file path' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from gateway' -s v -l verbose -d 'Verbose output'
            
            # agent options
            complete -c javaclawbot -n '__fish_seen_subcommand_from agent' -s m -l message -d 'Message to send'
            complete -c javaclawbot -n '__fish_seen_subcommand_from agent' -s s -l session -d 'Session ID'
            complete -c javaclawbot -n '__fish_seen_subcommand_from agent' -s w -l workspace -d 'Workspace directory' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from agent' -s c -l config -d 'Config file path' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from agent' -l markdown -d 'Render as Markdown'
            complete -c javaclawbot -n '__fish_seen_subcommand_from agent' -l logs -d 'Show runtime logs'
            
            # channels subcommands
            complete -c javaclawbot -n '__fish_seen_subcommand_from channels' -a status -d 'Show channel status'
            complete -c javaclawbot -n '__fish_seen_subcommand_from channels' -a login -d 'Link device via QR code'
            
            # cron subcommands
            complete -c javaclawbot -n '__fish_seen_subcommand_from cron' -a list -d 'List scheduled jobs'
            complete -c javaclawbot -n '__fish_seen_subcommand_from cron' -a add -d 'Add a scheduled job'
            complete -c javaclawbot -n '__fish_seen_subcommand_from cron' -a remove -d 'Remove a scheduled job'
            complete -c javaclawbot -n '__fish_seen_subcommand_from cron' -a enable -d 'Enable or disable a job'
            complete -c javaclawbot -n '__fish_seen_subcommand_from cron' -a run -d 'Manually run a job'
            
            # cron add options
            complete -c javaclawbot -n '__fish_seen_subcommand_from add' -s n -l name -d 'Job name' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from add' -s m -l message -d 'Message for agent' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from add' -s e -l every -d 'Run every N seconds' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from add' -s c -l cron -d 'Cron expression' -r
            
            # cost options
            complete -c javaclawbot -n '__fish_seen_subcommand_from cost' -s d -l days -d 'Number of days' -r
            complete -c javaclawbot -n '__fish_seen_subcommand_from cost' -s s -l session -d 'Session ID' -r
            
            # completion options
            complete -c javaclawbot -n '__fish_seen_subcommand_from completion' -s s -l shell -d 'Shell type' -xa 'zsh bash fish powershell'
            complete -c javaclawbot -n '__fish_seen_subcommand_from completion' -s i -l install -d 'Install to profile'
            complete -c javaclawbot -n '__fish_seen_subcommand_from completion' -s y -l yes -d 'Skip confirmation'
            complete -c javaclawbot -n '__fish_seen_subcommand_from completion' -l write-state -d 'Write to state directory'
            """;
    }

    /**
     * 生成 PowerShell 补全脚本
     */
    private String generatePowerShellCompletion() {
        return """
            Register-ArgumentCompleter -Native -CommandName javaclawbot -ScriptBlock {
                param($wordToComplete, $commandAst, $cursorPosition)
                
                $commandElements = $commandAst.CommandElements
                $commands = @('onboard', 'gateway', 'agent', 'status', 'channels', 'cron', 'provider', 'cost', 'completion')
                
                # Root level completion
                if ($commandElements.Count -le 1) {
                    $commands | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                        [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
                    }
                    return
                }
                
                $subCommand = $commandElements[1].Extent.Text
                
                switch ($subCommand) {
                    'channels' {
                        @('status', 'login') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                            [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
                        }
                    }
                    'cron' {
                        @('list', 'add', 'remove', 'enable', 'run') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                            [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
                        }
                    }
                    'provider' {
                        @('login') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                            [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)
                        }
                    }
                    'completion' {
                        @('--shell', '-s', '--install', '-i', '--yes', '-y', '--write-state') | Where-Object { $_ -like "$wordToComplete*" } | ForEach-Object {
                            [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterName', $_)
                        }
                    }
                }
            }
            """;
    }

    /**
     * 获取补全缓存目录
     */
    static Path getCompletionCacheDir() {
        String home = System.getProperty("user.home");
        String stateDir = System.getenv("XDG_STATE_HOME");
        if (stateDir != null && !stateDir.isBlank()) {
            return Paths.get(stateDir, "javaclawbot", "completions");
        }
        return Paths.get(home, ".local", "state", "javaclawbot", "completions");
    }

    /**
     * 获取补全缓存文件路径
     */
    static Path getCompletionCachePath(String shell) {
        String ext = switch (shell) {
            case "powershell" -> "ps1";
            case "fish" -> "fish";
            case "bash" -> "bash";
            default -> "zsh";
        };
        return getCompletionCacheDir().resolve("javaclawbot." + ext);
    }

    /**
     * 写入补全缓存
     */
    void writeCompletionCache(String shell) {
        try {
            Path cacheDir = getCompletionCacheDir();
            Files.createDirectories(cacheDir);
            
            Path cachePath = getCompletionCachePath(shell);
            String script = generateCompletionScript(shell);
            Files.writeString(cachePath, script);
            
            System.out.println("✓ 补全缓存已写入: " + cachePath);
        } catch (IOException e) {
            System.err.println("写入补全缓存失败: " + e.getMessage());
        }
    }

    /**
     * 安装补全到 shell profile
     */
    void installCompletion(String shell, boolean skipConfirm) {
        Path cachePath = getCompletionCachePath(shell);
        
        // 确保缓存存在
        if (!Files.exists(cachePath)) {
            writeCompletionCache(shell);
        }
        
        Path profilePath = getShellProfilePath(shell);
        String sourceLine = "source \"" + cachePath + "\"";
        
        try {
            // 检查 profile 是否存在
            if (!Files.exists(profilePath)) {
                Files.createDirectories(profilePath.getParent());
                Files.writeString(profilePath, "");
            }
            
            // 读取现有内容
            String content = Files.readString(profilePath);
            
            // 检查是否已安装
            if (content.contains("javaclawbot completion") || content.contains(cachePath.toString())) {
                if (!skipConfirm) {
                    System.out.println("补全已安装到: " + profilePath);
                }
                return;
            }
            
            // 添加补全
            String block = "\n# javaclawbot Completion\n" + sourceLine + "\n";
            String newContent = content.trim() + block;
            
            Files.writeString(profilePath, newContent);
            
            System.out.println("✓ 补全已安装到: " + profilePath);
            System.out.println("  重启 shell 或运行: source " + profilePath);
            
        } catch (IOException e) {
            System.err.println("安装补全失败: " + e.getMessage());
        }
    }

    /**
     * 获取 shell profile 路径
     */
    static Path getShellProfilePath(String shell) {
        String home = System.getProperty("user.home");
        return switch (shell) {
            case "zsh" -> Paths.get(home, ".zshrc");
            case "bash" -> {
                Path bashrc = Paths.get(home, ".bashrc");
                yield Files.exists(bashrc) ? bashrc : Paths.get(home, ".bash_profile");
            }
            case "fish" -> Paths.get(home, ".config", "fish", "config.fish");
            case "powershell" -> {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    String userProfile = System.getenv("USERPROFILE");
                    yield Paths.get(userProfile, "Documents", "PowerShell", "Microsoft.PowerShell_profile.ps1");
                }
                yield Paths.get(home, ".config", "powershell", "Microsoft.PowerShell_profile.ps1");
            }
            default -> Paths.get(home, "." + shell + "rc");
        };
    }
}