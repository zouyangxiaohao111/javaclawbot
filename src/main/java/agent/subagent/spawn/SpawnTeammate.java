package agent.subagent.spawn;

import agent.subagent.definition.PermissionMode;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskState;
import agent.subagent.task.TaskStatus;
import agent.subagent.task.TaskType;
import agent.subagent.team.InProcessTeammateTaskState;
import agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spawn Teammate - 创建队友进程
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - spawnTeammate()
 *
 * 功能：
 * 1. 根据操作系统选择后端（tmux / iTerm2 / InProcess）
 * 2. 创建队友进程
 * 3. 注册到 AppState.tasks
 */
public class SpawnTeammate {

    private static final Logger log = LoggerFactory.getLogger(SpawnTeammate.class);

    /**
     * 使用 ToolUseContext 创建队友
     * 对应 Open-ClaudeCode: spawnTeammate(config, context)
     */
    public static CompletableFuture<SpawnResult> spawn(
            String teamName,
            String name,
            String prompt,
            boolean background,
            String workingDirectory,
            ToolUseContext toolUseContext
    ) {
        log.info("Spawning teammate: team={}, name={}, prompt={}, background={}, workingDir={}",
                teamName, name, prompt, background, workingDirectory);

        try {
            // 从 ToolUseContext 获取 AppState 和 Setter
            AppState appState = extractAppState(toolUseContext);
            AppState.Setter setAppState = extractSetAppState(toolUseContext);

            if (appState == null || setAppState == null) {
                log.warn("AppState or Setter is null in ToolUseContext, creating local state");
                // 创建本地 AppState
                appState = new AppState();
                setAppState = appState.setter();
            }

            Backend backend = selectBackend();

            // 设置 ToolView 到 backend（用于创建 fallback 上下文）
            if (toolUseContext != null && toolUseContext.getToolView() != null) {
                backend.setToolView(toolUseContext.getToolView());
            }

            final AppState finalAppState = appState;
            final AppState.Setter finalSetAppState = setAppState;

            SpawnConfig config = new SpawnConfig();
            config.setTeamName(teamName);
            config.setName(name);
            config.setPrompt(prompt);
            config.setBackground(background);
            config.setWorkingDirectory(workingDirectory);

            return backend.spawn(config, toolUseContext)
                    .thenApply(result -> {
                        if (result.isSuccess()) {
                            registerTeammate(config, result, finalAppState, finalSetAppState);
                        }
                        return result;
                    });

        } catch (Exception e) {
            log.error("Error spawning teammate", e);
            return CompletableFuture.completedFuture(
                    SpawnResult.failure("Failed to spawn teammate: " + e.getMessage())
            );
        }
    }

    /**
     * 从 ToolUseContext 提取 AppState
     * 对应 Open-ClaudeCode: context.getAppState()
     */
    private static AppState extractAppState(ToolUseContext context) {
        Object appStateObj = context.getAppState();
        if (appStateObj instanceof AppState) {
            return (AppState) appStateObj;
        }
        log.warn("AppState in context is not agent.subagent.task.AppState, type: {}",
                appStateObj != null ? appStateObj.getClass().getName() : "null");
        return null;
    }

    /**
     * 从 ToolUseContext 提取 SetAppState
     * 对应 Open-ClaudeCode: context.setAppStateForTasks ?? context.setAppState
     */
    private static AppState.Setter extractSetAppState(ToolUseContext context) {
        Object setAppStateObj = context.getSetAppStateForTasks();
        if (setAppStateObj == null) {
            setAppStateObj = context.getSetAppState();
        }

        if (setAppStateObj instanceof AppState.Setter) {
            return (AppState.Setter) setAppStateObj;
        }

        // 如果不是 Setter 类型，尝试作为 UnaryOperator 使用
        // 注意：这可能不兼容，需要根据实际类型进行处理
        if (setAppStateObj != null) {
            log.warn("SetAppState in context is not AppState.Setter, type: {}, attempting to use directly",
                    setAppStateObj.getClass().getName());
            // 创建一个包装来处理不同的类型
            return createSetterFromObject(setAppStateObj);
        }

        log.warn("SetAppState in context is null");
        return null;
    }

