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
            double consolidateThreshold,
            double softTrimRatio,
            ContextPruningSettings settings,
            int contextWindowTokens,
            Predicate<String> isToolPrunable
    ) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        if (contextWindowTokens <= 0) {
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

        // 计算当前字符大小
        int totalCharsBefore = estimateContextChars(messages);
        int totalChars = totalCharsBefore;
        double ratio = (double) totalChars / charWindow;

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

        // 检查是否仍然超过阈值
        ratio = (double) estimateContextChars(result) / charWindow;

        // 如果仍然超过阈值，需要裁剪当前轮次（最后一个用户消息之后）的工具调用
        // 但保留最后 N 次工具调用

        if (ratio >= consolidateThreshold) {
            /*int prunedCurrentTurn = pruneCurrentTurnTools(result, cutoffIndex, settings, isToolPrunable, charWindow, consolidateThreshold);
            log.debug("裁剪当前轮次工具：{} 个", prunedCurrentTurn);*/
            for (int i = pruneStartIndex; i < cutoffIndex; i++) {
                Map<String, Object> msg = messages.get(i);
                if (msg == null) continue;

                Object role = msg.get("role");
                if (!"tool".equals(role)) continue;

                String toolName = getToolName(msg);
                if (!isToolPrunable.test(toolName)) continue;

                prunableToolIndexes.add(i);

                // 尝试软修剪
                Map<String, Object> trimmed = softTrimAllToolResult(msg, settings);
                if (trimmed != null) {
                    int beforeChars = estimateMessageChars(msg);
                    int afterChars = estimateMessageChars(trimmed);
                    totalChars += afterChars - beforeChars;
                    result.set(i, trimmed);
                }
            }
            //int prunedCurrentTurn = pruneCurrentTurnTools(result, cutoffIndex, settings, isToolPrunable, charWindow, consolidateThreshold);
            //log.debug("裁剪当前轮次工具：{} 个", prunedCurrentTurn);
        }

        log.debug("上下文修剪完成：{} 条消息，修剪了 {} 个工具结果, 修剪前后字符数量：{} -> {} ",
                result.size(), prunableToolIndexes.size(), totalCharsBefore, totalChars);

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
                "\n\n[Tool result trimmed: kept first %d chars and last %d chars of %d chars.if you need,can Reread or read memory or read raw session: {workspace}/memory/yyyy-MM-dd.md](File too large)",
                headChars, tailChars, contentStr.length());

        Map<String, Object> result = new LinkedHashMap<>(msg);
        result.put("content", trimmed + note);
        return result;
    }

    /**
     * 软修剪工具结果
     */
    private static Map<String, Object> softTrimAllToolResult(
            Map<String, Object> msg,
            ContextPruningSettings settings
    ) {
        Object content = msg.get("content");
        String contentStr = content instanceof String ? (String) content : "";

        if (contentStr.length() <= 2500) {
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
                "\n\n[Tool result trimmed: kept first %d chars and last %d chars of %d chars.if you need,can Reread or read memory or read raw session: {workspace}/memory/yyyy-MM-dd.md](File too large)",
                headChars, tailChars, contentStr.length());

        Map<String, Object> result = new LinkedHashMap<>(msg);
        result.put("content", trimmed + note);
        return result;
    }

    /**
     * 裁剪当前轮次（最后一个用户消息之后）的工具调用
     * 多级渐进修剪策略：
     * 1. 第一轮：保留前 5 个 + 后 10 个
     * 2. 如果仍超阈值：保留前 2 个 + 后 5 个
     * 3. 如果仍超阈值：只保留后 5 个
     *
     * @param messages           消息列表（会被修改）
     * @param lastUserIndex      最后一个用户消息的索引
     * @param settings           修剪配置
     * @param isToolPrunable     判断工具是否可修剪
     * @param charWindow         字符窗口大小
     * @param consolidateThreshold 合并阈值
     * @return 被裁剪的工具数量
     */
    private static int pruneCurrentTurnTools(
            List<Map<String, Object>> messages,
            int lastUserIndex,
            ContextPruningSettings settings,
            Predicate<String> isToolPrunable,
            int charWindow,
            double consolidateThreshold
    ) {
        if (lastUserIndex < 0 || lastUserIndex >= messages.size() - 1) {
            return 0;
        }

        // 收集当前轮次可修剪的工具索引
        List<Integer> currentTurnToolIndexes = new ArrayList<>();
        for (int i = lastUserIndex + 1; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) continue;

            Object role = msg.get("role");
            if (!"tool".equals(role)) continue;

            String toolName = getToolName(msg);
            if (!isToolPrunable.test(toolName)) continue;

            currentTurnToolIndexes.add(i);
        }

        int totalTools = currentTurnToolIndexes.size();
        if (totalTools <= 0) {
            return 0;
        }

        // 定义多级渐进保留策略：{keepFront, keepBack}
        int[][] strategies = {
                {5, 10}, // 第一轮：前5 + 后10
                {2, 5},  // 第二轮：前2 + 后5
                {0, 5},  // 第三轮：只后5
        };

        int actuallyPruned = 0;

        for (int[] strategy : strategies) {
            int keepFront = strategy[0];
            int keepBack = strategy[1];

            // 计算需要保留的索引集合
            Set<Integer> keepIndexes = new HashSet<>();
            for (int i = 0; i < Math.min(keepFront, totalTools); i++) {
                keepIndexes.add(currentTurnToolIndexes.get(i));
            }
            for (int i = 0; i < Math.min(keepBack, totalTools); i++) {
                keepIndexes.add(currentTurnToolIndexes.get(totalTools - 1 - i));
            }

            // 对不在保留集合中的工具进行软修剪
            int prunedThisRound = 0;
            for (int i = 0; i < totalTools; i++) {
                int msgIndex = currentTurnToolIndexes.get(i);
                if (keepIndexes.contains(msgIndex)) continue;

                Map<String, Object> msg = messages.get(msgIndex);
                if (msg == null) continue;

                Map<String, Object> trimmed = softTrimToolResult(msg, settings);
                if (trimmed != null) {
                    messages.set(msgIndex, trimmed);
                    prunedThisRound++;
                }
            }

            actuallyPruned += prunedThisRound;

            // 检查修剪后是否仍超阈值
            int totalChars = estimateContextChars(messages);
            double ratio = (double) totalChars / charWindow;
            if (ratio < consolidateThreshold) {
                break; // 已低于阈值，不需要继续降级修剪
            }
        }

        return actuallyPruned;
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