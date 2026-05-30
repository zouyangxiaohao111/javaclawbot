# DiffViewerPopup Monaco 重构设计文档

**日期**: 2026-05-27  
**版本**: 2.4.0  
**分支**: feature/monaco-diff-viewer  
**状态**: 设计完成，待实施

---

## 1. 背景与目标

### 1.1 现状痛点

当前 `DiffViewerPopup` 使用 JavaFX WebView + 手写 HTML 字符串拼接实现代码对比：

- **维护困难**: HTML/CSS 以 Java 字符串硬编码（`DiffViewerPopup.java` 第 29-65 行，200+ 行字符串拼接）
- **功能受限**: 仅支持行级 diff（`ADDED`/`REMOVED`），无词级高亮（看不出同一行内具体改了哪几个字）
- **无语法高亮**: 代码无颜色区分，可读性差
- **交互简陋**: 无折叠、无 minimap、无搜索、无快捷键

### 1.2 目标

用 **Monaco Editor**（VS Code 内核）替换手写 HTML diff，实现：

1. **IDE 级对比体验**: 左右分屏 + 行内 diff + 语法高亮 + 同步滚动
2. **降低维护成本**: HTML 模板从 200 行缩减到 ~30 行配置
3. **保留现有交互**: 回滚按钮、ESC 关闭、版本导航
4. **采用 Apple 设计风格**: 白色画布 + SF Pro 字体 + 全中文 UI

---

## 2. 技术选型

### 2.1 Monaco Editor

**选择理由**:
- 已在用 WebView，迁移成本最低
- 内置 `MonacoDiffEditor`，开箱即用
- 支持 70+ 语言语法高亮
- 词级 diff（同一行内精确标记变更字符）
- 本地资源打包到 `resources/monaco/`，无需联网

**体积**: ~2MB（压缩后）

**替代方案对比**:

| 方案 | 体积 | 语法高亮 | 词级 diff | 维护成本 | 选择 |
|------|------|---------|----------|---------|------|
| Monaco Editor | ~2MB | ✅ 全语言 | ✅ 内置 | 低 | ✅ |
| CodeMirror 6 | ~300KB | ✅ 主流 | ⚠️ 需插件 | 中 | ❌ |
| 纯 JavaFX (RichTextFX) | 0 | ⚠️ 需自写 | ❌ 需自写 | 极高 | ❌ |
| diff2html | ~50KB | ❌ | ✅ | 低 | ❌ |

### 2.2 设计风格

**Apple 设计风格**（参考 `design-md/apple/DESIGN.md`）:

- **画布**: `#ffffff`（纯白）
- **工具栏**: `#f5f5f7`（浅灰）
- **主色**: `#0066cc`（Action Blue）
- **回滚按钮**: `#ff3b30`（红色警示）
- **新增行**: `#34c759`（绿色）+ 背景 `#f0faf0`
- **删除行**: `#ff3b30`（红色）+ 背景 `#fff5f5`
- **正文**: `#1d1d1f`（近黑）
- **辅助文字**: `#86868b`（中灰）
- **字体**: SF Pro Display（标题）+ SF Mono（代码）+ PingFang SC（中文）

---

## 3. 架构设计

### 3.1 文件结构

```
src/main/resources/monaco/
├── vs/                          # Monaco 主题
│   ├── base.css
│   ├── editor.main.js
│   └── ...
└── diff-viewer.html             # 新版 HTML 模板（~30 行）

src/main/java/gui/ui/components/
├── DiffViewerPopup.java         # 重构：加载 Monaco + 注入数据
└── FileDiffBadge.java           # 保持不变
```

### 3.2 数据流

```
用户点击 [查看对比]
    ↓
ToolCallCard.handleDiffAction()
    ↓
DiffViewerPopup.show(originalPath, entry, backupManager)
    ↓
1. 读取备份文件 vs 当前文件（Java 端）
2. 计算行级 diff（保留现有 `DiffLinePair` 算法）
3. 序列化为 JSON: {original: "...", modified: "...", language: "java"}
4. 加载 WebView + Monaco diff-viewer.html
5. 通过 JS Bridge 注入数据: window.setDiffData(json)
    ↓
Monaco DiffEditor 渲染
    ↓
用户交互（导航/回滚/复制）→ JS → Java 回调
```

