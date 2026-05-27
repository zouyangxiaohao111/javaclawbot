package gui.ui.components;

import agent.tool.file.FileBackupManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monaco Editor based diff viewer popup.
 * <p>
 * Loads diff-viewer.html with Monaco Editor for side-by-side diff rendering,
 * replacing the previous hand-crafted HTML diff renderer.
 */
public class DiffViewerPopup {

    private static final Logger log = LoggerFactory.getLogger(DiffViewerPopup.class);

    private static final double DEFAULT_WIDTH = 1000;
    private static final double DEFAULT_HEIGHT = 680;
    private static final Gson GSON = new Gson();

    /** Cached path to extracted Monaco resources directory. */
    private static Path monacoDir = null;

    private DiffViewerPopup() {}

    public static void show(Path originalPath, FileBackupManager.BackupEntry entry,
                            FileBackupManager backupManager) {
        // 1. Extract Monaco resources
        Path htmlPath = extractMonacoResources();
        if (htmlPath == null) {
            log.error("Cannot load diff viewer: Monaco resources unavailable");
            return;
        }

        // 2. Read file contents
        String oldContent;
        String newContent;
        try {
            oldContent = Files.readString(entry.backupFilePath(), StandardCharsets.UTF_8);
            newContent = Files.exists(originalPath)
                    ? Files.readString(originalPath, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.error("Failed to read files for diff", e);
            return;
        }

        // 3. Build JSON data with Gson
        String fileName = originalPath.getFileName().toString();
        String filePath = originalPath.toString();
        String timestamp = formatTimestamp(entry.timestamp());

        int backupVersion = 1;
        int totalVersions = 1;
        if (backupManager != null) {
            List<FileBackupManager.BackupEntry> versions = backupManager.getVersions(originalPath);
            totalVersions = versions.size();
            for (int i = 0; i < versions.size(); i++) {
                if (versions.get(i).backupFilePath().equals(entry.backupFilePath())) {
                    backupVersion = i + 1;
                    break;
                }
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("original", oldContent);
        json.addProperty("modified", newContent);
        json.addProperty("fileName", fileName);
        json.addProperty("filePath", filePath);
        json.addProperty("timestamp", timestamp);
        json.addProperty("backupVersion", backupVersion);
        json.addProperty("totalVersions", totalVersions);
        String jsonString = GSON.toJson(json);

        // 4. Create Stage + WebView
        Stage stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);

        WebView wv = new WebView();
        wv.setContextMenuEnabled(true);
        wv.setStyle("-fx-background-color: white;");

        // 5. window.status callback (rollback / close)
        wv.getEngine().setOnStatusChanged(event -> {
            String s = event.getData();
            if ("close".equals(s)) {
                stage.close();
            } else if ("rollback".equals(s) && backupManager != null) {
                boolean ok = backupManager.restore(originalPath, entry);
                log.debug("Rollback result for {}: {}", originalPath, ok);
                stage.close();
            }
        });

        // 6. Load HTML
        wv.getEngine().load(htmlPath.toUri().toString());

        // 7. Inject diff data after page load completes (with retry for Monaco async init)
        wv.getEngine().getLoadWorker().stateProperty().addListener(
            (obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    // Monaco loads asynchronously via AMD require(); use retry loop
                    // instead of fixed delay to handle slow initialization
                    Thread injector = new Thread(() -> {
                        int maxRetries = 30; // 30 * 200ms = 6 seconds max
                        final boolean[] stored = {false};
                        for (int i = 0; i < maxRetries; i++) {
                            try { Thread.sleep(200); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            final int attempt = i;
                            final boolean[] done = {false};
                            javafx.application.Platform.runLater(() -> {
                                try {
                                    Object ready = wv.getEngine().executeScript(
                                            "window.__diffReady === true");
                                    if (Boolean.TRUE.equals(ready)) {
                                        // Monaco ready — 两步注入：先存数据，再调用函数
                                        // 避免大文件内容拼接成超长单条 JS 语句
                                        wv.getEngine().executeScript(
                                                "window.__injectedData=" + jsonString);
                                        Object result = wv.getEngine().executeScript(
                                                "fetchAndApplyDiff(window.__injectedData)");
                                        log.info("Monaco data injected (attempt {}), result={}", attempt, result);
                                        done[0] = true;
                                    } else if (!stored[0]) {
                                        // Monaco not ready — store pending data once
                                        wv.getEngine().executeScript(
                                                "window.__pendingDiffData=" + jsonString);
                                        stored[0] = true;
                                        log.debug("Stored __pendingDiffData, waiting for Monaco (attempt {})", attempt);
                                    }
                                    // If already stored, just wait for Monaco require() callback
                                } catch (Exception e) {
                                    log.warn("Monaco injection attempt {} failed: {}", attempt, e.getMessage());
                                }
                            });
                            try { Thread.sleep(50); } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            if (done[0]) {
                                log.info("Monaco diff data injection succeeded");
                                return;
                            }
                        }
                        log.error("Monaco data injection timed out after {} retries ({} seconds)",
                                maxRetries, maxRetries * 250 / 1000);
                    }, "monaco-data-injector");
                    injector.setDaemon(true);
                    injector.start();
                } else if (newState == Worker.State.FAILED) {
                    log.error("Failed to load diff viewer HTML from {}", htmlPath);
                }
            }
        );

        // 8. Stage shell (Apple-style rounded corners + shadow)
        StackPane root = new StackPane(wv);
        root.setStyle("-fx-background-color: white; -fx-background-radius: 12px;"
                + " -fx-border-color: rgba(0,0,0,0.08); -fx-border-radius: 12px;"
                + " -fx-border-width: 1px;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 20, 0, 0, 8);");

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);

        // Center on owner or screen
        if (stage.getOwner() != null) {
            stage.setX(stage.getOwner().getX() + (stage.getOwner().getWidth() - DEFAULT_WIDTH) / 2);
            stage.setY(stage.getOwner().getY() + (stage.getOwner().getHeight() - DEFAULT_HEIGHT) / 2);
        } else {
            double sw = javafx.stage.Screen.getPrimary().getVisualBounds().getWidth();
            double sh = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
            stage.setX((sw - DEFAULT_WIDTH) / 2);
            stage.setY((sh - DEFAULT_HEIGHT) / 2);
        }
        stage.show();

        log.info("Diff viewer opened: {} (v{}/{})", fileName, backupVersion, totalVersions);
    }

    // ===== Monaco Resource Extraction =====

    /**
     * Extracts Monaco Editor resources to a temporary directory.
     * <p>
     * Dev mode: returns path directly from src/main/resources/monaco if available.
     * Production (jar): extracts resources listed in manifest.txt to a temp directory.
     *
     * @return path to diff-viewer.html, or null on failure
     */
    private static Path extractMonacoResources() {
        // Dev mode: load directly from project resources directory
        Path projectMonaco = Path.of("src/main/resources/monaco");
        if (Files.exists(projectMonaco.resolve("diff-viewer.html"))) {
            return projectMonaco.resolve("diff-viewer.html");
        }

        // Production mode: extract from jar to temp directory
        if (monacoDir != null && Files.exists(monacoDir)) {
            return monacoDir.resolve("diff-viewer.html");
        }

        try {
            monacoDir = Files.createTempDirectory("nexusai-monaco-");
            monacoDir.toFile().deleteOnExit();

            // Read manifest listing all Monaco files
            InputStream manifestStream =
                    DiffViewerPopup.class.getResourceAsStream("/monaco/manifest.txt");
            if (manifestStream == null) {
                log.error("Monaco manifest.txt not found in jar resources");
                return null;
            }

            List<String> files;
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(manifestStream, StandardCharsets.UTF_8))) {
                files = reader.lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }

            // Extract each listed file
            for (String file : files) {
                extractSingleResource("/monaco/" + file, monacoDir.resolve(file));
            }

            log.info("Extracted {} Monaco resource files to {}", files.size(), monacoDir);
            return monacoDir.resolve("diff-viewer.html");
        } catch (IOException e) {
            log.error("Failed to extract Monaco resources", e);
            return null;
        }
    }

    /**
     * Extracts a single resource from the classpath to the target file path.
     */
    private static void extractSingleResource(String resourcePath, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream is = DiffViewerPopup.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Resource not found: {}", resourcePath);
                return;
            }
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
        }
    }

    // ===== Helpers =====

    private static String formatTimestamp(String ts) {
        if (ts == null || ts.isEmpty()) return "";
        String s = ts.replace("_", " ");
        return s.length() > 19 ? s.substring(0, 19) : s;
    }
}
