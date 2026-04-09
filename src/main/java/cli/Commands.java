package cli;

import agent.AgentLoop;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import config.Config;
import config.ConfigIO;
import config.ConfigReloader;
import config.ConfigSchema;
import corn.CronJob;
import corn.CronSchedule;
import corn.CronService;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;
import providers.*;
import session.SessionCostUsage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static config.ConfigReloader.createRuntimeComponents;

@Command(
        name = "javaclawbot",
        mixinStandardHelpOptions = true,
        versionProvider = Commands.VersionProviderImpl.class,
        description = "javaclawbot - Personal AI Assistant",
        subcommands = {
                Commands.OnboardCmd.class,
                Commands.GatewayCmd.class,
                Commands.AgentCmd.class,
                Commands.StatusCmd.class,
                Commands.ChannelsCmd.class,
                Commands.CronCmd.class,
                Commands.ProviderCmd.class,
                Commands.CostCmd.class,
                Commands.ServiceCmd.class,
                Commands.CompletionCmdWrapper.class
        }
)
public class Commands implements Runnable {

    public static final Set<String> EXIT_COMMANDS = Set.of("exit", "quit", "/exit", "/quit", ":q");
    private static final Logger log = LoggerFactory.getLogger(Commands.class);

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version and exit")
    boolean versionRequested;

