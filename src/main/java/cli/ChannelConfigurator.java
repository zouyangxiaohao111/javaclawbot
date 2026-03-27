package cli;

import config.Config;
import config.ConfigSchema;
import config.channel.*;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Channel 配置器
 * 
 * 对齐 OpenClaw 的 onboard-channels.ts
 */
public class ChannelConfigurator {

    /** Channel 元数据 */
    public record ChannelMeta(
        String id,
        String label,
        String description,
        boolean implemented,
        List<String> requiredFields
    ) {}

    /** 支持的 channel 列表 */
    private static final List<ChannelMeta> CHANNELS = List.of(
        new ChannelMeta("feishu", "飞书", "飞书机器人，支持群聊和私聊", true, 
            List.of("appId", "appSecret")),
        new ChannelMeta("telegram", "Telegram", "Telegram 机器人", false, 
            List.of("token")),
        new ChannelMeta("discord", "Discord", "Discord 机器人", false, 
            List.of("gatewayUrl")),
        new ChannelMeta("whatsapp", "WhatsApp", "WhatsApp 消息通道", false, 
            List.of("bridgeUrl")),
        new ChannelMeta("slack", "Slack", "Slack 机器人", false, 
            List.of("appToken", "botToken")),
        new ChannelMeta("dingtalk", "钉钉", "钉钉机器人", false, 
            List.of("clientId", "clientSecret")),
        new ChannelMeta("qq", "QQ", "QQ 机器人", false, 
            List.of("appId", "appSecret")),
        new ChannelMeta("email", "Email", "邮件通道", false, 
            List.of("imapHost", "smtpHost")),
        new ChannelMeta("mochat", "MoChat", "MoChat 企业微信", false, 
            List.of("baseUrl")),
        new ChannelMeta("matrix", "Matrix", "Matrix 协议", false, 
            List.of("homeserver", "accessToken"))
    );

    /**
     * 配置 channels
     */
    public static void configureChannels(Terminal terminal, LineReader reader,
                                         Config cfg, boolean advanced) {
        System.out.println();
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.println("  Channel 配置");
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.println();
        System.out.println("Channel 用于连接外部聊天平台，让 javaclawbot 可以在飞书、Telegram 等平台运行。");
        System.out.println();

        // 显示当前状态
        showChannelStatus(cfg);

        // 询问是否配置
        boolean shouldConfigure = TerminalPrompts.promptConfirm(
            reader,
            "是否配置 Channel？",
            false
        );

        if (!shouldConfigure) {
            System.out.println("  跳过 Channel 配置（可稍后编辑 ~/.javaclawbot/config.json）");
            return;
        }

        // 选择要配置的 channel
        List<ChannelMeta> selectedChannels = selectChannels(terminal, reader);

        if (selectedChannels.isEmpty()) {
            System.out.println("  未选择任何 Channel");
            return;
        }

        // 逐个配置
        for (ChannelMeta channel : selectedChannels) {
            configureChannel(terminal, reader, cfg, channel);
        }

        // 显示配置结果
        System.out.println();
        System.out.println("  ✓ Channel 配置完成");
        showChannelStatus(cfg);
    }

    /**
     * 显示 channel 状态
     */
    private static void showChannelStatus(Config cfg) {
        System.out.println("  当前 Channel 状态：");
        System.out.println();

        ChannelsConfig channels = cfg.getChannels();

        for (ChannelMeta meta : CHANNELS) {
            boolean enabled = isChannelEnabled(channels, meta.id());
            boolean configured = isChannelConfigured(channels, meta.id());
            String status = enabled ? "✓ 已启用" : (configured ? "○ 已配置" : "✗ 未配置");
            String impl = meta.implemented() ? "" : " (暂未实现)";
            System.out.println("    " + meta.label() + ": " + status + impl);
        }

        System.out.println();
    }

