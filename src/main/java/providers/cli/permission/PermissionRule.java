package providers.cli.permission;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 权限规则
 */
public class PermissionRule {

    private final String name;
    private final RuleType type;
    private final Set<String> toolNames;
    private final List<Pattern> bashPatterns;
    private final String message;
    private final Map<String, Object> updatedInput;

    public PermissionRule(String name, RuleType type, Set<String> toolNames,
                          List<Pattern> bashPatterns, String message,
                          Map<String, Object> updatedInput) {
        this.name = name;
        this.type = type;
        this.toolNames = toolNames != null ? new HashSet<>(toolNames) : Set.of();
        this.bashPatterns = bashPatterns != null ? new ArrayList<>(bashPatterns) : List.of();
        this.message = message;
        this.updatedInput = updatedInput;
    }

    public String getName() {
        return name;
    }

    public RuleType getType() {
        return type;
    }

    /**
     * 检查规则是否匹配请求
     */
    public boolean matches(PermissionRequest request) {
        String toolName = request.toolName();

        // 检查工具名匹配
        if (!toolNames.isEmpty()) {
            boolean toolMatched = false;
            for (String pattern : toolNames) {
                if (pattern.equals("*") || pattern.equalsIgnoreCase(toolName)) {
                    toolMatched = true;
                    break;
                }
                // 支持通配符
                if (pattern.contains("*")) {
                    String regex = pattern.replace("*", ".*");
                    if (toolName.matches("(?i)" + regex)) {
                        toolMatched = true;
                        break;
                    }
                }
            }
            if (!toolMatched) {
                return false;
            }
        }

        // 如果是 Bash 工具，检查命令模式
        if ("Bash".equalsIgnoreCase(toolName) && !bashPatterns.isEmpty()) {
            String command = extractCommand(request);
            if (command != null) {
                for (Pattern pattern : bashPatterns) {
                    if (pattern.matcher(command).find()) {
                        return true;
                    }
                }
            }
            // 如果指定了 bash 模式但不匹配，则此规则不适用
            return false;
        }

        // 工具名匹配且不是 Bash，或者没有指定 bash 模式
        return !toolNames.isEmpty() || !bashPatterns.isEmpty() ||
                (toolNames.isEmpty() && bashPatterns.isEmpty());
    }

    /**
     * 获取规则的决策
     */
    public PermissionDecision getDecision() {
        return switch (type) {
            case ALLOW -> PermissionDecision.allow(updatedInput);
            case DENY -> PermissionDecision.deny(message);
            case ASK_USER -> PermissionDecision.askUser(message);
        };
    }

    /**
     * 从请求中提取 Bash 命令
     */
    private String extractCommand(PermissionRequest request) {
        if (request.toolInput() == null) {
            return null;
        }
        Object command = request.toolInput().get("command");
        if (command instanceof String) {
            return (String) command;
        }
        return null;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建允许指定工具的规则
     */
    public static PermissionRule allowTools(String name, String... toolNames) {
        return new PermissionRule(name, RuleType.ALLOW,
                Set.of(toolNames), null, null, null);
    }

    /**
     * 创建允许指定工具的规则
     */
    public static PermissionRule allowTools(String name, Collection<String> toolNames) {
        return new PermissionRule(name, RuleType.ALLOW,
                new HashSet<>(toolNames), null, null, null);
    }

    /**
     * 创建允许所有工具的规则
     */
    public static PermissionRule allowAll(String name) {
        return new PermissionRule(name, RuleType.ALLOW,
                Set.of("*"), null, null, null);
    }

    /**
     * 创建允许 Bash 的规则
     */
    public static PermissionRule allowBash(String name) {
        return new PermissionRule(name, RuleType.ALLOW,
                Set.of("Bash"), null, null, null);
    }

    /**
     * 创建拒绝指定工具的规则
     */
    public static PermissionRule denyTools(String name, String... toolNames) {
        return new PermissionRule(name, RuleType.DENY,
                Set.of(toolNames), null, "Tool not allowed: " + String.join(", ", toolNames), null);
    }

    /**
     * 创建拒绝 Bash 模式的规则
     */
    public static PermissionRule denyBash(String name, String... patterns) {
        List<Pattern> compiledPatterns = Arrays.stream(patterns)
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .collect(Collectors.toList());
        return new PermissionRule(name, RuleType.DENY,
                Set.of("Bash"), compiledPatterns,
                "Dangerous command pattern detected", null);
    }

    /**
     * 创建询问用户的规则
     */
    public static PermissionRule askUser(String name, String message, String... toolNames) {
        return new PermissionRule(name, RuleType.ASK_USER,
                toolNames.length > 0 ? Set.of(toolNames) : Set.of("*"),
                null, message, null);
    }

    /**
     * 规则类型
     */
    public enum RuleType {
        ALLOW,
        DENY,
        ASK_USER
    }
}
