package agent.tool.plan;

import agent.tool.Tool;
import agent.tool.ToolUseContext;
import context.auto.PlanFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Tool for switching to Plan Mode (read-only exploration phase).
 * Corresponds to Open-ClaudeCode: src/tools/EnterPlanModeTool/EnterPlanModeTool.ts
 */
public class EnterPlanModeTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(EnterPlanModeTool.class);

    public static final String NAME = "EnterPlanMode";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Requests permission to enter plan mode for complex tasks requiring exploration and design";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of()
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        return CompletableFuture.completedFuture(
            "{\"error\": \"EnterPlanMode requires ToolUseContext. Use execute(Map, ToolUseContext)\"}");
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args, ToolUseContext ctx) {
        if (ctx == null) {
            return CompletableFuture.completedFuture(
                "{\"error\": \"No ToolUseContext available\"}");
        }

        // Cannot be used in sub-agent contexts
        if (ctx.getAgentId() != null) {
            return CompletableFuture.completedFuture(
                "{\"error\": \"EnterPlanMode tool cannot be used in agent contexts\"}");
        }

        try {
            String sessionId = ctx.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return CompletableFuture.completedFuture(
                    "{\"error\": \"No session ID available\"}");
            }

            // Enter plan mode and generate word slug
            String wordSlug = PlanModeState.enterPlanMode(sessionId);

            // Create empty plan file
            PlanFileManager.writePlanBySlug(wordSlug, "");

            String planFilePath = PlanFileManager.getPlanFilePathBySlug(wordSlug).toString();

            log.info("Entered plan mode: sessionId={}, wordSlug={}", sessionId, wordSlug);

            String message = "Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach.\n\n" +
                "In plan mode, you should:\n" +
                "1. Thoroughly explore the codebase to understand existing patterns\n" +
                "2. Identify similar features and architectural approaches\n" +
                "3. Consider multiple approaches and their trade-offs\n" +
                "4. Use AskUserQuestion if you need to clarify the approach\n" +
                "5. Design a concrete implementation strategy\n" +
                "6. When ready, use ExitPlanMode to present your plan for approval\n\n" +
                "Remember: DO NOT write or edit any files except the plan file. This is a read-only exploration and planning phase.\n\n" +
                "Plan file: " + planFilePath;

            return CompletableFuture.completedFuture(message);
        } catch (Exception e) {
            log.error("Error entering plan mode", e);
            return CompletableFuture.completedFuture(
                "{\"error\": \"Failed to enter plan mode: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
