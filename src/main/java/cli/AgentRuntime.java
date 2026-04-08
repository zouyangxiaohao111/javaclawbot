package cli;

import agent.AgentLoop;
import agent.ProgressCallback;
import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import config.Config;
import config.ConfigIO;
import config.ConfigReloader;
import config.provider.model.ModelConfig;
import corn.CronService;
import providers.HotSwappableProvider;
import providers.LLMProvider;
import session.SessionManager;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static config.ConfigReloader.createRuntimeComponents;

public final class AgentRuntime {

    private final Object lifecycleLock = new Object();
    private final ExecutorService lifecycleExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "javaclawbot-agent-runtime");
        t.setDaemon(false);
        return t;
    });

    private final Path configPathOverride;
    private final Path workspacePathOverride;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile CompletableFuture<Void> startFuture = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> stoppedFuture = CompletableFuture.completedFuture(null);

    private volatile Config config;
    private volatile MessageBus bus;
    private volatile LLMProvider provider;
    private volatile SessionManager sessionManager;
    private volatile CronService cron;
    private volatile AgentLoop agent;

    public AgentRuntime(Path configPathOverride, Path workspacePathOverride) {
        this.configPathOverride = configPathOverride;
        this.workspacePathOverride = workspacePathOverride;
    }

    public CompletionStage<Void> start() {
        synchronized (lifecycleLock) {
            if (running.get()) return CompletableFuture.completedFuture(null);
            if (startFuture != null && !startFuture.isDone()) return startFuture;

            startFuture = new CompletableFuture<>();
            stoppedFuture = new CompletableFuture<>();

            lifecycleExecutor.submit(() -> {
                try {
                    buildComponents();
                    cron.start().toCompletableFuture().join();
                    running.set(true);
                    startFuture.complete(null);

                    agent.run().toCompletableFuture().join();
                    running.set(false);
                    cleanup();
                    stoppedFuture.complete(null);
                } catch (Exception e) {
                    running.set(false);
                    cleanup();
                    startFuture.completeExceptionally(e);
                    if (!stoppedFuture.isDone()) {
                        stoppedFuture.completeExceptionally(e);
                    }
                }
            });

            return startFuture;
        }
    }

    public CompletionStage<Void> stop() {
        synchronized (lifecycleLock) {
            return CompletableFuture.runAsync(() -> {
                running.set(false);
                cleanup();
                if (!stoppedFuture.isDone()) {
                    stoppedFuture.complete(null);
                }
            }, lifecycleExecutor);
        }
    }

    public CompletionStage<Void> awaitStopped() {
        return stoppedFuture;
    }

    public CompletionStage<Void> publishInbound(InboundMessage msg) {
        return bus.publishInbound(msg);
    }

    public OutboundMessage consumeOutbound(long timeout, TimeUnit unit) throws InterruptedException {
        MessageBus currentBus = this.bus;
        if (currentBus == null) {
            throw new InterruptedException("Runtime stopped");
        }
        return currentBus.consumeOutbound(timeout, unit);
    }

    public boolean isRunning() {
        return running.get();
    }

    public CompletionStage<String> processDirect(
            String content,
            String sessionKey,
            String channel,
            String chatId,
            ProgressCallback onProgress
    ) {
        return agent.processDirect(content, sessionKey, channel, chatId, onProgress);
    }

    public AgentLoop getAgent() {
        return agent;
    }

    private void buildComponents() {
        RuntimeComponents rt = (configPathOverride != null || workspacePathOverride != null)
                ? createRuntimeComponents(configPathOverride, workspacePathOverride)
                : createRuntimeComponents();

        this.config = rt.getConfig();
        this.bus = new MessageBus();
        this.provider = new HotSwappableProvider(
                new ConfigReloader(configPathOverride != null ? configPathOverride : ConfigIO.getConfigPath())
        );
        this.sessionManager = new SessionManager(config.getWorkspacePath());

        Path cronStorePath = ConfigIO.getDataDir().resolve("cron").resolve("jobs.json");
        this.cron = new CronService(cronStorePath, null);

        this.agent = new AgentLoop(
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
                cron,
                config.getTools().isRestrictToWorkspace(),
                sessionManager,
                config.getTools().getMcpServers(),
                config.getChannels(),
                rt.getRuntimeSettings()
        );
    }

    private void cleanup() {
        try {
            if (cron != null) cron.stop();
        } catch (Exception ignored) {
        }
        try {
            if (agent != null) {
                agent.stop();
                agent.closeMcp().toCompletableFuture().join();
            }
        } catch (Exception ignored) {
        }
        cron = null;
        agent = null;
        bus = null;
        provider = null;
        sessionManager = null;
        config = null;
    }
}