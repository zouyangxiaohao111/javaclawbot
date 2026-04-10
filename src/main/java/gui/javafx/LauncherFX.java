package gui.javafx;

public class LauncherFX {
    public static void main(String[] args) {
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");
        System.setProperty("prism.allowhidpi", "true");
        JavaClawBotFX.main(args);
    }
}