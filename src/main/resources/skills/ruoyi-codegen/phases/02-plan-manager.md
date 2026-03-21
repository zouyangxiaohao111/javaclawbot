# Plan Manager Phase

本阶段负责计划生命周期管理。

## Need plan when

- 完整模块生成
- 多文件输出
- 需要多轮推进
- 用户明确要求按步骤
- 涉及 history / memory / cron 持续跟踪

## Duties

1. 新建计划
2. 更新 active plan
3. 完成后归档到 history
4. 取消时写入 cancelled history
5. 保持计划格式简洁可执行

## Plan format

使用 checklist 格式：

## 计划：{计划名}

- [ ] 1. ...
- [ ] 2. ...
- [ ] 3. ...

## Output

- plan_needed
- active_plan_to_create_or_update
- current_step
- history_update_needed