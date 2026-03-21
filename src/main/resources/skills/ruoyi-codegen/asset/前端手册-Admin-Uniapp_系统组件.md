# 系统组件 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 前端手册-Admin-Uniapp
> - **文档大小**: 5.07 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. Wot Design Uni 组件库](#1-wot-design-uni-组件库)
- [1. DictTag 字典标签](#1-dicttag-字典标签)
- [2. UserPicker 用户选择器](#2-userpicker-用户选择器)
- [3. 文件上传](#3-文件上传)
  - [3.1 uploadFile API](#31-uploadfile-api)
  - [3.2 uploadFileFromPath 工具](#32-uploadfilefrompath-工具)
  - [3.3 useUpload Hook](#33-useupload-hook)

---





**原文链接**: https://doc.iocoder.cn/admin-uniapp/components/

**所属章节**: 前端手册 Admin Uniapp

**爬取时间**: 2026/3/3 15:08:30

---

-   [](/ "首页")
-   开发指南
-   前端手册 Admin Uniapp

[芋道源码](https://www.iocoder.cn "作者")

[2026-01-02](javascript:;)

目录

[1\. Wot Design Uni 组件库](#_1-wot-design-uni-组件库)

[1\. DictTag 字典标签](#_1-dicttag-字典标签)

[2\. UserPicker 用户选择器](#_2-userpicker-用户选择器)

[3\. 文件上传](#_3-文件上传)

[3.1 uploadFile API](#_3-1-uploadfile-api)

[3.2 uploadFileFromPath 工具](#_3-2-uploadfilefrompath-工具)

[3.3 useUpload Hook](#_3-3-useupload-hook)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)系统组件

本小节，介绍项目中封装的系统组件。

## 1. Wot Design Uni 组件库

项目使用 [Wot Design Uni (opens new window)](https://wot-ui.cn/) 作为基础 UI 组件库，提供了丰富的移动端组件。

大多数情况下，你可以直接使用 Wot Design Uni 提供的组件来构建界面。

## 1. DictTag 字典标签

字典标签组件，用于展示字典数据的标签，支持颜色高亮。

-   源码位置：[src/components/dict-tag/dict-tag.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/components/dict-tag/dict-tag.vue)
-   详细文档：[字典数据](/admin-uniapp/dict/)

## 2. UserPicker 用户选择器

用户选择器组件，支持单选和多选模式，内置搜索过滤功能。

-   源码位置：[src/components/system-select/user-picker.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/components/system-select/user-picker.vue)
-   实战案例（单选）：[src/pages-system/dept/form/index.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/dept/form/index.vue) 的 `formData.leaderUserId` 部分
-   实战案例（多选）：[src/pages-bpm/user-group/form/index.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-bpm/user-group/form/index.vue) 的 `formData.userIds` 部分
-   实战案例（获取昵称）：[src/pages-system/operate-log/modules/search-form.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-system/operate-log/modules/search-form.vue) 的 `getUserNickname` 部分

## 3. 文件上传

项目提供了两种文件上传方式：

### 3.1 uploadFile API

直接调用上传 API，适用于简单场景。

-   源码位置：[src/api/infra/file/index.ts (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/api/infra/file/index.ts)
-   实战案例：[src/pages-infra/file/components/file-list.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-infra/file/components/file-list.vue) 的 `handleUpload` 部分

### 3.2 uploadFileFromPath 工具

支持前端直连上传（S3）和后端上传两种模式，通过环境变量 `VITE_UPLOAD_TYPE` 配置。

-   源码位置：[src/utils/uploadFile.ts (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/utils/uploadFile.ts)
-   实战案例：[src/pages-core/user/profile/index.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/pages-core/user/profile/index.vue) 的 `uploadFileFromPath` 部分

```
import { uploadFileFromPath } from '@/utils/uploadFile'

// 上传文件到指定目录
const url = await uploadFileFromPath(filePath, 'avatar')
```

### 3.3 useUpload Hook

[src/hooks/useUpload.ts (opens new window)](https://github.com/yudaocode/yudao-ui-admin-uniapp/blob/master/src/hooks/useUpload.ts) 封装了文件选择、校验、上传的完整流程。

```
import useUpload from '@/hooks/useUpload'

const { loading, data, run } = useUpload({
  fileType: 'image',
  maxSize: 5 * 1024 * 1024,
  success: (url) => console.log('上传成功:', url),
})

// 触发选择和上传
run()
```

[

字典数据

](/admin-uniapp/dict/)[

通用方法

](/admin-uniapp/util/)

---

## 📚 相关文档

- [IDE 调试 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_IDE-调试.md) (同章节)
- [代码格式化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_代码格式化.md) (同章节)
- [图标、主题、国际化 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_图标、主题、国际化.md) (同章节)
- [字典数据 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_字典数据.md) (同章节)
- [菜单路由 | ruoyi-vue-pro 开发指南](前端手册-Admin-Uniapp_菜单路由.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#前端手册-admin-uniapp)

</div>
