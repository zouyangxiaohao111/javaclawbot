# State Bootstrap Phase

本阶段先读取状态，不直接做代码生成。

## Must read first

- `codememory/codegen-plan-active.md`
- `codememory/codegen-memory.md`

## Duties

1. 判断 active plan 是否存在
2. 判断 active 是否真实有效，还是占位内容
3. 从 memory 中读取：
   - 作者名
   - 当前项目路径
   - 当前项目名
   - 数据库摘要
   - 最近模块
   - 用户固定偏好

## Output

- has_real_active_plan
- active_plan_summary
- memory_summary
- reusable_context