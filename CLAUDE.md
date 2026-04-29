# CLAUDE.md
## 编译命令
```shell
"C:\Program Files\Java\jdk-17\bin\java.exe" -Dmaven.multiModuleProjectDirectory=D:\code\ai_project\javaclawbot -Djansi.passthrough=true -Dmaven.home=D:\IDEA20240307\plugins\maven\lib\maven3 -Dclassworlds.conf=D:\IDEA20240307\plugins\maven\lib\maven3\bin\m2.conf -Dmaven.ext.class.path=D:\IDEA20240307\plugins\maven\lib\maven-event-listener.jar -javaagent:D:\IDEA20240307\lib\idea_rt.jar=53924 -Dfile.encoding=UTF-8 -classpath D:\IDEA20240307\plugins\maven\lib\maven3\boot\plexus-classworlds-2.8.0.jar;D:\IDEA20240307\plugins\maven\lib\maven3\boot\plexus-classworlds.license org.codehaus.classworlds.Launcher -Didea.version=2024.3.7 -Dmaven.repo.local=D:\apps\maven\repository compile

```

1. 编码前先思考

不要假设。不要掩盖困惑。把权衡讲清楚。

在开始实现之前：

明确说明你的假设。如果不确定，就提问。
如果存在多种理解方式，把它们列出来——不要默默选择一个。
如果有更简单的方法，要指出来。在必要时提出异议。
如果有不清楚的地方，先停下来。指出困惑点并提问。
2. 简单优先

用最少的代码解决问题。不做任何臆测性的扩展。

不添加需求之外的功能。
不为一次性代码做抽象。
不添加未被要求的“灵活性”或“可配置性”。
不为不可能发生的情况编写错误处理。
如果你写了 200 行但其实可以用 50 行解决，就重写。

问自己：“资深工程师会觉得这太复杂吗？”如果答案是会，那就简化。

3. 手术式修改

只改必须改的部分。只清理你自己引入的问题。

在修改已有代码时：

不要“顺便优化”相邻的代码、注释或格式。
不要重构没有问题的部分。
保持现有风格一致，即使你有不同偏好。
如果发现无关的死代码，可以指出——但不要删除。

当你的修改引入“孤立项”时：

删除因你的修改而变得未使用的导入/变量/函数。
不要删除原本就存在的死代码，除非被要求。

检验标准：每一行修改都应能直接对应用户需求。

4. 以目标驱动执行

定义成功标准。循环迭代直到验证通过。

将任务转化为可验证的目标：

“添加校验” → “为非法输入编写测试，然后让测试通过”
“修复 bug” → “写一个能复现问题的测试，然后让它通过”
“重构 X” → “确保修改前后测试都通过”

对于多步骤任务，给出简要计划：

1. [步骤] → 验证：[检查点]
2. [步骤] → 验证：[检查点]
3. [步骤] → 验证：[检查点]

清晰的成功标准能让你独立迭代。模糊的标准（例如“让它能用”）则需要不断确认。

## 执行顺序（复杂任务）

在进行大型多步骤工作之前，应遵循 **GUARDRAILS.md** 中的那些规则、当前的**范围**、以及计划运行的验证命令。如需暂停，请在聊天或**本地**草稿文件中总结进展（不要将 `HANDOFF.md` 添加到仓库中），然后使用 `/clear` 并基于该总结继续工作。


