# 决策矩阵（4 维）

> craft-studio 的核心决策表。回答 4 个问题，决定 mockup 路径。
> 配套：`brand-craft-notes.md`（选品牌）、`main-loop-pseudocode.md`（执行）

---

## 4 维度

### 1. Category（问题类别）

| 值 | 含义 | 输出形式 |
|------|------|---------|
| `design` | UI/视觉设计 | mockup 必备 |
| `architecture` | 架构图 | 流程图 / 节点图 |
| `workflow` | 流程设计 | 步骤图 / 状态机 |

### 2. Complexity（复杂度）

| 值 | 含义 | 典型 |
|------|------|------|
| `trivial` | 1 个组件 / 1 个 token | 单按钮配色 |
| `simple` | 1-3 个组件 / 1 个 section | 卡片样式 |
| `complex` | 完整页面 / 多个 section | landing page |
| `multi-system` | 跨产品 / 跨平台 | 设计系统 |

### 3. Brand_Need（品牌参考需求）

| 值 | 含义 | 行为 |
|------|------|------|
| `none` | 不依赖品牌 | 直接设计 |
| `reference` | 参考 1 个品牌的 token | 选 1 个标杆 + 标准 mockup |
| `inspired` | 参考 2-3 个品牌的"反 AI 味决策" | **决策融合** |
| `from-scratch` | 从零原创 | 决策融合 + 重度人格化 |

### 4. Human_Voice_Need（人文语气需求）

| 值 | 含义 | 适用 |
|------|------|------|
| `low` | 标准 SaaS 语气 | B2B 后台 / 工具 |
| `medium` | 轻度人格化 | 消费 SaaS / 协作工具 |
| `high` | 重度人格化 | 消费品 / 儿童 / 创意 / 品牌 |

---

## 决策表

| Category | Complexity | Brand_Need | Human_Voice | → 路径 |
|----------|-----------|-----------|-------------|--------|
| design | trivial | none | low | 终端文字 |
| design | trivial | reference | low | 1 品牌 + 标准 |
| design | simple | reference | low | 1 品牌 + 标准 |
| design | simple | inspired | medium | 2 品牌 + 微人格化 |
| design | simple | from-scratch | high | 2-3 品牌 + 重度人格化 |
| design | complex | reference | low | 1 品牌 + 标准（深度） |
| design | complex | inspired | medium | **2-3 品牌 + 中度人格化** |
| design | complex | from-scratch | high | **决策融合 + 重度人格化** |
| design | multi-system | inspired | high | 决策融合 + 设计系统 |
| architecture | simple | none | low | 终端流程图 |
| architecture | complex | none | low | 浏览器 + 流程图 |
| architecture | complex | reference | medium | 1 品牌风格流程图 |
| workflow | simple | reference | medium | 1 品牌 + 步骤图 |
| workflow | complex | inspired | high | 2 品牌 + 重度人格化步骤图 |
| workflow | complex | from-scratch | high | 决策融合 + 完整 4 机制 |

**粗体行** = craft-studio 核心路径（"反 AI 味"价值最高）。

---

## 决策流程（6 步）

```
1. 确定 Category（设计 / 架构 / 流程？）
   ↓
2. 评估 Complexity（trivial / simple / complex？）
   ↓
3. 回答 Brand_Need（none / reference / inspired / from-scratch？）
   ↓
4. 回答 Human_Voice_Need（low / medium / high？）
   ↓
5. 查表 → 得到路径
   ↓
6. 执行路径
```

---

## 路径含义

### 路径 1：终端文字
- 直接输出文字描述
- 不启动浏览器
- 适用于：trivial 任务 / 概念问题

### 路径 2：1 品牌 + 标准
- 选 1 个标杆 + 标准 mockup
- 不做"决策融合"
- 适用于：B2B 内部工具 / 后台

### 路径 3：2 品牌 + 微人格化
- 融合 2 个品牌 + 1-2 个 microcopy 替换
- 适用于：消费 SaaS（Notion / Linear 风格）

### 路径 4：2-3 品牌 + 中度人格化 ⭐
- 决策融合 + 5-10 个 microcopy 替换
- 适用于：landing page / marketing

### 路径 5：决策融合 + 重度人格化 ⭐
- 完整 4 机制 + 完整 microcopy 库
- 适用于：消费品牌 / 创意工具

### 路径 6：决策融合 + 设计系统
- 跨产品 / 跨平台一致性
- 适用于：design system 文档

---

## Iron Law（强制约束）

无论哪条路径，**这 4 个不允许**：

1. ❌ 不允许"Get Started / Learn More" 等模板化 microcopy
2. ❌ 不允许"全用 X 品牌"贴牌
3. ❌ 不允许"3+ 个 CTA 在同一屏"
4. ❌ 不允许"用 emoji 当图标"

**违反任意 1 条** = 自动重做。

---

## 实际示例

### 示例 1：landing page
- Category: `design`
- Complexity: `complex`
- Brand_Need: `inspired`
- Human_Voice: `medium`
- → **2-3 品牌 + 中度人格化**（决策融合核心路径）

### 示例 2：内部 dashboard
- Category: `design`
- Complexity: `complex`
- Brand_Need: `reference`
- Human_Voice: `low`
- → 1 品牌 + 标准（深度）

### 示例 3：架构图
- Category: `architecture`
- Complexity: `complex`
- Brand_Need: `none`
- Human_Voice: `low`
- → 浏览器 + 流程图

### 示例 4：消费 App
- Category: `design`
- Complexity: `complex`
- Brand_Need: `from-scratch`
- Human_Voice: `high`
- → **决策融合 + 重度人格化**（品牌原创）

### 示例 5：单按钮配色
- Category: `design`
- Complexity: `trivial`
- Brand_Need: `reference`
- Human_Voice: `low`
- → 1 品牌 + 标准

---

## 选品牌的辅助

如果 `Brand_Need = inspired` 或 `from-scratch`，需要选 2-3 个参考品牌：

1. 读 `brand-craft-notes.md` §"按产品类型推荐"
2. 选 2-3 个候选（**必须 ≥ 2**，违反 = 贴牌）
3. 读每个候选的 `design-md/{brand}/DESIGN.md` 的 `craft_notes`
4. 抽取每个的 `anti_ai_decision` 和 `signature_moves`
5. 融合为产品专属的 `craft_principles`

**详见**：`examples/example-4-brand-fusion.md`
