---
name: md-to-docx
description: 使用 Pandoc + reference-doc 模板 + Lua 过滤器，将用户提供或选定的 Markdown 内容（可混排 HTML）转换为符合指定 Word 模板规范的 .docx 文件。凡是用户提到“markdown转docx”“md转word”“套用docx模板导出Word”“reference-doc 模板生成文档”“Pandoc 转 Word”“用 Lua filter 处理 markdown/html 后输出 docx”等场景，都应优先使用此技能。即使用户没有明确说 Pandoc，但只要目标是“把 Markdown/HTML 内容按某个 docx 模板规范导出为 Word 文件”，也要使用此技能。
compatibility:
  tools: [shell, filesystem]
  dependencies: [pandoc]
---

# Markdown → DOCX（Pandoc 模板转换）技能

## 这个技能是做什么的

这个技能用于把**用户选定的内容**转换成**符合指定 docx 模板规范**的 Word 文件。

核心特征：
- 输入内容默认是 **Markdown + HTML 混排**。
- 输出目标是 **.docx**，并且必须**套用用户选择的 reference-doc 模板**。
- 转换依赖 **pandoc**。
- 转换时默认加载 `markdown-to-docx.lua`，由它再按顺序加载子过滤器。

## 默认执行方式

除非用户明确要求改用别的流程，否则默认使用下面这条两段式 Pandoc 管道命令：

```bash
cd {skill_dir} && pandoc input.md -t html | pandoc -f html -o output.docx --reference-doc ./references/template/标题不编号-列表第二行缩进.docx --lua-filter markdown-to-docx.lua
```

这条命令的含义：
1. 第一段：把 Markdown 转成 HTML，尽量保留 Markdown 与混排 HTML 的结构。
2. 第二段：从 HTML 生成 docx。
3. 通过 `--reference-doc template.docx` 让输出文档继承用户选定模板的样式规范,用户若未说明,默认使用`./references/template/标题不编号-列表第二行缩进.docx`下面的。
4. 通过 `--lua-filter markdown-to-docx.lua` 执行自定义 Lua 过滤链，补强 Pandoc 默认行为。

## 什么时候必须使用这个技能

遇到下面这些诉求时，直接使用本技能：
- “把这段 Markdown 导出成 Word”
- “按照这个 docx 模板生成文档”
- “我有 reference-doc 模板，帮我输出符合规范的 docx”
- “Markdown 里混了 HTML 表格、img、上下标，转 Word 后要尽量保真”
- “Pandoc + Lua filter 转 docx”
- “根据用户选择的模板输出不同格式的 Word 文件”

## 必要前提

开始前先检查：
1. 系统中是否可用 `pandoc`
2. `markdown-to-docx.lua` 是否存在
3. `lua/` 子模块目录是否存在且可被 `require`
4. 用户指定的 `template.docx` 是否存在
5. 输入内容是否已保存为 `input.md`

如果 `pandoc` 不可用，先明确提示缺少依赖，询问用户是否需要安装`pandoc`,不要假装可以完成真实转换。如果用户同意安装,根据当前系统动态安装

## 标准工作流

### 1. 确认输入

明确这 3 件事：
- 输入内容是什么（通常是用户提供的 Markdown 内容）
- 输出文件名是什么
- 要套用哪个 docx 模板

如果用户没有指定模板，而环境中存在默认模板，则使用默认模板。
如果用户给了多个模板选项，则让调用方或程序逻辑决定具体模板。

### 2. 准备文件

通常需要准备：
- `input.md`
- `output.docx`
- `template.docx` 本技能会自带几个模板, 非必须
- `markdown-to-docx.lua`
- `lua/*.lua`

要求 `markdown-to-docx.lua` 与 `lua/` 目录在可解析的相对位置上，因为主脚本会根据脚本自身位置动态设置 `package.path`。

### 3. 执行默认转换命令

默认命令：

```bash
pandoc input.md -t html | pandoc -f html -o output.docx --reference-doc template.docx --lua-filter markdown-to-docx.lua
```

如果需要替换真实文件名，按实际路径替换：

```bash
pandoc /path/to/input.md -t html | pandoc -f html -o /path/to/output.docx --reference-doc /path/to/template.docx --lua-filter /path/to/markdown-to-docx.lua
```

注意：
- 第二段 pandoc 的工作目录最好设置到 `markdown-to-docx.lua` 所在目录，确保 `lua/` 子模块能正确加载。
- 如果在 Java 中调用，推荐像用户给出的 `PandocUtils` 一样，显式设置 `ProcessBuilder.directory(...)`。

