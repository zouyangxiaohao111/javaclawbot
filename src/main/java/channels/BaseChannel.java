package channels;

import bus.InboundMessage;
import bus.MessageBus;
import bus.OutboundMessage;
import utils.Retryer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;


/**
 * 聊天平台“渠道”抽象基类。
 *
 * <p>每一种渠道（Telegram/Discord/Slack...）都应继承本类，并实现：</p>
 * <ul>
 *   <li>{@link #start()}：启动并持续监听消息</li>
 *   <li>{@link #stop()}：停止并释放资源</li>
 *   <li>{@link #send(OutboundMessage)}：发送出站消息</li>
 * </ul>
 *
 * <p>本类负责：</p>
 * <ul>
 *   <li>保存 config 与 message bus</li>
 *   <li>权限校验（allow_from 白名单）</li>
 *   <li>把入站消息封装成 {@link InboundMessage} 并投递到 {@link MessageBus}</li>
 * </ul>
 */
public abstract class BaseChannel {

    /**
     * 渠道名称（Python 里是类属性 name: str = "base"）。
     *
     * <p>Java 里用实例字段，子类可覆盖 {@link #getName()} 或直接修改该字段。</p>
     */
    protected String name = "base";

    /**
     * 渠道配置对象（Python 里是 Any）。
     *
     * <p>这里保持 Object，以支持不同渠道使用不同的配置类型。</p>
     */
    protected final Object config;

    /** 消息总线：用于发布 inbound/outbound 消息 */
    protected final MessageBus bus;

    /** 运行状态标记（对应 Python 的 self._running） */
    protected volatile boolean running = false;

    /**
     * 初始化渠道。
     *
     * @param config 渠道配置（任意类型）
     * @param bus    消息总线
     */
    protected BaseChannel(Object config, MessageBus bus) {
        this.config = config;
        this.bus = bus;
        this.running = false;
    }

    /**
     * 启动渠道并开始监听消息（长时间运行的异步任务）。
     *
     * <p>约定：</p>
     * <ol>
     *   <li>连接到具体聊天平台</li>
     *   <li>监听入站消息</li>
     *   <li>收到消息后调用 {@link #handleMessage(String, String, String, List, Map, String)}</li>
     * </ol>
     *
     * <p>为匹配 Python 的 async def，这里使用 CompletionStage<Void>。</p>
     */
    public abstract CompletionStage<Void> start();

    /**
     * 停止渠道并清理资源。
     *
     * <p>为匹配 Python 的 async def，这里使用 CompletionStage<Void>。</p>
     */
    public abstract CompletionStage<Void> stop();

    /**
     * 默认重试配置
     * @return
     */
    protected Retryer.RetryPolicy defaultRetryPolicy() {
        return new Retryer.RetryPolicy(
                3,                       // 最多 3 次（含首次）
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofSeconds(3),
                0.2                      // jitter ±20%
        );
    }

    /**
     * 通用重试执行框架：负责
     * - 记录每次重试次数
     * - 指数退避 + jitter
     * - 只在最终失败时抛出异常（由上层或子类决定最终怎么 log）
     */
    protected <T> T withRetry(
            String opName,
            Retryer.CheckedSupplier<T> work,
            java.util.function.Function<Throwable, Retryer.RetryDecision> decider
    ) throws Exception {
        return Retryer.executeWithRetry(
                opName,
                defaultRetryPolicy(),
                work,
                decider,
                msg -> java.util.logging.Logger.getLogger(getClass().getName()).warning(msg)
        );
    }

    /**
     * 通过本渠道发送消息。
     *
     * @param msg 出站消息
     */
    public abstract CompletionStage<Void> send(OutboundMessage msg);


    /**
     * BaseChannel 默认 warning 日志。
     * 子类可覆盖。
     */
    protected void logWarn(String msg) {
        java.util.logging.Logger.getLogger(getClass().getName()).warning(msg);
    }

