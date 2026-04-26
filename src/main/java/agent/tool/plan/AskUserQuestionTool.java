package agent.tool.plan;

import agent.tool.Tool;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Tool for asking the user multiple-choice questions to gather information.
 * Corresponds to Open-ClaudeCode: src/tools/AskUserQuestionTool/AskUserQuestionTool.tsx
 */
public class AskUserQuestionTool extends Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);
    private static final Gson gson = new Gson();

    public static final String NAME = "AskUserQuestion";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Asks the user multiple choice questions to gather information, clarify ambiguity, understand preferences, make decisions or offer them choices.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "questions", Map.of(
                    "type", "array",
                    "description", "Questions to ask the user (1-4 questions)",
                    "minItems", 1,
                    "maxItems", 4,
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "question", Map.of(
                                "type", "string",
                                "description", "The complete question to ask the user. Should be clear, specific, and end with a question mark."
                            ),
                            "header", Map.of(
                                "type", "string",
                                "description", "Very short label displayed as a chip/tag (max 12 chars). Examples: \"Auth method\", \"Library\", \"Approach\".",
                                "maxLength", 12
                            ),
                            "options", Map.of(
                                "type", "array",
                                "description", "The available choices for this question. Must have 2-4 options.",
                                "minItems", 2,
                                "maxItems", 4,
                                "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "label", Map.of(
                                            "type", "string",
                                            "description", "The display text for this option (1-5 words)"
                                        ),
                                        "description", Map.of(
                                            "type", "string",
                                            "description", "Explanation of what this option means or what will happen if chosen"
                                        ),
                                        "preview", Map.of(
                                            "type", "string",
                                            "description", "Optional preview content rendered when this option is focused"
                                        )
                                    ),
                                    "required", List.of("label", "description")
                                )
                            ),
                            "multiSelect", Map.of(
                                "type", "boolean",
                                "description", "Set to true to allow the user to select multiple options",
                                "default", false
                            )
                        ),
                        "required", List.of("question", "header", "options")
                    )
                ),
                "answers", Map.of(
                    "type", "object",
                    "description", "User answers collected by the permission component"
                ),
                "annotations", Map.of(
                    "type", "object",
                    "description", "Optional per-question annotations from the user"
                ),
                "metadata", Map.of(
                    "type", "object",
                    "description", "Optional metadata for tracking purposes. Not displayed to user.",
                    "properties", Map.of(
                        "source", Map.of(
                            "type", "string",
                            "description", "Optional identifier for the source of this question"
                        )
                    )
                )
            ),
            "required", List.of("questions")
        );
    }

    @Override
    public CompletionStage<String> execute(Map<String, Object> args) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) args.get("questions");
            @SuppressWarnings("unchecked")
            Map<String, String> answers = (Map<String, String>) args.get("answers");
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) args.get("annotations");

            if (questions == null || questions.isEmpty()) {
                return CompletableFuture.completedFuture(
                    "{\"error\": \"At least one question is required\"}");
            }

            if (answers != null && !answers.isEmpty()) {
                // User has already answered - return plain text matching Open-ClaudeCode's
                // mapToolResultToToolResultBlockParam output, so the LLM can directly read the answers
                StringBuilder sb = new StringBuilder();
                sb.append("User has answered your questions: ");
                boolean first = true;
                for (Map.Entry<String, String> entry : answers.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append("\"").append(entry.getKey()).append("\"=\"").append(entry.getValue()).append("\"");
                    first = false;
                }
                sb.append(". You can now continue with the user's answers in mind.");
                return CompletableFuture.completedFuture(sb.toString());
            }

            // Return questions to be displayed to user
            return CompletableFuture.completedFuture(
                String.format("{\"questions\": %s, \"answers\": {}, \"status\": \"awaiting_response\"}",
                    gson.toJson(questions)));
        } catch (Exception e) {
            log.error("Error executing AskUserQuestion", e);
            return CompletableFuture.completedFuture(
                "{\"error\": \"Failed to process questions: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
