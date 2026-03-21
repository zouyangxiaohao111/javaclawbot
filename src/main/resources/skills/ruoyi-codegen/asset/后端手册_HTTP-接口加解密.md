# HTTP 接口加解密 | ruoyi-vue-pro 开发指南

> **文档信息**
> - **所属章节**: 后端手册
> - **文档大小**: 8.06 KB
> - **优化时间**: 2026/3/3 17:01:51

## 📑 目录

- [1. @ApiEncrypt 注解](#1-apiencrypt-注解)
- [2. API 加密配置](#2-api-加密配置)
  - [2.1 后端配置](#21-后端配置)
  - [2.2 前端配置](#22-前端配置)
  - [2.3 如何生成密钥](#23-如何生成密钥)
- [3. 如何使用？](#3-如何使用)
- [666. 常见问题？](#666-常见问题)

---





**原文链接**: https://doc.iocoder.cn/api-encrypt/

**所属章节**: 后端手册

**爬取时间**: 2026/3/3 15:02:53

---

-   [](/ "首页")
-   开发指南
-   后端手册

[芋道源码](https://www.iocoder.cn "作者")

[2025-08-16](javascript:;)

目录

[1\. @ApiEncrypt 注解](#_1-apiencrypt-注解)

[2\. API 加密配置](#_2-api-加密配置)

[2.1 后端配置](#_2-1-后端配置)

[2.2 前端配置](#_2-2-前端配置)

[2.3 如何生成密钥](#_2-3-如何生成密钥)

[3\. 如何使用？](#_3-如何使用)

[666\. 常见问题？](#_666-常见问题)

# ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAB4AAAAeCAYAAAA7MK6iAAAAAXNSR0IArs4c6QAABGpJREFUSA3tVVtoXFUU3fvOI53UlmCaKIFmwEhsE7QK0ipFEdHEKpXaZGrp15SINsXUWvBDpBgQRKi0+KKoFeJHfZA+ED9KKoIU2gYD9UejTW4rVIzm0VSTziPzuNu1z507dibTTjL4U/DAzLn3nL3X2o91ziX6f9wMFdh6Jvbm9nNSV0msViVO6tN1Rm7NMu2OpeJ9lWBUTDxrJbYTS0hInuwciu9eLHlFxCLCZEk3MegsJmZ5K/JD6t7FkFdEvGUo1g7qJoG3MHImqRIn8/nzY1K9UPKKiJmtnUqHVE3Gbuay6vJE/N2FEmuxFjW2nUuE0yQXRRxLiTUAzs36zhZvOXJPdX850EVnnLZkB8prodQoM5JGj7Xk2mvC7JB8tG04Ef5PiXtG0UtxupRQSfTnBoCy554x18yJHI6I+G5Eru4LHmPJZEQsrvPUbMiA8G/WgMK7w7I+ez7++o2ANfbrjvaOl1tFMs+htG3IrZH9/hDX1Pr8Tc0UvH8tcX29KzAgIGcEkINyW5BF9x891hw6VYqgJHEk0huccS7vh3C6gTiODL+26huuBtbct8eZnqLML8PkxGYpuPZBqtqwkSjgc4mB5gbgig5i+y0UDK35LMxXisn9xQtK+nd26gTIHsHe/oblK/b29fUmN/8Y+9jAQrnBp56m1LcDlDp9irKTExSKduXJVWSqdBMA08pEJnEIOB3FPPMybu/oeV8zFeYN3xx576Q6RH+VmplE4ncQV5v+5rzSoyOU7PuEAg8g803PwBJ0CExno/jcMbN8tONYeOmHiuUNryvm3fRUy4tMPVLdAGkUhNWuggGrJcXPv+ouCjz0MKUHz1J2/E8IC9nqTabcxgaBYM0hPhD5Y65FsbxRQKxCQrDjDctW7PUM3HuZunFyifSAqEfuzCp48Il24luWUWZoyJCaPR82jE0+kFA643wRFVni4RYSq3ohJO2pZ7B5dO4xkDWbEpossJPLSrPjYID8rS2UHTlvyNxqIGsg674XJJ7vnh5L7PNwC4hh2sjCI96mzszOTpxLF0T7l88Yz7lAuK6OnL8gXLOnTvpzSb22YG8W7us3jSebFHeeqnXRG1vt+MoUM84LQIBmMsCTAcOauTh0T0l0neQK7m2bLMt2mGxU3HYssS0J2cdv5wljlPsrIuZLAG/2DOZIXgCYT8uMGZN+e2kSirfxZOPCsC0f24nTZzspnVn9VePS1Z5vubmAGGXG8ZFno9Hel0yfA5ZPhF7Dh972BQJ2qCpgH67lmWtBYbvk6sz02wjky2vXyz0XErP/kFB619js1BtwfOV4OPRqOQBjy3Qbk18vigUPPSD5ceHnwck7W9bhAqZdd7SuG7w4/P2F/GaJh8c7e9qgow+Q7cGBo+98WsLkuktFqiZabtXuQTu/Y5ETbR0v7tNSFnvrmu6pjdoan2KjMu8q/Hmj1EfCO2ZGfEIbIXKUlw8qaX9/b2oeSJmFksSeT/Fn0V3nSypChh4Gjh74ybO9aeZ/AN2dwciu2/MhAAAAAElFTkSuQmCC)HTTP 接口加解密

`yudao-spring-boot-starter-web` 技术组件，由它的 `encrypt` 包，提供 HTTP 接口加解密特性，提高安全性。

它主要由 ApiEncryptFilter 过滤器实现，主要有 2 个功能：

-   请求解密：「前端」请求时，将“请求体”进行加密，然后「后端」 ApiEncryptFilter 负责解密该请求体。
-   响应加密：「后端」响应时，「后端」ApiEncryptFilter 将“响应体”进行加密，然后「前端」进行解密。

疑问：为什么不使用 SpringMVC 的 RequestBodyAdvice 或 ResponseBodyAdvice 机制呢？

考虑到项目中会记录访问日志、异常日志，以及 HTTP API 签名等场景，最好是全局级、且提前做解析！！！

## 1. @ApiEncrypt 注解

在 Controller 类或方法上，添加 `@ApiEncrypt` 注解，声明它 **必须** 加密。它有如下两个属性：

-   `request`：请求加密标识，默认为 `true` 开启加密。
-   `response`：响应加密标识，默认为 `true` 开启加密。

疑问：没有声明 \`@ApiEncrypt\` 注解的接口，可以进行请求加密吗？

可以的，前端请求时，只要请求体进行了加密，后端 ApiEncryptFilter 都会进行解密。

而 `@ApiEncrypt` 注解的作用，还是上面体到的“必须”二字，即不允许明文（未加密）请求。

## 2. API 加密配置

### 2.1 后端配置

后端的 `application.yml` 中，配置 `yudao.api-encrypt` 配置项，配置 API 加密的相关参数：

```
yudao:
  api-encrypt:
    enable: true # 是否开启 API 加密
    algorithm: AES # 加密算法，支持 AES、RSA 等
    request-key: 52549111389893486934626385991395 # 【AES 案例】请求加密的秘钥，，必须 16、24、32 位
    response-key: 96103715984234343991809655248883 # 【AES 案例】响应加密的秘钥，AES 案例，必须 16、24、32 位
#    request-key: MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAKWzasimcZ1icsWDPVdTXcZs1DkOWjI+m9bTQU8aOqflnomkr6QO1WWeSHBHzuJGsTlV/ZY2pFfq/NstKC94hBjx7yioYJvzb2bKN/Uy4j5nM3iCF//u0RiFkkY8j0Bt/EWoFTOb6RHf8cHIAjbYYtP3pYzbpCIwryfe0g//KIuzAgMBAAECgYADDjZrYcpZjR2xr7RbXmGtzYbyUGXwZEAqa3XaWBD51J2iSyOkAlQEDjGmxGQ3vvb4qDHHadWI+3/TKNeDXJUO+xTVJrnismK5BsHyC6dfxlIK/5BAuknryTca/3UoA1yomS9ZlF3Q0wcecaDoEnSmZEaTrp9T3itPAz4KnGjv5QJBAN5mNcfu6iJ5ktNvEdzqcxkKwbXb9Nq1SLnmTvt+d5TPX7eQ9fCwtOfVu5iBLhhZzb5PJ7pSN3Zt6rl5/jPOGv0CQQC+vETX9oe1wbxZSv6/RBGy0Xow6GndbJwvd89PcAJ2h+OJXWtg/rRHB3t9EQm7iis0XbZTapj19E4U6l8EibhvAkEA1CvYpRwmHKu1SqdM+GBnW/2qHlBwwXJvpoK02TOm674HR/4w0+YRQJfkd7LOAgcyxJuJgDTNmtt0MmzS+iNoFQJAMVSUIZ77XoDq69U/qcw7H5qaFcgmiUQr6QL9tTftCyb+LGri+MUnby96OtCLSdvkbLjIDS8GvKYhA7vSM2RDNQJBAKGyVVnFFIrbK3yuwW71yvxQEGoGxlgvZSezZ4vGgqTxrr9HvAtvWLwR6rpe6ybR/x8uUtoW7NRBWgpiIFwjvY4= # 【RSA 案例】请求解密的私钥
#    response-key: MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDh/CHyBcS/zEfVyINVA7+c9Xxl0CPdxPMK1OIjxaLy/7BLfbkoEpI8onQtjuzfpuxCraDem9bu3BMF0pMH95HytI3Vi0kGjaV+WLIalwgc2w37oA2sbsmKzQOP7SDLO5s2QJNAD7kVwd+Q5rqaLu2MO0xVv+0IUJhn83hClC0L5wIDAQAB # 【RSA 案例】响应加密的公钥
```

① `algorithm` 支持 AES、RSA 两种加密算法。

疑问：如何支持 SM2、SM4 等国密算法？

ApiEncryptFilter 的构造方法里，有创建加解密 Encryptor、Decryptor 的逻辑，增加下对应的逻辑即可，使用 hutool 工具类的 SM2、SM4 即可。 当然，要注意引入下 SM2、SM4 的 Maven 依赖噢。

② `request-key` 配置项：请求的解密密钥。

-   如果是【对称加密】时，它「后端」对应的是“密钥”。对应的，「前端」也对应的也是“密钥”。
-   如果是【非对称加密】时，它「后端」对应的是“私钥”。对应的，「前端」对应的是“公钥”。（重要！！！）

对加密算法了解不多的同学，可以看看 [《什么是公钥和私钥？》 (opens new window)](https://help.aliyun.com/zh/ssl-certificate/what-are-a-public-key-and-a-private-key) 文档。

③ `response-key` 配置项：响应的加密密钥。

-   如果是【对称加密】时，它「后端」对应的是“密钥”。对应的，「前端」也对应的也是“密钥”。
-   如果是【非对称加密】时，它「后端」对应的是“公钥”。对应的，「前端」对应的是“私钥”。（重要！！！）

疑问：为什么在使用 AES 算法时，密钥使用 RSA 动态生成呢？

从安全性来说，直接使用 RSA 和 RSA + AES 是基本一致的。

RSA + AES 相比 RSA 的优势，主要还是性能方便，因为对称加密 body，比非对称加密 body 性能更好。

不过考虑到 RSA + AES 对大家的理解成本比较高，并且这块的性能在业务系统（MySQL 操作）相关的性能损耗，基本可以相对忽略不计哈。

### 2.2 前端配置

前端的 `env` 中，配置 `_API_ENCRYPT_` 配置项，配置 API 加密的相关参数：

```
VITE_APP_API_ENCRYPT_ENABLE = true
VITE_APP_API_ENCRYPT_HEADER = X-Api-Encrypt
VITE_APP_API_ENCRYPT_ALGORITHM = AES
VITE_APP_API_ENCRYPT_REQUEST_KEY = 52549111389893486934626385991395
VITE_APP_API_ENCRYPT_RESPONSE_KEY = 96103715984234343991809655248883
# VITE_APP_API_ENCRYPT_REQUEST_KEY = MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCls2rIpnGdYnLFgz1XU13GbNQ5DloyPpvW00FPGjqn5Z6JpK+kDtVlnkhwR87iRrE5Vf2WNqRX6vzbLSgveIQY8e8oqGCb829myjf1MuI+ZzN4ghf/7tEYhZJGPI9AbfxFqBUzm+kR3/HByAI22GLT96WM26QiMK8n3tIP/yiLswIDAQAB
# VITE_APP_API_ENCRYPT_RESPONSE_KEY = MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAOH8IfIFxL/MR9XIg1UDv5z1fGXQI93E8wrU4iPFovL/sEt9uSgSkjyidC2O7N+m7EKtoN6b1u7cEwXSkwf3kfK0jdWLSQaNpX5YshqXCBzbDfugDaxuyYrNA4/tIMs7mzZAk0APuRXB35Dmupou7Yw7TFW/7QhQmGfzeEKULQvnAgMBAAECgYAw8LqlQGyQoPv5p3gRxEMOCfgL0JzD3XBJKztiRd35RDh40Nx1ejgjW4dPioFwGiVWd2W8cAGHLzALdcQT2KDJh+T/tsd4SPmI6uSBBK6Ff2DkO+kFFcuYvfclQQKqxma5CaZOSqhgenacmgTMFeg2eKlY3symV6JlFNu/IKU42QJBAOhxAK/Eq3e61aYQV2JSguhMR3b8NXJJRroRs/QHEanksJtl+M+2qhkC9nQVXBmBkndnkU/l2tYcHfSBlAyFySMCQQD445tgm/J2b6qMQmuUGQAYDN8FIkHjeKmha+l/fv0igWm8NDlBAem91lNDIPBUzHL1X1+pcts5bjmq99YdOnhtAkAg2J8dN3B3idpZDiQbC8fd5bGPmdI/pSUudAP27uzLEjr2qrE/QPPGdwm2m7IZFJtK7kK1hKio6u48t/bg0iL7AkEAuUUs94h+v702Fnym+jJ2CHEkXvz2US8UDs52nWrZYiM1o1y4tfSHm8H8bv8JCAa9GHyriEawfBraILOmllFdLQJAQSRZy4wmlaG48MhVXodB85X+VZ9krGXZ2TLhz7kz9iuToy53l9jTkESt6L5BfBDCVdIwcXLYgK+8KFdHN5W7HQ==
```

### 2.3 如何生成密钥

后端的 ApiEncryptTest 单测类，提供的 `#testGenerateAsymmetric(...)` 方法，可以生成 RSA 公私钥、AES 密钥。

当然，你也可以使用 [http://web.chacuo.net/netrsakeypair (opens new window)](http://web.chacuo.net/netrsakeypair) 生成。

注意！！！生成后，`requestClientKey` 和 `responseClientKey` 是给前端的，`requestServerKey` 和 `responseServerKey` 是给后端的。

疑问：使用 AES 算法时，\`request\` 和 \`response\` 的密钥，可以用相同的？

没问题，AES 算法是对称加密算法，前后端可以使用相同的密钥。

疑问：使用 RSA 算法时，\`request\` 和 \`response\` 的密钥，可以用相同的？

不可以，RSA 算法是非对称加密算法，前后端需要使用不同的密钥。

## 3. 如何使用？

① 第一步【可选】：在 Controller 类或方法上，添加 `@ApiEncrypt` 注解。例如说 AuthController 的 `#login(...)` 方法：

```
    @PostMapping("/login")
    @PermitAll
    @ApiEncrypt // 这里，这里，这里！！！
    @Operation(summary = "使用账号密码登录")
    public CommonResult<AuthLoginRespVO> login(@RequestBody @Valid AuthLoginReqVO reqVO) {
        return success(authService.login(reqVO));
    }
```

② 第二步：前端请求时，在 request 的 `headers` 里添加如下：

```
headers: {
    isEncrypt: true
}
```

例如说：vue3 + element-plus 管理后台的 `#login(...)` 方法：

```
export const login = (data: UserLoginVO) => {
  return request.post({
    url: '/system/auth/login',
    data,
    headers: {
      isEncrypt: true
    }
  })
}
```

再例如说：vben 管理后台的 `#login(...)` 方法：

```
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/system/auth/login', data, {
    headers: {
      isEncrypt: true,
    },
  });
}
```

## 666. 常见问题？

① AES-256 失败，秘钥长度受限制问题？

参见 [《Java 解密 AES-256 失败，秘钥长度受限制问题》 (opens new window)](https://blog.csdn.net/LiZhen314/article/details/143357715)

[

HTTP 接口签名（防篡改）

](/http-sign/)[

单元测试

](/unit-test/)

---

## 📚 相关文档

- [Excel 导入导出 | ruoyi-vue-pro 开发指南](后端手册_Excel-导入导出.md) (同章节)
- [HTTP 接口签名（防篡改） | ruoyi-vue-pro 开发指南](后端手册_HTTP-接口签名（防篡改）.md) (同章节)
- [MyBatis 数据库 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-数据库.md) (同章节)
- [MyBatis 联表&分页查询 | ruoyi-vue-pro 开发指南](后端手册_MyBatis-联表&分页查询.md) (同章节)
- [OAuth 2.0（SSO 单点登录) | ruoyi-vue-pro 开发指南](后端手册_OAuth-2.0（SSO-单点登录).md) (同章节)


---

<div align="center">

[返回首页](README.md) | [查看目录](README.md#后端手册)

</div>
