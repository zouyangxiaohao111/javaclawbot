package agent.subagent.types;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * 任务工具类
 *
 * 对应 Open-ClaudeCode: src/Task.ts
 */
public class TaskUtils {

    /** Task ID 前缀映射 */
    private static final java.util.Map<TaskType, String> TASK_ID_PREFIXES = new java.util.EnumMap<>(TaskType.class);

    static {
        TASK_ID_PREFIXES.put(TaskType.LOCAL_AGENT, "a");
        TASK_ID_PREFIXES.put(TaskType.REMOTE_AGENT, "r");
        TASK_ID_PREFIXES.put(TaskType.IN_PROCESS_TEAMMATE, "t");
        TASK_ID_PREFIXES.put(TaskType.LOCAL_WORKFLOW, "w");
    }

    /** Case-insensitive-safe alphabet for task IDs (digits + lowercase) */
    private static final String TASK_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    /** 随机数生成器 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成任务 ID
     *
     * 对应 Open-ClaudeCode: generateTaskId()
     *
     * 使用安全的随机字节生成任务 ID
     * 格式: prefix + 8个随机字符
     * 36^8 ≈ 2.8 万亿组合，足以抵抗暴力破解
     */
    public static String generateTaskId(TaskType type) {
        String prefix = TASK_ID_PREFIXES.getOrDefault(type, "x");
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);

        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < 8; i++) {
            id.append(TASK_ID_ALPHABET.charAt((bytes[i] & 0xFF) % TASK_ID_ALPHABET.length()));
        }
        return id.toString();
    }

    /**
     * 创建任务状态基类
     *
     * 对应 Open-ClaudeCode: createTaskStateBase()
     *
     * @param id 任务 ID
     * @param type 任务类型
     * @param description 任务描述
     * @param toolUseId 可选的 tool_use ID
     * @param outputFile 输出文件路径
     * @return 任务状态基类
     */
    public static TaskStateBase createTaskStateBase(
            String id,
            TaskType type,
            String description,
            String toolUseId,
            String outputFile
    ) {
        return new TaskStateBase(id, type, description, toolUseId, outputFile);
    }

    /**
     * 任务状态基类实现
     */
    public static class TaskStateBase extends TaskState {
        public TaskStateBase(String id, TaskType type, String description, String toolUseId, String outputFile) {
            this.id = id;
            this.type = type;
            this.status = TaskStatus.PENDING;
            this.description = description;
            this.toolUseId = toolUseId;
            this.startTime = Instant.now();
            this.outputFile = outputFile;
            this.outputOffset = 0L;
            this.notified = false;
        }
    }
}
