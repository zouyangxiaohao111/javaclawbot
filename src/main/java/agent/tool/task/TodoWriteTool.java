package agent.tool.task;

import agent.subagent.task.todo.TodoItem;
import agent.subagent.task.todo.TodoList;
import agent.subagent.task.todo.TodoPersistence;
import agent.subagent.task.todo.TodoStatus;
import agent.tool.Tool;
import agent.subagent.task.AppState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * TodoWrite Tool.
 * 对应 Open-ClaudeCode: src/tools/TodoWriteTool/TodoWriteTool.ts
 *
 * 功能：更新当前 session 的 todo list
 */
public class TodoWriteTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    /** Tool name constant */
    public static final String NAME = "TodoWrite";

    /** AppState getter */
    private final AppState.Getter getAppState;

    /** AppState setter */
    private final AppState.Setter setAppState;

    /** Current agent/session key provider */
    private String agentId;

    /** Session ID for persistence */
    private String sessionId;

    /** Sessions directory for persistence (workspace/sessions) */
    private Path sessionsDir;

    /** Flag to skip persistence (e.g., during batch loading) */
    private boolean skipPersistence = false;

    public TodoWriteTool(AppState.Getter getAppState, AppState.Setter setAppState) {
        this.getAppState = getAppState;
        this.setAppState = setAppState;
    }

    /**
     * Set the agent/session key context.
     */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * Set the session ID for persistence.
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Set the sessions directory for persistence.
     * Usually workspace/sessions
     */
    public void setSessionsDir(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    /**
     * Set whether to skip persistence.
     * Used during batch loading to avoid unnecessary I/O.
     */
    public void setSkipPersistence(boolean skipPersistence) {
        this.skipPersistence = skipPersistence;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Update the todo list for the current session. To be used proactively and often to track progress and pending tasks. Make sure that at least one task is in_progress at all times. Always provide both content (imperative) and activeForm (present continuous) for each task.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> todosProperty = new LinkedHashMap<>();
        todosProperty.put("type", "array");
        todosProperty.put("description", "The updated todo list");

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");

        Map<String, Object> itemProperties = new LinkedHashMap<>();

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "The task description");
        itemProperties.put("content", contentProp);

        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", Arrays.asList("pending", "in_progress", "completed"));
        statusProp.put("description", "Task status");
        itemProperties.put("status", statusProp);

        Map<String, Object> activeFormProp = new LinkedHashMap<>();
        activeFormProp.put("type", "string");
        activeFormProp.put("description", "Present continuous form (e.g., 'Running tests')");
        itemProperties.put("activeForm", activeFormProp);

        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", Arrays.asList("content", "status", "activeForm"));

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(itemSchema);
        todosProperty.put("items", itemSchema);
        properties.put("todos", todosProperty);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("todos"));

        return schema;
    }

    @Override
    public CompletableFuture<String> execute(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get todos from arguments
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> todosInput = (List<Map<String, Object>>) args.get("todos");

                if (todosInput == null) {
                    return "{\"error\": \"todos is required\"}";
                }

                // Get current todos
                String todoKey = agentId != null ? agentId : "default";
                AppState state = getAppState.get();
                TodoList oldTodos = state.getTodos(todoKey);
                if (oldTodos == null) {
                    oldTodos = new TodoList();
                }

                // Build new todos list
                TodoList newTodos = new TodoList();
                for (Map<String, Object> itemMap : todosInput) {
                    String content = (String) itemMap.get("content");
                    String statusStr = (String) itemMap.get("status");
                    String activeForm = (String) itemMap.get("activeForm");

                    TodoStatus status = TodoStatus.fromValue(statusStr);
                    TodoItem item = new TodoItem(content, status, activeForm);
                    newTodos.add(item);
                }

                // Check if all done - if so, clear the list
                boolean allDone = newTodos.isAllCompleted();
                TodoList finalTodos = allDone ? new TodoList() : newTodos;

                // Update AppState
                setAppState.accept(prev -> {
                    AppState newState = new AppState();
                    newState.setTasks(new HashMap<>(prev.getTasks()));
                    newState.setTodos(new HashMap<>(prev.getTodos()));
                    newState.setTodos(todoKey, finalTodos);
                    newState.setAgentNameRegistry(new HashMap<>(prev.getAgentNameRegistry()));
                    return newState;
                });

                // Persist to file (unless skipped)
                if (!skipPersistence && sessionsDir != null && sessionId != null) {
                    TodoPersistence.save(sessionsDir, sessionId, todoKey, finalTodos);
                }

                // Build result
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("oldTodos", oldTodos.getItems());
                result.put("newTodos", newTodos.getItems());
                result.put("allDone", allDone);

                return toJson(result);

            } catch (Exception e) {
                log.error("TodoWriteTool execution error", e);
                return "{\"error\": \"" + e.getMessage() + "\"}";
            }
        });
    }

    /**
     * 加载 todos 到 AppState
     * 对应 Open-ClaudeCode: extractTodosFromTranscript() - 从 transcript 恢复
     *
     * @param sessionsDir sessions 根目录 (workspace/sessions)
     * @param sessionId session ID
     * @param setAppState state setter
     */
    public static void loadTodosIntoAppState(Path sessionsDir, String sessionId, AppState.Setter setAppState) {
        TodoList todoList = TodoPersistence.load(sessionsDir, sessionId);
        if (todoList == null || todoList.isEmpty()) {
            return;
        }

        // Use sessionId as the key (same as TodoWriteTool does)
        String todoKey = sessionId;

        setAppState.accept(prev -> {
            AppState newState = new AppState();
            newState.setTasks(new HashMap<>(prev.getTasks()));
            newState.setTodos(new HashMap<>(prev.getTodos()));
            newState.setTodos(todoKey, todoList);
            newState.setAgentNameRegistry(new HashMap<>(prev.getAgentNameRegistry()));
            return newState;
        });

        log.info("Loaded {} todos into AppState for session: {}", todoList.size(), sessionId);
    }

    private String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
