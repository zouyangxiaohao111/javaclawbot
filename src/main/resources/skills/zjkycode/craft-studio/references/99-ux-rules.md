# 99 UX 准则（精简版）

> 精简自 ui-ux-pro-max 10 优先级矩阵。每条规则：**check（必须做到）+ avoid（常见错误）+ 1 句反 AI 味注解**。反 AI 味相关：见 `anti-ai-patterns.md`。

---

## 1. 可访问性（CRITICAL - 10 条）

### 1.1 color-contrast
- ✅ Normal text 4.5:1，large text 3:1
- ❌ Gray-on-gray 看起来"高级感"（**典型 AI 味**）
- 📋 Lighthouse / axe DevTools

### 1.2 focus-states
- ✅ 可见 focus ring（2-4px，≥3:1 对比度）
- ❌ 移除 focus ring（"AI 经常偷懒"）
- 📋 Tab 键遍历整页

### 1.3 alt-text
- ✅ 描述性 alt（"登录表单截图"）
- ❌ Alt = "image" / "img1" / 留空
- 📋 屏幕阅读器测试（NVDA / VoiceOver）

### 1.4 aria-labels
- ✅ 图标按钮有 aria-label
- ❌ Icon-only 按钮无标签（**AI 味陷阱**）
- 📋 VoiceOver / NVDA 朗读测试

### 1.5 keyboard-nav
- ✅ Tab 顺序匹配视觉顺序
- ❌ 鼠标陷阱 / focus 跳来跳去
- 📋 拔鼠标走完所有流程

### 1.6 semantic-html
- ✅ button / nav / main / aside 用对标签
- ❌ div + onClick 模拟按钮
- 📋 Lighthouse SEO 审计

### 1.7 color-independence
- ✅ 错误状态不仅靠红色（也用 icon + 文字）
- ❌ 纯红绿配色（色盲不可达）
- 📋 色盲模拟器（Stark / Sim Daltonism）

### 1.8 text-resize
- ✅ 200% 缩放不破坏布局
- ❌ 固定 px font-size + overflow:hidden
- 📋 浏览器 zoom 200% 测试

### 1.9 motion-reduce
- ✅ 支持 `prefers-reduced-motion`
- ❌ 大动画无 fallback（**AI 容易堆动效**）
- 📋 系统设置 → 减弱动态效果

### 1.10 skip-links
- ✅ "跳到主内容"链接
- ❌ 键盘用户必须 Tab 30 次
- 📋 Tab 键首项测试

---

## 2. 触摸（CRITICAL - 10 条）

### 2.1 touch-target-size
- ✅ 触摸目标 ≥ 44×44px（Apple HIG / Material 48dp）
- ❌ 32px 按钮密集排列
- 📋 移动设备实操

### 2.2 touch-spacing
- ✅ 目标间距 ≥ 8px
- ❌ 按钮紧贴（**AI 味：紧凑 = 高端**）
- 📋 拇指点击热区图

### 2.3 swipe-gestures
- ✅ 滑动操作有视觉反馈
- ❌ 滑动删除无确认（破坏性操作）
- 📋 移动端 + 桌面鼠标拖动

### 2.4 hover-vs-tap
- ✅ 桌面 hover 信息，触摸长按展示
- ❌ 关键信息仅 hover 可见
- 📋 触摸设备测试

### 2.5 pull-to-refresh
- ✅ 列表可下拉刷新
- ❌ 移动端无刷新机制
- 📋 移动浏览器测试

### 2.6 form-keyboard
- ✅ 输入框匹配 inputmode（email/numeric）
- ❌ text 类型 + 用户手动切键盘
- 📋 移动端键盘弹出测试

### 2.7 sticky-headers
- ✅ 滚动时 nav 不挡内容
- ❌ nav 跟随但遮挡
- 📋 滚动到顶/底测试

### 2.8 pinch-zoom
- ✅ 允许图片/图表缩放
- ❌ viewport 锁死 + user-scalable=no
- 📋 移动浏览器捏合测试

### 2.9 long-press
- ✅ 长按有 context menu / drag
- ❌ 长按只触发 hover
- 📋 移动端长按测试

### 2.10 touch-feedback
- ✅ 点击瞬间视觉反馈（ripple / scale）
- ❌ 无反馈（用户不知是否点到）
- 📋 触觉 + 视觉双反馈

---

## 3. 表现（HIGH - 10 条）

### 3.1 lcp
- ✅ Largest Contentful Paint < 2.5s
- ❌ Hero 加载 > 5s（**AI 经常堆大图**）
- 📋 WebPageTest / Lighthouse

