# CLAUDE.md
## 编译命令
执行脚本
[maven-compiled.bat](maven-compiled.bat)
## 规则一：
先思后码（Think Before Coding）明确声明前提假设。遇不确定处，先提问而非盲目猜测。存在歧义时，列出多种可能的理解路径。若存在更简方案，应果断提出异议。陷入困惑时立即暂停，并明确指出模糊之处。
## 规则二：
简单至上（Simplicity First）仅用最少代码解决问题。杜绝任何“以防万一”的猜测性实现。不实现需求之外的功能。不为仅用一次的代码强行设计抽象。自检：资深工程师是否会认为此实现过度复杂？若是，立即简化。
## 规则三：
外科手术式修改（Surgical Changes）仅改动绝对必要的部分。仅清理自身引入的冗余或错误。切勿“顺手优化”相邻代码、注释或排版格式。未出问题的代码绝不重构。严格贴合项目既有风格。
## 规则四：
目标驱动执行（Goal-Driven Execution）明确定义成功标准（验收条件）。持续迭代直至验证通过。不要死板遵循步骤。定义成功形态并自主迭代。清晰的成功标准赋予你独立闭环执行的能力。
## 规则五：
仅将模型用于判断与裁量场景（Use the model only for judgment calls）适用于我：分类、起草、摘要总结、信息提取。切勿用于：路由分发、重试机制、确定性数据转换。若常规代码能给出答案，就由代码处理。
## 规则六：
Token 预算绝非软性建议（Token budgets are not advisory）单任务上限：4,000 Token。单会话上限：30,000 Token。接近预算上限时，执行上下文摘要并重置状态。主动暴露超支。切勿静默越界消耗。
## 规则七：
显式暴露冲突，拒绝折中调和（Surface conflicts, don't average them）若两种模式相互矛盾，明确择一（优先更新或更经测试的版本）。阐明选择理由。将另一处标记为待清理项。切勿强行融合冲突范式。## 规则八：落笔前先阅读（Read before you write）添加代码前，通读该文件的导出接口、直接调用方及公共工具函数。“看似互不干涉”是最危险的判断。若不理解现有代码为何如此设计，先提问。
## 规则九：
测试验证意图，而非仅验证行为（Tests verify intent, not just behavior）测试必须体现该行为*为何重要*（WHY），而非仅断言它*做了什么*（WHAT）。若业务逻辑变更时测试仍不报错，则该测试设计错误。
## 规则十：
关键步骤后强制设立检查点（Checkpoint after every significant step）总结已完成事项、已验证结果及剩余待办。若无法向我清晰描述当前状态，绝不可继续推进。若丢失上下文或逻辑偏离，立即暂停并重新声明当前状态。
## 规则十一：
严格遵从代码库既有规范，即便持保留意见（Match the codebase's conventions, even if you disagree）在代码库内部：规范一致性 > 个人技术偏好。若确信某规范存在实质危害，请显式提出。切勿暗中另起范式。
## 规则十二：
显式失败（Fail loud）若有步骤被静默跳过，宣称“已完成”即为错误。若有测试被跳过，宣称“测试通过”即为错误。默认原则：主动暴露不确定性，绝不掩盖。
##  编码后必须添加数据流日志
日志通常采用sfl4j 和 logback 切勿使用其他日志 格式为：
```java
if(log.enableDebug) {
    log.debug()
        }
log.info  log.warn log.error
```

## 可参考的经验
[经验.md](%E7%BB%8F%E9%AA%8C.md)

## 执行顺序（复杂任务）

在进行大型多步骤工作之前，应遵循 **GUARDRAILS.md** 中的那些规则、当前的**范围**、以及计划运行的验证命令。如需暂停，请在聊天或**本地**草稿文件中总结进展（不要将 `HANDOFF.md` 添加到仓库中），然后使用 `/clear` 并基于该总结继续工作。


This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **javaclawbot** (16672 symbols, 41309 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

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

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

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
版本变动需要认为确认，不要自动新增递增版本号，修复bug 请放入 [CHANGELOG.md](CHANGELOG.md) 中 ,如果该文档中版本新增了，pom.xml中也同步更新，每次版本变动需要同步在git中打上tag和branch,比如版本变动到2.3.4 远程需要同步新增这个分支
## git
由于在中国，github访问不稳定，需要 git -c http.proxy=http://127.0.0.1:7897  你需要检测是否开启代理，如果未开启代理 尝试一次原始提交 失败后提醒用户开启代理 
## 更新日志
详情需要放入 [CHANGELOG.md](CHANGELOG.md) 中,记录修改人（可以从git或者svn中获取用户本人）
这里需要动态总结，并每次更新，记住 版本每次变动，pom同步变动，规则：
| Date | Version | Change |
|------|---------|--------|
| 2026-04-23 | 1.7.0 | xxxxx. |

## GUI开发手册
见 [GUI开发手册.md](GUI.md)