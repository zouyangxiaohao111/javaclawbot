package agent.tool;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具注册表：用于动态管理与执行工具。
 *
 * 行为对齐 Python 源码：
 * - register/unregister/get/has
 * - get_definitions：返回 [tool.to_schema()]
 * - execute：
 *   - 找不到工具：返回错误 + 可用工具列表
 *   - validate_params 有错误：返回错误 + HINT
 *   - 执行工具：捕获异常并返回错误 + HINT
 *   - 若执行结果是字符串且以 "Error" 开头：追加 HINT
 *
 * 说明：
 * - Python 使用普通 dict，这里用 LinkedHashMap 保持插入顺序（更稳定的输出顺序）
 * - Python 的 execute 是 async，这里使用 CompletableFuture 实现异步链路
 * - Python 的 tool.execute(**params) 需要把 params 作为关键字参数展开；
 *   Java 侧 Tool 基类签名为 execute(Map<String,Object> args)，等价于传入 params map
 */
@Slf4j
public class ToolRegistry {

    /**
     * 当工具校验或执行失败时，追加的提示文本（与 Python 一致）
     */
    private static final String HINT = "\n\n[Analyze the error above and try a different approach.]";

    /**
     * 工具容器（对齐 Python: self._tools: dict[str, Tool]）
     */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 注册工具（对齐 Python: register）
     *
     * @param tool 工具实例
     */
    public void register(Tool tool) {
        if (tool == null) return;
        tools.put(tool.name(), tool);
        //log.info("注册工具: {}", tool.name());
    }

    /**
     * 按名称注销工具（对齐 Python: unregister）
     *
     * @param name 工具名称
     */
    public void unregister(String name) {
        if (name == null) return;
        tools.remove(name);
        log.info("注销工具: {}", name);
    }

    /**
     * 获取工具（对齐 Python: get）
     *
     * @param name 工具名称
     * @return 工具或 null
     */
    public Tool get(String name) {
        if (name == null) return null;
        return tools.get(name);
    }

    /**
     * 判断工具是否存在（对齐 Python: has）
     *
     * @param name 工具名称
     * @return 是否已注册
     */
    public boolean has(String name) {
        if (name == null) return false;
        return tools.containsKey(name);
    }

    public Collection<? extends Tool> getTools() {
        return tools.values();
    }

    /**
     * 获取所有工具定义（对齐 Python: get_definitions）
     *
     * @return OpenAI tools schema 列表
     */
    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            out.add(t.toSchema());
        }
        /*if (log.isDebugEnabled()){
            log.debug("获取工具定义，共 {} 个工具", out.size());
        }*/
        return out;
    }

    /**
     * 执行指定工具（对齐 Python: execute）
     *
     * @param name   工具名
     * @param params 工具参数（可为空）
     * @return 工具执行结果（字符串）
     */
    public CompletableFuture<String> execute(String name, Map<String, Object> params, ToolUseContext parentUseContext) {
        Tool tool = tools.get(name);
        if (tool == null) {
            // 对齐 Python：报错并给出可用工具名列表
            log.warn("工具未找到: {}, 可用工具: {}", name, String.join(", ", toolNames()));
            return CompletableFuture.completedFuture(
                    "Error: Tool '" + name + "' not found. Available: " + String.join(", ", toolNames())
            );
        }

        // 对齐 Python：params 必须是 dict；这里允许 null 并转为空 map
        // 做一次防御性拷贝，避免不可变 Map（如 Map.of()）或第三方 Map 实现引发并发/兼容问题
        Map<String, Object> safeParams;
        if (params == null) {
            safeParams = new LinkedHashMap<>();
        } else {
            safeParams = new LinkedHashMap<>(params);
        }

        log.info("执行工具: {}, 参数: {}", name, safeParams);

        try {
            // 参数校验（对齐 Python: validate_params）
            List<String> errors = tool.validateParams(safeParams);
            if (errors != null && !errors.isEmpty()) {
                log.warn("工具参数校验失败: {}, 错误: {}", name, String.join("; ", errors));
                return CompletableFuture.completedFuture(
                        "Error: Invalid parameters for tool '" + name + "': "
                                + String.join("; ", errors)
                                + HINT
                );
            }

            // 执行工具（对齐 Python: await tool.execute(**params)）
            // Java：Tool.execute(Map) 返回 CompletionStage<String>，这里转 CompletableFuture 方便链式处理
            return tool.execute(safeParams, parentUseContext)
                    .handle((result, ex) -> {
                        // 对齐 Python：异常进入 except，返回 Error executing ... + HINT
                        if (ex != null) {
                            log.error("工具执行失败: {}, 错误: {}", name, ex.getMessage(), ex);
                            return "Error executing " + name + ": " + ex.getMessage() + HINT;
                        }

                        // 对齐 Python：如果 result 是字符串且以 "Error" 开头，追加 HINT
                        if (result != null && result.startsWith("Error")) {
                            log.warn("工具执行返回错误结果: {}，错误：{}", name, result);
                            return result + HINT;
                        }

                        log.debug("工具执行成功: {}", name);
                        // Python 直接 return result（可能为非字符串，但该工程约定返回字符串）
                        return result;
                    })
                    .toCompletableFuture();

        } catch (Exception e) {
            // 对齐 Python：捕获异常，返回 Error executing ... + HINT
            log.error("工具执行异常: {}, 错误: {}", name, e.getMessage(), e);
            return CompletableFuture.completedFuture(
                    "Error executing " + name + ": " + e.getMessage() + HINT
            );
        }
    }

    /**
     * 获取工具名称列表（对齐 Python: tool_names 属性）
     *
     * @return 工具名称列表
     */
    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 工具数量（对齐 Python: __len__）
     *
     * @return 工具数量
     */
    public int size() {
        return tools.size();
    }

    /**
     * 是否包含某工具名（对齐 Python: __contains__）
     *
     * @param name 工具名
     * @return 是否存在
     */
    public boolean contains(String name) {
        if (name == null) return false;
        return tools.containsKey(name);
    }
}
