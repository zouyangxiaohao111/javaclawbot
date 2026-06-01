# 16 反 AI 味设计模式

> 每个模式：**表现（What）→ 典型案例（Example）→ 反例（Counter）→ 修复方法（Fix）**
> 配套：`human-voice-guide.md`（人文替代）、`99-ux-rules.md`（基础规则）

---

## 模式 1：紫蓝渐变（The AI Gradient）

- **表现**：所有 CTA 用紫蓝渐变（#6366F1 → #8B5CF6）模拟"高级感"
- **典型案例**：landing page 的 "Get Started" 按钮
- **反例**：纯色（黑 / 白 / 单一品牌色）+ 鲜明对比
- **修复**：用 brand color 实色 + hover 状态切换；渐变仅用于 hero 背景
- **参考品牌**：Stripe（gradient mesh 是 hero 背景，不是按钮）

---

## 模式 2：完美对称（The Mirror Layout）

- **表现**：所有元素居中、对称、平衡
- **典型案例**：dashboard 三个 card 完美等宽
- **反例**：hero 图偏左 4-8px / card 高度不一致
- **修复**：故意制造"人放"的不对称；用 5-10% 偏移
- **参考品牌**：Apple（hero 大图偏左 4-8px，制造呼吸）

---

## 模式 3：同质圆角（The Universal Radius）

- **表现**：所有卡片 12-16px 圆角
- **典型案例**：所有元素 8px 圆角
- **反例**：0 / 4 / 12 / 9999 混用（按钮 pill，卡片 12px，输入框 4px）
- **修复**：按"语义"决定圆角（按钮=pill，卡片=8-12px，输入框=4-6px，tag=full）
- **参考品牌**：Stripe（rounded.xs=4 / sm=6 / md=8 / lg=12 / xl=16 / pill=9999）

---

## 模式 4：模板 microcopy

- **表现**：Get Started / Learn More / Sign Up / Submit
- **典型案例**：所有 SaaS 用同一套按钮文案
- **反例**：人格化（"Let's go →" / "Show me how" / "Count me in"）
- **修复**：见 `human-voice-guide.md` §1
- **参考品牌**：Notion（"What's on your mind?" 而非 "New page"）

---

## 模式 5：emoji 作图标

- **表现**：📊 📈 🏠 替代矢量图标
- **典型案例**：dashboard 用 emoji 表示数据 / 导航用 emoji
- **反例**：Lucide / Heroicons SVG
- **修复**：用 Lucide React / Heroicons 库；emoji 仅用于 UGC 真实截图
- **参考品牌**：Linear（所有图标 SVG，零 emoji）

---

## 模式 6：Bento Grid 泛滥

- **表现**：所有页面拆成 4-6 个 card grid
- **典型案例**：feature page 6 个矩形 grid
- **反例**：根据内容决定布局（可能是 1 大 + 2 小 / 时间线 / 列表）
- **修复**：根据信息层级决定；不要为 bento 而 bento
- **参考品牌**：Apple（feature page 大小卡片混排，不用统一 grid）

---

## 模式 7：单一 accent 色

- **表现**：所有 SaaS = "slate-900 on white + 1 个蓝"
- **典型案例**：色彩单调，全灰 + 蓝色按钮
- **反例**：与领域契合的色调（金融深色 / 医疗柔和 / 工具黑白）
- **修复**：见 `human-voice-guide.md` §5 领域表
- **参考品牌**：Linear（紫色 + 黑 + 灰）/ Stripe（深蓝 + 紫 + ruby）

---

## 模式 8：阴影滥用

- **表现**：所有卡片都加 box-shadow
- **典型案例**：dashboard 每张卡都有阴影
- **反例**：hairline border 替代 / 仅在 modal/dropdown 用阴影
- **修复**：阴影只用于"漂浮"元素（modal / popover / dropdown）
- **参考品牌**：Stripe（card-feature-light 用 hairline border，card-dashboard-mockup 才用 shadow）

---

## 模式 9：缺图标的卡片

- **表现**：纯文字块，无视觉锚点
- **典型案例**：feature 列表只有标题 + 文字
- **反例**：图标 + 标题 + 文字 组合
- **修复**：每个 feature 配 Lucide 图标（24-32px）
- **参考品牌**：Linear（feature 列表都用精致 SVG icon）

---

## 模式 10：过度留白

