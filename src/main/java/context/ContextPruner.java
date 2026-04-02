package context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * 上下文修剪器
 * 
 * 对齐 OpenClaw 的 context-pruning/pruner.ts
 * 
 * 功能：
 * - 软修剪：当上下文使用率 > softTrimRatio 时，修剪工具结果（保留头尾）
 * - 硬清除：当上下文使用率 > hardClearRatio 时，清除旧的工具结果内容
 */
public class ContextPruner {

    private static final Logger log = LoggerFactory.getLogger(ContextPruner.class);

    private static final String PRUNED_CONTEXT_IMAGE_MARKER = "[image removed during context pruning]";


    /**
     * 修剪上下文消息
     *
     * @param messages           消息列表
     * @param settings           修剪配置
     * @param contextWindowTokens 上下文窗口大小
     * @param isToolPrunable     判断工具是否可修剪
     * @return 修剪后的消息列表
     */
    public static List<Map<String, Object>> pruneContextMessages(
            List<Map<String, Object>> messages,
            ContextPruningSettings settings,
            int contextWindowTokens,
            Predicate<String> isToolPrunable
    ) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        if (!settings.isEnabled() || contextWindowTokens <= 0) {
            return new ArrayList<>(messages);
        }

        // 计算字符窗口
        int charWindow = (int) Math.floor(contextWindowTokens * ContextPruningSettings.CHARS_PER_TOKEN_ESTIMATE);
        if (charWindow <= 0) {
            return new ArrayList<>(messages);
        }

        // 找到最后一个用户消息的索引，只裁剪该消息之前的工具结果
        // （之后的工具结果属于当前轮次，不应裁剪）
        int cutoffIndex = findLastUserIndex(messages);
        if (cutoffIndex == -1) {
            return new ArrayList<>(messages);
        }

        // 找到第一个用户消息的索引（保护初始身份读取）
        int firstUserIndex = findFirstUserIndex(messages);
        int pruneStartIndex = firstUserIndex == -1 ? messages.size() : firstUserIndex;

        // 使用默认的可修剪判断
        if (isToolPrunable == null) {
            isToolPrunable = toolName -> true; // 默认所有工具都可修剪
        }

        // 计算当前上下文大小
        int totalCharsBefore = estimateContextChars(messages);
        int totalChars = totalCharsBefore;
        double ratio = (double) totalChars / charWindow;

        // 如果低于软修剪阈值，不需要修剪
        if (ratio < settings.getSoftTrimRatio()) {
            return new ArrayList<>(messages);
        }

        // 收集可修剪的工具结果索引
        List<Integer> prunableToolIndexes = new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>(messages);

        for (int i = pruneStartIndex; i < cutoffIndex; i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) continue;

            Object role = msg.get("role");
            if (!"tool".equals(role)) continue;

            String toolName = getToolName(msg);
            if (!isToolPrunable.test(toolName)) continue;

            prunableToolIndexes.add(i);

