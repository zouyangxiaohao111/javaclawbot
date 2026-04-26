package agent.subagent.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Team Task model.
 * 对应 Open-ClaudeCode: src/utils/tasks.ts - Task (行 76-89)
 *
 * export type Task = z.infer<ReturnType<typeof TaskSchema>>
 * const TaskSchema = z.object({
 *   id: z.string(),
 *   subject: z.string(),
 *   description: z.string(),
 *   activeForm: z.string().optional(),
 *   owner: z.string().optional(),
 *   status: TaskStatusSchema(),
 *   blocks: z.array(z.string()),
 *   blockedBy: z.array(z.string()),
 *   metadata: z.record(z.string(), z.unknown()).optional(),
 * })
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamTask {
    private String id;
    private String subject;
    private String description;
    private String activeForm;
    private String owner;
    private String status;
    private List<String> blocks = new ArrayList<>();
    private List<String> blockedBy = new ArrayList<>();
    private Map<String, Object> metadata;

    public TeamTask() {
    }

    public TeamTask(String id, String subject, String description) {
        this.id = id;
        this.subject = subject;
        this.description = description;
    }

    // =====================
    // Static factory methods
    // =====================

    /**
     * Creates a new task with a unique ID.
     * 对应 Open-ClaudeCode: createTask() 内部逻辑
     */
    public static TeamTask create(String id, String subject, String description, String activeForm) {
        TeamTask task = new TeamTask();
        task.setId(id);
        task.setSubject(subject);
        task.setDescription(description != null ? description : "");
        task.setActiveForm(activeForm);
        task.setStatus("pending");
        task.setBlocks(new ArrayList<>());
        task.setBlockedBy(new ArrayList<>());
        return task;
    }

    // =====================
    // Getters
    // =====================

    public String getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public String getActiveForm() {
        return activeForm;
    }

    public String getOwner() {
        return owner;
    }

    public String getStatus() {
        return status;
    }

    public List<String> getBlocks() {
        return blocks;
    }

    public List<String> getBlockedBy() {
        return blockedBy;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    // =====================
    // Setters
    // =====================

    public void setId(String id) {
        this.id = id;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setActiveForm(String activeForm) {
        this.activeForm = activeForm;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setBlocks(List<String> blocks) {
        this.blocks = blocks != null ? blocks : new ArrayList<>();
    }

    public void setBlockedBy(List<String> blockedBy) {
        this.blockedBy = blockedBy != null ? blockedBy : new ArrayList<>();
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // =====================
    // Status checks
    // =====================

    /**
     * Checks if task is in a terminal state.
     * 对应 Open-ClaudeCode: isTerminalTaskStatus()
     */
    public boolean isTerminal() {
        return "completed".equals(status);
    }

    /**
     * Checks if task is blocked by other tasks.
     */
    public boolean isBlockedBy(List<TeamTask> allTasks) {
        if (blockedBy == null || blockedBy.isEmpty()) {
            return false;
        }
        java.util.Set<String> unresolvedIds = new java.util.HashSet<>();
        for (TeamTask t : allTasks) {
            if (!"completed".equals(t.getStatus())) {
                unresolvedIds.add(t.getId());
            }
        }
        for (String blockerId : blockedBy) {
            if (unresolvedIds.contains(blockerId)) {
                return true;
            }
        }
        return false;
    }
}