    /**
     * BaseChannel 默认 info 日志。
     * 子类可覆盖。
     */
    protected void logInfo(String msg) {
        java.util.logging.Logger.getLogger(getClass().getName()).info(msg);
    }

    protected void logSevere(String msg) {
        java.util.logging.Logger.getLogger(TelegramChannel.class.getName()).severe(msg);
    }

    protected void logDebug(String msg) {
        // 这里用 info 代替 debug，避免引入额外日志依赖
        java.util.logging.Logger.getLogger(TelegramChannel.class.getName()).info(msg);
    }
    /**
     * 判断 sender 是否允许使用机器人。
     *
     * <p>Python 逻辑：</p>
     * <ul>
     *   <li>读取 config.allow_from（默认为空列表）</li>
     *   <li>allow_from 为空 => 允许所有人</li>
     *   <li>senderId 字符串直接命中 => 允许</li>
     *   <li>若 senderId 包含 '|'，拆分后任何一段命中 => 允许</li>
     * </ul>
     *
     * @param senderId 发送者标识
     * @return true 允许；false 不允许
     */
    public boolean isAllowed(String senderId) {
        List<String> allowList = readAllowFromList(config);

        // 如果没有 allow list，默认放行
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }

        String senderStr = String.valueOf(senderId);

        // * 代表全部放行
        if(allowList.contains("*")) {
            return true;
        }
        // 直接命中
        if (allowList.contains(senderStr)) {
            return true;
        }

