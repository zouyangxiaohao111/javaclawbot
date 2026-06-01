# Example 4: 决策融合 (Brand Fusion)

> 场景：融合 Stripe + Linear + Notion 3 个品牌，生成产品专属 craft_principles

---

## 场景

**产品**：InkDrop — 一个 markdown 笔记工具，主打"快速 + 温暖"
**用户**：知识工作者 / 写作者
**目标**：landing page 设计，要"既不 Stripe 也不 Linear 也不 Notion"，但吸收三者的精华

---

## Step 1: 选 2-3 个参考品牌

### 决策矩阵

```
Category: design
Complexity: complex (landing page)
Brand_Need: inspired (≥ 2 brands)
Human_Voice: medium (笔记工具友好)
→ 路径：2-3 品牌 + 中度人格化
```

### 候选品牌选择

**为什么选这 3 个**：

| 品牌 | 提供的"反 AI 味决策" |
|------|---------------------|
| **Stripe** | 技术诚实 — 代码块 + tabular 数字 + 真实感 |
| **Linear** | 物理感 — spring 动画 + 紫色主色 + 冷静 |
| **Notion** | 温暖 — 朋友语气 + 3D hero + 鼓励 |

3 个品牌互补：
- Stripe 给"功能感"（不是花哨）
- Linear 给"专业感"（不是业余）
- Notion 给"温度"（不是冷）

---

## Step 2: 抽取 craft_notes

### Stripe 抽取

```yaml
# 从 stripe/DESIGN.md 读
stripe_craft:
  anti_ai_decision: "技术诚实 - 不用渐变模拟高级感，每像素都有功能"
  signature_moves:
    - "代码块用等宽字体强调'这就是真的'"
    - "tabular-figure body type for money/numerics"
  human_voice: "直接、精确、不做作"
  use_when: "B2B SaaS, DevTools, 金融"
```

**对 InkDrop 的可移植元素**：
- ✅ 代码块用等宽（笔记工具有 code block）
- ✅ tabular 数字（笔记工具有字数统计）
- ❌ 金融色（深蓝 + ruby）— 笔记工具不金融

### Linear 抽取

```yaml
linear_craft:
  anti_ai_decision: "物理感 - spring physics，像操作真实物体"
  signature_moves:
    - "spring physics 320/30"
    - "紫色 (#5E6AD2) + 黑色 + 灰阶"
  human_voice: "直接、冷静、精确"
  use_when: "B2B SaaS 工具, issue/project"
```

**对 InkDrop 的可移植元素**：
- ✅ 紫色 #5E6AD2（笔记工具可借鉴）
- ✅ spring 动画（操作流畅）
- ❌ 冷静语气（笔记工具要温暖一点）

### Notion 抽取

```yaml
notion_craft:
  anti_ai_decision: "温暖极简 - 不冷漠、不'工业感'"
  signature_moves:
    - "microcopy 像朋友说话"
    - "3D 渲染 hero"
  human_voice: "温暖、鼓励、不评判"
  use_when: "协作, 个人 productivity, 需要'温度'"
```

**对 InkDrop 的可移植元素**：
- ✅ 温暖 microcopy（"What's on your mind?" → 笔记场景 "What are you writing?"）
- ✅ 3D hero（笔记工具可展示笔记结构）
- ❌ emoji 作图标（InkDrop 选 SVG）

---

## Step 3: 融合为产品专属 craft_principles

```yaml
# InkDrop 专属（不是任何单一品牌）
inkdrop_craft_principles:
  visual:
    # 来自 Linear: 紫色主色
    primary_color: "#5E6AD2"
    # 来自 Notion: 米色背景（不是白）
    background: "#FAF8F5"
    # 来自 Stripe: 数字用 tabular
    tabular_numerics: true
    # 来自 Linear: spring
    animation: "spring 320/30"
    # 来自 Notion: 衬线点缀
    serif_accent: "Newsreader"
    # 来自 Apple: hero 偏左
    hero_offset: "8% left"

  microcopy:
    # 来自 Notion: 朋友语气
    search_placeholder: "What are you writing?"
    # 来自 Notion: 鼓励
    empty_state: "Nothing here yet. Start with one sentence?"
    # 来自 Notion: 温暖
    success: "Kept it. Your notes are safe."
    # 来自 Stripe: 精确
    word_count: "142 words · 4 min read"
    # 来自 Notion + Stripe 融合
    primary_cta: "Start writing →"  # 不是 Get Started, 也不是 Let's go

  components:
    # 来自 Linear: 1 个 primary CTA / 屏
    primary_cta_count: 1
    # 来自 Stripe: tabular 数字
    card_pill: "rgba(94, 106, 210, 0.1)"  # 紫 10%
    # 来自 Notion: 真实 UGC
    user_screenshots: true  # Slack / Twitter 真实对话

  avoid:
    # 反 AI 味（不踩坑）
    - "紫蓝渐变按钮"  # Stripe 教训
    - "完美居中"  # Apple 教训
    - "全部 12px 圆角"  # Stripe 教训
    - "Get Started"  # 通用教训
    - "emoji 作图标"  # 通用教训
    - "6 等宽 Bento Grid"  # Apple 教训
```

