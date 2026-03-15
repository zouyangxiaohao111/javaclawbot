package agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子Agent上下文隔离
 *
 * 对齐 OpenClaw 的 spawned-context.ts
 *
 * 功能：
 * - 子Agent的完整上下文隔离
 * - 工作空间继承
 * - 会话元数据管理
 */
public class SpawnedContext {

    private static final Logger log = LoggerFactory.getLogger(SpawnedContext.class);

    /**
     * 子Agent运行元数据
     */
    public static class SpawnedRunMetadata {
        public final String spawnedBy;          // 启动者会话Key
        public final String groupId;            // 组ID
        public final String groupChannel;       // 组通道
        public final String groupSpace;         // 组空间
        public final String workspaceDir;       // 工作空间目录
        public final int depth;                 // 嵌套深度
        public final String parentSessionKey;   // 父会话Key

        public SpawnedRunMetadata(
                String spawnedBy,
                String groupId,
                String groupChannel,
                String groupSpace,
                String workspaceDir,
                int depth,
                String parentSessionKey) {
            this.spawnedBy = spawnedBy;
            this.groupId = groupId;
            this.groupChannel = groupChannel;
            this.groupSpace = groupSpace;
            this.workspaceDir = workspaceDir;
            this.depth = depth;
            this.parentSessionKey = parentSessionKey;
        }

        /**
         * 创建子上下文
         */
        public SpawnedRunMetadata createChild(String childSessionKey, Path childWorkspace) {
            return new SpawnedRunMetadata(
                    childSessionKey,
                    groupId,
                    groupChannel,
                    groupSpace,
                    childWorkspace != null ? childWorkspace.toString() : workspaceDir,
                    depth + 1,
                    spawnedBy
            );
        }
    }

    /**
     * 工具上下文
     */
    public static class SpawnedToolContext {
        public final String agentGroupId;
        public final String agentGroupChannel;
        public final String agentGroupSpace;
        public final Path workspaceDir;

        public SpawnedToolContext(
                String agentGroupId,
                String agentGroupChannel,
                String agentGroupSpace,
                Path workspaceDir) {
            this.agentGroupId = agentGroupId;
            this.agentGroupChannel = agentGroupChannel;
            this.agentGroupSpace = agentGroupSpace;
            this.workspaceDir = workspaceDir;
        }

        public SpawnedRunMetadata toMetadata(String sessionKey, int depth) {
            return new SpawnedRunMetadata(
                    sessionKey,
                    agentGroupId,
                    agentGroupChannel,
                    agentGroupSpace,
                    workspaceDir != null ? workspaceDir.toString() : null,
                    depth,
                    null
            );
        }
    }

    /**
     * 隔离上下文管理器
     */
    public static class IsolatedContextManager {
        private final Map<String, SpawnedRunMetadata> contexts = new ConcurrentHashMap<>();
        private final Path defaultWorkspace;
        private final int maxDepth;

        public IsolatedContextManager(Path defaultWorkspace, int maxDepth) {
            this.defaultWorkspace = defaultWorkspace;
            this.maxDepth = maxDepth;
        }

        /**
         * 注册子Agent上下文
         */
        public SpawnedRunMetadata register(String sessionKey, SpawnedRunMetadata metadata) {
            if (metadata == null) {
                metadata = new SpawnedRunMetadata(
                        sessionKey,
                        null,
                        null,
                        null,
                        defaultWorkspace.toString(),
                        0,
                        null
                );
            }
            contexts.put(sessionKey, metadata);
            return metadata;
        }

        /**
         * 获取子Agent上下文
         */
        public SpawnedRunMetadata get(String sessionKey) {
            return contexts.get(sessionKey);
        }

        /**
         * 移除子Agent上下文
         */
        public void remove(String sessionKey) {
            contexts.remove(sessionKey);
        }

        /**
         * 检查是否可以spawn
         */
        public boolean canSpawn(String sessionKey) {
            SpawnedRunMetadata metadata = contexts.get(sessionKey);
            if (metadata == null) {
                return true; // 主Agent可以spawn
            }
            return metadata.depth < maxDepth;
        }

        /**
         * 获取当前深度
         */
        public int getDepth(String sessionKey) {
            SpawnedRunMetadata metadata = contexts.get(sessionKey);
            return metadata != null ? metadata.depth : 0;
        }

        /**
         * 创建子会话Key
         */
        public static String createChildSessionKey(String parentSessionKey, String taskId) {
            return parentSessionKey + ":sub:" + taskId;
        }

        /**
         * 解析会话Key
         */
        public static SessionKeyParts parseSessionKey(String sessionKey) {
            if (sessionKey == null || sessionKey.isBlank()) {
                return null;
            }

            String[] parts = sessionKey.split(":");
            if (parts.length < 2) {
                return null;
            }

            String channel = parts[0];
            String chatId = parts[1];
            String agentId = null;
            String subId = null;

            for (int i = 2; i < parts.length; i++) {
                if ("sub".equals(parts[i]) && i + 1 < parts.length) {
                    subId = parts[i + 1];
                    break;
                } else if (agentId == null) {
                    agentId = parts[i];
                }
            }

            return new SessionKeyParts(channel, chatId, agentId, subId);
        }
    }

    /**
     * 会话Key解析结果
     */
    public static class SessionKeyParts {
        public final String channel;
        public final String chatId;
        public final String agentId;
        public final String subId;

        public SessionKeyParts(String channel, String chatId, String agentId, String subId) {
            this.channel = channel;
            this.chatId = chatId;
            this.agentId = agentId;
            this.subId = subId;
        }

        public boolean isSubagent() {
            return subId != null;
        }

        public String toParentSessionKey() {
            if (subId == null) {
                return null;
            }
            return channel + ":" + chatId + (agentId != null ? ":" + agentId : "");
        }
    }

    /**
     * 上下文继承策略
     */
    public enum ContextInheritance {
        NONE,           // 不继承
        WORKSPACE,      // 只继承工作空间
        FULL,           // 完全继承
        ISOLATED        // 完全隔离
    }

    /**
     * 构建隔离上下文
     */
    public static SpawnedRunMetadata buildIsolatedContext(
            String sessionKey,
            String parentSessionKey,
            Path workspace,
            ContextInheritance inheritance,
            IsolatedContextManager manager) {

        SpawnedRunMetadata parentContext = manager.get(parentSessionKey);
        int depth = parentContext != null ? parentContext.depth + 1 : 0;

        String inheritedWorkspace = null;
        String groupId = null;
        String groupChannel = null;
        String groupSpace = null;

        if (parentContext != null && inheritance != ContextInheritance.NONE) {
            if (inheritance == ContextInheritance.WORKSPACE || inheritance == ContextInheritance.FULL) {
                inheritedWorkspace = workspace != null ? workspace.toString() : parentContext.workspaceDir;
            }
            if (inheritance == ContextInheritance.FULL) {
                groupId = parentContext.groupId;
                groupChannel = parentContext.groupChannel;
                groupSpace = parentContext.groupSpace;
            }
        } else {
            inheritedWorkspace = workspace != null ? workspace.toString() : manager.defaultWorkspace.toString();
        }

        return new SpawnedRunMetadata(
                sessionKey,
                groupId,
                groupChannel,
                groupSpace,
                inheritedWorkspace,
                depth,
                parentSessionKey
        );
    }
}