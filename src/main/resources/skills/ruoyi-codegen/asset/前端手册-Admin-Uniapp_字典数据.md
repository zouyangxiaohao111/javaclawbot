# 字典数据 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 前端手册-Admin-Uniapp
> - **文档大小**: 6.76 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 全局缓存](#1-全局缓存)
- [2. DICT\_TYPE](#2-dict_type)
- [3. DictTag 字典标签](#3-dicttag-字典标签)
  - [3.1 基础用法](#31-基础用法)
  - [3.2 组件属性](#32-组件属性)
- [4. 字典工具类](#4-字典工具类)
  - [4.1 getDictLabel](#41-getdictlabel)
  - [4.2 getDictObj](#42-getdictobj)
  - [4.3 getDictOptions](#43-getdictoptions)
- [5. 实战案例](#5-实战案例)
  - [5.1 列表展示](#51-列表展示)
  - [5.2 表单项](#52-表单项)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/dict/

**所属章节**: 前端手册 Admin Uniapp

**爬取时间**: 2026/3/3 15:08:28

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2026-01-02](javascript:;)

目录

[1\. 全局缓存](#_1-全局缓存)

[2\. DICT\_TYPE](#_2-dict-type)

[3\. DictTag 字典标签](#_3-dicttag-字典标签)

[3.1 基础用法](#_3-1-基础用法)

[3.2 组件属性](#_3-2-组件属性)

[4\. 字典工具类](#_4-字典工具类)

[4.1 getDictLabel](#_4-1-getdictlabel)

[4.2 getDictObj](#_4-2-getdictobj)

[4.3 getDictOptions](#_4-3-getdictoptions)

[5\. 实战案例](#_5-实战案例)

[5.1 列表展示](#_5-1-列表展示)

[5.2 表单项](#_5-2-表单项)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)字典数据

本小节，讲解前端如何使用 \[系统管理 -> 字典管理\] 菜单的字典数据，例如说字典数据的下拉框、单选按钮、高亮展示等等。

> ![字典管理](https://doc.iocoder.cn/img/Vben5/%E5%AD%97%E5%85%B8%E6%95%B0%E6%8D%AE/01.png)

## 1. 全局缓存

用户登录成功后，前端会从后端获取到全量的字典数据，缓存在 Pinia Store 中，可见 [src/store/dict.ts (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/store/dict.ts) 源码。

这样，前端在使用到字典数据时，无需重复请求后端，提升用户体验。

不过，缓存暂时未提供刷新，所以在字典数据发生变化时，需要用户刷新浏览器，进行重新加载。

## 2. DICT\_TYPE

在 [`dict.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/utils/dict.ts) 文件中，使用 `DICT_TYPE` 枚举了字典的 KEY。

```
export enum DICT_TYPE {
  // ========== 系统模块 ==========
  SYSTEM_USER_SEX = 'system_user_sex',
  COMMON_STATUS = 'common_status',
  // ... 其他字典类型
}
```

后续如果有新的字典 KEY，需要你自己进行添加。

## 3. DictTag 字典标签

[src/components/dict-tag/dict-tag.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/components/dict-tag/dict-tag.vue) 提供 `<DictTag />` 组件，翻译字段对应的字典展示文本，并根据 `colorType`、`cssClass` 进行高亮。

### 3.1 基础用法

```
<template>
  <!--
    type: 字典类型
    value: 字典值
  -->
  <DictTag :type="DICT_TYPE.COMMON_STATUS" :value="item.status" />
</template>

<script setup lang="ts">
import { DICT_TYPE } from '@/utils/dict'
</script>
```

### 3.2 组件属性

属性

说明

类型

默认值

type

字典类型

string

\-

value

字典值

any

\-

plain

是否镂空

boolean

true

## 4. 字典工具类

在 [`useDict.ts` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/hooks/useDict.ts) 文件中，提供了字典工具类。

### 4.1 getDictLabel

获取字典标签文本。

```
import { getDictLabel } from '@/hooks/useDict'
import { DICT_TYPE } from '@/utils/dict'

// 获取状态标签，如 "开启"、"关闭"
const label = getDictLabel(DICT_TYPE.COMMON_STATUS, 0)
```

### 4.2 getDictObj

获取字典对象，包含 label、value、colorType、cssClass。

```
import { getDictObj } from '@/hooks/useDict'
import { DICT_TYPE } from '@/utils/dict'

const dictObj = getDictObj(DICT_TYPE.COMMON_STATUS, 0)
// { label: '开启', value: '0', colorType: 'success', cssClass: '' }
```

### 4.3 getDictOptions

① 获取字典数组，用于 picker、radio 等组件。

```
import { getDictOptions } from '@/hooks/useDict'
import { DICT_TYPE } from '@/utils/dict'

// 获取字典选项，默认 string 类型
const options = getDictOptions(DICT_TYPE.COMMON_STATUS)
// [{ label: '开启', value: '0', colorType: 'success' }, { label: '关闭', value: '1', colorType: 'danger' }]

// 获取 number 类型的字典选项
const numberOptions = getDictOptions(DICT_TYPE.COMMON_STATUS, 'number')
// [{ label: '开启', value: 0, colorType: 'success' }, { label: '关闭', value: 1, colorType: 'danger' }]
```

② 也可以使用快捷方法，获取不同类型的字典选项。

```
import { getIntDictOptions, getStrDictOptions, getBoolDictOptions } from '@/hooks/useDict'

// 获取 number 类型的字典选项
const intOptions = getIntDictOptions(DICT_TYPE.COMMON_STATUS)

// 获取 string 类型的字典选项
const strOptions = getStrDictOptions(DICT_TYPE.COMMON_STATUS)

// 获取 boolean 类型的字典选项
const boolOptions = getBoolDictOptions(DICT_TYPE.SYSTEM_YES_NO)
```

## 5. 实战案例

### 5.1 列表展示

在列表中使用 `<DictTag />` 展示字典标签：

```
<template>
  <view v-for="item in list" :key="item.id" class="list-item">
    <text>{{ item.name }}</text>
    <DictTag :type="DICT_TYPE.COMMON_STATUS" :value="item.status" />
  </view>
</template>

<script setup lang="ts">
import { DICT_TYPE } from '@/utils/dict'
</script>
```

### 5.2 表单项

① 使用 `getDictOptions` 配合 `wd-picker` 实现字典选择：

```
<template>
  <wd-picker
    v-model="formData.status"
    label="状态"
    :columns="statusOptions"
    label-key="label"
    value-key="value"
  />
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getDictOptions } from '@/hooks/useDict'
import { DICT_TYPE } from '@/utils/dict'

const formData = ref({ status: '' })
const statusOptions = computed(() => getDictOptions(DICT_TYPE.COMMON_STATUS))
</script>
```

② 使用 `getDictOptions` 配合 `wd-radio-group` 实现字典单选：

```
<template>
  <wd-radio-group v-model="formData.sex" shape="button">
    <wd-radio
      v-for="dict in sexOptions"
      :key="dict.value"
      :value="dict.value"
    >
      {{ dict.label }}
    </wd-radio>
  </wd-radio-group>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { getIntDictOptions } from '@/hooks/useDict'
import { DICT_TYPE } from '@/utils/dict'

const formData = ref({ sex: 0 })
const sexOptions = computed(() => getIntDictOptions(DICT_TYPE.SYSTEM_USER_SEX))
</script>
```

[

图标、主题、国际化

](/admin-uniapp/icon-theme/)[

系统组件

](/admin-uniapp/components/)

---

## 📚 相关文档

- [IDE 调试 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_IDE-调试.md) (同章节)
- [代码格式化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_代码格式化.md) (同章节)
- [图标、主题、国际化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_图标、主题、国际化.md) (同章节)
- [系统组件 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_系统组件.md) (同章节)
- [菜单路由 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_菜单路由.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#前端手册-admin-uniapp)

</div>
