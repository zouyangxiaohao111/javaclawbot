package agent.subagent.execution;

import agent.subagent.definition.AgentDefinition;
import agent.subagent.task.AppState;
import agent.subagent.task.TaskStatus;
import agent.subagent.task.local.LocalAgentTaskState;
import agent.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代理恢复
 * 对应 Open-ClaudeCode: src/tools/AgentTool/resumeAgent.ts
 *
 * 用于恢复之前暂停的代理执行。
 *
 * 恢复流程：
 * 1. 从持久化存储加载之前保存的状态
 * 2. 恢复消息列表和上下文
 * 3. 继续执行 query 循环
 */
public class ResumeAgent {

    private static final Logger log = LoggerFactory.getLogger(ResumeAgent.class);

    /** 恢复状态存储路径 - 使用 .javaclawbot 目录 */
    private static final String RESUME_DIR = ".javaclawbot/resume/";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 后台执行器 */
    private static final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setName("resume-agent-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    /**
     * 恢复代理执行
     * 对应: resumeAgent()
     *
     * @param resumeId 恢复 ID（对应保存的状态文件名）
     * @return 恢复后的执行结果
     */
    public static AgentToolResult resume(String resumeId) {
        try {
            // 1. 加载保存的状态
            ResumeState state = loadState(resumeId);

            if (state == null) {
                return AgentToolResult.failure("Resume failed: no saved state found for " + resumeId);
            }

            // 2. 验证状态
            if (!state.isValid()) {
                return AgentToolResult.failure("Resume failed: invalid state for " + resumeId);
            }

            // 3. 继续 query 循环
            RunAgent.RunAgentParams params = RunAgent.RunAgentParams.builder()
                    .agent(state.getAgent())
                    .prompt(state.getLastPrompt())
                    .agentType(state.getAgentType())
                    .agentId(resumeId)
                    .toolUseContext(state.getToolUseContext())
                    .build();
            AgentToolResult result = RunAgent.execute(params);

            // 4. 清理保存的状态
            cleanupState(resumeId);

            return result;

        } catch (Exception e) {
            return AgentToolResult.failure("Resume error: " + e.getMessage());
        }
    }

    /**
     * 异步后台恢复代理执行
     * 对应 Open-ClaudeCode: resumeAgentBackground()
     *
     * 在后台启动恢复的代理，不阻塞当前线程。
     * 用于恢复长时间运行的代理。
     *
     * @param resumeId 恢复 ID
     * @param setAppState AppState setter
     * @return 恢复结果（包含 agentId, description, outputFile）
     */
    public static ResumeAgentResult resumeAgentBackground(String resumeId, AppState.Setter setAppState) {
        try {
            // 1. 加载保存的状态
            ResumeState state = loadState(resumeId);

            if (state == null) {
                return ResumeAgentResult.failure("Resume failed: no saved state found for " + resumeId);
            }

            // 2. 验证状态
            if (!state.isValid()) {
                return ResumeAgentResult.failure("Resume failed: invalid state for " + resumeId);
            }

            String agentId = resumeId;
            String description = state.getAgentType() != null ? state.getAgentType() : "resumed-agent";

            // 3. 创建并注册后台任务状态
            // 对应 TypeScript: registerAsyncAgent()
            LocalAgentTaskState taskState = LocalAgentTaskState.create(
                    agentId,
                    description,
                    null,  // toolUseId
                    state.getLastPrompt(),  // prompt
                    state.getAgentType()   // agentType
            );
            taskState.setBackgrounded(true);

            // 注册任务到 AppState
            AppState.registerTask(taskState, setAppState);

            // 4. 在后台线程启动恢复执行
            // 对应 TypeScript: void runWithAgentContext(asyncAgentContext, () => runAsyncAgentLifecycle(...))
            CompletableFuture.runAsync(() -> {
                try {
                    // 标记开始
                    AppState.updateTaskState(agentId, setAppState, t -> {
                        t.setStatus(TaskStatus.RUNNING);
                        return t;
                    });

                    // 执行恢复
                    RunAgent.RunAgentParams params = RunAgent.RunAgentParams.builder()
                            .agent(state.getAgent())
                            .prompt(state.getLastPrompt())
                            .agentType(state.getAgentType())
                            .agentId(agentId)
                            .toolUseContext(state.getToolUseContext())
                            .build();

                    AgentToolResult result = RunAgent.execute(params);

                    // 标记完成
                    AppState.<LocalAgentTaskState>updateTaskState(agentId, setAppState, t -> {
                        t.setStatus(result.isSuccess() ? TaskStatus.COMPLETED : TaskStatus.FAILED);
                        t.setEndTime(Instant.now());
                        if (result.isSuccess()) {
                            t.setResult(result.getContent());
                        } else {
                            t.setError(result.getError());
                        }
                        return t;
                    });

                    log.info("Resume agent completed: agentId={}, success={}", agentId, result.isSuccess());

                } catch (Exception e) {
                    log.error("Resume agent failed: agentId={}", agentId, e);

                    // 标记失败
                    AppState.<LocalAgentTaskState>updateTaskState(agentId, setAppState, t -> {
                        t.setStatus(TaskStatus.FAILED);
                        t.setEndTime(Instant.now());
                        t.setError(e.getMessage());
                        return t;
                    });
                } finally {
                    // 清理保存的状态
                    cleanupState(resumeId);
                }
            }, backgroundExecutor);

            // 5. 返回结果（立即返回，任务在后台运行）
            // 对应 TypeScript: return { agentId, description, outputFile }
            String outputFile = getTaskOutputPath(agentId);
            return ResumeAgentResult.success(agentId, description, outputFile);

        } catch (Exception e) {
            log.error("Failed to start resume agent background", e);
            return ResumeAgentResult.failure("Resume error: " + e.getMessage());
        }
    }

    /**
     * 获取任务输出文件路径
     */
    private static String getTaskOutputPath(String agentId) {
        return ".javaclawbot/tasks/" + agentId + ".json";
    }

    /**
     * 保存代理状态（用于暂停恢复）
     *
     * @param state 要保存的状态
     */
    public static void saveState(ResumeState state) {
        try {
            Path resumeDir = Paths.get(RESUME_DIR);
            Files.createDirectories(resumeDir);

            Path stateFile = resumeDir.resolve(state.getResumeId() + ".json");
            String json = objectMapper.writeValueAsString(state);
            Files.writeString(stateFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("Saved resume state: {}", state.getResumeId());
        } catch (Exception e) {
            log.error("Failed to save resume state: {}", state.getResumeId(), e);
        }
    }

    /**
     * 检查是否可以恢复
     *
     * @param resumeId 恢复 ID
     * @return 是否可以恢复
     */
    public static boolean canResume(String resumeId) {
        return loadState(resumeId) != null;
    }

    /**
     * 加载恢复状态
     */
    private static ResumeState loadState(String resumeId) {
        try {
            Path stateFile = Paths.get(RESUME_DIR).resolve(resumeId + ".json");
            if (!Files.exists(stateFile)) {
                return null;
            }

            String json = Files.readString(stateFile);
            return objectMapper.readValue(json, ResumeState.class);
        } catch (Exception e) {
            log.error("Failed to load resume state: {}", resumeId, e);
            return null;
        }
    }

    /**
     * 清理恢复状态
     */
    private static void cleanupState(String resumeId) {
        try {
            Path stateFile = Paths.get(RESUME_DIR).resolve(resumeId + ".json");
            Files.deleteIfExists(stateFile);
            log.info("Cleaned up resume state: {}", resumeId);
        } catch (Exception e) {
            log.error("Failed to cleanup resume state: {}", resumeId, e);
        }
    }

    /**
     * 恢复状态
     */
    public static class ResumeState {
        private String resumeId;
        private String agentType;
        private String lastPrompt;
        private AgentDefinition agent;
        private ToolUseContext toolUseContext;

        public boolean isValid() {
            return resumeId != null && agent != null;
        }

        // Getters and setters
        public String getResumeId() { return resumeId; }
        public void setResumeId(String resumeId) { this.resumeId = resumeId; }
        public String getAgentType() { return agentType; }
        public void setAgentType(String agentType) { this.agentType = agentType; }
        public String getLastPrompt() { return lastPrompt; }
        public void setLastPrompt(String lastPrompt) { this.lastPrompt = lastPrompt; }
        public AgentDefinition getAgent() { return agent; }
        public void setAgent(AgentDefinition agent) { this.agent = agent; }
        public ToolUseContext getToolUseContext() { return toolUseContext; }
        public void setToolUseContext(ToolUseContext toolUseContext) { this.toolUseContext = toolUseContext; }
    }

    /**
     * 异步恢复结果
     * 对应 Open-ClaudeCode: ResumeAgentResult
     */
    public static class ResumeAgentResult {
        private final boolean success;
        private final String agentId;
        private final String description;
        private final String outputFile;
        private final String error;

        private ResumeAgentResult(boolean success, String agentId, String description, String outputFile, String error) {
            this.success = success;
            this.agentId = agentId;
            this.description = description;
            this.outputFile = outputFile;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getAgentId() { return agentId; }
        public String getDescription() { return description; }
        public String getOutputFile() { return outputFile; }
        public String getError() { return error; }

        public static ResumeAgentResult success(String agentId, String description, String outputFile) {
            return new ResumeAgentResult(true, agentId, description, outputFile, null);
        }

        public static ResumeAgentResult failure(String error) {
            return new ResumeAgentResult(false, null, null, null, error);
        }
    }
}