### 3.3 JS Bridge 接口

**Java → JS**:
```javascript
window.setDiffData({
  original: "backup file content",
  modified: "current file content",
  language: "java",  // 从文件扩展名推断
  fileName: "ChatPage.java",
  backupVersion: 3,
  totalVersions: 7,
  timestamp: "2026-05-27 10:32"
});
```

**JS → Java** (通过 `WebView.engine.executeScript`):
```javascript
// 导航
window.onNavigate('prev');  // 上一处差异
window.onNavigate('next');  // 下一处差异

// 回滚
window.onRollback();  // 触发回滚确认弹窗

// 复制
window.onCopy();  // 复制当前选中代码
```

---

## 4. UI 设计

### 4.1 窗口外壳

**macOS 风格**（JavaFX Stage 自定义装饰）:
- 顶部标题栏：红绿灯按钮 + 居中标题"文件对比 · ChatPage.java"
- 圆角窗口（10px）
- 阴影：`0 20px 60px rgba(0,0,0,0.6)`

### 4.2 工具栏

```
┌──────────────────────────────────────────────────────────────┐
│ 📄 ChatPage.java  src/main/java/gui/ui/pages/                │
│                    [← 上一处] [下一处 →]  [↩ 回滚此版本]  10:32 │
└──────────────────────────────────────────────────────────────┘
```

- **文件信息**: 图标 + 文件名（加粗）+ 路径（灰色）
- **导航按钮**: 分段控件样式（`nav-group`），圆角 6px
- **回滚按钮**: 红色 `#ff3b30`，圆角 6px，hover 变深
- **时间戳**: 右对齐，灰色小字

### 4.3 Diff 分屏

```
┌────────────────────┬────────────────────┐
│ ● 备份版本 · v3    │ ● 当前文件         │
├────────────────────┼────────────────────┤
│ 42│public void...  │ 42│public void...  │
│ 43│  if (isLoad..  │ 43│  if (isLoad..  │
│ 44│- if (fullHis.. │ 44│+ trimmedNod..  │ ← 红色背景 / 绿色背景
│ 45│  isLoading..   │ 45│  isLoading..   │
│ 46│- List<Msg>..   │ 46│+ List<Msg>..   │
│ 47│- for (Msg m..  │ 47│+ batch.forE..  │ ← 词级高亮
│ 48│-   addMessag.. │ 48│  Platform.ru.. │
│ ...                │ ...                │
└────────────────────┴────────────────────┘
```

- **左侧标题**: 红点 + "备份版本 · v3"
- **右侧标题**: 绿点 + "当前文件"
- **行号**: 灰色 `#c7c7c7`，右对齐，宽度 40px
- **新增行**: 绿色文字 `#248a3d` + 浅绿背景 `#f0faf0`
- **删除行**: 红色文字 `#d70015` + 浅红背景 `#fff5f5`
- **词级高亮**: Monaco 自动标记同一行内变更的字符（加粗/下划线）
- **同步滚动**: 左右屏滚动联动
- **代码字体**: SF Mono 12px

### 4.4 状态栏

```
┌──────────────────────────────────────────────────────────────┐
│ − 4 行删除   + 5 行新增              备份 3 / 7  ESC 关闭 · ⌘C 复制 │
└──────────────────────────────────────────────────────────────┘
```

- **删除统计**: 红色 `#ff3b30`，加粗
- **新增统计**: 绿色 `#34c759`，加粗
- **版本信息**: 居中，灰色
- **快捷键提示**: 右对齐，浅灰 `#aeaeb2`，10px

---

## 5. 交互设计

### 5.1 导航

- **上一处/下一处按钮**: 跳转到上一个/下一个差异块
- **键盘快捷键**:
  - `Alt+↑` / `Alt+↓`: 上一处/下一处差异
  - `⌘+C` / `Ctrl+C`: 复制选中代码
  - `ESC`: 关闭弹窗

### 5.2 回滚

1. 点击"↩ 回滚此版本"按钮
2. 弹出确认对话框（JavaFX Alert）:
   ```
   确认回滚？
   将 ChatPage.java 恢复到备份版本 v3（2026-05-27 10:32）
   [取消] [回滚]
   ```
