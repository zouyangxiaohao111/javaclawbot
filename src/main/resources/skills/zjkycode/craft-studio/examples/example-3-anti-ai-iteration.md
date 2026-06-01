# Example 3: 反 AI 味迭代

> 场景：发现 6 个 AI 味模式，逐一修复，验证效果

---

## 场景

**产品**：DevBoard — 开发者 dashboard
**问题**：v1 mockup 由 AI 生成，团队反馈"一眼 AI 味"
**目标**：识别 6 个反 AI 味模式并修复

---

## 识别 6 个 AI 味模式

### 模式 1：紫蓝渐变按钮 ❌

```html
<!-- v1 -->
<button style="background: linear-gradient(135deg, #6366F1, #8B5CF6);">
  Get Started
</button>
```

**修复**（用 Stripe 风格）：

```html
<!-- v2 -->
<button style="background: #5E6AD2; border-radius: 999px;">
  Let's go →
</button>
```

**修复要点**：纯色 + 鲜明对比，pills 形，不用渐变。

---

### 模式 2：完美对称居中 ❌

```html
<!-- v1 -->
<div style="display: flex; justify-content: center; align-items: center;">
  <h1>Welcome to DevBoard</h1>
</div>
```

**修复**（用 Apple 风格）：

```html
<!-- v2 -->
<div style="padding-left: 8%; padding-right: 4%;">
  <h1>Welcome to DevBoard</h1>
</div>
```

**修复要点**：hero 偏左 8% / 4%，制造"人放"。

---

### 模式 3：同质圆角 ❌

```html
<!-- v1 -->
<style>
  .card { border-radius: 12px; }
  .button { border-radius: 12px; }
  .input { border-radius: 12px; }
  .tag { border-radius: 12px; }
</style>
```

**修复**（按语义）：

```html
<!-- v2 -->
<style>
  .card { border-radius: 12px; }   /* 卡片 12px */
  .button { border-radius: 9999px; } /* 按钮 pill */
  .input { border-radius: 6px; }    /* 输入框 6px */
  .tag { border-radius: 9999px; }    /* tag pill */
</style>
```

**修复要点**：按"语义"决定圆角（按钮 pill / 卡片 12px / 输入框 6px）。

---

### 模式 4：模板 microcopy ❌

```html
<!-- v1 -->
<button>Get Started</button>
<a>Learn More</a>
<button>Sign Up</button>
```

**修复**（人格化）：

```html
<!-- v2 -->
<button>Let's go →</button>
<a>Show me how</a>
<button>Count me in</button>
```

**修复要点**：见 `human-voice-guide.md` §1。

---

### 模式 5：emoji 作图标 ❌

```html
<!-- v1 -->
<div class="icon">📊</div>
<div class="icon">📈</div>
<div class="icon">🏠</div>
```

**修复**（用 Lucide SVG）：

```html
<!-- v2 -->
<svg class="icon" viewBox="0 0 24 24">
  <path d="..." /> <!-- 柱状图 SVG -->
</svg>
```

**修复要点**：用 Lucide / Heroicons 库。emoji 仅用于 UGC 真实截图。

---

### 模式 6：千篇一律 Bento Grid ❌

```html
<!-- v1：6 个等宽 card -->
<div style="display: grid; grid-template-columns: repeat(3, 1fr);">
  <div class="card">Feature 1</div>
  <div class="card">Feature 2</div>
  <div class="card">Feature 3</div>
  <div class="card">Feature 4</div>
  <div class="card">Feature 5</div>
  <div class="card">Feature 6</div>
</div>
```

**修复**（按信息层级）：

```html
<!-- v2：1 大 + 2 小 + 1 时间线 -->
<div style="display: grid; grid-template-columns: 2fr 1fr; gap: 24px;">
  <div class="card card-hero"> <!-- 占 2 列 -->
    <h2>主功能：实时部署</h2>
    <p>最重要的功能占最大空间</p>
  </div>
  <div class="card">
    <h3>辅助 1</h3>
  </div>
  <div class="card">
    <h3>辅助 2</h3>
  </div>
</div>
<div class="timeline">
  <h3>最近 24 小时</h3>
  <ul>
    <li>3 个 PR 合并</li>
    <li>1 个部署成功</li>
  </ul>
</div>
```

**修复要点**：根据信息层级决定布局，**不为 Bento 而 Bento**。

---

## 修复前后对比

| 维度 | v1 (AI 味) | v2 (反 AI 味) | 修复参考 |
|------|----------|-------------|---------|
| CTA 配色 | 紫蓝渐变 | 纯色 #5E6AD2 | Stripe |
| Hero 对齐 | 完美居中 | 偏左 8% | Apple |
| 圆角 | 全部 12px | 语义化（pill/12/6px） | Stripe |
| microcopy | Get Started | Let's go → | Notion |
| 图标 | 📊 emoji | SVG | Linear |
| 布局 | 6 等宽 card | 1 大 + 2 小 + 时间线 | Apple |
| 衬线 | 0 | "actually" 衬线斜体 | 通用 |
| 真实素材 | 0 | 0（v2 还没加） | Airbnb |

**总修复**：6/6 反 AI 味模式。

---

## 反 AI 味检查（v2 验证）

```
[✓] 不是紫蓝渐变
[✓] hero 偏左 8%
[✓] 圆角按语义
[✓] microcopy 人格化
[✓] SVG 图标（非 emoji）
[✓] 按信息层级布局
[✓] 衬线点缀
[ ] 真实素材（v3 加 Slack 真实截图）
```

---

## 关键学习

1. **反 AI 味不是"加东西"，是"减 + 改"** — 删 emoji / 删居中 / 改圆角
2. **每条反 AI 味模式都有"参考品牌"** — 不要凭空想，去看 71 品牌
3. **修复有优先级** — 紫蓝渐变 + 模板 microcopy 是 2 个最明显的
4. **没有"完美反 AI 味"** — v2 还差"真实素材"，v3 继续加

---

## 关联资源

- 16 模式全表：`references/anti-ai-patterns.md`
- 修复方法工具：`references/human-voice-guide.md`
- 决策融合：`examples/example-4-brand-fusion.md`
