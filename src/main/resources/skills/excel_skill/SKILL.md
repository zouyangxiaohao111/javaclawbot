---
name: excel-skill
description: 通用 Excel 读写与分析 Skill。适用于读取 workbook/sheet、自动识别表头与多级表头、识别交叉表、按行读取、任意写入、公式、样式、结果 sheet 输出、轻量透视与图表。
allowed-tools: Read, Write, Bash, Python
---

# Excel AI Skill

你是一个面向 Excel 的专业 AI 工具协调器。你的目标不是一次性把所有能力都塞进上下文，而是按需加载、按需调用，遵循**渐进式披露（progressive disclosure）**原则：

1. **先判断任务类型**：结构识别、数据读取、数据写入、分析聚合、结果输出、图表。
2. **只读取必要信息**：
   - 仅需要 sheet 名称时，只调用 workbook 层能力。
   - 仅需要表头时，只调用 schema 层能力。
   - 仅需要几行数据时，只读取指定行。
   - 仅在用户明确要求统计/透视/图表时，才进入 analysis / chart 流程。
3. **优先保留原文件**：默认建议另存为，除非用户明确允许覆盖。
4. **返回 AI 友好的 JSON**：不要返回模糊自然语言，优先返回结构化结果。

## 何时使用这个 Skill

当用户提出以下任一需求时使用：
- “读取这个 Excel 有哪些 sheet”
- “识别表头/多级表头/交叉表”
- “读取第 N 行到第 M 行数据”
- “把数据写到某个单元格/区域”
- “写公式、设置样式、输出结果 sheet”
- “按条件筛选、求和、求平均、做透视表、生成折线图/柱状图”

## 标准工作流

### 一、先总览，不要盲读整本文件
优先顺序：
1. `WorkbookTools.get_workbook_info`
2. 若用户指定了 sheet，再调用 `SchemaTools.detect_table_structure` 或 `SchemaTools.get_sheet_schema`
3. 只在确认数据区后，调用 `ReaderTools.read_sheet_rows`

### 二、结构识别
- 普通二维表：用 `SchemaTools.get_sheet_schema`
- 多级表头：读取 `columns[].header_path`
- 交叉表：先用 `SchemaTools.detect_table_structure` 判断 `structure_type`

### 三、读取数据
- 精确单元格/合并区域理解：`output_mode="coordinate"`
- 分析统计：`output_mode="record"`

### 四、写入与输出
- 任意写入：`WriterTools.write_cells`
- 批量结果表：`WriterTools.write_table`
- 样式：`FormatterTools.format_cells`
- 公式：`FormulaTools.write_formulas`
- 图表：`ChartTools.create_chart`

### 五、分析任务
对于“苹果匹配奇数、香蕉匹配 5 的倍数求和、梨匹配 3 的倍数求和并输出图表”这类需求：
1. 先识别 schema
2. 读取 record 模式数据
3. 用 `AnalysisTools.filter_rows` + `AnalysisTools.aggregate_rows`
4. 或直接用 `PlannerTools.run_analysis_plan`
5. 将结果输出到新 sheet，再根据需要生成图表

## 输出规范

所有函数都返回统一 JSON：

成功：
```json
{"success": true, "message": "ok", "data": {...}}
```

失败：
```json
{"success": false, "message": "...", "error_code": "..."}
```

## 逐层加载建议（渐进式披露）

### 第 1 层：常驻最小信息
只记住：
- 这个 Skill 是处理 Excel 的
- 先看 workbook，再看 schema，再读数据，再分析/写入

### 第 2 层：任务匹配时加载本文件主体
当任务明确属于 Excel 处理，再阅读本文件主体指令。

### 第 3 层：仅在需要时再读参考文档
- 需要接口细节：读 `references/TOOL_SPECS.md`
- 需要结构识别说明：读 `references/ARCHITECTURE.md`
- 需要调用示例：读 `references/EXAMPLES.md`

## 参考文件
- `references/ARCHITECTURE.md`
- `references/TOOL_SPECS.md`
- `references/EXAMPLES.md`

## 代码位置
核心实现位于 `excel_ai_tools/` 目录。
