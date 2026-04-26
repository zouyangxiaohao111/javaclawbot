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
 * Tool for exiting Plan Mode and presenting the plan for user approval.
 * Corresponds to Open-ClaudeCode: src/tools/ExitPlanModeTool/ExitPlanModeV2Tool.ts
 */
public class ExitPlanModeTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(ExitPlanModeTool.class);

    public static final String NAME = "ExitPlanMode";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Prompts the user to exit plan mode and start coding";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "allowedPrompts", Map.of(
                    "type", "array",
                    "description", "Prompt-based permissions needed to implement the plan. These describe categories of actions rather than specific commands.",
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "tool", Map.of(
                                "type", "string",
                                "enum", java.util.List.of("Bash"),
                                "description", "The tool this prompt applies to"
                            ),
                            "prompt", Map.of(
                                "type", "string",
                                "description", "Semantic description of the action, e.g. \"run tests\", \"install dependencies\""
                            )
                        )
                    )
                )
            )
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        return CompletableFuture.completedFuture(
            "{\"error\": \"ExitPlanMode requires ToolUseContext. Use execute(Map, ToolUseContext)\"}");
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args, ToolUseContext ctx) {
        if (ctx == null) {
            return CompletableFuture.completedFuture(
                "{\"error\": \"No ToolUseContext available\"}");
        }

        try {
            String sessionId = ctx.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return CompletableFuture.completedFuture(
                    "{\"error\": \"No session ID available\"}");
            }

            // Check if in plan mode
            String wordSlug = PlanModeState.getWordSlug(sessionId);
            if (wordSlug == null) {
                return CompletableFuture.completedFuture(
                    "{\"error\": \"You are not in plan mode. This tool is only for exiting plan mode after writing a plan. If your plan was already approved, continue with implementation.\"}");
            }

            // Read plan content from disk
            String plan = PlanFileManager.getPlanBySlug(wordSlug);
            String filePath = PlanFileManager.getPlanFilePathBySlug(wordSlug).toString();

            if (plan == null || plan.isBlank()) {
                return CompletableFuture.completedFuture(
                    String.format("{\"error\": \"No plan file found at %s. Please write your plan to this file before calling ExitPlanMode.\"}",
                        escapeJson(filePath)));
            }

            // Exit plan mode (restore previous mode)
            PlanModeState.exitPlanMode(sessionId);

            boolean isAgent = ctx.getAgentId() != null;

            log.info("Exited plan mode: sessionId={}, wordSlug={}, planLength={}", sessionId, wordSlug, plan.length());

            // Return plain text matching Open-ClaudeCode's mapToolResultToToolResultBlockParam
            // The LLM sees this as the tool_result content directly
            String result;
            if (isAgent) {
                result = "User has approved the plan. There is nothing else needed from you now.";
            } else {
                result = "User has approved your plan. You can now start coding.\n\n" +
                    "Your plan has been saved to: " + filePath + "\n\n" +
                    "## Approved Plan:\n" + plan;
            }
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Error exiting plan mode", e);
            return CompletableFuture.completedFuture(
                "{\"error\": \"Failed to exit plan mode: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
