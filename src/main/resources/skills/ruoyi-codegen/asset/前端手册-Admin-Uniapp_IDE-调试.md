# IDE 调试 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 前端手册-Admin-Uniapp
> - **文档大小**: 3.10 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. IDEA 调试](#1-idea-调试)
- [2. VS Code 调试](#2-vs-code-调试)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/debugger/

**所属章节**: 前端手册 Admin Uniapp

**爬取时间**: 2026/3/3 15:08:33

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2026-01-01](javascript:;)

目录

[1\. IDEA 调试](#_1-idea-调试)

[2\. VS Code 调试](#_2-vs-code-调试)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABGpJREFUSA3tVVtoXFUU3fvOI53UlmCaKIFmwEhsE7QK0ipFEdHEKpXaZGrp15SINsXUWvBDpBgQRKi0+KKoFeJHfZA+ED9KKoIU2gYD9UejTW4rVIzm0VSTziPzuNu1z507dibTTjL4U/DAzLn3nL3X2o91ziX6f9wMFdh6Jvbm9nNSV0msViVO6tN1Rm7NMu2OpeJ9lWBUTDxrJbYTS0hInuwciu9eLHlFxCLCZEk3MegsJmZ5K/JD6t7FkFdEvGUo1g7qJoG3MHImqRIn8/nzY1K9UPKKiJmtnUqHVE3Gbuay6vJE/N2FEmuxFjW2nUuE0yQXRRxLiTUAzs36zhZvOXJPdX850EVnnLZkB8prodQoM5JGj7Xk2mvC7JB8tG04Ef5PiXtG0UtxupRQSfTnBoCy554x18yJHI6I+G5Eru4LHmPJZEQsrvPUbMiA8G/WgMK7w7I+ez7++o2ANfbrjvaOl1tFMs+htG3IrZH9/hDX1Pr8Tc0UvH8tcX29KzAgIGcEkINyW5BF9x891hw6VYqgJHEk0huccS7vh3C6gTiODL+26huuBtbct8eZnqLML8PkxGYpuPZBqtqwkSjgc4mB5gbgig5i+y0UDK35LMxXisn9xQtK+nd26gTIHsHe/oblK/b29fUmN/8Y+9jAQrnBp56m1LcDlDp9irKTExSKduXJVWSqdBMA08pEJnEIOB3FPPMybu/oeV8zFeYN3xx576Q6RH+VmplE4ncQV5v+5rzSoyOU7PuEAg8g803PwBJ0CExno/jcMbN8tONYeOmHiuUNryvm3fRUy4tMPVLdAGkUhNWuggGrJcXPv+ouCjz0MKUHz1J2/E8IC9nqTabcxgaBYM0hPhD5Y65FsbxRQKxCQrDjDctW7PUM3HuZunFyifSAqEfuzCp48Il24luWUWZoyJCaPR82jE0+kFA643wRFVni4RYSq3ohJO2pZ7B5dO4xkDWbEpossJPLSrPjYID8rS2UHTlvyNxqIGsg674XJJ7vnh5L7PNwC4hh2sjCI96mzszOTpxLF0T7l88Yz7lAuK6OnL8gXLOnTvpzSb22YG8W7us3jSebFHeeqnXRG1vt+MoUM84LQIBmMsCTAcOauTh0T0l0neQK7m2bLMt2mGxU3HYssS0J2cdv5wljlPsrIuZLAG/2DOZIXgCYT8uMGZN+e2kSirfxZOPCsC0f24nTZzspnVn9VePS1Z5vubmAGGXG8ZFno9Hel0yfA5ZPhF7Dh972BQJ2qCpgH67lmWtBYbvk6sz02wjky2vXyz0XErP/kFB619js1BtwfOV4OPRqOQBjy3Qbk18vigUPPSD5ceHnwck7W9bhAqZdd7SuG7w4/P2F/GaJh8c7e9qgow+Q7cGBo+98WsLkuktFqiZabtXuQTu/Y5ETbR0v7tNSFnvrmu6pjdoan2KjMu8q/Hmj1EfCO2ZGfEIbIXKUlw8qaX9/b2oeSJmFksSeT/Fn0V3nSypChh4Gjh74ybO9aeZ/AN2dwciu2/MhAAAAAElFTkSuQmCC)IDE 调试

除了使用 Chrome 调试 JS 代码外，我们也可以使用 IDEA / WebStorm 或 VS Code 进行代码的调试。

## 1. IDEA 调试

友情提示：WebStorm 也支持。

① 使用 IDEA debug 功能，将前端项目运行起来。具体步骤如下：

![IDEA debug 前端项目](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/idea-debug.png)

② 点击链接，Windows 需按住 Ctrl + Shift + 鼠标左键，MacOS 需要按住 Shift + Command + 鼠标左键。如下图所示：

![点击链接](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/idea-01.png)

③ 点击后，会跳出一个独立的 Chrome 窗口。如下图所示：

![独立的 Chrome 窗口](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/idea-02.png)

④ 打个断点，例如说 `src/api/login.ts` 的登录接口。如下图所示：

![打个断点](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/idea-03.png)

⑤ 使用管理后台进行登录，可以看到成功进入断点。如下图所示：

![进入断点](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/idea-04.png)

## 2. VS Code 调试

友情提示：Cursor、CatPaw、Windsurf、Kiro 等也支持。

① 使用 npm 命令将前端项目运行起来，例如说 `npm run dev:h5`。耐心等待项目启动成功~

② 点击 VS Code 左侧的运行和调试，然后启动 Launch，之后会跳出一个独立的浏览器窗口。如下图所示：

![独立的浏览器窗口](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/vscode-01.png)

③ 打个断点，例如说 `src/api/login.ts` 的登录接口。如下图所示：

![打个断点](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/vscode-02.png)

④ 使用管理后台进行登录，可以看到成功进入断点。如下图所示：

![进入断点](https://doc.iocoder.cn/img/admin-uniapp/IDE%E8%B0%83%E8%AF%95/vscode-03.png)

[

通用方法

](/admin-uniapp/util/)[

代码格式化

](/admin-uniapp/format/)

---

## 📚 相关文档

- [代码格式化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_代码格式化.md) (同章节)
- [图标、主题、国际化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_图标、主题、国际化.md) (同章节)
- [字典数据 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_字典数据.md) (同章节)
- [系统组件 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_系统组件.md) (同章节)
- [菜单路由 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_菜单路由.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#前端手册-admin-uniapp)

</div>
