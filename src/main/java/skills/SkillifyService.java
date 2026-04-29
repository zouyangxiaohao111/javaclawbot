package skills;

import config.agent.SessionMemoryConfig;
import config.agent.SkillifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;
import providers.LLMResponse;
import session.SessionMemoryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Skillify Service
 * 对齐 Open-ClaudeCode: src/skills/bundled/skillify.ts
 *
 * 从对话工作流程中提取最佳实践，生成可复用的 Skill 文件。
 */
public class SkillifyService {

    private static final Logger log = LoggerFactory.getLogger(SkillifyService.class);

    private final SkillifyConfig config;
    private final SessionMemoryConfig sessionMemoryConfig;
    private final SessionMemoryService sessionMemoryService;
    private final LLMProvider provider;

    public SkillifyService(
            SkillifyConfig config,
            SessionMemoryConfig sessionMemoryConfig,
            SessionMemoryService sessionMemoryService,
            LLMProvider provider) {
        this.config = config;
        this.sessionMemoryConfig = sessionMemoryConfig;
        this.sessionMemoryService = sessionMemoryService;
        this.provider = provider;
    }

    /**
     * 检查功能是否启用
     */
    public boolean isEnabled() {
        if (!config.isEnabled()) {
            return false;
        }

        // 如果强依赖 Session Memory，检查其是否启用
        if (config.shouldRequireSessionMemory() && !sessionMemoryConfig.isEffectivelyEnabled()) {
            log.debug("Skillify requires session memory but it's not enabled");
            return false;
        }

        return true;
    }

    /**
     * 生成 Skill
     * 对齐: skillify.ts getPromptForCommand
     *
     * @param sessionId 会话 ID
     * @param userDescription 用户描述的工作流程
     * @param currentMessages 当前会话消息
     * @param systemPrompt 系统提示词
     * @return 生成的 SKILL.md 内容
     */
    public String generateSkill(
            String sessionId,
            String userDescription,
            List<Map<String, Object>> currentMessages,
            String systemPrompt) {

        if (!isEnabled()) {
            log.warn("Skillify is not enabled");
            return null;
        }

        // 获取 Session Memory 内容
        String sessionMemory = null;
        if (sessionMemoryService != null && sessionMemoryConfig.isEffectivelyEnabled()) {
            sessionMemory = sessionMemoryService.getContent(sessionId);
        }

        // 提取用户消息
        List<String> userMessages = extractUserMessages(currentMessages);

        // 构建 prompt
        String prompt = buildSkillifyPrompt(userDescription, sessionMemory, userMessages);

        try {
            // 调用 LLM 生成
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", "You are an assistant that creates skill files."));
            messages.add(Map.of("role", "user", "content", prompt));

            LLMResponse response = provider.chatWithRetry(
                    messages,
                    null,
                    provider.getDefaultModel(),
                    8192,
                    0.3,
                    null,
                    null,
                    null,
                    null
            ).toCompletableFuture().join();

            if (response != null && !"error".equals(response.getFinishReason())) {
                return response.getContent();
            }
        } catch (Exception e) {
            log.warn("Skillify generation failed", e);
        }

        return null;
    }

    /**
     * 异步生成 Skill
     */
    public CompletableFuture<String> generateSkillAsync(
            String sessionId,
            String userDescription,
            List<Map<String, Object>> currentMessages,
            String systemPrompt) {

        return CompletableFuture.supplyAsync(() -> generateSkill(sessionId, userDescription, currentMessages, systemPrompt));
    }

    /**
     * 提取用户消息
     * 对齐: extractUserMessages() in skillify.ts
     */
    private List<String> extractUserMessages(List<Map<String, Object>> messages) {
        List<String> userMessages = new ArrayList<>();

        if (messages == null) {
            return userMessages;
        }

        // 获取最后一次压缩边界后的消息
        boolean afterBoundary = true;
        for (Map<String, Object> msg : messages) {
            // 找到压缩边界
            if (isCompactBoundaryMessage(msg)) {
                afterBoundary = true;
                continue;
            }

            if (!afterBoundary) {
                continue;
            }

            String role = msg.get("role") instanceof String r ? r : null;
            Object content = msg.get("content");

            if ("user".equals(role) && content instanceof String s && !s.isBlank()) {
                userMessages.add(s);
            }
        }

        return userMessages;
    }

    /**
     * 检查是否是压缩边界消息
     */
    private boolean isCompactBoundaryMessage(Map<String, Object> msg) {
        if (msg == null) return false;

        Object role = msg.get("role");
        if (!"system".equals(role)) return false;

        Object content = msg.get("content");
        if (content instanceof String s) {
            return s.contains("___COMPACT_BOUNDARY___");
        }

        return false;
    }

    /**
     * 构建 Skillify prompt
     * 对齐: SKILLIFY_PROMPT in skillify.ts:22-156
     */
    private String buildSkillifyPrompt(String userDescription, String sessionMemory, List<String> userMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Skillify\n\n");

        if (userDescription != null && !userDescription.isBlank()) {
            prompt.append("The user described this process as: \"").append(userDescription).append("\"\n\n");
        }

        prompt.append("## Session Memory:\n");
        if (sessionMemory != null && !sessionMemory.isBlank()) {
            prompt.append(sessionMemory);
        } else {
            prompt.append("No session memory available.");
        }
        prompt.append("\n\n");

        prompt.append("## User Messages (conversation workflow):\n");
        for (int i = 0; i < userMessages.size(); i++) {
            prompt.append("---\n");
            prompt.append(userMessages.get(i));
            prompt.append("\n");
        }
        prompt.append("\n");

        prompt.append("""
                ## Task:
                Based on the conversation above, create a SKILL.md file that captures this workflow as a reusable skill.

                The SKILL.md should include:
                1. **name**: A short, descriptive name for the skill
                2. **description**: What this skill does
                3. **trigger**: When to use this skill
                4. **steps**: Step-by-step instructions
                5. **examples**: Example usage

                Important:
                - Keep the skill focused and reusable
                - Extract the essential workflow, not every detail
                - Use clear, actionable language
                - Include any important notes or warnings

                Respond with only the SKILL.md content.
                """);

        return prompt.toString();
    }
}
