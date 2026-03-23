---
name: zjky-codegen
description: "Route and execute ZJKY-style code generation tasks for company framework projects. This is a workflow-first orchestration skill, not a one-shot generator. Always treat ZjkyCode.md as the project-level guidance source, then load active plan and memory, classify the request into one primary scenario, load only the needed rules, execute in phases, and finally update plan/history/memory/cron consistently. Prefer stable multi-round delivery over uncontrolled full-context generation." 
---

# zjky-codegen

建科院代码生成总技能。  
这是一个**总 skill + 内部阶段化路由 + 状态驱动收尾**的工作流技能，不是一次性模板填空器。

核心目标：

- 把 `ZjkyCode.md` 作为项目级总纲文档持续维护
- 根据用户请求稳定识别主场景
- 严格走 `active plan / history / memory / cron` 生命周期
- 只按需读取场景规则、生成规则、模板和项目资料
- 支持多轮推进：定方向 → 出骨架 → 做收敛
- 让生成结果持续贴合项目规范，而不是一次性堆满内容

---

## 1. Skill positioning

本 skill 的定位是：

1. 管理项目级上下文
2. 管理任务级计划与状态
3. 路由到正确的生成场景
4. 调度后端、前端、SQL、测试、文档等生成阶段
5. 在任务结束时统一做状态写回与后续提醒处理

它的职责不是：

- 一上来把所有 reference 文档全部读入,渐进式纰漏
- 在没识别场景前就开始写代码
- 把模板直接当最终业务实现
- 忽略 `active/history/memory/cron` 的收尾动作

---

## 2. Five-state model

本 skill 维护五类核心状态或文档，每类职责必须分离：

### 2.1 `ZjkyCode.md` —— 项目总纲
作用：
- 作为**项目级长期指导文档**
- 帮助 LLM 快速理解仓库的大局架构、关键命令、重要规则、特殊约束
- 随项目演进持续改进，但更新频率低于任务状态文件

特点：
- 高优先级
- 低频更新
- 面向整个仓库
- 不记录具体某一轮任务进度

### 2.2 `codememory/codegen-plan-active.md` —— 当前活跃计划
作用：
- 记录当前正在推进的正式任务
- 表示“这轮工作还没结束，后续还会继续”

特点：
- 任务级
- 中高频更新
- 只保留当前仍然有效的活跃计划

### 2.3 `codememory/codegen-plan-history.md` —— 历史归档
作用：
- 记录已经完成、终止、废弃或被替代的正式计划
- 用于回顾，不用于当前路由判断

特点：
- 低频追加
- 不作为默认第一优先上下文

### 2.4 `codememory/codegen-memory.md` —— 持久记忆
作用：
- 记录跨任务仍然有效的重要偏好、约束、命名、路径、风格约定
- 避免重复问用户已经明确过的信息

特点：
- 跨任务共享
- 中频更新
- 只保留未来仍有价值的信息

### 2.5 `cron / reminder` —— 后续提醒
作用：
- 仅在确实存在“后续还要继续推进、检查、提醒”的条件下使用
- 不应为普通一次性回答强行创建提醒

特点：
- 条件触发
- 不是默认动作

---

## 3. Core responsibility

本 skill 负责：

0. 检查项目根目录是否存在 `ZjkyCode.md`
1. 决定 `ZjkyCode.md` 是“首次生成 / 读取使用 / 增量改进 / 暂不处理”
2. 读取当前活跃计划与持久记忆
3. 判断请求属于哪个**主场景**
4. 判断是否需要建立或更新正式计划
5. 只加载当前场景需要的规则文档
6. 分阶段执行生成或修改
7. 统一更新 `active plan / history / memory / cron`

本 skill 不应在一开始就把所有规则、模板和场景文档全部混在一次响应里使用。

---

## 4. Entry protocol

每次进入本 skill，必须按以下顺序工作：

### Phase 0: project guidance check
1. 判断项目根目录是否存在 `ZjkyCode.md`
2. 若不存在：先生成 `ZjkyCode.md`
3. 若存在：优先读取其关键内容
4. 若请求涉及仓库理解、架构梳理、命令补全、规则缺失，允许增量改进 `ZjkyCode.md`

