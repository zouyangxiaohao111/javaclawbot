# 请求限流（RateLimiter） | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 5.41 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 实现原理](#1-实现原理)
- [2. `@RateLimiter` 注解](#2-ratelimiter-注解)
- [3. 使用示例](#3-使用示例)

---





**原文链接**: https://doc.iocoder.cn/rate-limiter/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:50

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2024-04-11](javascript:;)

目录

[1\. 实现原理](#_1-实现原理)

[2\. @RateLimiter 注解](#_2-ratelimiter-注解)

[3\. 使用示例](#_3-使用示例)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABGpJREFUSA3tVVtoXFUU3fvOI53UlmCaKIFmwEhsE7QK0ipFEdHEKpXaZGrp15SINsXUWvBDpBgQRKi0+KKoFeJHfZA+ED9KKoIU2gYD9UejTW4rVIzm0VSTziPzuNu1z507dibTTjL4U/DAzLn3nL3X2o91ziX6f9wMFdh6Jvbm9nNSV0msViVO6tN1Rm7NMu2OpeJ9lWBUTDxrJbYTS0hInuwciu9eLHlFxCLCZEk3MegsJmZ5K/JD6t7FkFdEvGUo1g7qJoG3MHImqRIn8/nzY1K9UPKKiJmtnUqHVE3Gbuay6vJE/N2FEmuxFjW2nUuE0yQXRRxLiTUAzs36zhZvOXJPdX850EVnnLZkB8prodQoM5JGj7Xk2mvC7JB8tG04Ef5PiXtG0UtxupRQSfTnBoCy554x18yJHI6I+G5Eru4LHmPJZEQsrvPUbMiA8G/WgMK7w7I+ez7++o2ANfbrjvaOl1tFMs+htG3IrZH9/hDX1Pr8Tc0UvH8tcX29KzAgIGcEkINyW5BF9x891hw6VYqgJHEk0huccS7vh3C6gTiODL+26huuBtbct8eZnqLML8PkxGYpuPZBqtqwkSjgc4mB5gbgig5i+y0UDK35LMxXisn9xQtK+nd26gTIHsHe/oblK/b29fUmN/8Y+9jAQrnBp56m1LcDlDp9irKTExSKduXJVWSqdBMA08pEJnEIOB3FPPMybu/oeV8zFeYN3xx576Q6RH+VmplE4ncQV5v+5rzSoyOU7PuEAg8g803PwBJ0CExno/jcMbN8tONYeOmHiuUNryvm3fRUy4tMPVLdAGkUhNWuggGrJcXPv+ouCjz0MKUHz1J2/E8IC9nqTabcxgaBYM0hPhD5Y65FsbxRQKxCQrDjDctW7PUM3HuZunFyifSAqEfuzCp48Il24luWUWZoyJCaPR82jE0+kFA643wRFVni4RYSq3ohJO2pZ7B5dO4xkDWbEpossJPLSrPjYID8rS2UHTlvyNxqIGsg674XJJ7vnh5L7PNwC4hh2sjCI96mzszOTpxLF0T7l88Yz7lAuK6OnL8gXLOnTvpzSb22YG8W7us3jSebFHeeqnXRG1vt+MoUM84LQIBmMsCTAcOauTh0T0l0neQK7m2bLMt2mGxU3HYssS0J2cdv5wljlPsrIuZLAG/2DOZIXgCYT8uMGZN+e2kSirfxZOPCsC0f24nTZzspnVn9VePS1Z5vubmAGGXG8ZFno9Hel0yfA5ZPhF7Dh972BQJ2qCpgH67lmWtBYbvk6sz02wjky2vXyz0XErP/kFB619js1BtwfOV4OPRqOQBjy3Qbk18vigUPPSD5ceHnwck7W9bhAqZdd7SuG7w4/P2F/GaJh8c7e9qgow+Q7cGBo+98WsLkuktFqiZabtXuQTu/Y5ETbR0v7tNSFnvrmu6pjdoan2KjMu8q/Hmj1EfCO2ZGfEIbIXKUlw8qaX9/b2oeSJmFksSeT/Fn0V3nSypChh4Gjh74ybO9aeZ/AN2dwciu2/MhAAAAAElFTkSuQmCC)请求限流（RateLimiter）

[`yudao-spring-boot-starter-protection` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/) 技术组件，由它的 [`ratelimiter` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/ratelimiter/) 包，提供声明式的限流特性，可防止请求过多。例如说，用户疯狂的点击了某个按钮，导致发送了大量的请求。

```

@RateLimiter(count = 10, timeUnit = TimeUnit.MINUTES)
@PostMapping("/user/create")
public String createUser(User user){
    userService.createUser(user);
    return "添加成功";
}
```

-   每分钟，所有用户，只能操作 10 次

疑问：如果想按照每个用户，或者每个 IP，限制请求呢？

可设置该注解的 `keyResolver` 属性，可选择的有：

-   DefaultRateLimiterKeyResolver：全局级别
-   UserRateLimiterKeyResolver：用户 ID 级别
-   ClientIpRateLimiterKeyResolver：用户 IP 级别
-   ServerNodeRateLimiterKeyResolver：服务器 Node 级别
-   ExpressionIdempotentKeyResolver：自定义级别，通过 `keyArg` 属性指定 Spring EL 表达式

## 1. 实现原理

友情提示：

它的实现原理，和 [《幂等性（防重复提交）》](/idempotent/) 比较接近哈。

它的实现原理非常简单，针对相同参数的方法，一段时间内，只能执行一定次数。执行流程如下：

在方法执行前，判断参数对应的 Key 是否超过限制：

-   如果**超过**，则进行报错。
-   如果**未超过**，则使用 Redis 计数 +1

默认参数的 Redis Key 的计算规则由 [DefaultRateLimiterKeyResolver (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/ratelimiter/core/keyresolver/impl/DefaultRateLimiterKeyResolver.java) 实现，使用 MD5(方法名 + 方法参数)，避免 Redis Key 过长。

## 2. `@RateLimiter` 注解

[`@RateLimiter` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/ratelimiter/core/annotation/RateLimiter.java) 注解，声明在方法上，表示该方法需要开启限流。代码如下：

![ 注解](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/%E8%AF%B7%E6%B1%82%E9%99%90%E6%B5%81/%E6%B3%A8%E8%A7%A3.png)

① 对应的 AOP 切面是 [RateLimiterAspect (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/ratelimiter/core/aop/RateLimiterAspect.java) 类，核心就 10 行左右的代码，如下图所示：

![RateLimiterAspect](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/%E8%AF%B7%E6%B1%82%E9%99%90%E6%B5%81/RateLimiterAspect.png)

② 对应的 Redis Key 的前缀是 `rate_limiter:%` ，可见 [IdempotentRedisDAO (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-protection/src/main/java/cn/iocoder/yudao/framework/ratelimiter/core/redis/RateLimiterRedisDAO.java) 类，如下图所示：

![IdempotentRedisDAO 存储](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/%E8%AF%B7%E6%B1%82%E9%99%90%E6%B5%81/IdempotentRedisDAO.png)

## 3. 使用示例

本小节，我们实现 `/admin-api/system/user/page` RESTful API 接口的限流。

① 在 `pom.xml` 文件中，引入 `yudao-spring-boot-starter-protection` 依赖。

```
<dependency>
    <groupId>cn.iocoder.boot</groupId>
    <artifactId>yudao-spring-boot-starter-protection</artifactId>
</dependency>
```

② 在 `/admin-api/system/user/page` RESTful API 接口的对应方法上，添加 `@RateLimiter` 注解。代码如下：

```
// UserController.java

@GetMapping("/page")
@RateLimiter(count = 1, time = 60)
public CommonResult<PageResult<UserRespVO>> getUserPage(@Valid UserPageReqVO pageReqVO) {
    // ... 省略代码
}
```

③ 调用该 API 接口，执行成功。

![调用成功](https://doc.iocoder.cn/img/%E5%90%8E%E7%AB%AF%E6%89%8B%E5%86%8C/%E8%AF%B7%E6%B1%82%E9%99%90%E6%B5%81/%E6%A1%88%E4%BE%8B.png)

④ 再次调用该 API 接口，被限流拦截，执行失败。

```
{
  "code": 429,
  "data": null,
  "msg": "请求过于频繁，请稍后重试"
}
```

[

幂等性（防重复提交）

](/idempotent/)[

HTTP 接口签名（防篡改）

](/http-sign/)

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
