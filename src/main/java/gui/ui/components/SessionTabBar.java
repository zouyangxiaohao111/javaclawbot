package gui.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 顶部标签栏：管理 TabItem 列表 + 新建按钮
 * 采用 Claude 美学
 */
public class SessionTabBar extends HBox {

    private final HBox tabContainer;
    private final Label addBtn;
    private final List<TabItem> tabs = new ArrayList<>();
    private Consumer<String> onTabSelected;  // tabId
    private Consumer<String> onTabClosed;    // tabId
    private Runnable onNewTab;

    public SessionTabBar() {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(0);
        setPadding(new Insets(6, 8, 6, 8));
        setStyle("-fx-background-color: #f5f0e8; -fx-border-color: #e6dfd8; -fx-border-width: 0 0 1 0;");

        // 标签容器
        tabContainer = new HBox(4);
        tabContainer.setAlignment(Pos.CENTER_LEFT);

        // 弹性空间
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 新建按钮
        addBtn = new Label("+");
        addBtn.setStyle("-fx-font-size: 18px; -fx-text-fill: #6c6a64;"
            + " -fx-padding: 6 12 6 12; -fx-cursor: hand;"
            + " -fx-background-radius: 6;");
        addBtn.setOnMouseClicked(e -> { if (onNewTab != null) onNewTab.run(); });
        addBtn.setOnMouseEntered(e ->
            addBtn.setStyle(addBtn.getStyle() + " -fx-background-color: rgba(0,0,0,0.06);"));
        addBtn.setOnMouseExited(e -> {
            String s = addBtn.getStyle();
            addBtn.setStyle(s.replace(" -fx-background-color: rgba(0,0,0,0.06)", ""));
        });

        getChildren().addAll(tabContainer, spacer, addBtn);
    }

    public TabItem addTab(String tabId, String title) {
        TabItem tab = new TabItem(tabId, title);
        tab.setOnSelect(() -> {
            if (onTabSelected != null) onTabSelected.accept(tabId);
        });
        tab.setOnClose(() -> {
            if (onTabClosed != null) onTabClosed.accept(tabId);
        });
        tabs.add(tab);
        tabContainer.getChildren().add(tab);
        return tab;
    }

    public void removeTab(String tabId) {
        tabs.removeIf(t -> {
            if (t.getTabId().equals(tabId)) {
                tabContainer.getChildren().remove(t);
                return true;
            }
            return false;
        });
    }

    public void setActiveTab(String tabId) {
        for (TabItem t : tabs) {
            boolean match = t.getTabId().equals(tabId);
            t.setActive(match);
        }
    }

    public void updateTabTitle(String tabId, String title) {
        for (TabItem t : tabs) {
            if (t.getTabId().equals(tabId)) {
                t.setTitle(title);
                break;
            }
        }
    }

    public void updateTabStatus(String tabId, TabItem.Status status) {
        for (TabItem t : tabs) {
            if (t.getTabId().equals(tabId)) {
                t.setStatus(status);
                break;
            }
        }
    }

    public int getTabCount() { return tabs.size(); }

    public void setOnTabSelected(Consumer<String> handler) { this.onTabSelected = handler; }
    public void setOnTabClosed(Consumer<String> handler) { this.onTabClosed = handler; }
    public void setOnNewTab(Runnable handler) { this.onNewTab = handler; }
}
