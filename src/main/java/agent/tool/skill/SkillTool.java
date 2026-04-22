package agent.tool.skill;

import agent.command.CommandQueueManager;
import agent.command.SkillCommand;
import agent.tool.Tool;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import skills.SkillsLoader;
import utils.GsonFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class SkillTool extends Tool {

    private final SkillsLoader skillsLoader;
    private final CommandQueueManager commandQueueManager;

    public SkillTool(CommandQueueManager commandQueueManager, SkillsLoader skillManager) {
        this.skillsLoader = skillManager;
        this.commandQueueManager = commandQueueManager;
        log.info("初始化 SkillTool");
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return """
                这个工具用于管理提供特定领域说明和工作流程的专业技能，帮助您高效地加载、列出和卸载技能。
                    支持动作：
                    1.load - 加载指定技能（默认动作）。技能可能属于一个技能包下面的技能, 这种情况使用则需要指定: 技能包名称/技能名称,eg: zjkycode/brainstorming。
                    2.list - 列出指定路径下的所有技能。
                    3.unload - 卸载指定名称的技能。
                    4.reload - 强制重新加载已加载的技能（用于上下文裁剪后恢复完整技能内容）。

                    参数说明：
                    - action：动作类型，可选 load、list、unload 或 reload。默认 load。
                    - name：技能名称，在 load、unload 或 reload 时使用。
                    - path：技能目录路径，在 list 时使用；如果为空，则列出当前工作目录所有技能。

                    重要提示：
                    - **可用技能提示：**可用技能会在对话上下文的系统提醒消息中已列出，方便您查看。
                    - **阻塞性要求：**当用户请求匹配某个技能时，必须先调用相关技能工具，然后再生成其他响应。这是为了确保技能优先执行。
                    - **避免无效操作：**不要仅仅提到技能而不实际调用此工具，也不要调用已在运行中的技能。
                    - **技能加载状态：**如果技能名称已在用户说明中指定（格式：用户已指定使用的技能列表: xxx,xxx），则说明技能已加载，无需再次 load。具体技能说明在对话记录上下文中存在；
                    - 若技能说明不在当前对话上下文中（可能因上下文裁剪丢失），需使用 reload 动作重新加载。
                """;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("description", "操作类型: load | list | unload | reload。默认 load");

        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "技能名称, 技能可能属于一个技能包下面的技能, 这种情况使用则需要指定: 技能包名称/技能名称,eg: zjkycode/brainstorming。load / unload / reload 时使用");

        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "技能目录路径。list 时使用");

        props.put("action", action);
        props.put("name", name);
        props.put("path", path);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", props);

        // 注意：这里不能再 required=name 了，因为 list 动作只需要 path
        // 改为运行时校验
        out.put("required", java.util.List.of());

        return out;
    }

    @Override
    public CompletableFuture<String> execute(Map<String, Object> params) {
        String action = getString(params, "action");
        if (action == null || action.isBlank()) {
            action = "load"; // 兼容旧调用：只传 name 时默认加载
        }

        action = action.trim().toLowerCase(Locale.ROOT);
        log.debug("skill action: {}", action);

        return switch (action) {
            case "load" -> executeLoad(params);
            case "list" -> executeList(params);
            case "unload" -> executeUnload(params);
            case "reload" -> executeReload(params);
            default -> {
                log.warn("不支持的 skill 动作: {}", action);
                yield CompletableFuture.completedFuture(
                        "Error: unsupported action '" + action + "', expected one of: load, list, unload, reload"
                );
            }
        };
    }

    /**
     * 加载技能（保留你现有逻辑）
     */
    private CompletableFuture<String> executeLoad(Map<String, Object> params) {
        String name = getString(params, "name");

        if (name == null || name.isBlank()) {
            log.warn("加载技能失败: 技能名称为空");
            return CompletableFuture.completedFuture("Error: skill name required for action=load");
        }

        if (commandQueueManager.isLoaded(name)) {
            log.info("技能已加载，跳过重复加载，提醒AI需要使用reload工具强制加载，当前技能: {}", name);
            return CompletableFuture.completedFuture("技能已加载, 请勿重复加载, 请查看上下文,帮助用户说明如何使用技能，如果需要强制加载，请使用 reload 工具");
        }

        log.info("加载技能: {}", name);
        SkillCommand skillCommand = new SkillCommand(name, name, skillsLoader);
        commandQueueManager.addSkillCommandByTool(skillCommand);

        // 如果技能输出为空，则提示用户技能正在加载中
        if (StrUtil.isBlank(skillCommand.getOutput())) {
            log.debug("技能输出为空: {}", name);
        }

        log.info("技能加载完成: {}", name);
        return CompletableFuture.completedFuture(
                skillCommand.getOutput()
        );
    }

    /**
     * 列出指定 path 下的技能
     * 具体扫描逻辑放在 SkillsLoader 中
     */
    private CompletableFuture<String> executeList(Map<String, Object> params) {
        String path = getString(params, "path");
        if (StrUtil.isNotBlank(path)) {
            log.info("列出技能, 路径: {}", path);
            return CompletableFuture.completedFuture(GsonFactory.toJson(skillsLoader.listSkills(Path.of(path))));
        }

        log.info("列出所有技能");
        return CompletableFuture.completedFuture(GsonFactory.toJson(skillsLoader.listSkills(true)) );
    }

    /**
     * 卸载指定技能
     * 具体卸载逻辑放在 SkillsLoader 中
     */
    private CompletableFuture<String> executeUnload(Map<String, Object> params) {
        String name = getString(params, "name");

        if (name == null || name.isBlank()) {
            log.warn("卸载技能失败: 技能名称为空");
            return CompletableFuture.completedFuture("Error: skill name required for action=unload");
        }

        log.info("卸载技能: {}", name);
        return CompletableFuture.completedFuture(
                commandQueueManager.unloadUserSkill(name)
        );
    }

    /**
     * 强制重新加载已加载的技能
     * 用于上下文裁剪后恢复完整技能内容
     */
    private CompletableFuture<String> executeReload(Map<String, Object> params) {
        String name = getString(params, "name");

        if (name == null || name.isBlank()) {
            log.warn("重新加载技能失败: 技能名称为空");
            return CompletableFuture.completedFuture("Error: skill name required for action=reload");
        }

        log.info("重新加载技能: {}", name);
        // 从 loadSkills 中移除，绕过 isLoaded 检查
        // 注意：不从 userLoadedSkills 移除，用户仍然在使用这个技能
        commandQueueManager.getLoadSkills().remove(name);

        // 重新加载
        SkillCommand skillCommand = new SkillCommand(name, name, skillsLoader);
        commandQueueManager.addSkillCommandByTool(skillCommand);

        log.info("技能重新加载完成: {}", name);
        return CompletableFuture.completedFuture(
                "[技能重新加载完成]\n" + skillCommand.getOutput()
        );
    }

    private String getString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
