# Codegen Memory

## Fixed Preferences

- 作者名：zcw
- 代码风格：遵循项目现有规范
- UI 库：Element Plus
- 前端技术：Vue 3 + TypeScript
- 测试框架：JUnit 5
- 禁止注解：@Schema
- Converter：MapStruct
- 默认包名前缀：com.zjky.pro.app

## Current Project

- 当前项目名：资产处置管理平台
- 当前项目路径：E:\idea_workspace\asset-disposal-platform
- 前端路径：E:\idea_workspace\asset-disposal-platform\ui

## Database

- host:port：192.168.20.130:13306
- 用户名：root
- 密码：已记录
- 数据库名：asset_disposal_dev

## Recent History

1. [2026-03-12] 完成驾驶舱页面开发 - 浙江省国有企业资产管理系统驾驶舱，包含完整前后端代码
   - 前端：10 个 Vue 组件（主页面 + 8 个子组件 + 地图）
   - 后端：Controller + Service + VO（7 个接口）
   - API：前端 API 接口定义
2. [2026-03-12] 更新 ProviderCatalog 模型列表 - 添加最新 OpenAI、Anthropic、Gemini 等模型
3. [2026-03-12] OnboardWizard 支持手填模型 - 在选择列表中添加"手动输入模型名称"选项

## Common Issues

### 修改作者名
当用户说"把作者名改成 xxx"时，更新本文件中的"作者名"。

### 更新数据库连接
当用户说"更新数据库连接信息"时，重新记录数据库连接摘要。

### mysql-tool.js 密码参数
调用数据库工具时，密码参数使用：
`--password "你的密码"`

不要使用：
`--password=你的密码`

## Working Notes

- 首次使用且作者名缺失时，先询问作者名。
- 首次生成完整模块且项目路径缺失时，先询问项目路径。
- 基于表生成代码前，先确认表是否真实存在。
- 如果活跃计划文件只是模板或空内容，视为当前没有活跃计划。
- 生成代码时优先贴合目标项目现有写法，再使用模板默认写法。
- 驾驶舱页面使用 1920x1080 固定尺寸，深色科技风格
- 图表使用 ECharts 实现
- 路由配置在 remaining.ts 中添加
- 浙江地图 GeoJSON 从 DataV 阿里云 API 动态加载
