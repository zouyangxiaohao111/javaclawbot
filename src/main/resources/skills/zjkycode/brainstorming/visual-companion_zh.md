# 视觉伴侣指南

基于浏览器的视觉头脑风暴伴侣，用于展示模型、图表和选项。

## 何时使用

按问题决定，而不是按会话决定。测试标准：**用户通过看到它比阅读它更能理解吗？**

**使用浏览器** 当内容本身是视觉的：

- **UI 模型** — 线框图、布局、导航结构、组件设计
- **架构图** — 系统组件、数据流、关系图
- **并排视觉比较** — 比较两个布局、两个配色方案、两个设计方向
- **设计润色** — 当问题关于外观和感觉、间距、视觉层次时
- **空间关系** — 状态机、流程图、实体关系渲染为图表

**使用终端** 当内容是文本或表格：

- **需求和范围问题** — "X 是什么意思？"，"哪些功能在范围内？"
- **概念 A/B/C 选择** — 在用文字描述的方案之间选择
- **权衡列表** — 优缺点、比较表
- **技术决策** — API 设计、数据建模、架构方案选择
- **澄清问题** — 任何答案是文字而非视觉偏好的问题

关于 UI 主题的问题不自动是视觉问题。"你想要什么样的向导？" 是概念问题 — 使用终端。"这些向导布局哪个感觉对？" 是视觉问题 — 使用浏览器。

## 工作原理

服务器监视目录中的 HTML 文件，并将最新的文件提供给浏览器。你将 HTML 内容写入 `screen_dir`，用户在浏览器中看到它并可以点击选择选项。选择记录到 `state_dir/events`，你在下一轮读取它。

**内容片段 vs 完整文档：** 如果你的 HTML 文件以 `<!DOCTYPE` 或 `<html` 开头，服务器按原样提供（只注入辅助脚本）。否则，服务器自动将你的内容包装在框架模板中 — 添加头部、CSS 主题、选择指示器和所有交互基础设施。**默认编写内容片段。** 只有在需要完全控制页面时才编写完整文档。

## 启动会话

```bash
# Start server with persistence (mockups saved to project)
scripts/start-server.sh --project-dir /path/to/project

# Returns: {"type":"server-started","port":52341,"url":"http://localhost:52341",
#           "screen_dir":"/path/to/project/.superpowers/brainstorm/12345-1706000000/content",
#           "state_dir":"/path/to/project/.superpowers/brainstorm/12345-1706000000/state"}
```

保存响应中的 `screen_dir` 和 `state_dir`。告诉用户打开 URL。

**查找连接信息：** 服务器将其启动 JSON 写入 `$STATE_DIR/server-info`。如果你在后台启动服务器但没有捕获 stdout，读取该文件获取 URL 和端口。使用 `--project-dir` 时，检查 `<project>/.superpowers/brainstorm/` 获取会话目录。

**注意：** 将项目根目录作为 `--project-dir` 传递，这样模型会持久保存在 `.superpowers/brainstorm/` 中，并在服务器重启后保留。没有它，文件会进入 `/tmp` 并被清理。提醒用户将 `.superpowers/` 添加到 `.gitignore`（如果还没有）。

**按平台启动服务器：**

**Claude Code (macOS / Linux):**
```bash
# Default mode works — the script backgrounds the server itself
scripts/start-server.sh --project-dir /path/to/project
```

**Claude Code (Windows):**
```bash
# Windows auto-detects and uses foreground mode, which blocks the tool call.
# Use run_in_background: true on the Bash tool call so the server survives
# across conversation turns.
scripts/start-server.sh --project-dir /path/to/project
```
通过 Bash 工具调用时，设置 `run_in_background: true`。然后在下一轮读取 `$STATE_DIR/server-info` 获取 URL 和端口。

**Codex:**
```bash
# Codex reaps background processes. The script auto-detects CODEX_CI and
# switches to foreground mode. Run it normally — no extra flags needed.
scripts/start-server.sh --project-dir /path/to/project
```

