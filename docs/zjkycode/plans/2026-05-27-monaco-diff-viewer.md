# Monaco DiffViewer 实施计划

> **对于代理工作者：**必需的子技能：使用 zjkycode:subagent-driven-development（推荐）或 zjkycode:executing-plans 来逐任务实施此计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 用 Monaco Editor 替换 DiffViewerPopup 的手写 HTML diff，实现 IDE 级代码对比体验。

**架构：** Monaco Editor 资源打包到 `src/main/resources/monaco/`，运行时提取到临时目录，通过 `file://` 协议加载到 JavaFX WebView。Java 端通过 `WebView.engine().executeScript()` 注入 diff 数据，JS 端通过 `window.status` 回调 Java。保留现有 `show()` API 不变。

**技术栈：** Monaco Editor 0.45.0, JavaFX WebView 17.0.14, JDK 17

---

### 任务 1：下载并打包 Monaco Editor 资源

**文件：**
- 创建：`src/main/resources/monaco/vs/` (Monaco 核心文件)
- 创建：`src/main/resources/monaco/diff-viewer.html` (Diff 页面模板)

- [ ] **步骤 1：下载 Monaco Editor npm 包的 min 目录**

```bash
cd D:/code/ai_project/javaclawbot
mkdir -p src/main/resources/monaco
npx --yes monaco-editor@0.45.0 2>/dev/null || true
# 从 npm 缓存中复制 vs 目录
cp -r node_modules/monaco-editor/min/vs src/main/resources/monaco/vs
# 清理
rm -rf node_modules package.json package-lock.json
```

验证：`src/main/resources/monaco/vs/loader.js` 存在

- [ ] **步骤 2：验证 Monaco 资源完整性**

确认以下文件存在：
- `src/main/resources/monaco/vs/loader.js`
- `src/main/resources/monaco/vs/editor/editor.main.js`
- `src/main/resources/monaco/vs/editor/editor.main.css`
- `src/main/resources/monaco/vs/base/` 目录

- [ ] **步骤 3：提交 Monaco 资源**

```bash
git add src/main/resources/monaco/
git commit -m "feat: bundle Monaco Editor 0.45.0 resources"
```

---

### 任务 2：创建 diff-viewer.html 模板

**文件：**
- 创建：`src/main/resources/monaco/diff-viewer.html`

这个 HTML 文件是 Monaco DiffEditor 的完整页面，包含 Apple 设计风格 + 中文 UI。

- [ ] **步骤 1：创建 diff-viewer.html**

