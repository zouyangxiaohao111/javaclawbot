package agent.subagent.definition;

import agent.subagent.builtin.BuiltInAgents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * 1. 从 .javaclawbot/agents/ 目录加载 Markdown 代理定义（本项目）
 * 2. 从 .claude/agents/ 目录加载 Markdown 代理定义（Open-ClaudeCode 兼容）
 * 3. 从插件目录加载代理定义 (~/.javaclawbot/agents/ 和 ~/.claude/agents/)
 * 4. 合并内置代理
 * 5. 处理 agent 覆盖配置 (~/.javaclawbot/agent-overrides/)
 * 6. 按优先级去重
 *
 * 目录优先级（高到低）：
 * .javaclawbot/agents/ > .claude/agents/ > 内置 > ~/.javaclawbot/agents/ > ~/.claude/agents/
 */
public class AgentDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);

    /** 内置代理注册表 */
    private final AgentDefinitionRegistry registry;

    /** 有效的内存范围 */
    private static final List<String> VALID_MEMORY_SCOPES = List.of("user", "project", "local");

    /** 有效的隔离模式 */
    private static final List<String> VALID_ISOLATION_MODES;

    static {
        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) {
            VALID_ISOLATION_MODES = List.of("worktree", "remote");
        } else {
            VALID_ISOLATION_MODES = List.of("worktree");
        }
    }

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

        // 2. 加载项目代理
        // 同时支持 .claude/agents/（Open-ClaudeCode 兼容）和 .javaclawbot/agents/（本项目）
        int totalProjectAgents = 0;

        // .javaclawbot/agents/ - 本项目目录（优先级高）
        Path javaclawbotDir = cwd.resolve(".javaclawbot/agents");
        if (Files.exists(javaclawbotDir) && Files.isDirectory(javaclawbotDir)) {
            List<AgentDefinition> javaclawbotAgents = loadAgentsFromDir(javaclawbotDir);
            agents.addAll(javaclawbotAgents);
            totalProjectAgents += javaclawbotAgents.size();
            log.debug("Loaded {} agents from .javaclawbot/agents", javaclawbotAgents.size());
        }

        // .claude/agents/ - Open-ClaudeCode 兼容（优先级低，会被 javaclawbot 覆盖）
        Path claudeDir = cwd.resolve(".claude/agents");
        if (Files.exists(claudeDir) && Files.isDirectory(claudeDir)) {
            List<AgentDefinition> claudeAgents = loadAgentsFromDir(claudeDir);
            agents.addAll(claudeAgents);
            totalProjectAgents += claudeAgents.size();
            log.debug("Loaded {} agents from .claude/agents", claudeAgents.size());
        }

        log.debug("Loaded {} total project agents", totalProjectAgents);

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
                        AgentDefinition agent = parseAgentFromMarkdown(
                                file.toString(),
                                dir.toString(),
                                name,
                                content,
                                "projectSettings"
                        );
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
    private AgentDefinition parseAgentFromMarkdown(
            String filePath,
            String baseDir,
            String filename,
            String content,
            String source
    ) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        // 简单的 frontmatter 解析（YAML 格式）
        // 格式：--- ... ---
        if (!content.startsWith("---")) {
            // 没有 frontmatter，整个文件作为 system prompt
            AgentDefinition agent = new AgentDefinition();
            agent.setAgentType(filename);
            agent.setWhenToUse("Custom agent: " + filename);
            agent.setSource(source);
            agent.setFilename(filename);
            agent.setBaseDir(baseDir);
            agent.setGetSystemPrompt(() -> content.trim());
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

        String agentType = fields.getOrDefault("name", filename);
        String description = fields.get("description");

        // 验证必需字段
        if (agentType == null || agentType.isEmpty()) {
            log.debug("Agent file {} is missing required 'name' field", filePath);
            return null;
        }
        if (description == null || description.isEmpty()) {
            log.debug("Agent file {} is missing required 'description' field", filePath);
            return null;
        }

        // 解析 model
        String modelRaw = fields.get("model");
        String model = null;
        if (modelRaw != null && !modelRaw.isEmpty()) {
            model = modelRaw.equalsIgnoreCase("inherit") ? "inherit" : modelRaw.trim();
        }

        // 解析 permissionMode
        String permissionModeStr = fields.get("permissionMode");
        PermissionMode permissionMode = null;
        if (permissionModeStr != null && !permissionModeStr.isEmpty()) {
            try {
                permissionMode = PermissionMode.valueOf(permissionModeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid permissionMode '{}' in agent {}", permissionModeStr, agentType);
            }
        }

        // 解析 maxTurns
        String maxTurnsStr = fields.get("maxTurns");
        int maxTurns = 0;
        if (maxTurnsStr != null && !maxTurnsStr.isEmpty()) {
            try {
                maxTurns = Integer.parseInt(maxTurnsStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid maxTurns '{}' in agent {}", maxTurnsStr, agentType);
            }
        }

        // 解析 color
        String color = fields.get("color");

        // 解析 effort
        String effort = fields.get("effort");

        // 解析 background
        String backgroundStr = fields.get("background");
        boolean background = "true".equalsIgnoreCase(backgroundStr);

        // 解析 memory
        String memoryRaw = fields.get("memory");
        String memory = null;
        if (memoryRaw != null && VALID_MEMORY_SCOPES.contains(memoryRaw)) {
            memory = memoryRaw;
        } else if (memoryRaw != null) {
            log.warn("Invalid memory '{}' in agent {}. Valid options: {}", memoryRaw, agentType, VALID_MEMORY_SCOPES);
        }

        // 解析 isolation
        String isolationRaw = fields.get("isolation");
        AgentDefinition.Isolation isolation = null;
        if (isolationRaw != null && VALID_ISOLATION_MODES.contains(isolationRaw)) {
            isolation = "remote".equals(isolationRaw) ?
                    AgentDefinition.Isolation.REMOTE : AgentDefinition.Isolation.WORKTREE;
        } else if (isolationRaw != null) {
            log.warn("Invalid isolation '{}' in agent {}. Valid options: {}", isolationRaw, agentType, VALID_ISOLATION_MODES);
        }

        // 解析 skills
        String skillsStr = fields.get("skills");
        List<String> skills = null;
        if (skillsStr != null && !skillsStr.isEmpty()) {
            skills = parseCommaSeparatedList(skillsStr);
        }

        // 解析 mcpServers
        String mcpServersStr = fields.get("mcpServers");
        List<String> mcpServers = null;
        if (mcpServersStr != null && !mcpServersStr.isEmpty()) {
            mcpServers = parseCommaSeparatedList(mcpServersStr);
        }

        // 解析 requiredMcpServers
        String requiredMcpServersStr = fields.get("requiredMcpServers");
        List<String> requiredMcpServers = null;
        if (requiredMcpServersStr != null && !requiredMcpServersStr.isEmpty()) {
            requiredMcpServers = parseCommaSeparatedList(requiredMcpServersStr);
        }

        // 解析 initialPrompt
        String initialPrompt = fields.get("initialPrompt");

        // 解析 criticalSystemReminder_EXPERIMENTAL
        String criticalSystemReminder = fields.get("criticalSystemReminder_EXPERIMENTAL");

        // 解析 tools
        String toolsStr = fields.get("tools");
        List<String> tools = null;
        if (toolsStr != null && !toolsStr.isEmpty()) {
            tools = parseToolList(toolsStr);
        }

        // 解析 disallowedTools
        String disallowedToolsStr = fields.get("disallowedTools");
        List<String> disallowedTools = null;
        if (disallowedToolsStr != null && !disallowedToolsStr.isEmpty()) {
            disallowedTools = parseToolList(disallowedToolsStr);
        }

        // 构建代理定义
        AgentDefinition agent = new AgentDefinition();
        agent.setAgentType(agentType);
        agent.setWhenToUse(description.replace("\\n", "\n"));
        agent.setSource(source);
        agent.setFilename(filename);
        agent.setBaseDir(baseDir);
        agent.setModel(model);
        agent.setPermissionMode(permissionMode);
        agent.setMaxTurns(maxTurns);
        agent.setColor(color);
        agent.setEffort(effort);
        agent.setBackground(background);
        agent.setMemory(memory);
        agent.setIsolation(isolation);
        agent.setSkills(skills);
        agent.setMcpServers(mcpServers);
        agent.setRequiredMcpServers(requiredMcpServers);
        agent.setInitialPrompt(initialPrompt);
        agent.setCriticalSystemReminder_EXPERIMENTAL(criticalSystemReminder);
        agent.setTools(tools);
        agent.setDisallowedTools(disallowedTools);
        agent.setGetSystemPrompt(() -> systemPrompt);

        return agent;
    }

    /**
     * 解析 frontmatter
     * 对应: parseAgentFromMarkdown() 中的 YAML 解析
     */
    private Map<String, String> parseFrontmatter(String frontmatter) {
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
     * 解析逗号分隔的列表
     */
    private List<String> parseCommaSeparatedList(String str) {
        List<String> result = new ArrayList<>();
        if (str == null || str.isEmpty()) {
            return result;
        }
        String[] items = str.split(",");
        for (String item : items) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
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
     */
    private List<AgentDefinition> loadPluginAgents() {
        List<AgentDefinition> allPluginAgents = new ArrayList<>();

        // ~/.javaclawbot/agents/ - JavaClawBot 插件目录
        Path javaclawbotPluginDir = Paths.get(System.getProperty("user.home"), ".javaclawbot", "agents");
        if (Files.exists(javaclawbotPluginDir) && Files.isDirectory(javaclawbotPluginDir)) {
            List<AgentDefinition> agents = loadAgentsFromDir(javaclawbotPluginDir);
            allPluginAgents.addAll(agents);
            log.debug("Loaded {} agents from ~/.javaclawbot/agents", agents.size());
        }

        // ~/.claude/agents/ - Open-ClaudeCode 兼容插件目录
        Path claudePluginDir = Paths.get(System.getProperty("user.home"), ".claude", "agents");
        if (Files.exists(claudePluginDir) && Files.isDirectory(claudePluginDir)) {
            List<AgentDefinition> agents = loadAgentsFromDir(claudePluginDir);
            allPluginAgents.addAll(agents);
            log.debug("Loaded {} agents from ~/.claude/agents", agents.size());
        }

        return allPluginAgents;
    }

    /**
     * 应用代理覆盖配置
     * 对应: applyAgentOverrides()
     */
    private void applyAgentOverrides(List<AgentDefinition> agents) {
        for (AgentDefinition agent : agents) {
            // 从配置文件读取覆盖配置
            Path overrideFile = getAgentOverrideFile(agent.getAgentType());
            if (Files.exists(overrideFile)) {
                try {
                    String json = Files.readString(overrideFile);
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    AgentOverride override = mapper.readValue(json, AgentOverride.class);

                    if (override.getModel() != null && !override.getModel().isEmpty()) {
                        agent.setModel(override.getModel());
                        log.debug("Applied model override for {}: {}", agent.getAgentType(), override.getModel());
                    }
                    if (override.getMaxTurns() != null && override.getMaxTurns() > 0) {
                        agent.setMaxTurns(override.getMaxTurns());
                        log.debug("Applied maxTurns override for {}: {}", agent.getAgentType(), override.getMaxTurns());
                    }
                    if (override.getTools() != null && !override.getTools().isEmpty()) {
                        agent.setTools(override.getTools());
                        log.debug("Applied tools override for {}", agent.getAgentType());
                    }
                } catch (Exception e) {
                    log.warn("Failed to apply agent override for {}: {}", agent.getAgentType(), e.getMessage());
                }
            }
        }
    }

    /**
     * 获取代理覆盖配置文件路径
     */
    private Path getAgentOverrideFile(String agentType) {
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, ".javaclawbot", "agent-overrides", agentType + ".json");
    }

    /**
     * 代理覆盖配置
     */
    private static class AgentOverride {
        private String model;
        private Integer maxTurns;
        private List<String> tools;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Integer getMaxTurns() { return maxTurns; }
        public void setMaxTurns(Integer maxTurns) { this.maxTurns = maxTurns; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
    }

    /**
     * 去重代理列表
     * 对应: getActiveAgentsFromList()
     * 后添加的同名代理覆盖先添加的
     */
    private List<AgentDefinition> deduplicateAgents(List<AgentDefinition> agents) {
        java.util.Map<String, AgentDefinition> deduplicated = new java.util.LinkedHashMap<>();

        // 按优先级从低到高排序：built-in -> plugin -> userSettings -> projectSettings -> flagSettings
        List<AgentDefinition> sorted = new ArrayList<>(agents);
        sorted.sort((a, b) -> {
            int priorityA = getSourcePriority(a.getSource());
            int priorityB = getSourcePriority(b.getSource());
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
