package agent.subagent.team;

import agent.subagent.team.backends.Backend;
import agent.subagent.team.backends.BackendRouter;
import agent.subagent.team.backends.BackendType;
import agent.subagent.team.messaging.TeammateMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 团队协调器
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - handleSpawn()
 *
 * 职责：
 * 1. 创建 teammate
 * 2. 管理 teammate 生命周期
 * 3. 处理 teammate 消息
 */
public class TeamCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TeamCoordinator.class);

    /** 后端路由器 */
    private final BackendRouter backendRouter;

    /** Teammate 注册表 */
    private final TeammateRegistry registry;

    /** Teammate 信箱 */
    private final TeammateMailbox mailbox;

    /** 当前使用的后端类型 */
    private BackendType currentBackendType;

    public TeamCoordinator() {
        this.backendRouter = new BackendRouter();
        this.registry = new TeammateRegistry();
        this.mailbox = new TeammateMailbox();
    }

    public TeamCoordinator(BackendRouter backendRouter, TeammateRegistry registry, TeammateMailbox mailbox) {
        this.backendRouter = backendRouter;
        this.registry = registry;
        this.mailbox = mailbox;
    }

    /**
     * 创建 teammate
     * 对应: handleSpawn()
     *
     * @param config spawn 配置
     * @return teammate 信息
     */
    public TeammateInfo spawnTeammate(SpawnConfig config) {
        log.info("Spawning teammate: name={}, team={}", config.name, config.teamName);

        // 1. 检测后端
        Backend backend = backendRouter.detectBackend();
        this.currentBackendType = backend.type();

        // 2. 创建 pane
        Backend.CreatePaneResult result = backend.createPane(config.name, config.color);
        String paneId = result.getPaneId();

        // 3. 构建 teammate 信息
        TeammateInfo info = TeammateInfo.builder()
                .name(config.name)
                .teamName(config.teamName)
                .color(config.color)
                .paneId(paneId)
                .isFirstTeammate(result.isFirstTeammate())
                .status("running")
                .build();

        // 4. 注册 teammate
        registry.register(info);

        // 5. 发送初始命令
        backend.sendCommand(paneId, config.prompt);

        log.info("Spawned teammate: id={}, name={}, backend={}", info.getTeammateId(), info.getName(), currentBackendType);
        return info;
    }

    /**
     * 终止 teammate
     * 对应: killTeammate()
     *
     * @param teammateId teammate ID
     */
    public void killTeammate(String teammateId) {
        log.info("Killing teammate: id={}", teammateId);

        TeammateInfo info = registry.get(teammateId);
        if (info != null) {
            Backend backend = backendRouter.createBackend(currentBackendType.getValue());
            backend.killPane(info.getPaneId());
            registry.unregister(teammateId);
            log.info("Killed teammate: id={}", teammateId);
        } else {
            log.warn("Teammate not found: id={}", teammateId);
        }
    }

    /**
     * 获取 teammate 信息
     *
     * @param teammateId teammate ID
     * @return teammate 信息
     */
    public TeammateInfo getTeammate(String teammateId) {
        return registry.get(teammateId);
    }

    /**
     * 列出团队成员
     *
     * @param teamName 团队名称
     * @return team 成员列表
     */
    public List<TeammateInfo> listTeammates(String teamName) {
        return registry.listByTeam(teamName);
    }

    /**
     * 获取所有团队成员
     *
     * @return 所有 teammate
     */
    public List<TeammateInfo> getAllTeammates() {
        return List.copyOf(registry.getAll());
    }

    /**
     * 获取邮箱
     */
    public TeammateMailbox getMailbox() {
        return mailbox;
    }

    /**
     * 获取注册表
     */
    public TeammateRegistry getRegistry() {
        return registry;
    }

    /**
     * 获取当前后端类型
     */
    public BackendType getCurrentBackendType() {
        return currentBackendType;
    }

    /**
     * Spawn 配置
     */
    public static class SpawnConfig {
        public String name;
        public String teamName;
        public String prompt;
        public String color;
        public String agentType;

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final SpawnConfig config = new SpawnConfig();

            public Builder name(String name) {
                config.name = name;
                return this;
            }

            public Builder teamName(String teamName) {
                config.teamName = teamName;
                return this;
            }

            public Builder prompt(String prompt) {
                config.prompt = prompt;
                return this;
            }

            public Builder color(String color) {
                config.color = color;
                return this;
            }

            public Builder agentType(String agentType) {
                config.agentType = agentType;
                return this;
            }

            public SpawnConfig build() {
                return config;
            }
        }
    }
}
