# 常见问题排查

## 1. pandoc: command not found
说明系统未安装 Pandoc，或未加入 PATH。

## 2. --lua-filter 找不到 markdown-to-docx.lua
检查：
- 文件路径是否正确
- 命令中的当前工作目录是否正确

## 3. 主 Lua 能加载，但子模块 require 失败
检查：
- `lua/` 是否与 `markdown-to-docx.lua` 位于预期相对位置
- `package.path` 是否被正确设置
- 运行时 working directory 是否影响到相对资源

## 4. reference-doc 无效
检查：
- 模板文件是否存在
- 是否是 `.docx`
- 路径是否正确

## 5. 生成 docx 但样式不对
检查：
- 是否真的使用了正确模板
- 模板中的样式名称是否和预期一致
- 行内代码样式 `Inline Code` 是否在模板中有定义

## 6. 图片 caption 不符合预期
检查：
- 你启用的是 `image-title-to-caption.lua` 还是 `image-title-to-caption-add-number.lua`
- Markdown 图片是否提供了 `title`
- HTML `<img>` 是否正确写入了 `title`

## 7. HTML 元素没按预期转换
检查：
- 是否启用了 `markdown-html-recognition.lua`
- 第一段是否按默认流程先转成 html
- 原始 HTML 是否是 Pandoc 能接受的形式
