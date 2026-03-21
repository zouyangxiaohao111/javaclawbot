# VO 对象转换、数据翻译 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 7.60 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 对象转换](#1-对象转换)
  - [1.1 MapStruct](#11-mapstruct)
  - [1.2 BeanUtils](#12-beanutils)
- [2. 数据翻译](#2-数据翻译)
  - [2.1 场景一：模块内翻译](#21-场景一模块内翻译)
  - [2.2 场景二：跨模块翻译](#22-场景二跨模块翻译)
  - [2.3 场景三：Excel 导出翻译](#23-场景三excel-导出翻译)

---





**原文链接**: https://doc.iocoder.cn/vo/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:29

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2024-04-01](javascript:;)

目录

[1\. 对象转换](#_1-对象转换)

[1.1 MapStruct](#_1-1-mapstruct)

[1.2 BeanUtils](#_1-2-beanutils)

[2\. 数据翻译](#_2-数据翻译)

[2.1 场景一：模块内翻译](#_2-1-场景一-模块内翻译)

[2.2 场景二：跨模块翻译](#_2-2-场景二-跨模块翻译)

[2.3 场景三：Excel 导出翻译](#_2-3-场景三-excel-导出翻译)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABH1JREFUSA3tVl1oHFUUPmdmd2ltklqbpJDiNnXFmgbFktho7YMPNiJSSZM0+CAYSkUELVhM6YuwIPpgoOKDqOBDC0XE2CQoNtQXBUFTTcCi+Wlh1V2TQExsUzcltd3M9Tt3ZjZzZ2fT+OJTL8yeM+eee757fmeJbq//KQL8X3DUSFOcfr7cRsRtxNQMWueeVzOkaITIGqQHNg5y8+jNW9ldM7A6nTpAjuolUikAwq7CE3WcM2RRDz+XGVgN3FptU/aUSlvq9Pa3iZ1+sgAqJyyAFqkipd9dqiwHF3P65YycLWc/6sqGrvoEoIp6DOFaX5h6+dnfjkWprwqsPk0dUGq5vySwDImC10KxFHgGL1SWoc92O3eVht09qdXNH11I2SsTsJYqMWzihqGMi+A+Garf3BAuuLI5oGlULyNfyB/HYNujwktOfRrMr5t77NmevqaUopx0grnKAyvVpmwUDB4x6FPXuGvYLTDwWsejwgtgkYKPqRJg8SV6xaiZ3ZTppGneS4yfH5/66fZSDHv+QZci/+h5c5UHtpy67JUqGppM0sh0Nc1dW6/N1W5Yoqat8/TU/VnadmdeW2PLLSyh0cvxBs3KbqTmwYPpxN4do/mzE8nEpvX/UMu2Wbp74zUAK5q6WkHns7V0eWkdPbPzd3rxkTGybadYySumVzhcaJFbs5UrEkQ/+CK8gF5dnh/6ciIZ73gwQ927L1IitoxKLXYP3SjYdOrHHfTZhRRlFyrorafPk20B3HPD1y2G3qKZME5Jcf3t/HUC13/8tSd++vqFveMUTwAUxSUFI1QekR1+bIze3D9MF2aq6cPvG72CgnldWCFqyRw3lwH8ZMerjTD9ElRO7Gv44wNpC90aASqGfVlz/Rx17srQ57/UU26hkhQqUB7dBR71WmzQhHUnblGmVOEw0jhbV1n9OlXUDCIRGaNV5Jp43N516fN7JmnTHdfp7Hgy0luO4aMhtkLL8Bi3bUWYvzh5Mn1dTxrL6QmGuRhGL/TiTTxRoEdTszSaq9GR0NGA3KdkOz3hqSV3MIDhQ5IVX/Ivx3umBti2es2h4eZby7x8br1rkf7Mo90AqC8aQ3sJeNzqFRu+vSANAQe3PL7l0HGOAdwDCeZYvNKeoZp1Qfs6Aipndh86HmFRi0LAnEO47wsqM6cdfjh3jBPUzhZy7nvlUfFsamED1VQt6aISHVymXZ/B2aCtIG8AI8xfobj2d3en1wWVhOeHELKmLQ1s211s88comkv4UCwWyF787mJdYXtNfhKAXVqnKTq8QZvGAGGOfaTo5pGZ/PwbUCr5+DPr/1J92JNHr9aOl/F3iI5+O1nfybsGxoimvZ3ViWSluDITw3P37mypheDIPY0tw7+O/5ApbkYw+zpfaUVu32Pi98+defdUhEpZkRFq0aqyNh9FuL9hpYbEm6iwi0z2REd09ZmyENEbuhjDWzKvZXTqKYaBIr3tt5kuPtQBZFvEUwHt60vfCNu41XsksH9Ij1BMMz1Y0OOunHNShFIP5868g5zeXmuLwL9T4b6Q2+KejgAAAABJRU5ErkJggg==)VO 对象转换、数据翻译

本小节，我们来讲解 VO 的对象转换、数据翻译的功能。注意，这里的 VO 是泛指 Java POJO 对象，也可以是 DTO、BO 等等。

## 1. 对象转换

对象转换，指的是 A 类型对象，转换成 B 类型对象。例如说，我们有一个 UserDO 类型对象，需要转换成 UserVO 或者 UserDTO 类型对象。

市面上有很多的对象转换工具，例如说 MapStruct、Dozer、各种 BeanUtils、BeanCopier 等等。目前我们提供了 MapStruct、BeanUtils 两种解决方案。

相比来说，MapStruct 性能会略好于 BeanUtils，但是相比数据库操作带来的耗时来说，基本可以忽略不计。因此，一般情况下，建议使用 BeanUtils 即可。

### 1.1 MapStruct

项目使用 [MapStruct (opens new window)](https://www.iocoder.cn/Spring-Boot/MapStruct/?yudao) 实现 VO、DO、DTO 等对象之间的转换。

如果你没有学习过 MapStruct，需要阅读下 [《芋道 Spring Boot 对象转换 MapStruct 入门》 (opens new window)](https://www.iocoder.cn/Spring-Boot/MapStruct/?yudao) 文章。

在每个 `yudao-module-xxx` 模块的 `convert` 包下，可以看到各个业务的 Convert 接口，如下图所示：

![MapStruct 示例](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/MapStruct.png)

### 1.2 BeanUtils

项目提供了 [BeanUtils (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/util/object/BeanUtils.java) 类，它是基于 Hutool 的 BeanUtil 封装一层。如下图所示：

![BeanUtils](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/BeanUtils.png)

疑问：为什么不直接使用 Hutool BeanUtil，而是额外封装一层呢？

① 方便替换实现。例如说，你想把 Hutool BeanUtil 换成 Spring BeanUtil、BeanCopier 等时，只需要修改它。

② 特性增强。额外支持 List、Page 对象的转换，也支持 Consumer 进一步转化。

1、在简单场景，直接使用 BeanUtils 的 `#toBean(...)` 方法，如下图所示：

![简单场景](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/BeanUtils-01.png)

2、在复杂场景，可以通过 Consumer 进一步拼接，如下图所示：

图片纠错：最新版本不区分 yudao-module-erp-api 和 yudao-module-erp-biz 子模块，代码直接合并到 yudao-module-erp 模块的 src 目录下，更适合单体项目

![复杂场景](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/BeanUtils-02.png)

当然，如果 Consumer 的逻辑比较复杂，又希望 Controller 代码精简一点，可以放到对应的 Convert 类里，如下图所示：

![更复杂场景](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/BeanUtils-03.png)

## 2. 数据翻译

数据翻译，指的是将 A 类型对象的某个字段，“翻译”成 B 类型对象的某个字段。例如说，我们有一个 UserVO 的 `deptId` 字段，读取对应 DeptDO 的 `name` 字段，最终设置到 UserVO 的 `deptName` 字段。

一般来说，目前有两种方案：

-   方案一：数据库 SQL 联表查询，可见 [《MyBatis 联表&分页查询》](/mybatis-pro/) 文档
-   方案二：数据库多次单表查询，然后在 Java 代码中进行数据拼接（翻译）。其实就是「1.2 BeanUtils」的“复杂场景”。如下图所示：

图片纠错：最新版本不区分 yudao-module-erp-api 和 yudao-module-erp-biz 子模块，代码直接合并到 yudao-module-erp 模块的 src 目录下，更适合单体项目

![复杂场景](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/BeanUtils-02.png)

项目里，大多数采用“方案二”，因为这样可以减少数据库的压力，避免 SQL 过于复杂，也方便后续维护。

不过如果你觉得“方案二”比较麻烦，我们也集成了 [`easy-trans` (opens new window)](https://gitee.com/dromara/easy_trans) 框架，一个注解，搞定数据翻译。

下面，我们来分场景，看看具体如何使用！

### 2.1 场景一：模块内翻译

模块内翻译，指的是在同一个模块内，进行数据翻译。例如说，OperateLogRespVO 属于 `yudao-module-system` 模块，需要读取模块内的 AdminUserDO 数据。

① 第一步，给 OperateLogRespVO 实现 `com.fhs.core.trans.vo.VO` 接口。

② 第二步，给 OperateLogRespVO 的 `deptId` 字段，添加 `@Trans` 注解，如下图所示：

![模块内翻译](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/%E6%A8%A1%E5%9D%97%E5%86%85%E7%BF%BB%E8%AF%91.png)

-   `type` 属性：使用 `TransType.SIMPLE` 简单翻译，使用 MyBatis Plus
-   `target` 属性：目标 DO 实体的类，例如说 `AdminUserDO.class`
-   `fields` 属性：读取 DO 实体的字段，例如说 `nickname`。如果是多个字段，它也是个数组
-   `ref` 属性：设置 VO 类的字段，例如说 `userNickname`。如果是多个字段，可以使用 `refs`

更多关于 `@Trans` 注解的讲解，可见 [《Trans 注解详解(必读)》 (opens new window)](http://easy-trans.fhs-opensource.cn/components/trans.html) 文档。

③ 第三步，给需要翻译的 Controller 接口，添加 `@@TransMethodResult` 注解，代码如下所示：

```
@Tag(name = "管理后台 - 操作日志")
@RestController
@RequestMapping("/system/operate-log")
@Validated
public class OperateLogController {

    @GetMapping("/page")
    @Operation(summary = "查看操作日志分页列表")
    @PreAuthorize("@ss.hasPermission('system:operate-log:query')")
    @TransMethodResult // 【重要】这里是关键！！！
    public CommonResult<PageResult<OperateLogRespVO>> pageOperateLog(@Valid OperateLogPageReqVO pageReqVO) {
        PageResult<OperateLogDO> pageResult = operateLogService.getOperateLogPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, OperateLogRespVO.class));
    }
    
}
```

### 2.2 场景二：跨模块翻译

友情提示：

由于最新版本 biz 和 api 进行合并了，所以「跨模块翻译」，也可以使用「模块内翻译」的方式！！！

为什么呢？可以思考下，嘿嘿~

跨模块翻译，指的是在不同模块，进行数据翻译。例如说，CrmProductRespVO 属于 `yudao-module-crm` 模块，需要读取 `yudao-module-system` 模块的 AdminUserDO 数据。

① 第一步，给 CrmProductRespVO 实现 `com.fhs.core.trans.vo.VO` 接口。

② 第二步，给 CrmProductRespVO 的 `ownerUserId` 字段，添加 `@Trans` 注解，如下图所示：

图片纠错：最新版本不区分 yudao-module-crm-api 和 yudao-module-crm-biz 子模块，代码直接合并到 yudao-module-erp 模块的 src 目录下，更适合单体项目

![跨模块翻译](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/%E8%B7%A8%E6%A8%A1%E5%9D%97%E7%BF%BB%E8%AF%91.png)

-   `type` 属性：使用 `TransType.SIMPLE` 跨模块翻译。不过实际上，因为多模块是在单个 Java 进程中，所以它底层还是走的 MyBatis Plus
-   `targetClassName` 属性：目标 DO 实体的类全路径，例如说 `cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO`
-   `fields` 和 `ref` 属性：同上，不重复解释

友情提示：

后续这个场景下，`easy-trans` 的作者，也会改成 `TransType.SIMPLE` 简单翻译。

因此，“跨模块翻译”使用 `targetClassName` 属性的原因，是因为拿不到跨模块的 DO 实体类 = =

③ 第三步，给需要翻译的 Controller 接口，添加 `@TransMethodResult` 注解，和上面是一样的。

### 2.3 场景三：Excel 导出翻译

在 Excel 导出时，如果也有数据翻译的需求，需要调用 [TranslateUtils (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-mybatis/src/main/java/cn/iocoder/yudao/framework/translate/core/TranslateUtils.java) 的 `#translate(...)` 方法，如下图所示：

图片纠错：最新版本不区分 yudao-module-crm-api 和 yudao-module-crm-biz 子模块，代码直接合并到 yudao-module-erp 模块的 src 目录下，更适合单体项目

![Excel 导出翻译](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/VO/%E5%AF%BC%E5%87%BA%E7%BF%BB%E8%AF%91.png)

本质上，它就是 `easy-trans` 的手动翻译，可见 [《Trans 基础使用(必读)》的“3、自动翻译和手动翻译” (opens new window)](http://easy-trans.fhs-opensource.cn/components/basic.html#_3%E3%80%81%E8%87%AA%E5%8A%A8%E7%BF%BB%E8%AF%91%E5%92%8C%E6%89%8B%E5%8A%A8%E7%BF%BB%E8%AF%91)

[

分页实现

](/page-feature/)[

文件存储（上传下载）

](/file/)

---

## 📚 相关文档

- [Excel 导入导出 | ruoyi-vue-pro 开发指南](后端手册_Excel-导入导出.md) (同章节)
- [HTTP 接口加解密 | ruoyi-vue-pro 开发指南](后端手册_HTTP-接口加解密.md) (同章节)
- [HTTP 接口签名（防篡改） | ruoyi-vue-pro 开发指南](后端手册_HTTP-接口签名（防篡改）.md) (同章节)
- [MyBatis 数据库 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-数据库.md) (同章节)
- [MyBatis 联表&分页查询 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-联表&分页查询.md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#后端手册)

</div>