```html
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
*{margin:0;padding:0;box-sizing:border-box;}
html,body{height:100%;overflow:hidden;
  font-family:'SF Pro Display','SF Pro Text',system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;
  background:#ffffff;color:#1d1d1f;}
#toolbar{display:flex;align-items:center;padding:10px 16px;gap:8px;
  background:#f5f5f7;border-bottom:1px solid #e0e0e0;height:44px;}
#toolbar .file-icon{font-size:14px;}
#toolbar .file-name{font-weight:600;font-size:14px;color:#1d1d1f;letter-spacing:-0.2px;}
#toolbar .file-path{color:#86868b;font-size:12px;margin-left:4px;}
#toolbar .spacer{flex:1;}
#toolbar .nav-group{display:flex;gap:1px;background:#d2d2d7;border-radius:6px;padding:1px;}
#toolbar .nav-btn{border:none;background:#ffffff;color:#1d1d1f;border-radius:5px;
  padding:4px 12px;font-size:11px;cursor:pointer;font-family:inherit;font-weight:500;}
#toolbar .nav-btn:hover{background:#f0f0f2;}
#toolbar .nav-info{font-size:11px;color:#86868b;padding:0 6px;}
#toolbar .rollback-btn{border:none;background:#ff3b30;color:#fff;border-radius:6px;
  padding:5px 14px;font-size:12px;cursor:pointer;font-family:inherit;font-weight:500;margin-left:4px;}
#toolbar .rollback-btn:hover{background:#e0342a;}
#toolbar .timestamp{color:#86868b;font-size:11px;margin-left:8px;}
#diff-container{height:calc(100% - 44px - 28px);}
#statusbar{display:flex;padding:4px 16px;font-size:11px;color:#86868b;
  background:#f5f5f7;border-top:1px solid #e0e0e0;gap:16px;align-items:center;height:28px;}
#statusbar .del{color:#ff3b30;font-weight:500;}
#statusbar .add{color:#34c759;font-weight:500;}
#statusbar .spacer{flex:1;}
#statusbar .hint{color:#aeaeb2;font-size:10px;}

/* Monaco 自定义主题覆盖 */
.monaco-editor .inserted-sign{background:#f0faf0 !important;}
.monaco-editor .deleted-sign{background:#fff5f5 !important;}
.monaco-editor .inserted-text{background:rgba(52,199,89,0.2) !important;}
.monaco-editor .deleted-text{background:rgba(255,59,48,0.2) !important;}
.monaco-editor .line-numbers{color:#c7c7c7 !important;}
.monaco-editor{font-family:'SF Mono','JetBrains Mono','Menlo',monospace !important;font-size:13px !important;}
</style>
</head>
<body>
<div id="toolbar">
  <span class="file-icon">📄</span>
  <span class="file-name" id="fileName">--</span>
  <span class="file-path" id="filePath"></span>
  <span class="spacer"></span>
  <div class="nav-group">
    <button class="nav-btn" onclick="prevDiff()">← 上一处</button>
    <button class="nav-btn" onclick="nextDiff()">下一处 →</button>
  </div>
  <span class="nav-info" id="navInfo"></span>
  <button class="rollback-btn" onclick="doRollback()">↩ 回滚此版本</button>
  <span class="timestamp" id="timestamp"></span>
</div>
<div id="diff-container"></div>
<div id="statusbar">
  <span class="del" id="delCount">− 0 行删除</span>
  <span class="add" id="addCount">+ 0 行新增</span>
  <span class="spacer"></span>
  <span id="versionInfo"></span>
  <span class="hint">ESC 关闭</span>
</div>

<script src="vs/loader.js"></script>
<script>
// 禁用 web workers（file:// 协议不支持）
window.MonacoEnvironment = {
  getWorkerUrl: function() { return ''; }
};

require.config({ paths: { vs: 'vs' } });

var diffEditor = null;
var currentDiffIndex = -1;
var diffRanges = [];

require(['vs/editor/editor.main'], function() {
  // 定义 Apple 风格亮色主题
  monaco.editor.defineTheme('apple-light', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'comment', foreground: '86868b', fontStyle: 'italic' },
      { token: 'keyword', foreground: '0066cc' },
      { token: 'string', foreground: '248a3d' },
      { token: 'number', foreground: 'af5c02' },
      { token: 'type', foreground: '8250df' }
    ],
    colors: {
      'editor.background': '#ffffff',
      'editor.foreground': '#1d1d1f',
      'editorLineNumber.foreground': '#c7c7c7',
      'editorLineNumber.activeForeground': '#1d1d1f',
      'diffEditor.insertedTextBackground': '#34c75920',
      'diffEditor.removedTextBackground': '#ff3b3020',
      'diffEditor.insertedLineBackground': '#f0faf0',
      'diffEditor.removedLineBackground': '#fff5f5',
      'scrollbarSlider.background': '#00000015',
      'scrollbarSlider.hoverBackground': '#00000025'
    }
  });

  diffEditor = monaco.editor.createDiffEditor(
    document.getElementById('diff-container'),
    {
      theme: 'apple-light',
      readOnly: true,
      renderSideBySide: true,
      originalEditable: false,
      minimap: { enabled: false },
      scrollbar: { verticalScrollbarSize: 8, horizontalScrollbarSize: 8 },
      fontSize: 13,
      lineHeight: 20,
      fontFamily: "'SF Mono','JetBrains Mono','Menlo',monospace",
      padding: { top: 8 }
    }
  );

  // 如果数据已就绪则加载
  if (window.__pendingDiffData) {
    applyDiffData(window.__pendingDiffData);
    window.__pendingDiffData = null;
  }
});

// Java 调用入口
function setDiffData(json) {
  var data = typeof json === 'string' ? JSON.parse(json) : json;
  if (!diffEditor) {
    window.__pendingDiffData = data;
    return;
  }
  applyDiffData(data);
}

function applyDiffData(data) {
  document.getElementById('fileName').textContent = data.fileName || '';
  document.getElementById('filePath').textContent = data.filePath || '';
  document.getElementById('timestamp').textContent = data.timestamp || '';
  document.getElementById('versionInfo').textContent =
    '备份 ' + (data.backupVersion || 1) + ' / ' + (data.totalVersions || 1);

  var lang = data.language || detectLanguage(data.fileName);

  var originalModel = monaco.editor.createModel(data.original || '', lang);
  var modifiedModel = monaco.editor.createModel(data.modified || '', lang);

  diffEditor.setModel({
    original: { textModel: originalModel, title: '备份版本 · v' + (data.backupVersion || 1) },
    modified: { textModel: modifiedModel, title: '当前文件' }
  });

  // 统计增删行
  var changes = diffEditor.getLineChanges() || [];
  var delLines = 0, addLines = 0;
  diffRanges = [];
  for (var i = 0; i < changes.length; i++) {
    var c = changes[i];
    if (c.originalEndLineNumber > 0)
      delLines += c.originalEndLineNumber - c.originalStartLineNumber + 1;
    if (c.modifiedEndLineNumber > 0)
      addLines += c.modifiedEndLineNumber - c.modifiedStartLineNumber + 1;
    diffRanges.push(c);
  }
  document.getElementById('delCount').textContent = '− ' + delLines + ' 行删除';
  document.getElementById('addCount').textContent = '+ ' + addLines + ' 行新增';
  document.getElementById('navInfo').textContent =
    diffRanges.length > 0 ? '1 / ' + diffRanges.length : '';
  currentDiffIndex = -1;
}

function detectLanguage(fileName) {
  if (!fileName) return 'plaintext';
  var ext = fileName.split('.').pop().toLowerCase();
  var map = {
    'java':'java','js':'javascript','ts':'typescript','py':'python',
    'rb':'ruby','go':'go','rs':'rust','c':'c','cpp':'cpp','h':'cpp',
    'cs':'csharp','xml':'xml','json':'json','yaml':'yaml','yml':'yaml',
    'md':'markdown','html':'html','css':'css','sql':'sql','sh':'shell',
    'bat':'bat','ps1':'powershell','kt':'kotlin','swift':'swift',
    'php':'php','scala':'scala','r':'r','lua':'lua','toml':'ini'
  };
  return map[ext] || 'plaintext';
}

function prevDiff() {
  if (diffRanges.length === 0) return;
  if (currentDiffIndex <= 0) currentDiffIndex = diffRanges.length;
  currentDiffIndex--;
  var change = diffRanges[currentDiffIndex];
  var line = change.modifiedStartLineNumber || change.originalStartLineNumber;
  diffEditor.revealLineInCenter(line);
  document.getElementById('navInfo').textContent =
    (currentDiffIndex + 1) + ' / ' + diffRanges.length;
}

function nextDiff() {
  if (diffRanges.length === 0) return;
  currentDiffIndex++;
  if (currentDiffIndex >= diffRanges.length) currentDiffIndex = 0;
  var change = diffRanges[currentDiffIndex];
  var line = change.modifiedStartLineNumber || change.originalStartLineNumber;
  diffEditor.revealLineInCenter(line);
  document.getElementById('navInfo').textContent =
    (currentDiffIndex + 1) + ' / ' + diffRanges.length;
}

function doRollback() {
  window.status = 'rollback';
}

// ESC 关闭
document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') window.status = 'close';
  if (e.altKey && e.key === 'ArrowUp') { e.preventDefault(); prevDiff(); }
  if (e.altKey && e.key === 'ArrowDown') { e.preventDefault(); nextDiff(); }
});
</script>
</body>
</html>
```