- **表现**：32px+ padding 无内容 / 200px section gap
- **典型案例**：hero 区域 200px padding
- **反例**：有目的的留白（分隔 section 而非填充）
- **修复**：垂直 rhythm 用 8 的倍数（64/72/96px 用于 section gap）
- **参考品牌**：Notion（rhythm 紧凑，但每个 section 有目的留白）

---

## 模式 11：千篇一律的 hero

- **表现**：大字 + 副标题 + 1 个 CTA + 1 张产品截图
- **典型案例**：所有 SaaS landing 都是这个结构
- **反例**：独特 hero 结构（Notion 3D 渲染 / Linear 极简动画 / Stripe mesh）
- **修复**：参考 `brand-craft-notes.md` 中的标杆；让 hero 讲故事
- **参考品牌**：Notion（3D 动画 hero）/ Linear（极简 + 流畅动画）

---

## 模式 12：统一的 sans-serif

- **表现**：所有字体都是 Inter
- **典型案例**：标题 + body + code 全是 Inter
- **反例**：衬线标题 + sans body / 衬线点缀
- **修复**：见 `human-voice-guide.md` §3
- **参考品牌**：Stripe（Söhne thin 300 + Inter fallback）/ Apple（SF Pro Display + SF Pro Text）

---

## 模式 13：缺少 micro-interaction

- **表现**：hover 状态无变化 / transition 死板
- **典型案例**：按钮 hover 没反应
- **反例**：精细的 hover/focus 状态（颜色 + 阴影 + 缩放 100-200ms）
- **修复**：所有可交互元素加 transition（150-300ms）
- **参考品牌**：Linear（每个交互都有 spring physics 反馈）

---

## 模式 14：单色模式

- **表现**：只做 light mode
- **典型案例**：没有 dark mode
- **反例**：light + dark 同时设计
- **修复**：设计 token 支持双主题（`prefers-color-scheme`）
- **参考品牌**：Vercel（light + dark 同样精雕细琢）

---

## 模式 15：同质化 dashboard

- **表现**：侧栏 + 顶栏 + 主区域（三件套）
- **典型案例**：所有 dashboard 都是这个结构
- **反例**：根据使用场景定制（Linear 底部导航 / Notion 侧栏树 / Figma 画布为主）
- **修复**：参考品牌 craft_notes；让导航服务任务
- **参考品牌**：Linear（issues dashboard 顶部排序 + 侧栏筛选，不用三件套）

---

## 模式 16：stock 图滥用

- **表现**：握手 / 笑脸 / 办公室照
- **典型案例**：team page 用 stock 团队照 / about 用办公室走廊
- **反例**：真实场景 / 插画 / 抽象图 / 真实 UGC
- **修复**：见 `human-voice-guide.md` §4
- **参考品牌**：Airbnb（真实 host 照片，user-generated）/ Stripe（产品 UI 截图 + 真实客户 logo）

---

## 反 AI 味 4 大机制回顾

| 机制 | 解决的问题 | 关键实践 |
|------|----------|---------|
| 1. 人文与情感 | 缺"人味" | microcopy + 真实素材 + 故意不对称 |
| 2. 决策融合 | 防止贴牌 | 选 2-3 品牌 + 抽取 craft_notes |
| 3. 约束驱动 | 防止过度 | 1 字体 / 1 圆角 / 1 阴影 / 4-8 色 / 1 CTA |
| 4. 文化与情境 | 防止通用化 | 领域契合 + 字体 + 色调 |

---

## 快速自检清单

mockup 完成前必查：

- [ ] 没有紫蓝渐变按钮
- [ ] hero / 卡片有不对称设计
- [ ] 圆角按语义选择（不是统一 12px）
- [ ] microcopy 不是模板（不是 Get Started）
- [ ] 没有 emoji 作图标
- [ ] 没有千篇一律的 Bento Grid
- [ ] accent 色与领域契合
- [ ] 阴影仅用于漂浮元素
- [ ] feature 列表配图标
- [ ] 留白有目的
- [ ] hero 结构独特
- [ ] 至少 1 处衬线点缀
- [ ] 交互有 micro-feedback
- [ ] 同时支持 light + dark
- [ ] 导航服务任务（不是三件套）
- [ ] 真实素材（不用 stock）

**违反任意 1 条 = AI 味风险。** 修复后再交付。
