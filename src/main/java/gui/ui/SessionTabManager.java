package gui.ui;

import gui.ui.components.SessionTabBar;
import gui.ui.components.TabItem;
import gui.ui.pages.ChatPage;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理会话与标签的映射关系
 */
@Slf4j
public class SessionTabManager {

    private final SessionTabBar tabBar;
    private final BackendBridge backendBridge;
    private final VBox chatArea; // 包含 tabBar + chatPages 的容器
    private final Map<String, ChatPage> tabChatPages = new ConcurrentHashMap<>();
    private final Map<String, String> tabSessionMap = new ConcurrentHashMap<>(); // tabId → sessionId
    private final AtomicInteger tabCounter = new AtomicInteger(0);
    private String activeTabId = null;
    private int maxConcurrent = 4; // 默认值

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

        String tabId = "tab-" + tabCounter.incrementAndGet();
        String title = "新对话 " + tabCounter.get();

        // 创建标签
        TabItem tab = tabBar.addTab(tabId, title);
        tab.setStatus(TabItem.Status.IDLE);

        // 创建 ChatPage
        ChatPage chatPage = new ChatPage();
        chatPage.setBackendBridge(backendBridge);
        tabChatPages.put(tabId, chatPage);

        // 创建后端上下文
        backendBridge.createTabContext(tabId);

        // 将 ChatPage 添加到 chatArea
        chatPage.setVisible(false);
        chatPage.setManaged(false);
        VBox.setVgrow(chatPage, Priority.ALWAYS);
        chatArea.getChildren().add(chatPage);

        // 激活新标签
        switchToTab(tabId);

        log.info("创建新标签: tabId={}", tabId);
    }

    /**
     * 切换到指定标签
     */
    public void switchToTab(String tabId) {
        if (activeTabId != null && activeTabId.equals(tabId)) return;

        // 隐藏当前标签内容
        if (activeTabId != null) {
            ChatPage oldPage = tabChatPages.get(activeTabId);
            if (oldPage != null) {
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
        }

        // 更新标签栏状态
        tabBar.setActiveTab(tabId);

        // 更新后端活跃标签
        backendBridge.setActiveTab(tabId);

        log.info("切换标签: tabId={}", tabId);
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
            // 超出限制：在当前标签加载会话，不创建新标签
            log.info("标签数已达上限，在当前标签加载会话: sessionId={}", sessionId);
            loadSessionInCurrentTab(sessionId);
            return;
        }

        String tabId = "tab-" + tabCounter.incrementAndGet();
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
        tabChatPages.put(tabId, chatPage);

        // 创建后端上下文并恢复会话
        backendBridge.createTabContext(tabId);
        backendBridge.setActiveTab(tabId);
        backendBridge.resumeSession(tabId, sessionId);
        tabSessionMap.put(tabId, sessionId);

        // 加载历史消息
        var history = backendBridge.getSessionHistory(sessionId);
        chatPage.loadMessages(history);

        // 将 ChatPage 添加到 chatArea
        chatPage.setVisible(false);
        chatPage.setManaged(false);
        VBox.setVgrow(chatPage, Priority.ALWAYS);
        chatArea.getChildren().add(chatPage);

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

        // 加载历史消息
        var history = backendBridge.getSessionHistory(sessionId);
        chatPage.loadMessages(history);

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
     * 更新标签标题
     */
    public void updateTabTitle(String tabId, String title) {
        tabBar.updateTabTitle(tabId, title);
    }

    /**
     * 更新标签状态
     */
    public void updateTabStatus(String tabId, TabItem.Status status) {
        tabBar.updateTabStatus(tabId, status);
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
}