- [ ] **步骤 2：提交 HTML 模板**

```bash
git add src/main/resources/monaco/diff-viewer.html
git commit -m "feat: add Monaco diff-viewer HTML template with Apple style"
```

---

### 任务 3：重构 DiffViewerPopup.java

**文件：**
- 修改：`src/main/java/gui/ui/components/DiffViewerPopup.java`

核心变更：
1. 移除 `HTML_TEMPLATE` 常量和所有手写 HTML 渲染代码
2. 新增 Monaco 资源提取逻辑（从 jar 提取到临时目录）
3. 通过 `file://` 加载 HTML，用 `executeScript()` 注入 diff 数据
4. 保留 `show()` API 签名不变
5. 保留现有回滚/关闭回调机制（`window.status`）

- [ ] **步骤 1：重构 DiffViewerPopup.java**

替换整个文件内容。关键变更点：

1. **移除**：`HTML_TEMPLATE`（第 29-65 行）、`computeDiff()`、`countHunks()`、`renderLine()`、`esc()`、`DiffType` 枚举、`DiffLinePair` record
2. **新增**：`extractMonacoResources()` — 从 jar 资源提取 Monaco 文件到临时目录
3. **新增**：`buildDiffJson()` — 将原始/当前文件内容序列化为 JSON
4. **新增**：`detectLanguage()` — 从文件扩展名推断语言（与 HTML 中 JS 版一致，作为 fallback）
5. **修改**：`show()` 方法 — 加载 diff-viewer.html + 注入数据

