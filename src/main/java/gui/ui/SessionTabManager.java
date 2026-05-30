package gui.ui;

import gui.ui.components.SessionTabBar;
import gui.ui.components.TabItem;
import gui.ui.components.ToolCallCard;
import gui.ui.components.ModelSelectorPopup;
import gui.ui.pages.ChatPage;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理会话与标签的映射关系
 *
 * 设计原则：
 * 1. 每个标签有唯一的、稳定的 tabId（UUID）
 * 2. TabSessionContext 保持所有会话状态（waitingForResponse, userMessageCount 等）
 * 3. 切换标签时只改变可见性，不改变状态
 * 4. 标题生成后通过回调更新标签标题
 */
@Slf4j
public class SessionTabManager {

    private final SessionTabBar tabBar;
    private final BackendBridge backendBridge;
    private final VBox chatArea; // 包含 tabBar + chatPages 的容器
    private final Map<String, ChatPage> tabChatPages = new ConcurrentHashMap<>();
    private final Map<String, String> tabSessionMap = new ConcurrentHashMap<>(); // tabId → sessionId
    private final Map<String, Double> tabScrollPositions = new ConcurrentHashMap<>(); // tabId → 滚动位置
    private String activeTabId = null;
    private int maxConcurrent = 4; // 默认值
    private final ModelSelectorPopup modelSelectorPopup = new ModelSelectorPopup();