    /**
     * 选择要配置的 channels
     */
    private static List<ChannelMeta> selectChannels(Terminal terminal, LineReader reader) {
        List<String> options = new ArrayList<>();
        for (ChannelMeta meta : CHANNELS) {
            String impl = meta.implemented() ? "" : " (暂未实现)";
            options.add(meta.label() + impl);
        }
        options.add("✓ 完成");

        List<ChannelMeta> selected = new ArrayList<>();

        while (true) {
            String choice = TerminalPrompts.singleSelect(
                terminal,
                options,
                x -> x,
                "选择要配置的 Channel",
                options.size() - 1,  // 默认选择"完成"
                false
            );

            if (choice == null || choice.contains("完成")) {
                break;
            }

            // 找到对应的 channel
            for (int i = 0; i < CHANNELS.size(); i++) {
                ChannelMeta meta = CHANNELS.get(i);
                if (choice.startsWith(meta.label())) {
                    if (!selected.contains(meta)) {
                        selected.add(meta);
                    }
                    // 从选项中移除已选择的
                    options.remove(i);
                    options.remove(options.size() - 1); // 移除"完成"
                    options.add(i, "✓ " + meta.label() + (meta.implemented() ? "" : " (暂未实现)"));
                    options.add("✓ 完成");
                    break;
                }
            }
        }

        return selected;
    }

    /**
     * 配置单个 channel
     */
    private static void configureChannel(Terminal terminal, LineReader reader,
                                         Config cfg, ChannelMeta meta) {
        System.out.println();
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.println("  配置 " + meta.label());
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.println();
        System.out.println("  " + meta.description());
        System.out.println();

        if (!meta.implemented()) {
            System.out.println("  ⚠️ 此 Channel 暂未实现，配置将保存但不会生效。");
            System.out.println();
        }

        // 根据 channel 类型配置
        switch (meta.id()) {
            case "feishu" -> configureFeishu(reader, cfg);
            case "telegram" -> configureTelegram(reader, cfg);
            case "discord" -> configureDiscord(reader, cfg);
            case "whatsapp" -> configureWhatsApp(reader, cfg);
            case "slack" -> configureSlack(reader, cfg);
            case "dingtalk" -> configureDingTalk(reader, cfg);
            case "qq" -> configureQQ(reader, cfg);
            case "email" -> configureEmail(reader, cfg);
            case "mochat" -> configureMochat(reader, cfg);
            case "matrix" -> configureMatrix(reader, cfg);
        }

        // 询问是否启用
        boolean enable = TerminalPrompts.promptConfirm(
            reader,
            "是否启用 " + meta.label() + "？",
            true
        );

        setChannelEnabled(cfg.getChannels(), meta.id(), enable);
    }

    // ========== 各 Channel 配置方法 ==========

    private static void configureFeishu(LineReader reader, Config cfg) {
        FeishuConfig feishu = cfg.getChannels().getFeishu();

        System.out.println("  飞书机器人配置说明：");
        System.out.println("  1. 访问 https://open.feishu.cn/app");
        System.out.println("  2. 创建企业自建应用");
        System.out.println("  3. 获取 App ID 和 App Secret");
        System.out.println();

        feishu.setAppId(TerminalPrompts.promptText(reader, "App ID", feishu.getAppId()));
        feishu.setAppSecret(TerminalPrompts.promptSecret(reader, "App Secret", 
            maskSecret(feishu.getAppSecret())));
        
        // 可选配置
        boolean configureOptional = TerminalPrompts.promptConfirm(
            reader, "是否配置可选参数（加密密钥、验证令牌）？", false);
        
        if (configureOptional) {
            feishu.setEncryptKey(TerminalPrompts.promptText(reader, "Encrypt Key (可选)", 
                feishu.getEncryptKey()));
            feishu.setVerificationToken(TerminalPrompts.promptText(reader, "Verification Token (可选)", 
                feishu.getVerificationToken()));
        }

        // DM 策略
        configureDmPolicy(reader, "飞书", feishu.getAllowFrom());
    }

    private static void configureTelegram(LineReader reader, Config cfg) {
        TelegramConfig telegram = cfg.getChannels().getTelegram();

        System.out.println("  Telegram 机器人配置说明：");
        System.out.println("  1. 在 Telegram 中搜索 @BotFather");
        System.out.println("  2. 发送 /newbot 创建机器人");
        System.out.println("  3. 获取 Bot Token");
        System.out.println();

        telegram.setToken(TerminalPrompts.promptSecret(reader, "Bot Token", 
            maskSecret(telegram.getToken())));

        configureDmPolicy(reader, "Telegram", telegram.getAllowFrom());
    }