重构后的 `show()` 方法核心逻辑：

```java
public static void show(Path originalPath, FileBackupManager.BackupEntry entry,
                        FileBackupManager backupManager) {
    String oldContent, newContent;
    try {
        oldContent = Files.readString(entry.backupFilePath(), StandardCharsets.UTF_8);
        newContent = Files.exists(originalPath)
                ? Files.readString(originalPath, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
        return;
    }

    // 提取 Monaco 资源并获取 HTML 路径
    Path htmlPath = extractMonacoResources();
    if (htmlPath == null) return;

    // 构建 JSON 数据
    String fileName = originalPath.getFileName().toString();
    String filePath = originalPath.getParent() != null
            ? originalPath.getParent().toString().replace('\\', '/') : "";
    String ts = formatTimestamp(entry.timestamp());
    String lang = detectLanguage(fileName);
    int totalVersions = backupManager != null
            ? backupManager.getVersions(originalPath).size() : 1;
    int backupVersion = 1; // 从 entry 推断

    // JSON 转义
    String json = "{\"original\":" + jsonEscape(oldContent)
        + ",\"modified\":" + jsonEscape(newContent)
        + ",\"language\":\"" + lang + "\""
        + ",\"fileName\":" + jsonEscape(fileName)
        + ",\"filePath\":" + jsonEscape(filePath)
        + ",\"timestamp\":\"" + ts + "\""
        + ",\"backupVersion\":" + backupVersion
        + ",\"totalVersions\":" + totalVersions + "}";

    Stage stage = new Stage();
    stage.initStyle(StageStyle.TRANSPARENT);

    WebView wv = new WebView();
    wv.setContextMenuEnabled(true);
    wv.setStyle("-fx-background-color: white;");

    // 回调处理
    wv.getEngine().setOnStatusChanged(event -> {
        String s = event.getData();
        if ("close".equals(s)) {
            stage.close();
        } else if ("rollback".equals(s) && backupManager != null) {
            backupManager.restore(originalPath, entry);
            stage.close();
        }
    });

    // 加载 HTML
    wv.getEngine().load(htmlPath.toUri().toString());

    // HTML 加载完成后注入数据（loadWorker 监听页面加载完成）
    wv.getEngine().getLoadWorker().stateProperty().addListener(
        (obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                // Monaco 异步初始化，等待 require 完成
                String injectScript = "if(typeof setDiffData==='function'){"
                    + "setDiffData(" + json + ");}"
                    + "else{window.__pendingDiffData=" + json + ";}";
                // 延迟执行确保 Monaco loader 完成
                new Thread(() -> {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    Platform.runLater(() -> wv.getEngine().executeScript(injectScript));
                }).start();
            }
        }
    );

    // Stage 外壳（Apple 风格圆角 + 阴影）
    StackPane root = new StackPane(wv);
    root.setStyle("-fx-background-color: white; -fx-background-radius: 10px;"
            + " -fx-border-color: #d2d2d7; -fx-border-radius: 10px;"
            + " -fx-border-width: 1px;"
            + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 30, 0, 0, 10);");

    Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    scene.setFill(Color.TRANSPARENT);
    stage.setScene(scene);

    // 居中定位
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
}
```

