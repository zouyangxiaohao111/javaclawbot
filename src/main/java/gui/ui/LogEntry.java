package gui.ui;

/**
 * 日志条目，由 LogWatcher 从日志文件解析产生。
 */
public record LogEntry(
    String timestamp,   // "10:23:45.123"
    String level,       // "INFO", "WARN", "ERROR", "DEBUG", "TRACE"
    String logger,      // "agent.AgentLoop"
    String message,     // "Agent 循环已启动"
    String raw          // 原始行（用于导出）
) {}
