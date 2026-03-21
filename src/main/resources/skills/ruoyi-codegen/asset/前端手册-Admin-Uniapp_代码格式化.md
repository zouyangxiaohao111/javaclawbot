# 代码格式化 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 前端手册-Admin-Uniapp
> - **文档大小**: 3.45 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. JetBrains 端](#1-jetbrains-端)
- [2. VS Code 端](#2-vs-code-端)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/format/

**所属章节**: 前端手册 Admin Uniapp

**爬取时间**: 2026/3/3 15:08:35

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2026-01-01](javascript:;)

目录

[1\. JetBrains 端](#_1-jetbrains-端)

[2\. VS Code 端](#_2-vs-code-端)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)代码格式化

项目的 ESLint 配置文件位于根目录的 [eslint.config.mjs (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/eslint.config.mjs)，基于 [@uni-helper/eslint-config (opens new window)](https://github.com/uni-helper/eslint-config) 进行配置。

项目内集成了以下几种代码校验工具：

-   [ESLint (opens new window)](https://eslint.org/)：用于 JavaScript/TypeScript 代码检查
-   [Prettier (opens new window)](https://prettier.io/)：用于代码格式化（集成在 ESLint 中）

我们可以使用 IDE 自带的 Linter 功能，实现代码的格式化（自动检查和修复）。

友情提示：

如果你想使用 Prettier 插件，可参考 [《代码格式化（Prettier）》](//vue3/format) 文档。

## 1. JetBrains 端

参考 [《JetBrains 官方文档》 (opens new window)](https://www.jetbrains.com/help/idea/linters.html) 操作即可。

① 【手动修复】右键文件，选择 `Fix ESLint Problems` 即可。如下图所示：

![JetBrains 端 ESLint 自动修复](https://doc.iocoder.cn/img/admin-uniapp/%E4%BB%A3%E7%A0%81%E6%A0%BC%E5%BC%8F%E5%8C%96/JetBrains-fix.png)

② 【自动修复】可在 JetBrains 设置界面的 ESLint 选项中，勾选上 `Run eslint --fix on save` 选项，如下图所示：

![JetBrains 端 ESLint 自动修复 - 设置](https://doc.iocoder.cn/img/Vben5/%E4%BB%A3%E7%A0%81%E6%A0%BC%E5%BC%8F%E5%8C%96/JetBrains-autofix.png)

之后，保存页面，页面代码自动格式化。

## 2. VS Code 端

参考 [《VS Code 官方文档》 (opens new window)](https://code.visualstudio.com/docs/languages/javascript#_linters) 操作即可。

【自动修复】打开 VS Code 配置，搜索 save 后，勾选上 `Format On Save` 选项。如下图所示：

![VS Code 端自动格式化 - 设置](https://doc.iocoder.cn/img/admin-uniapp/%E4%BB%A3%E7%A0%81%E6%A0%BC%E5%BC%8F%E5%8C%96/VSCode%E8%87%AA%E5%8A%A8%E4%BF%9D%E5%AD%98.png)

之后，保存页面，页面代码自动格式化。

[

IDE 调试

](/admin-uniapp/debugger/)[

运行发布

](/admin-uniapp/deploy/)

---

## 📚 相关文档

- [IDE 调试 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_IDE-调试.md) (同章节)
- [图标、主题、国际化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_图标、主题、国际化.md) (同章节)
- [字典数据 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_字典数据.md) (同章节)
- [系统组件 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_系统组件.md) (同章节)
- [菜单路由 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_菜单路由.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#前端手册-admin-uniapp)

</div>