### 4. 产物校验

生成后至少检查：
- `output.docx` 文件存在
- 文件大小大于 0
- 没有 Pandoc/Lua 执行报错

如果用户要求严格校验，再补充人工检查：
- 标题样式是否符合模板
- 列表缩进是否符合模板
- 图片标题 / 编号是否符合预期
- 行内代码、颜色、上下标、下划线、HTML 表格等是否正常

## Lua 过滤链说明

主入口：`markdown-to-docx.lua`

它负责：
- 动态定位自身目录
- 修正 `package.path`
- 按顺序加载子模块
- 把多个过滤器模块合并成一个 Pandoc 过滤器数组返回

当前模块链可包含：
- `lua/preserve_font_color`
- `lua/image-title-to-caption`
- `lua/add-inline-code`
- 可选：`lua/markdown-html-recognition`
- 可选：`lua/image-title-to-caption-add-number`

## 各脚本职责

### `markdown-to-docx.lua`
主装配器。
负责把多个 Lua 过滤器按顺序组合起来。

### `lua/add-inline-code.lua`
把行内代码转换成带 `custom-style = "Inline Code"` 的 Span，方便 Word 模板中定义专门的行内代码样式。

### `lua/image-title-to-caption.lua`
让图片的 `title` 成为 caption，而不是使用默认 alt 逻辑；若没有 title，则尽量避免生成不想要的 caption。
适用于**图片标题不编号**场景。

### `lua/image-title-to-caption-add-number.lua`
在 caption 中加入“图 N：”编号，并避免同一图片重复计数。
适用于**图片标题需要自动编号**场景。

### `lua/markdown-html-recognition.lua`
增强对 Markdown 中原始 HTML 的识别与转换，例如：
- `==高亮==`
- `<sup>` / `<sub>` / `<u>`
- `<img ...>`
- 单独 RawBlock 的 img

适合 Markdown 中混有较多 HTML 标记的场景。

### `lua/preserve_font_color.lua`
保留 `Span style="color: ...; background-color: ..."` 的字体色与底色，输出为 Word openxml run。

## 模板选择规则

参考 `references/template-selection.md`。

已知模板集合示例：
- `sci论文-标题不编号.docx`
- `sci论文-标题编号.docx`
- `标题不编号-列表第二行缩进.docx`
- `标题不编号-列表第二行顶格.docx`
- `标题编号-列表第二行缩进.docx`
- `标题编号-列表第二行顶格.docx`

选择模板时，要根据用户需求判断：
- 标题是否编号
- 列表第二行是否缩进 / 顶格
- 是否是 SCI 论文模板

如果用户没有说清，但上下文明显存在默认模板，则优先使用默认模板。

## 当用户给的是 Java 集成需求时

如果用户不是要你手工执行命令，而是要你写后端集成代码，应遵循下面原则：
- 保留“两段式 Pandoc 转换”思路
- 第一段 `md -> html`
- 第二段 `html -> docx`
- 第二段必须带：
  - `--reference-doc template.docx`
  - `--lua-filter markdown-to-docx.lua`
- 工作目录要指向包含 `markdown-to-docx.lua` 和 `lua/` 的目录
- 要打印 Pandoc 输出日志，方便排错
- 要处理超时、退出码、临时目录清理

## 推荐输出格式

### 如果用户要“生成 docx”
输出：
- 生成好的 `.docx`
- 简短说明：所用模板、所用命令、是否启用编号版 caption、是否启用 HTML 增强

### 如果用户要“生成 Java 集成代码”
输出：
- 完整 Java 类
- 必要的中文注释
- 模板路径与 Pandoc 工作目录说明

## 常见错误处理

详见 `references/troubleshooting.md`。

高频问题：
- `pandoc` 不存在
- `--lua-filter` 找不到主脚本
- 主脚本能找到，但 `require('lua/xxx')` 失败
- `reference-doc` 路径无效
- 输出 docx 生成了，但样式不符合预期
- 图题编号与非编号脚本混用

## 处理原则

- 以“输出符合模板规范的 docx 文件”为第一目标。
- 默认坚持使用 **Markdown + HTML 混排的两段式 Pandoc 转换**。
- 默认命令就是：

```bash
cd {skill_dir} && pandoc input.md -t html | pandoc -f html -o output.docx --reference-doc ./references/template/标题不编号-列表第二行缩进.docx --lua-filter markdown-to-docx.lua
```

- 除非用户明确要求改流程，否则不要擅自改成别的单段命令。
- 如果缺少依赖或模板文件，先明确指出阻塞点。
