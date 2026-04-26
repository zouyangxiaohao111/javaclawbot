package agent.subagent.task;

import java.util.List;

/**
 * Result of claiming a task.
 * 对应 Open-ClaudeCode: src/utils/tasks.ts - ClaimTaskResult (行 488-499)
 *
 * export type ClaimTaskResult = {
 *   success: boolean
 *   reason?:
 *     | 'task_not_found'
 *     | 'already_claimed'
 *     | 'already_resolved'
 *     | 'blocked'
 *     | 'agent_busy'
 *   task?: Task
 *   busyWithTasks?: string[] // task IDs the agent is busy with (when reason is 'agent_busy')
 *   blockedByTasks?: string[] // task IDs blocking this task (when reason is 'blocked')
 * }
 */
public class ClaimTaskResult {
    private final boolean success;
    private final String reason;
    private final TeamTask task;
    private final List<String> busyWithTasks;
    private final List<String> blockedByTasks;

    private ClaimTaskResult(boolean success, String reason, TeamTask task,
                           List<String> busyWithTasks, List<String> blockedByTasks) {
        this.success = success;
        this.reason = reason;
        this.task = task;
        this.busyWithTasks = busyWithTasks;
        this.blockedByTasks = blockedByTasks;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getReason() {
        return reason;
    }

    public TeamTask getTask() {
        return task;
    }

    public List<String> getBusyWithTasks() {
        return busyWithTasks;
    }

    public List<String> getBlockedByTasks() {
        return blockedByTasks;
    }

    // =====================
    // Factory methods
    // =====================

    public static ClaimTaskResult success(TeamTask task) {
        return new ClaimTaskResult(true, null, task, null, null);
    }

    public static ClaimTaskResult taskNotFound() {
        return new ClaimTaskResult(false, "task_not_found", null, null, null);
    }

    public static ClaimTaskResult alreadyClaimed(TeamTask task) {
        return new ClaimTaskResult(false, "already_claimed", task, null, null);
    }

    public static ClaimTaskResult alreadyResolved(TeamTask task) {
        return new ClaimTaskResult(false, "already_resolved", task, null, null);
    }

    public static ClaimTaskResult blocked(TeamTask task, List<String> blockedByTasks) {
        return new ClaimTaskResult(false, "blocked", task, null, blockedByTasks);
    }

    public static ClaimTaskResult agentBusy(TeamTask task, List<String> busyWithTasks) {
        return new ClaimTaskResult(false, "agent_busy", task, busyWithTasks, null);
    }
}