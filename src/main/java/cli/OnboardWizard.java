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

public final class OnboardWizard {

    public void run() {
        Path configPath = ConfigIO.getConfigPath();
        boolean overwrite = false;

        if (Files.exists(configPath)) {
            try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

                System.out.println("配置已经存在: " + configPath);
                System.out.println("  y = 覆盖默认配置 (已配置的会被丢失)");
                System.out.println("  N = 刷新配置, 保持已有配置,并新增相关内容");

                overwrite = TerminalPrompts.promptConfirm(reader, "是否覆盖配置?", false);
            } catch (Exception e) {
                System.err.println("Failed to initialize terminal: " + e.getMessage());
                return;
            }
        }

        ConfigSchema.Config cfg;
        if (Files.exists(configPath) && overwrite) {
            cfg = new ConfigSchema.Config();
        } else {
            cfg = ConfigIO.loadConfig(null);
        }

        Path workspace = Helpers.getWorkspacePath(null);
        cfg.setWorkspacePath(workspace);

        createWorkspaceTemplates(workspace);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            System.out.println();
            System.out.println("第一步 1/3  配置提供商");

            configurePrimaryProvider(terminal, reader, cfg);

            System.out.println();
            System.out.println("第二步 2/3  配置备用模型(当主模型不可用时,自动请求备用模型)");

            configureFallback(terminal, reader, cfg);

            System.out.println();
            System.out.println("Step 3/3  配置技能");

            configureSkills(terminal, cfg, overwrite);

            ConfigIO.saveConfig(cfg, null);

            System.out.println();
            System.out.println("✓ 配置已存储在: " + configPath);
            System.out.println("✓ 工作空间已准备好: " + workspace);
            System.out.println();
            System.out.println("🐈 nanobot is ready!");
            System.out.println();
            System.out.println("下一步可以:");
            System.out.println("  1. 确认模型已经配置好,在 ~/.nanobot/config.json");
            System.out.println("  2. 对话: nanobot agent -m \"Hello!\"");

        } catch (Exception e) {
            System.err.println("Onboard wizard failed: " + e.getMessage());
        }
    }

    private void configurePrimaryProvider(Terminal terminal, LineReader reader, ConfigSchema.Config cfg) {
        List<ProviderCatalog.ProviderMeta> providers = ProviderCatalog.supportedProviders();
        String currentProvider = cfg.getAgents().getDefaults().getProvider();
        int defaultIndex = indexOfProvider(providers, currentProvider);

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
            providerConfig.setApiBase(TerminalPrompts.promptText(reader, "API base", defaultBase));
        }

        if (selected.isSupportsApiKey()) {
            providerConfig.setApiKey(TerminalPrompts.promptSecret(reader, "API key", providerConfig.getApiKey()));
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
    }
}