# 通用方法 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 前端手册-Admin-Uniapp
> - **文档大小**: 4.49 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 工具类](#1-工具类)
- [2. Hooks](#2-hooks)
- [3. 枚举类](#3-枚举类)
  - [3.1 字典枚举](#31-字典枚举)
  - [3.2 业务枚举](#32-业务枚举)
- [4. 缓存配置](#4-缓存配置)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/util/

**所属章节**: 前端手册 Admin Uniapp

**爬取时间**: 2026/3/3 15:08:31

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2026-01-02](javascript:;)

目录

[1\. 工具类](#_1-工具类)

[2\. Hooks](#_2-hooks)

[3\. 枚举类](#_3-枚举类)

[3.1 字典枚举](#_3-1-字典枚举)

[3.2 业务枚举](#_3-2-业务枚举)

[4\. 缓存配置](#_4-缓存配置)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABGpJREFUSA3tVVtoXFUU3fvOI53UlmCaKIFmwEhsE7QK0ipFEdHEKpXaZGrp15SINsXUWvBDpBgQRKi0+KKoFeJHfZA+ED9KKoIU2gYD9UejTW4rVIzm0VSTziPzuNu1z507dibTTjL4U/DAzLn3nL3X2o91ziX6f9wMFdh6Jvbm9nNSV0msViVO6tN1Rm7NMu2OpeJ9lWBUTDxrJbYTS0hInuwciu9eLHlFxCLCZEk3MegsJmZ5K/JD6t7FkFdEvGUo1g7qJoG3MHImqRIn8/nzY1K9UPKKiJmtnUqHVE3Gbuay6vJE/N2FEmuxFjW2nUuE0yQXRRxLiTUAzs36zhZvOXJPdX850EVnnLZkB8prodQoM5JGj7Xk2mvC7JB8tG04Ef5PiXtG0UtxupRQSfTnBoCy554x18yJHI6I+G5Eru4LHmPJZEQsrvPUbMiA8G/WgMK7w7I+ez7++o2ANfbrjvaOl1tFMs+htG3IrZH9/hDX1Pr8Tc0UvH8tcX29KzAgIGcEkINyW5BF9x891hw6VYqgJHEk0huccS7vh3C6gTiODL+26huuBtbct8eZnqLML8PkxGYpuPZBqtqwkSjgc4mB5gbgig5i+y0UDK35LMxXisn9xQtK+nd26gTIHsHe/oblK/b29fUmN/8Y+9jAQrnBp56m1LcDlDp9irKTExSKduXJVWSqdBMA08pEJnEIOB3FPPMybu/oeV8zFeYN3xx576Q6RH+VmplE4ncQV5v+5rzSoyOU7PuEAg8g803PwBJ0CExno/jcMbN8tONYeOmHiuUNryvm3fRUy4tMPVLdAGkUhNWuggGrJcXPv+ouCjz0MKUHz1J2/E8IC9nqTabcxgaBYM0hPhD5Y65FsbxRQKxCQrDjDctW7PUM3HuZunFyifSAqEfuzCp48Il24luWUWZoyJCaPR82jE0+kFA643wRFVni4RYSq3ohJO2pZ7B5dO4xkDWbEpossJPLSrPjYID8rS2UHTlvyNxqIGsg674XJJ7vnh5L7PNwC4hh2sjCI96mzszOTpxLF0T7l88Yz7lAuK6OnL8gXLOnTvpzSb22YG8W7us3jSebFHeeqnXRG1vt+MoUM84LQIBmMsCTAcOauTh0T0l0neQK7m2bLMt2mGxU3HYssS0J2cdv5wljlPsrIuZLAG/2DOZIXgCYT8uMGZN+e2kSirfxZOPCsC0f24nTZzspnVn9VePS1Z5vubmAGGXG8ZFno9Hel0yfA5ZPhF7Dh972BQJ2qCpgH67lmWtBYbvk6sz02wjky2vXyz0XErP/kFB619js1BtwfOV4OPRqOQBjy3Qbk18vigUPPSD5ceHnwck7W9bhAqZdd7SuG7w4/P2F/GaJh8c7e9qgow+Q7cGBo+98WsLkuktFqiZabtXuQTu/Y5ETbR0v7tNSFnvrmu6pjdoan2KjMu8q/Hmj1EfCO2ZGfEIbIXKUlw8qaX9/b2oeSJmFksSeT/Fn0V3nSypChh4Gjh74ybO9aeZ/AN2dwciu2/MhAAAAAElFTkSuQmCC)通用方法

本小节，分享前端项目的常用方法和工具类。

## 1. 工具类

项目的工具类位于 [`src/utils` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/utils) 目录。

文件

作用

`index.ts`

通用工具（路由解析、页面跳转、环境配置等）

`date.ts`

日期格式化、相对时间

`tree.ts`

树形结构构建、查找

`constants.ts`

枚举常量导出

`debounce.ts`

防抖函数

`download.ts`

文件下载

`encrypt.ts`

加解密

`validator.ts`

表单验证

`url.ts`

URL 处理

`systemInfo.ts`

系统信息获取

`toLoginPage.ts`

跳转登录页

## 2. Hooks

项目的 Hooks 位于 [`src/hooks` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/hooks) 目录。

文件

作用

`useRequest.ts`

异步请求封装

`useScroll.ts`

分页滚动加载

`useDict.ts`

字典数据

`useAccess.ts`

权限控制

`useUpload.ts`

文件上传

## 3. 枚举类

项目在 [`src/utils/constants` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/tree/master/src/utils/constants) 目录定义了枚举类。

### 3.1 字典枚举

字典枚举定义在 [`dict-enum.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/utils/constants/dict-enum.ts) 中，对应后端字典管理的字典类型 KEY。

```
import { DICT_TYPE } from '@/utils/constants'

// 使用字典类型
DICT_TYPE.COMMON_STATUS // 'common_status'
DICT_TYPE.SYSTEM_USER_SEX // 'system_user_sex'
DICT_TYPE.BPM_TASK_STATUS // 'bpm_task_status'
```

### 3.2 业务枚举

业务枚举用于前端业务逻辑判断，避免"魔法值"。

文件

模块

`biz-system-enum.ts`

系统模块（状态、菜单类型、角色类型等）

`biz-infra-enum.ts`

基础设施模块

`biz-bpm-enum.ts`

工作流模块

```
import { CommonStatusEnum, SystemMenuTypeEnum } from '@/utils/constants'

// 通用状态
if (status === CommonStatusEnum.ENABLE) { /* 开启 */ }
if (status === CommonStatusEnum.DISABLE) { /* 禁用 */ }

// 菜单类型判断
if (menuType === SystemMenuTypeEnum.DIR) { /* 目录 */ }
if (menuType === SystemMenuTypeEnum.MENU) { /* 菜单 */ }
if (menuType === SystemMenuTypeEnum.BUTTON) { /* 按钮 */ }
```

## 4. 缓存配置

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

持久化数据存储在：

-   H5：localStorage
-   小程序：uni.setStorageSync
-   App：uni.setStorageSync

[

系统组件

](/admin-uniapp/components/)[

IDE 调试

](/admin-uniapp/debugger/)

---

## 📚 相关文档

- [IDE 调试 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_IDE-调试.md) (同章节)
- [代码格式化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_代码格式化.md) (同章节)
- [图标、主题、国际化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_图标、主题、国际化.md) (同章节)
- [字典数据 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_字典数据.md) (同章节)
- [系统组件 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_系统组件.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#前端手册-admin-uniapp)

</div>
