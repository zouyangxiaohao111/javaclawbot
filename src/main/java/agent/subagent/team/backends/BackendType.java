package agent.subagent.team.backends;

/**
 * 后端类型枚举
 *
 * 对应 Open-ClaudeCode: src/tools/shared/spawnMultiAgent.ts - BackendType
 */
public enum BackendType {
    IN_PROCESS("in_process"),
    TMUX("tmux"),
    ITERM2("iterm2"),
    CONPTY("conpty");  // 预留

    private final String value;

    BackendType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static BackendType fromValue(String value) {
        for (BackendType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown BackendType: " + value);
    }
}