---

## Step 4: 验证"不贴牌"

### 反贴牌检查

```
[✓] 不像 Stripe — 没有深蓝 + ruby + 渐变 mesh
[✓] 不像 Linear — 没有冷调 + 黑紫对比
[✓] 不像 Notion — 没有 emoji + 3D
[✓] 像 InkDrop — 米色 + 紫 + 衬线 + 朋友语气
```

**关键**：任何看 InkDrop 的人能说"这是我自己的设计"，不能说"这是 Notion 改的色"。

---

## Step 5: 写 mockup

```html
<div class="mockup">
  <div class="mockup-header">Preview: InkDrop Landing</div>
  <div class="mockup-body">
    <!-- hero 偏左 8% -->
    <div style="padding-left: 8%;">
      <h1 style="font-family: 'Geist Sans', sans-serif;">
        Write <em style="font-family: 'Newsreader', serif; font-style: italic;">freely</em>.
        Find <em style="font-family: 'Newsreader', serif; font-style: italic;">instantly</em>.
      </h1>
      <p style="color: #64748b;">
        Markdown notes that work the way you think.
      </p>
      <button style="background: #5E6AD2; color: white; border-radius: 999px; padding: 8px 16px;">
        Start writing →
      </button>
      <small style="color: #94a3b8; font-family: 'Geist Mono', monospace; font-size: 13px;">
        142 words · 4 min read  <!-- tabular numbers from Stripe -->
      </small>
    </div>

    <!-- 真实 UGC（来自 Notion 经验） -->
    <div class="ugc-card">
      <img src="slack-conversation.png" />  <!-- 真实 Slack 对话截图 -->
      <p>"用 InkDrop 写了 200 篇笔记，搜索永远 0.2s 找到" — @user_real</p>
    </div>
  </div>
</div>
```

---

## Step 6: 反 AI 味检查 ✅

```
[✓] microcopy 人格化（"Start writing →", "What are you writing?"）
[✓] 融合 3 个品牌（不是 1 个）
[✓] 1 个 primary CTA
[✓] SVG 图标（无 emoji）
[✓] 真实素材（Slack 截图）
[✓] 衬线点缀（"freely" / "instantly" 用 Newsreader italic）
[✓] 故意的不对称（hero 偏左 8%）
[✓] 圆角按语义（button pill）
[✓] tabular 数字（字数统计）
[✓] spring 动画（准备在 CSS 里）
```

**10/10 通过**。

---

## 关键学习

### 1. 决策融合 ≠ 拼凑 token
- ❌ "用 Linear 的紫色 + Notion 的米色 + Stripe 的 tabular"（这是拼凑）
- ✅ "3 个品牌各自提供一个'反 AI 味决策'"（这是融合）

### 2. 抽取要回答："为什么这个元素存在？"
- Linear 紫色 → "因为物理感需要冷调支撑"
- Notion 米色 → "因为温暖需要降低对比度"
- Stripe tabular → "因为数字精确感需要"

### 3. 拒绝清单是关键
- `avoid` 字段防止"想加更多"（决策融合最容易过度）

### 4. 验证"不贴牌"是客观可测的
- 找 5 个看过 3 个品牌的人
- 让他们看 InkDrop mockup
- 问："这是哪个品牌改的色？"
- 答不出 / 答错 → 反贴牌成功

---

## 关联资源

- 71 品牌索引：`references/brand-craft-notes.md`
- 决策矩阵：`references/decision-matrix.md`
- 反 AI 味模式：`references/anti-ai-patterns.md`
- 反例 1：`examples/example-1-mockup-with-craft.md`