    /**
     * 从 Object 创建 Setter
     */
    private static AppState.Setter createSetterFromObject(Object obj) {
        // 尝试作为 AppState.Setter 使用
        if (obj instanceof AppState.Setter) {
            return (AppState.Setter) obj;
        }
        // 如果是其他类型，返回 null 让调用方创建本地状态
        return null;
    }

    public static CompletableFuture<SpawnResult> spawn(
            String teamName,
            String name,
            String prompt,
            boolean background,
            String workingDirectory,
            AppState appState,
            AppState.Setter setAppState,
            ToolUseContext toolUseContext
    ) {
        log.info("Spawning teammate: team={}, name={}, prompt={}, background={}, workingDir={}",
                teamName, name, prompt, background, workingDirectory);

        try {
            Backend backend = selectBackend();

            SpawnConfig config = new SpawnConfig();
            config.setTeamName(teamName);
            config.setName(name);
            config.setPrompt(prompt);
            config.setBackground(background);
            config.setWorkingDirectory(workingDirectory);

            return backend.spawn(config, toolUseContext)
                    .thenApply(result -> {
                        if (result.isSuccess()) {
                            registerTeammate(config, result, appState, setAppState);
                        }
                        return result;
                    });

        } catch (Exception e) {
            log.error("Error spawning teammate", e);
            return CompletableFuture.completedFuture(
                    SpawnResult.failure("Failed to spawn teammate: " + e.getMessage())
            );
        }
    }

