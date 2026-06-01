# 人文与情感原则

> craft-studio 的核心精神：让 mockup 一眼像"有人"做的。
> 配套：`anti-ai-patterns.md`（识别问题）、`99-ux-rules.md`（基础规则）、`brand-craft-notes.md`（品牌库）。

## 1. 语气化 microcopy

### 1.1 模板 → 人格化对照表

| ❌ AI 味模板 | ✅ 人文版本 | 适用场景 |
|------------|------------|---------|
| "Get Started" | "Let's go →" | B2B / 消费 |
| "Learn More" | "Show me how" | 教育 / SaaS |
| "Sign Up" | "Count me in" | 社区 / 工具 |
| "Submit" | "Send it" | 工具 / 移动 |
| "Cancel" | "Never mind" | 表单 / 设置 |
| "Continue" | "Onward" | 向导 / 流程 |
| "Save" | "Keep it" | 编辑器 |
| "Delete" | "Remove" | 设置（避免"删除"的惊吓） |
| "Settings" | "Preferences" | 工具（更友好） |
| "Help" | "How can I help?" | 客服 / 文档 |
| "Try it free" | "Take it for a spin" | 消费 SaaS |
| "Add to cart" | "Add to bag" | 电商 |
| "Buy now" | "Make it mine" | 高端电商 |
| "Skip" | "Not now" | 引导流 |
| "Done" | "All set" | 表单结束 |

### 1.2 按产品类型匹配

| 产品类型 | 风格 | 示例 |
|---------|------|------|
| B2B SaaS | 友好但不过度 | "Let's go" / "Count me in" |
| 消费者 App | 鼓励、温暖 | "Let's dive in" / "Try it free" |
| 开发者工具 | 保留命令感 | `>> run` / `make` / `yarn dev` |
| 企业软件 | 保留正式感 | "Proceed" / "Confirm" / "Submit" |
| 金融科技 | 严谨 + 安全感 | "Lock it in" / "Verified" |
| 医疗 | 温柔 + 关怀 | "We're with you" / "Take your time" |
| 教育 | 鼓励 + 好奇 | "Try it" / "What if..." |
| 儿童 | 简单 + 玩耍 | "Let's play!" / "Try!" |
| 创意工具 | 自由 + 探索 | "Make something" / "Surprise me" |

### 1.3 错误信息的人性化

| ❌ 死板 | ✅ 人文 |
|--------|--------|
| "Error 500" | "Hmm, that didn't work. Try again?" |
| "操作失败" | "Something went sideways. Give it another shot." |
| "Invalid input" | "That doesn't look right. Check the email format?" |
| "Network error" | "Looks like we're offline. Reconnect?" |
| "Permission denied" | "You don't have access to this. Ask an admin?" |
| "Session expired" | "You've been away too long. Sign in again?" |
| "Required field" | "We need this one to keep going." |

### 1.4 成功反馈

| ❌ 机械 | ✅ 温暖 |
|--------|--------|
| "Success" | "You're in." |
| "Saved" | "Kept it." |
| "Sent" | "On its way." |
| "Updated" | "All set." |
| "Created" | "Made it." |

---

## 2. 故意的不对称

### 2.1 留白不规则

**为什么**：机械的 32px grid 让人感觉"工业模板"。

```css
/* ❌ 死板 */
.section { padding: 32px; }

/* ✅ 人文节奏 */
.section {
  padding: 32px 36px 28px;  /* 上 32 / 左右 36 / 下 28 */
}
```

**原则**：5-10% 随机偏移，制造"人放"感觉。

### 2.2 元素错位

**做法**：
- Hero 图偏左 / 偏右 4-8px
- 标题左对齐时，文字基线不一定与网格对齐
- 卡片高度偶尔 +1/-1px

```css
/* ❌ 完美居中 */
.hero { display: flex; justify-content: center; align-items: center; }

/* ✅ 故意偏移 */
.hero { padding-left: 8%; padding-right: 4%; }
```

### 2.3 强调靠字号而非字重

