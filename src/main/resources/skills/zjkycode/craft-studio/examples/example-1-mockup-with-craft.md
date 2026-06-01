# Example 1: 基础 Mockup with Craft

> 场景：为 AI 搜索产品做 mockup，展示决策融合 + 反 AI 味

---

## 场景

**产品**：MindSearch — 一个 AI 搜索工具，对话式 + 引用式
**用户**：开发者 / 知识工作者
**目标**：3 个布局选项让人挑选

---

## 流程

### 第 1 步：决策矩阵评估

```
Category: design
Complexity: complex (landing page)
Brand_Need: inspired (≥ 2 brands)
Human_Voice: medium (B2B SaaS 友好但不过度)
→ 路径：2-3 品牌 + 中度人格化
```

### 第 1.5 步：决策融合

**候选品牌**：`notion` (温暖) + `linear.app` (物理感) + `vercel` (技术感)

**抽取 craft_notes**：

| 来源 | anti_ai_decision | 抽取的设计元素 |
|------|------------------|--------------|
| `notion` | 温暖极简，不冷漠 | microcopy 像朋友；hero 3D 渲染 |
| `linear.app` | spring physics | 动画有"重量"；紫色 (#5E6AD2) |
| `vercel` | 速度本身是设计 | Geist Sans + Mono 混排；动画 ≤ 300ms |

**融合为产品专属 craft_principles**：

```yaml
craft_principles:
  visual:
    - "Geist Sans 标题 + Geist Mono 数字（强调'快'）"
    - "紫色 #5E6AD2 主色 + 米色 #FAF8F5 背景（Linear + Notion 调色）"
    - "hero 大字偏左 6px（不完美对称）"
  microcopy:
    - "Search bar 占位符: 'What's on your mind?' （Notion 风格）"
    - "Primary CTA: 'Let's go →' 而非 'Search' （人格化）"
    - "空状态: 'No results yet. Try a different angle?' （有温度）"
  animation:
    - "搜索响应 spring 200ms（Linear 物理感）"
    - "loading: 进度条而非 spinner（Vercel 真实感）"
```

### 第 2 步：启动浏览器

```bash
scripts/start-server.sh --project-dir /path/to/mindsearch
```

### 第 3 步：写 platform.html

```html
<div class="mockup">
  <div class="mockup-header">Option A: Editorial Density</div>
  <div class="mockup-body">
    <h1 style="font-family: 'Geist Sans', sans-serif; padding-left: 6%;">
      Find <em style="font-family: serif;">anything</em> you've read.
    </h1>
    <input class="mock-input"
           placeholder="What's on your mind?"
           style="border-color: #5E6AD2; border-radius: 6px;" />
    <button class="mock-button"
            style="background: #5E6AD2; color: white; border-radius: 999px;">
      Let's go →
    </button>
    <p style="color: #64748b; font-family: 'Geist Mono', monospace; font-size: 13px;">
      23,481 sources indexed
    </p>
  </div>
</div>
```

### 第 4 步：迭代

`platform.html` → `platform-v2.html`（用户反馈"CTA 太小"）→ `platform-v3.html`（用户反馈"想要 demo 视频"）

---

## 反 AI 味检查 ✅

- [x] microcopy 人格化（不是 Get Started）
- [x] 融合 3 个品牌（Notion + Linear + Vercel）
- [x] 1 个 primary CTA（不是 3 个）
- [x] 衬线点缀（"anything" 用 serif 强调）
- [x] 故意的不对称（hero 偏左 6%）
- [x] 等宽字体（"23,481 sources" 用 Geist Mono）
- [x] 圆角按语义（CTA pill / 输入框 6px）

**不是紫蓝渐变** — 用 Notion 的米色 + Linear 的紫色，**不是** AI 默认的紫蓝渐变。

---

## 关键学习

1. **决策融合比选 1 个品牌更"人味"** — 3 个品牌的决策被融合，看不出"贴牌"
2. **microcopy 比 token 更关键** — "Let's go" 而非 "Search" 改变整个气质
3. **故意的不对称** — hero 偏左 6% 比居中更"人放"
4. **衬线点缀** — 关键词用 serif 创造"人写"的感觉
