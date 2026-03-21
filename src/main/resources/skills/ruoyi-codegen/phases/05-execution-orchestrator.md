# Execution Orchestrator Phase

本阶段负责调度具体执行，不负责计划管理。

## Execution branches

### Backend
读取：
- `reference/backend-rules.md`
- `reference/template-selection.md`

### Frontend
读取：
- `reference/frontend-rules.md`
- `reference/template-selection.md`

### Testing
读取：
- `reference/testing-rules.md`

### Docs
读取：
- `reference/doc-rules.md`

### Menu SQL
读取：
- `reference/menu-sql-rules.md`

### Existing Table DB flow
先调用：
- `scripts/mysql-tool.js`

### No Table flow
先产出：
- DDL / 字段方案
- 用户确认后再继续

### UI Image flow
先做：
- 页面结构拆解
- 字段与动作推断
- 再生成页面骨架