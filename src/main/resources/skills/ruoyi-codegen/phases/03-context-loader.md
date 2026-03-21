# Context Loader Phase

本阶段负责按需加载文档，而不是全部读取。

## Default reads

- `codememory/codegen-plan-active.md`
- `codememory/codegen-memory.md`

## Scenario reads

- partial-change -> `reference/scenario-a-partial-change.md`
- no-table -> `reference/scenario-b-no-table.md`
- existing-table -> `reference/scenario-c-existing-table.md`
- ui-image -> `reference/scenario-d-ui-image.md`

## Generator reads by scope

- backend -> `reference/backend-rules.md`
- frontend -> `reference/frontend-rules.md`
- testing -> `reference/testing-rules.md`
- docs -> `reference/doc-rules.md`
- menu -> `reference/menu-sql-rules.md`
- template -> `reference/template-selection.md`

## Project docs

按需读取 `asset/` 下对应指导文档。

## Rule

不要无差别全读。
只加载当前阶段真正需要的上下文。