package agent.subagent.team;

import agent.subagent.task.TaskState;
import agent.subagent.task.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内队友任务状态
 *
 * 对应 Open-ClaudeCode: src/utils/swarm/spawnInProcess.ts - InProcessTeammateTaskState
 *
 * 扩展 TaskState 以支持进程内队友特有的字段：
 * - identity: 队友身份信息
 * - prompt: 队友的提示词
 * - abortController: 中止控制器
 * - awaitingPlanApproval: 是否等待计划审批
 * - permissionMode: 权限模式
 * - isIdle: 是否空闲
 * - shutdownRequested: 是否请求关闭
 * - lastReportedToolCount: 最后报告的工具数量
 * - lastReportedTokenCount: 最后报告的 token 数量
 * - pendingUserMessages: 待处理的用户消息
 * - messages: 消息列表
 */
public class InProcessTeammateTaskState extends TaskState {

    private TeammateIdentity identity;
    private String prompt;
    private AtomicBoolean abortController;
    private boolean awaitingPlanApproval;
    private String permissionMode;
    private boolean isIdle;
    private boolean shutdownRequested;
    private int lastReportedToolCount;
    private int lastReportedTokenCount;
    private List<String> pendingUserMessages;
    private List<Object> messages;

    public InProcessTeammateTaskState() {
        super();
        this.type = TaskType.IN_PROCESS_TEAMMATE;
        this.abortController = new AtomicBoolean(false);
        this.pendingUserMessages = new ArrayList<>();
        this.messages = new ArrayList<>();
        this.isIdle = false;
        this.shutdownRequested = false;
        this.awaitingPlanApproval = false;
        this.permissionMode = "default";
        this.lastReportedToolCount = 0;
        this.lastReportedTokenCount = 0;
    }

    // Getters and Setters

    public TeammateIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(TeammateIdentity identity) {
        this.identity = identity;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public AtomicBoolean getAbortController() {
        return abortController;
    }

    public void setAbortController(AtomicBoolean abortController) {
        this.abortController = abortController;
    }

    public boolean isAwaitingPlanApproval() {
        return awaitingPlanApproval;
    }

    public void setAwaitingPlanApproval(boolean awaitingPlanApproval) {
        this.awaitingPlanApproval = awaitingPlanApproval;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    public boolean isIdle() {
        return isIdle;
    }

    public void setIdle(boolean idle) {
        isIdle = idle;
    }

    public boolean isShutdownRequested() {
        return shutdownRequested;
    }

    public void setShutdownRequested(boolean shutdownRequested) {
        this.shutdownRequested = shutdownRequested;
    }

    public int getLastReportedToolCount() {
        return lastReportedToolCount;
    }

    public void setLastReportedToolCount(int lastReportedToolCount) {
        this.lastReportedToolCount = lastReportedToolCount;
    }

    public int getLastReportedTokenCount() {
        return lastReportedTokenCount;
    }

    public void setLastReportedTokenCount(int lastReportedTokenCount) {
        this.lastReportedTokenCount = lastReportedTokenCount;
    }

    public List<String> getPendingUserMessages() {
        return pendingUserMessages;
    }

    public void setPendingUserMessages(List<String> pendingUserMessages) {
        this.pendingUserMessages = pendingUserMessages;
    }

    public List<Object> getMessages() {
        return messages;
    }

    public void setMessages(List<Object> messages) {
        this.messages = messages;
    }

    /**
     * 中止任务
     */
    public void abort() {
        this.abortController.set(true);
    }

    /**
     * 检查是否已中止
     */
    public boolean isAborted() {
        return this.abortController.get();
    }

    @Override
    public TaskState copy() {
        InProcessTeammateTaskState copy = new InProcessTeammateTaskState();
        copy.setId(this.getId());
        copy.setType(this.getType());
        copy.setStatus(this.getStatus());
        copy.setDescription(this.getDescription());
        copy.setStartTime(this.getStartTime());
        copy.setEndTime(this.getEndTime());
        copy.setIdentity(this.identity);
        copy.setPrompt(this.prompt);
        copy.setAbortController(new AtomicBoolean(this.abortController.get()));
        copy.setAwaitingPlanApproval(this.awaitingPlanApproval);
        copy.setPermissionMode(this.permissionMode);
        copy.setIdle(this.isIdle);
        copy.setShutdownRequested(this.shutdownRequested);
        copy.setLastReportedToolCount(this.lastReportedToolCount);
        copy.setLastReportedTokenCount(this.lastReportedTokenCount);
        copy.setPendingUserMessages(new ArrayList<>(this.pendingUserMessages));
        copy.setMessages(new ArrayList<>(this.messages));
        return copy;
    }

    /**
     * 队友身份信息
     */
    public static class TeammateIdentity {
        private String agentId;
        private String agentName;
        private String teamName;
        private String color;
        private boolean planModeRequired;
        private String parentSessionId;

        public TeammateIdentity() {}

        public TeammateIdentity(String agentId, String agentName, String teamName,
                                String color, boolean planModeRequired, String parentSessionId) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.teamName = teamName;
            this.color = color;
            this.planModeRequired = planModeRequired;
            this.parentSessionId = parentSessionId;
        }

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }

        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }

        public String getTeamName() { return teamName; }
        public void setTeamName(String teamName) { this.teamName = teamName; }

        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }

        public boolean isPlanModeRequired() { return planModeRequired; }
        public void setPlanModeRequired(boolean planModeRequired) { this.planModeRequired = planModeRequired; }

        public String getParentSessionId() { return parentSessionId; }
        public void setParentSessionId(String parentSessionId) { this.parentSessionId = parentSessionId; }
    }
}
