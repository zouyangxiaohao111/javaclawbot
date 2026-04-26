package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class Sidebar extends VBox {

    private static final int EXPANDED_WIDTH = 256;
    private static final int COLLAPSED_WIDTH = 64;

    private boolean expanded = true;
    private final VBox navContainer;
    private final List<NavigationItem> navItems = new ArrayList<>();
    private final List<Consumer<String>> pageChangeListeners = new ArrayList<>();
    private final List<Consumer<String>> resumeListeners = new ArrayList<>();
    private final List<Runnable> newChatListeners = new ArrayList<>();

    private VBox historyContainer;
    private Label titleLabel;
    private Button collapseBtn;

    public Sidebar() {
        setSpacing(0);
        setStyle("-fx-background-color: rgba(234, 232, 225, 0.6); -fx-border-color: rgba(0, 0, 0, 0.1); -fx-border-width: 0 1px 0 0;");
        setPrefWidth(EXPANDED_WIDTH);
        setMinWidth(EXPANDED_WIDTH);

        // Logo 区域
        HBox logoBox = createLogoBox();

        // 新对话按钮
        Button newChatButton = createNewChatButton();

        // 导航菜单
        navContainer = createNavigationMenu();

        // 历史对话
        ScrollPane historyScroll = createHistorySection();

        // 底部设置
        VBox settingsBox = createSettingsBox();

        getChildren().addAll(logoBox, newChatButton, navContainer, historyScroll, settingsBox);
        VBox.setVgrow(historyScroll, Priority.ALWAYS);
    }

    private HBox createLogoBox() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(8));
        box.setPrefHeight(48);
        box.setMinHeight(48);

        Label logo = new Label("J");
        logo.setStyle("-fx-background-color: #3b82f6; -fx-background-radius: 8px; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-family: Georgia;");
        logo.setPrefSize(32, 32);
        logo.setAlignment(Pos.CENTER);

        titleLabel = new Label("NexusAi");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 500;");

        collapseBtn = new Button("\u25C0");
        collapseBtn.setStyle("-fx-background-color: transparent; -fx-font-size: 12px;");
        collapseBtn.setOnAction(e -> toggle());
        HBox.setHgrow(collapseBtn, Priority.ALWAYS);
        collapseBtn.setAlignment(Pos.CENTER_RIGHT);

        box.getChildren().addAll(logo, titleLabel, collapseBtn);
        return box;
    }

    private Button createNewChatButton() {
        Button btn = new Button("+ 新对话");
        btn.setStyle("-fx-background-color: rgba(0, 0, 0, 0.05); -fx-background-radius: 8px; -fx-font-size: 14px; -fx-font-weight: 500; -fx-padding: 10px 12px;");
        btn.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(btn, new Insets(8));
        btn.setOnAction(e -> {
            notifyPageChange("chat");
            for (Runnable r : newChatListeners) r.run();
        });
        return btn;
    }

    private VBox createNavigationMenu() {
        VBox container = new VBox(2);
        container.setPadding(new Insets(4, 8, 4, 8));

        String[][] navData = {
            {"\uD83D\uDCAC", "Chat"},
            {"\uD83E\uDD16", "Models"},
            {"\uD83D\uDC64", "Agents"},
            {"\uD83D\uDCE1", "Channels"},
            {"\u26A1", "Skills"},
            {"\uD83D\uDD0C", "MCP"},
            {"\u23F0", "Cron Tasks"}
        };

        for (String[] item : navData) {
            NavigationItem navItem = new NavigationItem(item[0], item[1]);
            navItem.setOnMouseClicked(e -> {
                setActiveNavItem(navItem);
                notifyPageChange(item[1].toLowerCase().replace(" ", ""));
            });
            navItems.add(navItem);
            container.getChildren().add(navItem);
        }

        // 默认选中 Chat
        navItems.get(0).setActive(true);

        return container;
    }

    private ScrollPane createHistorySection() {
        historyContainer = new VBox(4);
        VBox container = historyContainer;
        container.setPadding(new Insets(12, 8, 8, 8));

        // 分隔线
        Line separator = new Line();
        separator.setEndX(240);
        separator.setStyle("-fx-stroke: rgba(0, 0, 0, 0.05);");
        container.getChildren().add(separator);

        // 历史分组
        addHistoryGroup(container, "今天", new String[][]{
            {"JavaFX UI 设计", "code"},
            {"ClawX 架构分析", null},
            {"子代理开发流程", "debug"},
            {"视觉伴侣集成", null}
        });

        addHistoryGroup(container, "昨天", new String[][]{
            {"配置管理优化", null},
            {"Telegram 通道集成", "channel"},
            {"MCP 工具重载", null},
            {"飞书消息处理", "feishu"}
        });

        addHistoryGroup(container, "更早", new String[][]{
            {"初始项目搭建", null},
            {"记忆系统初始化", null},
            {"技能系统开发", "skill"},
            {"CLI Agent 集成", null}
        });

        // 滚动容器
        ScrollPane scrollPane = new ScrollPane(container);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        return scrollPane;
    }

    private void addHistoryGroup(VBox parent, String groupName, String[][] items) {
        Label groupTitle = new Label(groupName);
        groupTitle.getStyleClass().add("group-title");
        groupTitle.setPadding(new Insets(8, 10, 4, 10));
        parent.getChildren().add(groupTitle);

        for (String[] item : items) {
            HistoryItem historyItem = new HistoryItem(item[0], item[1]);
            parent.getChildren().add(historyItem);
        }
    }

    private VBox createSettingsBox() {
        VBox container = new VBox(2);
        container.setPadding(new Insets(12, 8, 8, 8));

        // 分隔线
        Line separator = new Line();
        separator.setEndX(240);
        separator.setStyle("-fx-stroke: rgba(0, 0, 0, 0.05);");
        container.getChildren().add(separator);

        NavigationItem settingsItem = new NavigationItem("\u2699\uFE0F", "Settings");
        settingsItem.setOnMouseClicked(e -> notifyPageChange("settings"));

        NavigationItem devItem = new NavigationItem("\uD83D\uDD27", "Dev Console");
        devItem.setOnMouseClicked(e -> notifyPageChange("devconsole"));

        container.getChildren().addAll(settingsItem, devItem);
        return container;
    }

    private void setActiveNavItem(NavigationItem activeItem) {
        for (NavigationItem item : navItems) {
            item.setActive(item == activeItem);
        }
    }

    public void toggle() {
        expanded = !expanded;
        int targetWidth = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        setPrefWidth(targetWidth);
        setMinWidth(targetWidth);

        // 折叠时隐藏文字
        titleLabel.setVisible(expanded);
        titleLabel.setManaged(expanded);
        collapseBtn.setText(expanded ? "\u25C0" : "\u25B6");

        for (NavigationItem item : navItems) {
            item.getTextLabel().setVisible(expanded);
            item.getTextLabel().setManaged(expanded);
        }
    }

    public void addPageChangeListener(Consumer<String> listener) {
        pageChangeListeners.add(listener);
    }

    private void notifyPageChange(String page) {
        for (Consumer<String> listener : pageChangeListeners) {
            listener.accept(page);
        }
    }

    public void addNewChatListener(Runnable r) {
        newChatListeners.add(r);
    }

    public void addResumeListener(Consumer<String> listener) {
        resumeListeners.add(listener);
    }

    /**
     * 用后端数据刷新历史对话列表
     */
    public void refreshHistory(java.util.List<java.util.Map<String, Object>> sessions) {
        // 保留分隔线，清除其余历史项
        java.util.List<javafx.scene.Node> keep = new java.util.ArrayList<>();
        keep.add(historyContainer.getChildren().get(0)); // 分隔线
        historyContainer.getChildren().retainAll(keep);

        if (sessions == null || sessions.isEmpty()) return;

        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.List<java.util.Map<String, Object>> todaySessions = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> yesterdaySessions = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> earlierSessions = new java.util.ArrayList<>();

        for (java.util.Map<String, Object> s : sessions) {
            String updated = String.valueOf(s.getOrDefault("updated_at", ""));
            try {
                java.time.LocalDate d = java.time.LocalDateTime.parse(updated).toLocalDate();
                if (d.equals(today)) todaySessions.add(s);
                else if (d.equals(today.minusDays(1))) yesterdaySessions.add(s);
                else earlierSessions.add(s);
            } catch (Exception e) {
                earlierSessions.add(s);
            }
        }

        if (!todaySessions.isEmpty()) addHistoryGroup(historyContainer, "今天", todaySessions);
        if (!yesterdaySessions.isEmpty()) addHistoryGroup(historyContainer, "昨天", yesterdaySessions);
        if (!earlierSessions.isEmpty()) addHistoryGroup(historyContainer, "更早", earlierSessions);
    }

    private void addHistoryGroup(VBox parent, String groupName, java.util.List<java.util.Map<String, Object>> items) {
        Label groupTitle = new Label(groupName);
        groupTitle.getStyleClass().add("group-title");
        groupTitle.setPadding(new Insets(8, 10, 4, 10));
        parent.getChildren().add(groupTitle);

        for (java.util.Map<String, Object> item : items) {
            String sessionId = String.valueOf(item.getOrDefault("session_id", ""));
            String title = extractTitle(item);
            HistoryItem historyItem = new HistoryItem(title != null ? title : sessionId, null);
            historyItem.setOnMouseClicked(e -> {
                for (Consumer<String> listener : resumeListeners) {
                    listener.accept(sessionId);
                }
            });
            parent.getChildren().add(historyItem);
        }
    }

    private String extractTitle(java.util.Map<String, Object> sessionItem) {
        Object meta = sessionItem.get("metadata");
        if (meta instanceof java.util.Map) {
            Object title = ((java.util.Map<?, ?>) meta).get("title");
            if (title instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }
}
