# 6 个测试场景

> craft-studio v1 验收测试。每个场景独立可执行。

---

## 场景 1：基础 mockup

**目标**：验证浏览器服务可用，写 3 个布局选项

**步骤**：
1. 启动 server：`scripts/start-server.sh --project-dir /tmp/test-craft`
2. 决策矩阵：Category=design, Complexity=simple, Brand_Need=reference, Human_Voice=low
3. 选 1 个品牌（`notion`）
4. 写 `platform.html`（含 3 个 layout option）
5. 验证浏览器显示（curl `http://localhost:PORT/`）
6. 验证事件记录（`$STATE_DIR/events` 包含 `page-loaded`）

**预期**：
- URL 200 OK
- 3 个 options 可见
- `events` 含 `page-loaded` 和 `option-clicked` 槽位
- 文件名 `platform.html` 在 `screen_dir`

**验证**：
```bash
# 验证 server
curl -s http://localhost:52341/ | grep -q "platform" && echo OK

# 验证文件
ls $SCREEN_DIR/platform.html && echo OK

# 验证事件
cat $STATE_DIR/events | grep "page-loaded" && echo OK
```

---

## 场景 2：决策融合（核心）

**目标**：验证 2-3 品牌融合生成 mockup 不"贴牌"

**步骤**：
1. 启动 server
2. 决策矩阵：Category=design, Complexity=complex, Brand_Need=inspired, Human_Voice=medium
3. 选 3 个品牌：`stripe` + `linear.app` + `notion`
4. 读 `design-md/stripe/DESIGN.md` 的 `craft_notes` → 抽取"技术诚实"
5. 读 `design-md/linear.app/DESIGN.md` 的 `craft_notes` → 抽取"物理感"
6. 读 `design-md/notion/DESIGN.md` 的 `craft_notes` → 抽取"温暖"
7. 融合为产品专属 `craft_principles`（YAML 或 markdown）
8. 写 `mockup.html`

**预期**：
- mockup 不"贴牌"任何单一品牌
- 验证：5 个用户看 mockup，问"这是哪个品牌" → 答错或答不出 = 通过

**反贴牌检查**：
```
[✓] 不像 Stripe（没有深蓝 + ruby + 渐变 mesh）
[✓] 不像 Linear（没有冷调 + 黑紫对比）
[✓] 不像 Notion（没有 emoji + 3D hero）
[✓] 像产品（融合 3 个精华的专属风格）
```

---

## 场景 3：microcopy 迭代

**目标**：验证 microcopy 迭代从模板化到人格化的语气变化

**步骤**：
1. 启动 server
2. 写 `voice-v1.html`：`Get Started` / `Learn More` / `Submit` / `Cancel`
3. 截图 / 反馈：v1 评分 2/5（"太冷"）
4. 写 `voice-v2.html`：`Let's go →` / `Show me how` / `Send it` / `Never mind`
5. 反馈：v2 评分 4/5（"像朋友"）
6. 写 `voice-v3.html`：加错误信息人格化（"Hmm, that didn't work"）
7. 反馈：v3 评分 4.5/5（"但还能更暖"）
8. 写 `voice-v4.html`：加成功反馈（"Kept it. Your notes are safe."）
9. 反馈：v4 评分 5/5

**预期**：
- v1 → v2 评分显著上升（≥ 1.5 分）
- v2 → v4 评分持续上升
- 4 个版本文件名递增（`voice-v1.html` → `voice-v4.html`）

**反 AI 味检查**：
```
v1: [ ] microcopy 人格化
v2: [✓] microcopy 人格化
v3: [✓] microcopy 人格化 + 错误信息
v4: [✓] microcopy 人格化 + 错误信息 + 成功反馈
```

---

## 场景 4：品牌切换

**目标**：验证 craft_notes 注解在品牌切换时生效