This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **javaclawbot** (14883 symbols, 37672 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/javaclawbot/context` | Codebase overview, check index freshness |
| `gitnexus://repo/javaclawbot/clusters` | All functional areas |
| `gitnexus://repo/javaclawbot/processes` | All execution flows |
| `gitnexus://repo/javaclawbot/process/{name}` | Step-by-step execution trace |

## CLI

| Task                                         | Read this skill file                                                            |
|----------------------------------------------|---------------------------------------------------------------------------------|
| Understand architecture / "How does X work?" | `(~/.javaclawbot/workspace).claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?"  | `(~/.javaclawbot/workspace).claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md`      |
| Trace bugs / "Why is X failing?"             | `(~/.javaclawbot/workspace).claude/skills/gitnexus/gitnexus-debugging/SKILL.md`            |
| Rename / extract / split / refactor          | `(~/.javaclawbot/workspace).claude/skills/gitnexus/gitnexus-refactoring/SKILL.md`          |
| Tools, resources, schema reference           | `(~/.javaclawbot/workspace).claude/skills/gitnexus/gitnexus-guide/SKILL.md`                |
| Index, status, clean, wiki CLI commands      | `(~/.javaclawbot/workspace).claude/skills/gitnexus/gitnexus-cli/SKILL.md`                  |

<!-- gitnexus:end -->

## 项目核心类
AgentLoop为助手系统loop入口
ContextBuilder - 上下文构建
ProjectRegistry - 项目路径

## 核心包
src/main/java/agent/subagent - 子代理相关
src/main/java/agent/tool - 工具相关
src/main/java/context - 上下文相关
src/main/java/gui/ui - ui客户端
src/main/java/providers - 提供者
src/main/java/skills - 技能
src/main/java/utils - 通用工具

## 变动
版本变动，修复bug 请放入 [CHANGELOG.md](CHANGELOG.md) 中 
## 更新日志
详情需要放入 [CHANGELOG.md](CHANGELOG.md) 中 
这里需要动态总结，并每次更新，规则：
| Date | Version | Change |
|------|---------|--------|
| 2026-04-23 | 1.7.0 | xxxxx. |

## JavaFX GUI 经验总结

### WebView 高度自适应

**核心原则：内容必须在 WebView 宽度确定之后加载，否则 scrollHeight 不准。**

错误做法：
- 在 WebView 隐藏时 loadContent → 展开时 reload 并 measure → 文档异步加载可能在布局完成前就绪，此时 WebView 渲染宽度仍是旧值（0 或 100px），内容按窄宽度换行 → scrollHeight 极高 → 大量空白

正确做法：
1. WebView 始终保持 `managed=true`（在布局中占位）
2. **宽度绑定在 loadContent 之前设置**
3. `Platform.runLater` 延迟 loadContent 到场景布局完成后
4. 文档就绪后测量高度，存入变量复用
5. 折叠/展开仅切换 `maxHeight=0` ↔ 存储值，不操作 visibility

### WebView JS 高度测量

```java
// 用 Math.max，不是只用 body.scrollHeight
wv.getEngine().executeScript(
    "(function(){return Math.max(document.body.scrollHeight,"
    + "document.documentElement.scrollHeight);})()");
```

### WebView 背景色

`-fx-background-color` 对 WebView（native 节点）可能不生效。三层兜底：
1. JavaFX: `wv.setStyle("-fx-background-color: ...")`
2. CSS: `html{background:...;height:100%}` — height:100% 确保 html 填满 WebView 视口，遮盖内容下方的 native 背景
3. CSS: `body{background:...}`

### WebView 滚轮事件

WebView 是 native 节点，会截获滚轮事件不给外层 ScrollPane。必须在 WebView 上加 `addEventFilter(ScrollEvent.SCROLL, e -> { e.consume(); Event.fireEvent(parent, e.copyFor(parent, parent)); })`。

### StageStyle.TRANSPARENT 定制窗口

- 窗口控件：三个 SVGPath 按钮（最小化/最大化/关闭），放在 BorderPane.setTop 的 HBox 中
- 边缘拖拽 resize：Scene 级别 event filter 监听 MOUSE_MOVED/PRESSED/DRAGGED/RELEASED，根据鼠标位置判断方向（四边+四角），更新 stage x/y/width/height
- 窗口拖拽：在 top bar 上监听 MOUSE_PRESSED/DRAGGED
- 关闭按钮直接调 `Platform.exit()` + `System.exit(0)` 兜底，不能只靠 onCloseRequest

### SVGPath 替代 Unicode 图标

Unicode 符号（如 ⏹ ✕ □）在 macOS 中文环境下渲染不一致。直接用 SVGPath + fill/stroke 控制颜色，hover/press 状态更新 fill 值即可。