### 3.2 inp
- ✅ Interaction to Next Paint < 200ms
- ❌ 按钮点击 500ms 才响应
- 📋 Chrome DevTools Performance

### 3.3 cls
- ✅ Cumulative Layout Shift < 0.1
- ❌ 图片/字体加载后跳动
- 📋 Lighthouse / Web Vitals

### 3.4 bundle-size
- ✅ 初始 JS < 200KB（gzip）
- ❌ 一个 React bundle 1.5MB
- 📋 Bundlephobia / Webpack Analyzer

### 3.5 image-format
- ✅ WebP / AVIF + srcset
- ❌ 5MB PNG 嵌 hero
- 📋 Squoosh / Sharp

### 3.6 font-loading
- ✅ font-display: swap + preload
- ❌ FOIT（文字不可见 1s+）
- 📋 Lighthouse font audit

### 3.7 lazy-load
- ✅ Below-fold 图片 lazy load
- ❌ 全部图片 eager
- 📋 Network 面板

### 3.8 critical-css
- ✅ 首屏 CSS 内联
- ❌ 阻塞 render 的全局 CSS
- 📋 Critical CSS 工具

### 3.9 third-party
- ✅ 第三方脚本 < 5 个 / 异步
- ❌ 同步加载 12 个跟踪脚本
- 📋 Performance Observer

### 3.10 render-blocking
- ✅ JS defer / async
- ❌ head 末尾 12 个 `<script>` 同步
- 📋 Lighthouse "Eliminate render-blocking"

---

## 4. 风格（MEDIUM - 10 条）

### 4.1 color-count
- ✅ 1 屏 ≤ 4-6 种主色
- ❌ 12 种 accent 色随机点
- 📋 色板清单

### 4.2 dark-mode
- ✅ Light + dark 同时设计
- ❌ 只做 light（**AI 味**）
- 📋 系统主题切换测试

### 4.3 shadow-lift
- ✅ 阴影仅用于"漂浮"元素
- ❌ 所有卡片都加 shadow
- 📋 视觉密度检查

### 4.4 hairline-borders
- ✅ 1px border 替代 shadow 分隔
- ❌ shadow + border 双重
- 📋 1px border 与 shadow 选一

### 4.5 brand-consistency
- ✅ accent 色与品牌强相关
- ❌ 蓝色 SaaS 默认
- 📋 与领域契合度检查

### 4.6 contrast-hierarchy
- ✅ 3 级灰度（ink / mute / faint）
- ❌ 8 种相近灰度难分主次
- 📋 灰阶对比表

### 4.7 accent-purpose
- ✅ accent 色仅 1 个 primary CTA
- ❌ 3+ 蓝色按钮抢戏
- 📋 一屏 CTA 计数

### 4.8 state-visibility
- ✅ hover / active / disabled / loading 都有态
- ❌ 只有 default 态
- 📋 鼠标移动 + DevTools

### 4.9 microcopy-tone
- ✅ "Let's go" / "Send it" 人格化
- ❌ "Get Started" / "Submit" 模板
- 📋 见 `human-voice-guide.md`

### 4.10 error-tone
- ✅ 错误用 "Hmm, that didn't work"
- ❌ "Error 500" / "操作失败" 死板
- 📋 空态 / 错误态语气

---

## 5. 布局（MEDIUM - 10 条）

### 5.1 visual-hierarchy
- ✅ 1 个 primary CTA / 屏
- ❌ 3+ CTA 抢戏（**AI 味**）
- 📋 一屏 CTA 计数

### 5.2 grid-alignment
- ✅ 8px grid 基础
- ❌ 13px / 17px 混乱
- 📋 Figma 标尺

### 5.3 white-space-purpose
- ✅ 留白分隔 section
- ❌ 32px+ padding 填充无内容
- 📋 留白节奏

### 5.4 responsive-collapse
- ✅ mobile 4-up → 2-up → 1-up
- ❌ 桌面 4-up 移动硬塞
- 📋 移动浏览器

### 5.5 max-width
- ✅ 阅读宽度 65-75 字符
- ❌ 全宽 1920px 文字
- 📋 行长度测试

### 5.6 asymmetry
- ✅ hero 偏左 / 偏右 4-8px
- ❌ 完美居中（**AI 味：完美 = 工业感**）
- 📋 视觉"人放"检查

### 5.7 z-stacking
- ✅ 最多 3 层 z-index
- ❌ 模态/下拉/弹窗 z-index 大战
- 📋 DevTools Layers

