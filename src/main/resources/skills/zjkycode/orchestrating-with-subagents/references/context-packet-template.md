# Context Packet Template

派 subagent 时**必须**用此模板的 11 字段。下面是完整模板 + 每个字段的填写规范。

## 完整模板

```markdown
# Subagent Task Brief: [任务 ID]

## 任务目标（Goal）
[一句话：现在要做什么。]

## 当前阶段（Current Phase）
[属于 task_plan.md 的哪个 Phase？已完成哪些任务？引用 plan/task_plan.md]

## 范围（Scope）

### 必须做
- [ ] 任务 A
- [ ] 任务 B

### 不要做
- ❌ 不要修改 [列出禁改文件]
- ❌ 不要重构 [列出禁改代码]
- ❌ 不要添加未在范围内的功能

## 成功标准（Done When）
- [ ] 标准 1（可验证：mvn test 跑 X 测试通过）
- [ ] 标准 2（可验证：文件 Y 存在且内容符合规格）
- [ ] 标准 3（可验证：mvn compile 无 error/warning）

## 相关文件（Required Reading）
- `path/to/file1.java` — 为什么相关：[注释]
- `path/to/file2.md` — 为什么相关：[注释]
- `path/to/contract.md` — 上下游契约：[注释]

## 依赖项（Dependencies）
- 依赖前一个 subagent 的输出：`findings/<prev-task>.md`
- 依赖用户决策：[列出待决问题]
- 依赖外部资源：[如 API 文档 URL]

## 约束（Constraints）
- 语言：Java 17
- 框架：JavaFX + SLF4J + Maven
- 风格：项目 CLAUDE.md 规则
- 必读规范：
  - `src/main/java/utils/` — 项目工具类
  - 编码规范：SLF4J 必加数据流日志（参考经验.md）
- 禁改文件：`pom.xml`、`docs/CHANGELOG.md`

## 返回契约（Return Contract）
完成后**必须**写：
1. `findings/<task-id>.md` — 你的发现、决策、关键代码片段
2. `progress/<task-id>.md` — 执行日志（命令、错误、修复）
3. 更新 `plan/task_plan.md` 中对应任务的状态

如果失败，写：
- `findings/<task-id>-failed.md` — 失败原因 + 已尝试方案

报告状态（四选一）：
- **DONE** — 全部完成
- **DONE_WITH_CONCERNS** — 完成但有疑虑（列出疑虑）
- **NEEDS_CONTEXT** — 需要更多信息（说明需要什么）
- **BLOCKED** — 无法完成（说明阻塞原因）

## 验证命令（Verification）
完成后跑这些命令证明做对了：
- `mvn compile` — 必须通过
- `mvn test -Dtest=<新功能测试>` — 必须通过
- `mvn package -DskipTests` — 必须成功（可选）

## 反模式（Anti-Patterns）
- ❌ 不要碰 `src/main/java/utils/` 下的文件
- ❌ 不要修改 `pom.xml` 的依赖
- ❌ 不要跳过 logging（项目必须用 SLF4J）
- ❌ 不要用 `System.out.println`（必须用 SLF4J）
- ❌ 不要添加未在范围内的功能
- ❌ 不要"顺手优化"相邻代码
- ❌ 不要在没看到测试前写实现

## 触发相关 skill
作为 subagent，你应该**自己调用**这些 skill：
- `test-driven-development` — 实施前必读
- `verification-before-completion` — 完成后必跑
- 其他根据任务类别
```

## 字段使用规则

| 字段 | 必填 | 填写规范 |
|---|---|---|
| 任务目标 | 必填 | 一句话，不超过 50 字 |
| 当前阶段 | 必填 | 引用 task_plan.md 中的具体行号 |
| 范围（必须做/不要做）| 必填 | 必须做 ≥ 1 条；不要做 ≥ 1 条 |
| 成功标准 | 必填 | ≥ 3 条，每条必须可验证 |
| 相关文件 | 必填 | 数量 ≥ 1 |
| 依赖项 | 有依赖时 | 独立任务时省略 |
| 约束 | 必填 | 简单项目可省略细节 |
| 返回契约 | 必填 | 必写 findings/ + progress/ |
| 验证命令 | 必填 | 至少 1 个 mvn 命令 |
| 反模式 | 有已知陷阱时 | 无陷阱时省略 |
| 触发相关 skill | 任务有特定 skill 时 | 通用任务时省略 |

## 示例：填写良好的 context packet

（完整示例见 `examples/example-1-new-feature.md`）
