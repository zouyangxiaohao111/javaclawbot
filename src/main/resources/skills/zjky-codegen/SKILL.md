---
name: zjky-codegen
description: "Route and execute ZJKY-style code generation tasks for company framework projects. Use this skill when the user explicitly wants to generate, extend, continue, or refine backend modules, frontend pages, CRUD code, DDL-first business modules, menu SQL, route config, tests, or API docs for the company stack. This skill should act as a workflow-driven orchestrator: first load active plan and memory, then classify the request into continue-plan, partial-change, existing-table, no-table, or ui-image, and only then load the required scenario rules, generator rules, templates, and scripts. Prefer stable multi-round execution over one-shot generation. Always keep plan, memory, history, and follow-up notification behavior consistent."
---

# zjky-codegen

建科院代码生成总技能。  
这是一个“总 skill + 内部阶段化路由”的工作流技能，而不是单纯的一次性模板生成器。

目标：

- 根据用户请求识别任务场景
- 严格走 active plan / history / memory / cron 流程
- 按需读取场景规则与生成规则
- 稳定生成贴合项目规范的代码与配套产物
- 支持多轮推进：先定方向，再补细节，再收敛质量

## Core responsibility

本 skill 负责：

1. 读取当前活跃计划与记忆
2. 判断当前请求属于哪种场景
3. 判断是否需要建计划
4. 只加载当前场景需要的规则文档
5. 调度后端、前端、文档、测试、菜单 SQL、路由等生成阶段
6. 统一更新 active plan、history、memory，并在满足条件时触发通知

本 skill 不应在一开始就把所有规则、模板和场景文档全部混在一次响应里使用。

---

## Supported task categories

本 skill 支持以下主要任务：

- 延续之前未完成的代码生成计划
- 根据现有表结构生成或补全代码
- 根据功能需求先设计 DDL 再生成代码
- 对现有模块做局部修改
- 根据截图、原型图、设计图生成 Vue 页面
- 补充测试、文档、菜单 SQL、路由配置

---

## Workflow-first principle

本 skill 必须优先按工作流执行，而不是直接开始写代码。

默认执行顺序：

1. 读取 `codememory/codegen-plan-active.md`
2. 读取 `codememory/codegen-memory.md`
3. 判断 active plan 是否真实有效
4. 识别请求场景
5. 判断是否需要正式计划
6. 按需加载对应 scenario / generator / template / script
7. 执行生成或修改
8. 更新 active/history/memory
9. 满足条件时触发 cron 通知

如果状态文件不存在，应按首次使用处理，而不是报错。

---

## Internal routing categories

收到请求后，必须优先将任务识别为以下五类之一：

### 1. continue-plan
适用于：
- 继续上次任务
- 接着之前计划做
- 按原有计划继续生成
- 在前一次结果基础上继续补充

优先动作：
- 读取 active plan
- 读取 memory
- 判断是否真实未完成
- 向用户简要说明当前项目、计划、进度

### 2. partial-change
适用于：
- 只改一个接口
- 只补一个 mapper / VO / SQL / 页面
- 只增加一个字段或一个功能点
- 只改前端或只改后端某一层

优先读取：
- `reference/scenario-a-partial-change.md`

### 3. no-table
适用于：
- 用户描述业务，但库表尚未存在
- 需要先设计表结构或 DDL
- 用户明确要求先出表再生成代码

优先读取：
- `reference/scenario-b-no-table.md`

### 4. existing-table
适用于：
- 已有表结构，需基于表生成或补全代码
- 用户明确给出表名或数据库表已存在
- 需要先查表再决定单表 / 主子表 / 树表

优先读取：
- `reference/scenario-c-existing-table.md`

### 5. ui-image
适用于：
- 根据截图 / 原型图 / 设计稿生成页面
- 页面复刻或界面骨架生成
- 基于图片分析字段与交互

优先读取：
- `reference/scenario-d-ui-image.md`

---

## Routing order

按以下顺序判断，不要自由跳步：

