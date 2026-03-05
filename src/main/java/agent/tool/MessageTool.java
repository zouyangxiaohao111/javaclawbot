package agent.tool;

import bus.OutboundMessage;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Message tool for sending messages to users on chat channels.
 *
 * 1:1 with Python nanobot.agent.tools.message.MessageTool:
 * - set_context(channel, chat_id, message_id)
 * - execute(): explicit args override defaults from context
 * - requires channel & chat_id
 * - requires send_callback
 * - success: if (channel == default_channel && chat_id == default_chat_id) => _sent_in_turn = true
 * - returns a human readable status string
 *
 * Schema parameters intentionally do NOT expose message_id (matches Python).
 */
public class MessageTool extends Tool {

    private Function<OutboundMessage, CompletionStage<Void>> sendCallback;

    private String defaultChannel = "";
    private String defaultChatId = "";
    private String defaultMessageId = null;

    // Used by AgentLoop: if tool sent during turn to the default target, suppress normal final response
    private volatile boolean sentInTurn = false;

    public MessageTool() {}

    public MessageTool(Function<OutboundMessage, CompletionStage<Void>> sendCallback) {
        this.sendCallback = sendCallback;
    }

    public MessageTool(
            Function<OutboundMessage, CompletionStage<Void>> sendCallback,
            String defaultChannel,
            String defaultChatId,
            String defaultMessageId
    ) {
        this.sendCallback = sendCallback;
        this.defaultChannel = defaultChannel == null ? "" : defaultChannel;
        this.defaultChatId = defaultChatId == null ? "" : defaultChatId;
        this.defaultMessageId = defaultMessageId;
    }

    // ---------------- Python API parity ----------------

    /** Python: set_context(channel, chat_id, message_id=None) */
    public void setContext(String channel, String chatId, String messageId) {
        this.defaultChannel = channel == null ? "" : channel;
        this.defaultChatId = chatId == null ? "" : chatId;
        this.defaultMessageId = messageId;
    }

    /** Python: set_send_callback(callback) */
    public void setSendCallback(Function<OutboundMessage, CompletionStage<Void>> callback) {
        this.sendCallback = callback;
    }

    /** Python: start_turn(): reset per-turn send tracking */
    public void startTurn() {
        this.sentInTurn = false;
    }

    public boolean isSentInTurn() {
        return sentInTurn;
    }

    // ---------------- Tool interface ----------------

    @Override
    public String name() {
        return "message";
    }

    @Override
    public String description() {
        return "Send a message to the user. Use this when you want to communicate something.";
    }

    /**
     * Matches the Python JSON schema exactly: message_id is not exposed.
     */
    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("content", Map.of(
                "type", "string",
                "description", "The message content to send"
        ));
        props.put("channel", Map.of(
                "type", "string",
                "description", "Optional: target channel (telegram, discord, etc.)"
        ));
        props.put("chat_id", Map.of(
                "type", "string",
                "description", "Optional: target chat/user ID"
        ));
        props.put("media", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional: list of file paths to attach (images, audio, documents)"
        ));

        schema.put("properties", props);
        schema.put("required", List.of("content"));
        return schema;
    }

    /**
     * 1:1 with Python execute():
     * async def execute(content, channel=None, chat_id=None, message_id=None, media=None, **kwargs)
     */
    @Override
    public CompletionStage<String> execute(Map<String, Object> kwargs) {
        // ---- parse args ----
        String content = asString(kwargs.get("content"));
        String channel = asString(kwargs.get("channel"));
        String chatId = asString(kwargs.get("chat_id"));

        // Not in schema but accepted by Python execute() signature; support it here.
        String messageIdArg = asString(kwargs.get("message_id"));

        List<String> media = toStringList(kwargs.get("media"));

        // ---- apply defaults (Python: channel = channel or self._default_channel) ----
        String effectiveChannel = isBlank(channel) ? defaultChannel : channel;
        String effectiveChatId = isBlank(chatId) ? defaultChatId : chatId;
        String effectiveMessageId = isBlank(messageIdArg) ? defaultMessageId : messageIdArg;

        if (isBlank(effectiveChannel) || isBlank(effectiveChatId)) {
            return CompletableFuture.completedFuture("Error: No target channel/chat specified");
        }

        if (sendCallback == null) {
            return CompletableFuture.completedFuture("Error: Message sending not configured");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("message_id", effectiveMessageId);

        OutboundMessage msg = new OutboundMessage(
                effectiveChannel,
                effectiveChatId,
                content == null ? "" : content,
                media == null ? List.of() : media,
                metadata
        );

        final CompletionStage<Void> stage;
        try {
            stage = sendCallback.apply(msg);
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error sending message: " + String.valueOf(e));
        }

        if (stage == null) {
            return CompletableFuture.completedFuture("Error: Message sending not configured");
        }

        return stage.handle((ok, ex) -> {
            if (ex != null) {
                // Python: f"Error sending message: {str(e)}"
                return "Error sending message: " + String.valueOf(ex);
            }

            // Python: only mark sent_in_turn if sent to the default target
            if (Objects.equals(effectiveChannel, defaultChannel)
                    && Objects.equals(effectiveChatId, defaultChatId)) {
                this.sentInTurn = true;
            }

            String mediaInfo = (media != null && !media.isEmpty())
                    ? (" with " + media.size() + " attachments")
                    : "";
            return "Message sent to " + effectiveChannel + ":" + effectiveChatId + mediaInfo;
        });
    }

    // ---------------- helpers ----------------

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String asString(Object o) {
        if (o == null) return null;
        return String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }
        // tolerate single string accidentally passed
        if (o instanceof String s) {
            return List.of(s);
        }
        return null;
    }
}