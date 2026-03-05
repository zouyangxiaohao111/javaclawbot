package providers;

import java.util.Map;

/**
 * 工具调用请求
 *
 * 对齐 Python:
 * - id: str
 * - name: str
 * - arguments: dict[str, Any]
 */
public final class ToolCallRequest {

    private String id;
    private String name;
    private Map<String, Object> arguments;

    public ToolCallRequest() {}

    public ToolCallRequest(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    /**
     * 工具调用 ID
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * 工具名称
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * 工具参数（JSON 对象）
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }
}