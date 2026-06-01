# Example 2: Human Voice 迭代

> 场景：把 "Get Started" 改为人格化版本，验证语气变化

---

## 场景

**产品**：FormFlow — 表单构建工具
**用户**：市场营销 / 运营人员
**问题**：v1 用了模板化 microcopy，团队反馈"太冷、太 SaaS"

---

## 迭代过程

### v1：模板化（AI 味）

```html
<div class="hero">
  <h1>Build Better Forms</h1>
  <p>Create, share, and analyze forms in minutes.</p>
  <button>Get Started</button>
  <a>Learn More</a>
</div>
```

**问题**：
- ❌ "Get Started" 模板化
- ❌ "Learn More" 模板化
- ❌ "Build Better Forms" 通用口号
- ❌ CTA 文案不一致（一个有 → 一个无）

**反 AI 味检查**：
```
[ ] microcopy 人格化 — ❌
[ ] 1 个 primary CTA — ❌（2 个 CTA）
[ ] 不是通用口号 — ❌
```

### v2：人格化（首次修正）

```html
<div class="hero">
  <h1>Forms people <em>actually</em> finish.</h1>
  <p>Less friction. Better data. Happier respondents.</p>
  <button>Let's go →</button>
  <a>Show me how</a>
</div>
```

**改进**：
- ✅ "Let's go" 替代 "Get Started"
- ✅ "Show me how" 替代 "Learn More"
- ✅ 标题用 "actually" 加斜体强调（衬线点缀）
- ✅ "Forms people actually finish" 更有画面感

**反 AI 味检查**：
```
[✓] microcopy 人格化
[ ] 1 个 primary CTA — ❌（仍是 2 个）
[✓] 不是通用口号
```

### v3：减少 CTA（用户反馈后）

```html
<div class="hero">
  <h1>Forms people <em>actually</em> finish.</h1>
  <p>Less friction. Better data. Happier respondents.</p>
  <button>Let's go →</button>
  <small>No credit card. No setup. Just a form.</small>
</div>
```

**进一步改进**：
- ✅ 只 1 个 primary CTA（删 "Show me how"）
- ✅ 加 small 文本："No credit card. No setup. Just a form."（人格化 + 实用信息）

**反 AI 味检查**：
```
[✓] microcopy 人格化
[✓] 1 个 primary CTA
[✓] 不是通用口号
[✓] 衬线点缀
```

### v4：错误信息 + 成功反馈

```html
<!-- 错误信息 -->
<div class="error">
  <p><strong>Hmm, that email looks off.</strong> Double-check the @ sign?</p>
</div>

<!-- 成功反馈 -->
<div class="success">
  <p><strong>Form's live.</strong> Here's the link to share.</p>
</div>
```

**对比**：

| ❌ 死板 | ✅ 人文 |
|--------|--------|
| "Error: Invalid email" | "Hmm, that email looks off. Double-check the @ sign?" |
| "Form published successfully" | "Form's live. Here's the link to share." |

---

## microcopy 库（v3 提取）

```yaml
microcopy_library:
  primary_cta: "Let's go →"
  form_empty: "Nothing here yet. Add your first question?"
  form_saved: "Kept it. Drafts are safe with us."
  form_shared: "Sent! Recipients will get a friendly email."
  error_email: "Hmm, that email looks off. Double-check the @ sign?"
  error_required: "We need this one to keep going."
  success_published: "Form's live. Here's the link to share."
  cancel: "Never mind"
  delete_confirm: "Remove this form? It's not reversible."
  back: "← Back"
```

**反例（避免）**：
- "Get Started" / "Learn More" / "Sign Up"
- "Submit" / "Save" / "Cancel"（单独用）
- "Error" / "Success"（无温度）
- "Required field"（机械）

---

## 关键学习

1. **microcopy 是"人味"的第一关** — 比 token / 配色更明显
2. **删除比添加更有效** — 删 1 个 CTA 比加 5 个装饰更"人"
3. **承认错误也有温度** — "Hmm" / "looks off" 比 "Error" 更亲切
4. **小字也重要** — "No credit card. No setup. Just a form." 是真正的"反 AI 味"

---

## 测试方法

- 找 5 个用户做 A/B：v1 vs v3
- 测量"感觉像人做"评分（1-5）
- 预期：v3 评分显著高于 v1（≥ 1 分）
- 测量 CTA 点击率：v3 应该更高（1 个 CTA 比 2 个更聚焦）
