package gui.ui;

import config.Config;
import config.ConfigIO;
import lombok.extern.slf4j.Slf4j;
import providers.LLMProvider;
import session.Session;
import session.SessionManager;
import utils.Helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * 异步会话标题生成器。
 *
 * 当会话中用户消息数 >= 2 时触发，使用 LLM 生成 10 字以内的中文标题，
 * 存入 session.metadata["title"]。
 */
@Slf4j
public final class TitleGenerator {

    private TitleGenerator() {}

    /**
     * 使用 LLM 生成会话标题。同步执行，应在后台线程调用。
     * 如果已有标题则跳过。
     *
     * @param provider LLM provider
     * @param session  当前会话
     * @param fastModel 标题生成专用快速模型（null 则使用 provider 默认模型）
     */
    public static String generateTitle(LLMProvider provider, Session session, String fastModel) {
        return generateTitle(provider, session, fastModel, false);
    }

    public static void main(String[] args) {
        Config config = ConfigIO.loadConfig(null);
        String fastModel = config.getAgents().getDefaults().getFastModel();
        SessionManager sessionManager = new SessionManager(config.getWorkspacePath());
        List<Map<String, Object>> maps = sessionManager.listSessions();
        Map<String, Object> stringObjectMap = maps.get(0);
        String sessionId = (String) stringObjectMap.get("session_id");
        Session orCreate = sessionManager.getOrCreate("cli:direct");
        LLMProvider llmProvider = Helpers.makeHotProvider();


        String s = generateTitle(llmProvider, orCreate, fastModel, true);
        System.out.println("Generated title: " + s);
    }

