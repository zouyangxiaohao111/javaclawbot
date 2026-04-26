package agent.tool;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Base class for agent tools.
 *
 * Tools are capabilities that the agent can use to interact with
 * the environment, such as reading files, executing commands, etc.
 *
 * This is a Java port of javaclawbot/agent/tools/base.py
 */
public abstract class Tool {

    // JSON Schema simple type mapping
    private static final Map<String, List<Class<?>>> TYPE_MAP = Map.of(
            "string", List.of(String.class),
            "integer", List.of(Integer.class, Long.class, Short.class, Byte.class),
            "number", List.of(Number.class),
            "boolean", List.of(Boolean.class),
            "array", List.of(List.class),
            "object", List.of(Map.class)
    );

    /** Tool name used in function calls. */
    public abstract String name();

    /** Description of what the tool does. */
    public abstract String description();

    /** JSON Schema for tool parameters. Must be an object schema. */
    public abstract Map<String, Object> parameters();

    /**
     * Execute the tool with given parameters.
     * Return a string result of the tool execution.
     */
    public CompletionStage<String> execute(Map<String, Object> args, ToolUseContext parentUseContext) {
        return execute(args);
    }

    public abstract CompletionStage<String> execute(Map<String, Object> args);

    /**
     * Validate tool parameters against JSON schema. Returns error list (empty if valid).
     * Mirrors Python validate_params().
     */
    public List<String> validateParams(Map<String, Object> params) {
        Map<String, Object> schema = parameters();
        if (schema == null) schema = Map.of();

        Object t = schema.getOrDefault("type", "object");
        if (!"object".equals(String.valueOf(t))) {
            throw new IllegalArgumentException("Schema must be object type, got " + t);
        }

        // Ensure root is treated as object
        Map<String, Object> rootSchema = new LinkedHashMap<>(schema);
        rootSchema.put("type", "object");
        return validateAny(params, rootSchema, "");
    }

    @SuppressWarnings("unchecked")
    private List<String> validateAny(Object val, Map<String, Object> schema, String path) {
        String t = schema.get("type") == null ? null : String.valueOf(schema.get("type"));
        String label = (path == null || path.isBlank()) ? "parameter" : path;

        List<String> errors = new ArrayList<>();

        // type check
        if (t != null && TYPE_MAP.containsKey(t)) {
            if (!isInstanceOfSchemaType(val, t)) {
                errors.add(label + " should be " + t);
                // In Python, it returns immediately for type mismatch
                return errors;
            }
        }

        // enum check
        Object enumObj = schema.get("enum");
        if (enumObj instanceof List<?> enumList) {
            if (!enumList.contains(val)) {
                errors.add(label + " must be one of " + enumList);
            }
        }

        // number constraints
        if ("integer".equals(t) || "number".equals(t)) {
            if (val instanceof Number num) {
                if (schema.containsKey("minimum")) {
                    double min = asDouble(schema.get("minimum"));
                    if (num.doubleValue() < min) errors.add(label + " must be >= " + min);
                }
                if (schema.containsKey("maximum")) {
                    double max = asDouble(schema.get("maximum"));
                    if (num.doubleValue() > max) errors.add(label + " must be <= " + max);
                }
            }
        }

        // string constraints
        if ("string".equals(t) && val instanceof String s) {
            if (schema.containsKey("minLength")) {
                int min = asInt(schema.get("minLength"));
                if (s.length() < min) errors.add(label + " must be at least " + min + " chars");
            }
            if (schema.containsKey("maxLength")) {
                int max = asInt(schema.get("maxLength"));
                if (s.length() > max) errors.add(label + " must be at most " + max + " chars");
            }
        }

        // object properties + required
        if ("object".equals(t) && val instanceof Map<?, ?> mapVal) {
            Map<String, Object> props = asMap(schema.get("properties"));

            List<String> required = new ArrayList<>();
            Object reqObj = schema.get("required");
            if (reqObj instanceof List<?> reqList) {
                for (Object r : reqList) required.add(String.valueOf(r));
            }
            for (String k : required) {
                if (!mapVal.containsKey(k)) {
                    String p = (path == null || path.isBlank()) ? k : (path + "." + k);
                    errors.add("missing required " + p);
                }
            }

            for (Map.Entry<?, ?> e : mapVal.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (props.containsKey(k)) {
                    Object subSchemaObj = props.get(k);
                    if (subSchemaObj instanceof Map<?, ?>) {
                        Map<String, Object> subSchema = (Map<String, Object>) subSchemaObj;
                        String subPath = (path == null || path.isBlank()) ? k : (path + "." + k);
                        errors.addAll(validateAny(e.getValue(), subSchema, subPath));
                    }
                }
            }
        }

        // array items
        if ("array".equals(t) && val instanceof List<?> listVal) {
            Object itemsObj = schema.get("items");
            if (itemsObj instanceof Map<?, ?>) {
                Map<String, Object> itemsSchema = (Map<String, Object>) itemsObj;
                for (int i = 0; i < listVal.size(); i++) {
                    String subPath = (path == null || path.isBlank()) ? ("[" + i + "]") : (path + "[" + i + "]");
                    errors.addAll(validateAny(listVal.get(i), itemsSchema, subPath));
                }
            }
        }

        return errors;
    }

    private boolean isInstanceOfSchemaType(Object val, String schemaType) {
        if (val == null) return true; // Python version would fail isinstance(None, ...) but tools often allow nulls; keep permissive
        if ("number".equals(schemaType)) return (val instanceof Number);
        if ("integer".equals(schemaType)) return (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte);
        if ("array".equals(schemaType)) return (val instanceof List);
        if ("object".equals(schemaType)) return (val instanceof Map);
        if ("string".equals(schemaType)) return (val instanceof String);
        if ("boolean".equals(schemaType)) return (val instanceof Boolean);
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    private double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }

    /**
     * Convert tool to OpenAI function schema format.
     * Mirrors Python to_schema().
     */
    public Map<String, Object> toSchema() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name());
        fn.put("description", description());
        fn.put("parameters", parameters());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "function");
        out.put("function", fn);
        return out;
    }

    /**
     * 工具结果最大字符数，超出后持久化到磁盘。
     * 子类可覆写（如自行管理大小限制的工具可返回 Integer.MAX_VALUE）。
     * 默认值 50,000 字符（对齐 Claude Code 的 DEFAULT_MAX_RESULT_SIZE_CHARS）。
     */
    public int maxResultSizeChars() {
        return 50_000;
    }

    /** Convenience: default execute() wrapper when you want to validate first. */
    public CompletionStage<String> executeValidated(Map<String, Object> args) {
        List<String> errors = validateParams(args == null ? Map.of() : args);
        if (!errors.isEmpty()) {
            return CompletableFuture.completedFuture("Error: invalid parameters:\n- " + String.join("\n- ", errors));
        }
        return execute(args == null ? Map.of() : args);
    }
}
