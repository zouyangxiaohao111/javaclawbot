# Redis 缓存 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 10.24 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 编程式缓存](#1-编程式缓存)
  - [1.1 Spring Data Redis 配置](#11-spring-data-redis-配置)
  - [1.2 实战案例](#12-实战案例)
- [2. 声明式缓存](#2-声明式缓存)
  - [2.1 Spring Cache 配置](#21-spring-cache-配置)
  - [2.2 常见注解](#22-常见注解)
  - [2.3 实战案例](#23-实战案例)
  - [2.4 过期时间](#24-过期时间)
- [3. Redis 监控](#3-redis-监控)

---





**原文链接**: https://doc.iocoder.cn/redis-cache/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:42

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2022-04-03](javascript:;)

目录

[1\. 编程式缓存](#_1-编程式缓存)

[1.1 Spring Data Redis 配置](#_1-1-spring-data-redis-配置)

[1.2 实战案例](#_1-2-实战案例)

[2\. 声明式缓存](#_2-声明式缓存)

[2.1 Spring Cache 配置](#_2-1-spring-cache-配置)

[2.2 常见注解](#_2-2-常见注解)

[2.3 实战案例](#_2-3-实战案例)

[2.4 过期时间](#_2-4-过期时间)

[3\. Redis 监控](#_3-redis-监控)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABKFJREFUSA3tVl1oFVcQnrMbrak3QUgkya1akpJYcrUtIqW1JvFBE9LiQ5v6JmJpolbMg32rVrhgoYK0QiMY6i9Y6EMaW5D+xFJaTYItIuK2Kr3+BJNwkxBj05sQY3b3nM6cs2dv9t7NT/vQJw/sndk5M/PNzJkzewGerP+pAmy+ON8lLzUJgA8ZYxYIYZmGYRnctDaWvJJAmTtfP1pvXsBCCPP8QFcCaRkZYACgDZFO4stNIcBCajEOlmmC9XpJ9bAGCaPaPmzPl32dvLSVu3BWCTQs0XQQ6g0DYgwLIoAZbBCdW/i+781o1VVlm/410mw4h06Y7bIPHNyWDyL4FHkX03Q8SrzNhZTZriieckWt7cL6MM85YcLpsi/7O9/iXFT6MswI0DmmpkSaJ0qLxFIm3+i1THHB3zmBH3PYx9CcykcLOeQVVa7QtdxTgQgEleX2AjHYfwA+2ddV77ruGoJUbhGDI09YSNXyMpUt5ylOzxgbUmtOp7NmbNt8v3arjTBfYELmLUV+M+nSawNNAUqpT3ClJWg5I3BLT+cGW/DXNGCa6tx1aakCGEigArTn4TDIPdrXXYKCZNrHLMCOEPvHBlLQ99s9eHB7EB6NTki73CVPQ2F5MSx/uRQixfmq7rK0wYD8w8E905bnPDfwoWs/rfv93NWN/ZfvwsLIU7A09gxECyISeGJkHAau98L97tuw7NXnoPyNF8FcYGLGKsOs0mN3OEyec9esGW/ZEl945dTP34wlR2FZVQWU1q0Cw8Tr7p+hgLLNL0FPxx/Q35mA8aEUrH6nCgwEl0tn7wUiZYJnNRh6DK4UH/k0lfyrsBKdPVv/AriGIQcEDQZ65LBAGe2Rzui9Ybjz7XUppz1/uKBbyVPGkN3ZAeC6hr0x7Nr38N5+EqkoOm17xpoqR9ohQF55ERSvr4Dkr3chNfC3DMzGJlNBElW8w9nsGQvhNGIzDkXzCg8cLK951xHsFBlTJspJNi3ZFIMF2AeDV3q8DNOB+YHi6QTrChDIWDBRi5U5f+ZMfJLu3ccrqxtdxk4SKH336LFxSmkqefwU5T8fhdSdQf9IVKD6aNiwI/hnmcAZ91isYMJIaCUCx9W098+LgruikeTqzqqxKPUwqJyCPJiyemVVZBOijDGjD38Os0jOiSPL1z3SPjXNANbiNPXAdzTfukjjuknNBbyz3nwgTd3AVFqUJ5hpHlq9MveLnWwttUfoygBmvVjuikxND3znrhsELnZk7k+OjIGxeNEkomyLVta0xxn+HZhjBc4YZ/AFjHjz9u3xRZl2BN4aq9nFwWh16IrQ1aHHEd3j1+4/dB9OtH4e29A2H1DyHQRmOSfQZ1Fy7MHBTGB6J/Djq6p3OxyO2cB+4Car7v/o3GXgfAkj23+x9ID1Teoamo/SXcbvSf2PX7Vc8DdCmE1vN9di+32P9/5YR3vLnhCVGUWBjEkr3yh4H8v9CzmsbdhzOKzsJKM90iFdaTMjRPhGVsakRvOaRidljo6H6G7j+ctrJpsP+4COhDIl0La2+FS4+5mlocBaXY5QnGZysIBYoeSsl5qQzrSj/cgNrfuEzlWBfwA+EjrZyWUvpAAAAABJRU5ErkJggg==)Redis 缓存

[`yudao-spring-boot-starter-redis` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-redis/) 技术组件，使用 Redis 实现缓存的功能，它有 2 种使用方式：

-   编程式缓存：基于 Spring Data Redis 框架的 RedisTemplate 操作模板
-   声明式缓存：基于 Spring Cache 框架的 `@Cacheable` 等等注解

## 1. 编程式缓存

友情提示：

如果你未学习过 Spring Data Redis 框架，可以后续阅读 [《芋道 Spring Boot Redis 入门》 (opens new window)](http://www.iocoder.cn/Spring-Boot/Redis/?yudao) 文章。

```
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
</dependency>
```

由于 Redisson 提供了分布式锁、队列、限流等特性，所以使用它作为 Spring Data Redis 的客户端。

### 1.1 Spring Data Redis 配置

① 在 [`application-local.yaml` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-server/src/main/resources/application-local.yaml#L60-L64) 配置文件中，通过 `spring.redis` 配置项，设置 Redis 的配置。如下图所示：

![Spring Data Redis 配置](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/01.png)

② 在 [YudaoRedisAutoConfiguration (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-redis/src/main/java/cn/iocoder/yudao/framework/redis/config/YudaoRedisAutoConfiguration.java) 配置类，设置使用 JSON 序列化 value 值。如下图所示：

![YudaoRedisAutoConfiguration 配置类](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/02.png)

### 1.2 实战案例

以访问令牌 Access Token 的缓存来举例子，讲解项目中是如何使用 Spring Data Redis 框架的。

![Access Token 示例](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/07.png)

#### [#](#_1-2-1-引入依赖) 1.2.1 引入依赖

在 `yudao-module-system` 模块中，引入 `yudao-spring-boot-starter-redis` 技术组件。如下所示：

```
<dependency>
    <groupId>cn.iocoder.boot</groupId>
    <artifactId>yudao-spring-boot-starter-redis</artifactId>
</dependency>
```

#### [#](#_1-2-2-oauth2accesstokendo) 1.2.2 OAuth2AccessTokenDO

新建 [OAuth2AccessTokenDO (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/oauth2/OAuth2AccessTokenDO.java) 类，访问令牌 Access Token 类。代码如下：

图片纠错：最新版本不区分 yudao-module-system-api 和 yudao-module-system-biz 子模块，代码直接合并到 yudao-module-system 模块的 src 目录下，更适合单体项目

![OAuth2AccessTokenDO 类](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/03.png)

友情提示：

-   ① 如果值是【简单】的 String 或者 Integer 等类型，无需创建数据实体。
-   ② 如果值是【复杂对象】时，建议在 `dal/dataobject` 包下，创建对应的数据实体。

#### [#](#_1-2-3-rediskeyconstants) 1.2.3 RedisKeyConstants

为什么要定义 Redis Key 常量？

每个 `yudao-module-xxx` 模块，都有一个 RedisKeyConstants 类，定义该模块的 Redis Key 的信息。目的是，避免 Redis Key 散落在 Service 业务代码中，像对待数据库的表一样，对待每个 Redis Key。通过这样的方式，如果我们想要了解一个模块的 Redis 的使用情况，只需要查看 RedisKeyConstants 类即可。

在 `yudao-module-system` 模块的 [RedisKeyConstants (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/RedisKeyConstants.java) 类中，新建 OAuth2AccessTokenDO 对应的 Redis Key 定义 `OAUTH2_ACCESS_TOKEN`。如下图所示：

图片纠错：最新版本不区分 yudao-module-system-api 和 yudao-module-system-biz 子模块，代码直接合并到 yudao-module-system 模块的 src 目录下，更适合单体项目

![RedisKeyConstants 类](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/04.png)

#### [#](#_1-2-4-oauth2accesstokenredisdao) 1.2.4 OAuth2AccessTokenRedisDAO

新建 [OAuth2AccessTokenRedisDAO (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/redis/oauth2/OAuth2AccessTokenRedisDAO.java) 类，是 OAuth2AccessTokenDO 的 RedisDAO 实现。代码如下：

图片纠错：最新版本不区分 yudao-module-system-api 和 yudao-module-system-biz 子模块，代码直接合并到 yudao-module-system 模块的 src 目录下，更适合单体项目

![OAuth2AccessTokenRedisDAO 类](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/05.png)

#### [#](#_1-2-5-oauth2tokenserviceimpl) 1.2.5 OAuth2TokenServiceImpl

在 [OAuth2TokenServiceImpl (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java) 中，只要注入 OAuth2AccessTokenRedisDAO Bean，非常简洁干净的进行 OAuth2AccessTokenDO 的缓存操作，无需关心具体的实现。代码如下：

![OAuth2TokenServiceImpl 类](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/06.png)

## 2. 声明式缓存

友情提示：

如果你未学习过 Spring Cache 框架，可以后续阅读 [《芋道 Spring Boot Cache 入门》 (opens new window)](http://www.iocoder.cn/Spring-Boot/Cache/?yudao) 文章。

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

相比来说 Spring Data Redis 编程式缓存，Spring Cache 声明式缓存的使用更加便利，一个 `@Cacheable` 注解即可实现缓存的功能。示例如下：

```
@Cacheable(value = "users", key = "#id")
UserDO getUserById(Integer id);
```

### 2.1 Spring Cache 配置

① 在 [`application.yaml` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-server/src/main/resources/application.yaml#L60-L64) 配置文件中，通过 `spring.redis` 配置项，设置 Redis 的配置。如下图所示：

![Spring Cache 配置](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/10.png)

② 在 [YudaoCacheAutoConfiguration (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-redis/src/main/java/cn/iocoder/yudao/framework/redis/config/YudaoCacheAutoConfiguration.java) 配置类，设置使用 JSON 序列化 value 值。如下图所示：

![YudaoCacheAutoConfiguration 配置类](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/11.png)

### 2.2 常见注解

#### [#](#_2-2-1-cacheable-注解) 2.2.1 @Cacheable 注解

[`@Cacheable` (opens new window)](https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/cache/annotation/Cacheable.java) 注解：添加在方法上，缓存方法的执行结果。执行过程如下：

-   1）首先，判断方法执行结果的缓存。如果有，则直接返回该缓存结果。
-   2）然后，执行方法，获得方法结果。
-   3）之后，根据是否满足缓存的条件。如果满足，则缓存方法结果到缓存。
-   4）最后，返回方法结果。

#### [#](#_2-2-2-cacheput-注解) 2.2.2 @CachePut 注解

[`@CachePut` (opens new window)](https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/cache/annotation/CachePut.java) 注解，添加在方法上，缓存方法的执行结果。不同于 `@Cacheable` 注解，它的执行过程如下：

-   1）首先，执行方法，获得方法结果。也就是说，无论是否有缓存，都会执行方法。
-   2）然后，根据是否满足缓存的条件。如果满足，则缓存方法结果到缓存。
-   3）最后，返回方法结果。

#### [#](#_2-2-3-cacheevict-注解) 2.2.3 @CacheEvict 注解

[`@CacheEvict` (opens new window)](https://github.com/spring-projects/spring-framework/blob/master/spring-context/src/main/java/org/springframework/cache/annotation/CacheEvict.java) 注解，添加在方法上，删除缓存。

不使用 \`allEntries\` 属性，但是想批量删除一些缓存，怎么办？

可参考 [https://t.zsxq.com/phOrM (opens new window)](https://t.zsxq.com/phOrM) 帖子，手动删除一些。 ::

### 2.3 实战案例

在 [RoleServiceImpl (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/RoleServiceImpl.java) 中，使用 Spring Cache 实现了 Role 角色缓存，采用【被动读】的方案。原因是：

![RoleServiceImpl](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/12.png)

-   【被动读】相对能够保证 Redis 与 MySQL 的一致性
-   绝大数数据不需要放到 Redis 缓存中，采用【主动写】会将非必要的数据进行缓存

友情提示：

如果你未学习过 MySQL 与 Redis 一致性的问题，可以后续阅读 [《Redis 与 MySQL 双写一致性如何保证？ 》 (opens new window)](https://www.iocoder.cn/Fight/How-Redis-and-MySQL-double-write-consistency-guarantee/?yudao) 文章。

① 执行 `#getRoleFromCache(...)` 方法，从 MySQL 读取数据后，向 Redis 写入缓存。如下图所示：

![getTestDemo 方法](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/13.png)

② 执行 `#updateRole(...)` 或 `#deleteRole(...)` 方法，在更新或者删除 MySQL 数据后，从 Redis 删除缓存。如下图所示：

![getTestDemo 方法](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/14.png)

补充说明：

如果你在多个项目里，使用了 Redis 想通 db 的话，可以通过 `spring.cache.redis.key-prefix` 解决，可见 [https://gitee.com/zhijiantianya/ruoyi-vue-pro/pulls/998/ (opens new window)](https://gitee.com/zhijiantianya/ruoyi-vue-pro/pulls/998/)

### 2.4 过期时间

Spring Cache 默认使用 `spring.cache.redis.time-to-live` 配置项，设置缓存的过期时间，项目默认为 1 小时。

如果你想自定义过期时间，可以在 `@Cacheable` 注解中的 `cacheNames` 属性中，添加 `#{过期时间}` 后缀，单位是秒。如下图所示：

![过期时间](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/%E8%BF%87%E6%9C%9F%E6%97%B6%E9%97%B4.png)

实现的原来，参考 [《Spring @Cacheable 扩展支持自定义过期时间 》 (opens new window)](https://juejin.cn/post/7102222578026020871) 文章。

## 3. Redis 监控

`yudao-module-infra` 的 [`redis` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/controller/admin/redis/RedisController.java) 模块，提供了 Redis 监控的功能。

点击 \[基础设施 -> 监控中心 -> Redis 监控\] 菜单，可以查看到 Redis 的基础信息、命令统计、内存信息。如下图所示：

![Redis 监控](https://doc.iocoder.cn/img/Redis%E7%BC%93%E5%AD%98/21.png)

[

多数据源（读写分离）、事务

](/dynamic-datasource/)[

本地缓存

](/local-cache/)

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