    /**
     * 使用 LLM 生成会话标题。
     *
     * @param provider  LLM provider
     * @param session   当前会话
     * @param fastModel 标题生成专用快速模型（null 则使用 provider 默认模型）
     * @param force     即使已有标题也重新生成（用于对话深入后更新标题）
     * @return 生成的标题，失败时返回 null
     */
    public static String generateTitle(LLMProvider provider, Session session, String fastModel, boolean force) {
        if (provider == null || session == null) return null;
        try {
            String model = (fastModel != null && !fastModel.isBlank()) ? fastModel : provider.getDefaultModel();
            if (model == null || model.isBlank()) {
                log.info("无法生成标题: provider 没有默认模型");
                return null;
            }
            // 非强制模式下，已有标题则跳过
            Map<String, Object> meta = session.getMetadata();
            if (!force) {
                if (meta != null && meta.containsKey("title")) {
                    String existing = (String) meta.get("title");
                    if (existing != null && !existing.isBlank()) {
                        return existing; // 已有标题，不重复生成
                    }
                }
            }

            // 提取最近 6 条 user/assistant 消息作为上下文
            List<Map<String, Object>> messages = session.getMessages();
            List<Map<String, String>> contextMessages = new ArrayList<>();
            int count = 0;
            for (int i = messages.size() - 1; i >= 0 && count < 6; i--) {
                Map<String, Object> msg = messages.get(i);
                String role = (String) msg.get("role");
                if ("user".equals(role) || "assistant".equals(role)) {
                    String content = extractTextContent(msg.get("content"));
                    // 过滤掉空内容、"(empty)"占位符、纯工具调用消息
                    if (content != null && !content.isBlank()
                            && !"(empty)".equals(content.trim())
                            && content.trim().length() > 2) {
                        Map<String, String> m = new HashMap<>();
                        m.put("role", role);
                        m.put("content", content.length() > 200 ? content.substring(0, 200) : content);
                        contextMessages.add(0, m);
                        count++;
                    }
                }
            }

            if (contextMessages.isEmpty()) return null;

            // 构建消息
            List<Map<String, Object>> llmMessages = new ArrayList<>();
            Map<String, Object> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是一个标题生成助手。根据对话内容生成一个10字以内的中文标题，简洁概括对话主题。只输出标题，不要引号、标点或任何额外文字。");
            llmMessages.add(systemMsg);

            for (Map<String, String> cm : contextMessages) {
                Map<String, Object> m = new HashMap<>();
                m.put("role", cm.get("role"));
                m.put("content", cm.get("content"));
                llmMessages.add(m);
            }

            Map<String, Object> userPrompt = new HashMap<>();
            userPrompt.put("role", "user");
            userPrompt.put("content", "请为以上对话生成一个10字以内的中文标题。");
            llmMessages.add(userPrompt);

            // 调用 LLM（非流式，使用 chatWithRetry）
            java.util.concurrent.CompletableFuture<providers.LLMResponse> future;
            try {
                future = provider.chatWithRetry(
                        llmMessages,
                        null,   // tools
                        model,
                        50,     // max_tokens
                        0.3     // temperature
                );
            } catch (Exception ex) {
                log.warn("标题生成: chatWithRetry 调用失败: " + ex.getClass().getName() + " " + ex.getMessage(), ex);
                return null;
            }
            if (future == null) {
                log.warn("标题生成: chatWithRetry 返回 null future");
                return null;
            }
            providers.LLMResponse response;
            try {
                response = future.get(180, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException ex) {
                log.warn("标题生成: 请求超时(30s) model=" + model, ex);
                return null;
            } catch (Exception ex) {
                log.warn("标题生成: future.get 失败: type=" + ex.getClass().getSimpleName()
                        + " msg=" + (ex.getMessage() != null ? ex.getMessage() : "(null)")
                        + " model=" + model, ex);
                return null;
            }

            String title = response != null ? response.getContent() : null;
            if (response == null) {
                log.warn("标题生成: LLM 响应为 null");
                return null;
            }

            // 检查 LLM 是否返回了错误
            String finishReason = response.getFinishReason();
            if ("error".equals(finishReason)) {
                log.warn("标题生成: LLM 返回错误 finish_reason=error, content="
                    + (title != null ? title.substring(0, Math.min(200, title.length())) : "null")
                    + ", model=" + model);
                return null;
            }

            log.info("标题生成: LLM 原始响应 finish_reason=" + finishReason
                + ", raw_content=" + (title != null ? title.substring(0, Math.min(100, title.length())) : "null"));

            title = Helpers.stripThink(title);
            if (title != null) {
                title = title.trim()
                        .replaceAll("^[\"'\u201C\u201D\u2018\u2019\u300C\u300D]+", "")
                        .replaceAll("[\"'\u201C\u201D\u2018\u2019\u300C\u300D]+$", "")
                        .replaceAll("^[\u300A\u300E\u300F]", "")
                        .replaceAll("[\u300B\u300E\u300F]$", "");
                if (title.length() > 20) {
                    log.info("标题过长(" + title.length() + "字)，截断至20字: " + title.substring(0, 20));
                    title = title.substring(0, 20);
                }
            }

            if (title != null && !title.isBlank() && title.length() <= 20) {
                if (meta == null) {
                    meta = new HashMap<>();
                }
                meta.put("title", title);
                session.setMetadata(meta);
                log.info("标题已生成(AI): " + title);
                return title;
            }

            log.info("标题生成: AI 返回内容不可用 (null/blank/过长)");
            return null;
        } catch (Exception e) {
            String cause = e.getCause() != null ? e.getCause().toString() : "无cause";
            log.warn("标题生成失败: type=" + e.getClass().getName()
                + " msg=" + (e.getMessage() != null ? e.getMessage() : "(null)")
                + " cause=" + cause
                + " model=" + (provider != null ? provider.getDefaultModel() : "provider为null")
                + " sessionMsgs=" + (session != null ? session.getMessages().size() : "session为null"), e);
            return null;
        }
    }

    private static String extractTextContent(Object contentObj) {
        if (contentObj == null) return null;
        if (contentObj instanceof String s) {
            // 跳过 "(empty)" 占位符（纯 tool_calls 的 assistant 消息）
            if (s.isBlank() || "(empty)".equals(s.trim())) return null;
            return s;
        }
        if (contentObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if ("text".equals(map.get("type"))) {
                        Object text = map.get("text");
                        if (text instanceof String s) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(s);
                        }
                    }
                }
            }
            String result = sb.toString();
            if (result.isBlank()) return null;
            return result;
        }
        String result = String.valueOf(contentObj);
        if (result.isBlank() || "(empty)".equals(result.trim())) return null;
        return result;
    }
}