| ❌ 死板 | ✅ 人文 |
|--------|--------|
| 全部加粗强调 | 偶尔用 32px vs 24px 字号对比 |
| 14px 标题 + bold | 24px 标题 + regular（不加粗） |
| 字重阶梯 400/600/800 | 字重阶梯 300/400（少 weight 变化） |

### 2.4 不规则间距示例

| 位置 | 模板间距 | 人文间距 |
|------|---------|---------|
| Hero 上下 padding | 64px / 64px | 64px / 56px |
| 卡片间 | 24px | 24px / 28px / 20px |
| 段落间 | 16px | 16px / 18px / 14px |
| Section 间 | 64px | 72px / 56px / 64px |

---

## 3. 衬线 / 手写体点缀

### 3.1 标题用衬线

| 推荐字体 | 风格 | 适用 |
|---------|------|------|
| Newsreader | 现代 + 温暖 | 通用 / 工具 |
| GT Sectra | 编辑感 + 优雅 | 媒体 / 内容 |
| Fraunces | 复古 + 灵活 | 创意 / 品牌 |
| Source Serif | 经典 + 易读 | 企业 / 文档 |
| Söhne Mono | 工业 + 极简 | 开发者工具 |

**搭配**：body 用 Inter / Geist Sans / IBM Plex Sans。

**Fallback**：`'Georgia', serif`（系统衬线）。

### 3.2 关键数字用衬线

```html
<!-- ✅ 关键数字用衬线，强调"人写" -->
<p>从 <span class="num-serif">0</span> 到 <span class="num-serif">1</span></p>
<p><span class="num-serif">10x</span> faster</p>
```

```css
.num-serif {
  font-family: 'Newsreader', 'Georgia', serif;
  font-variant-numeric: oldstyle-nums;
  font-style: italic;
}
```

### 3.3 引用块用衬线斜体

```html
<blockquote class="quote">
  "Design is not just what it looks like and feels like. Design is how it works."
</blockquote>
```

```css
.quote {
  font-family: 'Newsreader', 'Georgia', serif;
  font-style: italic;
  font-size: 22px;
  line-height: 1.5;
  border-left: 2px solid var(--accent);
  padding-left: 24px;
}
```

### 3.4 谨慎使用手写体

- **可用于**：引言、签名、特殊标注
- **不可用于**：导航、按钮、表格数字
- **推荐字体**：Caveat、Reenie Beanie、Architects Daughter

---

## 4. 真实素材

### 4.1 真实头像

| ❌ AI 味 | ✅ 人文 |
|--------|--------|
| Generic avatar（灰色背景 + 字母） | 真实人像（Unsplash randomuser.me） |
| AI 生成的"完美"头像 | 真实光影 / 真实表情 |
| 卡通插画头像 | 真实摄影 |

**资源**：
- `https://unsplash.com/photos/random/face`
- `https://randomuser.me/photos`
- `https://uifaces.co`

### 4.2 真实照片

| ❌ AI 味 | ✅ 人文 |
|--------|--------|
| Stock photo（握手 / 笑脸 / 办公室） | Unsplash 真实场景 |
| 完美摆拍 | 街头摄影 / 自然光 / 抓拍 |
| 后期过度 | 保留胶片颗粒 |

**推荐风格**：
- 街头摄影（Brandon Woelfel / 35mm 风格）
- 自然光（清晨 / 黄昏 / 阴天柔光）
- 抓拍瞬间（不完美才有"人"）

**资源**：
- `https://unsplash.com`
- `https://glasshouse.photo`（人物）
- `https://www.producthunt.com/posts/photo-stocks`

### 4.3 真实 UGC（用户生成内容）

| ❌ AI 味 | ✅ 人文 |
|--------|--------|
| 假造的 customer testimonials | 真实用户截图 |
| 完美排版的"用户评价" | 真实对话（带平台 UI 痕迹） |
| 头像 + 5 星 + 文字 | Slack 截图 / Twitter 截图 / 邮件截图 |

**获取方式**：
- 截取 Slack / Discord 真实对话
- 截取 Twitter / X 推荐
- 截取 iMessage / WhatsApp
- 截取 GitHub issue 评论
- 截取 Linear / Notion 截图

