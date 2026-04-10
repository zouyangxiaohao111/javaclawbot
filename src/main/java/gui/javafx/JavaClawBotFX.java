package gui.javafx;

import config.Config;
import config.ConfigIO;
import gui.javafx.controller.MainController;
import gui.javafx.service.ThemeManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class JavaClawBotFX extends Application {
    private Config config;
    private ThemeManager themeManager;

    @Override
    public void start(Stage primaryStage) throws Exception {
        config = ConfigIO.loadConfig(null);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/jfx/main.fxml"));
        Parent root = loader.load();

        MainController controller = loader.getController();
        controller.initialize(config);

        themeManager = new ThemeManager(root);
        controller.setThemeManager(themeManager);
        themeManager.applyTheme(ThemeManager.Theme.LIGHT);

        Scene scene = new Scene(root, 1100, 800);

        primaryStage.setTitle("javaclawbot");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(920);
        primaryStage.setMinHeight(660);

        primaryStage.setOnCloseRequest(event -> {
            if (controller != null) {
                controller.onWindowClosing();
            }
        });

        primaryStage.show();

        if (controller != null) {
            controller.onWindowShown();
        }
    }

    @Override
    public void stop() throws Exception {
        if (config != null) {
            ConfigIO.saveConfig(config, null);
        }
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}