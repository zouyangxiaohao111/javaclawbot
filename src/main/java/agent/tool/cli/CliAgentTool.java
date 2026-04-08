package agent.tool.cli;

import agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import providers.cli.CliAgentCommandHandler;
import providers.cli.ProjectRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CLI Agent 工具 - 让主代理能够管理 CLI Agent (Claude Code / OpenCode)
 *
 * 支持操作:
 * - bind: 绑定项目
 * - unbind: 解绑项目
 * - projects: 列出所有项目
 * - run: 运行 CLI Agent
 * - status: 查看状态
 * - stop: 停止 Agent
 * - stopall: 停止所有 Agent
 */
@Slf4j
public class CliAgentTool extends Tool {

    private final CliAgentCommandHandler cliAgentHandler;

    public CliAgentTool(CliAgentCommandHandler cliAgentHandler) {
        this.cliAgentHandler = cliAgentHandler;
    }

    @Override
    public String name() {
        return "cli_agent";
    }

    @Override
    public String description() {
        return """
                管理 CLI Agent (Claude Code / OpenCode)，让主代理能够启动和管理 CLI 子代理。

                支持的操作:
                - bind: 绑定项目路径到项目名称
                - unbind: 解绑项目
                - projects: 列出所有绑定的项目
                - run: 在指定项目上运行 CLI Agent
                - status: 查看运行状态
                - stop: 停止指定项目的 Agent
                - stopall: 停止所有 CLI Agent

                注意：此工具仅在开发者模式下可用。
                """;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> action = new java.util.LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("bind", "unbind", "projects", "run", "status", "stop", "stopall"));
        action.put("description", "操作类型");

        Map<String, Object> project = new java.util.LinkedHashMap<>();
        project.put("type", "string");
        project.put("description", "项目名称 (bind/unbind/run/status/stop 时使用)");

