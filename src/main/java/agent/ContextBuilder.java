package agent;

import skills.SkillsLoader;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 上下文构建器：负责组装系统提示词与消息列表，用于调用大模型。
 *
 * 功能点：
 * 1) 生成系统提示词：身份信息 + 工作区引导文件 + 记忆 + 技能
 * 2) 生成消息列表：system + 历史 + 运行时元信息 + 用户消息（可带图片）
 * 3) 追加工具调用结果、追加助手消息
 *
 * 注意：
 * - 本类的行为尽量与 Python 逻辑一致，尤其是：
 *   - system prompt 分段拼接与 "---" 分隔
 *   - runtime context 的 tag 文案与字段
 *   - media 图片以 base64 data URL 注入（只处理 image/*）
 *   - assistant message 支持 tool_calls / reasoning_content / thinking_blocks
 */
public class ContextBuilder {

    /** 启动引导文件（从工作区读取） */
    public static final List<String> BOOTSTRAP_FILES = List.of(
            "AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md", "IDENTITY.md", "HEARTBEAT.md", "BOOTSTRAP.md"
    );

    /** 运行时元信息标签（仅元数据，不是指令） */
    private static final String RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]";

    private final Path workspace;
    private final MemoryStore memory;
    private final SkillsLoader skills;

    public ContextBuilder(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.memory = new MemoryStore(workspace);
        this.skills = new SkillsLoader(workspace);
    }

    /**
     * 构建系统提示词：身份 + 引导文件 + 记忆 + 常驻技能 + 技能索引摘要
     *
     * @param skillNames 预留参数：与 Python 一致（当前实现不依赖该参数）
     * @return 系统提示词文本
     */
    public String buildSystemPrompt(List<String> skillNames) {
        List<String> parts = new ArrayList<>();
        parts.add(getIdentity());

        String bootstrap = loadBootstrapFiles();
        if (bootstrap != null && !bootstrap.isBlank()) {
            parts.add(bootstrap);
        }

        String mem = memory.getMemoryContext();
        if (mem != null && !mem.isBlank()) {
            parts.add("# Memory\n\n" + mem);
        }

        // 配置装载技能提示词
        parts.add("""
                 # Load and uninstall(remove) Skills
                   protocol:
                   Load skill → call `skill_load`
                   Trigger:
                   - user asks to load/use a skill
                   - command starts with `/skill_name`
                
                   Uninstall(remove) skill → call `uninstall_skill`
                   Meaning:
                   - remove skill from current environment | forget skill
                   - files remain on disk
                """);

        List<String> alwaysSkills = skills.getAlwaysSkills();
        if (alwaysSkills != null && !alwaysSkills.isEmpty()) {
            String alwaysContent = skills.loadSkillsForContext(alwaysSkills);
            if (alwaysContent != null && !alwaysContent.isBlank()) {
                parts.add("# Active Skills\n\n" + alwaysContent);
            }
        }

        String skillsSummary = skills.buildSkillsSummary();
        if (skillsSummary != null && !skillsSummary.isBlank()) {
            parts.add(
                    """
                            # Skills
                                The following skills extend your capabilities.
                
                                Skill usage protocol:
                                1. Treat each skill's SKILL.md as an entrypoint, not as the complete skill.
                                2. When a task matches a skill, first read that skill's SKILL.md using the read_file tool.
                                3. Then follow the instructions inside SKILL.md exactly.
                                4. If SKILL.md tells you to read additional files, examples, templates, schemas, or supporting documents, you MUST read them before proceeding.
                                5. Do not assume the skill is fully loaded after reading only SKILL.md.
                                6. Respect gradual disclosure: only load the additional skill files that are required for the current task, but do not stop at SKILL.md if it explicitly points to more required context.
                                7. Do not summarize or approximate a skill from the index alone when the task requires actually using it.
                
                                Skills with available=\\"false\\" need dependencies installed first - you can try installing them with apt/brew.
                                           """ +
                            skillsSummary
            );
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 获取身份与运行环境信息（系统提示词核心身份区块）
     *
     * 说明：
     * - Python 里 runtime 拼为："{system} {machine}, Python x.y.z"
     * - Java 里没有 Python 版本，这里用 Java 运行时替代
     * - 历史日志在 Python 里强调每条以 [YYYY-MM-DD HH:MM] 开头，这里补齐相同文字
     */
    private String getIdentity() {
        // Python：workspace.expanduser().resolve()
        String workspacePath = workspace.toAbsolutePath().normalize().toString();

        String os = System.getProperty("os.name", "Unknown");
        String arch = System.getProperty("os.arch", "Unknown");
        String javaVersion = System.getProperty("java.version", "Unknown");

        // Python：macOS 特判 Darwin；这里按 Java 的 os.name 简单特判
        String system = os;
        if (system.toLowerCase(Locale.ROOT).contains("mac") || system.toLowerCase(Locale.ROOT).contains("darwin")) {
            system = "macOS";
        }
        String runtime = system + " " + arch + ", Java " + javaVersion;

        return "# nanobot 🐈\n\n" +
                "You are nanobot, a helpful AI assistant.\n\n" +
                "## Runtime\n" +
                runtime + "\n\n" +
                "## Workspace\n" +
                "Your workspace is at: " + workspacePath + "\n" +
                "- Long-term memory: " + workspacePath + "/memory/MEMORY.md (write important facts here)\n" +
                "- History log: " + workspacePath + "/memory/HISTORY.md (grep-searchable). Each entry starts with [YYYY-MM-DD HH:MM].\n" +
                "- Custom skills: " + workspacePath + "/skills/{skill-name}/SKILL.md\n\n" +
                """
                ## nanobot Guidelines\n
                - State intent before tool calls, but NEVER predict or claim results before receiving them.\n
                - Before modifying a file, read it first. Do not assume files or directories exist.\n
                - After writing or editing a file, re-read it if accuracy matters.\n
                - If a tool call fails, analyze the error before retrying with a different approach.\n
                - Ask for clarification when the request is ambiguous.\n\n
                - When using a skill, treat SKILL.md as the entrypoint only; follow its instructions and read any additional referenced files before acting.\n\n
                - Do not assume a skill is fully understood from its summary or SKILL.md alone if it explicitly requires additional context files.\\n\\n
                "Reply directly with text for conversations. Only use the 'message' tool to send to a specific chat channel.
                """;
    }

    /**
     * 构建运行时元信息块（放在用户消息之前的单独 user 消息里）
     *
     * 说明：
     * - Python 使用 datetime.now() + time.strftime("%Z") 获取时区缩写
     * - Java 这里用 ZonedDateTime + TimeZone.getDefault().getID() 作为时区标识
     * - 该块只是“元数据”，不是指令（tag 文本保持一致）
     */
    public static String buildRuntimeContext(String channel, String chatId) {
        String now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));

        // Python 用 %Z（例如 CST/UTC）；Java 里直接获取 ID（例如 Asia/Shanghai）
        TimeZone tz = TimeZone.getDefault();
        String tzName = (tz != null && tz.getID() != null && !tz.getID().isBlank()) ? tz.getID() : "UTC";

        List<String> lines = new ArrayList<>();
        lines.add("Current Time: " + now + " (" + tzName + ")");

        if (channel != null && !channel.isBlank() && chatId != null && !chatId.isBlank()) {
            lines.add("Channel: " + channel);
            lines.add("Chat ID: " + chatId);
        }

        return RUNTIME_CONTEXT_TAG + "\n" + String.join("\n", lines);
    }

    /**
     * 从工作区读取引导文件，按顺序拼接
     *
     * 注意：
     * - Python：只判断 exists，然后直接 read_text；读取失败会抛异常（但通常文件可读）
     * - Java：为了稳健，读取异常时跳过，不中断（整体效果更容错）
     */
    private String loadBootstrapFiles() {
        List<String> parts = new ArrayList<>();
        boolean hasSoulFile = false;

        for (String filename : BOOTSTRAP_FILES) {
            Path filePath = workspace.resolve(filename);

            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    parts.add("## " + filename + "\n\n" + content);
                    if (filename.equals("SOUL.md")) {
                        hasSoulFile = true;
                    }
                } catch (IOException ignored) {
                    // 读取失败则跳过
                }
            }
        }

        // 对齐 OpenClaw：如果 SOUL.md 存在，添加特殊提示
        if (hasSoulFile) {
            parts.add(0, "If SOUL.md is present, embody its persona and tone. Avoid stiff, generic replies; follow its guidance unless higher-priority instructions override it.");
        }

        return parts.isEmpty() ? "" : String.join("\n\n", parts);
    }

    /**
     * 构建本次调用的大模型消息列表：
     * system + 历史 + 运行时元信息 + 用户输入（可带图片）
     *
     * @param history        历史消息（OpenAI 兼容结构：role/content/等）
     * @param currentMessage 当前用户文本
     * @param skillNames     预留参数：与 Python 一致
     * @param media          本地图片路径列表（仅处理 image/*）
     * @param channel        渠道名
     * @param chatId         会话标识
     * @return 消息列表（每个元素是 Map，对齐 OpenAI 消息结构）
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> skillNames,
            List<String> media,
            String channel,
            String chatId
    ) {
        List<Map<String, Object>> out = new ArrayList<>();

        // system
        out.add(mapOf(
                "role", "system",
                "content", buildSystemPrompt(skillNames)
        ));

        // history（Python 是 *history 直接展开）
        if (history != null && !history.isEmpty()) {
            out.addAll(history);
        }

        // runtime context（作为 user 消息插入）
        out.add(mapOf(
                "role", "user",
                "content", buildRuntimeContext(channel, chatId)
        ));

        // 当前用户内容（文本 + 可选图片）
        out.add(mapOf(
                "role", "user",
                "content", buildUserContent(currentMessage, media)
        ));

        return out;
    }

    /**
     * 构建用户消息内容：若有图片则以 base64 data URL 方式注入，并把文本放最后
     *
     * 返回值：
     * - 无图片：String
     * - 有图片：List<Map>，每个元素为 {"type": "...", ...}
     */
    public Object buildUserContent(String text, List<String> media) {
        if (media == null || media.isEmpty()) {
            return text;
        }

        List<Map<String, Object>> images = new ArrayList<>();

        for (String pathStr : media) {
            if (pathStr == null || pathStr.isBlank()) continue;

            Path p = Path.of(pathStr);

            // Python：mimetypes.guess_type(path)，这里用 probe + guessFromName 双兜底
            String mime = guessMimeType(p);

            if (!Files.isRegularFile(p) || mime == null || !mime.startsWith("image/")) {
                continue;
            }

            try {
                byte[] bytes = Files.readAllBytes(p);
                String b64 = Base64.getEncoder().encodeToString(bytes);
                String url = "data:" + mime + ";base64," + b64;

                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", url);

                Map<String, Object> imageItem = new LinkedHashMap<>();
                imageItem.put("type", "image_url");
                imageItem.put("image_url", imageUrl);

                images.add(imageItem);
            } catch (IOException ignored) {
                // 读取失败跳过
            }
        }

        if (images.isEmpty()) {
            return text;
        }

        Map<String, Object> textItem = new LinkedHashMap<>();
        textItem.put("type", "text");
        textItem.put("text", text);

        List<Map<String, Object>> mixed = new ArrayList<>(images);
        mixed.add(textItem);
        return mixed;
    }

    /**
     * 追加工具调用结果消息（role=tool）
     */
    public List<Map<String, Object>> addToolResult(
            List<Map<String, Object>> messages,
            String toolCallId,
            String toolName,
            String result
    ) {
        if (messages == null) {
            messages = new ArrayList<>();
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId);
        msg.put("name", toolName);
        msg.put("content", result);

        messages.add(msg);
        return new ArrayList<>(messages);
    }

    /**
     * 追加助手消息（role=assistant），支持携带 tool_calls / reasoning_content / thinking_blocks
     */
    public List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages,
            String content,
            List<Map<String, Object>> toolCalls,
            String reasoningContent,
            List<Map<String, Object>> thinkingBlocks
    ) {
        if (messages == null) {
            messages = new ArrayList<>();
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content);

        if (toolCalls != null && !toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls);
        }

        // Python：reasoning_content is not None 才放进去（空字符串也应放）
        if (reasoningContent != null) {
            msg.put("reasoning_content", reasoningContent);
        }

        if (thinkingBlocks != null && !thinkingBlocks.isEmpty()) {
            msg.put("thinking_blocks", thinkingBlocks);
        }

        messages.add(msg);
        return new ArrayList<>(messages);
    }

    /**
     * 兼容旧签名：不传 thinking_blocks
     */
    public List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages,
            String content,
            List<Map<String, Object>> toolCalls,
            String reasoningContent
    ) {
        return addAssistantMessage(messages, content, toolCalls, reasoningContent, null);
    }

    // ==========================
    // 内部辅助方法
    // ==========================

    /**
     * 猜测 MIME 类型（用于判断是否为图片）
     */
    private static String guessMimeType(Path p) {
        try {
            String probed = Files.probeContentType(p);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (IOException ignored) {
        }

        // 兜底：用文件名猜测
        String name = p.getFileName() != null ? p.getFileName().toString() : "";
        String guess = URLConnection.guessContentTypeFromName(name);
        if (guess != null && !guess.isBlank()) {
            return guess;
        }

        // 再兜底：轻量扩展名映射
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return null;
    }

    /**
     * 构造 Map（保持插入顺序，便于输出稳定）
     */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}