`extractMonacoResources()` 实现要点：
```java
private static Path extractMonacoResources() {
    if (monacoDir != null && Files.exists(monacoDir)) return monacoDir.resolve("diff-viewer.html");
    try {
        monacoDir = Files.createTempDirectory("nexusai-monaco-");
        monacoDir.toFile().deleteOnExit();
        // 递归提取 src/main/resources/monaco/ 下所有文件
        extractResourceTree("/monaco", monacoDir);
        return monacoDir.resolve("diff-viewer.html");
    } catch (IOException e) {
        log.error("Failed to extract Monaco resources", e);
        return null;
    }
}
```

- [ ] **步骤 2：编译验证**

```bash
# 使用项目编译脚本
maven-compiled.bat
```

预期：编译成功，无错误

- [ ] **步骤 3：提交重构**

```bash
git add src/main/java/gui/ui/components/DiffViewerPopup.java
git commit -m "feat: refactor DiffViewerPopup to use Monaco Editor"
```

---

### 任务 4：手动验证

- [ ] **步骤 1：运行应用，触发对比弹窗**

通过 GUI 操作触发 `edit_file` 或 `write_file`，点击 [查看对比] 按钮。

验证项：
- [ ] Monaco DiffEditor 正常渲染左右分屏
- [ ] 语法高亮生效（Java 文件显示关键字/字符串/注释颜色）
- [ ] 新增行绿色背景、删除行红色背景
- [ ] 左右同步滚动
- [ ] "← 上一处" / "下一处 →" 导航跳转正确
- [ ] "↩ 回滚此版本" 弹出确认并执行回滚
- [ ] ESC 关闭弹窗
- [ ] Apple 设计风格（白色画布、#f5f5f7 工具栏、SF Pro 字体）
- [ ] 全中文 UI（文件名、备份版本、上一处、下一处、回滚、行删除/行新增）
- [ ] 状态栏显示正确的增删行数和版本信息

- [ ] **步骤 2：提交最终验证结果**

```bash
git add -A
git commit -m "feat: Monaco DiffViewer with Apple style - verified"
```

---

## 关键注意事项

### Monaco 资源提取
- 使用 `DiffViewerPopup.class.getResourceAsStream("/monaco/...")` 读取 jar 内资源
- 递归提取到 `{系统临时目录}/nexusai-monaco-{随机}/`
- 设置 `deleteOnExit()` 确保 JVM 退出时清理
- 首次提取后缓存 `monacoDir` 静态变量，后续调用不再提取

### WebView file:// 协议限制
- Monaco web workers 在 `file://` 下不可用 → 通过 `MonacoEnvironment.getWorkerUrl` 返回空字符串禁用
- Monaco 的 AMD loader (`require.config`) 在 `file://` 下正常工作
- 所有 Monaco 模块通过相对路径加载，无需额外配置

### JSON 转义
- 文件内容可能包含引号、换行、Unicode 字符
- 使用 `Gson` 序列化（项目已有依赖）而非手写转义
- 示例：`new Gson().toJson(content)` 生成安全的 JSON 字符串

### 性能考虑
- Monaco 首次加载 ~500ms（WebView 初始化 + JS 解析）
- 后续打开复用已提取的资源，加载更快
- 大文件（1000+ 行）Monaco 原生支持虚拟化渲染，无性能问题
