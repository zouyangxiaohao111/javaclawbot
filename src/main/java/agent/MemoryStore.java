package agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import session.Session;
import utils.Helpers;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 持久化记忆系统：
 * - MEMORY.md：长期事实（覆盖写）
 * - HISTORY.md：可检索历史日志（追加写）
 *
 * 工作方式：
 * - 把一段旧对话整理成可读文本
 * - 让模型通过工具调用 save_memory 返回：
 *   1) history_entry：追加写入 HISTORY.md
 *   2) memory_update：写入 MEMORY.md（若与当前不同才写）
 */
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 虚拟工具：要求模型以工具调用形式返回记忆压缩结果
     */
    private static final List<Map<String, Object>> SAVE_MEMORY_TOOL = buildSaveMemoryTool();

    private final Path memoryDir;
    private final Path memoryFile;
    private final Path historyFile;

    public MemoryStore(Path workspace) {
        // 确保 memory 目录存在
        this.memoryDir = Helpers.ensureDir(Objects.requireNonNull(workspace, "workspace").resolve("memory"));
        this.memoryFile = this.memoryDir.resolve("MEMORY.md");
        this.historyFile = this.memoryDir.resolve("HISTORY.md");
    }

    /**
     * 读取长期记忆（不存在则返回空串）
     */
    public String readLongTerm() {
        if (!Files.exists(memoryFile)) return "";
        try {
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 写入长期记忆（覆盖写）
     */
    public void writeLongTerm(String content) {
        try {
            Files.writeString(
                    memoryFile,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            throw new RuntimeException("写入 MEMORY.md 失败", e);
        }
    }

    /**
     * 追加历史日志（每条后面空一行，便于人读和 grep）
     */
    public void appendHistory(String entry) {
        try {
            Files.createDirectories(historyFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(
                    historyFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                w.write(rstrip(entry));
                w.newLine();
                w.newLine();
            }
        } catch (Exception e) {
            throw new RuntimeException("追加写入 HISTORY.md 失败", e);
        }
    }

    /**
     * 返回注入到系统提示词的记忆片段
     */
    public String getMemoryContext() {
        String longTerm = readLongTerm();
        return (longTerm != null && !longTerm.isEmpty())
                ? "## Long-term Memory\n" + longTerm
                : "";
    }

    /**
     * 将旧消息压缩到 MEMORY.md + HISTORY.md
     *
     * 行为说明：
     * - archiveAll=true：压缩全部消息，不保留尾部窗口，lastConsolidated 最终置 0
     * - archiveAll=false：保留 memoryWindow/2 条最新消息，其余按 lastConsolidated 指针开始压缩
     *
     * @param session      会话对象（需要：messages + lastConsolidated 可读写）
     * @param provider     模型提供者
     * @param model        模型名
     * @param archiveAll   是否归档全部
     * @param memoryWindow 记忆窗口（默认 50 一类）
     * @return 成功（含无操作）返回 true；失败返回 false
     */
    public CompletableFuture<Boolean> consolidate(
            Session session,
            LLMProvider provider,
            String model,
            boolean archiveAll,
            int memoryWindow
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");

        final List<Map<String, Object>> oldMessages;
        final int keepCount;

        if (archiveAll) {
            oldMessages = session.getMessages();
            keepCount = 0;
            log.info("记忆压缩（归档全部）：{} 条消息", session.getMessages().size());
        } else {
            keepCount = memoryWindow / 2;

            // 消息不足以产生“可压缩区间”
            if (session.getMessages().size() <= keepCount) {
                return CompletableFuture.completedFuture(true);
            }

            // 没有新增可压缩内容
            if (session.getMessages().size() - session.getLastConsolidated() <= 0) {
                return CompletableFuture.completedFuture(true);
            }

            int from = session.getLastConsolidated();
            int toExclusive = session.getMessages().size() - keepCount;

            if (toExclusive <= from) {
                return CompletableFuture.completedFuture(true);
            }

            oldMessages = session.getMessages().subList(from, toExclusive);

            if (oldMessages.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }

            log.info("记忆压缩：压缩 {} 条，保留 {} 条", oldMessages.size(), keepCount);
        }

        // 把待压缩消息转换成可读文本（空 content 直接跳过）
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> m : oldMessages) {
            if (m == null) continue;

            Object contentObj = m.get("content");
            if (contentObj == null) continue;

            String content = String.valueOf(contentObj);
            if (content.isEmpty()) continue;

            // 时间戳只保留到分钟（便于统一检索）
            String timestamp = safeTsPrefix(m.get("timestamp"));
            String role = String.valueOf(m.getOrDefault("role", "?")).toUpperCase(Locale.ROOT);

            // tools_used：用于检索（可选）
            String toolsSuffix = "";
            Object toolsUsedObj = m.get("tools_used");
            if (toolsUsedObj instanceof List<?> list && !list.isEmpty()) {
                String joined = list.stream().map(String::valueOf).collect(Collectors.joining(", "));
                toolsSuffix = " [tools: " + joined + "]";
            }

            lines.add("[" + timestamp + "] " + role + toolsSuffix + ": " + content);
        }

        String currentMemory = readLongTerm();

        // 注意：这里的 prompt 用纯文本拼接，避免 JSON 结构干扰模型
        String prompt = ""
                + "Process this conversation and call the save_memory tool with your consolidation.\n\n"
                + "## Current Long-term Memory\n"
                + ((currentMemory == null || currentMemory.isEmpty()) ? "(empty)" : currentMemory)
                + "\n\n"
                + "## Conversation to Process\n"
                + String.join("\n", lines);

        List<Map<String, Object>> messages = List.of(
                Map.of(
                        "role", "system",
                        "content", "You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."
                ),
                Map.of(
                        "role", "user",
                        "content", prompt
                )
        );

        // 这里保持默认 max_tokens/temperature，与 provider 默认一致（但显式传入更稳定）
        return chatCompat(provider, messages, SAVE_MEMORY_TOOL, model, 4096, 0.7, null)
                .handle((resp, ex) -> {
                    if (ex != null) {
                        log.error("记忆压缩失败", ex);
                        return false;
                    }
                    try {
                        return handleResponse(session, resp, archiveAll, keepCount, currentMemory);
                    } catch (Exception e) {
                        log.error("记忆压缩处理失败", e);
                        return false;
                    }
                });
    }

    /**
     * 处理模型返回的工具调用结果
     */
    private boolean handleResponse(
            Session session,
            LLMResponse response,
            boolean archiveAll,
            int keepCount,
            String currentMemory
    ) throws Exception {
        if (response == null || !response.hasToolCalls() || response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            log.warn("记忆压缩：模型未调用 save_memory，跳过");
            return false;
        }

        // 只取第一个工具调用
        var toolCall = response.getToolCalls().get(0);

        // 说明：
        // - 有些提供者会把 arguments 放成 Map（常见）
        // - 也可能放成 JSON 字符串（少数实现）
        // - 这里都做兼容
        Object argsObj = toolCall.getArguments();

        Map<String, Object> args;
        if (argsObj instanceof String s) {
            args = MAPPER.readValue(s, new TypeReference<>() {});
        } else if (argsObj instanceof Map<?, ?> m) {
            args = castToStringObjectMap(m);
        } else {
            // 如果 ToolCallRequest 里 arguments 固定就是 Map，这里基本不会触发
            log.warn("记忆压缩：工具参数类型异常：{}", (argsObj == null ? "null" : argsObj.getClass().getName()));
            return false;
        }

        // history_entry：写入 HISTORY.md（非字符串则转 JSON 保留信息）
        Object historyEntryObj = args.get("history_entry");
        if (historyEntryObj != null) {
            String entry = (historyEntryObj instanceof String)
                    ? (String) historyEntryObj
                    : MAPPER.writeValueAsString(historyEntryObj);
            appendHistory(entry);
        }

        // memory_update：写入 MEMORY.md（若与当前不同才写，避免无意义覆盖）
        Object memoryUpdateObj = args.get("memory_update");
        if (memoryUpdateObj != null) {
            String update = (memoryUpdateObj instanceof String)
                    ? (String) memoryUpdateObj
                    : MAPPER.writeValueAsString(memoryUpdateObj);

            if (!Objects.equals(update, currentMemory)) {
                writeLongTerm(update);
            }
        }

        // 更新 lastConsolidated 指针：
        // - archiveAll：归档后指针归零
        // - 否则：指向“被保留窗口”的起点
        int newLastConsolidated = archiveAll ? 0 : (session.getMessages().size() - keepCount);
        session.setLastConsolidated(newLastConsolidated);

        log.info("记忆压缩完成：总消息 {}，last_consolidated={}", session.getMessages().size(), session.getLastConsolidated());
        return true;
    }

    /**
     * 对不同 LLMProvider.chat 签名做兼容：
     * - 若存在 6 参版本（含 reasoning_effort），优先调用
     * - 否则回退到 5 参版本
     */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<LLMResponse> chatCompat(
            LLMProvider provider,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort
    ) {
        try {
            // 尝试：chat(messages, tools, model, maxTokens, temperature, reasoningEffort)
            Method m = provider.getClass().getMethod(
                    "chat",
                    List.class, List.class, String.class, int.class, double.class, String.class
            );
            Object r = m.invoke(provider, messages, tools, model, maxTokens, temperature, reasoningEffort);
            if (r instanceof CompletableFuture<?> f) {
                return (CompletableFuture<LLMResponse>) f;
            }
        } catch (NoSuchMethodException ignored) {
            // 没有 6 参版本则走回退
        } catch (Exception e) {
            // 反射调用失败也回退到 5 参版本
            log.debug("chat 6参调用失败，回退到 5参：{}", e.toString());
        }

        // 回退：chat(messages, tools, model, maxTokens, temperature)
        return provider.chat(messages, tools, model, maxTokens, temperature);
    }

    /**
     * 把任意 Map 的 key 转成字符串，便于后续读取
     */
    private static Map<String, Object> castToStringObjectMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 时间戳只取前 16 个字符（通常是 YYYY-MM-DD HH:MM）
     */
    private static String safeTsPrefix(Object tsObj) {
        if (tsObj == null) return "?";
        String ts = String.valueOf(tsObj);
        return ts.length() >= 16 ? ts.substring(0, 16) : ts;
    }

    /**
     * 去掉字符串末尾的空白字符（用于 history 追加写）
     */
    private static String rstrip(String s) {
        if (s == null) return "";
        int i = s.length() - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i--;
            else break;
        }
        return s.substring(0, i + 1);
    }

    /**
     * 构造 save_memory 工具 schema
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildSaveMemoryTool() {
        Map<String, Object> historyEntry = new LinkedHashMap<>();
        historyEntry.put("type", "string");
        historyEntry.put("description",
                "A paragraph (2-5 sentences) summarizing key events/decisions/topics. " +
                        "Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search.");

        Map<String, Object> memoryUpdate = new LinkedHashMap<>();
        memoryUpdate.put("type", "string");
        memoryUpdate.put("description",
                "Full updated long-term memory as markdown. Include all existing facts plus new ones. " +
                        "Return unchanged if nothing new.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("history_entry", historyEntry);
        properties.put("memory_update", memoryUpdate);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("history_entry", "memory_update"));

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "save_memory");
        function.put("description", "Save the memory consolidation result to persistent storage.");
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);

        return List.of(tool);
    }
}