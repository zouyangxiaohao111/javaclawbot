package agent.subagent.task.todo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * Todo item.
 * 对应 Open-ClaudeCode: src/utils/todo/types.ts - TodoItemSchema
 *
 * export type TodoItem = {
 *   content: string      // 任务内容
 *   status: TodoStatus  // 状态
 *   activeForm: string  // 进行时形式
 * }
 */
public class TodoItem {

    private String content;
    private TodoStatus status;
    private String activeForm;
    private Instant updatedAt;

    public TodoItem() {
    }

    public TodoItem(String content, TodoStatus status, String activeForm) {
        this.content = content;
        this.status = status;
        this.activeForm = activeForm;
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TodoStatus getStatus() {
        return status;
    }

    public void setStatus(TodoStatus status) {
        this.status = status;
    }

    public String getActiveForm() {
        return activeForm;
    }

    public void setActiveForm(String activeForm) {
        this.activeForm = activeForm;
    }

    @JsonIgnore
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @JsonIgnore
    public boolean isPending() {
        return status == TodoStatus.PENDING;
    }

    @JsonIgnore
    public boolean isInProgress() {
        return status == TodoStatus.IN_PROGRESS;
    }

    @JsonIgnore
    public boolean isCompleted() {
        return status == TodoStatus.COMPLETED;
    }

    /**
     * Create a copy of this TodoItem for immutable updates.
     */
    public TodoItem copy() {
        TodoItem copy = new TodoItem(content, status, activeForm);
        copy.updatedAt = this.updatedAt;
        return copy;
    }

    @Override
    public String toString() {
        return "TodoItem{" +
                "content='" + content + '\'' +
                ", status=" + status +
                ", activeForm='" + activeForm + '\'' +
                '}';
    }
}
