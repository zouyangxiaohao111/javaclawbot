package cli;

import agent.AgentLoop;
import bus.MessageBus;
import bus.OutboundMessage;
import channels.ChannelManager;
import config.Config;
import config.ConfigIO;
import config.ConfigReloader;
import config.ConfigSchema;
import corn.CronService;
import heartbeat.HeartbeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.HotSwappableProvider;
import providers.LLMProvider;
import session.SessionManager;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static config.ConfigReloader.createRuntimeComponents;

/**
 * GatewayRuntime
 *
 * 设计目标：
 * 1) 把 Commands.GatewayCmd 里的启动逻辑下沉为可复用的生命周期服务
 * 2) CLI 与 GUI 共享同一套 Gateway 组装逻辑，但不共享入口层阻塞 / shutdown hook
 * 3) GUI 启动 Gateway 时，不需要展示内部聊天过程，只负责 start / stop
 *
 * 模式说明：
 * - Facade：对外只暴露 start/stop/isRunning/awaitStopped
 * - Lifecycle Manager：统一托管 cron / heartbeat / channel / agent 的生命周期
 * - Composition Root：把 Gateway 需要的运行时对象集中在这里装配
 */
public final class GatewayRuntime {

    private static final Logger log = LoggerFactory.getLogger(GatewayRuntime.class);