### Phase 1: task state loading
1. 读取 `codememory/codegen-plan-active.md`
2. 读取 `codememory/codegen-memory.md`
3. 判断 active plan 是否仍然真实有效

### Phase 2: request routing
1. 将请求识别为一个主场景
2. 必要时打上副标签，但不能并行混跑多个主场景
3. 判断是否需要正式计划

### Phase 3: selective context loading
1. 只读取当前主场景需要的 scenario 文档
2. 只读取当前执行范围需要的 generator / template / project docs
3. 不做无差别全量加载

### Phase 4: staged execution
1. 定方向
2. 出骨架
3. 做收敛

### Phase 5: finalization
1. 更新 active
2. 需要时写入 history
3. 更新 memory
4. 满足条件时触发 cron 通知

如果状态文件不存在，应按首次使用处理，而不是报错。

---

## 5. `ZjkyCode.md` lifecycle policy

`ZjkyCode.md` 是本 skill 的一等公民，不是附带步骤。

### 5.1 首次生成条件
满足以下任一情况时，应创建 `ZjkyCode.md`：

- 项目根目录不存在该文件
- 明显是首次在该仓库开展系统化代码生成
- 后续多轮开发需要稳定的项目级指导

### 5.2 已存在时的默认行为
若 `ZjkyCode.md` 已存在：

- 默认先读再用
- 不要每轮都重写
- 除非发现明显缺失、陈旧、与当前仓库不匹配，才做增量改进

### 5.3 适合增量改进的情况
- 新识别出关键命令但文档未记录
- 新识别出重要架构关系但文档缺失
- 项目存在 `.javaclawbot/rules/`、`.cursorrules`、`.github/copilot-instructions.md`、`README.md` 等关键规则来源，而当前文档缺失重要信息
- 当前文档过于表面化，无法支撑后续多轮开发

### 5.4 不应更新的情况
- 只是一次很小的局部修改
- 当前请求与仓库总纲无关
- 没有新发现的高价值项
- 只是某轮任务进度变化，不应写进项目总纲

### 5.5 内容要求
生成或改进 `ZjkyCode.md` 时，必须遵守：

- 聚焦项目级信息，不写任务级临时进度
- 包含高频命令：构建、运行、测试、单测、必要脚本
- 强调高层架构、关键目录关系、重要约束
- 可吸收 README、Cursor/Copilot 规则中的重要内容
- 不堆砌显而易见的通用开发常识
- 不罗列所有简单可见目录
- 不虚构仓库中不存在的信息

### 5.6 文件开头固定文案
文件前必须包含：

```md
# ZjkyCode.md

该文件为使用 LLM 在本仓库中的代码工作提供了指导。
```

### 5.7 推荐提示基底
当需要生成或改进 `ZjkyCode.md` 时，可使用以下意图作为内部执行基底：

```txt
请分析这个代码库并创建或改进一个 ZjkyCode.md 文件，未来将提供给 LLM 代码实例在这个仓库中运行。

补充目标：
1. 总结会被广泛使用的命令，包括构建、运行测试、运行单一测试、必要开发脚本。
2. 总结高级代码架构和关键结构，帮助未来实例快速理解项目大局。
3. 若存在 .javaclawbot/rules/、.cursorrules、.github/copilot-instructions.md、README.md，请吸收其中真正重要的项目级规则。

约束：
- 如果已经有 ZjkyCode.md，优先做增量改进，不轻易整体重写。
- 不写显而易见的通用开发实践。
- 不虚构“常见开发任务”“开发技巧”“支持与文档”等未在仓库中明确存在的信息。
- 不罗列所有容易看出的文件结构。
```

---

## 6. Supported primary scenarios

收到请求后，必须先识别一个**主场景**。主场景只能有一个；如有必要，可追加副标签描述执行范围。

### 6.1 continue-plan
适用于：
- 继续上次任务
- 接着之前计划做
- 按原有计划继续生成
- 在前一次结果基础上继续补充

优先动作：
- 读取 active plan
- 读取 memory
- 判断 active plan 是否真实未完成
- 向用户简要说明当前项目、计划、进度

