package gui.javafx.controller;

import gui.javafx.model.SessionInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class SidebarController {
    @FXML private ListView<SessionInfo> sessionListView;
    @FXML private ComboBox<String> modelComboBox;
    @FXML private Label mcpStatusLabel;
    @FXML private ToggleButton devModeToggle;
    @FXML private Button toggleSidebarButton;

    private final ObservableList<SessionInfo> sessions = FXCollections.observableArrayList();
    private boolean expanded = true;
    private Consumer<String> modelChangeListener;

    public void initialize() {
        sessionListView.setItems(sessions);
        sessionListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(SessionInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getTitle());
                }
            }
        });

        modelComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && modelChangeListener != null && !newVal.equals(oldVal)) {
                modelChangeListener.accept(newVal);
            }
        });

        devModeToggle.setText(devModeToggle.isSelected() ? "ON" : "OFF");
        mcpStatusLabel.setText("0/0");
    }

    public void initModelSelector(String currentModel, List<String> models) {
        modelComboBox.getItems().clear();
        if (models != null && !models.isEmpty()) {
            modelComboBox.getItems().addAll(models);
        }
        if (currentModel != null && !currentModel.isBlank()) {
            modelComboBox.setValue(currentModel);
        } else if (!modelComboBox.getItems().isEmpty()) {
            modelComboBox.setValue(modelComboBox.getItems().get(0));
        }
    }

    public void setModelChangeListener(Consumer<String> modelChangeListener) {
        this.modelChangeListener = modelChangeListener;
    }

    public void loadSessions() {
        sessions.clear();
        sessions.add(new SessionInfo("1", "New Chat"));
    }

    @FXML
    private void onNewChatClicked() {
        sessions.add(0, new SessionInfo(UUID.randomUUID().toString(), "New Chat"));
        sessionListView.getSelectionModel().select(0);
    }

    @FXML
    private void onMcpManageClicked() {
        // TODO 打开 MCP 管理对话框
    }

    @FXML
    private void onDevModeToggled() {
        devModeToggle.setText(devModeToggle.isSelected() ? "ON" : "OFF");
    }

    @FXML
    private void onSettingsClicked() {
        // TODO 打开设置
    }

    @FXML
    private void onHelpClicked() {
        // TODO 显示帮助
    }

    @FXML
    private void onToggleSidebarClicked() {
        expanded = !expanded;
        toggleSidebarButton.setText(expanded ? "◂" : "▸");
    }
}