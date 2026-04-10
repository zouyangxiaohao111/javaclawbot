package gui.javafx.service;

import javafx.scene.Parent;
import java.util.Objects;

public class ThemeManager {
    public enum Theme {
        LIGHT, DARK
    }

    private final Parent root;
    private Theme currentTheme;

    public ThemeManager(Parent root) {
        this.root = root;
        this.currentTheme = Theme.LIGHT;
    }

    public void applyTheme(Theme theme) {
        if (root == null) return;

        root.getStylesheets().clear();

        String commonCss = Objects.requireNonNull(getClass().getResource("/jfx/styles/common.css")).toExternalForm();
        root.getStylesheets().add(commonCss);

        String themeCss = theme == Theme.LIGHT ?
            Objects.requireNonNull(getClass().getResource("/jfx/styles/light-theme.css")).toExternalForm() :
            Objects.requireNonNull(getClass().getResource("/jfx/styles/dark-theme.css")).toExternalForm();
        root.getStylesheets().add(themeCss);

        currentTheme = theme;
    }

    public void toggleTheme() {
        applyTheme(currentTheme == Theme.LIGHT ? Theme.DARK : Theme.LIGHT);
    }

    public Theme getCurrentTheme() {
        return currentTheme;
    }
}