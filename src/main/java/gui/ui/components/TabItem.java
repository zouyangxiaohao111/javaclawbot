package gui.ui.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * 单个标签：颜色圆点 + 标题 + 关闭按钮
 * 采用 Claude 美学
 */
public class TabItem extends HBox {

    public enum Status { IDLE, RUNNING, COMPLETED, ERROR }

    private final String tabId;
    private final Label dotLabel;
    private final Label titleLabel;
    private final Label closeBtn;
    private Status currentStatus = Status.IDLE;
    private boolean active = false;
    private Timeline pulseAnimation;

    private Runnable onCloseAction;
    private Runnable onSelectAction;

    public TabItem(String tabId, String title) {
        this.tabId = tabId;
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        setPadding(new Insets(6, 14, 6, 14));
        setStyle("-fx-cursor: hand; -fx-background-radius: 6;");

        // 状态圆点
        dotLabel = new Label();
        dotLabel.setPrefSize(8, 8);
        dotLabel.setMinSize(8, 8);
        dotLabel.setMaxSize(8, 8);
        dotLabel.setStyle("-fx-background-radius: 4; -fx-background-color: #6c6a64;");

        // 标题
        titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: #3d3d3a;");
        titleLabel.setMaxWidth(160);
        titleLabel.setEllipsisString("...");

        // 关闭按钮
        closeBtn = new Label("\u00D7");
        closeBtn.setStyle("-fx-font-size: 12px; -fx-text-fill: #8e8b82; -fx-padding: 0 2 0 2;");
        closeBtn.setVisible(false);
        closeBtn.setOnMouseClicked(e -> {
            e.consume();
            if (onCloseAction != null) onCloseAction.run();
        });

        getChildren().addAll(dotLabel, titleLabel, closeBtn);

        // 点击选中
        setOnMouseClicked(e -> {
            if (onSelectAction != null) onSelectAction.run();
        });

        // hover 显示关闭按钮
        setOnMouseEntered(e -> closeBtn.setVisible(true));
        setOnMouseExited(e -> { if (!active) closeBtn.setVisible(false); });

        updateStyle();
    }

    public String getTabId() { return tabId; }

    public void setTitle(String title) { titleLabel.setText(title); }
    public String getTitle() { return titleLabel.getText(); }

    public void setStatus(Status status) {
        this.currentStatus = status;
        stopPulse();
        String color;
        switch (status) {
            case RUNNING -> { color = "#cc785c"; startPulse(); }
            case COMPLETED -> color = "#5db872";
            case ERROR -> color = "#c64545";
            default -> color = "#6c6a64";
        }
        dotLabel.setStyle("-fx-background-radius: 4; -fx-background-color: " + color + ";");
    }

    public void setActive(boolean active) {
        this.active = active;
        closeBtn.setVisible(active);
        updateStyle();
    }

    public boolean isActive() { return active; }

    public void setOnClose(Runnable action) { this.onCloseAction = action; }
    public void setOnSelect(Runnable action) { this.onSelectAction = action; }

    private void updateStyle() {
        if (active) {
            setStyle("-fx-cursor: hand; -fx-background-radius: 6;"
                + " -fx-background-color: white;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 3, 0, 0, 1);");
        } else {
            setStyle("-fx-cursor: hand; -fx-background-radius: 6;"
                + " -fx-background-color: transparent;");
        }
    }

    private void startPulse() {
        pulseAnimation = new Timeline(
            new KeyFrame(Duration.seconds(0), e -> dotLabel.setOpacity(1.0)),
            new KeyFrame(Duration.seconds(0.75), e -> dotLabel.setOpacity(0.5)),
            new KeyFrame(Duration.seconds(1.5), e -> dotLabel.setOpacity(1.0))
        );
        pulseAnimation.setCycleCount(Timeline.INDEFINITE);
        pulseAnimation.play();
    }

    private void stopPulse() {
        if (pulseAnimation != null) {
            pulseAnimation.stop();
            pulseAnimation = null;
        }
        dotLabel.setOpacity(1.0);
    }
}
