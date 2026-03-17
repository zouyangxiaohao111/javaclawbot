package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import com.google.gson.Gson;
import config.ConfigSchema;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 渠道管理器：负责初始化/启动/停止各聊天渠道，并派发出站消息。
 *
 * 职责：
 * - 根据配置启用并初始化渠道（Telegram / WhatsApp / ...）
 * - 启动/停止所有渠道
 * - 启动出站派发器：从 MessageBus 取 OutboundMessage，按 channel 字段路由发送
 *
 * 设计要点：
 * - 使用“反射”按类名加载渠道，模拟 Python 的 try-import 行为：
 *   类不存在/依赖未引入 => 仅记录 warning，不导致工程编译失败
 * - 出站派发器轮询 bus.consumeOutbound()，每次最多等待 1 秒，便于 stop 时快速退出
 */
public class ChannelManager {

    /** 根配置 */
    private final ConfigSchema.Config config;
    /** 消息总线 */
    private final MessageBus bus;

    /** 已启用渠道：name -> channel 实例 */
    private final Map<String, BaseChannel> channels = new ConcurrentHashMap<>();

    /** 出站派发器运行标记 */
    private final AtomicBoolean dispatcherRunning = new AtomicBoolean(false);

    /** 线程资源：启动渠道、派发器均在后台线程运行 */
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    /** 出站派发器任务句柄 */
    private volatile Future<?> dispatchFuture;

