package providers.cli.model;

import java.time.LocalDateTime;

/**
 * CLI 会话信息
 *
 * 复刻 cc-connect core/message.go AgentSessionInfo
 */
public record CliSessionInfo(
    /**
     * 会话 ID
     */
    String id,

    /**
     * 摘要
     */
    String summary,

    /**
     * 消息数量
     */
    int messageCount,

    /**
     * 修改时间
     */
    LocalDateTime modifiedAt,

    /**
     * Git 分支
     */
    String gitBranch
) {
    public CliSessionInfo {
        // 允许 null
    }
}
