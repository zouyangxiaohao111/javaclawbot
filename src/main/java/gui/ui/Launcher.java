package gui.ui;

public class Launcher {
    public static void main(String[] args) {
        // JavaFX 在 unnamed module（shaded jar）中无法自动检测渲染管线，
        // 需要根据平台手动设置 prism.order
        if (System.getProperty("prism.order") == null) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                System.setProperty("prism.order", "d3d");
            } else {
                System.setProperty("prism.order", "es2");
            }
        }
        JavaClawBotApp.launchApp(args);
    }
}
