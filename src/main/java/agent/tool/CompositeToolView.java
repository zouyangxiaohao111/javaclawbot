package agent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Slf4j
public final class CompositeToolView implements ToolView {
    private final List<ToolRegistry> registries;

    public CompositeToolView(ToolRegistry... registries) {
        this.registries = new ArrayList<>();
        for (ToolRegistry registry : registries) {
            if (registry == null) {
                continue;
            }
            this.registries.add(registry); // Added once
        }
        log.info("创建复合工具视图，共 {} 个注册表", this.registries.size());
    }

    @Override
    public List<Map<String, Object>> getDefinitions() {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        for (ToolRegistry registry : registries) {
            if (registry == null) continue;

            for (Map<String, Object> def : registry.getDefinitions()) {
                String name = extractToolName(def);
                if (name != null) {
                    merged.put(name, def); // 后写覆盖前写
                }
            }
        }

        //log.debug("获取复合工具定义，合并后共 {} 个工具", merged.size());
        return new ArrayList<>(merged.values());
    }

    @Override
    public CompletionStage<String> execute(String name, Map<String, Object> args, ToolUseContext parentUseContext) {
        for (int i = registries.size() - 1; i >= 0; i--) {
            ToolRegistry registry = registries.get(i);
            if (registry != null && registry.get(name) != null) {
                log.debug("在注册表[{}]中找到工具: {}", i, name);
                return registry.execute(name, args, parentUseContext);
            }
        }
        log.warn("工具未找到: {}", name);
        return CompletableFuture.completedFuture(
                "Error: Tool '" + name + "' not found."
        );
    }

    @Override
    public Tool get(String name) {
        for (int i = registries.size() - 1; i >= 0; i--) {
            ToolRegistry registry = registries.get(i);
            if (registry != null) {
                Tool tool = registry.get(name);
                if (tool != null) {
                    return tool;
                }
            }
        }
        return null;
    }

    @Override
    public List<Tool> getTools() {
        List<Tool> tools = new ArrayList<>();
        for (int i = registries.size() - 1; i >= 0; i--) {
            ToolRegistry registry = registries.get(i);
            tools.addAll(registry.getTools());
        }
        return tools;
    }

    @Override
    public void addTool(Tool tool) {
        if (registries.isEmpty()) {
            registries.add(new ToolRegistry());
            log.info("创建新的工具注册表");
        }
        this.registries.get(registries.size() - 1).register(tool);
    }

    @SuppressWarnings("unchecked")
    private String extractToolName(Map<String, Object> def) {
        if (def == null) return null;
        Object fn = def.get("function");
        if (fn instanceof Map<?, ?> map) {
            Object name = map.get("name");
            return name == null ? null : String.valueOf(name);
        }
        return null;
    }
}