    public ChannelManager(ConfigSchema.Config config, MessageBus bus) {
        this.config = Objects.requireNonNull(config, "config 不能为空");
        this.bus = Objects.requireNonNull(bus, "bus 不能为空");

        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "channel-manager-worker");
            t.setDaemon(true);
            return t;
        });

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "channel-manager-scheduler");
            t.setDaemon(true);
            return t;
        });

        initChannels();
    }

    /**
     * 根据配置初始化渠道。
     *
     * 说明：
     * - 这里不直接引用具体渠道类，避免你未引入某 SDK 时编译失败
     * - 通过反射按类名加载：加载失败就 warning（等价 Python 的 ImportError）
     */
    private void initChannels() {
        // Telegram
        if (config.getChannels().getTelegram().isEnabled()) {
            // 你后面说 Telegram 用 TelegramBots 库，这里先按类名约定加载你的实现类
            // TODO：当你实现 TelegramChannel 后，保证类名/包名一致即可
            // groq_api_key 逻辑：Python 这里传 config.providers.groq.api_key
            // 但你 ConfigSchema 里不一定有 groq 这个 provider；这里先尝试从 providers 里按名称取
            String groqApiKey = null;
            try {
                var groq = config.getProviders().getByName("groq");
                if (groq != null) groqApiKey = groq.getApiKey();
            } catch (Exception ignored) {
            }

            // 约定你的类名：javaclawbot.channels.telegram.TelegramChannel -> Java 侧可能是 channels.TelegramChannel
            // 这里用更常见的：channels.TelegramChannel
            tryLoadChannel(
                    "telegram",
                    "channels.TelegramChannel",
                    new Class<?>[]{ConfigSchema.TelegramConfig.class, MessageBus.class, String.class},
                    new Object[]{config.getChannels().getTelegram(), bus, (groqApiKey == null ? "" : groqApiKey)}
            );
        }

        // WhatsApp（你已实现 channels.WhatsAppChannel）
        if (config.getChannels().getWhatsapp().isEnabled()) {
            tryLoadChannel(
                    "whatsapp",
                    "channels.WhatsAppChannel",
                    new Class<?>[]{ConfigSchema.WhatsAppConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getWhatsapp(), bus}
            );
        }

        // Discord
        if (config.getChannels().getDiscord().isEnabled()) {
            tryLoadChannel(
                    "discord",
                    "channels.DiscordChannel",
                    new Class<?>[]{ConfigSchema.DiscordConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getDiscord(), bus}
            );
        }

        // Feishu
        if (config.getChannels().getFeishu().isEnabled()) {
            tryLoadChannel(
                    "feishu",
                    "channels.FeishuChannel",
                    new Class<?>[]{ConfigSchema.FeishuConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getFeishu(), bus}
            );
        }

        // Mochat
        if (config.getChannels().getMochat().isEnabled()) {
            tryLoadChannel(
                    "mochat",
                    "channels.MochatChannel",
                    new Class<?>[]{ConfigSchema.MochatConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getMochat(), bus}
            );
        }

        // DingTalk
        if (config.getChannels().getDingtalk().isEnabled()) {
            tryLoadChannel(
                    "dingtalk",
                    "channels.DingTalkChannel",
                    new Class<?>[]{ConfigSchema.DingTalkConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getDingtalk(), bus}
            );
        }

        // Email
        if (config.getChannels().getEmail().isEnabled()) {
            tryLoadChannel(
                    "email",
                    "channels.EmailChannel",
                    new Class<?>[]{ConfigSchema.EmailConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getEmail(), bus}
            );
        }

        // Slack
        if (config.getChannels().getSlack().isEnabled()) {
            tryLoadChannel(
                    "slack",
                    "channels.SlackChannel",
                    new Class<?>[]{ConfigSchema.SlackConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getSlack(), bus}
            );
        }

        // QQ（你之前说 SDK 缺失允许 TODO；这里仍用反射）
        if (config.getChannels().getQq().isEnabled()) {
            tryLoadChannel(
                    "qq",
                    "channels.QQChannel",
                    new Class<?>[]{ConfigSchema.QQConfig.class, MessageBus.class},
                    new Object[]{config.getChannels().getQq(), bus}
            );
        }

        // Matrix（如果你后续实现）
        // TODO：你提供的 Python MatrixChannel 依赖 matrix-nio，Java 需要另选 SDK；暂时保留可选加载位
        // 你 ConfigSchema 当前也没有 matrix 字段；如果后面加了再补
        // tryLoadChannel("matrix", "channels.MatrixChannel", ...);

        if (channels.isEmpty()) {
            logWarning("No channels enabled");
        }
    }

    /**
     * 尝试通过反射加载并实例化渠道。
     *
     * @param name            渠道名（例如 telegram）
     * @param className       渠道类名（例如 channels.TelegramChannel）
     * @param ctorParamTypes  构造器参数类型
     * @param ctorArgs        构造器参数值
     */
    private void tryLoadChannel(String name,
                                String className,
                                Class<?>[] ctorParamTypes,
                                Object[] ctorArgs) {
        try {
            Class<?> clazz = Class.forName(className);
            if (!BaseChannel.class.isAssignableFrom(clazz)) {
                logWarning("Channel class not a BaseChannel: " + className);
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends BaseChannel> c = (Class<? extends BaseChannel>) clazz;

            Constructor<? extends BaseChannel> ctor = c.getConstructor(ctorParamTypes);
            BaseChannel instance = ctor.newInstance(ctorArgs);

            channels.put(name, instance);
            logInfo(capFirst(name) + " channel enabled");
        } catch (ClassNotFoundException e) {
            // 等价 Python ImportError：依赖没装或没实现
            logWarning(capFirst(name) + " channel not available: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            // 构造器签名不匹配：需要你提供真实签名
            logWarning(capFirst(name) + " channel ctor mismatch: " + e.getMessage());
        } catch (Throwable t) {
            logWarning(capFirst(name) + " channel init failed: " + t.getMessage());
        }
    }

    /**
     * 启动全部渠道 + 出站派发器。
     *
     * 语义对齐 Python：
     * - 如果没有任何渠道启用：warning 并直接返回
     * - 先启动派发器（持续运行）
     * - 再启动每个渠道（通常也是持续运行）
     *
     * @return 异步完成（注意：渠道一般“常驻运行”，因此通常不会自然完成）
     */
    public CompletionStage<Void> startAll() {
        if (channels.isEmpty()) {
            logWarning("No channels enabled");
            return CompletableFuture.completedFuture(null);
        }

        // 启动出站派发器
        startOutboundDispatcher();

        // 启动各渠道
        List<CompletableFuture<Void>> starts = new ArrayList<>();
        for (Map.Entry<String, BaseChannel> e : channels.entrySet()) {
            String name = e.getKey();
            BaseChannel ch = e.getValue();

            logInfo("Starting " + name + " channel...");

            CompletableFuture<Void> f = ch.start()
                    .toCompletableFuture()
                    .exceptionally(ex -> {
                        logSevere("Failed to start channel " + name + ": " + ex.getMessage());
                        return null;
                    });

            starts.add(f);
        }

        // 等价 asyncio.gather(...): 全部启动任务完成（通常不会完成）
        return CompletableFuture.allOf(starts.toArray(new CompletableFuture[0]));
    }

    /**
     * 停止全部渠道 + 派发器。
     */
    public CompletionStage<Void> stopAll() {
        return CompletableFuture.runAsync(() -> {
            logInfo("Stopping all channels...");

            // 停止派发器
            stopOutboundDispatcher();

            // 停止所有渠道
            for (Map.Entry<String, BaseChannel> e : channels.entrySet()) {
                String name = e.getKey();
                BaseChannel ch = e.getValue();
                try {
                    ch.stop().toCompletableFuture().join();
                    logInfo("Stopped " + name + " channel");
                } catch (Exception ex) {
                    logSevere("Error stopping " + name + ": " + ex.getMessage());
                }
            }
        }, executor);
    }

    /**
     * 出站派发器：从 bus 取 OutboundMessage，按 channel 路由发送。
     *
     * Python 语义：
     * - consume_outbound() 最多等 1 秒，超时继续循环
     * - _progress 消息按 send_progress / send_tool_hints 开关过滤
     * - 未知 channel 记录 warning
     */
    private void startOutboundDispatcher() {
        if (dispatchFuture != null && !dispatchFuture.isDone()) {
            return;
        }

        dispatcherRunning.set(true);
        dispatchFuture = executor.submit(() -> {
            logInfo("Outbound dispatcher started");

            while (dispatcherRunning.get()) {
                try {
                    // 等待一条出站消息，最长 1 秒（便于 stop 时快速退出）
                    OutboundMessage msg = bus.consumeOutbound(1, TimeUnit.SECONDS);
                    // by zcw 发现如果是 outbound.take 然后使用同一个线程池 造成无限等待, 并不会消费到消息
                            /*.toCompletableFuture()
                            .orTimeout(1, TimeUnit.SECONDS)
                            .join();*/

                    if (msg == null) {
                        continue;
                    }

                    //logInfo("收到出站消息:" + new Gson().toJson(msg));

                    // 过滤 progress/tool_hint
                    if (isTrue(msg.getMetadata().get("_progress"))) {
                        boolean isToolHint = isTrue(msg.getMetadata().get("_tool_hint"));
                        if (isToolHint && !config.getChannels().isSendToolHints()) {
                            continue;
                        }
                        if (!isToolHint && !config.getChannels().isSendProgress()) {
                            continue;
                        }
                    }

                    BaseChannel channel = channels.get(msg.getChannel());
                    if (channel != null) {
                        try {
                            channel.send(msg).toCompletableFuture().join();
                        } catch (Exception ex) {
                            logSevere("Error sending to " + msg.getChannel() + ": " + ex.getMessage());
                        }
                    } else {
                        logWarning("Unknown channel: " + msg.getChannel());
                    }

                } catch (CompletionException ce) {
                    // 1 秒超时会进入这里（TimeoutException 被包了一层）
                    Throwable cause = ce.getCause();
                    if (cause instanceof TimeoutException) {
                        continue;
                    }
                    // stop 时 join 也可能抛 CancellationException
                    if (cause instanceof CancellationException) {
                        break;
                    }
                    logWarning("Outbound dispatcher error: " + (cause == null ? ce.getMessage() : cause.getMessage()));
                } catch (CancellationException ignored) {
                    break;
                } catch (Exception e) {
                    logWarning("Outbound dispatcher error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 停止出站派发器。
     */
    private void stopOutboundDispatcher() {
        dispatcherRunning.set(false);

        Future<?> f = dispatchFuture;
        dispatchFuture = null;
        if (f != null) {
            f.cancel(true);
            try {
                f.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取指定渠道。
     */
    public BaseChannel getChannel(String name) {
        if (name == null) return null;
        return channels.get(name);
    }

    /**
     * 获取所有渠道状态。
     *
     * 返回结构对齐 Python：
     * {
     *   "telegram": {"enabled": true, "running": true/false},
     *   ...
     * }
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, BaseChannel> e : channels.entrySet()) {
            String name = e.getKey();
            BaseChannel ch = e.getValue();

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("enabled", true);
            s.put("running", ch.isRunning());

            out.put(name, s);
        }
        return out;
    }

    /**
     * 获取已启用渠道名列表。
     */
    public List<String> getEnabledChannels() {
        return new ArrayList<>(channels.keySet());
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private static boolean isTrue(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            return "true".equals(t) || "1".equals(t) || "yes".equals(t);
        }
        return false;
    }

    private static String capFirst(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void logInfo(String msg) {
        java.util.logging.Logger.getLogger(ChannelManager.class.getName()).info(msg);
    }

    private void logWarning(String msg) {
        java.util.logging.Logger.getLogger(ChannelManager.class.getName()).warning(msg);
    }

    private void logSevere(String msg) {
        java.util.logging.Logger.getLogger(ChannelManager.class.getName()).severe(msg);
    }
}