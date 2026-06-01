---
name: craft-studio
description: "基于浏览器的视觉头脑风暴伴侣，专为'反 AI 味'设计而生的设计 intelligence。融合 99 UX 准则 + 71 品牌 craft_notes + 人文与情感原则，让 mockup 一眼有人味儿。**前置依赖：必须加载 [brainstorming]**"
---

# Craft Studio - 反 AI 味设计 Intelligence

> "好的设计一眼像人做的，不是一眼像 X 品牌做的。"

## Iron Law（反 AI 味 4 不允许）

**绝对禁止**，违反 = 自动重做：

1. ❌ "Get Started / Learn More / Sign Up" 等模板化 microcopy
2. ❌ 全用 1 个品牌的 token（贴牌）
3. ❌ 3+ 个 CTA 在同一屏
4. ❌ 用 emoji 当图标

---

## 何时使用 / 何时不用

**使用**：UI mockup / 布局对比 / 品牌风格选择 / 设计新页面 / 重构 UI / A/B 视觉选项
**不用**：纯文字 / 概念问题 / 范围决策（终端文字即可）

---

## 快速开始（4 步）

### 1. 读原则层（按需）
- `references/99-ux-rules.md`（99 准则）
- `references/human-voice-guide.md`（人文原则）
- `references/anti-ai-patterns.md`（16 反 AI 模式）

### 2. 决策融合（核心！）
- 读 `references/decision-matrix.md`（4 维评估）
- 选 **2-3 个参考品牌**（不只 1 个！）
- 读 `design-md/{品牌}/DESIGN.md` 的 `craft_notes` 字段
- 抽取每个的 `anti_ai_decision` + `signature_moves`
- 融合为产品专属的 `craft_principles`
- 写产品专属的 `microcopy` 列表（不是模板）

### 3. 启动浏览器
```bash
scripts/start-server.sh --project-dir /path/to/project
```

### 4. 写 mockup → 迭代 → 等待反馈
按 `references/main-loop-pseudocode.md` 的循环。

---

## 决策矩阵（4 维）

| 维度 | 选项 |
|------|------|
| Category | design / architecture / workflow |
| Complexity | trivial / simple / complex / multi-system |
| Brand_Need | none / reference / **inspired** / from-scratch |
| Human_Voice | low / medium / high |

详见 `references/decision-matrix.md`。

---

## 反 AI 味 4 机制

1. **人文与情感**（Human & Emotion）— microcopy + 真实素材 + 故意不对称
2. **决策融合**（Decision Fusion）— 选 2-3 品牌 + 抽取 craft_notes
3. **约束驱动**（Constraint-Driven）— 1 字体 / 1 圆角 / 1 阴影 / 4-8 色 / 1 CTA
4. **文化与情境**（Cultural Context）— 领域契合 + 字体 + 色调

详见 `references/human-voice-guide.md` 和 `references/anti-ai-patterns.md`。

---

## 工作流

### 第 1 步：原则选择
读 99 准则 + 人文原则，识别本任务需要的 1-3 条规则。

### 第 1.5 步：决策融合（核心！）
**不要直接选 1 个品牌的 token**。必须：
- 选 2-3 个参考品牌
- 抽取每个的 `craft_notes.anti_ai_decision`
- 融合为产品专属的 `craft_principles`
- 写产品专属的 `microcopy` 列表

### 第 2 步：浏览器启动
```bash
scripts/start-server.sh --project-dir /path/to/project
```

### 第 3 步：写 mockup
- 写内容片段（自动包装）
- 用语义化文件名（`platform.html` / `layout.html` / `voice-v1.html`）
- **永远不重用文件名**

### 第 4 步：迭代
- 读 `$STATE_DIR/events`（浏览器事件）
- 合并用户终端文本
- 决定下一步（迭代 / 推进 / fallback）

---

## DESIGN.md 使用

**路径**：`design-md/{品牌名}/DESIGN.md`

**必读字段**（新增）：
- `craft_notes.anti_ai_decision` — 品牌的反 AI 味核心决策
- `craft_notes.signature_moves` — 3-5 个标志性手法
- `craft_notes.human_voice` — 品牌的"语气"
- `craft_notes.use_when` — 何时用此品牌
- `craft_notes.avoid_when` — 何时避免
- `craft_notes.reference_examples` — 参考 URL 和学习点

**用法**：**抽取 `craft_notes`，不直接套 token**。融合 2-3 个品牌的 `craft_notes`。

**状态**：v1 手工撰写 10 个标杆（`stripe` / `linear.app` / `apple` / `notion` / `vercel` / `claude` / `tesla` / `shopify` / `figma` / `airbnb`）。其余 61 个品牌用 `brand-craft-notes.md` 反推。

---

## 集成

- **前置依赖**：[brainstorming]
- **替换**：[visual-companion]（被引用 5 处 → 已更新）
- **多 Tab**：file lock 同步（详见 visual-companion 经验）

---

## 常见误区

| ❌ 误区 | ✅ 正确 |
|--------|--------|
| 全用 1 个品牌 token | 融合 2-3 个品牌的 craft_notes |
| 模板化 microcopy | 见 `human-voice-guide.md` |
| emoji 作图标 | Lucide / Heroicons |
| 3+ CTA 同屏 | 1 个 primary CTA |
| 完美对称 | 故意不对称 |
| 过度留白 | 有目的的留白 |
| 紫蓝渐变 | 纯色 + 鲜明对比 |

---

## 危险信号

- "反正用户看不到细节" → 细节决定品味
- "AI 都这样" → 这是借口，不是事实
- "X 品牌就是这么做的" → 决策融合，不是贴牌
- "简单点就行" → 仍然要遵循 4 机制

---

## 验收清单

- [ ] microcopy 人格化（不是模板）
- [ ] 融合 2-3 个品牌（不是 1 个）
- [ ] 1 个 primary CTA / 屏
- [ ] SVG 图标（不用 emoji）
- [ ] 真实素材（不用 stock）
- [ ] 衬线点缀（标题用衬线）
- [ ] 故意的不对称
- [ ] 圆角按语义

---

## 参考

- 71 品牌索引：`references/brand-craft-notes.md`
- 快速索引：`references/design-md-index.md`
- 决策矩阵：`references/decision-matrix.md`
- 主循环：`references/main-loop-pseudocode.md`
- 99 UX 准则：`references/99-ux-rules.md`
- 人文原则：`references/human-voice-guide.md`
- 反 AI 模式：`references/anti-ai-patterns.md`
- 框架模板：`scripts/frame-template.html`