        Map<String, Object> path = new java.util.LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "项目路径 (bind 时使用)");

        Map<String, Object> agentType = new java.util.LinkedHashMap<>();
        agentType.put("type", "string");
        agentType.put("enum", List.of("claude", "opencode"));
        agentType.put("description", "Agent 类型 (run 时使用，默认 claude)");

        Map<String, Object> prompt = new java.util.LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("description", "提示词 (run 时使用)");

        Map<String, Object> main = new java.util.LinkedHashMap<>();
        main.put("type", "boolean");
        main.put("description", "是否设为主代理项目 (bind 时使用，默认 false)");

        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("action", action);
        props.put("project", project);
        props.put("path", path);
        props.put("agent_type", agentType);
        props.put("prompt", prompt);
        props.put("main", main);

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);

        return schema;
    }

    @Override
    public CompletableFuture<String> execute(Map<String, Object> args) {
        String action = (String) args.get("action");

        if (action == null || action.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 action 参数");
        }

        return switch (action.toLowerCase()) {
            case "bind" -> handleBind(args);
            case "unbind" -> handleUnbind(args);
            case "projects" -> handleProjects();
            case "run" -> handleRun(args);
            case "status" -> handleStatus(args);
            case "stop" -> handleStop(args);
            case "stopall" -> handleStopAll();
            default -> CompletableFuture.completedFuture("错误: 未知的 action: " + action);
        };
    }

    // ==================== 各操作处理 ====================

    private CompletableFuture<String> handleBind(Map<String, Object> args) {
        String project = (String) args.get("project");
        String path = (String) args.get("path");
        Boolean main = args.containsKey("main") && Boolean.TRUE.equals(args.get("main"));

        if (project == null || project.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 project 参数");
        }
        if (path == null || path.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 path 参数");
        }

        ProjectRegistry registry = cliAgentHandler.getProjectRegistry();
        boolean success = registry.bind(project, path, main);

        if (success) {
            String mainHint = main ? " [主代理项目]" : "";
            return CompletableFuture.completedFuture(
                    "✅ 项目已绑定" + mainHint + ": " + project + " → " + path);
        } else {
            return CompletableFuture.completedFuture("❌ 绑定失败");
        }
    }

    private CompletableFuture<String> handleUnbind(Map<String, Object> args) {
        String project = (String) args.get("project");

        if (project == null || project.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 project 参数");
        }

        ProjectRegistry registry = cliAgentHandler.getProjectRegistry();
        boolean wasMain = registry.getInfo(project) != null && registry.getInfo(project).isMain();
        boolean success = registry.unbind(project);

        if (success) {
            // 停止相关的 Agent
            cliAgentHandler.getAgentPool().stopAllForProject(project);
            String mainHint = wasMain ? " (原主代理项目已清除)" : "";
            return CompletableFuture.completedFuture("✅ 项目已解绑" + mainHint + ": " + project);
        } else {
            return CompletableFuture.completedFuture("❌ 项目不存在: " + project);
        }
    }

    private CompletableFuture<String> handleProjects() {
        ProjectRegistry registry = cliAgentHandler.getProjectRegistry();
        Map<String, ProjectRegistry.ProjectInfo> projects = registry.listAll();

        if (projects.isEmpty()) {
            return CompletableFuture.completedFuture("📁 暂无绑定项目");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📁 已绑定项目 (").append(projects.size()).append("):\n");

        for (Map.Entry<String, ProjectRegistry.ProjectInfo> entry : projects.entrySet()) {
            ProjectRegistry.ProjectInfo info = entry.getValue();
            sb.append("  • ").append(entry.getKey());
            if (info.isMain()) {
                sb.append(" ⭐ [主代理]");
            }
            sb.append(" → ").append(info.getPath()).append("\n");
        }

        return CompletableFuture.completedFuture(sb.toString());
    }

    private CompletableFuture<String> handleRun(Map<String, Object> args) {
        String project = (String) args.get("project");
        String prompt = (String) args.get("prompt");
        String agentType = args.containsKey("agent_type") ? (String) args.get("agent_type") : "claude";

        if (project == null || project.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 project 参数");
        }
        if (prompt == null || prompt.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 prompt 参数");
        }

        ProjectRegistry registry = cliAgentHandler.getProjectRegistry();
        String path = registry.getPath(project);
        if (path == null) {
            return CompletableFuture.completedFuture("❌ 项目 '" + project + "' 未绑定。请先使用 bind 操作绑定项目。");
        }

        // 异步执行并返回
        return cliAgentHandler.getAgentPool()
                .getOrCreate(project, agentType)
                .thenCompose(session -> session.send(prompt, List.of(), List.of()))
                .thenApply(v -> "✅ CLI Agent (" + agentType + ") 已启动并执行: " + project)
                .exceptionally(e -> "❌ 启动失败: " + e.getMessage());
    }

    private CompletableFuture<String> handleStatus(Map<String, Object> args) {
        String project = args.containsKey("project") ? (String) args.get("project") : null;

        if (project != null) {
            // 特定项目状态
            return CompletableFuture.completedFuture(
                    cliAgentHandler.getAgentPool().getStatusSummary());
        } else {
            // 所有项目状态
            return CompletableFuture.completedFuture(
                    cliAgentHandler.getAgentPool().getStatusSummary());
        }
    }

    private CompletableFuture<String> handleStop(Map<String, Object> args) {
        String project = (String) args.get("project");
        String agentType = args.containsKey("agent_type") ? (String) args.get("agent_type") : null;

        if (project == null || project.isBlank()) {
            return CompletableFuture.completedFuture("错误: 缺少 project 参数");
        }

        if (agentType != null) {
            // 停止特定类型的 Agent
            String key = project + ":" + agentType;
            var session = cliAgentHandler.getAgentPool().getSession(key);
            if (session != null) {
                return session.close()
                        .thenApply(v -> {
                            cliAgentHandler.getAgentPool().removeSession(key);
                            return "✅ 已停止 " + project + " 的 " + agentType + " Agent";
                        })
                        .exceptionally(e -> "❌ 停止失败: " + e.getMessage());
            } else {
                return CompletableFuture.completedFuture("⚠️ " + project + " 的 " + agentType + " Agent 未运行");
            }
        } else {
            // 停止该项目的所有 Agent
            return cliAgentHandler.getAgentPool()
                    .stopAllForProject(project)
                    .thenApply(v -> "✅ 已停止 " + project + " 的所有 Agent")
                    .exceptionally(e -> "❌ 停止失败: " + e.getMessage());
        }
    }

    private CompletableFuture<String> handleStopAll() {
        int count = cliAgentHandler.getAgentPool().getAllSessions().size();
        if (count == 0) {
            return CompletableFuture.completedFuture("⚠️ 无运行中的 CLI Agent");
        }

        return cliAgentHandler.getAgentPool()
                .closeAll()
                .thenApply(v -> "✅ 已停止所有 CLI Agent (共 " + count + " 个)")
                .exceptionally(e -> "❌ 停止失败: " + e.getMessage());
    }
}
