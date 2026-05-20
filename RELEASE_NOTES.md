# NexusAI v2.3.11 Release Notes

**发布日期**：2026-05-19

---

## 概述

NexusAI v2.3.11 是一个稳定性修复版本，解决了标题写入的问题。本版本延续了 v2.3.0 以来的 GUI 全面升级，提供了更稳定、更流畅的桌面 AI 助手体验。

---

## 本版本更新内容

### Bug 修复
- **标题无法写入**：修复标题生成后无法正确写入的 bug

---

## 近期重要更新回顾

### v2.3.10 - 多会话状态管理
- 每个标签页独立维护 TodoWrite、文件变更、工具卡片等状态
- 标题生成完成后自动更新所有标签页标题
- 状态栏显示实际使用的模型名称

### v2.3.9 - 多会话标签栏
- 顶部标签栏支持多会话并行，类似浏览器标签设计
- 颜色圆点状态指示器：珊瑚色(运行中)/绿色(已完成)/灰色(空闲)/红色(错误)
- 标签切换时聊天内容即时切换

### v2.3.8 - 设置页面重构
- Claude 风格设计语言（奶油色画布、珊瑚橙主色、衬线标题）
- 数据库连接表单 Navicat 风格改造
- 聊天页面无限滚动加载历史消息
- 默认供应商精简为 8 个国内常用供应商

### v2.3.7 - 记忆检索协议
- 记忆主动检索协议，LLM 在任务开始前必须主动检索历史模式
- 系统命令 LocalCommand 全量补全

### v2.3.5 - 文件备份与回滚
- 右下角浮标折叠/展开交互
- 文件备份/回滚、差异查看器
- 菜单栏全面中文化

### v2.3.4 - 稳定性修复
- 修复 JavaFX 文本布局崩溃
- 修复 CSS 格式解析异常
- 工具卡片改用纯 JavaFX 渲染，消除空白区域

### v2.3.0 - 重大版本更新
- **项目更名**：javaclawbot → NexusAI
- **一键安装包**：Windows/macOS/Linux 安装脚本
- **GUI 全面升级**：基于 JavaFX 的桌面应用

---

## 系统要求

- Java 17 或更高版本
- Maven 3.6+
- Node.js（可选，用于 MCP 服务器）

---

## 安装方式

### Windows
下载 `NexusAI-Setup-2.3.0.exe` 一键安装包，自动检测并配置环境依赖。

### 手动安装
```bash
git clone https://github.com/your-org/javaclawbot.git
cd javaclawbot
mvn clean package -DskipTests
java -jar target/NexusAI.jar
```

---

## 功能特性

### GUI 界面
- 左侧导航 + 右侧内容区的经典布局
- 对话页面支持 Markdown 渲染、代码高亮
- 多会话标签页并行管理
- 状态栏显示模型名称和上下文使用率

### 模型配置
- 支持 DeepSeek、智谱 GLM、阿里云通义、MiniMax 等主流供应商
- 可视化配置 API Key、参数、上下文窗口

### 技能系统
- 插件式技能扩展
- 支持从 ClawHub/Skills.sh 市场安装
- 技能启用/禁用开关

### MCP 工具扩展
- 通过 Model Context Protocol 连接外部工具
- 支持联网搜索、图像理解、代码分析等

### 数据库集成
- 支持 MySQL、PostgreSQL、Oracle 等主流数据库
- 内置四层安全围栏：SQL 注入防护、操作分类检测、用户确认拦截、事务保护

### 定时任务
- Cron 表达式或固定间隔自动触发 AI 任务
- 支持一次性任务和周期性任务

### 多渠道支持
- Telegram、飞书、钉钉、Discord、Email、Slack、Matrix

---

## 已知问题

- macOS 上某些 JavaFX 原生库可能需要手动配置
- 大量消息时 WebView 渲染可能有轻微卡顿（已通过消息窗口化优化）

---

## 下载

- **Windows 安装包**: `NexusAI-Setup-2.3.0.exe`
- **JAR 包**: `NexusAI-2.3.11.jar`

---

## 致谢

感谢所有贡献者和用户的支持！

---

## 相关链接

- [GitHub 仓库](https://github.com/your-org/javaclawbot)
- [使用文档](asset/NexusAi操作配置指南.html)
- [技能市场 - ClawHub](https://clawhub.ai)
- [技能市场 - Skills.sh](https://skills.sh)