    private static void configureDiscord(LineReader reader, Config cfg) {
        DiscordConfig discord = cfg.getChannels().getDiscord();

        System.out.println("  Discord 机器人配置说明：");
        System.out.println("  1. 访问 https://discord.com/developers/applications");
        System.out.println("  2. 创建应用并添加 Bot");
        System.out.println("  3. 获取 Gateway URL 和 Token");
        System.out.println();

        discord.setGatewayUrl(TerminalPrompts.promptText(reader, "Gateway URL", 
            discord.getGatewayUrl()));
        discord.setToken(TerminalPrompts.promptSecret(reader, "Bot Token", 
            maskSecret(discord.getToken())));

        configureDmPolicy(reader, "Discord", discord.getAllowFrom());
    }

    private static void configureWhatsApp(LineReader reader, Config cfg) {
        WhatsAppConfig whatsapp = cfg.getChannels().getWhatsapp();

        System.out.println("  WhatsApp 配置说明：");
        System.out.println("  需要运行 WhatsApp Bridge 服务");
        System.out.println();

        whatsapp.setBridgeUrl(TerminalPrompts.promptText(reader, "Bridge URL", 
            whatsapp.getBridgeUrl()));
        whatsapp.setBridgeToken(TerminalPrompts.promptSecret(reader, "Bridge Token", 
            maskSecret(whatsapp.getBridgeToken())));

        configureDmPolicy(reader, "WhatsApp", whatsapp.getAllowFrom());
    }

    private static void configureSlack(LineReader reader, Config cfg) {
        SlackConfig slack = cfg.getChannels().getSlack();

        System.out.println("  Slack 机器人配置说明：");
        System.out.println("  1. 创建 Slack App");
        System.out.println("  2. 启用 Socket Mode");
        System.out.println("  3. 获取 App-Level Token 和 Bot Token");
        System.out.println();

        slack.setAppToken(TerminalPrompts.promptSecret(reader, "App Token", 
            maskSecret(slack.getAppToken())));
        slack.setBotToken(TerminalPrompts.promptSecret(reader, "Bot Token", 
            maskSecret(slack.getBotToken())));

        // Slack DM 配置
        System.out.println();
        System.out.println("  Slack DM 访问策略：");
        System.out.println("  - pairing: 未知用户需要配对码验证（推荐）");
        System.out.println("  - allowlist: 仅允许列表中的用户");
        System.out.println("  - open: 允许所有用户");
        System.out.println();

        String policy = TerminalPrompts.promptText(reader, 
            "DM 策略 (pairing/allowlist/open)", "pairing");

        if (("allowlist".equals(policy) || "open".equals(policy)) && slack.getDm() != null) {
            String users = TerminalPrompts.promptText(reader, 
                "允许的用户列表（逗号分隔，open 模式使用 *）", 
                slack.getDm().getAllowFrom().isEmpty() ? "" : String.join(",", slack.getDm().getAllowFrom()));
            
            if (users != null && !users.isBlank()) {
                slack.getDm().getAllowFrom().clear();
                for (String user : users.split(",")) {
                    String trimmed = user.trim();
                    if (!trimmed.isEmpty()) {
                        slack.getDm().getAllowFrom().add(trimmed);
                    }
                }
            }
        }
    }

    private static void configureDingTalk(LineReader reader, Config cfg) {
        DingTalkConfig dingtalk = cfg.getChannels().getDingtalk();

        System.out.println("  钉钉机器人配置说明：");
        System.out.println("  1. 访问 https://open.dingtalk.com");
        System.out.println("  2. 创建企业内部应用");
        System.out.println("  3. 获取 Client ID 和 Client Secret");
        System.out.println();

        dingtalk.setClientId(TerminalPrompts.promptText(reader, "Client ID", 
            dingtalk.getClientId()));
        dingtalk.setClientSecret(TerminalPrompts.promptSecret(reader, "Client Secret", 
            maskSecret(dingtalk.getClientSecret())));

        configureDmPolicy(reader, "钉钉", dingtalk.getAllowFrom());
    }

