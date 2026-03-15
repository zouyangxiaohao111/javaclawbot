package agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子Agent能力声明
 *
 * 对齐 OpenClaw 的 subagent-capabilities.ts
 *
 * 功能：
 * - 子Agent的能力声明机制
 * - 角色定义（main/orchestrator/leaf）
 * - 控制范围
 */
public class SubagentCapabilities {

    private static final Logger log = LoggerFactory.getLogger(SubagentCapabilities.class);

    /**
     * 子Agent会话角色
     */
    public enum SessionRole {
        MAIN,           // 主Agent
        ORCHESTRATOR,   // 编排者（可以spawn子Agent）
        LEAF            // 叶子节点（不能spawn）
    }

    /**
     * 控制范围
     */
    public enum ControlScope {
        CHILDREN,       // 可以控制子Agent
        NONE            // 不能控制
    }

    /**
     * 能力声明
     */
    public static class Capabilities {
        public final int depth;
        public final SessionRole role;
        public final ControlScope controlScope;
        public final boolean canSpawn;
        public final boolean canControlChildren;

        public Capabilities(int depth, SessionRole role, ControlScope controlScope,
                           boolean canSpawn, boolean canControlChildren) {
            this.depth = depth;
            this.role = role;
            this.controlScope = controlScope;
            this.canSpawn = canSpawn;
            this.canControlChildren = canControlChildren;
        }
    }

    /**
     * 会话能力条目（用于持久化）
     */
    public static class SessionCapabilityEntry {
        public final String sessionId;
        public final int spawnDepth;
        public final SessionRole subagentRole;
        public final ControlScope subagentControlScope;

        public SessionCapabilityEntry(String sessionId, int spawnDepth,
                                       SessionRole subagentRole, ControlScope subagentControlScope) {
            this.sessionId = sessionId;
            this.spawnDepth = spawnDepth;
            this.subagentRole = subagentRole;
            this.subagentControlScope = subagentControlScope;
        }
    }

    /** 默认最大spawn深度 */
    public static final int DEFAULT_MAX_SPAWN_DEPTH = 3;

    /** 能力存储 */
    private final Map<String, SessionCapabilityEntry> capabilityStore = new ConcurrentHashMap<>();
    private final int maxSpawnDepth;

    public SubagentCapabilities() {
        this(DEFAULT_MAX_SPAWN_DEPTH);
    }

    public SubagentCapabilities(int maxSpawnDepth) {
        this.maxSpawnDepth = Math.max(1, maxSpawnDepth);
    }

    /**
     * 根据深度解析角色
     */
    public SessionRole resolveRoleForDepth(int depth) {
        if (depth <= 0) {
            return SessionRole.MAIN;
        }
        return depth < maxSpawnDepth ? SessionRole.ORCHESTRATOR : SessionRole.LEAF;
    }

    /**
     * 根据角色解析控制范围
     */
    public ControlScope resolveControlScopeForRole(SessionRole role) {
        return role == SessionRole.LEAF ? ControlScope.NONE : ControlScope.CHILDREN;
    }

    /**
     * 解析能力
     */
    public Capabilities resolveCapabilities(int depth) {
        SessionRole role = resolveRoleForDepth(depth);
        ControlScope controlScope = resolveControlScopeForRole(role);
        boolean canSpawn = role == SessionRole.MAIN || role == SessionRole.ORCHESTRATOR;
        boolean canControlChildren = controlScope == ControlScope.CHILDREN;

        return new Capabilities(depth, role, controlScope, canSpawn, canControlChildren);
    }

    /**
     * 获取会话能力
     */
    public Capabilities getCapabilities(String sessionKey) {
        SessionCapabilityEntry entry = capabilityStore.get(sessionKey);
        if (entry != null) {
            return new Capabilities(
                    entry.spawnDepth,
                    entry.subagentRole,
                    entry.subagentControlScope,
                    entry.subagentRole != SessionRole.LEAF,
                    entry.subagentControlScope == ControlScope.CHILDREN
            );
        }

        // 默认为主Agent
        return resolveCapabilities(0);
    }

    /**
     * 注册会话能力
     */
    public void registerCapabilities(String sessionKey, int depth) {
        Capabilities caps = resolveCapabilities(depth);
        SessionCapabilityEntry entry = new SessionCapabilityEntry(
                sessionKey,
                depth,
                caps.role,
                caps.controlScope
        );
        capabilityStore.put(sessionKey, entry);
        log.debug("Registered capabilities for {}: depth={}, role={}", sessionKey, depth, caps.role);
    }

    /**
     * 移除会话能力
     */
    public void removeCapabilities(String sessionKey) {
        capabilityStore.remove(sessionKey);
    }

    /**
     * 检查是否可以spawn
     */
    public boolean canSpawn(String sessionKey) {
        Capabilities caps = getCapabilities(sessionKey);
        return caps.canSpawn;
    }

    /**
     * 检查是否可以控制子Agent
     */
    public boolean canControlChildren(String sessionKey) {
        Capabilities caps = getCapabilities(sessionKey);
        return caps.canControlChildren;
    }

    /**
     * 获取深度
     */
    public int getDepth(String sessionKey) {
        SessionCapabilityEntry entry = capabilityStore.get(sessionKey);
        return entry != null ? entry.spawnDepth : 0;
    }

    /**
     * 获取角色
     */
    public SessionRole getRole(String sessionKey) {
        SessionCapabilityEntry entry = capabilityStore.get(sessionKey);
        return entry != null ? entry.subagentRole : SessionRole.MAIN;
    }

    /**
     * 构建能力描述文本
     */
    public static String buildCapabilityDescription(Capabilities caps) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a ").append(caps.role.name().toLowerCase()).append(" agent");
        sb.append(" at depth ").append(caps.depth).append(".");

        if (caps.canSpawn) {
            sb.append(" You can spawn sub-agents to handle complex tasks.");
        } else {
            sb.append(" You cannot spawn sub-agents.");
        }

        if (caps.canControlChildren) {
            sb.append(" You can control your child agents.");
        }

        return sb.toString();
    }

    /**
     * 构建系统提示后缀
     */
    public static String buildSystemPromptSuffix(Capabilities caps) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Agent Capabilities\n\n");
        sb.append(buildCapabilityDescription(caps));
        sb.append("\n\n");

        if (caps.role == SessionRole.LEAF) {
            sb.append("As a leaf agent, you should focus on completing your assigned task ");
            sb.append("without delegating to sub-agents. Report your results clearly.\n");
        } else if (caps.role == SessionRole.ORCHESTRATOR) {
            sb.append("As an orchestrator, you can break down complex tasks and delegate to sub-agents. ");
            sb.append("Coordinate their work and synthesize results.\n");
        }

        return sb.toString();
    }
}