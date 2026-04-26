package agent.subagent.task.todo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Todo 持久化管理器
 *
 * 对应 Open-ClaudeCode: 通过 session transcript 恢复 todos（Java 版本改为文件存储）
 *
 * 存储位置: {sessionsDir}/{sessionId}/todos.json
 * sessionsDir 通常为 {workspace}/sessions
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoPersistence {

    private static final Logger log = LoggerFactory.getLogger(TodoPersistence.class);

    /** 文件版本 */
    private static final int VERSION = 1;

    /** todos 子目录 */
    private static final String TODOS_SUBDIR = "todos";

    /** todos 文件名 */
    private static final String TODOS_FILE = "todos.json";

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /** 持久化数据 */
    private int version = VERSION;
    private String agentId;
    private Instant updatedAt;
    private List<TodoItem> todos;

    public TodoPersistence() {
        this.todos = new ArrayList<>();
    }

    public TodoPersistence(String agentId, List<TodoItem> todos) {
        this.agentId = agentId;
        this.todos = todos != null ? todos : new ArrayList<>();
        this.updatedAt = Instant.now();
    }

    // =====================
    // Getters and Setters
    // =====================

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<TodoItem> getTodos() {
        return todos;
    }

    public void setTodos(List<TodoItem> todos) {
        this.todos = todos;
    }

    // =====================
    // 静态方法
    // =====================

    /**
     * 获取 session 对应的 todos 存储路径
     * 路径格式: {sessionsDir}/{sessionId}/todos/todos.json
     */
    private static Path getStoragePath(Path sessionsDir, String sessionId) {
        return sessionsDir.resolve(sessionId).resolve(TODOS_SUBDIR).resolve(TODOS_FILE);
    }

    /**
     * 确保目录存在
     */
    private static void ensureDirectory(Path sessionsDir, String sessionId) throws IOException {
        Path dir = sessionsDir.resolve(sessionId).resolve(TODOS_SUBDIR);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 加载 todos
     * 对应 Open-ClaudeCode: extractTodosFromTranscript() - 从 transcript 恢复
     *
     * @param sessionsDir sessions 根目录 (workspace/sessions)
     * @param sessionId session ID
     * @return TodoList 或 null（如果不存在）
     */
    public static TodoList load(Path sessionsDir, String sessionId) {
        if (sessionsDir == null || sessionId == null || sessionId.isEmpty()) {
            log.debug("Cannot load todos: sessionsDir or sessionId is null");
            return null;
        }

        Path path = getStoragePath(sessionsDir, sessionId);
        if (!Files.exists(path)) {
            log.debug("No todos file found for session: {} at {}", sessionId, path);
            return null;
        }

        try {
            String content = Files.readString(path);
            TodoPersistence data = objectMapper.readValue(content, TodoPersistence.class);

            if (data.getTodos() == null) {
                return null;
            }

            log.info("Loaded {} todos for session: {}", data.getTodos().size(), sessionId);
            return new TodoList(data.getTodos());
        } catch (Exception e) {
            log.error("Failed to load todos for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 保存 todos
     * 对应 Open-ClaudeCode: TodoWriteTool 每次调用后保存
     *
     * @param sessionsDir sessions 根目录 (workspace/sessions)
     * @param sessionId session ID
     * @param agentId agent/session key
     * @param todoList 要保存的 todo 列表
     */
    public static void save(Path sessionsDir, String sessionId, String agentId, TodoList todoList) {
        if (sessionsDir == null || sessionId == null || sessionId.isEmpty()) {
            log.warn("Cannot save todos: sessionsDir or sessionId is null or empty");
            return;
        }

        try {
            ensureDirectory(sessionsDir, sessionId);
            Path path = getStoragePath(sessionsDir, sessionId);

            TodoPersistence data = new TodoPersistence(agentId, todoList.getItems());
            String content = objectMapper.writeValueAsString(data);
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.debug("Saved {} todos for session: {}", todoList.size(), sessionId);
        } catch (Exception e) {
            log.error("Failed to save todos for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 删除 todos 文件
     *
     * @param sessionsDir sessions 根目录
     * @param sessionId session ID
     */
    public static void delete(Path sessionsDir, String sessionId) {
        if (sessionsDir == null || sessionId == null) {
            return;
        }
        Path path = getStoragePath(sessionsDir, sessionId);
        try {
            Files.deleteIfExists(path);
            log.info("Deleted todos file for session: {}", sessionId);
        } catch (IOException e) {
            log.error("Failed to delete todos for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 检查 todos 是否存在
     *
     * @param sessionsDir sessions 根目录
     * @param sessionId session ID
     * @return 是否存在
     */
    public static boolean exists(Path sessionsDir, String sessionId) {
        if (sessionsDir == null || sessionId == null) {
            return false;
        }
        return Files.exists(getStoragePath(sessionsDir, sessionId));
    }
}