### 5.8 fold-content
- ✅ 首屏可见核心价值
- ❌ 价值主张藏在第 3 屏
- 📋 1024×768 viewport

### 5.9 section-rhythm
- ✅ section 间距 64-96px
- ❌ section 间距 24px 拥挤
- 📋 视觉呼吸

### 5.10 sidebar-vs-tabs
- ✅ 复杂层级用 sidebar；2-5 项用 tabs
- ❌ 3 项用 sidebar 浪费
- 📋 信息架构测试

---

## 6. 字体（MEDIUM - 10 条）

### 6.1 font-pairing
- ✅ 衬线标题 + sans body
- ❌ 全 Inter（**AI 味**）
- 📋 见 `human-voice-guide.md` §3

### 6.2 type-scale
- ✅ Modular scale（1.2 / 1.25 / 1.333）
- ❌ 字号 11/13/15/17/19 任意
- 📋 Type Scale tool

### 6.3 line-height
- ✅ Body 1.5-1.7，display 1.05-1.2
- ❌ 全 1.4 死板
- 📋 行间距测试

### 6.4 letter-spacing
- ✅ Display 用负 tracking（-1.4px / 56px）
- ❌ 全 0
- 📋 Typography 检查

### 6.5 font-weight
- ✅ Display thin (300) + body regular (400)
- ❌ 全部 bold
- 📋 视觉重量

### 6.6 font-features
- ✅ tnum 用于金额 / 计数
- ❌ 普通比例数字
- 📋 OpenType features

### 6.7 fallback
- ✅ Inter 不可用 → system-ui
- ❌ 一个字体加载失败整页崩溃
- 📋 font-display: swap

### 6.8 line-length
- ✅ 65-75 字符（35-50 汉字）
- ❌ 整段 200 字符
- 📋 标尺工具

### 6.9 font-amount
- ✅ 1-2 个字体家族
- ❌ 4 个字体家族
- 📋 字体清单

### 6.10 micro-type
- ✅ 数字用 oldstyle-nums
- ❌ 全 lining-nums
- 📋 `font-variant-numeric`

---

## 7. 动画（MEDIUM - 10 条）

### 7.1 duration
- ✅ 微交互 150-300ms，过渡 300-500ms
- ❌ 1s+ 让人等
- 📋 DevTools Performance

### 7.2 easing
- ✅ 缓出 ease-out 进入；缓入 ease-in 退出
- ❌ 全 linear 机械
- 📋 cubic-bezier 测试

### 7.3 spring-physics
- ✅ spring (stiffness/damping) 自然
- ❌ ease-in-out 死板（**AI 味**）
- 📋 framer-motion / motion.dev

### 7.4 stagger
- ✅ 列表项 30-50ms stagger
- ❌ 同时淡入无层次
- 📋 视觉节奏

### 7.5 reduced-motion
- ✅ 尊重 `prefers-reduced-motion`
- ❌ 大动画无 fallback
- 📋 系统设置

### 7.6 hover-state
- ✅ hover 有颜色/阴影/缩放变化
- ❌ 按钮 hover 无反应
- 📋 鼠标移动

### 7.7 focus-state
- ✅ focus 有 transition
- ❌ focus 瞬间出现
- 📋 Tab 键测试

### 7.8 loading-state
- ✅ skeleton / spinner / progress
- ❌ 空白屏等
- 📋 慢网络测试

### 7.9 state-transitions
- ✅ 状态切换有动画过渡
- ❌ 模态瞬间 pop
- 📋 状态切换流畅度

### 7.10 scroll-triggered
- ✅ 滚动到视口才触发
- ❌ 全部同时动
- 📋 IntersectionObserver

---

## 8. 表单（MEDIUM - 10 条）

### 8.1 labels
- ✅ label 始终可见（不只用 placeholder）
- ❌ 仅 placeholder（**AI 味陷阱**）
- 📋 输入框测试

### 8.2 required-mark
- ✅ 必填项明确标记
- ❌ 用户提交后才知道
- 📋 视觉扫描

### 8.3 inline-validation
- ✅ 失焦后验证，不在输入中报错
- ❌ 输入每个字符就报错
- 📋 体验流程

### 8.4 error-specificity
- ✅ "邮箱格式不正确" 而非 "输入有误"
- ❌ 通用错误
- 📋 错误信息清单

### 8.5 autocomplete
- ✅ name / email / tel 用 autocomplete
- ❌ 全 text + 用户重输
- 📋 Chrome autofill 测试

