# Router Phase

本阶段只负责把请求识别为一个主场景，不负责直接生成代码。

## Routing targets

- continue-plan
- partial-change
- no-table
- existing-table
- ui-image

## Priority order

1. continue-plan
2. partial-change
3. ui-image
4. existing-table
5. no-table

## Rules

- 如果用户表达明显是继续之前任务，优先进入 continue-plan
- 如果用户只要求补一个点，优先 partial-change
- 如果输入主要是图片、原型、截图，优先 ui-image
- 如果已存在表，优先 existing-table
- 如果明确无表，优先 no-table

输出：
- route_label
- reason
- whether_need_plan_judgement