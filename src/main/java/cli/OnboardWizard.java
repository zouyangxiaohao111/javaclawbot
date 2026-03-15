package cli;

import config.ConfigIO;
import config.ConfigSchema;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import providers.ProviderCatalog;
import utils.Helpers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboard 向导
 * 
 * 对齐 OpenClaw 的 onboarding.ts 功能：
 * - 安全警告确认
 * - 模式选择（quickstart/advanced）
 * - 配置处理（keep/modify/reset）
 * - 提供商配置
 * - 备用模型配置（nanobot 特有，保留）
 * - 技能安装
 * - 工作空间模板
 * - Shell 补全提示
 */
public final class OnboardWizard {

    /** 向导模式 */
    public enum WizardFlow {
        QUICKSTART,  // 快速开始，使用默认值
        ADVANCED     // 高级模式，手动配置所有选项
    }

    /** 配置处理方式 */
    public enum ConfigAction {
        KEEP,      // 保留现有配置
        MODIFY,    // 修改配置
        RESET      // 重置配置
    }

    private WizardFlow flow = WizardFlow.QUICKSTART;
    private boolean acceptRisk = false;

    public void run() {
        run(new String[0]);
    }

    /**
     * 运行向导
     * @param args 命令行参数
     */
    public void run(String[] args) {
        parseArgs(args);

        Path configPath = ConfigIO.getConfigPath();
        ConfigAction configAction = ConfigAction.KEEP;

        // ========== 安全警告确认 ==========
        // 对齐 OpenClaw 的 requireRiskAcknowledgement
        if (!acceptRisk) {
            if (!showRiskAcknowledgement()) {
                System.out.println("已取消配置向导。");
                return;
            }
        }

        // ========== 模式选择 ==========
        // 对齐 OpenClaw 的 flow 选择
        if (flow == null) {
            flow = selectWizardFlow();
        }

        // ========== 配置处理 ==========
        // 对齐 OpenClaw 的 config handling
        if (Files.exists(configPath)) {
            configAction = handleExistingConfig(configPath);
            if (configAction == null) {
                return; // 用户取消
            }
        }

        ConfigSchema.Config cfg;
        if (Files.exists(configPath) && configAction == ConfigAction.RESET) {
            cfg = new ConfigSchema.Config();
        } else {
            cfg = ConfigIO.loadConfig(null);
        }

        Path workspace = Helpers.getWorkspacePath(null);
        cfg.setWorkspacePath(workspace);

        // ========== 创建工作空间模板 ==========
        createWorkspaceTemplates(workspace);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            int totalSteps = flow == WizardFlow.QUICKSTART ? 2 : 3;
            int currentStep = 1;

            // ========== 配置提供商 ==========
            System.out.println();
            System.out.println("第一步 " + currentStep++ + "/" + totalSteps + "  配置提供商");
            configurePrimaryProvider(terminal, reader, cfg);

            // ========== 配置 Channel ==========
            // 对齐 OpenClaw 的 setupChannels
            System.out.println();
            System.out.println("第" + (flow == WizardFlow.ADVANCED ? "二" : "一") + "步 " + currentStep++ + "/" + (totalSteps + 1) + "  配置 Channel");
            ChannelConfigurator.configureChannels(terminal, reader, cfg, flow == WizardFlow.ADVANCED);

            // ========== 配置备用模型 ==========
            // nanobot 特有功能，保留
            if (flow == WizardFlow.ADVANCED) {
                System.out.println();
                System.out.println("第三步 " + currentStep++ + "/" + (totalSteps + 1) + "  配置备用模型");
                System.out.println("(当主模型不可用时,自动请求备用模型)");
                configureFallback(terminal, reader, cfg);
            }

            // ========== 配置技能 ==========
            System.out.println();
            System.out.println("第" + (flow == WizardFlow.ADVANCED ? "四" : "二") + "步 " + currentStep + "/" + (totalSteps + 1) + "  配置技能");
            configureSkills(terminal, cfg, configAction == ConfigAction.RESET);

            // ========== 保存配置 ==========
            ConfigIO.saveConfig(cfg, null);

            // ========== 完成提示 ==========
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
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quickstart", "-q" -> flow = WizardFlow.QUICKSTART;
                case "--advanced", "-a" -> flow = WizardFlow.ADVANCED;
                case "--accept-risk" -> acceptRisk = true;
            }
        }
    }

    /**
     * 显示安全警告确认
     * 对齐 OpenClaw 的 requireRiskAcknowledgement
     */
    private boolean showRiskAcknowledgement() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  ⚠️  安全警告 — 请仔细阅读");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("nanobot 是一个个人 AI 助手，默认为单一可信操作边界。");
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
            return TerminalPrompts.promptConfirm(reader, 
                    "我理解这是个人默认设置，多用户使用需要安全加固。继续？", 
                    false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 选择向导模式
     * 对齐 OpenClaw 的 flow 选择
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
            return "Advanced".equals(selected) ? WizardFlow.ADVANCED : WizardFlow.QUICKSTART;
        } catch (Exception e) {
            return WizardFlow.QUICKSTART;
        }
    }

    /**
     * 处理现有配置
     * 对齐 OpenClaw 的 config handling
     */
    private ConfigAction handleExistingConfig(Path configPath) {
        System.out.println();
        System.out.println("检测到现有配置: " + configPath);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

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
            System.err.println("Failed to handle existing config: " + e.getMessage());
        }

        return ConfigAction.KEEP;
    }

    private void configurePrimaryProvider(Terminal terminal, LineReader reader, ConfigSchema.Config cfg) {
        List<ProviderCatalog.ProviderMeta> providers = ProviderCatalog.supportedProviders();
        String currentProvider = cfg.getAgents().getDefaults().getProvider();
        int defaultIndex = indexOfProvider(providers, currentProvider);

        // QuickStart 模式：如果已有配置，直接使用
        if (flow == WizardFlow.QUICKSTART && currentProvider != null && !currentProvider.isBlank()) {
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
        ConfigSchema.ProviderConfig providerConfig = cfg.getProviders().getByName(providerName);
        if (providerConfig == null) return;

        if (selected.isSupportsApiBase()) {
            String defaultBase = (providerConfig.getApiBase() != null && !providerConfig.getApiBase().isBlank())
                    ? providerConfig.getApiBase()
                    : selected.getDefaultApiBase();
            
            if (flow == WizardFlow.ADVANCED) {
                providerConfig.setApiBase(TerminalPrompts.promptText(reader, "API base", defaultBase));
            } else if (defaultBase != null && !defaultBase.isBlank()) {
                providerConfig.setApiBase(defaultBase);
            }
        }

        if (selected.isSupportsApiKey()) {
            String existingKey = providerConfig.getApiKey();
            if (flow == WizardFlow.QUICKSTART && existingKey != null && !existingKey.isBlank()) {
                System.out.println("  使用现有 API key");
            } else {
                providerConfig.setApiKey(TerminalPrompts.promptSecret(reader, "API key", existingKey));
            }
        }

        String model;
        if (selected.isManualModelOnly() || selected.getRecommendedModels().isEmpty()) {
            model = TerminalPrompts.promptText(
                    reader,
                    "默认模型",
                    cfg.getAgents().getDefaults().getModel()
            );
        } else {
            // 构建选项列表：推荐模型 + 手动输入选项
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
            
            if (chosenModel != null && chosenModel.equals("✏️ 手动输入模型名称")) {
                // 用户选择手动输入
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

    private void configureFallback(Terminal terminal, LineReader reader, ConfigSchema.Config cfg) {
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

        List<ConfigSchema.FallbackTarget> targets = new ArrayList<>();

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

            ConfigSchema.FallbackTarget t = new ConfigSchema.FallbackTarget();
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
            if (n > 0) cfg.getAgents().getDefaults().getFallback().setMaxAttempts(n);
        } catch (Exception ignored) {}
    }

    private void configureSkills(Terminal terminal, ConfigSchema.Config cfg, boolean overwrite) {
        List<BuiltinSkillsInstaller.SkillResource> builtinSkills = BuiltinSkillsInstaller.discoverBuiltinSkills();

        if (builtinSkills.isEmpty()) {
            System.out.println("没有找到预构建的技能列表");
            return;
        }

        // QuickStart 模式：跳过技能选择，使用默认
        if (flow == WizardFlow.QUICKSTART) {
            System.out.println("  跳过技能安装（可稍后使用 'nanobot skills' 安装）");
            return;
        }

        TerminalPrompts.SelectionResult<BuiltinSkillsInstaller.SkillResource> selection =
                BuiltinSkillsInstaller.promptSelection(terminal, builtinSkills);

        if (selection.isSkipped()) {
            System.out.println("跳过预构建的技能安装.");
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
     * 显示完成消息
     * 对齐 OpenClaw 的 finalizeOnboardingWizard
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
        System.out.println("  🐈 nanobot is ready!");
        System.out.println();
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.println("  下一步：");
        System.out.println();
        System.out.println("  1. 确认模型配置: cat ~/.nanobot/config.json");
        System.out.println("  2. 开始对话: nanobot agent -m \"Hello!\"");
        System.out.println("  3. 查看帮助: nanobot --help");
        System.out.println();
        System.out.println("  Shell 补全:");
        System.out.println("    nanobot completion --install");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private int indexOfProvider(List<ProviderCatalog.ProviderMeta> providers, String providerName) {
        if (providerName == null) return 0;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getName().equalsIgnoreCase(providerName)) return i;
        }
        return 0;
    }

    private int indexOfString(List<String> items, String value) {
        if (value == null) return 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equalsIgnoreCase(value)) return i;
        }
        return 0;
    }

    private static void createWorkspaceTemplates(Path workspace) {
        try { Files.createDirectories(workspace); } catch (IOException ignored) {}

        try {
            Path skills = workspace.resolve("skills");
            Files.createDirectories(skills);
        } catch (IOException ignored) {}

        Path memoryDir = workspace.resolve("memory");
        try { Files.createDirectories(memoryDir); } catch (IOException ignored) {}

        Path memoryFile = memoryDir.resolve("MEMORY.md");
        if (Files.notExists(memoryFile)) {
            try {
                Files.writeString(memoryFile, "");
            } catch (IOException ignored) {}
        }

        Path historyFile = memoryDir.resolve("HISTORY.md");
        if (Files.notExists(historyFile)) {
            try {
                Files.writeString(historyFile, "");
            } catch (IOException ignored) {}
        }

        // 复制模板文件到工作空间
        // 对齐 OpenClaw 的 onboard 流程
        copyTemplateIfMissing(workspace, "AGENTS.md");
        copyTemplateIfMissing(workspace, "SOUL.md");
        copyTemplateIfMissing(workspace, "TOOLS.md");
        copyTemplateIfMissing(workspace, "USER.md");
        copyTemplateIfMissing(workspace, "IDENTITY.md");
        copyTemplateIfMissing(workspace, "HEARTBEAT.md");
        
        // BOOTSTRAP.md: 仅在全新 workspace 时创建
        createBootstrapIfNeeded(workspace);
    }

    /**
     * 从 resources/templates/ 复制模板文件到工作空间（如果不存在）
     */
    private static void copyTemplateIfMissing(Path workspace, String filename) {
        Path targetPath = workspace.resolve(filename);
        if (Files.exists(targetPath)) {
            return; // 已存在，不覆盖
        }

        try {
            // 从 classpath 读取模板
            var is = OnboardWizard.class.getClassLoader().getResourceAsStream("templates/" + filename);
            if (is != null) {
                String content = new String(is.readAllBytes());
                // 移除 YAML front matter（如果存在）
                content = stripFrontMatter(content);
                Files.writeString(targetPath, content);
                System.out.println("  ✓ Created: " + filename);
            }
        } catch (Exception e) {
            System.err.println("  ⚠ Failed to create " + filename + ": " + e.getMessage());
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
     * 对齐 OpenClaw 的逻辑：
     * - 如果 USER.md 或 IDENTITY.md 已被修改，则跳过
     * - 如果 memory 目录已存在内容，则跳过
     */
    private static void createBootstrapIfNeeded(Path workspace) {
        Path bootstrapPath = workspace.resolve("BOOTSTRAP.md");
        
        // 如果已存在，不重复创建
        if (Files.exists(bootstrapPath)) {
            return;
        }
        
        // 检查是否是遗留 workspace（已有用户内容）
        try {
            Path userPath = workspace.resolve("USER.md");
            Path memoryDir = workspace.resolve("memory");
            
            boolean hasUserContent = false;
            
            // 检查 memory 目录是否有内容
            if (Files.exists(memoryDir) && Files.isDirectory(memoryDir)) {
                long memoryFiles = Files.list(memoryDir)
                        .filter(p -> p.toString().endsWith(".md"))
                        .count();
                if (memoryFiles > 0) {
                    hasUserContent = true;
                }
            }
            
            // 检查 USER.md 是否有用户内容（非空且非模板）
            if (Files.exists(userPath)) {
                String userContent = Files.readString(userPath);
                // 简单检查：如果包含实际内容（非模板占位符）
                if (userContent.contains("Name:") && !userContent.contains("_(your name)_")) {
                    hasUserContent = true;
                }
            }
            
            // 如果已有用户内容，不创建 BOOTSTRAP.md
            if (hasUserContent) {
                System.out.println("  ℹ Skipping BOOTSTRAP.md (workspace already initialized)");
                return;
            }
            
            // 创建 BOOTSTRAP.md
            copyTemplateIfMissing(workspace, "BOOTSTRAP.md");
            
        } catch (IOException e) {
            System.err.println("  ⚠ Failed to check workspace state: " + e.getMessage());
        }
    }
}