package agent.subagent.context;

import java.util.UUID;

/**
 * 查询追踪信息
 *
 * 对应 Open-ClaudeCode: src/utils/forkedAgent.ts - queryTracking
 *
 * 为子代理创建新的追踪链，depth 在父代理基础上递增。
 */
public class QueryTracking {

    /** 追踪链 ID */
    private final String chainId;

    /** 追踪深度（父代理 depth + 1） */
    private final int depth;

    public QueryTracking(String chainId, int depth) {
        this.chainId = chainId;
        this.depth = depth;
    }

    /**
     * 创建新的 QueryTracking，depth 在父代理基础上递增
     */
    public static QueryTracking createChildTracking(QueryTracking parentTracking) {
        return new QueryTracking(
                UUID.randomUUID().toString(),
                (parentTracking != null ? parentTracking.getDepth() : -1) + 1
        );
    }

    /**
     * 从父追踪创建（depth 递增）
     */
    public static QueryTracking fromParent(QueryTracking parent) {
        return createChildTracking(parent);
    }

    public String getChainId() { return chainId; }
    public int getDepth() { return depth; }

    @Override
    public String toString() {
        return "QueryTracking{chainId='" + chainId + "', depth=" + depth + "}";
    }
}