    private static void configureQQ(LineReader reader, Config cfg) {
        QQConfig qq = cfg.getChannels().getQq();

        System.out.println("  QQ 机器人配置说明：");
        System.out.println("  需要配置 QQ 开放平台应用");
        System.out.println();

        qq.setAppId(TerminalPrompts.promptText(reader, "App ID", qq.getAppId()));
        qq.setSecret(TerminalPrompts.promptSecret(reader, "Secret", 
            maskSecret(qq.getSecret())));

        configureDmPolicy(reader, "QQ", qq.getAllowFrom());
    }

    private static void configureEmail(LineReader reader, Config cfg) {
        EmailConfig email = cfg.getChannels().getEmail();

        System.out.println("  Email 配置说明：");
        System.out.println("  配置 IMAP 和 SMTP 服务器信息");
        System.out.println();

        // IMAP 配置
        System.out.println("  IMAP 配置（接收邮件）：");
        email.setImapHost(TerminalPrompts.promptText(reader, "IMAP Host", email.getImapHost()));
        String imapPort = TerminalPrompts.promptText(reader, "IMAP Port", 
            String.valueOf(email.getImapPort()));
        try { email.setImapPort(Integer.parseInt(imapPort)); } catch (Exception ignored) {}
        email.setImapUsername(TerminalPrompts.promptText(reader, "IMAP Username", email.getImapUsername()));
        email.setImapPassword(TerminalPrompts.promptSecret(reader, "IMAP Password", 
            maskSecret(email.getImapPassword())));

        // SMTP 配置
        System.out.println();
        System.out.println("  SMTP 配置（发送邮件）：");
        email.setSmtpHost(TerminalPrompts.promptText(reader, "SMTP Host", email.getSmtpHost()));
        String smtpPort = TerminalPrompts.promptText(reader, "SMTP Port", 
            String.valueOf(email.getSmtpPort()));
        try { email.setSmtpPort(Integer.parseInt(smtpPort)); } catch (Exception ignored) {}
        email.setSmtpUsername(TerminalPrompts.promptText(reader, "SMTP Username", email.getSmtpUsername()));
        email.setSmtpPassword(TerminalPrompts.promptSecret(reader, "SMTP Password", 
            maskSecret(email.getSmtpPassword())));
        email.setFromAddress(TerminalPrompts.promptText(reader, "发件人地址", email.getFromAddress()));
    }

    private static void configureMochat(LineReader reader, Config cfg) {
        MochatConfig mochat = cfg.getChannels().getMochat();

        System.out.println("  MoChat 配置说明：");
        System.out.println("  配置 MoChat 企业微信服务地址");
        System.out.println();

        mochat.setBaseUrl(TerminalPrompts.promptText(reader, "Base URL", mochat.getBaseUrl()));
        mochat.setClawToken(TerminalPrompts.promptSecret(reader, "Claw Token", 
            maskSecret(mochat.getClawToken())));

        configureDmPolicy(reader, "MoChat", mochat.getAllowFrom());
    }

    private static void configureMatrix(LineReader reader, Config cfg) {
        MatrixConfig matrix = cfg.getChannels().getMatrix();

        System.out.println("  Matrix 配置说明：");
        System.out.println("  配置 Matrix Homeserver 和访问令牌");
        System.out.println();

        matrix.setHomeserver(TerminalPrompts.promptText(reader, "Homeserver URL", 
            matrix.getHomeserver()));
        matrix.setAccessToken(TerminalPrompts.promptSecret(reader, "Access Token", 
            maskSecret(matrix.getAccessToken())));

        configureDmPolicy(reader, "Matrix", matrix.getAllowFrom());
    }

    // ========== 辅助方法 ==========

