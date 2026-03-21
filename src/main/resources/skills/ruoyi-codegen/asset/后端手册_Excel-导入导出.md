# Excel 导入导出 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 13.28 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. Excel 导出](#1-excel-导出)
  - [1.1 后端导入实现](#11-后端导入实现)
  - [1.2 前端导入实现](#12-前端导入实现)
- [2. Excel 导入](#2-excel-导入)
  - [2.1 后端导入实现](#21-后端导入实现)
  - [2.2 前端导入实现](#22-前端导入实现)
- [3. 字段转换器](#3-字段转换器)
  - [3.1 DictConvert 实现](#31-dictconvert-实现)
  - [3.1 DictConvert 使用示例](#31-dictconvert-使用示例)
- [4. 更多 EasyExcel 注解](#4-更多-easyexcel-注解)
  - [4.1 `@ExcelProperty`](#41-excelproperty)
  - [4.2 `@ColumnWidth`](#42-columnwidth)
  - [4.3 `@ContentFontStyle`](#43-contentfontstyle)
  - [4.4 `@ContentLoopMerge`](#44-contentloopmerge)
  - [4.5 `@ContentRowHeight`](#45-contentrowheight)
  - [4.6 `@ContentStyle`](#46-contentstyle)
  - [4.7 `@HeadFontStyle`](#47-headfontstyle)
  - [4.8 `@HeadRowHeight`](#48-headrowheight)
  - [4.9 `@HeadStyle`](#49-headstyle)
  - [4.11 `@ExcelIgnoreUnannotated`](#411-excelignoreunannotated)

---





**原文链接**: https://doc.iocoder.cn/excel-import-and-export/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:33

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2022-03-27](javascript:;)

目录

[1\. Excel 导出](#_1-excel-导出)

[1.1 后端导入实现](#_1-1-后端导入实现)

[1.2 前端导入实现](#_1-2-前端导入实现)

[2\. Excel 导入](#_2-excel-导入)

[2.1 后端导入实现](#_2-1-后端导入实现)

[2.2 前端导入实现](#_2-2-前端导入实现)

[3\. 字段转换器](#_3-字段转换器)

[3.1 DictConvert 实现](#_3-1-dictconvert-实现)

[3.1 DictConvert 使用示例](#_3-1-dictconvert-使用示例)

[4\. 更多 EasyExcel 注解](#_4-更多-easyexcel-注解)

[4.1 @ExcelProperty](#_4-1-excelproperty)

[4.2 @ColumnWidth](#_4-2-columnwidth)

[4.3 @ContentFontStyle](#_4-3-contentfontstyle)

[4.4 @ContentLoopMerge](#_4-4-contentloopmerge)

[4.5 @ContentRowHeight](#_4-5-contentrowheight)

[4.6 @ContentStyle](#_4-6-contentstyle)

[4.7 @HeadFontStyle](#_4-7-headfontstyle)

[4.8 @HeadRowHeight](#_4-8-headrowheight)

[4.9 @HeadStyle](#_4-9-headstyle)

[4.11 @ExcelIgnoreUnannotated](#_4-11-excelignoreunannotated)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABH1JREFUSA3tVl1oHFUUPmdmd2ltklqbpJDiNnXFmgbFktho7YMPNiJSSZM0+CAYSkUELVhM6YuwIPpgoOKDqOBDC0XE2CQoNtQXBUFTTcCi+Wlh1V2TQExsUzcltd3M9Tt3ZjZzZ2fT+OJTL8yeM+eee757fmeJbq//KQL8X3DUSFOcfr7cRsRtxNQMWueeVzOkaITIGqQHNg5y8+jNW9ldM7A6nTpAjuolUikAwq7CE3WcM2RRDz+XGVgN3FptU/aUSlvq9Pa3iZ1+sgAqJyyAFqkipd9dqiwHF3P65YycLWc/6sqGrvoEoIp6DOFaX5h6+dnfjkWprwqsPk0dUGq5vySwDImC10KxFHgGL1SWoc92O3eVht09qdXNH11I2SsTsJYqMWzihqGMi+A+Garf3BAuuLI5oGlULyNfyB/HYNujwktOfRrMr5t77NmevqaUopx0grnKAyvVpmwUDB4x6FPXuGvYLTDwWsejwgtgkYKPqRJg8SV6xaiZ3ZTppGneS4yfH5/66fZSDHv+QZci/+h5c5UHtpy67JUqGppM0sh0Nc1dW6/N1W5Yoqat8/TU/VnadmdeW2PLLSyh0cvxBs3KbqTmwYPpxN4do/mzE8nEpvX/UMu2Wbp74zUAK5q6WkHns7V0eWkdPbPzd3rxkTGybadYySumVzhcaJFbs5UrEkQ/+CK8gF5dnh/6ciIZ73gwQ927L1IitoxKLXYP3SjYdOrHHfTZhRRlFyrorafPk20B3HPD1y2G3qKZME5Jcf3t/HUC13/8tSd++vqFveMUTwAUxSUFI1QekR1+bIze3D9MF2aq6cPvG72CgnldWCFqyRw3lwH8ZMerjTD9ElRO7Gv44wNpC90aASqGfVlz/Rx17srQ57/UU26hkhQqUB7dBR71WmzQhHUnblGmVOEw0jhbV1n9OlXUDCIRGaNV5Jp43N516fN7JmnTHdfp7Hgy0luO4aMhtkLL8Bi3bUWYvzh5Mn1dTxrL6QmGuRhGL/TiTTxRoEdTszSaq9GR0NGA3KdkOz3hqSV3MIDhQ5IVX/Ivx3umBti2es2h4eZby7x8br1rkf7Mo90AqC8aQ3sJeNzqFRu+vSANAQe3PL7l0HGOAdwDCeZYvNKeoZp1Qfs6Aipndh86HmFRi0LAnEO47wsqM6cdfjh3jBPUzhZy7nvlUfFsamED1VQt6aISHVymXZ/B2aCtIG8AI8xfobj2d3en1wWVhOeHELKmLQ1s211s88comkv4UCwWyF787mJdYXtNfhKAXVqnKTq8QZvGAGGOfaTo5pGZ/PwbUCr5+DPr/1J92JNHr9aOl/F3iI5+O1nfybsGxoimvZ3ViWSluDITw3P37mypheDIPY0tw7+O/5ApbkYw+zpfaUVu32Pi98+defdUhEpZkRFq0aqyNh9FuL9hpYbEm6iwi0z2REd09ZmyENEbuhjDWzKvZXTqKYaBIr3tt5kuPtQBZFvEUwHt60vfCNu41XsksH9Ij1BMMz1Y0OOunHNShFIP5868g5zeXmuLwL9T4b6Q2+KejgAAAABJRU5ErkJggg==)Excel 导入导出

项目的 [`yudao-spring-boot-starter-excel` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/tree/master/yudao-framework/yudao-spring-boot-starter-excel) 技术组件，基于 FastExcel 实现 Excel 的读写操作，可用于实现最常见的 Excel 导入导出等功能。

FastExcel 的介绍？

FastExcel 是原 EasyExcel 作者开源的 Excel 工具库，具有简单易用、低内存、高性能的特点。

（EasyExcel 作者：2023 年我已从阿里离职，近期阿里宣布停止更新 EasyExcel，我决定继续维护和升级这个项目。在重新开始时，我选择为它起名为 FastExcel，以突出这个框架在处理 Excel 文件时的高性能表现，而不仅仅是简单易用。）

在尽可用节约内存的情况下，支持百万行的 Excel 读写操作。例如说，仅使用 64M 内存，20 秒完成 75M（46 万行 25 列）Excel 的读取。并且，还有极速模式能更快，但是内存占用会在100M 多一点。

![EasyExcel](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/01.png)

## 1. Excel 导出

以 \[系统管理 -> 岗位管理\] 菜单为例子，讲解它 Excel 导出的实现。

![系统管理 -> 岗位管理](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/02.png)

### 1.1 后端导入实现

在 [PostController (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/dept/PostController.java#L88-L97) 类中，定义 `/admin-api/system/post/export` 导出接口。代码如下：

```
    @GetMapping("/export")
    @Operation(summary = "岗位管理")
    @PreAuthorize("@ss.hasPermission('system:post:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void export(HttpServletResponse response, @Validated PostPageReqVO reqVO) throws IOException {
         // ① 查询数据
        reqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<PostDO> list = postService.getPostPage(reqVO).getList();
        // ② 导出 Excel
        ExcelUtils.write(response, "岗位数据.xls", "岗位列表", PostRespVO.class,
                BeanUtils.toBean(list, PostRespVO.class));
    }
```

-   ① 将从数据库中查询出来的列表，一般可以复用分页接口，需要设置 `.setPageSize(PageParam.PAGE_SIZE_NONE)` 不过滤分页。
-   ② 将 PostDO 列表，转换成 PostRespVO 列表，之后通过 ExcelUtils 转换成 Excel 文件，返回给前端。

#### [#](#_1-1-1-postexcelvo-类) 1.1.1 PostExcelVO 类

复用 [PostRespVO (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/dept/vo/post/PostRespVO.java) 类，实现 岗位 Excel 导出的 VO 类。代码如下：

```
@Schema(description = "管理后台 - 岗位信息 Response VO")
@Data
@ExcelIgnoreUnannotated // ③
public class PostRespVO {

    @Schema(description = "岗位序号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("岗位序号") // ①
    private Long id;

    @Schema(description = "岗位名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "小土豆")
    @ExcelProperty("岗位名称")
    private String name;

    @Schema(description = "岗位编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "yudao")
    @ExcelProperty("岗位编码")
    private String code;

    @Schema(description = "显示顺序不能为空", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("岗位排序")
    private Integer sort;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    // ②
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat(DictTypeConstants.COMMON_STATUS)
    private Integer status;

    @Schema(description = "备注", example = "快乐的备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
```

-   ① 每个字段上，添加 [`@ExcelProperty` (opens new window)](https://github.com/alibaba/easyexcel/blob/master/easyexcel-core/src/main/java/com/alibaba/excel/annotation/ExcelProperty.java) 注解，声明 Excel Head 头部的名字。每个字段的**值**，就是它对应的 Excel Row 行的数据值。
-   ② 如果字段的的注解 `converter` 属性是 DictConvert 转换器，用于字典的转换。例如说，通过 `status` 字段，将 `status = 1` 转换成“开启”列，`status = 0` 转换成”禁用”列。稍后，我们会在 [「3. 字段转换器」](#_3-%E5%AD%97%E6%AE%B5%E8%BD%AC%E6%8D%A2%E5%99%A8) 小节来详细讲讲。
-   ③ 在类上，添加 [`@ExcelIgnoreUnannotated` (opens new window)](https://github.com/alibaba/easyexcel/blob/master/easyexcel-core/src/main/java/com/alibaba/excel/annotation/ExcelIgnoreUnannotated.java) 注解，表示未添加 `@ExcelProperty` 的字段，不进行导出。

因此，最终 Excel 导出的效果如下：

![PostExcelVO 效果](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/05.png)

#### [#](#_1-1-2-excelutils-写入) 1.1.2 ExcelUtils 写入

ExcelUtils 的 [`#write(...)` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/excel/core/util/ExcelUtils.java#L19-L40) 方法，将列表以 Excel 响应给前端。代码如下图：

![write 方法](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/06.png)

### 1.2 前端导入实现

在 [`post/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-vue2/blob/master/src/views/system/post/index.vue#L232-L243) 界面，定义 `#handleExport()` 操作，代码如下图：

![handleExport 方法](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/07.png)

## 2. Excel 导入

以 \[系统管理 -> 用户管理\] 菜单为例子，讲解它 Excel 导出的实现。

![系统管理 -> 用户管理](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/11.png)

### 2.1 后端导入实现

在 [UserController (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/user/UserController.java#L176-L187) 类中，定义 `/admin-api/system/user/import` 导入接口。代码如下：

![导入 Excel 接口](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/12.png)

将前端上传的 Excel 文件，读取成 UserImportExcelVO 列表。

#### [#](#_2-1-1-userimportexcelvo-类) 2.1.1 UserImportExcelVO 类

创建 [UserImportExcelVO (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/user/vo/user/UserImportExcelVO.java) 类，用户 Excel 导入的 VO 类。它的作用和 Excel 导入是一样的，代码如下：

![UserImportExcelVO 代码](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/13.png)

对应使用的 Excel 导入文件如下：

![UserImportExcelVO 文件](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/14.png)

#### [#](#_2-1-2-excelutils-读取) 2.1.2 ExcelUtils 读取

ExcelUtils 的 [`#read(...)` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/excel/core/util/ExcelUtils.java#L42-L46) 方法，读取 Excel 文件成列表。代码如下图：

![read 方法](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/16.png)

### 2.2 前端导入实现

在 [`user/index.vue` (opens new window)](https://github.com/yudaocode/yudao-ui-admin-vue2/blob/master/src/views/system/user/index.vue#L174-L193) 界面，定义 Excel 导入的功能，代码如下图：

![Excel 导入的功能](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/15.png)

## 3. 字段转换器

EasyExcel 定义了 [Converter (opens new window)](https://github.com/alibaba/easyexcel/blob/master/easyexcel-core/src/main/java/com/alibaba/excel/converters/Converter.java) 接口，用于实现字段的转换。它有两个核心方法：

① `#convertToJavaData(...)` 方法：将 Excel Row 对应表格的值，转换成 Java 内存中的值。例如说，Excel 的“状态”列，将“状态”列转换成 `status = 1`，”禁用”列转换成 `status = 0`。

② `#convertToExcelData(...)` 方法：恰好相反，将 Java 内存中的值，转换成 Excel Row 对应表格的值。例如说，Excel 的“状态”列，将 `status = 1` 转换成“开启”列，`status = 0` 转换成”禁用”列。

### 3.1 DictConvert 实现

以项目中提供的 [DictConvert (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/excel/core/convert/DictConvert.java) 举例子，它实现 Converter 接口，提供字典数据的转换。代码如下：

![DictConvert 实现](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/21.png)

实现的代码比较简单，自己看看就可以明白。

### 3.1 DictConvert 使用示例

在需要转换的字段上，声明注解 `@ExcelProperty` 的 `converter` 属性是 DictConvert 转换器，注解 [`@DictFormat` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-excel/src/main/java/cn/iocoder/yudao/framework/excel/core/annotations/DictFormat.java) 为对应的字典数据的类型。示例如下：

![DictConvert 使用示例](https://doc.iocoder.cn/img/Excel%E5%AF%BC%E5%85%A5%E5%AF%BC%E5%87%BA/22.png)

## 4. 更多 EasyExcel 注解

基于 [《EasyExcel 中的注解 》 (opens new window)](https://juejin.cn/post/6844904177974542343) 文章，整理相关注解。

### 4.1 `@ExcelProperty`

这是最常用的一个注解，注解中有三个参数 `value`、`index`、`converter` 分别代表列明、列序号、数据转换方式。`value` 和 `index` 只能二选一，通常不用设置 `converter`。

**最佳实践**

```
public class ImeiEncrypt {
    
    @ExcelProperty(value = "imei")
    private String imei;
}
```

### 4.2 `@ColumnWidth`

用于设置列宽度的注解，注解中只有一个参数 `value`。`value` 的单位是字符长度，最大可以设置 255 个字符，因为一个 Excel 单元格最大可以写入的字符个数，就是 255 个字符。

**最佳实践**

```
public class ImeiEncrypt {
    
    @ColumnWidth(value = 18)
    private String imei;
}
```

### 4.3 `@ContentFontStyle`

用于设置单元格内容字体格式的注解。参数如下：

参数

含义

`fontName`

字体名称

`fontHeightInPoints`

字体高度

`italic`

是否斜体

`strikeout`

是否设置删除水平线

`color`

字体颜色

`typeOffset`

偏移量

`underline`

下划线

`bold`

是否加粗

`charset`

编码格式

### 4.4 `@ContentLoopMerge`

用于设置合并单元格的注解。参数如下：

参数

含义

`eachRow`

`columnExtend`

### 4.5 `@ContentRowHeight`

用于设置行高。参数如下：

参数

含义

value

行高，`-1`代表自动行高

### 4.6 `@ContentStyle`

设置内容格式注解。参数如下：

参数

含义

`dataFormat`

日期格式

`hidden`

设置单元格使用此样式隐藏

`locked`

设置单元格使用此样式锁定

`quotePrefix`

在单元格前面增加\`符号，数字或公式将以字符串形式展示

`horizontalAlignment`

设置是否水平居中

`wrapped`

设置文本是否应换行。将此标志设置为`true`通过在多行上显示使单元格中的所有内容可见

`verticalAlignment`

设置是否垂直居中

`rotation`

设置单元格中文本旋转角度。03版本的Excel旋转角度区间为-90°~90°，07版本的Excel旋转角度区间为0°~180°

`indent`

设置单元格中缩进文本的空格数

`borderLeft`

设置左边框的样式

`borderRight`

设置右边框样式

`borderTop`

设置上边框样式

`borderBottom`

设置下边框样式

`leftBorderColor`

设置左边框颜色

`rightBorderColor`

设置右边框颜色

`topBorderColor`

设置上边框颜色

`bottomBorderColor`

设置下边框颜色

`fillPatternType`

设置填充类型

`fillBackgroundColor`

设置背景色

`fillForegroundColor`

设置前景色

`shrinkToFit`

设置自动单元格自动大小

### 4.7 `@HeadFontStyle`

用于定制标题字体格式。参数如下：

参数

含义

`fontName`

设置字体名称

`fontHeightInPoints`

设置字体高度

`italic`

设置字体是否斜体

`strikeout`

是否设置删除线

`color`

设置字体颜色

`typeOffset`

设置偏移量

`underline`

设置下划线

`charset`

设置字体编码

`bold`

设置字体是否家畜

### 4.8 `@HeadRowHeight`

设置标题行行高。参数如下：

参数

含义

`value`

设置行高，-1代表自动行高

### 4.9 `@HeadStyle`

设置标题样式。参数如下：

参数

含义

`dataFormat`

日期格式

`hidden`

设置单元格使用此样式隐藏

`locked`

设置单元格使用此样式锁定

`quotePrefix`

在单元格前面增加\`符号，数字或公式将以字符串形式展示

`horizontalAlignment`

设置是否水平居中

`wrapped`

设置文本是否应换行。将此标志设置为`true`通过在多行上显示使单元格中的所有内容可见

`verticalAlignment`

设置是否垂直居中

`rotation`

设置单元格中文本旋转角度。03版本的Excel旋转角度区间为-90°~90°，07版本的Excel旋转角度区间为0°~180°

`indent`

设置单元格中缩进文本的空格数

`borderLeft`

设置左边框的样式

`borderRight`

设置右边框样式

`borderTop`

设置上边框样式

`borderBottom`

设置下边框样式

`leftBorderColor`

设置左边框颜色

`rightBorderColor`

设置右边框颜色

`topBorderColor`

设置上边框颜色

`bottomBorderColor`

设置下边框颜色

`fillPatternType`

设置填充类型

`fillBackgroundColor`

设置背景色

`fillForegroundColor`

设置前景色

`shrinkToFit`

设置自动单元格自动大小

#### [#](#_4-10-excelignore) 4.10 `@ExcelIgnore`

不将该字段转换成 Excel。

### 4.11 `@ExcelIgnoreUnannotated`

没有注解的字段都不转换

[

文件存储（上传下载）

](/file/)[

操作日志、访问日志、异常日志

](/system-log/)

---

## 📚 相关文档

- [HTTP 接口加解密 | ruoyi-vue-pro 开发指南](后端手册_HTTP-接口加解密.md) (同章节)
- [HTTP 接口签名（防篡改） | ruoyi-vue-pro 开发指南](后端手册_HTTP-接口签名（防篡改）.md) (同章节)
- [MyBatis 数据库 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-数据库.md) (同章节)
- [MyBatis 联表&分页查询 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-联表&分页查询.md) (同章节)
- [OAuth 2.0（SSO 单点登录) | ruoyi-vue-pro 开发指南](后端手册_OAuth-2.0（SSO-单点登录).md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#后端手册)

</div>