    public SessionTabManager(SessionTabBar tabBar, BackendBridge backendBridge, VBox chatArea) {
        this.tabBar = tabBar;
        this.backendBridge = backendBridge;
        this.chatArea = chatArea;

        // 从配置读取最大并发数
        try {
            if (backendBridge.getConfig() != null) {
                this.maxConcurrent = backendBridge.getConfig().getAgents().getDefaults().getMaxConcurrent();
            }
        } catch (Exception e) {
            log.warn("读取 maxConcurrent 配置失败，使用默认值 4");
        }

        // 绑定标签栏事件
        tabBar.setOnTabSelected(this::switchToTab);
        tabBar.setOnTabClosed(this::closeTab);
        tabBar.setOnNewTab(this::createNewTab);

        // 设置 ProjectRegistry 变更回调，当 registry 变更时刷新当前活跃标签的徽标
        backendBridge.setOnRegistryChanged(() -> {
            if (activeTabId != null) {
                ChatPage activePage = tabChatPages.get(activeTabId);
                if (activePage != null) {
                    activePage.refreshProjectBadge();
                }
            }
        });

        // 设置标题生成回调，当标题生成成功时更新标签标题
        backendBridge.setOnTitleChanged(() -> {
            log.info("[标题回调] 触发，tabSessionMap大小={}, 内容={}", tabSessionMap.size(), tabSessionMap);
            // 遍历所有标签，找到对应的 sessionId 并更新标题
            try {
                var sessions = backendBridge.getSessionManager().listSessions();
                log.info("[标题回调] 会话列表大小={}", sessions.size());
                for (var entry : tabSessionMap.entrySet()) {
                    String tabId = entry.getKey();
                    String sessionId = entry.getValue();
                    log.info("[标题回调] 检查标签: tabId={}, sessionId={}", tabId, sessionId);
                    if (sessionId == null) continue;

                    for (var s : sessions) {
                        if (sessionId.equals(s.get("session_id"))) {
                            Object md = s.get("metadata");
                            log.info("[标题回调] 找到会话: sessionId={}, metadata={}", sessionId, md);
                            if (md instanceof Map<?, ?> metaMap) {
                                Object t = metaMap.get("title");
                                log.info("[标题回调] 标题值: {}", t);
                                if (t instanceof String ts && !ts.isBlank()) {
                                    log.info("[标题回调] 更新标签标题: tabId={}, title={}", tabId, ts);
                                    Platform.runLater(() -> updateTabTitle(tabId, ts));
                                }
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[标题回调] 异常: {}", e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 创建默认标签（初始化时调用）
     */
    public void createDefaultTab() {
        createNewTab();
    }

    /**
     * 创建新标签
     */
    public void createNewTab() {
        // 动态读取最新配置
        int currentMax = getMaxConcurrent();
        log.info("createNewTab: 当前标签数={}, 最大并发数={}", tabBar.getTabCount(), currentMax);
        if (tabBar.getTabCount() >= currentMax) {
            showConcurrencyLimitToast();
            return;
        }

        // 使用 UUID 确保 tabId 稳定
        String tabId = UUID.randomUUID().toString().substring(0, 8);
        String title = "新对话";

        log.info("[标签创建] tabId={}, sessionKey=cli:{}", tabId, tabId);

        // 创建标签
        TabItem tab = tabBar.addTab(tabId, title);
        tab.setStatus(TabItem.Status.IDLE);

        // 创建 ChatPage
        ChatPage chatPage = new ChatPage();
        chatPage.setBackendBridge(backendBridge);
        // 初始化项目徽标和 Popover（修复多标签系统下徽标无文字、点击无反应）
        chatPage.setProjectInfo(backendBridge.getProjectRegistry(),
            backendBridge.getConfig().getWorkspacePath());

        // 注册消息发送回调
        final String currentTabId = tabId;
        // 用于跟踪 edit_file/write_file 的参数 (toolCallId → file_path)
        final Map<String, String> fileEditParams = new java.util.HashMap<>();
        // 用于跟踪最后一个工具卡片
        final ToolCallCard[] lastToolCard = {null};

        // 设置停止回调
        chatPage.getChatInput().setOnStop(() -> backendBridge.stopMessage());

        chatPage.getChatInput().addSendListener(text -> {
            // 用于控制进度条更新频率的计数器
            final int[] progressCount = {0};

            log.info("[消息发送] tabId={}, text={}", currentTabId, text.length() > 50 ? text.substring(0, 50) + "..." : text);
            // 确保当前标签是活跃的
            backendBridge.setActiveTab(currentTabId);

            // 确保 session 已创建并记录 sessionId
            backendBridge.ensureSession();
            String sessionId = backendBridge.getActiveSessionId();
            if (sessionId != null && !tabSessionMap.containsKey(currentTabId)) {
                tabSessionMap.put(currentTabId, sessionId);
                log.info("[Session映射] tabId={}, sessionId={}", currentTabId, sessionId);
            }

            // 切换到发送中状态（显示停止按钮）
            chatPage.getChatInput().setSending(true);
            // 添加思考占位符
            chatPage.addThinkingPlaceholder();
            chatPage.setStatusText("● 思考中...");
            // 更新标签状态
            tabBar.updateTabStatus(currentTabId, TabItem.Status.RUNNING);

            // 获取附件路径（图片+其他文件）
            java.util.List<String> mediaPaths = chatPage.getChatInput().getAllAttachmentPaths();
            java.util.List<java.nio.file.Path> imagePaths = new java.util.ArrayList<>();
            for (String p : chatPage.getChatInput().getAttachedImages()) {
                imagePaths.add(java.nio.file.Path.of(p));
            }

            backendBridge.sendMessage(text, mediaPaths,
                progress -> {
                    // 进度回调在 JavaFX 线程中执行
                    log.debug("[Progress] tabId={}, type={}, content={}", currentTabId,
                        progress.isToolResult() ? "toolResult" :
                        progress.isToolHint() ? "toolHint" :
                        progress.isReasoning() ? "reasoning" : "content",
                        progress.content() != null ? progress.content().substring(0, Math.min(40, progress.content().length())) : "null");
                    if (progress.isToolResult()) {
                        // 处理工具结果（TodoWrite、AskUserQuestion 等）
                        handleToolResult(chatPage, lastToolCard, fileEditParams, progress);
                    } else if (progress.isToolHint()) {
                        // 处理工具提示（显示工具调用卡片）
                        handleToolHint(chatPage, lastToolCard, fileEditParams, progress);
                    } else if (progress.isReasoning()) {
                        // 显示推理内容
                        chatPage.addReasoningBlock(progress.content());
                    } else {
                        // 流式进度文本：替换上一个气泡，避免 WebView 累积卡死 GUI
                        chatPage.addAssistantMessage(progress.content(), true);
                    }
                    // 实时更新上下文使用率（每 3 个进度事件更新一次，避免频繁刷新）
                    if (progressCount[0]++ % 3 == 0) {
                        chatPage.setContextUsage(backendBridge.getContextUsageRatioForTab(currentTabId));
                    }
                },
                response -> {
                    // 最终回复
                    log.debug("[最终回复] tabId={}, response={}", currentTabId,
                        response != null ? response.substring(0, Math.min(50, response.length())) : "null");
                    chatPage.getChatInput().setSending(false);
                    chatPage.removeThinkingPlaceholder();
                    // 先清除流式气泡和推理块追踪，再检查是否需要添加推理
                    // 顺序关键：工具调用轮次的流式推理属于前一轮，不应影响当前轮的推理判断
                    chatPage.clearStreamingBubble();
                    boolean hadStreamingReasoning = chatPage.hasStreamingReasoningBlocks();
                    log.debug("[DIAG] hadStreamingReasoning={}, streamingReasoningBlocks.size={}",
                        hadStreamingReasoning,
                        chatPage.getStreamingReasoningBlockCount());
                    String reasoning = backendBridge.getLastReasoningContent();
                    // 推理未通过流式展示 → 作为独立推理块添加（与历史恢复行为一致）
                    if (reasoning != null && !reasoning.isBlank() && !hadStreamingReasoning) {
                        chatPage.addReasoningBlock(reasoning);
                    }
                    // 回复内容作为独立气泡添加
                    chatPage.addAssistantMessage(response, false);
                    chatPage.setStatusText("● 模型就绪 · " + getCurrentModelName());
                    // 修复多标签上下文错乱：使用 tabId 精确获取对应标签的上下文
                    chatPage.setContextUsage(backendBridge.getContextUsageRatioForTab(currentTabId));
                    // 更新标签状态
                    tabBar.updateTabStatus(currentTabId, TabItem.Status.COMPLETED);
                    // 重置工具卡片
                    lastToolCard[0] = null;
                },
                error -> {
                    // 错误
                    log.error("[错误回复] tabId={}, error={}", currentTabId, error);
                    chatPage.getChatInput().setSending(false);
                    chatPage.removeThinkingPlaceholder();
                    chatPage.clearStreamingBubble();
                    chatPage.addAssistantMessage("⚠ " + error, false);
                    chatPage.setStatusText("● 错误");
                    // 更新标签状态
                    tabBar.updateTabStatus(currentTabId, TabItem.Status.ERROR);
                    // 重置工具卡片
                    lastToolCard[0] = null;
                }
            );
            chatPage.addUserMessage(text, imagePaths);
        });

        tabChatPages.put(tabId, chatPage);

        // 创建后端上下文
        backendBridge.createTabContext(tabId);
        log.debug("[后端上下文] 创建完成: tabId={}, sessionKey=cli:{}", tabId, tabId);

        // 将 ChatPage 添加到 chatArea
        chatPage.setVisible(false);
        chatPage.setManaged(false);
        VBox.setVgrow(chatPage, Priority.ALWAYS);
        chatArea.getChildren().add(chatPage);

        // 设置初始状态文本（包含模型名称）
        chatPage.setStatusText("● 模型就绪 · " + getCurrentModelName());

        // 接线模型选择器
        wireModelSelector(chatPage, tabId);

        // 激活新标签
        switchToTab(tabId);

        log.info("[标签创建完成] tabId={}, 当前标签数={}", tabId, tabBar.getTabCount());
    }

    /**
     * 切换到指定标签
     */
    public void switchToTab(String tabId) {
        if (activeTabId != null && activeTabId.equals(tabId)) return;

        log.debug("[标签切换] 从 {} 切换到 {}", activeTabId, tabId);

        // 保存当前标签的滚动位置
        if (activeTabId != null) {
            ChatPage oldPage = tabChatPages.get(activeTabId);
            if (oldPage != null) {
                tabScrollPositions.put(activeTabId, oldPage.getScrollPosition());
                oldPage.setVisible(false);
                oldPage.setManaged(false);
            }
        }

        // 显示目标标签内容
        activeTabId = tabId;
        ChatPage newPage = tabChatPages.get(tabId);
        if (newPage != null) {
            newPage.setVisible(true);
            newPage.setManaged(true);
            // 恢复目标标签的滚动位置：等待布局完成后恢复，避免被 vvalue 监听器干扰
            Double savedPosition = tabScrollPositions.get(tabId);
            if (savedPosition != null) {
                log.debug("[标签切换] 恢复滚动位置: tabId={}, position={}", tabId, savedPosition);
                newPage.setScrollPosition(savedPosition);
            } else {
                log.debug("[标签切换] 无保存的滚动位置: tabId={}", tabId);
            }
            log.debug("[标签切换] ChatPage 已显示: tabId={}", tabId);
        } else {
            log.debug("[标签切换] ChatPage 不存在: tabId={}", tabId);
        }

        // 更新标签栏状态
        tabBar.setActiveTab(tabId);

        // 更新后端活跃标签
        backendBridge.setActiveTab(tabId);

        // 刷新状态栏模型显示 + 项目徽标
        ChatPage activePage = tabChatPages.get(tabId);
        if (activePage != null) {
            activePage.setStatusText("\u25CF 模型就绪 \u00B7 " + getCurrentModelName() + " \u25BE");
            activePage.refreshProjectBadge();
        }

        // 检查 session 映射
        String sessionId = tabSessionMap.get(tabId);
        log.debug("[标签切换] sessionId={}", sessionId);
    }

    /**
     * 关闭标签
     */
    public void closeTab(String tabId) {
        ChatPage page = tabChatPages.remove(tabId);
        if (page != null) {
            page.setVisible(false);
            page.setManaged(false);
            chatArea.getChildren().remove(page);
        }

        // 清理后端上下文
        backendBridge.destroyTabContext(tabId);
        tabSessionMap.remove(tabId);

        // 移除标签
        tabBar.removeTab(tabId);

        // 如果关闭的是当前标签，切换到最近的标签
        if (tabId.equals(activeTabId)) {
            activeTabId = null;
            if (!tabChatPages.isEmpty()) {
                String newActive = tabChatPages.keySet().iterator().next();
                switchToTab(newActive);
            }
        }

        log.info("关闭标签: tabId={}", tabId);
    }

    /**
     * 通过 sessionId 切换标签（sidebar 触发）
     * 如果已有标签对应此 session，直接切换
     * 如果没有且超出限制，在当前标签加载会话并更新标题
     */
    public void switchToSession(String sessionId) {
        // 查找是否已有标签对应此 session
        for (Map.Entry<String, String> entry : tabSessionMap.entrySet()) {
            if (sessionId.equals(entry.getValue())) {
                switchToTab(entry.getKey());
                return;
            }
        }

        // 没找到对应标签，检查是否超出限制
        if (tabBar.getTabCount() >= getMaxConcurrent()) {
            // 超出限制：显示提示
            showConcurrencyLimitToast();
            return;
        }

        // 使用 UUID 确保 tabId 稳定
        String tabId = UUID.randomUUID().toString().substring(0, 8);
        // 尝试从会话元数据获取标题，否则使用截断的 sessionId
        String title = "会话 " + sessionId.substring(0, Math.min(8, sessionId.length()));
        try {
            var sessions = backendBridge.getSessionManager().listSessions();
            for (var s : sessions) {
                if (sessionId.equals(s.get("session_id"))) {
                    // title 嵌套在 metadata 对象中
                    Object md = s.get("metadata");
                    if (md instanceof Map<?, ?> metaMap) {
                        Object t = metaMap.get("title");
                        if (t instanceof String ts && !ts.isBlank()) {
                            title = ts;
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}

        // 创建标签
        TabItem tab = tabBar.addTab(tabId, title);
        tab.setStatus(TabItem.Status.IDLE);

        // 创建 ChatPage
        ChatPage chatPage = new ChatPage();
        chatPage.setBackendBridge(backendBridge);
        // 初始化项目徽标和 Popover
        chatPage.setProjectInfo(backendBridge.getProjectRegistry(),
            backendBridge.getConfig().getWorkspacePath());

        // 注册消息发送回调 - 每个标签页独立的状态
        final String currentTabId = tabId;
        // 用于跟踪 edit_file/write_file 的参数 (toolCallId → file_path)
        final Map<String, String> fileEditParams = new java.util.HashMap<>();
        // 用于跟踪最后一个工具卡片
        final ToolCallCard[] lastToolCard = {null};

        // 设置停止回调
        chatPage.getChatInput().setOnStop(() -> backendBridge.stopMessage());

        chatPage.getChatInput().addSendListener(text -> {
            // 用于控制进度条更新频率的计数器
            final int[] progressCount = {0};

            // 确保当前标签是活跃的
            backendBridge.setActiveTab(currentTabId);

            // 切换到发送中状态（显示停止按钮）
            chatPage.getChatInput().setSending(true);
            // 添加思考占位符
            chatPage.addThinkingPlaceholder();
            chatPage.setStatusText("● 思考中...");
            // 更新标签状态
            tabBar.updateTabStatus(currentTabId, TabItem.Status.RUNNING);

            // 获取附件路径（图片+其他文件）
            java.util.List<String> mediaPaths = chatPage.getChatInput().getAllAttachmentPaths();
            java.util.List<java.nio.file.Path> imagePaths = new java.util.ArrayList<>();
            for (String p : chatPage.getChatInput().getAttachedImages()) {
                imagePaths.add(java.nio.file.Path.of(p));
            }

            backendBridge.sendMessage(text, mediaPaths,
                progress -> {
                    // 进度回调在 JavaFX 线程中执行
                    if (progress.isToolResult()) {
                        // 处理工具结果（TodoWrite、AskUserQuestion 等）
                        handleToolResult(chatPage, lastToolCard, fileEditParams, progress);
                    } else if (progress.isToolHint()) {
                        // 处理工具提示（显示工具调用卡片）
                        handleToolHint(chatPage, lastToolCard, fileEditParams, progress);
                    } else if (progress.isReasoning()) {
                        // 显示推理内容
                        chatPage.addReasoningBlock(progress.content());
                    } else {
                        // 流式进度文本：替换上一个气泡，避免 WebView 累积卡死 GUI
                        chatPage.addAssistantMessage(progress.content(), true);
                    }
                    // 实时更新上下文使用率（每 3 个进度事件更新一次，避免频繁刷新）
                    if (progressCount[0]++ % 3 == 0) {
                        chatPage.setContextUsage(backendBridge.getContextUsageRatioForTab(currentTabId));
                    }
                },
                response -> {
                    // 最终回复
                    chatPage.getChatInput().setSending(false);
                    chatPage.removeThinkingPlaceholder();
                    // 先清除流式气泡和推理块追踪，再检查是否需要添加推理
                    chatPage.clearStreamingBubble();
                    boolean hadStreamingReasoning = chatPage.hasStreamingReasoningBlocks();
                    String reasoning = backendBridge.getLastReasoningContent();
                    // 推理未通过流式展示 → 作为独立推理块添加（与历史恢复行为一致）
                    if (reasoning != null && !reasoning.isBlank() && !hadStreamingReasoning) {
                        chatPage.addReasoningBlock(reasoning);
                    }
                    // 回复内容作为独立气泡添加
                    chatPage.addAssistantMessage(response, false);
                    chatPage.setStatusText("● 模型就绪 · " + getCurrentModelName());
                    // 修复多标签上下文错乱：使用 tabId 精确获取对应标签的上下文
                    chatPage.setContextUsage(backendBridge.getContextUsageRatioForTab(currentTabId));
                    // 更新标签状态
                    tabBar.updateTabStatus(currentTabId, TabItem.Status.COMPLETED);
                    // 重置工具卡片
                    lastToolCard[0] = null;
                },
                error -> {
                    // 错误
                    chatPage.getChatInput().setSending(false);
                    chatPage.removeThinkingPlaceholder();
                    chatPage.clearStreamingBubble();
                    chatPage.addAssistantMessage("⚠ " + error, false);
                    chatPage.setStatusText("● 错误");
                    // 更新标签状态
                    tabBar.updateTabStatus(currentTabId, TabItem.Status.ERROR);
                    // 重置工具卡片
                    lastToolCard[0] = null;
                }
            );
            chatPage.addUserMessage(text, imagePaths);
        });

        tabChatPages.put(tabId, chatPage);

        // 创建后端上下文并恢复会话
        backendBridge.createTabContext(tabId);
        backendBridge.setActiveTab(tabId);
        backendBridge.resumeSession(tabId, sessionId);
        tabSessionMap.put(tabId, sessionId);

        // 设置备份管理器（必须在 loadMessages 之前，否则工具卡片的对比/回滚按钮无法获取 FileBackupManager）
        agent.tool.file.FileBackupManager fbm = backendBridge.getFileBackupManager();
        if (fbm != null) {
            chatPage.getFileDiffBadge().setBackupManager(fbm);
            chatPage.getFileDiffBadge().loadFromBackupManager();
        }

        // 加载历史消息
        var history = backendBridge.getSessionHistory(sessionId);
        chatPage.loadMessages(history);
        // 恢复历史会话后更新上下文使用率
        chatPage.setContextUsage(backendBridge.getContextUsageRatioForTab(tabId));

        // 将 ChatPage 添加到 chatArea
        chatPage.setVisible(false);
        chatPage.setManaged(false);
        VBox.setVgrow(chatPage, Priority.ALWAYS);
        chatArea.getChildren().add(chatPage);

        // 接线模型选择器
        wireModelSelector(chatPage, tabId);

        // 激活新标签
        switchToTab(tabId);

        log.info("恢复会话到新标签: tabId={}, sessionId={}", tabId, sessionId);
    }

    /**
     * 在当前标签加载指定会话（超出标签限制时使用）
     */
    private void loadSessionInCurrentTab(String sessionId) {
        if (activeTabId == null) {
            log.warn("无活跃标签，无法加载会话");
            return;
        }

        ChatPage chatPage = tabChatPages.get(activeTabId);
        if (chatPage == null) {
            log.warn("活跃标签的 ChatPage 不存在: {}", activeTabId);
            return;
        }

        // 获取会话标题
        String title = "会话 " + sessionId.substring(0, Math.min(8, sessionId.length()));
        try {
            var sessions = backendBridge.getSessionManager().listSessions();
            for (var s : sessions) {
                if (sessionId.equals(s.get("session_id"))) {
                    Object md = s.get("metadata");
                    if (md instanceof Map<?, ?> metaMap) {
                        Object t = metaMap.get("title");
                        if (t instanceof String ts && !ts.isBlank()) {
                            title = ts;
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}

        // 更新标签标题
        tabBar.updateTabTitle(activeTabId, title);
        tabSessionMap.put(activeTabId, sessionId);

        // 恢复会话
        backendBridge.setActiveTab(activeTabId);
        backendBridge.resumeSession(activeTabId, sessionId);

        // 设置备份管理器（必须在 loadMessages 之前）
        agent.tool.file.FileBackupManager fbm = backendBridge.getFileBackupManager();
        if (fbm != null) {
            chatPage.getFileDiffBadge().setBackupManager(fbm);
            chatPage.getFileDiffBadge().loadFromBackupManager();
        }

        // 加载历史消息
        var history = backendBridge.getSessionHistory(sessionId);
        chatPage.loadMessages(history);
        // 恢复历史会话后更新上下文使用率
        chatPage.setContextUsage(backendBridge.getContextUsageRatioForTab(activeTabId));

        log.info("在当前标签加载会话: tabId={}, sessionId={}, title={}", activeTabId, sessionId, title);
    }

    /**
     * 通过 sessionId 关闭标签
     */
    public void closeTabBySession(String sessionId) {
        String tabIdToRemove = null;
        for (Map.Entry<String, String> entry : tabSessionMap.entrySet()) {
            if (sessionId.equals(entry.getValue())) {
                tabIdToRemove = entry.getKey();
                break;
            }
        }
        if (tabIdToRemove != null) {
            closeTab(tabIdToRemove);
        }
    }

    public ChatPage getActiveChatPage() {
        return activeTabId != null ? tabChatPages.get(activeTabId) : null;
    }

    public String getActiveTabId() {
        return activeTabId;
    }

    public int getTabCount() {
        return tabBar.getTabCount();
    }

    /**
     * 获取当前模型名称
     * 优先使用标签级别模型，否则使用全局默认模型
     */
    private String getCurrentModelName() {
        try {
            // 优先检查标签级别模型
            if (activeTabId != null && backendBridge != null) {
                String[] modelConfig = backendBridge.getModelForTab(activeTabId);
                if (modelConfig[1] != null && !modelConfig[1].isBlank()) {
                    return modelConfig[1];
                }
            }
            // 回退到全局默认模型
            if (backendBridge != null && backendBridge.getConfig() != null) {
                String model = backendBridge.getConfig().getAgents().getDefaults().getModel();
                if (model != null && !model.isBlank()) {
                    return model;
                }
            }
        } catch (Exception e) {
            log.warn("获取模型名称失败: {}", e.getMessage());
        }
        return "未知模型";
    }

    /**
     * 更新标签标题
     */
    public void updateTabTitle(String tabId, String title) {
        tabBar.updateTabTitle(tabId, title);
    }

    /**
     * 从会话列表更新所有标签的标题
     * 当标题生成完成后调用
     */
    public void updateTitlesFromSessions() {
        log.info("[标题更新] 开始更新标签标题，tabSessionMap大小={}", tabSessionMap.size());
        try {
            var sessions = backendBridge.getSessionManager().listSessions();
            for (var entry : tabSessionMap.entrySet()) {
                String tabId = entry.getKey();
                String sessionId = entry.getValue();
                if (sessionId == null) continue;

                for (var s : sessions) {
                    if (sessionId.equals(s.get("session_id"))) {
                        Object md = s.get("metadata");
                        if (md instanceof Map<?, ?> metaMap) {
                            Object t = metaMap.get("title");
                            if (t instanceof String ts && !ts.isBlank()) {
                                log.info("[标题更新] 更新标签: tabId={}, title={}", tabId, ts);
                                Platform.runLater(() -> updateTabTitle(tabId, ts));
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[标题更新] 异常: {}", e.getMessage());
        }
    }

    /**
     * 更新标签状态
     */
    public void updateTabStatus(String tabId, TabItem.Status status) {
        tabBar.updateTabStatus(tabId, status);
    }

    /**
     * 为 ChatPage 接线模型选择器点击回调
     */
    private void wireModelSelector(ChatPage chatPage, String tabId) {
        chatPage.getChatInput().setOnModelClick(() -> {
            if (modelSelectorPopup.isShowing()) {
                modelSelectorPopup.hide();
                return;
            }
            String[] modelConfig = backendBridge.getModelForTab(tabId);
            String curProvider = modelConfig[0];
            String curModel = modelConfig[1];
            modelSelectorPopup.show(
                chatPage.getChatInput().getLeftStatusLabel(),
                backendBridge.getConfig(),
                tabId,
                curProvider,
                curModel,
                (provider, model) -> {
                    backendBridge.setModelForTab(tabId, provider, model);
                    chatPage.getChatInput().updateModelDisplayName(model);
                }
            );
        });
    }

    /**
     * 动态读取最大并发数配置
     */
    private int getMaxConcurrent() {
        try {
            if (backendBridge != null && backendBridge.getConfig() != null) {
                return backendBridge.getConfig().getAgents().getDefaults().getMaxConcurrent();
            }
        } catch (Exception e) {
            log.warn("读取 maxConcurrent 配置失败，使用默认值");
        }
        return maxConcurrent;
    }

    private void showConcurrencyLimitToast() {
        int currentMax = getMaxConcurrent();
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("会话数量限制");
            alert.setHeaderText(null);
            alert.setContentText("已达到最大并发数 (" + currentMax + ")，请关闭空闲标签后重试");
            alert.show();
        });
    }

    /**
     * 处理工具结果（TodoWrite、AskUserQuestion 等）
     */
    private void handleToolResult(ChatPage chatPage, ToolCallCard[] lastToolCard,
                                  Map<String, String> fileEditParams,
                                  BackendBridge.ProgressEvent progress) {
        String tn = progress.toolName();
        String content = progress.content();
        String tcId = progress.toolCallId();
        if (content == null || content.isBlank()) return;

        // 仅当结果为合法 JSON 且 status="awaiting_response" 时进入对话框流程
        if (isAwaitingResponse(content)) {
            showAskUserQuestionDialog(content, tcId);
        } else if ("AskUserQuestion".equals(tn)) {
            if (content.contains("\"questions\"")) {
                if (lastToolCard[0] != null) {
                    lastToolCard[0].setStatus("completed");
                    lastToolCard[0].addStructuredContent(
                        gui.ui.components.AskQuestionResultView.build(content));
                }
            } else if (lastToolCard[0] != null) {
                lastToolCard[0].setStatus("completed");
                lastToolCard[0].addResult(content);
            }
        } else if ("TodoWrite".equals(tn)) {
            chatPage.getFileDiffBadge().updateTodoFromJson(content);
            if (lastToolCard[0] != null) {
                lastToolCard[0].setStatus("completed");
                lastToolCard[0].addStructuredContent(
                    gui.ui.components.TodoResultView.build(content));
            }
        } else if ("edit_file".equals(tn) || "write_file".equals(tn)) {
            // Structured file-change summary
            String filePath = tcId != null ? fileEditParams.remove(tcId) : null;
            if (filePath == null) {
                filePath = extractFilePathFromResult(content);
            }
            if (lastToolCard[0] != null) {
                lastToolCard[0].setStatus("completed");
                if (filePath != null && backendBridge != null) {
                    agent.tool.file.FileBackupManager fbm = backendBridge.getFileBackupManager();
                    int[] stats = parseDiffStats(content);
                    lastToolCard[0].setFileEditResult(filePath, stats[0], stats[1], fbm, null);
                    // Notify FileDiffBadge
                    if (fbm != null) {
                        try {
                            java.nio.file.Path p = java.nio.file.Path.of(filePath);
                            java.util.List<agent.tool.file.FileBackupManager.BackupEntry> vers = fbm.getVersions(p);
                            if (!vers.isEmpty()) {
                                chatPage.getFileDiffBadge().addModifiedFile(p, vers.get(vers.size() - 1));
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    lastToolCard[0].addResult(content);
                }
            }
        } else {
            if (lastToolCard[0] != null) {
                lastToolCard[0].setStatus("completed");
                lastToolCard[0].addResult(content);
            }
        }
    }

    /**
     * 处理工具提示（显示工具调用卡片）
     */
    private void handleToolHint(ChatPage chatPage, ToolCallCard[] lastToolCard,
                                Map<String, String> fileEditParams,
                                BackendBridge.ProgressEvent progress) {
        // 工具提示到达时，先固化当前流式气泡（将伴随工具调用的内容文本从"可替换"转为"永久"）
        // 防止后续 clearStreamingBubble() 在最终回复时误删该内容
        chatPage.finalizeStreamingBubble();

        String toolName = progress.toolName() != null
            ? progress.toolName()
            : extractToolName(progress.content());
        String params = progress.content();

        if ("TodoWrite".equals(toolName)) {
            params = "更新任务列表";
        }

        // Store file_path for edit_file/write_file cards
        if (("edit_file".equals(toolName) || "write_file".equals(toolName))
                && progress.toolCallId() != null) {
            String filePath = extractFilePathFromParams(params);
            if (filePath != null) {
                fileEditParams.put(progress.toolCallId(), filePath);
            }
            // Show friendly params
            if (filePath != null) {
                params = java.nio.file.Path.of(filePath).getFileName().toString();
            }
        }

        ToolCallCard card = chatPage.addToolCallCard(toolName, "running", params, false);
        lastToolCard[0] = card;
    }

    /**
     * 检查工具结果是否为合法的 awaiting_response 格式
     */
    private static boolean isAwaitingResponse(String rawResult) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            Map<String, Object> map = gson.fromJson(rawResult, Map.class);
            return "awaiting_response".equals(map.get("status")) && map.containsKey("questions");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 显示 AskUserQuestion 对话框
     */
    private void showAskUserQuestionDialog(String json, String toolCallId) {
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = gson.fromJson(json, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) root.get("questions");

            if (questions == null || questions.isEmpty()) return;

            gui.ui.components.QuestionDialog dialog = new gui.ui.components.QuestionDialog(questions);
            // 使用 Platform.runLater 确保在 JavaFX 线程中显示对话框
            Platform.runLater(() -> {
                dialog.showAndWait().ifPresentOrElse(answers -> {
                    if (!answers.isEmpty() && toolCallId != null) {
                        backendBridge.answerUserQuestion(toolCallId, answers);
                    }
                }, () -> {
                    // 用户取消/关闭对话框 — 注入空答案解除 AgentLoop 阻塞
                    if (toolCallId != null) {
                        backendBridge.answerUserQuestion(toolCallId, java.util.Map.of());
                    }
                });
            });
        } catch (Exception e) {
            log.warn("显示 AskUserQuestion 对话框失败: {}", e.getMessage());
        }
    }

    /**
     * 提取工具名称
     */
    private static String extractToolName(String toolHint) {
        if (toolHint == null || toolHint.isBlank()) return "tool";
        int paren = toolHint.indexOf('(');
        if (paren > 0) return toolHint.substring(0, paren).trim();
        return toolHint.trim();
    }

    /**
     * 从工具参数中提取 file_path
     */
    private static String extractFilePathFromParams(String params) {
        if (params == null || params.isBlank()) return null;
        int idx = params.indexOf("file_path=");
        if (idx < 0) return null;
        String after = params.substring(idx + "file_path=".length());
        int end = after.length();
        for (String delim : new String[]{", old_string=", ", new_string=", ", content=", ", replace_all="}) {
            int d = after.indexOf(delim);
            if (d > 0 && d < end) end = d;
        }
        return after.substring(0, end).trim();
    }

    /**
     * 从工具结果中提取 file_path
     */
    private static String extractFilePathFromResult(String result) {
        if (result == null || result.isBlank()) return null;
        String prefix = "The file ";
        int start = result.indexOf(prefix);
        if (start >= 0) {
            String after = result.substring(start + prefix.length());
            String[] endMarkers = {" has been updated", " has been updated successfully", "."};
            for (String m : endMarkers) {
                int end = after.indexOf(m);
                if (end > 0) return after.substring(0, end).trim();
            }
        }
        String createPrefix = "File created successfully at: ";
        start = result.indexOf(createPrefix);
        if (start >= 0) {
            String after = result.substring(start + createPrefix.length());
            int end = after.indexOf('\n');
            return end > 0 ? after.substring(0, end).trim() : after.trim();
        }
        return null;
    }

    /**
     * 解析 unified diff 统计
     */
    private static int[] parseDiffStats(String result) {
        int added = 0, removed = 0;
        if (result == null || result.isBlank()) return new int[]{added, removed};
        for (String line : result.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) added++;
            else if (line.startsWith("-") && !line.startsWith("---")) removed++;
        }
        return new int[]{added, removed};
    }
}
