# HTTP 接口签名（防篡改） | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 5.30 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 实现原理](#1-实现原理)
- [2. 使用示例](#2-使用示例)

---





**原文链接**: https://doc.iocoder.cn/http-sign/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:51

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2024-06-04](javascript:;)

目录

[1\. 实现原理](#_1-实现原理)

[2\. 使用示例](#_2-使用示例)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)HTTP 接口签名（防篡改）

[`yudao-spring-boot-starter-protection` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/) 技术组件，由它的 [`signature` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/signature/) 包，提供 HTTP 接口签名特性，提高安全性。

例如说：项目给第三方提供 HTTP 接口时，为了提高对接中数据传输的安全性（防止请求参数被篡改），同时校验调用方的有效性，通常都需要增加签名 sign。

市面上也有非常多的案例，例如说：

-   [《微信支付 —— 安全规范》 (opens new window)](https://pay.weixin.qq.com/wiki/doc/api/jsapi.php?chapter=4_3)
-   [《支付宝 —— 签名 》 (opens new window)](https://opendocs.alipay.com/common/02khjm)

## 1. 实现原理

在 Controller 的方法上，添加 `@ApiSignature` 注解，声明它需要签名。

然后，通过 AOP 切面，ApiSignatureAspect 对这些方法进行拦截，校验签名是否正确。它的签名算法如下：

```
// ApiSignatureAspect.java

String serverSignatureString = buildSignatureString(signature, request, appSecret)
DigestUtil.sha256Hex(serverSignatureString);

    private String buildSignatureString(ApiSignature signature, HttpServletRequest request, String appSecret) {
        SortedMap<String, String> parameterMap = getRequestParameterMap(request); // 请求头
        SortedMap<String, String> headerMap = getRequestHeaderMap(signature, request); // 请求参数
        String requestBody = StrUtil.nullToDefault(ServletUtils.getBody(request), ""); // 请求体
        return MapUtil.join(parameterMap, "&", "=")
                + requestBody
                + MapUtil.join(headerMap, "&", "=")
                + appSecret;
    }
```

① 将请求头、请求体、请求参数，按照一定顺序排列，然后添加密钥，获得需要进行签名的字符串。

其中，每个调用方 `appId` 对应一个唯一 `appSecret`，通过在 Redis 配置，它对应 key 为 `api_signature_app` 的 HASH 结构，hashKey 为 `appId`。

② 之后，通过 SHA256 进行加密，得到签名 sign。

* * *

注意：第三方调用时，每次请求 Header 需要带上 `appId`、`timestamp`、`nonce`、`sign` 四个参数：

-   `appId`：调用方的唯一标识。
-   `timestamp`：请求时的时间戳。
-   `nonce`：用于请求的防重放攻击，每次请求唯一，例如说 UUID。
-   `sign`：HTTP 签名。

疑问：为什么使用请求 Header 传参？

避免这四个参数，在请求 QueryString、Request Body 可能重复的问题！

## 2. 使用示例

① 在需要使用的 `yudao-module-xxx` 模块的 ，引入 `yudao-spring-boot-starter-protection` 依赖：

```
<dependency>
    <groupId>cn.iocoder.boot</groupId>
    <artifactId>yudao-spring-boot-starter-protection</artifactId>
</dependency>
```

② 在 Redis 添加一个 `appId` 为 `test`，密钥为 `123456` 的配置：

```
hset api_signature_app test 123456
```

③ 在 Controller 的方法上，添加 `@ApiSignature` 注解：

```
// UserController.java

@GetMapping("/page")
@Operation(summary = "获得用户分页列表")
@PreAuthorize("@ss.hasPermission('system:user:list')")
@ApiSignature(timeout = 30, timeUnit = TimeUnit.MINUTES) // 关键是此处。ps：设置为 30 分钟，只是为了测试方便，不是必须！
public CommonResult<PageResult<UserRespVO>> getUserPage(@Valid UserPageReqVO pageReqVO) {
    // ... 省略代码
}
```

④ 调用该 API 接口，执行成功。如下是一个 IDEA HTTP 的示例：

```
// UserController.http

GET {{baseUrl}}/system/user/page?pageNo=1&pageSize=10
Authorization: Bearer {{token}}
appId: test
timestamp: 1717494535932
nonce: e7eb4265-885d-40eb-ace3-2ecfc34bd639
sign: 01e1c3df4d93eafc862753641ebfc1637e70f853733684a139f8b630af5c84cd
tenant-id: {{adminTenentId}}
```

-   `appId`、`timestamp`、`nonce`、`sign` 通过请求 Header 传递，避免和请求参数冲突。【必须传递】
-   `timestamp`：请求时的时间戳。
-   `nonce`：用于请求的防重放攻击，每次请求唯一，例如说 UUID。
-   `sign`：HTTP 签名。如果你不知道多少，可以直接 debug ApiSignatureAspect 的 `serverSignature` 处的代码，进行获得。

友情提示：强烈建议 ApiSignatureAspect 的实现代码，一共就 100 多行代码。可以通过 ApiSignatureTest 单元测试，直接执行噢！

[

请求限流（RateLimiter）

](/rate-limiter/)[

HTTP 接口加解密

](/api-encrypt/)

---

## 📚 相关文档

- [Excel 导入导出 | ruoyi-vue-pro 开发指南](后端手册_Excel-导入导出.md) (同章节)
- [HTTP 接口加解密 | ruoyi-vue-pro 开发指南](后端手册_HTTP-接口加解密.md) (同章节)
- [MyBatis 数据库 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-数据库.md) (同章节)
- [MyBatis 联表&分页查询 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-联表&分页查询.md) (同章节)
- [OAuth 2.0（SSO 单点登录) | ruoyi-vue-pro 开发指南](后端手册_OAuth-2.0（SSO-单点登录).md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#后端手册)

</div>