    /**
     * 配置 DM 策略
     */
    private static void configureDmPolicy(LineReader reader, String channelName, 
                                           List<String> allowFrom) {
        System.out.println();
        System.out.println("  " + channelName + " DM 访问策略：");
        System.out.println("  - pairing: 未知用户需要配对码验证（推荐）");
        System.out.println("  - allowlist: 仅允许列表中的用户");
        System.out.println("  - open: 允许所有用户");
        System.out.println();

        String policy = TerminalPrompts.promptText(reader, 
            "DM 策略 (pairing/allowlist/open)", "pairing");

        if ("allowlist".equals(policy) || "open".equals(policy)) {
            String users = TerminalPrompts.promptText(reader, 
                "允许的用户列表（逗号分隔，open 模式使用 *）", 
                allowFrom.isEmpty() ? "" : String.join(",", allowFrom));
            
            if (users != null && !users.isBlank()) {
                allowFrom.clear();
                for (String user : users.split(",")) {
                    String trimmed = user.trim();
                    if (!trimmed.isEmpty()) {
                        allowFrom.add(trimmed);
                    }
                }
            }
        }
    }

    /**
     * 检查 channel 是否启用
     */
    private static boolean isChannelEnabled(ChannelsConfig channels, String id) {
        return switch (id) {
            case "feishu" -> channels.getFeishu().isEnabled();
            case "telegram" -> channels.getTelegram().isEnabled();
            case "discord" -> channels.getDiscord().isEnabled();
            case "whatsapp" -> channels.getWhatsapp().isEnabled();
            case "slack" -> channels.getSlack().isEnabled();
            case "dingtalk" -> channels.getDingtalk().isEnabled();
            case "qq" -> channels.getQq().isEnabled();
            case "email" -> channels.getEmail().isEnabled();
            case "mochat" -> channels.getMochat().isEnabled();
            case "matrix" -> channels.getMatrix().isEnabled();
            default -> false;
        };
    }

    /**
     * 检查 channel 是否已配置
     */
    private static boolean isChannelConfigured(ChannelsConfig channels, String id) {
        return switch (id) {
            case "feishu" -> {
                var c = channels.getFeishu();
                yield c.getAppId() != null && !c.getAppId().isBlank();
            }
            case "telegram" -> {
                var c = channels.getTelegram();
                yield c.getToken() != null && !c.getToken().isBlank();
            }
            case "discord" -> {
                var c = channels.getDiscord();
                yield c.getGatewayUrl() != null && !c.getGatewayUrl().isBlank();
            }
            case "whatsapp" -> {
                var c = channels.getWhatsapp();
                yield c.getBridgeUrl() != null && !c.getBridgeUrl().isBlank();
            }
            case "slack" -> {
                var c = channels.getSlack();
                yield c.getAppToken() != null && !c.getAppToken().isBlank();
            }
            case "dingtalk" -> {
                var c = channels.getDingtalk();
                yield c.getClientId() != null && !c.getClientId().isBlank();
            }
            case "qq" -> {
                var c = channels.getQq();
                yield c.getAppId() != null && !c.getAppId().isBlank();
            }
            case "email" -> {
                var c = channels.getEmail();
                yield c.getImapHost() != null && !c.getImapHost().isBlank();
            }
            case "mochat" -> {
                var c = channels.getMochat();
                yield c.getBaseUrl() != null && !c.getBaseUrl().isBlank();
            }
            case "matrix" -> {
                var c = channels.getMatrix();
                yield c.getHomeserver() != null && !c.getHomeserver().isBlank();
            }
            default -> false;
        };
    }

    /**
     * 设置 channel 启用状态
     */
    private static void setChannelEnabled(ChannelsConfig channels, 
                                          String id, boolean enabled) {
        switch (id) {
            case "feishu" -> channels.getFeishu().setEnabled(enabled);
            case "telegram" -> channels.getTelegram().setEnabled(enabled);
            case "discord" -> channels.getDiscord().setEnabled(enabled);
            case "whatsapp" -> channels.getWhatsapp().setEnabled(enabled);
            case "slack" -> channels.getSlack().setEnabled(enabled);
            case "dingtalk" -> channels.getDingtalk().setEnabled(enabled);
            case "qq" -> channels.getQq().setEnabled(enabled);
            case "email" -> channels.getEmail().setEnabled(enabled);
            case "mochat" -> channels.getMochat().setEnabled(enabled);
            case "matrix" -> channels.getMatrix().setEnabled(enabled);
        }
    }

    /**
     * 遮蔽敏感信息
     */
    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return "";
        if (secret.length() <= 8) return "********";
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }
}