    private final Object lifecycleLock = new Object();
    private final ExecutorService lifecycleExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "javaclawbot-gateway-runtime");
        t.setDaemon(true);
        return t;
    });

    private final Path configPathOverride;
    private final Path workspacePathOverride;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private volatile Config config;
    private volatile MessageBus bus;
    private volatile LLMProvider provider;
    private volatile SessionManager sessionManager;
    private volatile CronService cron;
    private volatile AgentLoop agent;
    private volatile ChannelManager channels;
    private volatile HeartbeatService heartbeat;

    private volatile CompletableFuture<Void> startFuture = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> mainFuture = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> stoppedFuture = CompletableFuture.completedFuture(null);
    private volatile Future<?> bootstrapTask;

    public GatewayRuntime() {
        this(null, null);
    }

    public GatewayRuntime(Path configPathOverride, Path workspacePathOverride) {
        this.configPathOverride = configPathOverride;
        this.workspacePathOverride = workspacePathOverride;
    }

    public boolean isRunning() {
        return running.get();
    }

    public CompletionStage<Void> start() {
        synchronized (lifecycleLock) {
            if (running.get()) {
                return CompletableFuture.completedFuture(null);
            }
            if (startFuture != null && !startFuture.isDone()) {
                return startFuture;
            }

            stopping.set(false);
            stoppedFuture = new CompletableFuture<>();
            startFuture = new CompletableFuture<>();

            bootstrapTask = lifecycleExecutor.submit(() -> {
                try {
                    buildComponents();
                    if (Thread.currentThread().isInterrupted() || stopping.get()) {
                        throw new CancellationException("Gateway startup cancelled before support services start");
                    }

                    startSupportServices();
                    if (Thread.currentThread().isInterrupted() || stopping.get()) {
                        throw new CancellationException("Gateway startup cancelled after support services start");
                    }

                    running.set(true);
                    startFuture.complete(null);

                    CompletableFuture<Void> loopFuture = agent.run().toCompletableFuture();
                    mainFuture = loopFuture;

                    loopFuture.whenComplete((v, ex) -> {
                        running.set(false);
                        cleanupComponents();
                        completeStopped(ex);
                    });
                } catch (CancellationException e) {
                    cleanupComponents();
                    running.set(false);
                    startFuture.completeExceptionally(e);
                    completeStopped(null);
                } catch (Exception e) {
                    cleanupComponents();
                    running.set(false);
                    startFuture.completeExceptionally(e);
                    completeStopped(e);
                    throw new RuntimeException("GatewayRuntime start failed", e);
                }
            });

            return startFuture;
        }
    }

    public CompletionStage<Void> stop() {
        synchronized (lifecycleLock) {
            if (stopping.get()) {
                return CompletableFuture.completedFuture(null);
            }
            stopping.set(true);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                Future<?> localBootstrap = bootstrapTask;
                if (localBootstrap != null && !localBootstrap.isDone()) {
                    try {
                        localBootstrap.cancel(true);
                    } catch (Exception ignored) {
                    }
                }

                CompletableFuture<Void> localStartFuture = startFuture;
                if (localStartFuture != null && !localStartFuture.isDone()) {
                    localStartFuture.completeExceptionally(new CancellationException("Gateway startup cancelled by stop()"));
                }

                cleanupComponents();

                CompletableFuture<Void> f = mainFuture;
                if (f != null && !f.isDone()) {
                    try {
                        f.cancel(true);
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                running.set(false);
                stopping.set(false);
                completeStopped(null);
            }
        }, lifecycleExecutor);
    }

    public CompletionStage<Void> awaitStopped() {
        return stoppedFuture;
    }

    public Config getConfig() {
        return config;
    }

    private void buildComponents() {
        RuntimeComponents rt = (configPathOverride != null || workspacePathOverride != null)
                ? createRuntimeComponents(configPathOverride, workspacePathOverride)
                : createRuntimeComponents();

        this.config = rt.getConfig();
        this.bus = new MessageBus();
        this.provider = makeHotProvider();
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

        cron.setOnJob(job -> {
            var cronTool = agent.getCronTool();
            if (cronTool != null) cronTool.setCronContext(true);
            try {
                return agent.processDirect(
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
                });
            } finally {
                if (cronTool != null) cronTool.setCronContext(false);
            }
        });

        this.channels = new ChannelManager(config, provider, bus);

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
        } catch (Exception ignored) {
        }

        this.heartbeat = new HeartbeatService(
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
                    if ("cli".equals(hbChannel.get())) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return bus.publishOutbound(new OutboundMessage(
                            hbChannel.get(),
                            hbChatId.get(),
                            response != null ? response : "",
                            null,
                            null
                    )).toCompletableFuture();
                },
                HeartbeatService.parseConfig(config)
        );
    }

    private void startSupportServices() {
        cron.start().toCompletableFuture().join();
        heartbeat.start().toCompletableFuture().join();
        channels.startAll().toCompletableFuture().join();
    }

    private void cleanupComponents() {
        HeartbeatService localHeartbeat = this.heartbeat;
        this.heartbeat = null;
        if (localHeartbeat != null) {
            try {
                localHeartbeat.stop();
            } catch (Exception ignored) {
            }
        }

        CronService localCron = this.cron;
        this.cron = null;
        if (localCron != null) {
            try {
                localCron.stop();
            } catch (Exception ignored) {
            }
        }

        AgentLoop localAgent = this.agent;
        this.agent = null;
        if (localAgent != null) {
            try {
                localAgent.stop();
            } catch (Exception ignored) {
            }
            try {
                localAgent.closeMcp().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }

        ChannelManager localChannels = this.channels;
        this.channels = null;
        if (localChannels != null) {
            try {
                localChannels.stopAll().toCompletableFuture().join();
            } catch (Exception ignored) {
            }
        }

        this.bus = null;
        this.provider = null;
        this.sessionManager = null;
    }

    private void completeStopped(Throwable ex) {
        CompletableFuture<Void> f = stoppedFuture;
        if (f == null || f.isDone()) {
            return;
        }

        if (ex == null) {
            f.complete(null);
        } else {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            f.completeExceptionally(cause);
        }
    }

    private LLMProvider makeHotProvider() {
        Path configPath = configPathOverride != null ? configPathOverride : ConfigIO.getConfigPath();
        ConfigReloader reloader = new ConfigReloader(configPath);
        return new HotSwappableProvider(reloader);
    }
}