3. 确认后调用 `backupManager.restore(entry)`
4. 关闭弹窗，刷新文件

### 5.3 复制

- 选中代码后 `⌘+C` / `Ctrl+C` 复制到剪贴板
- Monaco 原生支持，无需额外实现

### 5.4 关闭

- `ESC` 键关闭弹窗
- 点击窗口关闭按钮（红绿灯红灯）
- 点击弹窗外部区域（可选，需配置 `Stage.initModality(Modality.NONE)`）

---

## 6. 实施计划

### 6.1 阶段 1: Monaco 资源打包

1. 下载 Monaco Editor 发行版（~2MB）
2. 解压到 `src/main/resources/monaco/`
3. 创建 `diff-viewer.html` 模板（~30 行）
4. 验证 WebView 可加载 Monaco

### 6.2 阶段 2: DiffViewerPopup 重构

1. 移除手写 HTML 模板（200+ 行）
2. 加载 `diff-viewer.html` + Monaco
3. 实现 JS Bridge:
   - `setDiffData(json)`: 注入数据
   - `onNavigate(direction)`: 导航回调
   - `onRollback()`: 回滚回调
4. 保留现有 `DiffLinePair` 算法（行级 diff）
5. 新增语言检测（从文件扩展名推断）

### 6.3 阶段 3: UI 美化

1. 自定义 Stage 装饰（macOS 窗口外壳）
2. 工具栏布局（文件信息 + 导航 + 回滚）
3. 状态栏布局（统计 + 版本 + 快捷键提示）
4. 应用 Apple 设计 tokens（颜色/字体/圆角）

### 6.4 阶段 4: 测试与优化

1. 功能测试:
   - 对比弹窗正常显示
   - 导航按钮跳转正确
   - 回滚功能正常
   - ESC 关闭正常
2. 性能测试:
   - 大文件（1000+ 行）对比不卡顿
   - WebView 加载 Monaco 时间 < 500ms
3. 兼容性测试:
   - Windows / macOS / Linux 三平台
   - Java 17 + JavaFX 17.0.14

### 6.5 阶段 5: 文档与发布

1. 更新 CHANGELOG.md（2.4.0 版本）
2. 提交代码，打 tag `v2.4.0`
3. 合并到 `main` 分支
4. 推送远程，创建 PR

---

## 7. 风险与缓解

### 7.1 体积增加

**风险**: Monaco ~2MB 增加 JAR 体积  
**缓解**: 相比现有 WebView 方案，体积增加可接受（用户已下载 JavaFX ~50MB）

### 7.2 加载性能

**风险**: WebView 加载 Monaco 可能较慢  
**缓解**: 
- 预加载 Monaco 到 WebView 缓存
- 显示加载指示器（"正在加载对比视图..."）

### 7.3 兼容性

**风险**: Monaco 在某些平台 WebView 中可能不兼容  
**缓解**: 
- 测试三平台（Windows / macOS / Linux）
- 回退方案：保留旧版 HTML diff 作为 fallback

---

## 8. 验收标准

- [ ] Monaco DiffEditor 正常渲染左右分屏
- [ ] 语法高亮生效（Java 文件）
- [ ] 词级 diff 标记变更字符
- [ ] 导航按钮跳转正确
- [ ] 回滚功能正常（含确认弹窗）
- [ ] ESC 关闭正常
- [ ] Apple 设计风格应用（颜色/字体/圆角）
- [ ] 全中文 UI
- [ ] 大文件（1000+ 行）对比流畅
- [ ] 三平台测试通过

---

## 9. 参考资料

- [Monaco Editor 文档](https://microsoft.github.io/monaco-editor/)
- [Monaco DiffEditor API](https://microsoft.github.io/monaco-editor/api/interfaces/monaco.editor.idiffeditor.html)
- [Apple Design Guidelines](./design-md/apple/DESIGN.md)
- [JavaFX WebView 指南](https://openjfx.io/javadoc/17/javafx.web/javafx/scene/web/WebView.html)

---

**设计批准**: ✅ 用户已确认 Apple 风格 + 中文 UI  
**下一步**: 调用 `writing-plans` 技能创建详细实施计划