### 6.2 partial-change
适用于：
- 只改一个接口
- 只补一个 mapper / VO / SQL / 页面
- 只增加一个字段或一个功能点
- 只改前端或只改后端某一层

优先读取：
- `reference/scenario-a-partial-change.md`
  它会说明如何修改

### 6.3 no-table
适用于：
- 用户描述业务，但库表尚未存在
- 需要先设计表结构或 DDL
- 用户明确要求先出表再生成代码

优先读取：
- `reference/scenario-b-no-table.md`

  它会说明如何操作

### 6.4 existing-table
适用于：
- 已有表结构，需基于表生成或补全代码
- 用户明确给出表名或数据库表已存在
- 需要先查表再决定单表 / 主子表 / 树表

优先读取：
- `reference/scenario-c-existing-table.md`

  它会说明如何操作

### 6.5 ui-image
适用于：
- 根据截图 / 原型图 / 设计稿生成页面
- 页面复刻或界面骨架生成
- 基于图片分析字段与交互

优先读取：
- `reference/scenario-d-ui-image.md`

  它会说明如何操作

---

## 7. Routing decision order

按以下顺序判断，不要自由跳步：

1. 是否继续旧任务
2. 是否明确是局部修改
3. 是否明显是图片页面任务
4. 是否已有表结构可依赖
5. 是否属于无表先设计 DDL

### 路由规则
- 若命中 continue-plan，则优先使用 continue-plan
- 若用户说“继续”但 active plan 实际不存在或已完成，不得强行归入 continue-plan
- 若同时出现“已有表 + 只改一点”，优先归入 partial-change，并把 existing-table 作为副标签
- 若是截图生成页面但用户同时要后端，仅可把 ui-image 作为主场景，再根据需要挂后端副标签，不能伪造真实接口
- 如果分类存在模糊性，应做**最小范围判断**，不要同时混走多个主场景

---

## 8. Planning policy

### 8.1 必须创建或更新正式计划的情况
满足任一条件时，必须创建或更新 active plan：

- 完整生成一个业务模块
- 涉及前后端多个文件
- 需要分阶段推进
- 用户明确要求按步骤执行
- 涉及持续跟踪
- 截图任务同时包含 API 骨架或后端生成
- 基于现有表做完整模块生成
- 无表场景需要先 DDL 再代码生成

### 8.2 可不建立正式计划的情况
仅当以下条件明显成立时，才可直接执行：

- 明确是局部修改
- 变更范围小
- 不需要持续追踪
- 不影响 active/history/memory
- 用户明确要先直接给代码

### 8.3 active plan 有效性判断
只有同时满足以下条件，active plan 才视为有效：

- 存在明确目标
- 仍有未完成步骤
- 与当前请求属于同一任务链
- 没有被新任务明确替代

若不满足，应将其视为无效或待归档，而不是机械继续。

---

## 9. Multi-round execution model

默认采用多轮推进，而不是一次性压满所有内容。

### Round 1: 定方向
- 识别场景
- 判断 active plan
- 明确范围
- 明确最小缺口
- 需要时建立计划

### Round 2: 出骨架
- 生成主要代码骨架
- 生成关键文件
- 补基础 SQL / 文档 / API / 页面骨架

### Round 3: 做收敛
- 对齐项目风格
- 补齐细节
- 做约束检查
- 更新 memory / history / active / cron

多轮不代表重复提问，而是逐步收敛质量。

---

## 10. Context loading policy

不要在每个任务中一次性读取全部 reference 文档。必须按需加载。

### 10.1 默认先读
- `ZjkyCode.md`（若存在且与当前请求有关）
- `codememory/codegen-plan-active.md`
- `codememory/codegen-memory.md`

### 10.2 场景文档按需读取
- partial-change -> `reference/scenario-a-partial-change.md`
- no-table -> `reference/scenario-b-no-table.md`
- existing-table -> `reference/scenario-c-existing-table.md`
- ui-image -> `reference/scenario-d-ui-image.md`

