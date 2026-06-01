# 主循环伪代码

> craft-studio 浏览器服务的核心循环。来源 visual-companion 工作流，新增"反 AI 味"决策融合步骤。

---

## 启动流程

```bash
scripts/start-server.sh --project-dir /path/to/project
```

返回：

```json
{
  "type": "server-started",
  "port": 52341,
  "url": "http://localhost:52341",
  "screen_dir": "/path/to/project/.zjkycode/brainstorm/12345-1706000000/content",
  "state_dir": "/path/to/project/.zjkycode/brainstorm/12345-1706000000/state"
}
```

---

## 主循环

```
LOOP for each visual question:

  1. 检测服务器存活
     READ $STATE_DIR/server-info
     IF 不存在 → 重启 (scripts/start-server.sh)

  2. 原则选择（新增）
     READ references/99-ux-rules.md
     SELECT 1-3 条与本任务相关的规则
     READ references/human-voice-guide.md
     SELECT 适用的 microcopy 风格

  3. 决策融合（新增！核心）
     READ references/decision-matrix.md
     EVALUATE 4 维度（Category / Complexity / Brand_Need / Human_Voice）
     DETERMINE 路径（终端文字 / 1 品牌 / 2 品牌 / 决策融合）

     IF Brand_Need = inspired OR from-scratch:
       SELECT 2-3 个参考品牌（必须 ≥ 2）
       READ design-md/{brand}/DESIGN.md 的 craft_notes
       EXTRACT 每个的 anti_ai_decision + signature_moves
       FUSE 为产品专属的 craft_principles

  4. 写 HTML 到 screen_dir
     WRITE {新文件名}.html  # 永远不重用文件名
     # 文件名示例：platform.html, layout.html, voice-v1.html
     # 服务器自动包装完整 HTML（除非以 <!DOCTYPE 开头）

  5. 提示用户
     提醒 URL
     简要文字总结（含决策融合来源）
     "看看并告诉我你的想法"

  6. 等待用户反馈
     READ $STATE_DIR/events  # 浏览器事件
     COMBINE 终端文本

  7. 决定下一步
     IF 反馈改变当前屏 → 写新文件 (v2, v3, ...)
     IF 当前屏验证 → 进入下一问题
     IF 不需要浏览器 → 写 waiting.html

END LOOP
```

---

## 关键点

- **每屏新文件**：`layout.html` → `layout-v2.html` → `layout-v3.html`
- **写内容片段**：服务器自动包装完整 HTML（除非以 `<!DOCTYPE` 开头）
- **等待屏**：返回终端前推送 `waiting.html` 清空浏览器
- **30 分钟无活动**：服务器自动退出

---

## CSS 类清单

### 选项

```html
<div class="options">
  <div class="option" data-choice="a" onclick="toggleSelect(this)">
    <div class="letter">A</div>
    <div class="content"><h3>Title</h3><p>Description</p></div>
  </div>
</div>
```

### 多选

```html
<div class="options" data-multiselect>
  <!-- 同样 markup，用户可选多个 -->
</div>
```

### 卡片

```html
<div class="cards">
  <div class="card" data-choice="design1" onclick="toggleSelect(this)">
    <div class="card-image"><!-- mockup --></div>
    <div class="card-body"><h3>Name</h3><p>Description</p></div>
  </div>
</div>
```

### 模型（mockup 容器）

```html
<div class="mockup">
  <div class="mockup-header">Preview: Dashboard Layout</div>
  <div class="mockup-body"><!-- mockup HTML --></div>
</div>
```

### 分割视图（对比）

```html
<div class="split">
  <div class="mockup"><!-- left --></div>
  <div class="mockup"><!-- right --></div>
</div>
```

### 模拟元素

```html
<div class="mock-nav">Logo | Home | About</div>
<div class="mock-sidebar">Navigation</div>
<div class="mock-content">Main</div>
<button class="mock-button">Action</button>
<input class="mock-input" placeholder="Input">
<div class="placeholder">Placeholder</div>
```

---

## 反 AI 味检查（mockup 完成前）

```
CHECKLIST 写 HTML 后必查：

[ ] microcopy 不用 Get Started / Learn More
[ ] 不是"全用 X 品牌 token"（决策融合后）
[ ] 1 屏 ≤ 1 个 primary CTA
[ ] 没有 emoji 作图标
[ ] 至少 1 处衬线点缀（标题 / 数字 / 引用）
[ ] 真实素材（不用 stock）
[ ] 故意的不对称（hero 偏左 / 偏右 4-8px）
[ ] 圆角按语义（按钮 pill / 卡片 12px / 输入框 4px）

IF 任意 1 项不通过 → 修复后推送 v2
```

---

## 错误处理

| 场景 | 处理 |
|------|------|
| 浏览器启动失败 | fallback 到终端文字 + ASCII mockup |
| DESIGN.md 缺失 | 提示 `git clone https://github.com/VoltAgent/awesome-design-md.git` |
| craft_notes 字段缺失 | 使用 `design-md-index.md` 的快速索引 + 通用 craft_notes |
| 选 1 个品牌（违反决策融合） | 拒绝并提示"必须选 2-3 个" |
| microcopy 用 Get Started | 警告并提供 5 个替代方案 |
| 跨平台启动失败 | 用 `--disable-owner-monitor` 标志（visual-companion 经验） |

---

## 集成点

- **前置依赖**：[brainstorming]
- **替换**：[visual-companion]（保留为参考）
- **多 Tab**：file lock 同步（详见 visual-companion 经验）
