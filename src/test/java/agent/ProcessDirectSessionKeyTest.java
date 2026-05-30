package agent;

import bus.InboundMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 processDirect 方法中 sessionKeyOverride 的设置
 */
public class ProcessDirectSessionKeyTest {

    @Test
    void testInboundMessageSessionKeyOverride() {
        // 模拟 processDirect 中创建 InboundMessage 的逻辑
        String effectiveChannel = "cli";
        String effectiveChatId = "4545bb75";
        String effectiveSessionKey = "cron:test001:1780152060000";

        InboundMessage msg = new InboundMessage(
                effectiveChannel,
                "user",
                effectiveChatId,
                "计算 123*456 的结果",
                null,
                null
        );

        // 修复前：没有设置 sessionKeyOverride
        // msg.getSessionKey() 会返回 "cli:4545bb75"（错误）
        assertEquals("cli:4545bb75", msg.getSessionKey(),
                "修复前：getSessionKey 返回 channel:chatId");

        // 修复后：设置 sessionKeyOverride
        msg.setSessionKeyOverride(effectiveSessionKey);
        assertEquals("cron:test001:1780152060000", msg.getSessionKey(),
                "修复后：getSessionKey 返回正确的 cron session key");
    }

    @Test
    void testSessionKeyOverridePriority() {
        InboundMessage msg = new InboundMessage("cli", "user", "4545bb75", "test");

        // 默认情况下，getSessionKey 返回 channel:chatId
        assertEquals("cli:4545bb75", msg.getSessionKey());

        // 设置 sessionKeyOverride 后，优先使用 override
        msg.setSessionKeyOverride("cron:job123");
        assertEquals("cron:job123", msg.getSessionKey());

        // 清除 sessionKeyOverride 后，恢复为 channel:chatId
        msg.setSessionKeyOverride(null);
        assertEquals("cli:4545bb75", msg.getSessionKey());
    }
}