**Gemini CLI:**
```bash
# Use --foreground and set is_background: true on your shell tool call
# so the process survives across turns
scripts/start-server.sh --project-dir /path/to/project --foreground
```

**其他环境：** 服务器必须在后台跨会话轮运行。如果你的环境会回收分离的进程，使用 `--foreground` 并用平台的后台执行机制启动命令。

如果 URL 从浏览器无法访问（在远程/容器化设置中常见），绑定非回环主机：

```bash
scripts/start-server.sh \
  --project-dir /path/to/project \
  --host 0.0.0.0 \
  --url-host localhost
```

使用 `--url-host` 控制返回的 URL JSON 中打印的主机名。

## 循环流程

1. **检查服务器是否存活**，然后**写入 HTML** 到 `screen_dir` 的新文件：
   - 每次写入前，检查 `$STATE_DIR/server-info` 是否存在。如果不存在（或 `$STATE_DIR/server-stopped` 存在），服务器已关闭 — 在继续之前用 `start-server.sh` 重启它。服务器在 30 分钟不活动后自动退出。
   - 使用语义化文件名：`platform.html`、`visual-style.html`、`layout.html`
   - **永远不要重用文件名** — 每个屏幕获得新文件
   - 使用 Write 工具 — **永远不要使用 cat/heredoc**（会在终端中输出噪音）
   - 服务器自动提供最新文件

2. **告诉用户期望什么并结束你的轮次：**
   - 提醒他们 URL（每一步，不只是第一次）
   - 简要文字总结屏幕上的内容（例如，"正在展示首页的 3 种布局选项"）
   - 请他们在终端中回应："看看并告诉我你的想法。如果想的话可以点击选择选项。"

3. **在你的下一轮** — 用户在终端回应后：
   - 读取 `$STATE_DIR/events`（如果存在）— 这包含用户的浏览器交互（点击、选择）作为 JSON 行
   - 与用户的终端文本合并以获得完整图景
   - 终端消息是主要反馈；`state_dir/events` 提供结构化交互数据

4. **迭代或推进** — 如果反馈改变了当前屏幕，写入新文件（例如 `layout-v2.html`）。只有当前步骤验证后才移动到下一个问题。

5. **返回终端时卸载** — 当下一步不需要浏览器时（例如，澄清问题、权衡讨论），推送等待屏幕以清除过时内容：

   ```html
   <!-- filename: waiting.html (or waiting-2.html, etc.) -->
   <div style="display:flex;align-items:center;justify-content:center;min-height:60vh">
     <p class="subtitle">Continuing in terminal...</p>
   </div>
   ```

   这防止用户盯着已解决的选择，而对话已经继续。当下一个视觉问题出现时，像往常一样推送新内容文件。

6. 重复直到完成。

## 编写内容片段

只编写放入页面的内容。服务器自动将其包装在框架模板中（头部、主题 CSS、选择指示器和所有交互基础设施）。

**最小示例：**

```html
<h2>Which layout works better?</h2>
<p class="subtitle">Consider readability and visual hierarchy</p>

<div class="options">
  <div class="option" data-choice="a" onclick="toggleSelect(this)">
    <div class="letter">A</div>
    <div class="content">
      <h3>Single Column</h3>
      <p>Clean, focused reading experience</p>
    </div>
  </div>
  <div class="option" data-choice="b" onclick="toggleSelect(this)">
    <div class="letter">B</div>
    <div class="content">
      <h3>Two Column</h3>
      <p>Sidebar navigation with main content</p>
    </div>
  </div>
</div>
```

就是这样。不需要 `<html>`、CSS 或 `<script>` 标签。服务器提供所有这些。

## 可用的 CSS 类

框架模板为你的内容提供这些 CSS 类：

### 选项（A/B/C 选择）

```html
<div class="options">
  <div class="option" data-choice="a" onclick="toggleSelect(this)">
    <div class="letter">A</div>
    <div class="content">
      <h3>Title</h3>
      <p>Description</p>
    </div>
  </div>
</div>
```

