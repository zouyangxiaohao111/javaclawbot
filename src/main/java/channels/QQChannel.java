package channels;

import bus.MessageBus;
import bus.OutboundMessage;
import config.ConfigSchema;
import config.channel.QQConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * QQ 渠道（占位版）
 *
 * <p>说明：</p>
 * <ul>
 *   <li>Python 版本使用 botpy SDK（qq-botpy）通过 WebSocket 接入 QQ 机器人能力，并处理 C2C 私聊/私信消息。</li>
 *   <li>Java 侧目前未引入对应 SDK，因此本类把“SDK 交互部分”全部用 TODO 占位。</li>
 *   <li>其余逻辑保持可运行语义：启动/停止生命周期、去重、入站投递到 MessageBus、出站发送结构、metadata 透传等。</li>
 * </ul>
 */
public class QQChannel extends BaseChannel {

    /** 渠道名称 */
    public static final String CHANNEL_NAME = "qq";

    /**
     * QQ 配置（对应 QQConfig）
     *
     * <p>你当前工程里已存在 QQConfig：</p>
     * <ul>
     *   <li>enabled/appId/secret/allowFrom</li>
     * </ul>
     */
    private final QQConfig qqConfig;

    /**
     * QQ SDK 客户端占位对象
     *
     * <p>Python: self._client: botpy.Client</p>
     * <p>Java: 由于没有 SDK，这里用 Object 占位；后续接入 SDK 时替换为真实类型。</p>
     */
    private Object client;

    /**
     * 已处理消息 ID 去重队列（Python: deque(maxlen=1000)）
     *
     * <p>说明：用于避免重连/重复事件导致同一消息多次进入机器人。</p>
     */
    private final Deque<String> processedIds = new ArrayDeque<>(1000);

    /**
     * 去重队列最大长度（Python: maxlen=1000）
     */
    private static final int MAX_PROCESSED_IDS = 1000;

    public QQChannel(QQConfig config, MessageBus bus) {
        super(config, bus);
        this.qqConfig = Objects.requireNonNull(config, "QQConfig 不能为空");
        this.name = CHANNEL_NAME;
        this.client = null;
    }

    /**
     * 启动 QQ 机器人
     *
     * <p>Python 逻辑：</p>
     * <ul>
     *   <li>检查 QQ SDK 是否安装（QQ_AVAILABLE）</li>
     *   <li>检查 app_id/secret 配置</li>
     *   <li>启动并进入 _run_bot 自动重连循环</li>
     * </ul>
     *
     * <p>Java TODO：</p>
     * <ul>
     *   <li>初始化 QQ Java SDK Client</li>
     *   <li>注册 onReady / onC2CMessage / onDirectMessage 回调</li>
     *   <li>启动连接并保持运行</li>
     * </ul>
     */
    @Override
    public CompletionStage<Void> start() {
        // 使用异步启动，避免阻塞调用线程
        return CompletableFuture.runAsync(() -> {
            // 标记运行中
            setRunning(true);

            // 基本配置校验：与 Python 一致
            if (isBlank(qqConfig.getAppId()) || isBlank(qqConfig.getSecret())) {
                java.util.logging.Logger.getLogger(QQChannel.class.getName())
                        .severe("QQ app_id 或 secret 未配置");
                setRunning(false);
                return;
            }

            // TODO：初始化 QQ Java SDK
            // 1) 创建 client
            // 2) 关闭 SDK 自带文件日志（如果有），统一走你项目的日志体系
            // 3) 注册回调：on_ready / on_c2c_message_create / on_direct_message_create
            // 4) 启动连接（WebSocket）
            //
            // 注意：Python 版本会进入 _run_bot()，并在异常时 5 秒后重连
            // Java 侧应在这里启动一个后台线程/任务循环实现同等语义

            // 启动自动重连循环（占位实现）
            runBotReconnectLoop();
        });
    }

