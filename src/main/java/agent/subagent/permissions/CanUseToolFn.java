package agent.subagent.permissions;

import agent.tool.Tool;
import agent.tool.ToolUseContext;

import java.util.Map;

/**
 * 工具权限检查函数
 *
 * 对应 Open-ClaudeCode: src/hooks/useCanUseTool.tsx - CanUseToolFn
 *
 * 用于检查代理是否有权限使用特定工具。
 * 返回权限决策结果。
 */
@FunctionalInterface
public interface CanUseToolFn {

    /**
     * 检查工具使用权限
     *
     * @param tool 工具对象
     * @param args 工具参数
     * @param context 工具使用上下文
     * @return 权限决策结果
     */
    PermissionDecision check(Tool tool, Map<String, Object> args, ToolUseContext context);

    /**
     * 权限决策结果
     */
    class PermissionDecision {
        private final PermissionBehavior behavior;
        private final String reason;

        public PermissionDecision(PermissionBehavior behavior) {
            this(behavior, null);
        }

        public PermissionDecision(PermissionBehavior behavior, String reason) {
            this.behavior = behavior;
            this.reason = reason;
        }

        public PermissionBehavior getBehavior() {
            return behavior;
        }

        public String getReason() {
            return reason;
        }

        public static PermissionDecision allow() {
            return new PermissionDecision(PermissionBehavior.ALLOW);
        }

        public static PermissionDecision allow(String reason) {
            return new PermissionDecision(PermissionBehavior.ALLOW, reason);
        }

        public static PermissionDecision deny() {
            return new PermissionDecision(PermissionBehavior.DENY);
        }

        public static PermissionDecision deny(String reason) {
            return new PermissionDecision(PermissionBehavior.DENY, reason);
        }

        public static PermissionDecision ask() {
            return new PermissionDecision(PermissionBehavior.ASK);
        }

        public static PermissionDecision ask(String reason) {
            return new PermissionDecision(PermissionBehavior.ASK, reason);
        }
    }

    /**
     * 权限行为枚举
     */
    enum PermissionBehavior {
        /** 允许执行 */
        ALLOW,
        /** 拒绝执行 */
        DENY,
        /** 需要询问用户 */
        ASK
    }
}
