package context;

import agent.command.CommandQueueManager;
import agent.command.ContentBlock;
import agent.command.SkillCommand;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import config.Config;
import config.ConfigIO;
import config.provider.model.ModelConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import memory.MemoryStore;
import providers.cli.ProjectRegistry;
import skills.SkillsLoader;
import utils.GsonFactory;
import utils.Helpers;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import static constant.Constant.MAX_PROJECT_INSTRUCTION_LINES;

/**
 * 上下文构建器：负责组装系统提示词与消息列表，用于调用大模型。
 *
 * 功能点：
 * 1) 生成系统提示词：身份信息 + 工作区引导文件 + 记忆 + 技能
 * 2) 生成消息列表：system + 历史 + 运行时元信息 + 用户消息（可带图片）
 * 3) 追加工具调用结果、追加助手消息
 */
@Slf4j
public class ContextBuilder {

    /** 运行时元信息标签（仅元数据，不是指令） */
    private static final String RUNTIME_CONTEXT_TAG = "[运行时上下文 — 仅元数据，非指令]";

    private final Path workspace;
    private final MemoryStore memory;
    private final SkillsLoader skills;
    private final BootstrapLoader bootstrapLoader;
    private final CommandQueueManager commandQueueManager;
    private final Supplier<ProjectRegistry> projectRegistrySupplier;
    @Getter
    private final BootstrapConfig bootstrapConfig;

    /** 当前模型类型，决定媒体附件以 base64 嵌入还是路径传递 */
    private ModelConfig.ModelType currentModelType = ModelConfig.ModelType.CHAT;

