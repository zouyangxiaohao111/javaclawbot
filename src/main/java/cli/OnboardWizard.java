package cli;

import config.Config;
import config.ConfigIO;
import config.ConfigSchema;
import config.provider.FallbackTarget;
import config.provider.ProviderConfig;
import lombok.Getter;
import lombok.Setter;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import providers.ProviderCatalog;
import utils.Helpers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OnboardWizard {

    /** 向导模式 */
    public enum WizardFlow {
        QUICKSTART,
        ADVANCED
    }

    /** 配置处理方式 */
    public enum ConfigAction {
        KEEP,
        MODIFY,
        RESET
    }

    /**
     * 注意：
     * 这里不能默认 QUICKSTART，否则永远不会进入 selectWizardFlow()
     */
    private WizardFlow flow = null;

    /** 是否已通过命令行接受风险确认 */
    private boolean acceptRisk = false;
    @Getter
    @Setter
    private Path workspace = null;

    /**
     * 运行向导
     */
    public void run(String[] args) {
        parseArgs(args);

        Path configPath = ConfigIO.getConfigPath();
        ConfigAction configAction = ConfigAction.KEEP;

        // 1) 风险确认
        if (!acceptRisk) {
            if (!showRiskAcknowledgement()) {
                System.out.println("已取消配置向导。");
                return;
            }
        }

        // 2) 选择模式（仅当命令行未显式指定时）
        if (flow == null) {
            flow = selectWizardFlow();
            if (flow == null) {
                System.out.println("已取消配置向导。");
                return;
            }
        }

        // 3) 若已存在配置，询问如何处理
        if (Files.exists(configPath)) {
            configAction = handleExistingConfig(configPath);
            if (configAction == null) {
                System.out.println("已取消配置向导。");
                return;
            }
        }

        // 4) 加载配置
        Config cfg;
        if (Files.exists(configPath) && configAction == ConfigAction.RESET) {
            cfg = new Config();
        } else {
            cfg = ConfigIO.loadConfig(null);
        }

        // 5) 设定 workspace
        Path workspace = Helpers.getWorkspacePath(null);
        cfg.setWorkspacePath(workspace);

        // 6) 进入交互
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            // 先创建 workspace 基础目录，但模板文件延后到配置流程后再创建
            ensureWorkspaceStructure(workspace);

            int totalSteps = (flow == WizardFlow.QUICKSTART) ? 3 : 4;
            int currentStep = 1;

            // 第 1 步：配置提供商
            System.out.println();
            System.out.println("第 " + currentStep + "/" + totalSteps + " 步  配置提供商");
            currentStep++;
            configurePrimaryProvider(terminal, reader, cfg, configAction);

            // 第 2 步：配置 Channel
            System.out.println();
            System.out.println("第 " + currentStep + "/" + totalSteps + " 步  配置 Channel");
            currentStep++;
            ChannelConfigurator.configureChannels(terminal, reader, cfg, flow == WizardFlow.ADVANCED);

            // 第 3 步：配置备用模型（仅高级模式）
            if (flow == WizardFlow.ADVANCED) {
                System.out.println();
                System.out.println("第 " + currentStep + "/" + totalSteps + " 步  配置备用模型");
                System.out.println("(当主模型不可用时，自动请求备用模型)");
                currentStep++;
                configureFallback(terminal, reader, cfg);
            }

            // 第 4 / 3 步：配置技能
            System.out.println();
            System.out.println("第 " + currentStep + "/" + totalSteps + " 步  配置技能");
            configureSkills(terminal, cfg, configAction == ConfigAction.RESET);

            // 7) 配置完成后，再创建/补齐模板文件
            createWorkspaceTemplates(workspace);

            // 8) 保存配置
            ConfigIO.saveConfig(cfg, null);

            // 9) 完成提示
            showCompletionMessage(configPath, workspace);

        } catch (Exception e) {
            System.err.println("Onboard wizard failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 解析命令行参数
     */
    private void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--workspace=")) {
                workspace = Path.of(arg.substring("--workspace=".length()));
                continue;
            }
            switch (arg) {
                case "--quickstart", "-q" -> flow = WizardFlow.QUICKSTART;
                case "--advanced", "-a" -> flow = WizardFlow.ADVANCED;
                case "--accept-risk" -> acceptRisk = true;
                default -> {
                    // ignore
                }
            }
        }
    }

    /**
     * 显示安全警告确认
     */
    private boolean showRiskAcknowledgement() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  ⚠️  安全警告 — 请仔细阅读");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("javaclawbot 是一个个人 AI 助手，默认为单一可信操作边界。");
        System.out.println();
        System.out.println("重要提示：");
        System.out.println("  • 此助手可以读取文件并执行操作（如果启用了工具）");
        System.out.println("  • 恶意提示可能诱导其执行不安全操作");
        System.out.println("  • 默认不适合多用户共享环境");
        System.out.println();
        System.out.println("建议的安全基线：");
        System.out.println("  • 保持密钥远离助手可访问的文件系统");
        System.out.println("  • 对启用工具的助手使用最强大的模型");
        System.out.println("  • 多用户环境请隔离信任边界");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            return TerminalPrompts.promptConfirm(
                    reader,
                    "我理解这是个人默认设置，多用户使用需要安全加固。继续？",
                    false
            );
        } catch (Exception e) {
            System.err.println("风险确认失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 选择向导模式
     */
    private WizardFlow selectWizardFlow() {
        System.out.println();
        System.out.println("选择配置模式：");
        System.out.println("  1. QuickStart - 快速开始，使用推荐默认值");
        System.out.println("  2. Advanced   - 高级模式，手动配置所有选项");
        System.out.println();

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            List<String> options = List.of("QuickStart", "Advanced");
            String selected = TerminalPrompts.singleSelect(
                    terminal,
                    options,
                    x -> x,
                    "选择配置模式",
                    0,
                    false
            );

            if (selected == null) {
                return null;
            }
            return "Advanced".equals(selected) ? WizardFlow.ADVANCED : WizardFlow.QUICKSTART;
        } catch (Exception e) {
            System.err.println("选择配置模式失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 处理现有配置
     */
    private ConfigAction handleExistingConfig(Path configPath) {
        System.out.println();
        System.out.println("检测到现有配置: " + configPath);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            List<String> options = List.of(
                    "保留现有值",
                    "更新配置值",
                    "重置配置"
            );

            String selected = TerminalPrompts.singleSelect(
                    terminal,
                    options,
                    x -> x,
                    "配置处理方式",
                    0,
                    false
            );

            if (selected == null) return null;
            if (selected.contains("保留")) return ConfigAction.KEEP;
            if (selected.contains("更新")) return ConfigAction.MODIFY;
            if (selected.contains("重置")) return ConfigAction.RESET;

        } catch (Exception e) {
            System.err.println("处理现有配置失败: " + e.getMessage());
        }

        return ConfigAction.KEEP;
    }

    /**
     * 配置主提供商
     */
    private void configurePrimaryProvider(
            Terminal terminal,
            LineReader reader,
            Config cfg,
            ConfigAction configAction
    ) {
        List<ProviderCatalog.ProviderMeta> providers = ProviderCatalog.supportedProviders();
        String currentProvider = cfg.getAgents().getDefaults().getProvider();
        int defaultIndex = indexOfProvider(providers, currentProvider);

        // QuickStart + KEEP：沿用现有配置
        if (flow == WizardFlow.QUICKSTART
                && configAction == ConfigAction.KEEP
                && currentProvider != null
                && !currentProvider.isBlank()) {
            System.out.println("  使用现有提供商配置: " + currentProvider);
            return;
        }

        ProviderCatalog.ProviderMeta selected = TerminalPrompts.singleSelect(
                terminal,
                providers,
                p -> p.getLabel() + " [" + p.getName() + "]",
                "选择默认提供者",
                defaultIndex,
                false
        );
        if (selected == null) return;

        String providerName = selected.getName();
        ProviderConfig providerConfig = cfg.getProviders().getByName(providerName);
        if (providerConfig == null) {
            System.err.println("未找到提供商配置: " + providerName);
            return;
        }

        // API Base
        if (selected.isSupportsApiBase()) {
            String defaultBase = (providerConfig.getApiBase() != null && !providerConfig.getApiBase().isBlank())
                    ? providerConfig.getApiBase()
                    : selected.getDefaultApiBase();

            if (flow == WizardFlow.ADVANCED
                    || configAction == ConfigAction.MODIFY
                    || configAction == ConfigAction.RESET) {
                String input = TerminalPrompts.promptText(reader, "API base", defaultBase);
                providerConfig.setApiBase(input);
            } else if (defaultBase != null && !defaultBase.isBlank()) {
                providerConfig.setApiBase(defaultBase);
            }
        }

        // API Key
        if (selected.isSupportsApiKey()) {
            String existingKey = providerConfig.getApiKey();

            if (flow == WizardFlow.QUICKSTART
                    && configAction == ConfigAction.KEEP
                    && existingKey != null
                    && !existingKey.isBlank()) {
                System.out.println("  使用现有 API key");
            } else {
                String apiKey = TerminalPrompts.promptSecret(reader, "API key", existingKey);
                providerConfig.setApiKey(apiKey);
            }
        }

        // 模型
        String model;
        if (selected.isManualModelOnly() || selected.getRecommendedModels().isEmpty()) {
            model = TerminalPrompts.promptText(
                    reader,
                    "默认模型",
                    cfg.getAgents().getDefaults().getModel()
            );
        } else {
            List<String> modelOptions = new ArrayList<>(selected.getRecommendedModels());
            modelOptions.add("✏️ 手动输入模型名称");

            int modelDefaultIndex = indexOfString(modelOptions, cfg.getAgents().getDefaults().getModel());

            String chosenModel = TerminalPrompts.singleSelect(
                    terminal,
                    modelOptions,
                    x -> x,
                    "选择默认模型: " + selected.getLabel(),
                    modelDefaultIndex,
                    false
            );

            if ("✏️ 手动输入模型名称".equals(chosenModel)) {
                model = TerminalPrompts.promptText(
                        reader,
                        "输入模型名称",
                        cfg.getAgents().getDefaults().getModel()
                );
            } else {
                model = (chosenModel != null) ? chosenModel : cfg.getAgents().getDefaults().getModel();
            }
        }

        cfg.getAgents().getDefaults().setProvider(providerName);
        cfg.getAgents().getDefaults().setModel(model);
    }

    /**
     * 配置备用模型
     */
    private void configureFallback(Terminal terminal, LineReader reader, Config cfg) {
        boolean enabled = TerminalPrompts.promptConfirm(
                reader,
                "是否启用备用模型?",
                cfg.getAgents().getDefaults().getFallback().isEnabled()
        );
        cfg.getAgents().getDefaults().getFallback().setEnabled(enabled);

        if (!enabled) {
            cfg.getAgents().getDefaults().getFallback().getTargets().clear();
            return;
        }

        cfg.getAgents().getDefaults().getFallback().setMode("on_error");

        List<ProviderCatalog.ProviderMeta> providers = ProviderCatalog.supportedProviders();
        String primary = cfg.getAgents().getDefaults().getProvider();

        List<ProviderCatalog.ProviderMeta> fallbackCandidates = new ArrayList<>();
        for (ProviderCatalog.ProviderMeta p : providers) {
            if (!p.getName().equalsIgnoreCase(primary)) {
                fallbackCandidates.add(p);
            }
        }

        TerminalPrompts.SelectionResult<ProviderCatalog.ProviderMeta> providerSelection =
                TerminalPrompts.multiSelect(
                        terminal,
                        fallbackCandidates,
                        p -> p.getLabel() + " [" + p.getName() + "]",
                        "选择备用模型和对应提供商",
                        false,
                        true
                );

        if (providerSelection.isSkipped()) {
            return;
        }

        List<FallbackTarget> targets = new ArrayList<>();

        for (ProviderCatalog.ProviderMeta meta : providerSelection.getItems()) {
            List<String> models;

            if (meta.isManualModelOnly() || meta.getRecommendedModels().isEmpty()) {
                String manual = TerminalPrompts.promptText(reader, "备用模型:" + meta.getLabel(), "");
                models = (manual == null || manual.isBlank()) ? List.of() : List.of(manual);
            } else {
                TerminalPrompts.SelectionResult<String> modelSelection =
                        TerminalPrompts.multiSelect(
                                terminal,
                                meta.getRecommendedModels(),
                                x -> x,
                                "选择多个备用模型: " + meta.getLabel(),
                                false,
                                true
                        );

                if (modelSelection.isSkipped() || modelSelection.getItems().isEmpty()) {
                    continue;
                }
                models = modelSelection.getItems();
            }

            if (models.isEmpty()) continue;

            FallbackTarget t = new FallbackTarget();
            t.setEnabled(true);
            t.setProvider(meta.getName());
            t.setModels(models);
            targets.add(t);
        }

        cfg.getAgents().getDefaults().getFallback().setTargets(targets);

        String maxAttemptsRaw = TerminalPrompts.promptText(
                reader,
                "失败尝试次数",
                String.valueOf(cfg.getAgents().getDefaults().getFallback().getMaxAttempts())
        );
        try {
            int n = Integer.parseInt(maxAttemptsRaw);
            if (n > 0) {
                cfg.getAgents().getDefaults().getFallback().setMaxAttempts(n);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 配置技能
     */
    private void configureSkills(Terminal terminal, Config cfg, boolean overwrite) {
        List<BuiltinSkillsInstaller.SkillResource> builtinSkills = BuiltinSkillsInstaller.discoverBuiltinSkills();

        if (builtinSkills.isEmpty()) {
            System.out.println("没有找到预构建的技能列表");
            return;
        }

        // QuickStart 默认安装所有技能
        if (flow == WizardFlow.QUICKSTART) {
            System.out.println("  快速模式：安装所有内置技能...");
            BuiltinSkillsInstaller.InstallSummary summary =
                    BuiltinSkillsInstaller.installSelectedSkills(
                            cfg.getWorkspacePath(),
                            builtinSkills,
                            overwrite
                    );
            BuiltinSkillsInstaller.printSummary(summary);
            return;
        }

        // Advanced 模式：让用户选择要安装的技能
        TerminalPrompts.SelectionResult<BuiltinSkillsInstaller.SkillResource> selection =
                BuiltinSkillsInstaller.promptSelection(terminal, builtinSkills);

        if (selection.isSkipped()) {
            System.out.println("跳过预构建的技能安装。");
            return;
        }

        BuiltinSkillsInstaller.InstallSummary summary =
                BuiltinSkillsInstaller.installSelectedSkills(
                        cfg.getWorkspacePath(),
                        selection.getItems(),
                        overwrite
                );

        BuiltinSkillsInstaller.printSummary(summary);
    }

    /**
     * 完成提示
     */
    private void showCompletionMessage(Path configPath, Path workspace) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  ✓ 配置完成");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  配置文件: " + configPath);
        System.out.println("  工作空间: " + workspace);
        System.out.println();
        System.out.println("  🐱 javaclawbot is ready!");
        System.out.println();
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.println("  下一步：");
        System.out.println();
        System.out.println("  1. 确认模型配置: cat " + configPath);
        System.out.println("  2. 开始对话: javaclawbot agent -m \"Hello!\"");
        System.out.println("  3. 查看帮助: javaclawbot --help");
        System.out.println();
        System.out.println("  Shell 补全:");
        System.out.println("    javaclawbot completion --install");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private int indexOfProvider(List<ProviderCatalog.ProviderMeta> providers, String providerName) {
        if (providerName == null) return 0;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getName().equalsIgnoreCase(providerName)) {
                return i;
            }
        }
        return 0;
    }

    private int indexOfString(List<String> items, String value) {
        if (value == null) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equalsIgnoreCase(value)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 只创建基础目录，不创建模板文件
     */
    private static void ensureWorkspaceStructure(Path workspace) {
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {
        }

        try {
            Files.createDirectories(workspace.resolve("skills"));
        } catch (IOException ignored) {
        }

        try {
            Files.createDirectories(workspace.resolve("plugins"));
        } catch (IOException ignored) {
        }

        Path memoryDir = workspace.resolve("memory");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException ignored) {
        }

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.notExists(memoryFile)) {
            // 不在这里创建空文件，由 createWorkspaceTemplates() 从模板复制
        }

        /*Path historyFile = memoryDir.resolve("HISTORY.md");
        if (Files.notExists(historyFile)) {
            try {
                Files.writeString(historyFile, "", StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }*/
    }

    /**
     * 创建工作空间模板
     */
    public static void createWorkspaceTemplates(Path workspace) {
        try {
            Files.createDirectories(workspace);
        } catch (IOException ignored) {
        }

        try {
            Files.createDirectories(workspace.resolve("skills"));
        } catch (IOException ignored) {
        }

        try {
            Files.createDirectories(workspace.resolve("plugins"));
        } catch (IOException ignored) {
        }

        Path memoryDir = workspace.resolve("memory");
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException ignored) {
        }

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.notExists(memoryFile)) {
            // 不在这里创建空文件，由 createWorkspaceTemplates() 从模板复制
        }

        createBootstrapIfNeeded(workspace);

        copyTemplateIfMissing(workspace, "AGENTS.md");
        copyTemplateIfMissing(workspace, "SOUL.md");
        copyTemplateIfMissing(workspace, "TOOLS.md");
        copyTemplateIfMissing(workspace, "USER.md");
        copyTemplateIfMissing(workspace, "IDENTITY.md");
        copyTemplateIfMissing(workspace, "HEARTBEAT.md");

        // 复制 MEMORY.md 模板到 memory 目录
        copyMemoryTemplate(workspace);
    }

    /**
     * 从 resources/templates/ 复制模板文件到工作空间（如果不存在）
     */
    private static void copyTemplateIfMissing(Path workspace, String filename) {
        Path targetPath = workspace.resolve(filename);
        if (Files.exists(targetPath)) {
            return;
        }

        try (InputStream is = OnboardWizard.class.getClassLoader().getResourceAsStream("templates/" + filename)) {
            if (is != null) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                content = stripFrontMatter(content);
                Files.writeString(targetPath, content, StandardCharsets.UTF_8);
                System.out.println("  ✓ Created: " + filename);
            } else {
                System.err.println("  ⚠ 模板不存在: templates/" + filename);
            }
        } catch (Exception e) {
            System.err.println("  ⚠ Failed to create " + filename + ": " + e.getMessage());
        }
    }

    /**
     * 复制 MEMORY.md 模板到 memory 目录
     */
    private static void copyMemoryTemplate(Path workspace) {
        Path memoryDir = workspace.resolve("memory");
        Path memoryFile = memoryDir.resolve("MEMORY.md");

        if (Files.exists(memoryFile)) {
            return;
        }

        try (InputStream is = OnboardWizard.class.getClassLoader().getResourceAsStream("templates/memory/MEMORY.md")) {
            if (is != null) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(memoryFile, content, StandardCharsets.UTF_8);
                System.out.println("  ✓ Created: memory/MEMORY.md");
            } else {
                System.err.println("  ⚠ 模板不存在: templates/memory/MEMORY.md");
            }
        } catch (Exception e) {
            System.err.println("  ⚠ Failed to create memory/MEMORY.md: " + e.getMessage());
        }
    }

    /**
     * 移除 YAML front matter
     */
    private static String stripFrontMatter(String content) {
        if (!content.startsWith("---")) {
            return content;
        }
        int endIndex = content.indexOf("\n---", 3);
        if (endIndex == -1) {
            return content;
        }
        int start = endIndex + "\n---".length();
        String trimmed = content.substring(start);
        return trimmed.replaceFirst("^\\s+", "");
    }

    /**
     * 创建 BOOTSTRAP.md（仅在全新 workspace 时）
     */
    private static void createBootstrapIfNeeded(Path workspace) {
        Path bootstrapPath = workspace.resolve("BOOTSTRAP.md");

        if (Files.exists(bootstrapPath)) {
            return;
        }

        copyTemplateIfMissing(workspace, "BOOTSTRAP.md");
        /*try {
            Path userPath = workspace.resolve("USER.md");
            Path identityPath = workspace.resolve("IDENTITY.md");
            Path memoryDir = workspace.resolve("memory");

            boolean hasUserContent = false;

            // memory 目录是否已有内容
            if (Files.exists(memoryDir) && Files.isDirectory(memoryDir)) {
                try (var stream = Files.list(memoryDir)) {
                    long memoryFiles = stream
                            .filter(p -> p.toString().endsWith(".md"))
                            .count();
                    if (memoryFiles > 0) {
                        hasUserContent = true;
                    }
                }
            }

            // USER.md 是否已有真实内容
            if (!hasUserContent && Files.exists(userPath)) {
                String userContent = Files.readString(userPath, StandardCharsets.UTF_8);
                if (!userContent.isBlank()
                        && (!userContent.contains("_(your name)_"))
                        && (!userContent.contains("Name:") || userContent.replace("Name:", "").trim().length() > 0)) {
                    hasUserContent = true;
                }
            }

            // IDENTITY.md 是否已有真实内容
            if (!hasUserContent && Files.exists(identityPath)) {
                String identityContent = Files.readString(identityPath, StandardCharsets.UTF_8);
                if (!identityContent.isBlank()) {
                    hasUserContent = true;
                }
            }

            if (hasUserContent) {
                System.out.println("  Skipping BOOTSTRAP.md (workspace already initialized)");
                return;
            }

            copyTemplateIfMissing(workspace, "BOOTSTRAP.md");

        } catch (IOException e) {
            System.err.println("  ⚠ Failed to check workspace state: " + e.getMessage());
        }*/
    }
}