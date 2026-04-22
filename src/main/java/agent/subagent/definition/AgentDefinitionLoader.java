package agent.subagent.definition;

import agent.subagent.builtin.BuiltInAgents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 代理定义加载器
 *
 * 对应 Open-ClaudeCode: src/tools/AgentTool/loadAgentsDir.ts - getAgentDefinitionsWithOverrides()
 *
 * 职责：
 * 1. 从 .claude/commands/ 目录加载 Markdown 代理定义
 * 2. 从插件目录加载代理定义
 * 3. 合并内置代理
 * 4. 处理 agent 覆盖配置
 */
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);

    /** 内置代理注册表 */
    private final AgentDefinitionRegistry registry;

    public AgentDefinitionLoader() {
        this.registry = new AgentDefinitionRegistry();
    }

    /**
     * 加载所有代理定义（带覆盖）
     * 对应: getAgentDefinitionsWithOverrides()
     */
    public List<AgentDefinition> getAgentDefinitionsWithOverrides(Path cwd) {
        List<AgentDefinition> agents = new ArrayList<>();

        // 1. 获取内置代理
        agents.addAll(BuiltInAgents.getBuiltInAgents());
        log.debug("Loaded {} built-in agents", BuiltInAgents.getBuiltInAgents().size());

        // 2. 加载项目代理（.claude/commands/）
        List<AgentDefinition> projectAgents = loadAgentsFromDir(cwd.resolve(".claude/commands"));
        agents.addAll(projectAgents);
        log.debug("Loaded {} project agents", projectAgents.size());

        // 3. 加载插件代理
        List<AgentDefinition> pluginAgents = loadPluginAgents();
        agents.addAll(pluginAgents);
        log.debug("Loaded {} plugin agents", pluginAgents.size());

        // 4. 应用覆盖配置
        applyAgentOverrides(agents);

        // 5. 去重（同名覆盖优先级）
        List<AgentDefinition> deduplicated = deduplicateAgents(agents);
        log.info("Loaded {} total agents (after deduplication)", deduplicated.size());

        return deduplicated;
    }

    /**
     * 从目录加载 Markdown 代理
     * 对应: loadMarkdownFilesForSubdir()
     */
    private List<AgentDefinition> loadAgentsFromDir(Path dir) {
        List<AgentDefinition> agents = new ArrayList<>();

        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
            log.debug("Agent directory does not exist: {}", dir);
            return agents;
        }

        try (Stream<Path> paths = Files.walk(dir)) {
            paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        String name = file.getFileName().toString().replace(".md", "");
                        AgentDefinition agent = parseAgentFromMarkdown(name, content, "userSettings");
                        if (agent != null) {
                            agents.add(agent);
                            log.debug("Loaded agent from file: {}", file);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load agent from {}: {}", file, e.getMessage());
                    }
                });
        } catch (Exception e) {
            log.error("Error walking agent directory {}: {}", dir, e.getMessage());
        }

        return agents;
    }

    /**
     * 解析 Markdown 代理定义
     * 对应: parseAgentFromMarkdown()
     */
    private AgentDefinition parseAgentFromMarkdown(String name, String content, String source) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        // 简单的 frontmatter 解析（YAML 格式）
        // 格式：--- ... ---
        if (!content.startsWith("---")) {
            // 没有 frontmatter，整个文件作为 system prompt
            AgentDefinition agent = new AgentDefinition();
            agent.agentType = name;
            agent.whenToUse = "Custom agent: " + name;
            agent.source = source;
            agent.getSystemPrompt = () -> content.trim();
            return agent;
        }

        int endFrontmatter = content.indexOf("---", 3);
        if (endFrontmatter < 0) {
            return null;
        }

        String frontmatter = content.substring(3, endFrontmatter).trim();
        String systemPrompt = content.substring(endFrontmatter + 3).trim();

        // 解析 frontmatter 字段
        Map<String, String> fields = parseFrontmatter(frontmatter);

        String agentType = fields.getOrDefault("name", name);
        String description = fields.get("description");
        String model = fields.get("model");
        String permissionModeStr = fields.get("permissionMode");
        String maxTurnsStr = fields.get("maxTurns");
        String toolsStr = fields.get("tools");
        String disallowedToolsStr = fields.get("disallowedTools");

        AgentDefinition agent = new AgentDefinition();
        agent.agentType = agentType;
        agent.whenToUse = description != null ? description : "Custom agent: " + agentType;
        agent.source = source;
        agent.getSystemPrompt = () -> systemPrompt;

        // 解析 model
        if (model != null && !model.isEmpty()) {
            agent.model = model.equalsIgnoreCase("inherit") ? "inherit" : model;
        }

        // 解析 permissionMode
        if (permissionModeStr != null) {
            try {
                agent.permissionMode = PermissionMode.valueOf(permissionModeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid permissionMode: {}", permissionModeStr);
            }
        }

        // 解析 maxTurns
        if (maxTurnsStr != null) {
            try {
                agent.maxTurns = Integer.parseInt(maxTurnsStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid maxTurns: {}", maxTurnsStr);
            }
        }

        // 解析 tools
        if (toolsStr != null) {
            agent.tools = parseToolList(toolsStr);
        }

        // 解析 disallowedTools
        if (disallowedToolsStr != null) {
            agent.disallowedTools = parseToolList(disallowedToolsStr);
        }

        return agent;
    }

    /**
     * 解析 frontmatter
     */
    private Map<String, String> parseFrontmatter(String frontmatter) {
        // 简单的键值对解析
        // 支持：key: value 或 key: "multiline value"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^(\\w+):\\s*(.*)$");
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();

        for (String line : frontmatter.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            java.util.regex.Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String key = matcher.group(1);
                String value = matcher.group(2).trim();
                // 移除引号
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                } else if (value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                fields.put(key, value);
            }
        }

        return fields;
    }

    /**
     * 解析工具列表
     * 格式：tool1, tool2, tool3 或 ["tool1", "tool2", "tool3"]
     */
    private List<String> parseToolList(String toolsStr) {
        List<String> tools = new ArrayList<>();

        // 处理 JSON 数组格式
        if (toolsStr.contains("[") && toolsStr.contains("]")) {
            // 简单处理 JSON 数组
            String[] items = toolsStr.replaceAll("[\\[\\]\"]", "").split(",");
            for (String item : items) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    tools.add(trimmed);
                }
            }
        } else {
            // 逗号分隔格式
            String[] items = toolsStr.split(",");
            for (String item : items) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    tools.add(trimmed);
                }
            }
        }

        return tools;
    }

    /**
     * 加载插件代理
     * 对应: loadPluginAgents()
     * 当前实现为空，后续扩展
     */
    private List<AgentDefinition> loadPluginAgents() {
        // TODO: 实现插件代理加载
        return new ArrayList<>();
    }

    /**
     * 应用代理覆盖配置
     * 对应: applyAgentOverrides()
     * 当前实现为空，后续扩展
     */
    private void applyAgentOverrides(List<AgentDefinition> agents) {
        // TODO: 实现代理覆盖配置
    }

    /**
     * 去重代理列表
     * 对应: getActiveAgentsFromList()
     * 后添加的同名代理覆盖先添加的
     */
    private List<AgentDefinition> deduplicateAgents(List<AgentDefinition> agents) {
        // 内置代理优先级最低，用户代理优先级最高
        // 顺序：built-in -> plugin -> userSettings -> projectSettings -> flagSettings
        // 我们按相反顺序遍历，最后一个胜出

        java.util.Map<String, AgentDefinition> deduplicated = new java.util.LinkedHashMap<>();

        // 按优先级从低到高排序：built-in -> plugin -> userSettings -> projectSettings -> flagSettings
        List<AgentDefinition> sorted = new ArrayList<>(agents);
        sorted.sort((a, b) -> {
            int priorityA = getSourcePriority(a.source);
            int priorityB = getSourcePriority(b.source);
            return Integer.compare(priorityA, priorityB);
        });

        for (AgentDefinition agent : sorted) {
            deduplicated.put(agent.getAgentType(), agent);
        }

        return new ArrayList<>(deduplicated.values());
    }

    /**
     * 获取来源优先级
     */
    private int getSourcePriority(String source) {
        if (source == null) return 0;
        return switch (source) {
            case "built-in" -> 0;
            case "plugin" -> 1;
            case "userSettings" -> 2;
            case "projectSettings" -> 3;
            case "flagSettings" -> 4;
            default -> 0;
        };
    }

    /**
     * 获取注册表实例
     */
    public AgentDefinitionRegistry getRegistry() {
        return registry;
    }
}
