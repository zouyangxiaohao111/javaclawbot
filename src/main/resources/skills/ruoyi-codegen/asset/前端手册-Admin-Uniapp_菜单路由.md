# 菜单路由 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 前端手册-Admin-Uniapp
> - **文档大小**: 7.93 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 路由](#1-路由)
  - [1.1 路由配置](#11-路由配置)
  - [1.2 路由跳转](#12-路由跳转)
  - [1.3 登录拦截](#13-登录拦截)
- [2. 首页菜单](#2-首页菜单)
  - [2.1 菜单数据结构](#21-菜单数据结构)
  - [2.2 菜单配置示例](#22-菜单配置示例)
  - [2.3 仅 PC 端页面](#23-仅-pc-端页面)
- [3. 权限控制](#3-权限控制)
  - [3.1 useAccess Hook](#31-useaccess-hook)
  - [3.2 权限数据来源](#32-权限数据来源)
  - [3.3 菜单权限过滤](#33-菜单权限过滤)
  - [3.4 按钮权限控制](#34-按钮权限控制)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/route/

**所属章节**: 前端手册 Admin Uniapp

**爬取时间**: 2026/3/3 15:08:25

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2026-01-02](javascript:;)

目录

[1\. 路由](#_1-路由)

[1.1 路由配置](#_1-1-路由配置)

[1.2 路由跳转](#_1-2-路由跳转)

[1.3 登录拦截](#_1-3-登录拦截)

[2\. 首页菜单](#_2-首页菜单)

[2.1 菜单数据结构](#_2-1-菜单数据结构)

[2.2 菜单配置示例](#_2-2-菜单配置示例)

[2.3 仅 PC 端页面](#_2-3-仅-pc-端页面)

[3\. 权限控制](#_3-权限控制)

[3.1 useAccess Hook](#_3-1-useaccess-hook)

[3.2 权限数据来源](#_3-2-权限数据来源)

[3.3 菜单权限过滤](#_3-3-菜单权限过滤)

[3.4 按钮权限控制](#_3-4-按钮权限控制)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)菜单路由

## 1. 路由

项目基于 [unibest (opens new window)](https://unibest.tech/) 框架，使用约定式路由，页面文件放在 `src/pages` 或 `src/pages-xxx` 目录下，会自动生成路由配置。

官方文档

详细的路由配置和使用方式，请参考：[《unibest 官方文档 —— uni 插件》 (opens new window)](https://unibest.tech/base/3-plugin)

### 1.1 路由配置

使用 `definePage` 宏配置页面信息：

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

### 1.2 路由跳转

① uniapp 提供了多种路由跳转方式，可见 [《uniapp 官方文档 —— 页面和路由》 (opens new window)](https://uniapp.dcloud.net.cn/api/router.html) 文档。

② 项目封装了增强的返回方法 `navigateBackPlus`，当不存在上一页时会跳转到首页：

```
import { navigateBackPlus } from '@/utils'

// 返回上一页，如果不存在则跳转到首页
navigateBackPlus()

// 返回上一页，如果不存在则跳转到指定页面
navigateBackPlus('/pages-system/user/index')
```

### 1.3 登录拦截

项目默认在 [`src/router/config.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/router/config.ts) 开启登录拦截，未登录时访问需要登录的页面，会自动跳转到登录页。

可通过以下两种方式，配置页面不需要登录拦截：

① **方式一：在 `definePage` 中配置**

```
<script lang="ts" setup>
definePage({
  excludeLoginPath: true,  // 排除登录拦截
})
</script>
```

② **方式二：在 `EXCLUDE_LOGIN_PATH_LIST` 中配置**

```
// src/router/config.ts
export const EXCLUDE_LOGIN_PATH_LIST = [
  '/pages/xxx/index',
  '/pages-sub/xxx/index',
]
```

## 2. 首页菜单

首页的菜单列表，定义在 [`src/pages/index/index.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages/index/index.ts) 文件中。

![首页菜单](https://doc.iocoder.cn/img/Admin-Uniapp/%E8%8F%9C%E5%8D%95%E8%B7%AF%E7%94%B1/%E9%A6%96%E9%A1%B5%E8%8F%9C%E5%8D%95.png)

### 2.1 菜单数据结构

```
/** 菜单项类型 */
export interface MenuItem {
  key: string           // 菜单唯一标识
  name: string          // 菜单名称
  icon: string          // 菜单图标（Wot UI 图标名）
  url?: string          // 跳转路径
  iconColor?: string    // 图标颜色
  enabled?: boolean     // 是否启用（默认 true）
  permission?: string   // 权限标识
}

/** 菜单分组类型 */
export interface MenuGroup {
  key: string           // 分组唯一标识
  name: string          // 分组名称
  menus: MenuItem[]     // 分组下的菜单列表
}
```

### 2.2 菜单配置示例

```
const menuGroupsData: MenuGroup[] = [
  {
    key: 'system',
    name: '系统管理',
    menus: [
      {
        key: 'user',
        name: '用户管理',
        icon: 'user',
        url: '/pages-system/user/index',
        iconColor: '#1890ff',
        permission: 'system:user:list',
      },
      {
        key: 'role',
        name: '角色管理',
        icon: 'secured',
        url: '/pages-system/role/index',
        iconColor: '#2f54eb',
        permission: 'system:role:query',
      },
      // ... 更多菜单
    ],
  },
  // ... 更多分组
]
```

疑问：首页菜单，是否支持管理后台配置？

短期来看，暂时需求量不大。

目前先通过 PC 端的管理后台 \[系统管理 -> 菜单管理\] 功能，里面的 `permission` 字段，来控制「移动端 - 首页菜单」的显示和隐藏即可。

### 2.3 仅 PC 端页面

部分功能（如代码生成、表单构建等）仅支持 PC 端访问，配置 `url` 为 `ONLY_PC_PAGE` 常量：

```
import { ONLY_PC_PAGE } from '@/router/config'

{
  key: 'codegen',
  name: '代码生成',
  icon: 'code',
  url: ONLY_PC_PAGE,  // 点击后跳转到"仅 PC 端访问"提示页
  iconColor: '#52c41a',
}
```

## 3. 权限控制

前端通过权限控制，隐藏用户没有权限的菜单和按钮，实现功能级别的权限。

友情提示

前端的权限控制，主要是提升用户体验，避免操作后发现没有权限。最终在请求到后端时，还是会进行一次权限的校验。

### 3.1 useAccess Hook

项目提供了 [`useAccess` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/hooks/useAccess.ts) Hook 进行权限判断：

```
import { useAccess } from '@/hooks/useAccess'

const { hasAccessByCodes, hasAccessByRoles } = useAccess()

// 基于权限码判断
if (hasAccessByCodes(['system:user:create'])) {
  // 有新增用户权限
}

// 基于角色判断
if (hasAccessByRoles(['admin'])) {
  // 是管理员角色
}
```

### 3.2 权限数据来源

用户的权限数据在登录成功后，通过 [`/admin-api/system/auth/get-permission-info` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/auth/AuthController.java#L107-L110) 接口获取，存储在 `userStore` 中：

```
import { useUserStore } from '@/store/user'

const userStore = useUserStore()

// 用户角色列表
console.log(userStore.roles)       // ['admin', 'common']

// 用户权限列表
console.log(userStore.permissions) // ['system:user:list', 'system:user:create', ...]
```

### 3.3 菜单权限过滤

首页菜单会自动根据用户权限进行过滤，没有权限的菜单项不会显示。

实现代码：[`src/pages/index/index.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages/index/index.ts) 的 `getMenuGroups` 方法：

```
// src/pages/index/index.ts
export function getMenuGroups(): MenuGroup[] {
  const { hasAccessByCodes } = useAccess()
  return menuGroupsData
    .map(group => ({
      ...group,
      // 过滤掉没有权限的菜单项
      menus: group.menus.filter((menu) => {
        // 没有配置权限的菜单项默认展示
        if (!menu.permission) {
          return true
        }
        return hasAccessByCodes([menu.permission])
      }),
    }))
    // 过滤掉没有菜单项的分组
    .filter(group => group.menus.length > 0)
}
```

### 3.4 按钮权限控制

在页面中可以使用 `v-if` 结合 `hasAccessByCodes` 或 `hasAccessByRoles` 控制按钮显示。

实战案例：

-   列表页的新增按钮：[`src/pages-system/user/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/user/index.vue)
-   详情页的编辑、删除按钮[`src/pages-system/user/detail/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/user/detail/index.vue)

```
<template>
  <wd-button
    v-if="hasAccessByCodes(['system:user:create'])"
    type="primary"
    @click="handleCreate"
  >
    新增用户
  </wd-button>
</template>

<script lang="ts" setup>
import { useAccess } from '@/hooks/useAccess'

const { hasAccessByCodes } = useAccess()
</script>
```

[

开发规范

](/admin-uniapp/dev-spec/)[

图标、主题、国际化

](/admin-uniapp/icon-theme/)

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
