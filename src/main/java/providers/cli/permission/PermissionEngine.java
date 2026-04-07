package providers.cli.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 权限引擎 - 自动处理 CLI Agent 的权限请求
 *
 * 规则优先级:
 * 1. 按添加顺序匹配
 * 2. 第一个匹配的规则生效
 * 3. 如果没有规则匹配，使用默认策略
 */
public class PermissionEngine {

    private static final Logger log = LoggerFactory.getLogger(PermissionEngine.class);

    private final List<PermissionRule> rules = new ArrayList<>();
    private PermissionDecision defaultDecision = PermissionDecision.deny("No matching rule found");

    public PermissionEngine() {
    }

    /**
     * 添加规则 (添加到列表末尾)
     */
    public PermissionEngine addRule(PermissionRule rule) {
        rules.add(rule);
        return this;
    }

    /**
     * 添加规则 (添加到列表开头，优先级更高)
     */
    public PermissionEngine addRuleFirst(PermissionRule rule) {
        rules.add(0, rule);
        return this;
    }

    /**
     * 设置默认决策
     */
    public PermissionEngine setDefaultDecision(PermissionDecision decision) {
        this.defaultDecision = decision;
        return this;
    }

    /**
     * 清除所有规则
     */
    public PermissionEngine clearRules() {
        rules.clear();
        return this;
    }

    /**
     * 判断权限请求
     *
     * @param request 权限请求
     * @return 权限决策
     */
    public PermissionDecision decide(PermissionRequest request) {
        for (PermissionRule rule : rules) {
            if (rule.matches(request)) {
                PermissionDecision decision = rule.getDecision();
                log.debug("Permission rule matched: {} -> {} for {}",
                        rule.getName(), decision.behavior(), request.toolName());
                return decision;
            }
        }

        log.debug("No rule matched for {}, using default: {}",
                request.toolName(), defaultDecision.behavior());
        return defaultDecision;
    }

    /**
     * 获取所有规则
     */
    public List<PermissionRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * 创建默认权限引擎
     *
     * 默认规则:
     * - 文件读取工具 -> auto allow
     * - 文件编辑工具 -> auto allow
     * - 危险 Bash 命令 -> auto deny
     * - 其他工具 -> auto allow
     */
    public static PermissionEngine createDefault() {
        PermissionEngine engine = new PermissionEngine();

        // 1. 危险 Bash 命令 - 自动拒绝 (最高优先级)
        engine.addRule(PermissionRule.denyBash(
                "Dangerous bash patterns",
                "rm\\s+-rf",
                "rm\\s+-fr",
                "rmdir\\s+",
                "format\\s+",
                "mkfs\\.",
                "dd\\s+if=",
                "sudo\\s+rm",
                "\\:\\(\\)\\{\\s*:\\|\\:&\\s*\\}\\s*;\\s*:", // fork bomb
                ">\\s*/dev/sd", // 覆盖磁盘
                "chmod\\s+-R\\s+777", // 危险权限
                "chown\\s+-R\\s+"
        ));

        // 2. 文件读取工具 - 自动允许
        engine.addRule(PermissionRule.allowTools(
                "Safe read tools",
                "Read", "Glob", "Grep", "ListDir", "LS"
        ));

        // 3. 文件编辑工具 - 自动允许
        engine.addRule(PermissionRule.allowTools(
                "Edit tools",
                "Edit", "Write", "NewFile", "Create", "Delete"
        ));

        // 4. Web 工具 - 自动允许
        engine.addRule(PermissionRule.allowTools(
                "Web tools",
                "WebSearch", "WebFetch", "Curl"
        ));

        // 5. 其他 Bash - 自动允许
        engine.addRule(PermissionRule.allowBash("Default bash allow"));

        // 6. 默认 - 允许所有其他工具
        engine.setDefaultDecision(PermissionDecision.allow());

        return engine;
    }

    /**
     * 创建严格模式权限引擎
     *
     * - 只允许文件读取
     * - 所有修改操作需要用户确认
     */
    public static PermissionEngine createStrict() {
        PermissionEngine engine = new PermissionEngine();

        // 1. 危险命令 - 拒绝
        engine.addRule(PermissionRule.denyBash(
                "Dangerous bash",
                "rm", "rmdir", "format", "mkfs", "dd"
        ));

        // 2. 读取工具 - 允许
        engine.addRule(PermissionRule.allowTools(
                "Read tools",
                "Read", "Glob", "Grep", "ListDir", "LS"
        ));

        // 3. 其他所有工具 - 询问用户
        engine.setDefaultDecision(PermissionDecision.askUser("Please confirm this action"));

        return engine;
    }
}
