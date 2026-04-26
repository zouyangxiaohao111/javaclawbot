package agent.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * 对多个 ToolRegistry 的组合视图。
 * <p>
 * 约定：
 * - 后面的 registry 优先级更高，会覆盖前面同名工具
 * - 因此建议传入顺序：
 * shared, mcp, local
 * 最终优先级：local > mcp > shared
 */
public interface ToolView {
    List<Map<String, Object>> getDefinitions();

    CompletionStage<String> execute(String name, Map<String, Object> args, ToolUseContext parentUseContext);

    Tool get(String name);
    List<Tool> getTools();

    void addTool(Tool tool);

    /**
     * 获取指定工具的最大结果字符数。
     * 如果工具不存在，返回默认值 50_000。
     */
    default int maxResultSizeChars(String name) {
        Object tool = get(name);
        if (tool instanceof Tool t) {
            return t.maxResultSizeChars();
        }
        return 50_000;
    }
}