            // 尝试软修剪
            Map<String, Object> trimmed = softTrimToolResult(msg, settings);
            if (trimmed != null) {
                int beforeChars = estimateMessageChars(msg);
                int afterChars = estimateMessageChars(trimmed);
                totalChars += afterChars - beforeChars;
                result.set(i, trimmed);
            }
        }

        // 检查是否需要硬清除
        // by zcw 硬清除交给llm了 所以这里不需要了
        /*ratio = (double) totalChars / charWindow;
        if (ratio < settings.getHardClearRatio() || !settings.getHardClear().isEnabled()) {
            return result;
        }

        // 计算可修剪工具结果的字符数
        int prunableToolChars = 0;
        for (int i : prunableToolIndexes) {
            Map<String, Object> msg = result.get(i);
            if (msg == null) {
                continue;
            }
            prunableToolChars += estimateMessageChars(msg);
        }

        // 如果可修剪字符数不足，不执行硬清除
        if (prunableToolChars < settings.obtainMinPrunableToolCharsByContextWindow(charWindow)) {
            return result;
        }

        // 执行硬清除
        for (int i : prunableToolIndexes) {
            if (ratio < settings.getHardClearRatio()) {
                break;
            }

            Map<String, Object> msg = result.get(i);
            if (msg == null) continue;

            int beforeChars = estimateMessageChars(msg);

            // 创建清除后的消息
            Map<String, Object> cleared = new LinkedHashMap<>(msg);
            cleared.put("content", settings.getHardClear().getPlaceholder());
            result.set(i, cleared);

            int afterChars = estimateMessageChars(cleared);
            totalChars += afterChars - beforeChars;
            ratio = (double) totalChars / charWindow;
        }*/

        log.debug("上下文修剪完成：{} 条消息，修剪了 {} 个工具结果", 
                result.size(), prunableToolIndexes.size());

        return result;
    }

    /**
     * 软修剪工具结果
     */
    private static Map<String, Object> softTrimToolResult(
            Map<String, Object> msg,
            ContextPruningSettings settings
    ) {
        Object content = msg.get("content");
        String contentStr = content instanceof String ? (String) content : "";

        if (contentStr.length() <= settings.getSoftTrim().getMaxChars()) {
            return null; // 不需要修剪
        }

        int headChars = settings.getSoftTrim().getHeadChars();
        int tailChars = settings.getSoftTrim().getTailChars();

        if (headChars + tailChars >= contentStr.length()) {
            return null; // 不需要修剪
        }

        String head = contentStr.substring(0, Math.min(headChars, contentStr.length()));
        String tail = contentStr.substring(Math.max(0, contentStr.length() - tailChars));

        String trimmed = head + "\n...\n" + tail;
        String note = String.format(
                "\n\n[Tool result trimmed: kept first %d chars and last %d chars of %d chars.]",
                headChars, tailChars, contentStr.length());

        Map<String, Object> result = new LinkedHashMap<>(msg);
        result.put("content", trimmed + note);
        return result;
    }

    /**
     * 找到助手消息的截止索引
     */
    private static int findAssistantCutoffIndex(List<Map<String, Object>> messages, int keepLastAssistants) {
        if (keepLastAssistants <= 0) {
            return messages.size();
        }

        int remaining = keepLastAssistants;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) continue;

            Object role = msg.get("role");
            if (!"assistant".equals(role)) continue;

            remaining--;
            if (remaining == 0) {
                return i;
            }
        }

        return -1; // 没有足够的助手消息
    }

    /**
     * 找到第一个用户消息的索引
     */
    private static int findFirstUserIndex(List<Map<String, Object>> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) continue;

            Object role = msg.get("role");
            if ("user".equals(role)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 找到最后一个用户消息的索引
     */
    private static int findLastUserIndex(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (msg != null && "user".equals(msg.get("role"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 估算上下文字符数
     */
    public static int estimateContextChars(List<Map<String, Object>> messages) {
        if (messages == null) return 0;
        return messages.stream()
                .mapToInt(ContextPruner::estimateMessageChars)
                .sum();
    }

    /**
     * 估算消息字符数
     */
    public static int estimateMessageChars(Map<String, Object> msg) {
        if (msg == null) return 0;

        Object role = msg.get("role");

        if ("user".equals(role)) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                return s.length();
            }
            // 多部分内容
            if (content instanceof List<?> list) {
                return estimateTextAndImageChars(list);
            }
            return 0;
        }

        if ("assistant".equals(role)) {
            int chars = 0;
            Object content = msg.get("content");
            if (content instanceof String s) {
                chars += s.length();
            } else if (content instanceof List<?> list) {
                for (Object block : list) {
                    if (block instanceof Map<?, ?> map) {
                        Object type = map.get("type");
                        if ("text".equals(type) && map.get("text") instanceof String t) {
                            chars += t.length();
                        }
                        if ("thinking".equals(type) && map.get("thinking") instanceof String t) {
                            chars += t.length();
                        }
                        if ("toolCall".equals(type)) {
                            try {
                                Object args = map.get("arguments");
                                if (args != null) {
                                    chars += args.toString().length();
                                }
                            } catch (Exception e) {
                                chars += 128;
                            }
                        }
                    }
                }
            }
            return chars;
        }

        if ("tool".equals(role)) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                return s.length();
            }
            if (content instanceof List<?> list) {
                return estimateTextAndImageChars(list);
            }
            return 0;
        }

        Object content = msg.get("content");
        if (content instanceof String s) {
            return s.length();
        }
        if (content instanceof List<?> list) {
            return estimateTextAndImageChars(list);
        }
        return 256;
    }

    /**
     * 估算文本和图片字符数
     */
    private static int estimateTextAndImageChars(List<?> content) {
        int chars = 0;
        for (Object block : content) {
            if (block instanceof Map<?, ?> map) {
                Object type = map.get("type");
                if ("text".equals(type) && map.get("text") instanceof String t) {
                    chars += t.length();
                }
                if ("image".equals(type) || "image_url".equals(type)) {
                    chars += ContextPruningSettings.IMAGE_CHAR_ESTIMATE;
                }
            }
        }
        return chars;
    }

    /**
     * 获取工具名称
     */
    private static String getToolName(Map<String, Object> msg) {
        Object name = msg.get("name");
        if (name instanceof String s && !s.isBlank()) {
            return s;
        }
        Object toolName = msg.get("tool_name");
        if (toolName instanceof String s && !s.isBlank()) {
            return s;
        }
        return "unknown";
    }
}