    @Override
    public void run() {
        throw new ParameterException(new CommandLine(this), "Missing command");
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Commands()).execute(args);
        System.exit(code);
    }

    static class VersionProviderImpl implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"🐱 javaclawbot v" + 1};
        }
    }

    static void printAgentResponse(String response, boolean renderMarkdown) {
        String content = response == null ? "" : response;
        System.out.println();
        System.out.println("🐱 javaclawbot");
        System.out.println(content);
        System.out.println();
    }

    static boolean isExitCommand(String input) {
        if (input == null) return false;
        return EXIT_COMMANDS.contains(input.trim().toLowerCase(Locale.ROOT));
    }

    static void createWorkspaceTemplates(Path workspace) {
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {
        }

        try {
            Path skills = workspace.resolve("skills");
            Files.createDirectories(skills);
        } catch (IOException ignored) {
        }

        Path memoryDir = workspace.resolve("memory");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException ignored) {
        }

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.notExists(memoryFile)) {
            try {
                Files.writeString(memoryFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException ignored) {
            }
        }

        /*Path historyFile = memoryDir.resolve("HISTORY.md");
        if (Files.notExists(historyFile)) {
            try {
                Files.writeString(historyFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException ignored) {}
        }*/
    }

    /**
     * 创建“可热更新 + fallback”的 Provider 代理
     * <p>
     * 设计说明：
     * - AgentLoop 依旧只依赖 LLMProvider 抽象
     * - 实际传入的是 HotSwappableProvider（代理）
     * - 每次 chat 前自动检查 config.json 是否变化
     */
    static LLMProvider makeHotProvider() {
        Path configPath = ConfigIO.getConfigPath();
        ConfigReloader reloader = new ConfigReloader(configPath);
        return new HotSwappableProvider(reloader);
    }

    @Command(name = "onboard", description = "Initialize javaclawbot configuration and workspace.")
    static class OnboardCmd implements Runnable {
        @Option(names = {"-workspace", "--workspace"}, description = "Workspace directory")
        String workspace;
        @Override
        public void run() {
            OnboardWizard onboardWizard = new OnboardWizard();
            //onboardWizard.setWorkspace(Paths.get(workspace));

            onboardWizard.run(new String[]{"--workspace=" + workspace});
        }
    }

    @Command(name = "gateway", description = "Start the javaclawbot gateway.")
    static class GatewayCmd implements Runnable {

        @Option(names = {"-p", "--port"}, description = "Gateway port")
        int port = 18790;

        @Option(names = {"-w", "--workspace"}, description = "Workspace directory")
        String workspace;

        @Option(names = {"-c", "--config"}, description = "Path to config file")
        String config;

        @Option(names = {"-v", "--verbose"}, description = "Verbose output")
        boolean verbose = false;

        @Override
        public void run() {
            System.out.println("🐱 Starting javaclawbot gateway on port " + port + "...");

            Path configPath = (config != null) ? Paths.get(config).toAbsolutePath() : null;
            Path workspacePath = (workspace != null) ? Paths.get(workspace).toAbsolutePath() : null;

            GatewayRuntime gateway = new GatewayRuntime(configPath, workspacePath);
            AtomicBoolean shutdownOnce = new AtomicBoolean(false);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (shutdownOnce.compareAndSet(false, true)) {
                    try {
                        gateway.stop().toCompletableFuture().join();
                    } catch (Exception ignored) {
                    }
                }
            }, "javaclawbot-gateway-shutdown"));

            try {
                gateway.start().toCompletableFuture().join();
                gateway.awaitStopped().toCompletableFuture().join();
            } catch (Exception e) {
                log.error("启动失败!", e);
                throw e;
            } finally {
                if (shutdownOnce.compareAndSet(false, true)) {
                    try {
                        gateway.stop().toCompletableFuture().join();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Command(name = "agent", description = "Interact with the agent directly.")
    static class AgentCmd implements Runnable {

        @Option(names = {"-m", "--message"}, description = "Message to send to the agent")
        String message;

        @Option(names = {"-s", "--session"}, description = "Session ID")
        String sessionId = "cli:direct";

        @Option(names = {"-w", "--workspace"}, description = "Workspace directory")
        String workspace;

        @Option(names = {"-c", "--config"}, description = "Path to config file")
        String config;

        @Option(names = {"--markdown"}, negatable = true, description = "Render assistant output as Markdown")
        boolean markdown = true;

        @Option(names = {"--logs"}, negatable = true, description = "Show javaclawbot runtime logs during chat")
        boolean logs = false;

        @Override
        public void run() {
            Path configPath = (config != null) ? Paths.get(config).toAbsolutePath() : null;
            Path workspacePath = (workspace != null) ? Paths.get(workspace).toAbsolutePath() : null;

            AgentRuntime agent = new AgentRuntime(configPath, workspacePath);
            AtomicBoolean shutdownOnce = new AtomicBoolean(false);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (shutdownOnce.compareAndSet(false, true)) {
                    try {
                        agent.stop().toCompletableFuture().join();
                    } catch (Exception ignored) {
                    }
                }
            }, "javaclawbot-agent-shutdown"));

            try {
                agent.start().toCompletableFuture().join();

                if (message != null && !message.isBlank()) {
                    String[] pair = splitSession(sessionId);
                    String resp = agent.processDirect(
                            message,
                            sessionId,
                            pair[0],
                            pair[1],
                            null
                    ).toCompletableFuture().join();

                    printAgentResponse(resp, markdown);
                    return;
                }

                new AgentConsoleSession(agent, sessionId, markdown).run();

            } catch (Exception e) {
                log.error("启动失败!", e);
                throw e;
            } finally {
                if (shutdownOnce.compareAndSet(false, true)) {
                    try {
                        agent.stop().toCompletableFuture().join();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private static String[] splitSession(String sessionId) {
            if (sessionId != null && sessionId.contains(":")) {
                String[] parts = sessionId.split(":", 2);
                return new String[]{parts[0], parts[1]};
            }
            return new String[]{"cli", sessionId != null ? sessionId : "direct"};
        }
    }

    @Command(name = "status", description = "Show javaclawbot status.")
    static class StatusCmd implements Runnable {
        @Override
        public void run() {
            Path configPath = ConfigIO.getConfigPath();
            Config config = ConfigIO.loadConfig(null);
            Path workspace = config.getWorkspacePath();

            System.out.println("🐱 javaclawbot Status\n");
            System.out.println("Config: " + configPath + (Files.exists(configPath) ? " ✓" : " ✗"));
            System.out.println("Workspace: " + workspace + (Files.exists(workspace) ? " ✓" : " ✗"));

            if (Files.exists(configPath)) {
                System.out.println("Model: " + config.getAgents().getDefaults().getModel());
                for (ProviderRegistry.ProviderSpec spec : ProviderRegistry.PROVIDERS) {
                    var p = config.getProviders().getByName(spec.getName());
                    if (p == null) continue;

                    if (spec.isOauth()) {
                        System.out.println(spec.getLabel() + ": ✓ (OAuth)");
                    } else if (spec.isLocal()) {
                        String base = p.getApiBase();
                        System.out.println(spec.getLabel() + ": " + (base != null && !base.isBlank() ? ("✓ " + base) : "not set"));
                    } else {
                        boolean hasKey = p.getApiKey() != null && !p.getApiKey().isBlank();
                        System.out.println(spec.getLabel() + ": " + (hasKey ? "✓" : "not set"));
                    }
                }
            }
        }
    }

    @Command(name = "channels", description = "Manage channels", subcommands = {
            ChannelsCmd.Status.class,
            ChannelsCmd.Login.class
    })
    static class ChannelsCmd implements Runnable {
        @Override
        public void run() {
            throw new ParameterException(new CommandLine(this), "Missing channels subcommand");
        }

        @Command(name = "status", description = "Show channel status.")
        static class Status implements Runnable {
            @Override
            public void run() {
                Config config = ConfigIO.loadConfig(null);
                System.out.println("Channel Status");
                System.out.println("WhatsApp: " + (config.getChannels().getWhatsapp().isEnabled() ? "✓" : "✗") + " " + safe(config.getChannels().getWhatsapp().getBridgeUrl()));
                System.out.println("Discord: " + (config.getChannels().getDiscord().isEnabled() ? "✓" : "✗") + " " + safe(config.getChannels().getDiscord().getGatewayUrl()));
                System.out.println("Feishu: " + (config.getChannels().getFeishu().isEnabled() ? "✓" : "✗") + " " + mask(config.getChannels().getFeishu().getAppId()));
                System.out.println("Mochat: " + (config.getChannels().getMochat().isEnabled() ? "✓" : "✗") + " " + safe(config.getChannels().getMochat().getBaseUrl()));
                System.out.println("Telegram: " + (config.getChannels().getTelegram().isEnabled() ? "✓" : "✗") + " " + mask(config.getChannels().getTelegram().getToken()));
                System.out.println("Slack: " + (config.getChannels().getSlack().isEnabled() ? "✓" : "✗") + " " +
                        ((config.getChannels().getSlack().getAppToken() != null && config.getChannels().getSlack().getBotToken() != null) ? "socket" : "not configured"));
                System.out.println("DingTalk: " + (config.getChannels().getDingtalk().isEnabled() ? "✓" : "✗") + " " + mask(config.getChannels().getDingtalk().getClientId()));
                System.out.println("QQ: " + (config.getChannels().getQq().isEnabled() ? "✓" : "✗") + " " + mask(config.getChannels().getQq().getAppId()));
                System.out.println("Email: " + (config.getChannels().getEmail().isEnabled() ? "✓" : "✗") + " " + safe(config.getChannels().getEmail().getImapHost()));
            }

            static String safe(String s) {
                return (s == null || s.isBlank()) ? "not configured" : s;
            }

            static String mask(String s) {
                if (s == null || s.isBlank()) return "not configured";
                int n = Math.min(10, s.length());
                return s.substring(0, n) + "...";
            }
        }

        @Command(name = "login", description = "Link device via QR code.")
        static class Login implements Runnable {
            @Override
            public void run() {
                throw new CommandLine.ExecutionException(new CommandLine(this), "Bridge login not implemented in Java CLI");
            }
        }
    }

    @Command(name = "cron", description = "Manage scheduled tasks", subcommands = {
            CronCmd.ListCmd.class,
            CronCmd.AddCmd.class,
            CronCmd.RemoveCmd.class,
            CronCmd.EnableCmd.class,
            CronCmd.RunCmd.class
    })
    static class CronCmd implements Runnable {
        @Override
        public void run() {
            throw new ParameterException(new CommandLine(this), "Missing cron subcommand");
        }

        @Command(name = "list", description = "List scheduled jobs.")
        static class ListCmd implements Runnable {

            @Option(names = {"-a", "--all"}, description = "Include disabled jobs")
            boolean all = false;

            @Override
            public void run() {
                Path storePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
                CronService service = new CronService(storePath, null);
                List<CronJob> jobs = service.listJobs(all);

                if (jobs == null || jobs.isEmpty()) {
                    System.out.println("No scheduled jobs.");
                    return;
                }

                System.out.println("Scheduled Jobs");
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

                for (CronJob job : jobs) {
                    String sched;
                    if ("every".equals(job.getSchedule().getKind())) {
                        sched = "every " + ((job.getSchedule().getEveryMs() != null ? job.getSchedule().getEveryMs() : 0) / 1000) + "s";
                    } else if ("cron".equals(job.getSchedule().getKind())) {
                        sched = job.getSchedule().getExpr() + (job.getSchedule().getTz() != null ? (" (" + job.getSchedule().getTz() + ")") : "");
                    } else {
                        sched = "one-time";
                    }

                    String nextRun = "";
                    if (job.getState() != null && job.getState().getNextRunAtMs() != null) {
                        nextRun = fmt.format(Instant.ofEpochMilli(job.getState().getNextRunAtMs()));
                    }

                    String status = job.isEnabled() ? "enabled" : "disabled";
                    System.out.println(job.getId() + " | " + job.getName() + " | " + sched + " | " + status + " | " + nextRun);
                }
            }
        }

        @Command(name = "add", description = "Add a scheduled job.")
        static class AddCmd implements Runnable {

            @Option(names = {"-n", "--name"}, required = true, description = "Job name")
            String name;

            @Option(names = {"-m", "--message"}, required = true, description = "Message for agent")
            String message;

            @Option(names = {"-e", "--every"}, description = "Run every N seconds")
            Integer every;

            @Option(names = {"-c", "--cron"}, description = "Cron expression (e.g. '0 9 * * *')")
            String cronExpr;

            @Option(names = {"--tz"}, description = "IANA timezone for cron (e.g. 'America/Vancouver')")
            String tz;

            @Option(names = {"--at"}, description = "Run once at time (ISO format, e.g. 2026-03-01T12:00:00Z)")
            String at;

            @Option(names = {"-d", "--deliver"}, description = "Deliver response to channel")
            boolean deliver = false;

            @Option(names = {"--to"}, description = "Recipient for delivery")
            String to;

            @Option(names = {"--channel"}, description = "Channel for delivery (e.g. 'telegram', 'whatsapp')")
            String channel;

            @Override
            public void run() {
                if (tz != null && (cronExpr == null || cronExpr.isBlank())) {
                    throw new CommandLine.ExecutionException(new CommandLine(this), "Error: --tz can only be used with --cron");
                }

                CronSchedule schedule;
                if (every != null) {
                    schedule = new CronSchedule(CronSchedule.Kind.every, every * 1000L, null, null, null);
                } else if (cronExpr != null && !cronExpr.isBlank()) {
                    schedule = new CronSchedule(CronSchedule.Kind.cron, null, null, cronExpr, tz);
                } else if (at != null && !at.isBlank()) {
                    Instant inst = Instant.parse(at);
                    schedule = new CronSchedule(CronSchedule.Kind.at, inst.toEpochMilli(), null, null, null);
                } else {
                    throw new CommandLine.ExecutionException(new CommandLine(this), "Error: Must specify --every, --cron, or --at");
                }

                Path storePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
                CronService service = new CronService(storePath, null);

                CronJob job = service.addJob(name, schedule, message, deliver, to, channel, false);
                System.out.println("✓ Added job '" + job.getName() + "' (" + job.getId() + ")");
            }
        }

        @Command(name = "remove", description = "Remove a scheduled job.")
        static class RemoveCmd implements Runnable {
            @Parameters(index = "0", description = "Job ID to remove")
            String jobId;

            @Override
            public void run() {
                Path storePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
                CronService service = new CronService(storePath, null);
                boolean ok = service.removeJob(jobId);
                if (ok) System.out.println("✓ Removed job " + jobId);
                else System.out.println("Job " + jobId + " not found");
            }
        }

        @Command(name = "enable", description = "Enable or disable a job.")
        static class EnableCmd implements Runnable {
            @Parameters(index = "0", description = "Job ID")
            String jobId;

            @Option(names = {"--disable"}, description = "Disable instead of enable")
            boolean disable = false;

            @Override
            public void run() {
                Path storePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
                CronService service = new CronService(storePath, null);
                CronJob job = service.enableJob(jobId, !disable);
                if (job != null) {
                    System.out.println("✓ Job '" + job.getName() + "' " + (disable ? "disabled" : "enabled"));
                } else {
                    System.out.println("Job " + jobId + " not found");
                }
            }
        }

        @Command(name = "run", description = "Manually run a job.")
        static class RunCmd implements Runnable {
            @Parameters(index = "0", description = "Job ID to run")
            String jobId;

            @Option(names = {"-f", "--force"}, description = "Run even if disabled")
            boolean force = false;

            @Override
            public void run() {

                // by zcw 改成动态配置读取
//            Config config = ConfigIO.loadConfig(null);
                RuntimeComponents rt = createRuntimeComponents();
                Config config = rt.config;

                // 变成可fallback的
                // LLMProvider provider = makeProvider(config);
                LLMProvider provider = makeHotProvider();
                MessageBus bus = new MessageBus();

                AgentLoop agentLoop = new AgentLoop(
                        bus,
                        provider,
                        config.getWorkspacePath(),
                        config.getAgents().getDefaults().getModel(),
                        config.getAgents().getDefaults().getMaxToolIterations(),
                        config.obtainTemperature(provider.getDefaultModel()),
                        config.obtainMaxTokens(provider.getDefaultModel()),
                        config.obtainContextWindow(provider.getDefaultModel()),
                        config.getAgents().getDefaults().getMemoryWindow(),
                        config.getAgents().getDefaults().getReasoningEffort(),
                        null,
                        config.getTools().isRestrictToWorkspace(),
                        null,
                        config.getTools().getMcpServers(),
                        config.getChannels(),
                        rt.runtimeSettings
                );

                Path storePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
                CronService service = new CronService(storePath, null);

                List<String> resultHolder = new ArrayList<>();

                service.setOnJob(job -> agentLoop.processDirect(
                        job.getPayload().getMessage(),
                        "cron:" + job.getId(),
                        job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "cli",
                        job.getPayload().getTo() != null ? job.getPayload().getTo() : "direct",
                        (c, toolHint) -> CompletableFuture.completedFuture(null)
                ).thenApply(resp -> {
                    resultHolder.add(resp);
                    return resp;
                }));

                boolean ok = service.runJob(jobId, force).toCompletableFuture().join();
                if (ok) {
                    System.out.println("✓ Job executed");
                    if (!resultHolder.isEmpty()) {
                        printAgentResponse(resultHolder.get(0), true);
                    }
                } else {
                    System.out.println("Failed to run job " + jobId);
                }

                try {
                    agentLoop.closeMcp().toCompletableFuture().join();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Command(name = "provider", description = "Manage providers", subcommands = {
            ProviderCmd.LoginCmd.class
    })
    static class ProviderCmd implements Runnable {
        @Override
        public void run() {
            throw new ParameterException(new CommandLine(this), "Missing provider subcommand");
        }

        @Command(name = "login", description = "Authenticate with an OAuth provider.")
        static class LoginCmd implements Runnable {
            @Parameters(index = "0", description = "OAuth provider (e.g. 'openai-codex', 'github-copilot')")
            String provider;

            @Override
            public void run() {
                String key = provider.replace("-", "_");
                ProviderRegistry.ProviderSpec spec = ProviderRegistry.PROVIDERS.stream()
                        .filter(s -> s.getName().equals(key) && s.isOauth())
                        .findFirst()
                        .orElse(null);

                if (spec == null) {
                    String names = ProviderRegistry.PROVIDERS.stream()
                            .filter(ProviderRegistry.ProviderSpec::isOauth)
                            .map(s -> s.getName().replace("_", "-"))
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    throw new CommandLine.ExecutionException(new CommandLine(this),
                            "Unknown OAuth provider: " + provider + " Supported: " + names);
                }

                throw new CommandLine.ExecutionException(new CommandLine(this),
                        "OAuth login not implemented in Java CLI");
            }
        }
    }

    @Command(name = "cost", description = "Show session cost and usage statistics.")
    static class CostCmd implements Runnable {

        @Option(names = {"-d", "--days"}, description = "Number of days to show (default: 30)")
        int days = 30;

        @Option(names = {"-s", "--session"}, description = "Show cost for specific session")
        String sessionId;

        @Override
        public void run() {
            Config config = ConfigIO.loadConfig(null);
            Path workspace = config.getWorkspacePath();
            Path sessionsDir = workspace.resolve("sessions");

            if (!Files.exists(sessionsDir)) {
                System.out.println("No sessions found.");
                return;
            }

            if (sessionId != null && !sessionId.isBlank()) {
                // Show specific session cost
                Path sessionFile = sessionsDir.resolve(sessionId.replace(":", "_") + ".jsonl");
                if (!Files.exists(sessionFile)) {
                    System.out.println("Session not found: " + sessionId);
                    return;
                }

                SessionCostUsage.SessionCostSummary summary = SessionCostUsage.loadSessionCostSummary(sessionFile);
                if (summary == null) {
                    System.out.println("Failed to load session cost.");
                    return;
                }

                System.out.println("\n🐱 Session Cost Summary: " + sessionId + "\n");
                System.out.println("Messages: " + summary.messageCounts().total() +
                        " (user: " + summary.messageCounts().user() +
                        ", assistant: " + summary.messageCounts().assistant() + ")");
                System.out.println("Tool calls: " + summary.messageCounts().toolCalls());

                if (summary.totals() != null) {
                    System.out.println("\nToken Usage:");
                    System.out.println("  Input: " + formatNumber(summary.totals().input()));
                    System.out.println("  Output: " + formatNumber(summary.totals().output()));
                    System.out.println("  Cache Read: " + formatNumber(summary.totals().cacheRead()));
                    System.out.println("  Cache Write: " + formatNumber(summary.totals().cacheWrite()));
                    System.out.println("  Total: " + formatNumber(summary.totals().totalTokens()));
                }

                if (summary.latency() != null) {
                    System.out.println("\nLatency:");
                    System.out.println("  Avg: " + formatMs(summary.latency().avgMs()));
                    System.out.println("  P95: " + formatMs(summary.latency().p95Ms()));
                    System.out.println("  Min: " + formatMs(summary.latency().minMs()));
                    System.out.println("  Max: " + formatMs(summary.latency().maxMs()));
                }

                if (summary.toolUsage() != null) {
                    System.out.println("\nTop Tools:");
                    summary.toolUsage().tools().stream()
                            .limit(5)
                            .forEach(t -> System.out.println("  " + t.name() + ": " + t.count()));
                }
                System.out.println();
                return;
            }

            // Show overall cost summary
            long endMs = System.currentTimeMillis();
            long startMs = endMs - (days * 24L * 60 * 60 * 1000);

            SessionCostUsage.CostUsageSummary summary = SessionCostUsage.loadCostUsageSummary(sessionsDir, startMs, endMs);

            System.out.println("\n🐱 Cost & Usage Summary (Last " + days + " days)\n");

            if (summary.totals() != null) {
                System.out.println("Total Tokens: " + formatNumber(summary.totals().totalTokens()));
                System.out.println("  Input: " + formatNumber(summary.totals().input()));
                System.out.println("  Output: " + formatNumber(summary.totals().output()));
                System.out.println("  Cache Read: " + formatNumber(summary.totals().cacheRead()));
                System.out.println("  Cache Write: " + formatNumber(summary.totals().cacheWrite()));
                if (summary.totals().totalCost() > 0) {
                    System.out.println("Total Cost: $" + String.format("%.4f", summary.totals().totalCost()));
                }
            }

            if (!summary.daily().isEmpty()) {
                System.out.println("\nDaily Usage:");
                System.out.println("  Date       | Tokens");
                System.out.println("  -----------|--------");
                summary.daily().stream()
                        .limit(10)
                        .forEach(d -> System.out.println("  " + d.date() + " | " + formatNumber(d.totals().totalTokens())));
            }
            System.out.println();
        }

        private String formatNumber(int n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }

        private String formatMs(long ms) {
            if (ms >= 60_000) return String.format("%.1fm", ms / 60_000.0);
            if (ms >= 1000) return String.format("%.1fs", ms / 1000.0);
            return ms + "ms";
        }
    }

    @Command(name = "completion", description = "Generate shell completion script.")
    static class CompletionCmdWrapper implements Runnable {
        @Override
        public void run() {
            new CompletionCmd().run();
        }
    }

    @Command(name = "service", description = "管理守护进程服务",
            subcommands = {
                    ServiceCmd.Install.class,
                    ServiceCmd.Uninstall.class,
                    ServiceCmd.Start.class,
                    ServiceCmd.Stop.class,
                    ServiceCmd.Restart.class,
                    ServiceCmd.Status.class
            })
    static class ServiceCmd implements Runnable {
        @Override
        public void run() {
            throw new ParameterException(new CommandLine(this), "缺少 service 子命令");
        }

        @Command(name = "install", description = "安装守护进程服务")
        static class Install implements Runnable {

            @Option(names = {"-p", "--port"}, description = "Gateway 端口")
            int port = 18790;

            @Option(names = {"-w", "--workspace"}, description = "工作空间目录")
            String workspace;

            @Option(names = {"--start"}, description = "安装后立即启动")
            boolean start = false;

            @Override
            public void run() {
                try {
                    daemon.DaemonService service = daemon.DaemonServiceFactory.create();
                    daemon.DaemonService.ServiceConfig config = new daemon.DaemonService.ServiceConfig(port, workspace);

                    System.out.println("正在安装 " + service.getLabel() + " 服务...");

                    daemon.DaemonService.ServiceResult result = service.install(config);
                    System.out.println(result.message() != null ? result.message() : result.error());

                    if (start && result.success()) {
                        System.out.println("正在启动服务...");
                        daemon.DaemonService.ServiceResult startResult = service.start();
                        System.out.println(startResult.message() != null ? startResult.message() : startResult.error());
                    }

                } catch (UnsupportedOperationException e) {
                    System.err.println("错误: " + e.getMessage());
                    System.err.println("当前仅支持 Linux (systemd)、macOS (launchd) 和 Windows (计划任务)");
                } catch (Exception e) {
                    System.err.println("安装服务失败: " + e.getMessage());
                }
            }
        }

        @Command(name = "uninstall", description = "卸载守护进程服务")
        static class Uninstall implements Runnable {
            @Override
            public void run() {
                try {
                    daemon.DaemonService service = daemon.DaemonServiceFactory.create();

                    System.out.println("正在卸载 " + service.getLabel() + " 服务...");

                    daemon.DaemonService.ServiceResult result = service.uninstall();
                    System.out.println(result.message() != null ? result.message() : result.error());

                } catch (UnsupportedOperationException e) {
                    System.err.println("错误: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("卸载服务失败: " + e.getMessage());
                }
            }
        }

        @Command(name = "start", description = "启动守护进程服务")
        static class Start implements Runnable {
            @Override
            public void run() {
                try {
                    daemon.DaemonService service = daemon.DaemonServiceFactory.create();

                    daemon.DaemonService.ServiceResult result = service.start();
                    System.out.println(result.message() != null ? result.message() : result.error());

                } catch (UnsupportedOperationException e) {
                    System.err.println("错误: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("启动服务失败: " + e.getMessage());
                }
            }
        }

        @Command(name = "stop", description = "停止守护进程服务")
        static class Stop implements Runnable {
            @Override
            public void run() {
                try {
                    daemon.DaemonService service = daemon.DaemonServiceFactory.create();

                    daemon.DaemonService.ServiceResult result = service.stop();
                    System.out.println(result.message() != null ? result.message() : result.error());

                } catch (UnsupportedOperationException e) {
                    System.err.println("错误: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("停止服务失败: " + e.getMessage());
                }
            }
        }

        @Command(name = "restart", description = "重启守护进程服务")
        static class Restart implements Runnable {
            @Override
            public void run() {
                try {
                    daemon.DaemonService service = daemon.DaemonServiceFactory.create();

                    daemon.DaemonService.ServiceResult result = service.restart();
                    System.out.println(result.message() != null ? result.message() : result.error());

                } catch (UnsupportedOperationException e) {
                    System.err.println("错误: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("重启服务失败: " + e.getMessage());
                }
            }
        }

        @Command(name = "status", description = "查看服务状态")
        static class Status implements Runnable {
            @Override
            public void run() {
                try {
                    daemon.DaemonService service = daemon.DaemonServiceFactory.create();
                    daemon.DaemonService.ServiceStatus status = service.status();

                    System.out.println();
                    System.out.println("🐱 javaclawbot 服务状态");
                    System.out.println();
                    System.out.println("平台: " + daemon.DaemonServiceFactory.getCurrentPlatform() + " (" + service.getLabel() + ")");
                    System.out.println("安装: " + (status.installed() ? "✓ 已安装" : "✗ 未安装"));
                    System.out.println("状态: " + status.statusText());

                    if (status.pid() != null) {
                        System.out.println("PID: " + status.pid());
                    }

                    if (status.logPath() != null) {
                        System.out.println("日志: " + status.logPath());
                    }

                    System.out.println();

                } catch (UnsupportedOperationException e) {
                    System.err.println("错误: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("获取状态失败: " + e.getMessage());
                }
            }
        }
    }
}