### 8.6 password-toggle
- ✅ 密码可见切换
- ❌ 密码只能盲打
- 📋 移动端体验

### 8.7 submit-feedback
- ✅ 提交后明确反馈（loading / success）
- ❌ 按钮不变（用户重复点）
- 📋 慢网络测试

### 8.8 unsaved-warning
- ✅ 离开未保存提示
- ❌ 数据丢失无提示
- 📋 路由切换测试

### 8.9 fieldset-organization
- ✅ 相关字段分组（fieldset / legend）
- ❌ 7 个字段平铺
- 📋 视觉扫描

### 8.10 progress-indicator
- ✅ 多步表单显示进度
- ❌ 不知道走到第几步
- 📋 多步表单测试

---

## 9. 导航（MEDIUM - 10 条）

### 9.1 breadcrumbs
- ✅ 深层路径有面包屑
- ❌ 用户迷路
- 📋 3 层路径测试

### 9.2 active-state
- ✅ 当前页 nav 项高亮
- ❌ 用户不知在哪
- 📋 视觉扫描

### 9.3 back-button
- ✅ 浏览器返回工作
- ❌ SPA 拦截返回
- 📋 浏览器返回

### 9.4 deep-link
- ✅ URL 可分享/可收藏
- ❌ 仅内存状态
- 📋 URL 检查

### 9.5 search
- ✅ 复杂内容有搜索
- ❌ 50 项列表无搜索
- 📋 内容规模测试

### 9.6 filter-vs-sort
- ✅ 列表提供过滤和排序
- ❌ 用户只能滚动
- 📋 列表长度测试

### 9.7 pagination-vs-infinite
- ✅ SEO 内容分页；feed 用 infinite
- ❌ SEO 页面 infinite scroll
- 📋 内容类型区分

### 9.8 404
- ✅ 404 有用提示 + 返回首页
- ❌ 404 死页
- 📋 错误路径测试

### 9.9 sitemap-footer
- ✅ 关键页面在 footer
- ❌ 关键路径只在 nav
- 📋 重要页面可达性

### 9.10 skip-nav
- ✅ 跳过 nav 直达主内容
- ❌ 键盘用户 Tab 30+ 次
- 📋 Tab 测试

---

## 10. 图表（LOW - 9 条）

### 10.1 chart-type-match
- ✅ 比较用柱状；趋势用折线；占比用饼/堆叠
- ❌ 全饼图（**AI 味**）
- 📋 数据类型检查

### 10.2 axis-labels
- ✅ 坐标轴有标签 + 单位
- ❌ 仅数字无说明
- 📋 视觉扫描

### 10.3 legend
- ✅ 图例对应颜色
- ❌ 颜色无对应说明
- 📋 颜色对比

### 10.4 data-density
- ✅ 数据点有足够对比
- ❌ 10 条线挤一起
- 📋 视觉清晰度

### 10.5 tooltip
- ✅ hover 显示精确值
- ❌ 只能估读
- 📋 鼠标 hover

### 10.6 empty-state
- ✅ 无数据有占位 + 说明
- ❌ 空白图表
- 📋 0 数据测试

### 10.7 loading-skeleton
- ✅ 图表加载用 skeleton
- ❌ 跳变从无到全
- 📋 慢网络

### 10.8 color-blind
- ✅ 区分不用仅靠颜色（也用形状/线型）
- ❌ 红绿配色（色盲不可读）
- 📋 模拟器

### 10.9 mobile-responsive
- ✅ 移动端图表可读
- ❌ 4K 图表挤进 360px
- 📋 移动设备

---

## 优先级矩阵速查

| 类别 | 优先级 | 条数 | 违反成本 |
|------|-------|-----|---------|
| 可访问性 | CRITICAL | 10 | 法律 / 用户排除 |
| 触摸 | CRITICAL | 10 | 移动端不可用 |
| 表现 | HIGH | 10 | 用户流失 |
| 风格 | MEDIUM | 10 | 视觉/品牌问题 |
| 布局 | MEDIUM | 10 | 信息架构 |
| 字体 | MEDIUM | 10 | 阅读体验 |
| 动画 | MEDIUM | 10 | 流畅度 |
| 表单 | MEDIUM | 10 | 转化率 |
| 导航 | MEDIUM | 10 | 可发现性 |
| 图表 | LOW | 9 | 数据理解 |

**反 AI 味注解已嵌入每条规则的 ❌ 中。** 详细反 AI 味模式见 `anti-ai-patterns.md`。
