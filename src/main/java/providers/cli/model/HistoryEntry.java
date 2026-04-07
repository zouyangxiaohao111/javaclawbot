package providers.cli.model;

import java.time.LocalDateTime;

/**
 * 历史条目
 *
 * 复刻 cc-connect core/message.go HistoryEntry
 */
public record HistoryEntry(
    /**
     * 角色: "user" 或 "assistant"
     */
    String role,

    /**
     * 内容
     */
    String content,

    /**
     * 时间戳
     */
    LocalDateTime timestamp
) {
    public HistoryEntry {
        // 允许 null
    }
}