### 10.3 生成规则按需读取
- 后端 -> `reference/backend-rules.md`
- 前端 -> `reference/frontend-rules.md`
- 测试 -> `reference/testing-rules.md`
- 文档 -> `reference/doc-rules.md`
- 菜单 SQL -> `reference/menu-sql-rules.md`
- 模板选择 -> `reference/template-selection.md`

### 10.4 项目指导文档按需读取
- `asset/` 下相关手册
- README
- `.javaclawbot/rules/`
- `.cursorrules`
- `.github/copilot-instructions.md`

原则：
- 能少读就少读
- 先读最关键的
- 后续缺信息再增量补读

---

## 11. Execution scopes

完整模块默认可包含：

- 后端代码
- 前端代码
- 单元测试
- API 文档
- 菜单 SQL
- 路由配置

如果用户说“全部生成”，可按全量处理。  
如果用户只要部分内容，不要强行扩展到全量。

---

## 12. Hard constraints

始终遵守：

- 禁止使用swagger形式的任何接口 注解 eg: `@Schema`
- 对象转换优先 MapStruct
- 作者名首次缺失时先询问用户
- 默认包名前缀：`com.zjky.pro.app.{模块名}`
- 代码风格优先贴合项目现有规范
- 菜单 SQL 必须先看真实表结构
- 表生成代码必须先查表
- 无表场景必须先定 DDL
- 图片页面任务不得伪造真实后端接口和表结构
- 模板只用于骨架，不能视为最终业务实现

---

## 13. Template usage policy

使用 `templates/` 时必须遵守：

1. 先根据场景决定是否可使用模板
2. 模板只生成基础骨架
3. 生成后必须继续贴合：
   - 真实表结构
   - 项目指导文档
   - 现有代码风格
   - 用户具体业务需求
4. 遇到主子表、树表、联表查询、导入导出、字典翻译、状态流转、复杂交互时，不得机械套模板

优先读取：
- `reference/template-selection.md`

---

## 14. Finalization policy

任务完成后必须检查：

1. 是否需要更新 `codegen-plan-active.md`
2. 是否需要把已完成或失效计划写入 `codegen-plan-history.md`
3. 是否需要更新 `codegen-memory.md`
4. 是否需要增量改进 `ZjkyCode.md`
5. 是否列出生成或修改的文件
6. 是否说明剩余待确认点或风险
7. 是否满足触发 cron 提醒的条件

### 14.1 幂等性要求
状态写回必须尽量幂等：

- 同一轮未发生实质进展，不要重复刷写 memory/history
- 计划未完成时，不要错误归档到 history
- 只是普通问答或说明，不要创建 active plan
- 只是小改动且无长期价值，不要污染 memory
- 任务级进度不要写入 `ZjkyCode.md`

---

## 15. Response contract

### 15.1 信息不足时
按以下结构响应：
1. 当前识别场景
2. 当前状态（`ZjkyCode.md` / active plan / memory 是否命中）
3. 缺失的最小关键信息
4. 拿到后下一步动作

### 15.2 开始执行时
按以下结构响应：
1. 当前识别场景
2. 当前生成范围
3. 将读取哪些关键规则
4. 将创建或修改的主要文件
5. 是否创建或更新计划

### 15.3 完成时
按以下结构响应：
1. 已完成内容
2. 生成或修改的文件清单
3. 约束检查结果
4. `ZjkyCode.md / memory / active / history / cron` 更新情况
5. 剩余风险或待确认项

---

## 16. What not to do

不要这样做：

- 不检查 `ZjkyCode.md` 就直接进入多轮开发
- 每轮都重写 `ZjkyCode.md`
- 不读 active plan 就直接开始新任务
- 不读 memory 就重复问已知信息
- 没查表就假装已经理解表结构
- 没定 DDL 就直接写完整 CRUD
- UI 图任务直接伪造后端真实接口
- 局部修改被误扩展成完整模块重构
- 把所有 reference 文档一次性全塞进上下文
- 把模板输出误当成最终业务成品
- 忽略 active/history/memory/cron 的收尾动作

---

## 17. Minimal operational summary

一句话执行原则：

**先检查项目总纲，再判断当前计划，再识别一个主场景，再按需读取规则并分阶段执行，最后做幂等的状态写回。**