**步骤**：
1. 启动 server
2. 决策矩阵：Brand_Need=reference
3. 选 `stripe` → 写 `mockup-stripe.html`
4. 用户反馈后，切换到 `linear.app` → 写 `mockup-linear.html`
5. 对比 2 个 mockup

**预期**：
- `mockup-stripe.html` 体现 Stripe 风格（深蓝 + ruby + tabular + 工程师语气）
- `mockup-linear.html` 体现 Linear 风格（紫 + 黑 + spring + 冷静语气）
- 视觉风格明显不同
- microcopy 语气不同

**风格对比清单**：
| 元素 | stripe | linear.app |
|------|--------|-----------|
| 主色 | #533afd 紫蓝 | #5E6AD2 紫色 |
| 背景 | 米色 cream | 黑/白 |
| 动画 | fade 短 | spring 320/30 |
| microcopy | "Get webhook" | "Set up" |
| 数字 | tabular | normal |
| 整体感 | 金融严谨 | issue tracker 冷静 |

---

## 场景 5：浏览器失败 fallback

**目标**：验证终端 fallback 当浏览器服务不可用

**步骤**：
1. 启动 server
2. 写 `mockup.html`
3. 手动 kill 进程：`pkill -f server.cjs`（或 `taskkill /F /IM node.exe` on Windows）
4. agent 检测到 server 死亡
5. 切换到终端 ASCII mockup
6. 通知用户："浏览器不可用，用终端文字 + ASCII 描述"

**预期**：
- 用户得到 ASCII mockup，不卡死
- 终端显示 `Mockup: Dashboard with 3 cards...`
- 不抛 panic / 不卡 30s 等超时

**fallback 模板**：
```
⚠️ Browser server unavailable. Falling back to terminal description.

[Mockup: Dashboard]
+----------------------------------+
| Logo  Nav  Nav  Nav   [Profile]  |
+----------------------------------+
| Hero: Big title + 1 CTA         |
+----------------------------------+
| Card 1 | Card 2 | Card 3        |
+----------------------------------+

Description: ... (full text description)
```

---

## 场景 6：跨 Tab 同步

**目标**：验证 file lock 在多 Tab 下不冲突

**步骤**：
1. Tab 1 启动 server：`--project-dir /tmp/test-tab1 --port 52341`
2. Tab 2 启动 server：`--project-dir /tmp/test-tab2 --port 52342`
3. Tab 1 写 `mockup.html`
4. Tab 2 写 `mockup.html`（不同目录，**不冲突**）
5. 验证 2 个 server 独立运行

**预期**：
- 2 个 server 在不同端口运行
- 互不干扰
- 跨 Tab 不共享 design-md 选择（每 Tab 独立）
- 跨 Tab 不共享 mockup 文件（不同 `screen_dir`）

**检查**：
```bash
# 验证 2 个端口都活
curl -s http://localhost:52341/ > /dev/null && echo "tab1 OK"
curl -s http://localhost:52342/ > /dev/null && echo "tab2 OK"

# 验证 2 个目录
ls /tmp/test-tab1/.zjkycode/brainstorm/*/content/mockup.html
ls /tmp/test-tab2/.zjkycode/brainstorm/*/content/mockup.html
```

---

## 验收清单（v1 全部 6 场景）

- [ ] 场景 1：基础 mockup
- [ ] 场景 2：决策融合
- [ ] 场景 3：microcopy 迭代
- [ ] 场景 4：品牌切换
- [ ] 场景 5：浏览器失败 fallback
- [ ] 场景 6：跨 Tab 同步

**Iron Law 验证**（所有场景通用）：
- [ ] 无 "Get Started" / "Learn More"
- [ ] 无 1 品牌贴牌
- [ ] 1 屏 ≤ 1 个 primary CTA
- [ ] 无 emoji 作图标

---

## 自动化测试建议（v2）

```bash
# 运行所有 6 场景
bash tests/run-all.sh

# 单独运行
bash tests/run-scenario-1.sh
bash tests/run-scenario-2.sh
# ...
```

**v1 手工测试，v2 自动化。**
