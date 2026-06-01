# Changelog

craft-studio 的版本变更记录。

---

## [v1.0.0] - 2026-06-01

### Added - 全新 craft-studio 技能

craft-studio = visual-companion（71 品牌 + 浏览器服务）+ ui-ux-pro-max（99 UX 准则）+ 新增"人文与情感"反 AI 味指导层。

**核心哲学**："好的设计一眼像人做的，不是一眼像 X 品牌做的。"

### 文件结构

```
skills/zjkycode/craft-studio/
├── SKILL.md                              # 主入口（177 行）
├── references/                           # 7 个 reference 文件
│   ├── 99-ux-rules.md                    # 99 UX 准则（557 行）
│   ├── human-voice-guide.md              # 人文原则（290 行）
│   ├── anti-ai-patterns.md               # 16 反 AI 模式（200 行）
│   ├── decision-matrix.md                # 4 维决策（180 行）
│   ├── brand-craft-notes.md              # 71 品牌总索引（217 行）
│   ├── design-md-index.md                # 快速索引（78 行）
│   └── main-loop-pseudocode.md           # 主循环（185 行）
├── design-md/                            # 71 品牌 DESIGN.md 库
│   ├── stripe/                           # ✅ craft_notes 完整
│   ├── linear.app/                       # ✅ craft_notes 完整
│   ├── apple/                            # ✅ craft_notes 完整
│   ├── notion/                           # ✅ craft_notes 完整
│   ├── vercel/                           # ✅ craft_notes 完整
│   ├── claude/                           # ✅ craft_notes 完整
│   ├── tesla/                            # ✅ craft_notes 完整
│   ├── shopify/                          # ✅ craft_notes 完整
│   ├── figma/                            # ✅ craft_notes 完整
│   ├── airbnb/                           # ✅ craft_notes 完整
│   └── ... (61 个品牌待 v2 模板化)
├── scripts/                              # 7 个浏览器服务脚本
│   ├── server.cjs
│   ├── frame-template.html
│   ├── helper.js
│   ├── start-server.sh / ps1
│   └── stop-server.sh / ps1
├── examples/                             # 4 个示例（726 行总计）
│   ├── example-1-mockup-with-craft.md
│   ├── example-2-human-voice-iteration.md
│   ├── example-3-anti-ai-iteration.md
│   └── example-4-brand-fusion.md
├── tests/                                # 6 个测试场景
│   └── test-scenarios.md                 # 220 行
└── CHANGELOG.md
```

### 关键能力

#### 反 AI 味 4 机制
1. **人文与情感**（Human & Emotion）— microcopy + 真实素材 + 故意不对称
2. **决策融合**（Decision Fusion）— 选 2-3 品牌 + 抽取 craft_notes
3. **约束驱动**（Constraint-Driven）— 1 字体 / 1 圆角 / 1 阴影 / 4-8 色 / 1 CTA
4. **文化与情境**（Cultural Context）— 领域契合 + 字体 + 色调

#### Iron Law（4 不允许）
1. ❌ "Get Started / Learn More / Sign Up" 等模板化 microcopy
2. ❌ 全用 1 个品牌的 token（贴牌）
3. ❌ 3+ 个 CTA 在同一屏
4. ❌ 用 emoji 当图标

#### craft_notes 字段
10 个标杆品牌（`stripe` / `linear.app` / `apple` / `notion` / `vercel` / `claude` / `tesla` / `shopify` / `figma` / `airbnb`）含完整 craft_notes 注解：
- `anti_ai_decision` — 反 AI 味核心决策
- `signature_moves` — 3-5 个标志性手法
- `human_voice` — 品牌语气
- `use_when` / `avoid_when` — 适用 / 避免场景
- `reference_examples` — 参考 URL 和学习点

### Changed - 集成更新

- **brainstorming**：`visual-companion` → `craft-studio`（2 处）
- **using-zjkycode**：`visual-companion` → `craft-studio`（2 处）
- **orchestrating-with-subagents**：`visual-companion` → `craft-studio`（5 个文件）
  - `SKILL.md`
  - `references/decision-matrix.md`
  - `references/integration-map.md`
  - `tests/test-scenarios.md`
  - `examples/example-4-parallel-tasks.md`

### Deprecated

- `visual-companion`（保留为参考，已被 craft-studio 取代）
- `ui-ux-pro-max`（精简为 references/99-ux-rules.md，scripts/data 未下载）

### Technical

- 工作目录：Windows 11 + bash
- 浏览器服务：Node.js（v18+）
- 跨平台启动：bash + PowerShell 双脚本
- 多 Tab 同步：file lock 机制（来自 visual-companion 经验）

---

## 设计文档

完整设计文档：`C:\Users\WIN\.javaclawbot\plans\2026-06-01-craft-studio-design.md`
实施计划：`C:\Users\WIN\.javaclawbot\plans\2026-06-01-craft-studio-impl.md`