    /**
     * 自动重连循环（占位版）
     *
     * <p>Python:</p>
     * <pre>
     * while self._running:
     *   try: await client.start(appid, secret)
     *   except: log warning
     *   if running: sleep(5)
     * </pre>
     *
     * <p>Java TODO：</p>
     * <ul>
     *   <li>调用 SDK 的 start/connect</li>
     *   <li>异常时记录日志，等待 5 秒重连</li>
     *   <li>停止时跳出循环</li>
     * </ul>
     */
    private void runBotReconnectLoop() {
        // 这里用新线程模拟 Python 的 async 循环
        Thread t = new Thread(() -> {
            java.util.logging.Logger log = java.util.logging.Logger.getLogger(QQChannel.class.getName());
            while (isRunning()) {
                try {
                    // TODO：真正的 SDK 启动/连接
                    // client.start(appid=qqConfig.getAppId(), secret=qqConfig.getSecret());
                    log.info("QQ bot started (TODO 占位：需要接入 Java QQ SDK 才能真正连接)");

                    // TODO：阻塞等待 SDK 运行结束/断开
                    // 例如：sdk.start(...) 阻塞直到断开；或者你自己 join 一个事件循环
                    sleepSilently(60_000);
                } catch (Exception e) {
                    log.warning("QQ bot error: " + e.getMessage());
                }

                if (isRunning()) {
                    log.info("Reconnecting QQ bot in 5 seconds...");
                    sleepSilently(5_000);
                }
            }
        }, "qq-bot-loop");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 停止 QQ 机器人
     *
     * <p>Python:</p>
     * <ul>
     *   <li>self._running = False</li>
     *   <li>await self._client.close()</li>
     * </ul>
     */
    @Override
    public CompletionStage<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            setRunning(false);

            // TODO：关闭 SDK client
            if (client != null) {
                try {
                    // client.close();
                } catch (Exception ignored) {
                    // 与 Python 一样：关闭失败也不抛出
                }
            }

            java.util.logging.Logger.getLogger(QQChannel.class.getName()).info("QQ bot stopped");
        });
    }

    /**
     * 发送 QQ 消息（占位版）
     *
     * <p>Python:</p>
     * <pre>
     * msg_id = msg.metadata.get("message_id")
     * client.api.post_c2c_message(openid=msg.chat_id, msg_type=0, content=msg.content, msg_id=msg_id)
     * </pre>
     *
     * <p>Java TODO：</p>
     * <ul>
     *   <li>实现按 openid 发送 C2C 私聊消息</li>
     *   <li>把 metadata.message_id 作为 msg_id 用于回复/关联</li>
     * </ul>
     */
    @Override
    public CompletionStage<Void> send(OutboundMessage msg) {
        return CompletableFuture.runAsync(() -> {
            if (client == null) {
                java.util.logging.Logger.getLogger(QQChannel.class.getName())
                        .warning("QQ client 未初始化（TODO 占位）");
                return;
            }

            try {
                Map<String, Object> meta = (msg.getMetadata() != null) ? msg.getMetadata() : Map.of();
                Object msgIdObj = meta.get("message_id");
                String msgId = (msgIdObj != null) ? String.valueOf(msgIdObj) : null;

                // TODO：调用 SDK/API 发消息
                // client.api.post_c2c_message(
                //     openid = msg.getChatId(),
                //     msg_type = 0,
                //     content = msg.getContent(),
                //     msg_id = msgId
                // );

                java.util.logging.Logger.getLogger(QQChannel.class.getName())
                        .info("QQ send (TODO 占位): openid=" + msg.getChatId() + ", content=" + safeLogText(msg.getContent()));
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(QQChannel.class.getName())
                        .severe("Error sending QQ message: " + e.getMessage());
            }
        });
    }

    /**
     * SDK 回调入口：收到 C2C/私信消息（占位版）
     *
     * <p>Python:</p>
     * <ul>
     *   <li>按 data.id 去重</li>
     *   <li>取 author.id / author.user_openid</li>
     *   <li>content 为空则忽略</li>
     *   <li>调用 self._handle_message(sender_id=user_id, chat_id=user_id, metadata={"message_id": data.id})</li>
     * </ul>
     *
     * <p>Java TODO：</p>
     * <ul>
     *   <li>把 SDK 的 message 对象映射到：messageId / authorId(openid) / content</li>
     *   <li>然后调用本方法</li>
     * </ul>
     */
    public CompletionStage<Void> onIncomingMessage(String messageId, String userOpenId, String content) {
        return CompletableFuture.runAsync(() -> {
            try {
                // 1) 去重（按 messageId）
                if (messageId != null && isProcessed(messageId)) {
                    return;
                }
                if (messageId != null) {
                    rememberProcessed(messageId);
                }

                // 2) 内容清洗
                String text = (content != null) ? content.trim() : "";
                if (text.isEmpty()) {
                    return;
                }

                // 3) metadata 透传 message_id（用于回复）
                Map<String, Object> metadata = new HashMap<>();
                if (messageId != null) {
                    metadata.put("message_id", messageId);
                }

                // 4) 投递到总线：chat_id=用户 openid（Python 同语义：私聊会话就是用户）
                // BaseChannel 的方法名是 handleMessage（你贴的 BaseChannel）
                this.handleMessage(
                        userOpenId,
                        userOpenId,
                        text,
                        null,
                        metadata,
                        null
                ).toCompletableFuture().join();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(QQChannel.class.getName())
                        .severe("Error handling QQ message: " + e.getMessage());
            }
        });
    }

    // =========================
    // 去重队列实现（与 Python deque(maxlen=1000) 同语义）
    // =========================

    /**
     * 判断消息是否已处理过
     */
    private boolean isProcessed(String messageId) {
        // ArrayDeque 没有 contains 的高性能保证，但规模只有 1000，足够
        return processedIds.contains(messageId);
    }

    /**
     * 记录已处理消息 ID，超过上限就丢弃最旧的
     */
    private void rememberProcessed(String messageId) {
        // 控制 maxlen：超过上限移除最旧元素
        while (processedIds.size() >= MAX_PROCESSED_IDS) {
            processedIds.pollFirst();
        }
        processedIds.addLast(messageId);
    }

    // =========================
    // 工具方法
    // =========================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 安全 sleep：被中断则恢复中断标记并退出
     */
    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 日志中避免输出过长内容
     */
    private static String safeLogText(String s) {
        if (s == null) return "";
        String t = s.replace("\n", "\\n").replace("\r", "\\r");
        if (t.length() > 200) {
            return t.substring(0, 200) + "...";
        }
        return t;
    }
}