    public void setCurrentModelType(ModelConfig.ModelType type) {
        this.currentModelType = type != null ? type : ModelConfig.ModelType.CHAT;
    }

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
        this(workspace, bootstrapConfig, warnHandler, null);
    }

    /** 带外部 ProjectRegistry supplier 的构造器（支持 per-session 动态解析） */
    public ContextBuilder(Path workspace, BootstrapConfig bootstrapConfig, java.util.function.Consumer<String> warnHandler, Supplier<ProjectRegistry> projectRegistrySupplier) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.memory = new MemoryStore(workspace);
        this.skills = new SkillsLoader(workspace);
        this.bootstrapConfig = bootstrapConfig != null ? bootstrapConfig : new BootstrapConfig();
        this.commandQueueManager = new CommandQueueManager(this.skills);
        if (projectRegistrySupplier != null) {
            this.projectRegistrySupplier = projectRegistrySupplier;
        } else {
            ProjectRegistry defaultPr = new ProjectRegistry(Helpers.getDataPath()
                    .resolve("projects")
                    .resolve("projects.json"));
            defaultPr.load();
            String cwd = System.getProperty("user.dir");
            if (cwd != null && !cwd.isBlank() && defaultPr.getMainProject() == null) {
                defaultPr.bind("main", cwd, true);
            }
            this.projectRegistrySupplier = () -> defaultPr;
        }

        this.bootstrapLoader = new BootstrapLoader(workspace, this.bootstrapConfig, warnHandler, this.projectRegistrySupplier);
    }


    public boolean isDevelopment() {
        Config config = ConfigIO.loadConfig(ConfigIO.getConfigPath(workspace));
        return config.getAgents().getDefaults().isDevelopment();
    }

    /**
     * 构建系统提示词（支持指定运行类型）
     *
     * @return 系统提示词文本
     */
    public String buildMemorySystemPrompt(String prompt) {
        List<String> parts = new ArrayList<>();

        // 配置工作流程
        //String agents = bootstrapLoader.loadAgents();
        parts.add(prompt);

        // 构建记忆
        String context = buildMemoryContext();
        if (context != null && !context.isBlank()) {
            parts.add(context);
        }

        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 构建系统提示词（支持指定运行类型）
     *
     * @return 系统提示词文本
     */
    public String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();

        // 配置工作流程
        String agents = bootstrapLoader.loadAgents();
        parts.add(agents);

        // 构建记忆
        String context = buildMemoryContext();

        if (context != null && !context.isBlank()) {
            parts.add(context);
        }

        // 可用技能说明
        parts.add(skills.buildSkillsSimpleSummary());

        // 配置身份
        parts.add(bootstrapLoader.loadIdentity());
        // 配置灵魂
        parts.add(bootstrapLoader.loadSoul());
        // 加载用户说明
        parts.add(bootstrapLoader.loadUser());
        // 加载用户说明
        parts.add(bootstrapLoader.loadTool());

        parts.add(bootstrapLoader.loadPlugin());
        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 处理 /project 前缀命令（已废弃，使用 /bind --main 代替）
     *
     * @param userMsg 用户消息
     * @return Object[] {String处理后消息, Boolean是否处理了project命令}
     * @deprecated 使用 /bind --main 代替 /project
     */
    @Deprecated
    public Object[] handleProjectPrefix(String userMsg) {
        Object[] results = new Object[2];

        if (userMsg == null || userMsg.isBlank()) {
            results[0] = userMsg;
            results[1] = false;
            return results;
        }

        // 提示用户使用新命令
        if (userMsg.startsWith("/project ")) {
            results[0] = "⚠️ /project 命令已废弃。\n\n请使用:\n  /bind --main <路径>   设置主项目\n  /bind main=<路径> --main  绑定并设为主项目\n\n查看 /projects 列出所有项目";
            results[1] = true;
            return results;
        }

        if (userMsg.equals("/project") || userMsg.equals("/project clear")) {
            results[0] = "⚠️ /project 命令已废弃。\n\n请使用:\n  /unbind main  解绑主项目\n  /projects    列出所有项目";
            results[1] = true;
            return results;
        }

        results[0] = userMsg;
        results[1] = false;
        return results;
    }

    /**
     * 构建项目上下文（仅开发者模式）
     * 从 ProjectRegistry 获取 main 项目，读取其 CODE-AGENT.md / CLAUDE.md
     */
    public String buildProjectContext() {
        // 检查是否是开发者模式
        if (!isDevelopment()) {
            return "";
        }

        // 从 ProjectRegistry 获取主项目
        ProjectRegistry.ProjectInfo mainProject = projectRegistrySupplier.get().getMainProject();
        if (mainProject == null) {
            return "";
        }

        Path projectPath = Path.of(mainProject.getPath());
        if (!Files.isDirectory(projectPath)) {
            log.warn("Main project path does not exist: {}", projectPath);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<project-context>\n");
        sb.append("当前主项目: ").append(projectPath).append("\n");

        // 读取项目指令文件
        String content = readProjectInstruction(projectPath);
        if (content != null && !content.isBlank()) {
            sb.append("\n# 项目记忆文件（前 ").append(MAX_PROJECT_INSTRUCTION_LINES).append(" 行）\n");
            sb.append(content);
            sb.append("\n（超过 ").append(MAX_PROJECT_INSTRUCTION_LINES).append(" 行已截断，完整内容请使用 read_file 工具）\n");
        }

        sb.append("</project-context>");
        return sb.toString();
    }

    /**
     * 读取项目指令文件（CODE-AGENT.md 或 CLAUDE.md）前 N 行
     */
    private String readProjectInstruction(Path projectPath) {
        if (projectPath == null) return null;

        // 优先读取 CODE-AGENT.md
        Path codeAgent = projectPath.resolve("CODE-AGENT.md");
        if (Files.isRegularFile(codeAgent)) {
            return readFileMaxLines(codeAgent);
        }

        // 其次读取 CLAUDE.md
        Path claude = projectPath.resolve("CLAUDE.md");
        if (Files.isRegularFile(claude)) {
            return readFileMaxLines(claude);
        }

        return null;
    }

    /**
     * 读取文件前 N 行
     */
    private String readFileMaxLines(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int limit = Math.min(lines.size(), MAX_PROJECT_INSTRUCTION_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to read project instruction file: {} - {}", file, e.getMessage());
            return null;
        }
    }


    /**
     * 通过用户消息的前缀加载技能
     * @param userMsg
     * @return Object[] (String, Boolean)
     */
    public Object[] loadSkillByPrefix(String userMsg) {
        Object[] results = new Object[2];

        String activeSkillPrefix = """
                **重要提示**: 
                - 用户已加载的技能(指用户在常驻技能之外额外加载的技能)
                - 已加载的技能在付给你与用户的对话记录中已列出技能说明,如果对话中详细说明不存在,请使用工具`skill`加载该技能,包含在标签
                
                用户已指定使用的技能列表: %s\n\n
                """;

        // 常驻和已加载技能判断
        String skillName = commandQueueManager.isLoadedByUserMsg(userMsg);
        Set<String> userLoadedSkills = commandQueueManager.getUserLoadedSkills();
        String formatted = activeSkillPrefix.formatted(userLoadedSkills);
        if (StrUtil.isNotBlank(skillName)) {
            userMsg = userMsg.replace("/" + skillName, "").trim();
            // 加载已经加载的技能说明
            userMsg = formatted + "\n\nARGUMENTS: " + userMsg;

            results[0] = userMsg;
            results[1] = false;
            return results;
        }

        // 如果以上条件都不满足则查询所有技能
        List<String> skillNames = skills.listSkillNames(true);
        for (String skill : skillNames) {
            if (userMsg.startsWith("/" + skill)) {
                userMsg = userMsg.replace("/" + skill, "").trim();

                SkillCommand skillCommand = new SkillCommand(skill, skill, skills);
                commandQueueManager.addSkillCommand(skillCommand);
                List<ContentBlock> list = commandQueueManager.triggerCommandOutput();
                StringBuilder sb = new StringBuilder();
                for (ContentBlock block : list) {
                    sb.append(block.getText()).append("\n");
                }
                userMsg =  formatted + sb + "\n\nARGUMENTS: " + userMsg;
                results[0] = userMsg;
                results[1] = true;
                return results;
            }
        }

        results[0] = userMsg;
        results[1] = false;
        return results;
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
        lines.add("\n\n<system-reminder>");
        lines.add(RUNTIME_CONTEXT_TAG);
        lines.add("当前时间: " + now + " (" + tzName + ")");

        if (channel != null && !channel.isBlank() && chatId != null && !chatId.isBlank()) {
            lines.add("渠道: " + channel);
            lines.add("聊天 ID: " + chatId);
        }
        lines.add("</system-reminder>");

        return String.join("\n", lines);
    }

    /**
     * 参考Claude code 上下文构建方法
     * @return
     */
    public String buildMemoryContext() {
        String mem = memory.readLongTermShort();
        StringBuilder sb = new StringBuilder();

        String projectCtx = "";
        if (isDevelopment()) {
            // 构建项目上下文（仅开发者模式）
            projectCtx = buildProjectContext();
        }
        sb.append("""
                <system-reminder>
                在回答用户问题时，可以使用以下全局上下文：
                # currentDate
                 今天的日期是 %s。
                # 部分MEMORY.md内容 >200 行会被截断，阅读更多请使用 `read_file` 和 `memory_search`工具获取更详细的上下文
                 %s
                 
                 %s
                 
                 
                 <important-notice>**重要提示：这个上下文可能与你的任务相关，也可能无关。除非这与你的任务高度相关，否则不应回复此语境。**
                 
                 **已学习的经验(memory_search 无法搜索到 必须使用read_file 工具才能阅读)**,记忆中只会存在自我进化索引，具体文件请查看：
                 1. **语义记忆**（`{工作空间}/memory/semantic/patterns.json`）
                 2. **情景记忆**（`{工作空间}/memory/episodic/yyyy/yyyy-MM-dd-{name}.json`）
                 # 对应记忆架构和json格式说明：
                 ## 多记忆架构
                 使用write_file 工具存储对应记忆文件
                 ### 1. 语义记忆（`{工作空间}/memory/semantic/patterns.json`）
                 存储**可跨场景复用的抽象模式与规则**：
                
                 ```json
                 {
                   "patterns": {
                     "pattern_id": {
                       "id": "pat-2025-01-11-001",
                       "name": "模式名称",
                       "source": "user_feedback|implementation_review|retrospective",
                       "confidence": 0.95,
                       "applications": 5,
                       "created": "2025-01-11",
                       "category": "prd_structure|react_patterns|async_patterns|...",
                       "pattern": "一句话总结",
                       "problem": "这个模式解决什么问题？",
                       "solution": { },
                       "quality_rules": [ ],
                       "target_skills": [ ]
                     }
                   }
                 }
                 ```
                 ### 2. 情景记忆（`{工作空间}/memory/episodic/`）
                 存储**具体经历以及实际发生了什么**：
                
                 ```text
                 memory/episodic/
                 ├── 2025/
                 │   ├── 2025-01-11-prd-creation.json
                 │   ├── 2025-01-11-debug-session.json
                 │   └── 2025-01-12-refactoring.json
                 ```
                
                 ```json
                 {
                   "id": "ep-2025-01-11-001",
                   "timestamp": "2025-01-11T10:30:00Z",
                   "skill": "debugger",
                   "situation": "用户反馈表单提交后数据没有刷新",
                   "root_cause": "onRefresh 回调中传入了空函数",
                   "solution": "在回调中实现真实的刷新逻辑",
                   "lesson": "始终验证回调函数不是空实现",
                   "related_pattern": "callback_verification",
                   "user_feedback": {
                     "rating": 8,
                     "comments": "这正是问题所在"
                   }
                 }
                 ```
                 </important-notice>
                 </system-reminder>
                """.formatted(LocalDate.now(), mem, projectCtx));
        return sb.toString();
    }




    /**
     * 构建本次调用的大模型消息列表：
     * system + 历史 + 运行时元信息 + 用户输入（可带图片）
     *
     * @param history        历史消息（OpenAI 兼容结构：role/content/等）
     * @param currentMessage 当前用户文本
     * @param media          本地文件路径列表（根据模型类型决定嵌入或路径传递）
     * @param channel        渠道名
     * @param chatId         会话标识
     * @return 消息列表（每个元素是 Map，对齐 OpenAI 消息结构）
     */
    public List<Map<String, Object>> buildContextCompressMessages(List<Map<String, Object>> history,
                                                         String currentMessage,
                                                         List<String> media,
                                                         String channel,
                                                         String chatId) {
        List<Map<String, Object>> out = new ArrayList<>();

        // 构建系统提示词
        //String systemPrompt = buildSystemPrompt();
        String systemPrompt = currentMessage;

        // 运行环境
        String runtimeContext = buildRuntimeContext(channel, chatId);
        out.add(mapOf(
                "role", "system",
                "content", systemPrompt  + runtimeContext
        ));

        List<Map<String, Object>> userBlocks = new ArrayList<>();

        // 构建第2条用户消息, 该消息为常驻技能
        userBlocks.add(Map.of("type", "text", "text", loadResidentSkill()));

        // 构建第4条用户消息, 该消息为本地命令描述
        userBlocks.add(Map.of("type", "text", "text", buildLocalCommandDesc()));
        out.add(mapOf(
                "role", "user",
                "content", userBlocks
        ));

        // 添加历史（过滤掉 system 角色消息）
        if (CollUtil.isNotEmpty(history)) {
            for (Map<String, Object> msg : history) {
                if (msg == null) continue;
                if ("system".equals(msg.get("role"))) continue;
                out.add(msg);
            }
        }

        // 当前用户内容（文本 + 可选图片）
        // 是否需要引导，设置引导用户
        out.add(mapOf(
                "role", "user",
                "content", buildUserContent("开始执行上下文压缩", media)
        ));
        return out;
    }


    /**
     * 构建本次调用的大模型消息列表：
     * system + 历史 + 运行时元信息 + 用户输入（可带图片）
     *
     * @param history        历史消息（OpenAI 兼容结构：role/content/等）
     * @param currentMessage 当前用户文本
     * @param media          本地文件路径列表（根据模型类型决定嵌入或路径传递）
     * @param channel        渠道名
     * @param chatId         会话标识
     * @return 消息列表（每个元素是 Map，对齐 OpenAI 消息结构）
     */
    public List<Map<String, Object>> buildMemoryMessages(List<Map<String, Object>> history,
                                                         String currentMessage,
                                                         List<String> media,
                                                         String channel,
                                                         String chatId) {
        List<Map<String, Object>> out = new ArrayList<>();

        // 构建系统提示词
        String systemPrompt = buildMemorySystemPrompt(currentMessage);

        // 运行环境
        String runtimeContext = buildRuntimeContext(channel, chatId);
        out.add(mapOf(
                "role", "system",
                "content", systemPrompt  + runtimeContext
        ));

        List<Map<String, Object>> userBlocks = new ArrayList<>();

        // 构建第2条用户消息, 该消息为常驻技能
        String loaded = loadResidentSkill();
        if (StrUtil.isNotBlank(loaded)) {
            userBlocks.add(Map.of("type", "text", "text", loaded));
        }

        // 该消息为本地命令描述
        userBlocks.add(Map.of("type", "text", "text", buildLocalCommandDesc()));
        out.add(mapOf(
                "role", "user",
                "content", userBlocks
        ));

        // 添加历史（过滤掉 system 角色消息）
        if (CollUtil.isNotEmpty(history)) {
            for (Map<String, Object> msg : history) {
                if (msg == null) continue;
                if ("system".equals(msg.get("role"))) continue;
                out.add(msg);
            }
        }
        out.add(mapOf(
                "role", "user",
                "content", "开始读取当前活跃会话记录,依次为由进行 记忆和自我进化, 完成后将报告发送给用户"
        ));
        return out;
    }

    /**
     * 构建本次调用的大模型消息列表：
     * system + 历史 + 运行时元信息 + 用户输入（可带图片）
     *
     * @param history        历史消息（OpenAI 兼容结构：role/content/等）
     * @param currentMessage 当前用户文本
     * @param media          本地文件路径列表（根据模型类型决定嵌入或路径传递）
     * @param channel        渠道名
     * @param chatId         会话标识
     * @return 消息列表（每个元素是 Map，对齐 OpenAI 消息结构）
     */
    public List<Map<String, Object>> buildMessages(
            List<Map<String, Object>> history,
            String currentMessage,
            List<String> media,
            String channel,
            String chatId
    ) {
        List<Map<String, Object>> out = new ArrayList<>();

        // 构建系统提示词
        String systemPrompt = buildSystemPrompt();

        // 运行环境
        String runtimeContext = buildRuntimeContext(channel, chatId);
        out.add(mapOf(
                "role", "system",
                "content", systemPrompt  + runtimeContext
        ));

        List<Map<String, Object>> userBlocks = new ArrayList<>();

        // 构建第2条用户消息, 该消息为常驻技能
        userBlocks.add(Map.of("type", "text", "text", loadResidentSkill()));

        // 构建第4条用户消息, 该消息为本地命令描述
        userBlocks.add(Map.of("type", "text", "text", buildLocalCommandDesc()));
        out.add(mapOf(
                "role", "user",
                "content", userBlocks
        ));

        // 添加历史（过滤掉 system 角色消息 — 仅由 ContextBuilder 添加系统提示词）
        if (CollUtil.isNotEmpty(history)) {
            for (Map<String, Object> msg : history) {
                if (msg == null) continue;
                // Skip system messages from session — ContextBuilder adds its own system prompt
                if ("system".equals(msg.get("role"))) continue;
                out.add(msg);
            }
        }

        // 通过用户指定前缀加载技能
        Object[] objects = loadSkillByPrefix(currentMessage);
        currentMessage = (String) objects[0];
        boolean isLoadedSkillByMsg = (boolean) objects[1];

        // 如果没有通过用户指定前缀加载技能(或者已经加载过了)
        if (!isLoadedSkillByMsg) {
            StringBuilder sb = new StringBuilder();
            List<ContentBlock> contentBlocks = commandQueueManager.triggerCommandOutput();
            if (CollUtil.isNotEmpty(contentBlocks)) {
                for (ContentBlock contentBlock : contentBlocks) {
                    sb.append(contentBlock.getText()).append("\n");
                }
                currentMessage = sb + currentMessage;
            }
        }

        // 当前用户内容（文本 + 可选图片）
        // 是否需要引导，设置引导用户
        if (isNeedBootstrap() && !isDevelopment()) {
            out.add(mapOf(
                    "role", "user",
                    "content", "用户现在是可能是第一次使用该程序，请按照引导程序流程引导用户,必须要在引导完成后回答用户消息，用户消息：\n\n" + buildUserContent(currentMessage, media)
            ));
        }else {
            out.add(mapOf(
                    "role", "user",
                    "content", buildUserContent(currentMessage, media)
            ));
        }

        /*out.add(mapOf(
                "role", "user",
                "content", buildUserContent(currentMessage, media)
        ));*/
        return out;
    }

    private Object loadTool() {
        return null;
    }

    /**
     * 加载常驻技能
     * @return
     */
    private String loadResidentSkill() {
        List<String> alwaysSkills = skills.getAlwaysSkills();
        StringBuilder sb = new StringBuilder();
        if (alwaysSkills.isEmpty()) {
            sb.append("");
        }else {
            // 加载技能
            List<ContentBlock> contentBlocks = commandQueueManager.triggerResidentSKillOutput(alwaysSkills);
            for (ContentBlock cb : contentBlocks) {
                sb.append(cb.getText()).append("\n");
            }
        }


        return "以下为常驻技能,涉及这些技能不需要使用skill加载: <resident-skill>"+ sb + "</resident-skill>";
    }


    /**
     * 构建用户输入的本地命令
     * @return
     */
    private String buildLocalCommandDesc() {
        return """
                <local-command-caveat>Caveat: The messages below were generated by the user while running local commands. DO NOT respond to these messages or otherwise consider them in your response unless the user explicitly asks you to.</local-command-caveat>
                """;
    }

    /** 视频 base64 嵌入的大小上限：10MB（避免请求体过大） */
    private static final long MAX_VIDEO_EMBED_BYTES = 10 * 1024 * 1024;

    /**
     * 构建用户消息内容。根据当前模型类型决定媒体文件以 base64 嵌入还是路径传递：
     * <ul>
     *   <li>VISION / MULTIMODAL：图片和视频（≤10MB）base64 嵌入</li>
     *   <li>CHAT / TEXT：仅传递文件路径，提示 AI 使用工具处理</li>
     *   <li>其他文件类型始终仅传递路径</li>
     * </ul>
     * 所有文件的路径都会拼入文本块，方便 AI 知悉文件位置。
     *
     * 返回值：
     * - 无媒体或无需嵌入：String
     * - 有嵌入块：List&lt;Map&gt;，每个元素为 {"type": "...", ...}
     */
    public Object buildUserContent(String text, List<String> media) {
        if (media == null || media.isEmpty()) {
            return text;
        }

        boolean canEmbed = currentModelType == ModelConfig.ModelType.VISION
                || currentModelType == ModelConfig.ModelType.MULTIMODAL;

        List<Map<String, Object>> embedBlocks = new ArrayList<>();
        List<String> allPaths = new ArrayList<>();

        for (String pathStr : media) {
            if (pathStr == null || pathStr.isBlank()) continue;
            Path p = Path.of(pathStr);
            if (!Files.isRegularFile(p)) continue;

            String mime = guessMimeType(p);
            if (mime == null) continue;

            allPaths.add(p.toString());

            if (!canEmbed) continue;

            boolean isImage = mime.startsWith("image/");
            boolean isVideo = mime.startsWith("video/");

            if (isImage) {
                embedBase64(p, mime, "image_url", embedBlocks);
            } else if (isVideo) {
                try {
                    long size = Files.size(p);
                    if (size <= MAX_VIDEO_EMBED_BYTES) {
                        embedBase64(p, mime, "video_url", embedBlocks);
                    }
                } catch (IOException ignored) {
                }
            }
        }

        // 构建带路径说明的文本
        StringBuilder textWithPaths = new StringBuilder();
        if (!allPaths.isEmpty()) {
            textWithPaths.append("用户提供以下文件，你可以使用工具（如 MCP中 查看图片/视频工具等）查看和处理：\n");
            for (String fp : allPaths) {
                textWithPaths.append("- ").append(fp).append("\n");
            }
            textWithPaths.append("\n");
        }
        textWithPaths.append(text);

        if (embedBlocks.isEmpty()) {
            return textWithPaths.toString();
        }

        Map<String, Object> textItem = new LinkedHashMap<>();
        textItem.put("type", "text");
        textItem.put("text", textWithPaths.toString());

        List<Map<String, Object>> mixed = new ArrayList<>(embedBlocks);
        mixed.add(textItem);
        return mixed;
    }

    /** 读取文件 base64 编码为 content block，失败静默跳过 */
    private void embedBase64(Path p, String mime, String blockType, List<Map<String, Object>> blocks) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String url = "data:" + mime + ";base64," + b64;

            Map<String, Object> urlObj = new LinkedHashMap<>();
            urlObj.put("url", url);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", blockType);
            item.put(blockType, urlObj);

            blocks.add(item);
        } catch (IOException ignored) {
        }
    }

    /** @deprecated 使用 Helpers.buildToolCallDicts */
    public static List<Map<String, Object>> buildToolCallDicts(List<providers.ToolCallRequest> toolCalls) {
        return utils.Helpers.buildToolCallDicts(toolCalls);
    }

    /** @deprecated 使用 Helpers.addToolResult */
    public static List<Map<String, Object>> addToolResult(
            List<Map<String, Object>> messages, String toolCallId,
            String toolName, String result) {
        return utils.Helpers.addToolResult(messages, toolCallId, toolName, result);
    }

    /** @deprecated 使用 Helpers.addAssistantMessage */
    public static List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages, String content,
            List<Map<String, Object>> toolCalls, String reasoningContent,
            List<Map<String, Object>> thinkingBlocks) {
        return utils.Helpers.addAssistantMessage(messages, content, toolCalls, reasoningContent, thinkingBlocks);
    }

    /** @deprecated 使用 Helpers.addAssistantMessage */
    public static List<Map<String, Object>> addAssistantMessage(
            List<Map<String, Object>> messages, String content,
            List<Map<String, Object>> toolCalls, String reasoningContent) {
        return utils.Helpers.addAssistantMessage(messages, content, toolCalls, reasoningContent);
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