**视觉处理**：
- 保留平台 UI chrome（Slack 紫色侧栏 / Twitter 蓝色）
- 模糊头像 + 真实昵称
- 加设备框（iPhone 截图 / Mac 截图）

### 4.4 真实数据

- 不用"123 / 456"占位数字
- 不用"Lorem ipsum 50 字"
- 真实场景：客户名、订单号、金额、日期
- 数字格式：本地化（千分位 / 货币 / 日期）

---

## 5. 领域 × 字体 × 色调推荐

### 5.1 推荐表

| 领域 | 标题字体 | Body 字体 | 主色调 | 强调色 | 反 AI 味要点 |
|------|---------|----------|--------|--------|------------|
| **金融科技** | GT Sectra / Söhne | Inter | 严谨深色 (#0A1628) | 金 / 银 (#C9A961) | 不用渐变；用表格 + tnum |
| **医疗** | GT Walsheim | Inter | 柔和绿 / 蓝 | 暖白 | 不用红色强调；温柔语气 |
| **工具 / DevTools** | Geist Mono / IBM Plex Mono | Geist Sans / Inter | 黑白 + 单一 accent | 1 个鲜明色 | 保留命令感；code 即 hero |
| **消费品** | 无衬线 + 衬线标题 | Inter | 鲜亮 | 真实照片 | 不用 emoji；用真实场景 |
| **儿童** | rounded (Quicksand) | Quicksand | 鲜亮 + 手绘 | 多色 | 玩耍语气；大触摸目标 |
| **企业** | 衬线标题 + sans body | Inter | 深色 + 严肃 | 1 个金色 | 保留正式感；不用渐变 |
| **教育** | 衬线 + 手写体点缀 | Inter | 温暖 + 自然 | 1 个鼓励色 | 好奇语气；用真实故事 |
| **媒体 / 内容** | 衬线大标题 | sans body | 高对比黑白 | 1 个鲜明 accent | 编辑感；不用渐变 |
| **AI 工具** | Geist Sans | Inter | 黑 + 1 accent | 渐变可（少量） | 诚实语气；承认局限 |
| **创意 / 设计** | 衬线（手写体点缀） | Inter | 高对比 + 大色块 | 多色但和谐 | 自由探索；不用模板 |
| **电商** | sans 标题 | Inter | 鲜亮 + 真实照片 | 1 个 CTA 色 | 真实 UGC；不用 stock |
| **社交** | sans 标题 | Inter | 鲜亮 | 多色但克制 | 真实头像；不用卡通 |

### 5.2 字体推荐资源

- **Google Fonts**：`https://fonts.google.com`（免费、CDN）
- **Inter**：`https://rsms.me/inter/`（开源）
- **Söhne**（付费）：Klim Type Foundry
- **GT America**（付费）：Grilli Type
- **Geist**（开源）：Vercel
- **IBM Plex**（开源）：IBM

### 5.3 色调灵感

- **金融科技深色**：`#0A1628` / `#1C2A3D` / `#0F2942`
- **医疗柔和**：`#E8F0EE` / `#A8C5BD` / `#5A7F75`
- **开发者黑白**：`#000000` / `#FFFFFF` / 1 个 accent（如 `#FF5733`）
- **消费鲜亮**：`#FF6B6B` / `#4ECDC4` / `#FFE66D` + 真实照片
- **企业金色**：`#1A1A2E` + `#C9A961` + 白色

---

## 6. 实施清单

mockup 前必查：

- [ ] 选了 2-3 个参考品牌（不是 1 个）
- [ ] microcopy 人格化（不是 Get Started）
- [ ] 留白不规则（5-10% 偏移）
- [ ] 至少 1 处衬线点缀（标题 / 数字 / 引用）
- [ ] 真实素材（不用 stock / 不用 emoji）
- [ ] 1 个 primary CTA / 屏
- [ ] 错误信息有温度
- [ ] 字体与领域契合

**核心哲学**："好的设计一眼像人做的，不是一眼像 X 品牌做的。"