**多选：** 在容器上添加 `data-multiselect` 让用户选择多个选项。每次点击切换项目。指示条显示计数。

```html
<div class="options" data-multiselect>
  <!-- same option markup — users can select/deselect multiple -->
</div>
```

### 卡片（视觉设计）

```html
<div class="cards">
  <div class="card" data-choice="design1" onclick="toggleSelect(this)">
    <div class="card-image"><!-- mockup content --></div>
    <div class="card-body">
      <h3>Name</h3>
      <p>Description</p>
    </div>
  </div>
</div>
```

### 模型容器

```html
<div class="mockup">
  <div class="mockup-header">Preview: Dashboard Layout</div>
  <div class="mockup-body"><!-- your mockup HTML --></div>
</div>
```

### 分割视图（并排）

```html
<div class="split">
  <div class="mockup"><!-- left --></div>
  <div class="mockup"><!-- right --></div>
</div>
```

### 优缺点

```html
<div class="pros-cons">
  <div class="pros"><h4>Pros</h4><ul><li>Benefit</li></ul></div>
  <div class="cons"><h4>Cons</h4><ul><li>Drawback</li></ul></div>
</div>
```

### 模拟元素（线框构建块）

```html
<div class="mock-nav">Logo | Home | About | Contact</div>
<div style="display: flex;">
  <div class="mock-sidebar">Navigation</div>
  <div class="mock-content">Main content area</div>
</div>
<button class="mock-button">Action Button</button>
<input class="mock-input" placeholder="Input field">
<div class="placeholder">Placeholder area</div>
```

### 排版和部分

- `h2` — 页面标题
- `h3` — 部分标题
- `.subtitle` — 标题下的次要文字
- `.section` — 带下边距的内容块
- `.label` — 小型大写标签文字

## 浏览器事件格式

当用户在浏览器中点击选项时，他们的交互被记录到 `$STATE_DIR/events`（每行一个 JSON 对象）。当你推送新屏幕时，文件会自动清除。

```jsonl
{"type":"click","choice":"a","text":"Option A - Simple Layout","timestamp":1706000101}
{"type":"click","choice":"c","text":"Option C - Complex Grid","timestamp":1706000108}
{"type":"click","choice":"b","text":"Option B - Hybrid","timestamp":1706000115}
```

完整事件流显示用户的探索路径 — 他们可能在确定之前点击多个选项。最后一个 `choice` 事件通常是最终选择，但点击模式可能揭示值得询问的犹豫或偏好。

如果 `$STATE_DIR/events` 不存在，用户没有与浏览器交互 — 只使用他们的终端文本。

## 设计技巧

- **根据问题调整保真度** — 布局用线框图，润色问题用精细设计
- **在每个页面上解释问题** — "哪个布局感觉更专业？" 而不只是 "选一个"
- **推进前迭代** — 如果反馈改变了当前屏幕，写新版本
- **每屏最多 2-4 个选项**
- **在重要时使用真实内容** — 对于摄影作品集，使用实际图片（Unsplash）。占位内容会掩盖设计问题。
- **保持模型简单** — 专注于布局和结构，而不是像素级完美的设计

## 文件命名

- 使用语义化名称：`platform.html`、`visual-style.html`、`layout.html`
- 永远不要重用文件名 — 每个屏幕必须是新文件
- 对于迭代：附加版本后缀如 `layout-v2.html`、`layout-v3.html`
- 服务器按修改时间提供最新文件

## 清理

```bash
scripts/stop-server.sh $SESSION_DIR
```

如果会话使用了 `--project-dir`，模型文件会持久保存在 `.superpowers/brainstorm/` 中供以后参考。只有 `/tmp` 会话会在停止时被删除。

## 参考

- 框架模板（CSS 参考）：`scripts/frame-template.html`
- 辅助脚本（客户端）：`scripts/helper.js`