    private static Backend selectBackend() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            ITerm2Backend iTerm2 = new ITerm2Backend();
            if (iTerm2.isAvailable()) {
                return iTerm2;
            }
        }

        TmuxBackend tmux = new TmuxBackend();
        if (tmux.isAvailable()) {
            return tmux;
        }

        return new InProcessBackend();
    }

    private static void registerTeammate(
            SpawnConfig config,
            SpawnResult result,
            AppState appState,
            AppState.Setter setAppState
    ) {
        log.info("Registering teammate: name={}, sessionName={}",
                config.getName(), result.getSessionName());

        // 创建 TaskState 并注册到 AppState.tasks
        String taskId = "in_process_teammate-" + UUID.randomUUID().toString().substring(0, 8);

        InProcessTeammateTaskState taskState = new InProcessTeammateTaskState();
        taskState.setId(taskId);
        taskState.setType(TaskType.IN_PROCESS_TEAMMATE);
        taskState.setStatus(TaskStatus.RUNNING);
        taskState.setDescription(config.getName() + ": " +
                (config.getPrompt().length() > 50 ? config.getPrompt().substring(0, 50) + "..." : config.getPrompt()));
        taskState.setPrompt(config.getPrompt());
        taskState.setAbortController(new AtomicBoolean(false));
        taskState.setAwaitingPlanApproval(false);
        taskState.setPermissionMode("default");
        taskState.setIdle(false);
        taskState.setShutdownRequested(false);
        taskState.setLastReportedToolCount(0);
        taskState.setLastReportedTokenCount(0);
        taskState.setPendingUserMessages(new ArrayList<>());
        taskState.setMessages(new ArrayList<>());

        // 设置队友身份
        InProcessTeammateTaskState.TeammateIdentity identity = new InProcessTeammateTaskState.TeammateIdentity(
                taskId,
                config.getName(),
                config.getTeamName(),
                "blue",
                false,
                null
        );
        taskState.setIdentity(identity);

        // 注册到 AppState
        setAppState.accept(prev -> {
            Map<String, TaskState> tasks = new HashMap<>(
                    prev.getTasks() != null ? prev.getTasks() : new java.util.HashMap<>()
            );
            tasks.put(taskId, taskState);
            prev.setTasks(tasks);
            return prev;
        });

        log.info("Registered teammate task: id={}, name={}", taskId, config.getName());
    }

    // =====================
    // Utility Methods
    // =====================

    /**
     * 生成唯一的队友名称
     * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - generateUniqueTeammateName()
     *
     * 如果名称已存在，追加数字后缀（如 tester-2, tester-3）
     *
     * @param baseName 基础名称
     * @param teamName 团队名称（可选）
     * @return 唯一名称
     */
    public static String generateUniqueTeammateName(String baseName, String teamName) {
        // TODO: 当实现团队文件支持后，检查团队成员列表
        // 目前直接返回基础名称
        if (teamName == null || teamName.isEmpty()) {
            return baseName;
        }
        // 占位：后续应检查团队成员是否已存在同名
        return baseName;
    }

    /**
     * 构建继承的 CLI 标志
     * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - buildInheritedCliFlags()
     *
     * 从父会话继承以下设置：
     * - 权限模式
     * - 模型覆盖
     * - 设置文件路径
     * - 插件目录
     * - Chrome 标志
     *
     * @param planModeRequired 是否需要计划模式
     * @param permissionMode 权限模式
     * @return CLI 标志字符串
     */
    public static String buildInheritedCliFlags(boolean planModeRequired, PermissionMode permissionMode) {
        StringBuilder flags = new StringBuilder();

        // 权限模式传播（计划模式优先）
        if (planModeRequired) {
            // 不继承绕过权限
        } else if (permissionMode == PermissionMode.BYPASS_PERMISSIONS) {
            flags.append(" --dangerously-skip-permissions");
        } else if (permissionMode == PermissionMode.ACCEPT_EDITS) {
            flags.append(" --permission-mode acceptEdits");
        } else if (permissionMode == PermissionMode.PLAN) {
            flags.append(" --permission-mode plan");
        }

        // TODO: 添加以下继承项（需要配置系统支持）
        // - 模型覆盖 (getMainLoopModelOverride())
        // - 设置文件路径 (getFlagSettingsPath())
        // - 插件目录 (getInlinePlugins())
        // - Chrome 标志 (getChromeFlagOverride())

        return flags.toString();
    }

    /**
     * 构建继承的环境变量
     * 对应 Open-ClaudeCode: src/utils/swarm/spawnUtils.ts - buildInheritedEnvVars()
     *
     * @return 环境变量字符串
     */
    public static String buildInheritedEnvVars() {
        // 基本环境变量
        StringBuilder envVars = new StringBuilder();
        envVars.append("CLAUDECODE=1 ");
        envVars.append("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1");

        // TODO: 从系统环境变量继承 API 相关变量
        // 如 ANTHROPIC_API_KEY, OPENAI_API_KEY 等

        return envVars.toString();
    }

    /**
     * 格式化队友命令
     * 对应 Open-ClaudeCode: 构建 spawnCommand
     *
     * @param workingDir 工作目录
     * @param binaryPath 可执行文件路径
     * @param teammateArgs 队友参数
     * @param inheritedFlags 继承的 CLI 标志
     * @param inheritedEnvVars 继承的环境变量
     * @return 完整的 spawn 命令
     */
    public static String formatSpawnCommand(
            String workingDir,
            String binaryPath,
            String teammateArgs,
            String inheritedFlags,
            String inheritedEnvVars
    ) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("cd ").append(shellQuote(workingDir)).append(" && ");
        cmd.append("env ").append(inheritedEnvVars).append(" ");
        cmd.append(shellQuote(binaryPath)).append(" ");
        cmd.append(teammateArgs);
        if (!inheritedFlags.isEmpty()) {
            cmd.append(" ").append(inheritedFlags);
        }
        return cmd.toString();
    }

    /**
     * Shell 引号转义
     */
    private static String shellQuote(String value) {
        if (value == null) return "''";
        // 简单实现：使用单引号并转义内部单引号
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // =====================
    // Teammate Termination
    // =====================

    public static CompletableFuture<Boolean> terminate(String sessionName) {
        log.info("Terminating teammate: sessionName={}", sessionName);

        TmuxBackend tmux = new TmuxBackend();
        if (tmux.isAvailable()) {
            return tmux.terminate(sessionName);
        }

        ITerm2Backend iTerm2 = new ITerm2Backend();
        if (iTerm2.isAvailable()) {
            return iTerm2.terminate(sessionName);
        }

        return CompletableFuture.completedFuture(false);
    }
}