1. 是否继续旧任务
2. 是否局部修改
3. 是否图片页面
4. 是否已有表
5. 是否无表需先设计

如果分类存在模糊性，应做最小范围判断，而不是同时混走多个场景。

---

## Planning policy

### 必须创建正式计划的情况

满足任一条件时，必须创建或更新 active plan：

- 完整生成一个业务模块
- 涉及前后端多个文件
- 需要分阶段推进
- 用户明确要求按步骤执行
- 涉及 active/history/memory/cron 的持续跟踪
- 截图任务同时包含 API 骨架或后端生成
- 基于现有表做完整模块生成
- 无表场景需要先 DDL 再代码生成

### 可以不创建正式计划的情况

仅当以下条件明显成立时，才可直接执行：

- 明确是局部修改
- 变更范围小
- 不需要持续追踪
- 不会影响 active/history/memory
- 用户明确要求先直接给代码

---

## Multi-round default working mode

默认采用多轮工作方式，而不是一次性压满所有内容：

### 第 1 轮：定方向
- 识别场景
- 判断 active plan
- 确定范围
- 提出最小确认项
- 需要时建立计划

### 第 2 轮：出骨架
- 生成主要代码骨架
- 生成关键文件
- 补基础 SQL / 文档 / API / 页面骨架

### 第 3 轮：做收敛
- 对齐项目风格
- 补全细节
- 做约束检查
- 更新 memory / history / active / cron

多轮不是重复提问，而是逐步收敛质量。

---

## Context loading policy

不要在每个任务中一次性读取全部 reference 文档。  
必须按需加载：

### 所有任务默认先读
- `codememory/codegen-plan-active.md`
- `codememory/codegen-memory.md`

### 场景文档按需读取
- partial-change -> `reference/scenario-a-partial-change.md`
- no-table -> `reference/scenario-b-no-table.md`
- existing-table -> `reference/scenario-c-existing-table.md`
- ui-image -> `reference/scenario-d-ui-image.md`

### 生成规则按需读取
- 后端 -> `reference/backend-rules.md`
- 前端 -> `reference/frontend-rules.md`
- 测试 -> `reference/testing-rules.md`
- 文档 -> `reference/doc-rules.md`
- 菜单 SQL -> `reference/menu-sql-rules.md`
- 模板选择 -> `reference/template-selection.md`

### 项目指导文档按需读取
- `asset/` 下相关手册

不要无差别全读，避免噪声过高。

---

## Execution scopes

完整模块默认可包含：

- 后端代码
- 前端代码
- 单元测试
- API 文档
- 菜单 SQL
- 路由配置

如果用户说“全部生成”，可直接按全量处理。  
如果用户只要部分内容，不要强行扩展到全量。

---

## Hard constraints

始终遵守：

- 禁止使用 `@Schema`
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

## Template usage policy

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

## Finalization policy

任务完成后必须检查：

- 是否更新 active plan
- 是否需要将计划写入 history
- 是否更新 memory
- 是否列出生成或修改的文件
- 是否说明剩余待确认点
- 是否满足触发 cron 提醒的条件

---

## Response structure

### 信息不足时
1. 当前识别场景
2. 当前状态（active plan / memory 是否命中）
3. 缺失的最小关键信息
4. 拿到后下一步动作

### 开始执行时
1. 当前识别场景
2. 当前生成范围
3. 将读取哪些关键规则
4. 将创建或修改的主要文件
5. 是否创建或更新计划

### 完成时
1. 已完成内容
2. 生成或修改的文件清单
3. 约束检查结果
4. memory / active / history / cron 更新情况
5. 剩余风险或待确认项

---

## What not to do

不要这样做：

- 不读 active plan 就直接开始新任务
- 不读 memory 就重复问已知信息
- 没查表就假装已经理解表结构
- 没定 DDL 就直接写完整 CRUD
- UI 图任务直接伪造后端真实接口
- 局部修改被误扩展成完整模块重构
- 把所有 reference 文档一次性全塞进上下文
- 把模板输出误当成最终业务成品
- 忽略 active/history/memory/cron 的收尾动作