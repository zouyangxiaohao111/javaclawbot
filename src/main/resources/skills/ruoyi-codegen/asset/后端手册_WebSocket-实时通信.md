# WebSocket 实时通信 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 14.54 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. 功能简介](#1-功能简介)
  - [1.1 Token 身份认证](#11-token-身份认证)
  - [1.2 Session 会话管理](#12-session-会话管理)
  - [1.3 Message 消息格式](#13-message-消息格式)
  - [1.4 Message 消息接收](#14-message-消息接收)
  - [1.5 Message 消息推送](#15-message-消息推送)
- [2. 使用方案](#2-使用方案)
  - [2.1 方案一：纯 WebSocket](#21-方案一纯-websocket)
  - [2.2 方案二：WebSocket + HTTP](#22-方案二websocket-http)
  - [2.3 如何选择？](#23-如何选择)
- [3. 实战案例](#3-实战案例)
- [666. 社区贡献相关](#666-社区贡献相关)

---





**原文链接**: https://doc.iocoder.cn/websocket/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:22

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2023-11-23](javascript:;)

目录

[1\. 功能简介](#_1-功能简介)

[1.1 Token 身份认证](#_1-1-token-身份认证)

[1.2 Session 会话管理](#_1-2-session-会话管理)

[1.3 Message 消息格式](#_1-3-message-消息格式)

[1.4 Message 消息接收](#_1-4-message-消息接收)

[1.5 Message 消息推送](#_1-5-message-消息推送)

[2\. 使用方案](#_2-使用方案)

[2.1 方案一：纯 WebSocket](#_2-1-方案一-纯-websocket)

[2.2 方案二：WebSocket + HTTP](#_2-2-方案二-websocket-http)

[2.3 如何选择？](#_2-3-如何选择)

[3\. 实战案例](#_3-实战案例)

[666\. 社区贡献相关](#_666-社区贡献相关)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABGpJREFUSA3tVVtoXFUU3fvOI53UlmCaKIFmwEhsE7QK0ipFEdHEKpXaZGrp15SINsXUWvBDpBgQRKi0+KKoFeJHfZA+ED9KKoIU2gYD9UejTW4rVIzm0VSTziPzuNu1z507dibTTjL4U/DAzLn3nL3X2o91ziX6f9wMFdh6Jvbm9nNSV0msViVO6tN1Rm7NMu2OpeJ9lWBUTDxrJbYTS0hInuwciu9eLHlFxCLCZEk3MegsJmZ5K/JD6t7FkFdEvGUo1g7qJoG3MHImqRIn8/nzY1K9UPKKiJmtnUqHVE3Gbuay6vJE/N2FEmuxFjW2nUuE0yQXRRxLiTUAzs36zhZvOXJPdX850EVnnLZkB8prodQoM5JGj7Xk2mvC7JB8tG04Ef5PiXtG0UtxupRQSfTnBoCy554x18yJHI6I+G5Eru4LHmPJZEQsrvPUbMiA8G/WgMK7w7I+ez7++o2ANfbrjvaOl1tFMs+htG3IrZH9/hDX1Pr8Tc0UvH8tcX29KzAgIGcEkINyW5BF9x891hw6VYqgJHEk0huccS7vh3C6gTiODL+26huuBtbct8eZnqLML8PkxGYpuPZBqtqwkSjgc4mB5gbgig5i+y0UDK35LMxXisn9xQtK+nd26gTIHsHe/oblK/b29fUmN/8Y+9jAQrnBp56m1LcDlDp9irKTExSKduXJVWSqdBMA08pEJnEIOB3FPPMybu/oeV8zFeYN3xx576Q6RH+VmplE4ncQV5v+5rzSoyOU7PuEAg8g803PwBJ0CExno/jcMbN8tONYeOmHiuUNryvm3fRUy4tMPVLdAGkUhNWuggGrJcXPv+ouCjz0MKUHz1J2/E8IC9nqTabcxgaBYM0hPhD5Y65FsbxRQKxCQrDjDctW7PUM3HuZunFyifSAqEfuzCp48Il24luWUWZoyJCaPR82jE0+kFA643wRFVni4RYSq3ohJO2pZ7B5dO4xkDWbEpossJPLSrPjYID8rS2UHTlvyNxqIGsg674XJJ7vnh5L7PNwC4hh2sjCI96mzszOTpxLF0T7l88Yz7lAuK6OnL8gXLOnTvpzSb22YG8W7us3jSebFHeeqnXRG1vt+MoUM84LQIBmMsCTAcOauTh0T0l0neQK7m2bLMt2mGxU3HYssS0J2cdv5wljlPsrIuZLAG/2DOZIXgCYT8uMGZN+e2kSirfxZOPCsC0f24nTZzspnVn9VePS1Z5vubmAGGXG8ZFno9Hel0yfA5ZPhF7Dh972BQJ2qCpgH67lmWtBYbvk6sz02wjky2vXyz0XErP/kFB619js1BtwfOV4OPRqOQBjy3Qbk18vigUPPSD5ceHnwck7W9bhAqZdd7SuG7w4/P2F/GaJh8c7e9qgow+Q7cGBo+98WsLkuktFqiZabtXuQTu/Y5ETbR0v7tNSFnvrmu6pjdoan2KjMu8q/Hmj1EfCO2ZGfEIbIXKUlw8qaX9/b2oeSJmFksSeT/Fn0V3nSypChh4Gjh74ybO9aeZ/AN2dwciu2/MhAAAAAElFTkSuQmCC)WebSocket 实时通信

## 1. 功能简介

项目的 [`yudao-spring-boot-starter-websocket` (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/tree/master/yudao-framework/yudao-spring-boot-starter-websocket) 组件，基于 [Spring WebSocket (opens new window)](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#websocket) 进行二次封装，实现了更加简单的使用方式。例如说，WebSocket 的认证、Session 的管理、WebSocket 集群的消息广播等等。

疑问：为什么不使用 Netty 实现 WebSocket？

Netty 的学习和使用门槛较高，对大家可能不够友好，而 Spring WebSocket 足够满足 99.99% 的场景。

### 1.1 Token 身份认证

① 在 WebSocket 连接建立时，通过 QueryString 的 `token` 参数，进行认证。例如说：`ws://127.0.0.1:48080/ws?token=xxx`。

由于 WebSocket 是基于 HTTP 建立连接，所以它的认证可以复用项目的 [TokenAuthenticationFilter (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java) 实现。

为什么 token 不使用 Header 传递？

WebSocket 不支持 Header 传递，所以只能使用 QueryString 传递。

② 认证完成后，会通过 [LoginUserHandshakeInterceptor (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/security/LoginUserHandshakeInterceptor.java) 拦截器，将用户信息存储到 WebSocket Session 的 `attributes` 中。

这样，后续可以使用 [WebSocketFrameworkUtils (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/util/WebSocketFrameworkUtils.java) 获取用户信息，例如说：

```
// WebSocketFrameworkUtils.java

// ① 获取当前用户
public static LoginUser getLoginUser(WebSocketSession session)

// ② 获得当前用户的类型
public static Integer getLoginUserType(WebSocketSession session)

// ③ 获得当前用户的编号
public static Integer getLoginUserType(WebSocketSession session)

// ④ 获得当前用户的租户编号
public static Long getTenantId(WebSocketSession session)
```

### 1.2 Session 会话管理

每个前端和后端建立的 WebSocket 连接，对应后端的一个 WebSocketSession 会话对象。由于后续需要对 WebSocketSession 进行消息的发送，所以需要进行管理。

① WebSocketSession 的管理，由 [WebSocketSessionManager (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/session/WebSocketSessionManager.java) 定义接口，由 [WebSocketSessionManagerImpl (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/session/WebSocketSessionManagerImpl.java) 具体实现。

```
// 添加和移除 Session
void addSession(WebSocketSession session);
void removeSession(WebSocketSession session);

// 获得 Session，多种维度
WebSocketSession getSession(String id); // Session 编号
Collection<WebSocketSession> getSessionList(Integer userType); // 用户类型
Collection<WebSocketSession> getSessionList(Integer userType, Long userId); // 用户编号
```

② WebSocket 建立和关闭连接时，通过 [WebSocketSessionHandlerDecorator (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/session/WebSocketSessionHandlerDecorator.java) 处理器，分别调用 WebSocketSessionManager 进行 Session 的添加和移除。

### 1.3 Message 消息格式

WebSocket 默认使用“文本”进行通信，而业务需要按照不同类型的消息，进行不同的处理。因此，项目定义了 [JsonWebSocketMessage (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/message/JsonWebSocketMessage.java) 消息对象，包含 `type` 消息类型 + `content` 消息内容。

和 Spring MVC 对比，可以理解为：

标识

方法

参数

Spring MVC

URL + Method 等

Controller 的 Method 方法

QueryString 或 RequestBody 等

项目 WebSocket

`type` 消息类型

[WebSocketMessageListener (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/listener/WebSocketMessageListener.java) 实现类

解析 `content` 消息内容后的 Message 对象

具体 JsonWebSocketMessage 和 WebSocketMessageListener 详细说明，参见「1.4 Message 消息接收」小节。

### 1.4 Message 消息接收

① WebSocket 接收到项目后，会先交给 [JsonWebSocketMessageHandler (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/handler/JsonWebSocketMessageHandler.java) 消息处理器，将消息解析成 JsonWebSocketMessage 对象。

之后，根据 `type` 消息类型，获得到 WebSocketMessageListener 实现类，并将 `content` 消息内容进一步解析成 Message 对象，交给它进行处理。

② 具体案例，可见 [DemoWebSocketMessageListener (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/websocket/DemoWebSocketMessageListener.java)、[DemoSendMessage (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/websocket/message/DemoSendMessage.java) 类。

### 1.5 Message 消息推送

① 项目的 [WebSocketMessageSender (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-framework/yudao-spring-boot-starter-websocket/src/main/java/cn/iocoder/yudao/framework/websocket/core/sender/WebSocketMessageSender.java) 接口，定义了给 Session 发送消息的方法。如下所示：

```
// WebSocketMessageSender.java

// ① 发送消息给指定用户
void send(Integer userType, Long userId, String messageType, String messageContent);
default void sendObject(Integer userType, Long userId, String messageType, Object messageContent) {
    send(userType, userId, messageType, JsonUtils.toJsonString(messageContent));
}

// ② 发送消息给指定用户类型
void send(Integer userType, String messageType, String messageContent);
default void sendObject(Integer userType, String messageType, Object messageContent) {
    send(userType, messageType, JsonUtils.toJsonString(messageContent));
}

// ③ 发送消息给指定 Session
void send(String sessionId, String messageType, String messageContent);
default void sendObject(String sessionId, String messageType, Object messageContent) {
    send(sessionId, messageType, JsonUtils.toJsonString(messageContent));
}
```

② WebSocketMessageSender 有多种实现类，如下：

实现类

是否支持 WebSocket 集群

前置要求

LocalWebSocketMessageSender

❌

无

RedisWebSocketMessageSender

✅

开启 [《消息队列（Redis）》](/message-queue/redis/)

RocketMQWebSocketMessageSender

✅

开启 [《消息队列（RocketMQ）》](/message-queue/rocketmq/)

KafkaWebSocketMessageSender

✅

开启 [《消息队列（Kafka）》](/message-queue/kafka/)

RabbitMQWebSocketMessageSender

✅

开启 [《消息队列（RabbitMQ）》](/message-queue/rabbitmq/)

疑问：什么是 WebSocket 集群？

在后端部署多个 Java 进程时，会形成 WebSocket 集群。此时，就会存在跨进程的消息推送问题。例如说，连接 A 进程的 WebSocket 的用户，想要发送消息给连接 B 进程的 WebSocket 用户。

😁 如何解决呢？消息不直接发送给用户 WebSocketSession，而是先发给 Redis、RocketMQ 等消息队列，再由每个 Java 进程监听该消息，分别判断判断该用户 WebSocket 是否连接的是自己，如果是，则进行消息推送。

默认配置下，使用 LocalWebSocketMessageSender 本地发送消息，不支持 WebSocket 集群。可通过修改 `application.yaml` 配置文件的 `yudao.websocket.sender-type` 来切换，如下：

```
yudao:
  websocket:
    enable: true # websocket的开关
    path: /infra/ws # 路径
    sender-type: redis # 消息发送的类型，可选值为 local、redis、rocketmq、kafka、rabbitmq
    sender-rocketmq:
      topic: ${spring.application.name}-websocket # 消息发送的 RocketMQ Topic
      consumer-group: ${spring.application.name}-websocket-consumer # 消息发送的 RocketMQ Consumer Group
    sender-rabbitmq:
      exchange: ${spring.application.name}-websocket-exchange # 消息发送的 RabbitMQ Exchange
      queue: ${spring.application.name}-websocket-queue # 消息发送的 RabbitMQ Queue
    sender-kafka:
      topic: ${spring.application.name}-websocket # 消息发送的 Kafka Topic
      consumer-group: ${spring.application.name}-websocket-consumer # 消息发送的 Kafka Consumer Group
```

另外，默认的 WebSocket 连接地址是 `ws://127.0.0.1:48080/infra/ws`，可通过 `yudao.websocket.path` 配置项进行修改。

## 2. 使用方案

目前有 2 种使用方案，分别是：

方案名

上行

下行

方案一：纯 WebSocket

WebSocket

WebSocket

方案二：WebSocket + HTTP

HTTP

WebSocket

疑问：什么是上行？什么是下行？

-   上行：指的是“前端”发送消息给“后端”，WebSocket 和 HTTP 都可以。
-   下行：指的是“后端”发送消息给“前端”，只能使用 WebSocket。

友情提示：下文中提到的所有配置，项目都已经配置好。你只需要按照下文的步骤，进行调试即可，了解每个配置的作用即可。

### 2.1 方案一：纯 WebSocket

![WebSocket 测试界面](https://doc.iocoder.cn/img/WebSocket/WebSocket%E6%B5%8B%E8%AF%95%E7%95%8C%E9%9D%A2.png)

-   前端：见 \[基础设施 -> WebSocket 测试\] 菜单，对应 [/views/infra/websocket/index.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-vue3/blob/master/src/views/infra/webSocket/index.vue) 界面
-   后端：见 `yudao-module-infra` 模块，对应 [DemoWebSocketMessageListener (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/websocket/DemoWebSocketMessageListener.java) 监听器

基于 WebSocket 实现的单聊和群聊，暂时不支持消息的持久化（刷新后，消息会消息）。建议，多多调试，更好的理解 WebSocket 流程。

#### [#](#_2-1-1-后端代码) 2.1.1 后端代码

① 在 `yudao-module-infra` 模块的 `pom.xml` 文件中，引入 `yudao-spring-boot-starter-websocket` 依赖。如下所示：

```
    <dependency>
        <groupId>cn.iocoder.boot</groupId>
        <artifactId>yudao-spring-boot-starter-websocket</artifactId>
    </dependency>
```

② 新建 DemoWebSocketMessageListener 类，实现对应消息的处理。如下图所示：

图片纠错：最新版本不区分 yudao-module-infra-api 和 yudao-module-infra-biz 子模块，代码直接合并到 yudao-module-infra 模块的 src 目录下，更适合单体项目

![DemoWebSocketMessageListener 类](https://doc.iocoder.cn/img/WebSocket/DemoWebSocketMessageListener%E7%B1%BB.png)

#### [#](#_2-1-2-前端代码) 2.1.2 前端代码

① 建立 WebSocket 连接，如下图所示：

![WebSocket 连接](https://doc.iocoder.cn/img/WebSocket/WebSocket%E8%BF%9E%E6%8E%A5.png)

② 发送 WebSocket 消息，如下图所示：

![WebSocket 发送消息](https://doc.iocoder.cn/img/WebSocket/WebSocket%E5%8F%91%E9%80%81%E6%B6%88%E6%81%AF.png)

③ 接收 WebSocket 消息。如下图所示：

![WebSocket 接收消息](https://doc.iocoder.cn/img/WebSocket/WebSocket%E6%8E%A5%E6%94%B6%E6%B6%88%E6%81%AF.png)

### 2.2 方案二：WebSocket + HTTP

![公告通知](https://doc.iocoder.cn/img/WebSocket/%E5%85%AC%E5%91%8A%E9%80%9A%E7%9F%A5.png)

-   前端：见 \[系统管理 -> 消息中心 -> 通知公告\] 菜单，对应 [/views/system/notice/index.vue (opens new window)](https://github.com/yudaocode/yudao-ui-admin-vue3/blob/master/src/views/system/notice/index.vue) 界面的【推送】按钮
-   后端：见 `yudao-module-system` 模块，对应 [DemoWebSocketMessageListener (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/yudao-module-infra/src/main/java/cn/iocoder/yudao/module/infra/websocket/DemoWebSocketMessageListener.java) 监听器

点击某条公告的【推送】按钮，仅仅推送给所有在线用户。由于 WebSocket 目前暂时没全局建立，所以还是使用 \[基础设施 -> WebSocket 测试\] 菜单演示。如下图所示：

![公告通知的推送](https://doc.iocoder.cn/img/WebSocket/%E5%85%AC%E5%91%8A%E9%80%9A%E7%9F%A5%E7%9A%84%E6%8E%A8%E9%80%81.png)

#### [#](#_2-2-1-后端代码) 2.2.1 后端代码

【相同】① 在 `yudao-module-infra` 模块的 `pom.xml` 文件中，引入 `yudao-spring-boot-starter-websocket` 依赖。

【不同】② 在 `yudao-module-system` 模块的 `pom.xml` 文件中，引入 `yudao-module-infra` 依赖。如下所示：

```
    <dependency>
        <groupId>cn.iocoder.boot</groupId>
        <artifactId>yudao-module-infra</artifactId>
        <version>${revision}</version>
    </dependency>
```

【不同】③ 在 `yudao-module-system` 模块，在 NoticeController 类中，新建 `#push(...)` 方法，用于推送公告消息。如下图所示：

![NoticeController 推送](https://doc.iocoder.cn/img/WebSocket/NoticeController%E6%8E%A8%E9%80%81.png)

本质上，它替代了方案一的 DemoWebSocketMessageListener 类，走 HTTP 上行消息，替代 WebSocket 上行消息。

疑问：WebSocketSenderApi 是什么？

它是由 `yudao-module-infra` 对 WebSocketMessageSender 的封装，因为只有它（`yudao-module-infra`）可以访问到 WebSocketMessageSender 的实现类，所以需要通过 API 的方式，暴露给其它模块使用。

这也是为什么 `yudao-module-system` 模块，需要引入 `yudao-module-infra-api` 依赖的原因。

#### [#](#_2-2-2-前端代码) 2.2.2 前端代码

【相同】① 建立 WebSocket 连接，和方案一相同，不重复截图。

【不同】② 发送 HTTP 消息，如下图所示：

![HTTP 发送消息](https://doc.iocoder.cn/img/WebSocket/%E5%85%AC%E5%91%8A%E9%80%9A%E7%9F%A5%E7%9A%84%E5%89%8D%E7%AB%AF%E8%B0%83%E7%94%A8.png)

本质上，它替代了方案一的 WebSocket 上行消息，走 HTTP 上行消息。

【相同】③ 接收 WebSocket 消息，和方案一相同，不重复截图。

### 2.3 如何选择？

我个人是倾向于方案二的，使用 HTTP 上行消息，使用 WebSocket 下行消息。原因如下：

① `yudao-module-infra` 扮演一个 WebSocket 服务的角色，可以通过它来主动发送（下行）消息给前端。这样，未来如果使用 MQTT 中间件（例如说，EMQX、阿里云 MQTT、腾讯云 MQTT 等）替换现有 WebSocket 也比较方便。

② HTTP 上行消息，相比 WebSocket 上行消息来说，更加方便，也比较符合我们的编码习惯。

③ 在微服务架构下，多个服务是拆分开的，无法提供相同的 WebSocket 连接。例如说，`yudao-module-infra` 和 `yudao-module-system` 两个服务都需要有 WebSocket 推送能力时，需要前端分别连接它们两个服务。

考虑到 `ruoyi-vue-pro` 和 `yudao-cloud` 架构的统一性，还是只让 `yudao-module-infra` 提供 WebSocket 服务：

-   前端连接 `yudao-module-infra` 的 WebSocket 服务，其它服务通过 `yudao-module-infra` 下行消息。
-   前端 HTTP 上行消息时，还是通过 HTTP 调用各个服务。

ps：如果你只用 `ruoyi-vue-pro` 单体架构，不会存在 ③ 的困扰，方案一也没问题。

## 3. 实战案例

① [《商城 —— 在线客服》](/mall/kefu/)

one more thing~ 后续我们会使用 WebSocket 实现 IM 即时通信功能，敬请期待。

## 666. 社区贡献相关

-   [《Pull Request：补充 WebSocket 握手测试用例》 (opens new window)](https://github.com/YunaiV/ruoyi-vue-pro/pull/833)

[

SaaS 多租户【数据库隔离】

](/saas-tenant/dynamic/)[

异常处理（错误码）

](/exception/)

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