        // 兼容 senderId 形如 "a|b|c" 的情况：任意分段命中就放行
        if (senderStr.contains("|")) {
            String[] parts = senderStr.split("\\|");
            for (String part : parts) {
                if (part != null && !part.isEmpty() && allowList.contains(part)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 处理来自聊天平台的入站消息：做权限校验，然后投递到 message bus。
     *
     * <p>对应 Python 的 _handle_message（增加 session_key 覆盖）。</p>
     *
     * @param senderId   发送者标识
     * @param chatId     会话/群组/频道标识
     * @param content    消息正文
     * @param media      媒体链接列表（可为 null）
     * @param metadata   额外元数据（可为 null）
     * @param sessionKey 可选：覆盖会话键（例如 thread 维度会话）
     * @return 异步完成信号
     */
    protected CompletionStage<Void> handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata,
            String sessionKey
    ) {
        // 权限拦截：不允许则记录 warning 并丢弃（与 Python 一致）
        if (!isAllowed(senderId)) {
            // 对齐 Python 的 loguru logger.warning(...) 的语义：这里用 JDK 自带 logger，避免引入额外依赖
            java.util.logging.Logger.getLogger(BaseChannel.class.getName()).warning(
                    String.format(
                            "Access denied for sender %s on channel %s. " +
                                    "Add them to allowFrom list in config to grant access.",
                            senderId, getName()
                    )
            );
            return CompletableFuture.completedFuture(null);
        }

        // Python 里 media or []，metadata or {}
        List<String> safeMedia = (media != null) ? media : new ArrayList<>();
        Map<String, Object> safeMetadata = (metadata != null) ? metadata : new HashMap<>();

        // 构造 InboundMessage（你给的 Java InboundMessage 支持该构造器）
        InboundMessage msg = new InboundMessage(
                getName(),
                String.valueOf(senderId),
                String.valueOf(chatId),
                content,
                safeMedia,
                safeMetadata
        );

        // Python 这里还会写 session_key_override=session_key
        // 对齐你给的 Java InboundMessage：通过 setter 设置覆盖会话键
        msg.setSessionKeyOverride(sessionKey);

        // 对齐你给出的 bus 方法名：publishInbound
        return bus.publishInbound(msg);
    }

    /**
     * 便捷重载：不传 sessionKey（对应 Python 的默认 None）。
     */
    protected CompletionStage<Void> handleMessage(
            String senderId,
            String chatId,
            String content,
            List<String> media,
            Map<String, Object> metadata
    ) {
        return handleMessage(senderId, chatId, content, media, metadata, null);
    }

    /**
     * 便捷重载：只传文本（media/metadata/sessionKey 都为空）。
     */
    protected CompletionStage<Void> handleMessage(
            String senderId,
            String chatId,
            String content
    ) {
        return handleMessage(senderId, chatId, content, null, null, null);
    }

    /**
     * 渠道是否正在运行。
     *
     * <p>对应 Python 的 @property is_running。</p>
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 设置运行状态（子类在 start/stop 里通常会维护该值）。
     *
     * @param running 是否运行中
     */
    protected void setRunning(boolean running) {
        this.running = running;
    }

    /**
     * 获取渠道名称。
     *
     * <p>子类可以覆盖此方法，或直接设置 {@link #name}。</p>
     */
    public String getName() {
        return name;
    }

    /**
     * 读取 config.allow_from，最大化兼容 Python 的 getattr(self.config, "allow_from", []) 行为。
     *
     * <p>由于 config 类型是 Object，因此这里使用“反射 + 宽松适配”。</p>
     * <ul>
     *   <li>优先尝试方法：getAllowFrom() / allowFrom() / getAllow_from()</li>
     *   <li>再尝试字段：allow_from / allowFrom</li>
     *   <li>支持返回类型：Collection、数组、单个字符串（逗号分隔）</li>
     *   <li>读不到或值为 null => 返回空列表</li>
     * </ul>
     */
    private static List<String> readAllowFromList(Object cfg) {
        if (cfg == null) {
            return Collections.emptyList();
        }

        // 1) Map 直接取键
        if (cfg instanceof Map<?, ?> map) {
            Object v = map.get("allow_from");
            if (v == null) {
                v = map.get("allowFrom");
            }
            return normalizeAllowFromValue(v);
        }

        // 2) 反射尝试方法
        Object valueFromMethod = tryInvokeNoArg(cfg,
                "getAllowFrom",
                "allowFrom",
                "getAllow_from",
                "getAllowfrom"
        );
        if (valueFromMethod != null) {
            return normalizeAllowFromValue(valueFromMethod);
        }

        // 3) 反射尝试字段
        Object valueFromField = tryReadField(cfg, "allow_from", "allowFrom");
        if (valueFromField != null) {
            return normalizeAllowFromValue(valueFromField);
        }

        // 4) 未配置则为空
        return Collections.emptyList();
    }

    /**
     * 把 allow_from 的各种可能形态统一转换为 List<String>。
     */
    private static List<String> normalizeAllowFromValue(Object v) {
        if (v == null) {
            return Collections.emptyList();
        }

        // Collection
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object o : c) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }

        // 数组（包含 String[] / Object[] 等）
        Class<?> cls = v.getClass();
        if (cls.isArray()) {
            int len = Array.getLength(v);
            List<String> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                Object o = Array.get(v, i);
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }

        // 单个字符串（兼容写成 "a,b,c"）
        if (v instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return Collections.emptyList();
            }
            if (trimmed.contains(",")) {
                String[] parts = trimmed.split(",");
                List<String> out = new ArrayList<>();
                for (String p : parts) {
                    String t = (p != null) ? p.trim() : "";
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
                return out;
            }
            return List.of(trimmed);
        }

        // 其他类型：转字符串当作单值
        return List.of(String.valueOf(v));
    }

    /**
     * 反射调用无参方法：按给定方法名依次尝试，成功返回结果，失败返回 null。
     */
    private static Object tryInvokeNoArg(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException e) {
                // 没有该方法：继续尝试下一个
            } catch (Exception e) {
                // 调用失败：继续尝试下一个
            }
        }
        return null;
    }

    /**
     * 反射读取字段：按给定字段名依次尝试，成功返回值，失败返回 null。
     */
    private static Object tryReadField(Object target, String... fieldNames) {
        for (String fname : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fname);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // 没有该字段：继续尝试下一个
            } catch (Exception e) {
                // 读取失败：继续尝试下一个
            }
        }
        return null;
    }
}