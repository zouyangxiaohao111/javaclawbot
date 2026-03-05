package cli;

import agent.AgentLoop;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import channels.ChannelManager;
import config.ConfigIO;
import config.ConfigSchema;
import corn.CronJob;
import corn.CronSchedule;
import corn.CronService;
import heartbeat.HeartbeatService;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;
import providers.CustomProvider;
import providers.LLMProvider;
import providers.ProviderRegistry;
import session.SessionManager;
import utils.Helpers;

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

@Command(
        name = "nanobot",
        mixinStandardHelpOptions = true,
        versionProvider = Commands.VersionProviderImpl.class,
        description = "nanobot - Personal AI Assistant",
        subcommands = {
                Commands.OnboardCmd.class,
                Commands.GatewayCmd.class,
                Commands.AgentCmd.class,
                Commands.StatusCmd.class,
                Commands.ChannelsCmd.class,
                Commands.CronCmd.class,
                Commands.ProviderCmd.class
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
            return new String[]{"🐈 nanobot v" + 1};
        }
    }

    static void printAgentResponse(String response, boolean renderMarkdown) {
        String content = response == null ? "" : response;
        System.out.println();
        System.out.println("🐈 nanobot");
        System.out.println(content);
        System.out.println();
    }

    static boolean isExitCommand(String input) {
        if (input == null) return false;
        return EXIT_COMMANDS.contains(input.trim().toLowerCase(Locale.ROOT));
    }

    static void createWorkspaceTemplates(Path workspace) {
        try { Files.createDirectories(workspace); } catch (IOException ignored) {}

        try {
            Path skills = workspace.resolve("skills");
            Files.createDirectories(skills);
        } catch (IOException ignored) {}

        Path memoryDir = workspace.resolve("memory");
        try { Files.createDirectories(memoryDir); } catch (IOException ignored) {}

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.notExists(memoryFile)) {
            try {
                Files.writeString(memoryFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException ignored) {}
        }

        Path historyFile = memoryDir.resolve("HISTORY.md");
        if (Files.notExists(historyFile)) {
            try {
                Files.writeString(historyFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException ignored) {}
        }
    }

    /**
     * Provider 创建逻辑（对齐 Python 的“尽量可用”策略）：
     * 1) openai_codex -> 明确不支持
     * 2) provider=custom -> CustomProvider(apiKey, apiBase, model)
     * 3) 其它 provider：如果配置里提供了 api_base（OpenAI-compatible endpoint），也走 CustomProvider
     * 4) 否则报错：No API key / no api_base
     */
    static LLMProvider makeProvider(ConfigSchema.Config config) {
        String model = config.getAgents().getDefaults().getModel();
        String providerName = config.getProviderName(model);
        var p = config.getProvider(model);

        if ("openai_codex".equals(providerName) || (model != null && model.startsWith("openai-codex/"))) {
            throw new CommandLine.ExecutionException(new CommandLine(new Commands()),
                    "Error: OpenAI Codex is not supported in this Java build.");
        }

        String apiKey = (p != null && p.getApiKey() != null) ? p.getApiKey() : null;
        String apiBase = config.getApiBase(model);

        // custom：强制使用 CustomProvider（OpenAI-compatible）
        if ("custom".equals(providerName)) {
            if (apiBase == null || apiBase.isBlank()) apiBase = "http://localhost:8000/v1";
            if (apiKey == null || apiKey.isBlank()) apiKey = "no-key";
            return new CustomProvider(apiKey, apiBase, model);
        }

        // 其它 provider：如果有 api_base，也用 CustomProvider 兜底（解决你现在“config is not supported”）
        if (apiBase != null && !apiBase.isBlank()) {
            if (apiKey == null || apiKey.isBlank()) {
                // 有些本地/无鉴权网关可能不需要 key，这里保持兼容
                apiKey = "no-key";
            }
            return new CustomProvider(apiKey, apiBase, model);
        }

        // 没 api_base：就要求 apiKey 且 provider 非 oauth
        ProviderRegistry.ProviderSpec spec = ProviderRegistry.findByName(providerName);
        boolean isOauth = spec != null && spec.isOauth();
        boolean isBedrock = model != null && model.startsWith("bedrock/");
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        if (!isBedrock && !hasKey && !isOauth) {
            throw new CommandLine.ExecutionException(new CommandLine(new Commands()),
                    "Error: No API key configured (and no api_base set).");
        }

        throw new CommandLine.ExecutionException(new CommandLine(new Commands()),
                "Error: Provider '" + providerName + "' is not supported in this Java build. " +
                        "Tip: set tools/providers api_base to an OpenAI-compatible endpoint so CustomProvider can be used.");
    }

    @Command(name = "onboard", description = "Initialize nanobot configuration and workspace.")
    static class OnboardCmd implements Runnable {
        @Override
        public void run() {
            Path configPath = ConfigIO.getConfigPath();

            if (Files.exists(configPath)) {
                System.out.println("Config already exists at " + configPath);
                System.out.println("  y = overwrite with defaults (existing values will be lost)");
                System.out.println("  N = refresh config, keeping existing values and adding new fields");

                boolean overwrite = promptConfirm("Overwrite?");
                if (overwrite) {
                    ConfigSchema.Config cfg = new ConfigSchema.Config();
                    try {
                        ConfigIO.saveConfig(cfg, null);
                    } catch (IOException e) {
                        System.err.println("Failed to save config: " + e.getMessage());
                        return;
                    }
                    System.out.println("✓ Config reset to defaults at " + configPath);
                } else {
                    ConfigSchema.Config cfg = ConfigIO.loadConfig(null);
                    try {
                        ConfigIO.saveConfig(cfg, null);
                    } catch (IOException e) {
                        System.err.println("Failed to save config: " + e.getMessage());
                        return;
                    }
                    System.out.println("✓ Config refreshed at " + configPath + " (existing values preserved)");
                }
            } else {
                try {
                    ConfigIO.saveConfig(new ConfigSchema.Config(), null);
                } catch (IOException e) {
                    System.err.println("Failed to save config: " + e.getMessage());
                    return;
                }
                System.out.println("✓ Created config at " + configPath);
            }

            Path workspace = Helpers.getWorkspacePath(null);
            if (Files.notExists(workspace)) {
                try { Files.createDirectories(workspace); } catch (IOException ignored) {}
                System.out.println("✓ Created workspace at " + workspace);
            }

            createWorkspaceTemplates(workspace);

            System.out.println();
            System.out.println("🐈 nanobot is ready!");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  1. Add your API key to ~/.nanobot/config.json");
            System.out.println("  2. Chat: nanobot agent -m \"Hello!\"");
        }

        static boolean promptConfirm(String prompt) {
            System.out.print(prompt + " [y/N] ");
            try {
                Scanner sc = new Scanner(System.in);
                String s = sc.nextLine();
                if (s == null) return false;
                s = s.trim().toLowerCase(Locale.ROOT);
                return s.equals("y") || s.equals("yes");
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Command(name = "gateway", description = "Start the nanobot gateway.")
    static class GatewayCmd implements Runnable {

        @Option(names = {"-p", "--port"}, description = "Gateway port")
        int port = 18790;

        @Option(names = {"-v", "--verbose"}, description = "Verbose output")
        boolean verbose = false;

        @Override
        public void run() {
            System.out.println("🐈 Starting nanobot gateway on port " + port + "...");

            ConfigSchema.Config config = ConfigIO.loadConfig(null);
            MessageBus bus = new MessageBus();
            LLMProvider provider = makeProvider(config);
            SessionManager sessionManager = new SessionManager(config.getWorkspacePath());

            Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
            CronService cron = new CronService(cronStorePath, null);

            AgentLoop agent = new AgentLoop(
                    bus,
                    provider,
                    config.getWorkspacePath(),
                    config.getAgents().getDefaults().getModel(),
                    config.getAgents().getDefaults().getMaxToolIterations(),
                    config.getAgents().getDefaults().getTemperature(),
                    config.getAgents().getDefaults().getMaxTokens(),
                    config.getAgents().getDefaults().getMemoryWindow(),
                    config.getAgents().getDefaults().getReasoningEffort(),
                    config.getTools().getWeb().getSearch().getApiKey(),
                    config.getTools().getExec(),
                    cron,
                    config.getTools().isRestrictToWorkspace(),
                    sessionManager,
                    config.getTools().getMcpServers(),
                    config.getChannels()
            );

            cron.setOnJob(job -> agent.processDirect(
                    job.getPayload().getMessage(),
                    "cron:" + job.getId(),
                    job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "cli",
                    job.getPayload().getTo() != null ? job.getPayload().getTo() : "direct",
                    (c, toolHint) -> CompletableFuture.completedFuture(null)
            ).thenCompose(resp -> {
                if (job.getPayload().isDeliver() && job.getPayload().getTo() != null) {
                    return bus.publishOutbound(new OutboundMessage(
                            job.getPayload().getChannel() != null ? job.getPayload().getChannel() : "cli",
                            job.getPayload().getTo(),
                            resp != null ? resp : "",
                            null,
                            null
                    )).thenApply(x -> resp);
                }
                return CompletableFuture.completedFuture(resp);
            }));

            ChannelManager channels = new ChannelManager(config, bus);

            // 选 heartbeat 投递目标：优先非 cli/system 且在 enabled_channels 的 session
            final AtomicReference<String> hbChannel = new AtomicReference<>("cli");
            final AtomicReference<String> hbChatId = new AtomicReference<>("direct");
            try {
                Set<String> enabled = new HashSet<>(channels.getEnabledChannels());
                for (Map<String, Object> s : sessionManager.listSessions()) {
                    Object keyObj = s.get("key");
                    if (!(keyObj instanceof String key)) continue;
                    if (!key.contains(":")) continue;
                    String[] parts = key.split(":", 2);
                    String ch = parts[0];
                    String cid = parts[1];
                    if ("cli".equals(ch) || "system".equals(ch)) continue;
                    if (enabled.contains(ch) && cid != null && !cid.isBlank()) {
                        hbChannel.set(ch);
                        hbChatId.set(cid);
                        break;
                    }
                }
            } catch (Exception ignored) {}

            HeartbeatService heartbeat = new HeartbeatService(
                    config.getWorkspacePath(),
                    provider,
                    agent.getModel(),
                    tasks -> agent.processDirect(
                            tasks,
                            "heartbeat",
                            hbChannel.get(),
                            hbChatId.get(),
                            (c, toolHint) -> CompletableFuture.completedFuture(null)
                    ).toCompletableFuture(),
                    response -> {
                        // 没有外部 channel 则不投递（对齐 Python：cli 不投递）
                        if ("cli".equals(hbChannel.get())) return CompletableFuture.completedFuture(null);
                        return bus.publishOutbound(new OutboundMessage(
                                hbChannel.get(),
                                hbChatId.get(),
                                response != null ? response : "",
                                null,
                                null
                        )).toCompletableFuture();
                    },
                    config.getGateway().getHeartbeat().getIntervalS(),
                    config.getGateway().getHeartbeat().isEnabled()
            );

            AtomicBoolean stopping = new AtomicBoolean(false);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (stopping.compareAndSet(false, true)) {
                    heartbeat.stop();
                    cron.stop();
                    agent.stop();
                    try { channels.stopAll().toCompletableFuture().join(); } catch (Exception ignored) {}
                    try { agent.closeMcp().toCompletableFuture().join(); } catch (Exception ignored) {}
                }
            }));

            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    cron.start().toCompletableFuture().join();
                    heartbeat.start().toCompletableFuture().join();
                    channels.startAll().toCompletableFuture().join();
                    agent.run().toCompletableFuture().join();
                } catch (Exception ignored) {
                    log.error("启动失败!", ignored);
                } finally {
                    heartbeat.stop();
                    cron.stop();
                    agent.stop();
                    try { channels.stopAll().toCompletableFuture().join(); } catch (Exception ignored) {}
                    try { agent.closeMcp().toCompletableFuture().join(); } catch (Exception ignored) {}
                }
            });

            f.join();
        }
    }

    @Command(name = "agent", description = "Interact with the agent directly.")
    static class AgentCmd implements Runnable {

        @Option(names = {"-m", "--message"}, description = "Message to send to the agent")
        String message;

        @Option(names = {"-s", "--session"}, description = "Session ID")
        String sessionId = "cli:direct";

        @Option(names = {"--markdown"}, negatable = true, description = "Render assistant output as Markdown")
        boolean markdown = true;

        @Option(names = {"--logs"}, negatable = true, description = "Show nanobot runtime logs during chat")
        boolean logs = false;

        @Override
        public void run() {
            ConfigSchema.Config config = ConfigIO.loadConfig(null);
            MessageBus bus = new MessageBus();
            LLMProvider provider = makeProvider(config);

            Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
            CronService cron = new CronService(cronStorePath, null);

            AgentLoop agentLoop = new AgentLoop(
                    bus,
                    provider,
                    config.getWorkspacePath(),
                    config.getAgents().getDefaults().getModel(),
                    config.getAgents().getDefaults().getMaxToolIterations(),
                    config.getAgents().getDefaults().getTemperature(),
                    config.getAgents().getDefaults().getMaxTokens(),
                    config.getAgents().getDefaults().getMemoryWindow(),
                    config.getAgents().getDefaults().getReasoningEffort(),
                    config.getTools().getWeb().getSearch().getApiKey(),
                    config.getTools().getExec(),
                    cron,
                    config.getTools().isRestrictToWorkspace(),
                    null,
                    config.getTools().getMcpServers(),
                    config.getChannels()
            );

            BiProgress progress = new BiProgress(agentLoop);

            // 单次模式：直接调用，不需要 bus
            if (message != null && !message.isBlank()) {
                String resp = agentLoop.processDirect(
                        message,
                        sessionId,
                        "cli",
                        "direct",
                        progress::onProgress
                ).toCompletableFuture().join();
                printAgentResponse(resp, markdown);
                try { agentLoop.closeMcp().toCompletableFuture().join(); } catch (Exception ignored) {}
                return;
            }

            // 交互模式：通过 bus 路由（对齐 Python）
            String cliChannel;
            String cliChatId;
            if (sessionId.contains(":")) {
                String[] parts = sessionId.split(":", 2);
                cliChannel = parts[0];
                cliChatId = parts[1];
            } else {
                cliChannel = "cli";
                cliChatId = sessionId;
            }

            Path histFile = Paths.get(System.getProperty("user.home"), ".nanobot", "history", "cli_history");
            try { Files.createDirectories(histFile.getParent()); } catch (IOException ignored) {}

            Terminal terminal;
            try {
                terminal = TerminalBuilder.builder().system(true).build();
            } catch (IOException e) {
                throw new CommandLine.ExecutionException(new CommandLine(new Commands()), "Failed to init terminal", e);
            }

            DefaultHistory history;
            try {
                history = new DefaultHistory();
                history.read(histFile, false);
            } catch (Exception e) {
                history = new DefaultHistory(); // 确保是干净的
            }

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(history)
                    .build();

            // JLine 有时会把 history 只写到内存，这里确保退出时 flush
            if (reader instanceof LineReaderImpl impl) {
                impl.setVariable(LineReader.HISTORY_SIZE, 10000);
            }

            System.out.println("🐈 Interactive mode (type exit or Ctrl+C to quit)\n");

            CompletableFuture<Void> busTask = CompletableFuture.runAsync(agentLoop::run);

            AtomicBoolean running = new AtomicBoolean(true);

            // turn 同步：一次输入等待一次“非 progress”的回复
            final AtomicReference<CountDownLatch> turnLatchRef = new AtomicReference<>(new CountDownLatch(0));
            final AtomicReference<String> turnResponseRef = new AtomicReference<>(null);

            CompletableFuture<Void> outboundTask = CompletableFuture.runAsync(() -> {
                while (running.get()) {
                    try {
                        OutboundMessage out = bus.consumeOutbound(1, TimeUnit.SECONDS);
                        // by zcw 发现如果是 outbound.take 然后使用同一个线程池 造成无限等待, 并不会消费到消息
                                /*.toCompletableFuture().get(1, TimeUnit.SECONDS);*/
                        if (out == null) continue;

                        Map<String, Object> meta = out.getMetadata() != null ? out.getMetadata() : Map.of();
                        boolean isProgress = Boolean.TRUE.equals(meta.get("_progress"));
                        boolean isToolHint = Boolean.TRUE.equals(meta.get("_tool_hint"));

                        if (isProgress) {
                            var ch = agentLoop.getChannelsConfig();
                            if (ch != null && isToolHint && !ch.isSendToolHints()) continue;
                            if (ch != null && !isToolHint && !ch.isSendProgress()) continue;
                            System.out.println("  ↳ " + (out.getContent() == null ? "" : out.getContent()));
                            continue;
                        }

                        // 非 progress：如果当前 turn 在等待，则把内容交给 turnLatch；否则当作异步消息直接打印
                        CountDownLatch latch = turnLatchRef.get();
                        if (latch != null && latch.getCount() > 0) {
                            if (out.getContent() != null && !out.getContent().isBlank()) {
                                turnResponseRef.compareAndSet(null, out.getContent());
                            }
                            latch.countDown();
                        } else {
                            if (out.getContent() != null && !out.getContent().isBlank()) {
                                printAgentResponse(out.getContent(), markdown);
                            }
                        }
                    } catch (CancellationException ce) {
                        break;
                    } catch (Exception ignored) {
                    }
                }
            });

            try {
                while (true) {
                    String userInput;
                    try {
                        userInput = reader.readLine("You: ");
                    } catch (UserInterruptException e) {
                        System.out.println("\nGoodbye!");
                        break;
                    } catch (EndOfFileException e) {
                        System.out.println("\nGoodbye!");
                        break;
                    }

                    if (userInput == null) continue;
                    String command = userInput.trim();
                    if (command.isEmpty()) continue;

                    if (isExitCommand(command)) {
                        System.out.println("\nGoodbye!");
                        break;
                    }

                    // history 写入
                    try { history.add(userInput); history.save(); } catch (Exception ignored) {}

                    // 准备等待本次回复
                    turnResponseRef.set(null);
                    CountDownLatch latch = new CountDownLatch(1);
                    turnLatchRef.set(latch);

                    InboundMessage in = new InboundMessage(cliChannel, "user", cliChatId, userInput, null, null);
                    bus.publishInbound(in).toCompletableFuture().join();

                    // 等待回复（可改更小/更大超时）
                    System.out.println("[dim]nanobot is thinking...[/dim]");
                    boolean ok = latch.await(5, TimeUnit.MINUTES);
                    String resp = turnResponseRef.get();
                    turnLatchRef.set(new CountDownLatch(0));

                    if (!ok) {
                        System.out.println("Timed out waiting for response.");
                    } else if (resp != null && !resp.isBlank()) {
                        printAgentResponse(resp, markdown);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                running.set(false);
                agentLoop.stop();
                outboundTask.cancel(true);
                try { CompletableFuture.allOf(busTask, outboundTask).join(); } catch (Exception ignored) {}
                try { agentLoop.closeMcp().toCompletableFuture().join(); } catch (Exception ignored) {}
                try { history.save(); } catch (Exception ignored) {}
            }
        }

        static class BiProgress {
            final AgentLoop agentLoop;
            BiProgress(AgentLoop agentLoop) { this.agentLoop = agentLoop; }

            CompletionStage<Void> onProgress(String content, boolean toolHint) {
                var ch = agentLoop.getChannelsConfig();
                if (ch != null && toolHint && !ch.isSendToolHints()) return CompletableFuture.completedFuture(null);
                if (ch != null && !toolHint && !ch.isSendProgress()) return CompletableFuture.completedFuture(null);
                System.out.println("  ↳ " + (content == null ? "" : content));
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    @Command(name = "status", description = "Show nanobot status.")
    static class StatusCmd implements Runnable {
        @Override
        public void run() {
            Path configPath = ConfigIO.getConfigPath();
            ConfigSchema.Config config = ConfigIO.loadConfig(null);
            Path workspace = config.getWorkspacePath();

            System.out.println("🐈 nanobot Status\n");
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
        @Override public void run() { throw new ParameterException(new CommandLine(this), "Missing channels subcommand"); }

        @Command(name = "status", description = "Show channel status.")
        static class Status implements Runnable {
            @Override
            public void run() {
                ConfigSchema.Config config = ConfigIO.loadConfig(null);
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

            static String safe(String s) { return (s == null || s.isBlank()) ? "not configured" : s; }
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
        @Override public void run() { throw new ParameterException(new CommandLine(this), "Missing cron subcommand"); }

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
                ConfigSchema.Config config = ConfigIO.loadConfig(null);
                LLMProvider provider = makeProvider(config);
                MessageBus bus = new MessageBus();

                AgentLoop agentLoop = new AgentLoop(
                        bus,
                        provider,
                        config.getWorkspacePath(),
                        config.getAgents().getDefaults().getModel(),
                        config.getAgents().getDefaults().getMaxToolIterations(),
                        config.getAgents().getDefaults().getTemperature(),
                        config.getAgents().getDefaults().getMaxTokens(),
                        config.getAgents().getDefaults().getMemoryWindow(),
                        config.getAgents().getDefaults().getReasoningEffort(),
                        config.getTools().getWeb().getSearch().getApiKey(),
                        config.getTools().getExec(),
                        null,
                        config.getTools().isRestrictToWorkspace(),
                        null,
                        config.getTools().getMcpServers(),
                        config.getChannels()
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

                try { agentLoop.closeMcp().toCompletableFuture().join(); } catch (Exception ignored) {}
            }
        }
    }

    @Command(name = "provider", description = "Manage providers", subcommands = {
            ProviderCmd.LoginCmd.class
    })
    static class ProviderCmd implements Runnable {
        @Override public void run() { throw new ParameterException(new CommandLine(this), "Missing provider subcommand"); }

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
}