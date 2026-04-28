package agent.subagent.task.todo;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Todo item status enum.
 * 对应 Open-ClaudeCode: src/utils/todo/types.ts - TodoStatusSchema
 *
 * z.enum(['pending', 'in_progress', 'completed'])
 */
public enum TodoStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static TodoStatus fromValue(String value) {
        for (TodoStatus status : TodoStatus.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown TodoStatus value: " + value);
    }
}
