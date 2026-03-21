# Scope Confirmation Phase

本阶段负责确认本轮输出边界。

## Possible scopes

- backend
- frontend
- test
- docs
- menu-sql
- route

## Rules

- 用户说“全部生成”时，直接按全量
- 用户只说生成后端，不扩展前端
- 局部修改只保留必要范围
- 图片页面默认优先 frontend，可选 API 骨架