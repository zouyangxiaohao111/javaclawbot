package agent.subagent.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Teammate 注册表
 *
 * 对应 Open-ClaudeCode: src/tasks/LocalAgentTask/LocalAgentTask.tsx - teammateRegistry
 */
public class TeammateRegistry {

    private static final Logger log = LoggerFactory.getLogger(TeammateRegistry.class);

    /** Teammate 存储: id -> TeammateInfo */
    private final Map<String, TeammateInfo> teammates = new ConcurrentHashMap<>();

    /**
     * 注册 teammate
     *
     * @param info teammate 信息
     */
    public void register(TeammateInfo info) {
        if (info == null || info.getTeammateId() == null) {
            throw new IllegalArgumentException("TeammateInfo and teammateId cannot be null");
        }
        teammates.put(info.getTeammateId(), info);
        log.debug("Registered teammate: id={}, name={}", info.getTeammateId(), info.getName());
    }

    /**
     * 获取 teammate
     *
     * @param id teammate ID
     * @return teammate 信息，或 null
     */
    public TeammateInfo get(String id) {
        return teammates.get(id);
    }

    /**
     * 列出指定团队的 teammate
     *
     * @param teamName 团队名称
     * @return teammate 列表
     */
    public List<TeammateInfo> listByTeam(String teamName) {
        return teammates.values().stream()
                .filter(t -> teamName.equals(t.getTeamName()))
                .collect(Collectors.toList());
    }

    /**
     * 注销 teammate
     *
     * @param id teammate ID
     */
    public void unregister(String id) {
        TeammateInfo removed = teammates.remove(id);
        if (removed != null) {
            log.debug("Unregistered teammate: id={}, name={}", id, removed.getName());
        }
    }

    /**
     * 获取所有 teammate
     *
     * @return 所有 teammate
     */
    public Collection<TeammateInfo> getAll() {
        return teammates.values();
    }

    /**
     * 检查是否存在
     *
     * @param id teammate ID
     * @return 是否存在
     */
    public boolean contains(String id) {
        return teammates.containsKey(id);
    }

    /**
     * 获取 teammate 数量
     *
     * @return 数量
     */
    public int size() {
        return teammates.size();
    }

    /**
     * 清空注册表
     */
    public void clear() {
        teammates.clear();
        log.debug("Cleared teammate registry");
    }
}
