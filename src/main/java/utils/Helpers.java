package utils;

import providers.ToolCallRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * javaclawbot 工具方法
 *
 * 功能：
 * - 确保目录存在
 * - 获取数据目录与工作区目录
 * - 获取会话目录、技能目录
 * - 生成时间戳
 * - 截断字符串
 * - 将字符串转换为安全文件名
 * - 解析会话键（channel:chat_id）
 */
public final class Helpers {

    private Helpers() {}

    /**
     * 确保目录存在（不存在则递归创建）
     */
    public static Path ensureDir(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException("创建目录失败：" + path, e);
        }
        return path;
    }

    /**
     * 获取数据目录：~/.javaclawbot
     */
    public static Path getDataPath() {
        return ensureDir(Path.of(System.getProperty("user.home"), ".javaclawbot"));
    }

    private static final Pattern THINK_BLOCK = Pattern.compile("\\<think\\>.*?\\<\\/think\\>", Pattern.DOTALL);
    private static final Pattern THINKING_BLOCK = Pattern.compile("\\<thinking\\>.*?\\<\\/thinking\\>", Pattern.DOTALL);


    public static String stripThink(String text) {
        if (text == null || text.isBlank()) return null;
        // 删除整个 think 块（包括标签和内容）
        String cleaned = THINK_BLOCK.matcher(text).replaceAll("").trim();
        String thinkCleaned = THINKING_BLOCK.matcher(cleaned).replaceAll("").trim();
        return thinkCleaned.isBlank() ? null : thinkCleaned;
    }

    /**
     * 获取思考区块 -> <think></think>
     * @param text
     * @return
     */
    public static String obtainThinkBlock(String text) {
        if (text == null || text.isBlank()) return null;
        // 优先尝试 <think> 标签
        var thinkMatcher = THINK_BLOCK.matcher(text);
        if (thinkMatcher.find()) {
            return thinkMatcher.group().replaceAll("\\<think\\>|\\</think\\>", "").trim();
        }
        // 其次尝试 <thinking> 标签
        var thinkingMatcher = THINKING_BLOCK.matcher(text);
        if (thinkingMatcher.find()) {
            return thinkingMatcher.group().replaceAll("\\<thinking\\>|\\</thinking\\>", "").trim();
        }
        return null;
    }

    public static String toolHint(List<ToolCallRequest> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (var tc : toolCalls) {
            Object val = (tc.getArguments() != null && !tc.getArguments().isEmpty())
                    ? tc.getArguments().values().iterator().next()
                    : null;
            if (!(val instanceof String s)) {
                parts.add(tc.getName());
            } else {
                parts.add(s.length() > 40
                        ? tc.getName() + "(\"" + s.substring(0, 40) + "…\")"
                        : tc.getName() + "(\"" + s + "\")");
            }
        }
        return String.join(", ", parts);
    }

    /**
     * 获取工作区目录
     *
     * @param workspace 可选；为空则使用 ~/.javaclawbot/workspace
     */
    public static Path getWorkspacePath(String workspace) {
        Path path;
        if (workspace != null && !workspace.isBlank()) {
            path = expandUserHome(workspace);
        } else {
            path = Path.of(System.getProperty("user.home"), ".javaclawbot", "workspace");
        }
        return ensureDir(path);
    }

    /**
     * 获取会话存储目录：~/.javaclawbot/sessions
     */
    public static Path getSessionsPath() {
        return ensureDir(getDataPath().resolve("sessions"));
    }

    /**
     * 获取技能目录：{workspace}/skills
     *
     * @param workspace 可选；为空则使用默认工作区
     */
    public static Path getSkillsPath(Path workspace) {
        Path ws = (workspace != null) ? workspace : getWorkspacePath(null);
        return ensureDir(ws.resolve("skills"));
    }

    /**
     * 当前时间戳（ISO 格式）
     */
    public static String timestamp() {
        return LocalDateTime.now().toString();
    }

    /**
     * 截断字符串（超长则追加后缀）
     */
    public static String truncateString(String s, int maxLen, String suffix) {
        if (s == null) return "";
        if (suffix == null) suffix = "...";
        if (maxLen < 0) maxLen = 0;

        if (s.length() <= maxLen) return s;

        int keep = Math.max(0, maxLen - suffix.length());
        return s.substring(0, keep) + suffix;
    }

    /**
     * 转换为安全文件名（一比一实现）
     *
     * 规则：
     * - 将 <>:"/\\|?* 这些字符替换为下划线 _
     * - 再做 trim（去掉首尾空白）
     */
    public static String safeFilename(String name) {
        if (name == null) return "";
        String unsafe = "<>:\"/\\|?*";
        String out = name;
        for (int i = 0; i < unsafe.length(); i++) {
            char c = unsafe.charAt(i);
            out = out.replace(c, '_');
        }
        return out.trim();
    }

    /**
     * 解析会话键：channel:chat_id
     */
    public static String[] parseSessionKey(String key) {
        if (key == null) throw new IllegalArgumentException("会话键为空");
        String[] parts = key.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效会话键：" + key);
        }
        return parts;
    }

    /**
     * 将以 ~ 开头的路径展开为用户目录
     */
    private static Path expandUserHome(String path) {
        String p = path.trim();
        if (p.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(p.substring(2));
        }
        return Path.of(p);
    }


    public static String safeTruncate(String s, int maxCodePoints) {
        if (s == null || s.isEmpty()) return s;
        int total = s.codePointCount(0, s.length());
        if (total <= maxCodePoints) return s;

        int end = s.offsetByCodePoints(0, maxCodePoints);
        return s.substring(0, end);
    }

    // ── 消息构建 ──

    /** 将 ToolCallRequest 列表转为 API 格式的 tool_calls dict */
    public static List<Map<String, Object>> buildToolCallDicts(List<ToolCallRequest> toolCalls) {
        List<Map<String, Object>> dicts = new ArrayList<>();
        if (toolCalls == null) return dicts;
        for (var tc : toolCalls) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.getName());
            fn.put("arguments", GsonFactory.toJson(tc.getArguments()));
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", tc.getId());
            call.put("type", "function");
            call.put("function", fn);
            dicts.add(call);
        }
        return dicts;
    }

    /** 追加助手消息（role=assistant），支持 tool_calls / reasoning_content / thinking_blocks */
    public static List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages, String content,
            List<Map<String, Object>> toolCalls, String reasoningContent,
            List<Map<String, Object>> thinkingBlocks) {
        if (messages == null) messages = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content);
        if (toolCalls != null && !toolCalls.isEmpty())
            msg.put("tool_calls", toolCalls);
        if (reasoningContent != null)
            msg.put("reasoning_content", reasoningContent);
        if (thinkingBlocks != null && !thinkingBlocks.isEmpty())
            msg.put("thinking_blocks", thinkingBlocks);
        messages.add(msg);
        return new ArrayList<>(messages);
    }

    public static List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages, String content,
            List<Map<String, Object>> toolCalls, String reasoningContent) {
        return addAssistantMessage(messages, content, toolCalls, reasoningContent, null);
    }

    /** 追加工具结果消息（role=tool） */
    public static List<Map<String, Object>> addToolResult(
            List<Map<String, Object>> messages, String toolCallId,
            String toolName, String result) {
        if (messages == null) messages = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("name", toolName);
        msg.put("content", result);
        messages.add(msg);
        return new ArrayList<>(messages);
    }
}