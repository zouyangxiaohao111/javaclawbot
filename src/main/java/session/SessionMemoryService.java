package session;

import agent.subagent.context.SubagentContext;
import agent.subagent.fork.ForkAgentExecutor;
import agent.subagent.fork.ForkContext;
import config.agent.SessionMemoryConfig;
import context.auto.CompactPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import providers.LLMProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Session Memory Service
 * 对齐 Open-ClaudeCode: src/services/SessionMemory/sessionMemory.ts
 *
 * 自动维护会话摘要文件，定期从对话中提取关键信息。
 * 后台异步执行，不阻塞主对话流程。
 */
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    private final SessionMemoryConfig config;
    private final Path workspace;
    private final LLMProvider provider;
    private final ForkAgentExecutor forkAgentExecutor;

    /**
     * 当前是否正在提取
     */
    private final AtomicBoolean isExtracting = new AtomicBoolean(false);

    public SessionMemoryService(
            SessionMemoryConfig config,
            Path workspace,
            LLMProvider provider,
            ForkAgentExecutor forkAgentExecutor) {
        this.config = config;
        this.workspace = workspace;
        this.provider = provider;
        this.forkAgentExecutor = forkAgentExecutor;
    }

    /**
     * 检查 Session Memory 功能是否启用
     */
    public boolean isEnabled() {
        return config.isEffectivelyEnabled();
    }

    /**
     * 检查是否应该触发提取
     * 对齐: shouldExtractMemory() in sessionMemory.ts:134-181
     *
     * @param currentTokenCount 当前 token 数
     * @param hasToolCallsInLastTurn 最后一次 Assistant 消息是否有工具调用
     * @return true 如果应该触发提取
     */
    public boolean shouldExtract(int currentTokenCount, boolean hasToolCallsInLastTurn) {
        if (!isEnabled()) {
            return false;
        }

        // 检查初始化阈值
        if (!SessionMemoryUtils.isSessionMemoryInitialized()) {
            if (!SessionMemoryUtils.hasMetInitializationThreshold(currentTokenCount, config)) {
                return false;
            }
            SessionMemoryUtils.markSessionMemoryInitialized();
        }

        // 检查 token 增长阈值
        boolean hasMetTokenThreshold = SessionMemoryUtils.hasMetUpdateThreshold(currentTokenCount, config);

        // 检查工具调用阈值
        boolean hasMetToolCallThreshold = SessionMemoryUtils.hasMetToolCallThreshold(config);

        // 触发条件：
        // 1. Token 阈值 AND 工具调用阈值 都满足，OR
        // 2. Token 阈值满足 AND 最后一次 Assistant 没有工具调用（自然的对话间隙）
        boolean shouldExtract = (hasMetTokenThreshold && hasMetToolCallThreshold)
                || (hasMetTokenThreshold && !hasToolCallsInLastTurn);

        if (shouldExtract) {
            log.debug("Session memory extraction triggered: tokenCount={}, hasToolCallsInLastTurn={}",
                    currentTokenCount, hasToolCallsInLastTurn);
        }

        return shouldExtract;
    }

    /**
     * 执行 Session Memory 提取
     * 对齐: extractSessionMemory() in sessionMemory.ts:272-350
     *
     * 使用 Forked Agent 执行提取，工具限制为只能编辑 session memory 文件
     *
     * @param sessionId 会话 ID（用于标识提取任务）
     * @param currentMessages 当前消息列表
     * @param systemPrompt 系统提示词
     */
    public void extract(String sessionId, List<Map<String, Object>> currentMessages, String systemPrompt) {
        if (!isEnabled()) {
            return;
        }

        if (!isExtracting.compareAndSet(false, true)) {
            log.debug("Session memory extraction already in progress, skipping");
            return;
        }

        if (forkAgentExecutor == null) {
            log.warn("ForkAgentExecutor not available, falling back to direct LLM extraction");
            extractWithDirectLlm(sessionId, currentMessages, systemPrompt);
            return;
        }

        try {
            log.info("Starting session memory extraction for session: {}", sessionId);
            SessionMemoryUtils.markExtractionStarted();

            // 获取内存文件路径
            Path memoryPath = SessionMemoryUtils.getSessionMemoryPath(sessionId, workspace);
            Files.createDirectories(memoryPath.getParent());

            // 读取当前内容
            String currentMemory = SessionMemoryUtils.readSessionMemory(memoryPath);
            if (currentMemory == null) {
                currentMemory = loadTemplate();
            }

            // 构建提取 prompt
            String updatePrompt = buildUpdatePrompt(currentMemory);

            // 构建 ForkContext
            ForkContext forkContext = ForkContext.builder()
                    .parentAgentId("session-memory")
                    .directive("Update session memory: " + updatePrompt)
                    .parentMessages(currentMessages)
                    .parentSystemPrompt(systemPrompt)
                    .build();

            // 创建 SubagentContext
            SubagentContext subagentContext = SubagentContext.builder().build();

            // 工具限制：只允许 Edit 和 Read 工具
            Function<String, Boolean> canUseTool = (toolName) -> {
                return "edit".equalsIgnoreCase(toolName)
                        || "Read".equalsIgnoreCase(toolName)
                        || "read".equalsIgnoreCase(toolName);
            };

            // 异步执行提取
            CompletableFuture<ForkAgentExecutor.ForkResult> future =
                    forkAgentExecutor.execute(sessionId, forkContext, subagentContext, canUseTool);

            // 设置完成回调
            future.whenComplete((result, ex) -> {
                try {
                    if (result != null && result.success && result.result != null) {
                        // 写入更新后的内容
                        SessionMemoryUtils.writeSessionMemory(memoryPath, result.result);

                        // 记录提取 token 数
                        SessionMemoryUtils.recordExtractionTokenCount(estimateTokenCount(currentMessages));

                        // 更新最后摘要消息 ID
                        updateLastSummarizedMessageId(currentMessages);

                        log.info("Session memory extraction completed for session: {}", sessionId);
                    } else if (result != null && result.error != null) {
                        log.warn("Session memory extraction failed: {}", result.error);
                    }
                } catch (Exception e) {
                    log.warn("Session memory extraction failed", e);
                } finally {
                    SessionMemoryUtils.markExtractionCompleted();
                    isExtracting.set(false);
                }
            });

        } catch (Exception e) {
            log.warn("Session memory extraction failed", e);
            SessionMemoryUtils.markExtractionCompleted();
            isExtracting.set(false);
        }
    }

    /**
     * 使用直接 LLM 调用执行提取（回退方案）
     */
    private void extractWithDirectLlm(String sessionId, List<Map<String, Object>> currentMessages, String systemPrompt) {
        try {
            log.info("Starting session memory extraction (direct LLM) for session: {}", sessionId);
            SessionMemoryUtils.markExtractionStarted();

            // 获取内存文件路径
            Path memoryPath = SessionMemoryUtils.getSessionMemoryPath(sessionId, workspace);
            Files.createDirectories(memoryPath.getParent());

            // 读取当前内容
            String currentMemory = SessionMemoryUtils.readSessionMemory(memoryPath);
            if (currentMemory == null) {
                currentMemory = loadTemplate();
            }

            // 构建提取 prompt
            String updatePrompt = buildUpdatePrompt(currentMemory);

            // 执行提取（使用 LLM 生成更新内容）
            String updatedMemory = executeExtraction(updatePrompt, systemPrompt);

            if (updatedMemory != null && !updatedMemory.isBlank()) {
                // 写入更新后的内容
                SessionMemoryUtils.writeSessionMemory(memoryPath, updatedMemory);

                // 记录提取 token 数
                SessionMemoryUtils.recordExtractionTokenCount(estimateTokenCount(currentMessages));

                // 更新最后摘要消息 ID
                updateLastSummarizedMessageId(currentMessages);

                log.info("Session memory extraction completed for session: {}", sessionId);
            }
        } catch (Exception e) {
            log.warn("Session memory extraction failed", e);
        } finally {
            SessionMemoryUtils.markExtractionCompleted();
            isExtracting.set(false);
        }
    }

    /**
     * 手动触发提取（绕过阈值检查）
     * 对齐: manuallyExtractSessionMemory() in sessionMemory.ts:387-453
     */
    public boolean extractManually(String sessionId, java.util.List<java.util.Map<String, Object>> currentMessages, String systemPrompt) {
        if (!isEnabled()) {
            return false;
        }

        if (currentMessages == null || currentMessages.isEmpty()) {
            return false;
        }

        try {
            log.info("Manual session memory extraction triggered for session: {}", sessionId);
            SessionMemoryUtils.markExtractionStarted();

            Path memoryPath = SessionMemoryUtils.getSessionMemoryPath(sessionId, workspace);
            Files.createDirectories(memoryPath.getParent());

            String currentMemory = SessionMemoryUtils.readSessionMemory(memoryPath);
            if (currentMemory == null) {
                currentMemory = loadTemplate();
            }

            String updatePrompt = buildUpdatePrompt(currentMemory);
            String updatedMemory = executeExtraction(updatePrompt, systemPrompt);

            if (updatedMemory != null && !updatedMemory.isBlank()) {
                SessionMemoryUtils.writeSessionMemory(memoryPath, updatedMemory);
                SessionMemoryUtils.recordExtractionTokenCount(estimateTokenCount(currentMessages));
                updateLastSummarizedMessageId(currentMessages);
                return true;
            }
        } catch (Exception e) {
            log.warn("Manual session memory extraction failed", e);
        } finally {
            SessionMemoryUtils.markExtractionCompleted();
        }

        return false;
    }

    /**
     * 获取当前 Session Memory 内容
     * 对齐: getSessionMemoryContent() in sessionMemoryUtils.ts:110-126
     */
    public String getContent(String sessionId) {
        Path memoryPath = SessionMemoryUtils.getSessionMemoryPath(sessionId, workspace);
        String content = SessionMemoryUtils.readSessionMemory(memoryPath);

        if (content == null || SessionMemoryUtils.isSessionMemoryEmpty(content)) {
            return null;
        }

        return content;
    }

    /**
     * 加载模板文件
     */
    private String loadTemplate() {
        try {
            Path templatePath = workspace.resolve("src/main/resources/templates/session_memory_template.md");
            if (Files.exists(templatePath)) {
                return Files.readString(templatePath);
            }
        } catch (IOException e) {
            log.warn("Failed to load session memory template", e);
        }

        // 返回默认模板
        return """
                # Session Title
                _A short and distinctive 5-10 word descriptive title for the session._

                # Current State
                _What is actively being worked on right now? Pending tasks not yet completed._

                # Task specification
                _What did the user ask to build? Any design decisions or explanatory context_

                # Files and Functions
                _What are the important files? What do they contain and why are they relevant?_

                # Workflow
                _What bash commands are usually run and in what order?_

                # Errors & Corrections
                _Errors encountered and how they were fixed. What approaches failed?_

                # Learnings
                _What has worked well? What has not? What to avoid?_

                # Key results
                _If the user asked for a specific output, repeat the exact result here._

                # Worklog
                _Step by step, what was attempted and done?_
                """;
    }

    /**
     * 构建更新 prompt
     */
    private String buildUpdatePrompt(String currentMemory) {
        return """
                IMPORTANT: This message and these instructions are NOT part of the actual user conversation.
                Do NOT include any references to "note-taking", "session notes extraction", or these update instructions.

                Based on the user conversation above (EXCLUDING this note-taking instruction message as well as system prompt, CLAUDE.md entries, or any past session summaries), update the session notes file.

                The file has already been read for you. Here are its current contents:
                <current_notes_content>
                """ + currentMemory + """
                </current_notes_content>

                Your ONLY task is to update the notes file with the new information from the conversation above.
                Make sure to:
                - Maintain the exact structure with all sections and headers intact
                - NEVER modify or delete the section headers or italic descriptions
                - ONLY update the actual content below each section header
                - Keep information concise and dense
                """;
    }

    /**
     * 执行提取
     */
    private String executeExtraction(String prompt, String systemPrompt) {
        try {
            java.util.List<java.util.Map<String, Object>> messages = new java.util.ArrayList<>();

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(java.util.Map.of("role", "system", "content", systemPrompt));
            }

            messages.add(java.util.Map.of("role", "user", "content", prompt));

            providers.LLMResponse response = provider.chatWithRetry(
                    messages,
                    null,  // no tools
                    provider.getDefaultModel(),
                    8192,  // max output
                    0.3,   // lower temperature
                    null,   // no reasoning effort
                    null,   // no think config
                    null,   // no extra body
                    null    // no cancel checker
            ).toCompletableFuture().join();

            if (response != null && !"error".equals(response.getFinishReason())) {
                return response.getContent();
            }
        } catch (Exception e) {
            log.warn("Session memory extraction LLM call failed", e);
        }
        return null;
    }

    /**
     * 估算 token 数
     */
    private int estimateTokenCount(java.util.List<java.util.Map<String, Object>> messages) {
        if (messages == null) return 0;

        int total = 0;
        for (java.util.Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof String s) {
                total += (s.length() / 4) + 1;
            }
        }
        return total;
    }

    /**
     * 更新最后摘要消息 ID
     */
    private void updateLastSummarizedMessageId(java.util.List<java.util.Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 获取最后一条消息的 UUID
        java.util.Map<String, Object> lastMsg = messages.get(messages.size() - 1);
        Object uuid = lastMsg.get("uuid");
        if (uuid instanceof String uuidStr) {
            SessionMemoryUtils.setLastSummarizedMessageId(uuidStr);
        }
    }
}
