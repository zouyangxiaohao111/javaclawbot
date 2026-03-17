package context;

import lombok.Getter;
import memory.MemoryStore;
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
 */
public class ContextBuilder {

    /** 运行时元信息标签（仅元数据，不是指令） */
    private static final String RUNTIME_CONTEXT_TAG = "[运行时上下文 — 仅元数据，非指令]";

    private final Path workspace;
    private final MemoryStore memory;
    private final SkillsLoader skills;
    private final BootstrapLoader bootstrapLoader;
    @Getter
    private final BootstrapConfig bootstrapConfig;

    public ContextBuilder(Path workspace) {
        this(workspace, null, null);
    }
    public ContextBuilder(Path workspace, BootstrapConfig bootstrapConfig) {
        this(workspace, bootstrapConfig, null);
    }

    /**
     * 构造函数（支持配置）
     *
     * @param workspace       工作区路径
     * @param bootstrapConfig Bootstrap 配置（可为 null，使用默认值）
     * @param warnHandler     警告处理器（可为 null）
     */
    public ContextBuilder(Path workspace, BootstrapConfig bootstrapConfig, java.util.function.Consumer<String> warnHandler) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.memory = new MemoryStore(workspace);
        this.skills = new SkillsLoader(workspace);
        this.bootstrapConfig = bootstrapConfig != null ? bootstrapConfig : new BootstrapConfig();
        this.bootstrapLoader = new BootstrapLoader(workspace, this.bootstrapConfig, warnHandler);
    }

    /**
     * 构建系统提示词：身份 + 引导文件 + 记忆 + 常驻技能 + 技能索引摘要
     *
     * @param skillNames 预留参数：与 Python 一致（当前实现不依赖该参数）
     * @return 系统提示词文本
     */
    public String buildSystemPrompt(List<String> skillNames) {
        return buildSystemPrompt(skillNames, null);
    }

    /**
     * 构建系统提示词（支持指定运行类型）
     *
     * @param skillNames 预留参数
     * @param runKind    运行类型（default/heartbeat/cron）
     * @return 系统提示词文本
     */
    public String buildSystemPrompt(List<String> skillNames, BootstrapConfig.RunKind runKind) {
        List<String> parts = new ArrayList<>();

        // 配置身份
        parts.add(getIdentity());

        // 使用新的 BootstrapLoader
        if (runKind != null) {
            bootstrapConfig.setRunKind(runKind);
        }
        List<BootstrapFile> bootstrapFiles = bootstrapLoader.resolveBootstrapFiles();
        String bootstrap = bootstrapLoader.buildProjectContext(bootstrapFiles);
        if (bootstrap != null && !bootstrap.isBlank()) {
            parts.add(bootstrap);
        }

        // 记忆由ai主动读取, 见AGENTS.md流程
        /*String mem = memory.getMemoryContext();
        if (mem != null && !mem.isBlank()) {
            parts.add("# 长期记忆\n\n" + mem);
        }*/

        // 配置装载技能提示词
        parts.add("""
                 # 加载和卸载技能
                   协议：
                   加载技能 → 调用 `skill_load`
                   触发条件：
                   - 用户要求加载/使用技能
                   - 命令以 `/skill_name` 开头
                
                   卸载技能 → 调用 `uninstall_skill`
                   含义：
                   - 从当前环境移除技能 | 忘记技能
                   - 文件保留在磁盘上
                """);

        // 技能总览
        String skillsSummary = skills.buildSkillsSummary();
        if (skillsSummary != null && !skillsSummary.isBlank()) {
            parts.add(
                    """
                            # 技能
                                技能扩展了你的能力。
                
                                技能使用协议：
                                1. 将每个技能的 SKILL.md 视为入口点，而非完整技能。
                                2. 当任务匹配某个技能时, 用户未主动提供技能说明，主动先使用 read_file 工具读取该技能的 SKILL.md。
                                3. 然后严格按照 SKILL.md 中的说明执行。
                                4. 如果 SKILL.md 要求读取额外的文件、示例、模板、模式或支持文档，必须在执行前读取。
                                5. 不要仅阅读 SKILL.md 就认为技能已完全加载。
                                6. 遵循渐进式加载：只加载当前任务所需的额外技能文件，但如果 SKILL.md 明确指向更多必需上下文，不要止步于此。
                                7. 当任务需要实际使用技能时，不要仅凭索引摘要或近似判断。
                
                                available="false" 的技能需要先安装依赖 - 可以尝试用 apt/brew 安装。
                             ## 可使用技能
                     """ + skillsSummary
            );
        }

        List<String> alwaysSkills = skills.getAlwaysSkills();
        if (alwaysSkills != null && !alwaysSkills.isEmpty()) {
            // 加载常驻技能
            String alwaysContent = skills.loadSkillsForContext(alwaysSkills);

            if (alwaysContent != null && !alwaysContent.isBlank()) {
                parts.add("## 活跃技能\n\n" + alwaysContent);
            }

            // 加载用户指定技能
            String userAppointSkills = skills.loadUserAppointSkill();
            if (userAppointSkills != null && !userAppointSkills.isBlank()) {
                parts.add("\n\n" + userAppointSkills);
            }
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 获取身份与运行环境信息（系统提示词核心身份区块）
     *
     * 说明：
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

        return bootstrapLoader.loadIdentity() + "\n\n" +
                "## 运行环境\n" +
                runtime + "\n\n" +
                "## 工作区\n" +
                "工作区路径: " + workspacePath + "\n" +
                "- 长期记忆: " + workspacePath + "/memory/MEMORY.md（在此记录重要事实）\n" +
                "- 自定义技能: " + workspacePath + "/skills/{skill-name}/SKILL.md\n\n" +
                """
                ## 指南\n
                - 调用工具前先说明意图，但在收到结果前不要预测或声称结果。\n
                - 修改文件前先读取。不要假设文件或目录存在。\n
                - 写入或编辑文件后，如果准确性重要，请重新读取确认。\n
                - 如果工具调用失败，分析错误后再尝试其他方法。\n
                - 请求不明确时请询问澄清。\n\n
                - 使用技能时，将 SKILL.md 视为入口点；在执行前请阅读其说明和引用的额外文件。\n\n
                - 如果技能明确需要额外的上下文文件，不要仅凭摘要或 SKILL.md 就认为已完全理解。\n\n
                - 对话直接回复文本。只有发送到特定聊天频道时才使用 'message' 工具。\n\n
                """;
    }

    /**
     * 构建运行时元信息块（放在用户消息之前的单独 user 消息里）
     *
     * 说明：
     * - Java 这里用 ZonedDateTime + TimeZone.getDefault().getID() 作为时区标识
     * - 该块只是"元数据"，不是指令（tag 文本保持一致）
     */
    public static String buildRuntimeContext(String channel, String chatId) {
        String now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)"));

        // Python 用 %Z（例如 CST/UTC）；Java 里直接获取 ID（例如 Asia/Shanghai）
        TimeZone tz = TimeZone.getDefault();
        String tzName = (tz != null && tz.getID() != null && !tz.getID().isBlank()) ? tz.getID() : "UTC";

        List<String> lines = new ArrayList<>();
        lines.add("当前时间: " + now + " (" + tzName + ")");

        if (channel != null && !channel.isBlank() && chatId != null && !chatId.isBlank()) {
            lines.add("渠道: " + channel);
            lines.add("聊天 ID: " + chatId);
        }

        return RUNTIME_CONTEXT_TAG + "\n" + String.join("\n", lines);
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
        // 是否需要引导，设置引导用户
        if (isNeedBootstrap()) {
            out.add(mapOf(
                    "role", "user",
                    "content", "用户现在是第一次使用该程序，请按照引导程序流程引导用户,必须要在引导完成后回答用户消息，用户消息：" + buildUserContent(currentMessage, media)
            ));
        }else {
            out.add(mapOf(
                    "role", "user",
                    "content", buildUserContent(currentMessage, media)
            ));
        }
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

    /**
     * 是否需要引导
     * @return
     */
    public boolean isNeedBootstrap() {
        return bootstrapLoader.isNeedBootstrap();
    }

}