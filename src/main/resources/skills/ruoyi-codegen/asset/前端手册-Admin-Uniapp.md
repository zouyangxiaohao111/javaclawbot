# 开发规范 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: Root
> - **文档大小**: 8.22 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [0. 实战案例](#0-实战案例)
  - [0.1 分页列表](#01-分页列表)
  - [0.2 树形列表](#02-树形列表)
- [1. pages 页面（view）](#1-pages-页面view)
  - [1.1 页面组织](#11-页面组织)
  - [1.2 页面结构](#12-页面结构)
  - [1.3 页面配置](#13-页面配置)
- [2. api 请求](#2-api-请求)
  - [2.1 请求封装](#21-请求封装)
- [3. component 组件](#3-component-组件)
  - [3.1 全局组件](#31-全局组件)
  - [3.2 页面组件](#32-页面组件)
- [4. style 样式](#4-style-样式)
- [5. store 状态管理](#5-store-状态管理)
- [6. 常见问题](#6-常见问题)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/dev-spec/

**所属章节**: Root

**爬取时间**: 2026/3/3 15:01:32

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2025-01-02](javascript:;)

目录

[0\. 实战案例](#_0-实战案例)

[0.1 分页列表](#_0-1-分页列表)

[0.2 树形列表](#_0-2-树形列表)

[1\. pages 页面（view）](#_1-pages-页面-view)

[1.1 页面组织](#_1-1-页面组织)

[1.2 页面结构](#_1-2-页面结构)

[1.3 页面配置](#_1-3-页面配置)

[2\. api 请求](#_2-api-请求)

[2.1 请求封装](#_2-1-请求封装)

[3\. component 组件](#_3-component-组件)

[3.1 全局组件](#_3-1-全局组件)

[3.2 页面组件](#_3-2-页面组件)

[4\. style 样式](#_4-style-样式)

[5\. store 状态管理](#_5-store-状态管理)

[6\. 常见问题](#_6-常见问题)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)开发规范

本项目基于 [unibest (opens new window)](https://unibest.tech/) 作为模版，采用 uniapp + Vue 3 + TypeScript + Vite 技术栈，使用 [Wot UI (opens new window)](https://wot-ui.cn/) 库。

## 0. 实战案例

本小节，提供大家开发移动端功能时，最常用的分页列表页面、树形页面的实战案例。

### 0.1 分页列表

可参考 \[系统管理 -> 岗位管理\] 功能：

-   API 接口：[`/src/api/system/post/index.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/api/system/post/index.ts)
-   列表页面：[`/src/pages-system/post/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/post/index.vue)
-   详情页面：[`/src/pages-system/post/detail/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/post/detail/index.vue)
-   表单页面：[`/src/pages-system/post/form/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/post/form/index.vue)

### 0.2 树形列表

可参考 \[系统管理 -> 部门管理\] 功能：

-   API 接口：[`/src/api/system/dept/index.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/api/system/dept/index.ts)
-   列表页面：[`/src/pages-system/dept/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/dept/index.vue)
-   详情页面：[`/src/pages-system/dept/detail/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/dept/detail/index.vue)
-   表单页面：[`/src/pages-system/dept/form/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/dept/form/index.vue)

也可参考 \[系统管理 -> 菜单管理\] 功能，对应 [`/src/pages-system/menu/` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/menu/) 目录。

## 1. pages 页面（view）

### 1.1 页面组织

页面按照功能模块进行分包，主要分为：

目录

说明

`pages/`

主包页面，包含 Tabbar 页面（首页、工作流、通讯录、消息、我的）

`pages-core/`

核心分包，包含登录、注册、错误页等

`pages-system/`

系统管理分包

`pages-infra/`

基础设施分包

`pages-bpm/`

工作流分包

为什么要分包？

小程序有主包大小限制（2 MB），分包可以有效减小主包体积，提升首屏加载速度。

另外，在微信小程序的开发模式下，包可能会超过 1.5 MB 大小，这是正常现象，编译打包后就会恢复正常。

### 1.2 页面结构

在 [`src/pages-system` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/pages-system) 目录下，每个模块对应一个目录，它的所有功能的 `.vue` 都放在该目录里。

一般来说，一个路由对应一个 `index.vue` 文件，详情页面放在 `detail` 子目录下，表单页面放在 `form` 子目录下，页面私有组件放在 `components` 子目录下。

每个功能模块的页面结构如下：

```
pages-system/post/           # 岗位管理
├── components/              # 页面私有组件
│   └── search-form.vue      # 搜索表单组件
├── detail/                  # 详情页面
│   └── index.vue
├── form/                    # 表单页面（新增/编辑）
│   ├── components/          # 表单私有组件
│   └── index.vue
└── index.vue                # 列表页面
```

ps：其它 `src/pages-xxx` 目录下的页面结构类似。

### 1.3 页面配置

使用 `definePage` 宏配置页面信息，支持约定式路由：

```
<script lang="ts" setup>
  definePage({
    style: {
      navigationStyle: 'custom', // 自定义导航栏
      navigationBarTitleText: '', // 页面标题
    },
    excludeLoginPath: false, // 是否需要登录，默认为 false（一般情况下，不用添加）
  })
</script>
```

关于 definePage 更多的介绍，可见 [《unibest 官方文档 —— uni 插件》 (opens new window)](https://unibest.tech/base/3-plugin) 文档。

## 2. api 请求

在 [`src/api` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/api) 目录下，每个模块对应一个目录，包含该模块的所有 API 文件。

![api 目录结构](https://doc.iocoder.cn/img/admin-uniapp/%E5%BC%80%E5%8F%91%E8%A7%84%E8%8C%83/api.png)

每个 API 文件通常包含：

-   API 方法：调用 `http` 发起对后端 RESTful API 的请求
-   `interface` 类型：定义 API 的请求参数和返回结果的类型，对应后端的 VO 类型

### 2.1 请求封装

项目使用 [`src/http/http.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/http/http.ts) 基于 `uni.request` 进行请求封装，提供统一的请求方法。

官方文档

详细的请求配置和使用方式，请参考：[《unibest 官方文档 —— 请求篇》 (opens new window)](https://doc.vben.pro/guide/essentials/server.html)。

注意：项目使用的是 `简单版本http` 噢，类似 `alova 的 http` 和 `vue-query` 已经删除（控制包大小）。

请求封装中包含了以下核心功能：

-   租户支持：自动在请求头中添加 `tenant-id` 租户编号
-   访问令牌：自动在请求头中添加 `Authorization` Bearer Token
-   刷新令牌：当访问令牌过期时，自动使用 `refreshToken` 刷新令牌（双 Token 模式）
-   API 加密：支持请求数据加密和响应数据解密
-   错误处理：统一的错误消息提示和 401 未登录处理

## 3. component 组件

### 3.1 全局组件

在 [`src/components` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/components) 目录下，实现全局公共组件：

```
components/
├── dict-tag/                 # 字典标签组件
│   └── index.vue
└── system-select/            # 系统选择组件
    ├── dept-select.vue       # 部门选择
    ├── user-select.vue       # 用户选择
```

* * *

更多说明，可见 [系统组件](/admin-uniapp/component/) 文档。

### 3.2 页面组件

每个页面的私有组件，放在页面目录下的 `components` 目录：

```
pages/index/
├── components/
│   ├── banner.vue            # 轮播图组件
│   ├── menu-section.vue      # 菜单区域组件
│   └── user-header.vue       # 用户头部组件
└── index.vue
```

## 4. style 样式

项目使用 [UnoCSS (opens new window)](https://unocss.dev/) 作为原子化 CSS 解决方案，可参考如下文档：

-   [《unibest 官方文档 —— UnoCSS》 (opens new window)](https://unibest.tech/base/4-style)

## 5. store 状态管理

项目使用 [Pinia (opens new window)](https://pinia.vuejs.org/) 进行状态管理，配合 `pinia-plugin-persistedstate` 插件实现数据持久化。

Store 文件位于 [`src/store` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/store) 目录下：

```
store/
├── index.ts                  # Store 入口，统一导出
├── user.ts                   # 用户信息（用户、角色、权限）
├── token.ts                  # Token 管理（登录、登出、刷新令牌）
├── dict.ts                   # 字典数据缓存
└── theme.ts                  # 主题配置
```

官方文档

详细的 Store 使用方式，请参考：[《unibest 官方文档 —— 状态管理篇》 (opens new window)](https://unibest.tech/base/9-state) 文档。

## 6. 常见问题

-   [《unibest 官方文档 —— 常见问题》 (opens new window)](https://unibest.tech/base/14-faq)
-   [《unibest 官方文档 —— 常见问题 2》 (opens new window)](https://unibest.tech/base/15-faq)
-   [《Wot UI 官方文档 —— 常见问题》 (opens new window)](https://wot-ui.cn/guide/common-problems.html)

[

配置读取

](/vue2/config-center/)[

菜单路由

](/admin-uniapp/route/)

---

## 📚 相关文档

- [功能开启 | ruoyi-vue-pro 开发指南](CRM-手册.md) (同章节)
- [功能开启 | ruoyi-vue-pro 开发指南](ERP-手册.md) (同章节)
- [功能开启 | ruoyi-vue-pro 开发指南](IoT-物联网手册.md) (同章节)
- [ruoyi-vue-pro 开发指南](ruoyi-vue-pro-开发指南（文档更新时间：2026-3-3）.md) (同章节)
- [消息队列（内存） | ruoyi-vue-pro 开发指南](中间件手册.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#root)

</div>
