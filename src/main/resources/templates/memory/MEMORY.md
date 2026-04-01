# Long-term Memory

This file stores important information that should persist across sessions.

## User Information

(Important facts about the user)

## Preferences

(User preferences learned over time)

## Project Context

(Information about ongoing projects)

## Important Notes

(Things to remember)

---

*This file is automatically updated by javaclawbot when important information should be remembered.*

## 多 Memory 系统

项目使用 multi-memory 架构记录经验：

```
memory/
├── semantic/patterns.json     # 抽象模式（可复用）
├── episodic/{年份}/             # 具体经验（时间记录）
└── working/current_session.json # 当前会话状态
```

模式来源可追溯：每个模式有 `id`、`source`、`confidence`、`applications` 字段。
