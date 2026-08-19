# identity / app-registry 集成调研（OIDC + 参数签名）

> 调研票：[#4](https://github.com/ZhangColin/aiplatform-server/issues/4)（wayfinder:research），产出供 A2 规格票（#6）消费。
>
> 信息源（约定：**不读 aieducenter-identity 源码本体**）：
> - app-registry swagger `http://localhost:8088/v3/api-docs`（已拉取成功）
> - identity swagger `http://localhost:10001/v3/api-docs`（已拉取成功）
> - SSO demo 参考实现 `~/workspace/aieducenter-identity/demo/`（demo-backend + demo-web）
> - 本仓库 `docs/guide/cartisan-boot-使用手册.md` §1.8 + cartisan-openapi 0.1.0-SNAPSHOT 依赖 jar（本仓库 pom 声明的框架模块，反编译核对签名算法）
>
> 调研日期：2026-08-19。两个服务均已启动，swagger 均可访问；无「服务未启动」缺口。

## TL;DR

- 登录走 **OIDC 授权码模式（BFF 形态）**：浏览器 → 我方后端 `/auth/login`（种 state/nonce cookie，302 到 identity `/authorize`）→ identity 登录页建 SSO 会话发 code 302 回我方 `/auth/callback` → 后端持 client_secret 调 `/token` 换三件套 → token 只存服务端会话，浏览器只持业务 cookie。demo 前后端就是这个形态，可照抄。
- 参数签名就是 **cartisan-openapi 同一套 HMAC-SHA256**（identity / app-registry 均基于 cartisan-boot）：我方配 `cartisan.openapi.self.api-key=aiplatform` + apiSecret，用 `OpenApiClient` 自动签名调 identity 的机机端点；apiSecret 由 app-registry 创建/轮换时一次性发放。
- 我方要提交给 app-registry 的：`redirect_uris`、`post_logout_redirect_uris`（独立白名单）、scopes、grants、应用展示名；等对方发的：`client_id`/`client_secret`（一次性明文）与 apiKey 的 apiSecret。
- 联调顺序：**先定前端域名/回调地址（#8）→ app-registry 配 SsoClient + 发凭证 → 我方后端 BFF 三端点 + 签名配置 → 前端接入口 → 浏览器闭环联调**。

---

## 一、OIDC 登录流程图解

### 1.1 identity 的 OIDC 元数据（discovery 实测）

`GET http://localhost:10001/.well-known/openid-configuration`：

| 项 | 值 | 说明 |
|---|---|---|
| issuer | `https://identity.aieducenter.com` | 生产 issuer；local 实际入口 `http://identity.localhost:10001`（demo 配置实证） |
| authorization_endpoint | `/authorize` | 有 SSO 会话发 code 302；无则 302 登录页 |
| token_endpoint | `/token` | code 换 access/id/refresh；refresh 轮换 |
| userinfo_endpoint | `/userinfo` | Bearer access_token 换资料（按 scope） |
| jwks_uri | `/jwks` | RS256 验签公钥（实测单把 RSA，`kid=identity-rs256-v1`） |
| end_session_endpoint | `/logout` | RP-initiated 登出 |
| scopes_supported | `openid profile email phone` | |
| response_types_supported | `code` | **纯授权码**，无 implicit |
| grant_types_supported | `authorization_code`, `refresh_token` | 无 password 等其它 grant |
| id_token_signing_alg | `RS256` | 非对称，可本地 JWKS 验签 |
| token_endpoint_auth | `client_secret_post` | client 凭据放 form body（demo 实证） |
| front/back-channel logout | 均不支持；`post_logout_redirect_uris_supported: true` | 登出走 RP-initiated + 白名单回跳 |

### 1.2 端到端时序（demo 实证，我方可照抄的 BFF 形态）

```
浏览器(demo-web)         我方后端BFF(demo-backend:10010)      identity(:10001)         app-registry(:8088)
    │ ① <a href="/auth/login">                                 │                        │
    │────（Next rewrites 同源代理 /auth/* → BFF）──────────────►│                        │
    │                          ② 生成 state+nonce              │                        │
    │                          种 oauth_txn cookie              │                        │
    │◄───302 /authorize?client_id&redirect_uri&response_type=code&scope&state&nonce──────┤
    │──────────────────────────────────────────────────────────►│                        │
    │                          ③ 无 SSO 会话 → 302 登录页        │                        │
    │   （登录页浏览器闭环：密码 /api/sso/login、验证码 /api/sso/login-code、               │
    │     注册即登录 /api/sso/register；均 200 {redirectUrl}，SPA fetch 读体后顶层导航）     │
    │                          ④ 建 SSO 会话 + 种 sso_session cookie + 发 code             │
    │◄───302 {redirect_uri}?code=...&state=... ─────────────────┤                        │
    │──── /auth/callback?code&state ──►│                        │                        │
    │                          ⑤ state 与 oauth_txn cookie 精确比对（防 CSRF）            │
    │                          ⑥ POST /token（form：grant_type=authorization_code          │
    │                             &code&redirect_uri&client_id&client_secret）──────────►│
    │                          ◄─ {access_token, refresh_token, id_token, expires_in} ───┤
    │                          ⑦ 生成不透明 sessionId，token 三件套只存服务端会话          │
    │◄─ Set-Cookie: demo_session（HttpOnly+SameSite=Lax）；清 oauth_txn                   │
    │ ⑧ fetch('/api/me') ───►│ ⑨ 凭 cookie 取会话 → 解 id_token claims（sub/email/       │
    │                          nickname/picture）返回；无会话 401                            │
    │                                                                                     │
    │ ⑩ 登出 POST /auth/logout ─►│ ⑪ 先取 id_token 作 hint + 删本地会话                    │
    │◄───302 /logout?client_id&post_logout_redirect_uri&state&id_token_hint ──────────────┤
    │                          ⑫ identity 清 SSO 会话+cookie → 白名单匹配则 302 回跳      │
```

### 1.3 关键实现要点（demo 代码逐条对应）

| 要点 | demo 出处 | 我方落地 |
|---|---|---|
| 发起：`GET /auth/login` 生成 `state`+`nonce`（各 32 字节随机 URL-safe），种 `oauth_txn` cookie（值 `"<state>:<nonce>"`，HttpOnly + SameSite=Lax，maxAge 600s），302 到 `/authorize` | `AuthController#login` | 后端做，参数七项见 §三 |
| 回调：`GET /auth/callback` 校验 state（cookie state 段精确比对）→ 换 token 失败统一回 `/?error=state_mismatch` 或 `/?error=exchange_failed`，具体原因（status + `{error, error_description}`）只进日志 | `AuthController#callback`、`TokenExchangeException` | 照抄；错误页路由由前端定 |
| 换 token：`POST {issuer}/token`，**form-urlencoded body** 带 `grant_type=authorization_code, code, redirect_uri, client_id, client_secret` | `OidcClient#exchangeCode` | 照抄；client_secret 只存后端 |
| 会话：token 三件套存服务端（demo 用内存 Map，进程重启即丢）→ 我方需换 Redis/DB；浏览器只持不透明 `demo_session` cookie（HttpOnly + SameSite=Lax，无 maxAge=会话级） | `BffSessionStore` / `BffSession` | 形态保留，存储升级 |
| `/api/me`：凭业务 cookie 取会话 → 解 id_token claims 返 `userId/email/nickname/picture`；无会话 401 | `MeController` | 照抄 |
| id_token 校验：demo **只解不验签**（token 经可信机机通道取得；注释明言 #17 引入 `/jwks` 后可补验签）。**我方生产建议**：拉 `/jwks`（`kid=identity-rs256-v1`，RS256）本地验 id_token 签名 + 校验 `iss`/`aud`(=client_id)/`exp`/`nonce` | `OidcClient#decodeIdToken` | 升级为 JWKS 验签 |
| 登出：`POST /auth/logout` —— ①**先**取 id_token（作 hint）**再**删本地会话（顺序不能反）；②302 到 `/logout?client_id&post_logout_redirect_uri&state&id_token_hint`。**`post_logout_redirect_uri` 是独立白名单**，不登记则 identity 清完会话只返 200 不回跳 | `AuthController#logout`、`OidcClient#logoutUrl` | 照抄 |
| cookie Secure 标志：`sso.cookie-secure` local 显式 false（`http://*.localhost` 下 Safari/Firefox 拒存 Secure cookie → `oauth_txn` 落不了地 → callback 恒 state_mismatch）；生产 https 必须 true | `SsoProperties` + `application-local.yml`（issue #37 踩坑记录） | local 关、prod 开 |
| 前端零 OAuth 知识：登录=一个 `<a href="/auth/login">`，登出=一个 form POST `/auth/logout`，会话=`fetch('/api/me', {credentials:'same-origin'})`；Next 用 rewrites 把 `/api/*`、`/auth/*` 同源代理到 BFF（cookie SameSite=Lax 的前提） | `demo-web/src/app/page.tsx`、`src/lib/api.ts`、`next.config.mjs` | 照抄同源代理姿态 |

### 1.4 token 校验方式小结

- **id_token**：RS256 JWT，JWKS 可本地验签（demo 未做，属 demo 简化；我方应做）。
- **access_token**：形态（JWT or opaque）swagger 未标明（见 §五缺口-3）。稳妥做法：登录态以我方服务端会话为准（BFF 形态下 access_token 不出后端），需要用户资料时优先解 id_token claims 或调 `/userinfo`（Bearer）。
- **refresh_token**：`/token` 支持 `refresh_token` grant 且「refresh 轮换」（旧 refresh 用后作废换新）——具体轮换窗口/宽限 swagger 未写（缺口-6）。

## 二、app-registry 参数签名调用规范（与 cartisan-openapi 的对应）

### 2.1 结论：就是 cartisan-openapi 那一套

identity 与 app-registry 都基于 cartisan-boot；identity swagger 里标注 `@RequireSignature` 的机机端点（`Account / Signed`、`Verification / Signed`、`Captcha / Signed`）由 `SignatureVerificationFilter` 验签，验签凭证（apiKey → apiSecret）从 app-registry 的 bootstrap 端点拉取。我方后端已依赖 `cartisan-openapi`（本仓库 pom 已声明），**直接用 `OpenApiClient` 自动签名即可，无需手写签名**。以下算法细节供排障与非 Java 调用方参考（自 cartisan-openapi 0.1.0-SNAPSHOT 字节码逐条核对）。

### 2.2 签名算法与请求头

| 项 | 规范 |
|---|---|
| 签名算法 | `HMAC-SHA256(待签名串, apiSecret)`，输出**小写 hex**（`HexFormat.of().formatHex`） |
| timestamp | 秒级（`System.currentTimeMillis()/1000`），服务端容差默认 ±300s（`cartisan.openapi.timestamp-tolerance`） |
| nonce | 每次请求新生成：UUID 去横线；防重放 TTL 默认 300s，**需业务侧提供 Redis `NonceRepository`**（OPENAPI-003） |
| bodyDigest | `SHA-256(请求体字节)` 小写 hex；GET/无 body 时对空体计算，仍是必填参数 |
| 待签名串 | 参数放入 TreeMap 按 key **字典序**，拼 `k1=v1&k2=v2&...`。基础四参：`apiKey`、`bodyDigest`、`nonce`、`timestamp`；**GET 请求 URL 的 query 参数也并入同一 TreeMap 参与签名**（OPENAPI-005：改 query 即改签名） |
| 签名请求头 | `X-Api-Key`、`X-Timestamp`、`X-Nonce`、`X-Body-Digest`、`X-Sign` |
| 上下文透传头（不参与签名） | `X-Request-Id`、`X-Client-Ip`、`X-User-Id`、`X-User-Name`、`X-Tenant-Id`、`X-Tenant-Name` |

### 2.3 我方调用配置（apiKey=aiplatform）

```yaml
cartisan:
  openapi:
    self:
      api-key: aiplatform
      api-secret: ${AIPLATFORM_API_SECRET}   # app-registry 创建/轮换时一次性发放，勿入库入仓
    apikey-service-url: http://localhost:8088/api/app-registry/api-keys   # 验收方拉凭证用；我们作为调用方主要用 self 凭据
    timestamp-tolerance: 300
    nonce-ttl: 300                            # 若我方也要验别方签名，需提供 Redis NonceRepository
```

- 调用方式：注入 `OpenApiClient`，`post(url, body, TypeReference)` / `get(url, TypeReference)`（query 参与签名由客户端处理）；非 2xx 抛 `OpenApiClientException(statusCode, body)`（手册 §2.26/§3.26）。
- 若我方后端**作为被调方**想验别家签名：`@RequireSignature` 注解 + `RemoteApiKeyProvider`（指向 app-registry bootstrap `GET /api/app-registry/api-keys/{apiKey}`，返回 `ApiKeyInfo{apiKey, apiSecret, appName}` 含明文 secret；本地 Caffeine 缓存 30 分钟，OPENAPI-004）。
- **凭证生命周期（app-registry swagger）**：`POST /api/app-registry/apps/{appId}/api-keys`「创建或轮换 ApiKey——已有则轮换、否则新建；**响应一次性返回明文 apiSecret**」；`GET .../api-keys` 只回脱敏视图。轮换即旧 secret 失效，需与我方部署节奏配合（缓存 30 分钟 + 轮换窗口）。

### 2.4 identity 侧可签名调用的端点（我们真正要用的）

| 端点（identity） | 用途 | 备注 |
|---|---|---|
| `POST /api/account/authenticate` | email/phone + 密码验密 → SubjectView（不发 token 不建会话） | 防枚举：账号不存在与密码错统一 401 ACCOUNT_009 |
| `POST /api/account/authenticate-by-code` | 验证码登录 → SubjectView | purpose=LOGIN |
| `POST /api/account/register` | email/phone + 已验码注册 → SubjectView（注册即登录、不发 token） | purpose=REGISTER |
| `POST /api/verification-codes` / `.../verify` | 发码（EMAIL/SMS × purpose）/ 裸验码 | 图形码可选，防轰炸靠 per-ip 限流 |
| `GET /api/captchas` | 取一次性图形码（base64 + captchaId，Redis 3min） | 给短信发码加防脚本层时用 |
| `GET /api/account/find`、`GET /api/account/{userId}` | 按 email/phone / userId 查 SubjectView（userId/email/phone/nickname/avatar/status） | 纯读、无 gate |
| `PUT /api/account/{userId}/profile` | 按 userId 改昵称/头像（非空字段） | |
| `POST /api/account/reset-password`、`POST /api/account/{userId}/change-password` | 重置/改密（改后踢会话） | |

注意：identity 另有一组 `@RequireManagementCaller` 白名单 gate 的 admin 端点（搜索/封号/踢人/解锁，默认白名单 admin-console）——**非白名单签名 403**。aiplatform 是否入白名单未知（缺口-7）；A2 规格若需要管理能力要跟 identity 侧确认。

## 三、我方需准备/提交的配置项清单

### 3.1 需**提交给 identity 侧**（经 app-registry 登记，凭 appId 操作）

| 配置项 | 值（待 #8 定域名后填） | 通道 | 谁提供 |
|---|---|---|---|
| `redirect_uris`（必填，精确匹配白名单） | `https://<aiplatform-web 域名>/auth/callback`（local：`http://<host>:<port>/auth/callback`） | `PUT /api/app-registry/apps/{appId}/sso-clients`（整份替换） | 我方提供（前端域名定稿后） |
| `post_logout_redirect_uris`（必填，**独立白名单**） | `https://<域名>/`（登出回首页） | 同上 | 我方提供 |
| `scopes` | `openid`（+ 按需 `profile email phone`，demo 用 `openid profile`） | 同上 | 我方确认 |
| `grants` | `authorization_code` + `refresh_token` | 同上 | 我方确认 |
| 应用展示名 `name`（≤128）/ `description`（≤512） | 「AI 平台」等——登录页「登录到 XXX 应用」展示用（identity `GET /api/sso/client-info` 回 clientId+clientName） | `POST /api/app-registry/apps` 或 `PUT /api/app-registry/apps/{id}` | 我方提供 |

### 3.2 需**向 identity/app-registry 侧领取**的

| 凭据 | 获取方式 | 备注 |
|---|---|---|
| `client_id` + `client_secret` | `POST /api/app-registry/apps/{appId}/sso-clients/credentials`（无则创建，有则仅重置 secret；**client_id 终身稳定，secret 一次性明文返回**） | 存我方后端环境变量；轮换 secret 不换 client_id |
| apiKey=aiplatform 的 `apiSecret` | `POST /api/app-registry/apps/{appId}/api-keys`（一次性明文） | 已注册 apiKey=aiplatform；secret 由持管理权限者发放 |
| 各环境 identity issuer 地址 | 人工告知（local：`http://identity.localhost:10001`；生产 discovery 标 `https://identity.aieducenter.com`） | discovery 端点可直接探测 |

### 3.3 我方**后端**（aiplatform-server）配置（对照 demo `SsoProperties` 七项 + openapi 两项）

| 配置 | demo local 参考值 | 说明 |
|---|---|---|
| `sso.issuer` | `http://identity.localhost:10001` | 各环境不同 |
| `sso.client-id` / `sso.client-secret` | 领取后填 | secret 只进环境变量，浏览器永不接触 |
| `sso.redirect-uri` | `http://demo.localhost:3000/auth/callback` | **指向前端入口**（经代理到后端），须与登记白名单逐字一致 |
| `sso.scope` | `openid profile` | |
| `sso.app-base-url` | `http://demo.localhost:3000` | 登录/登出后回跳 |
| `sso.cookie-secure` | local `false` / prod `true` | Safari/Firefox 在 `http://*.localhost` 拒存 Secure cookie 的坑（demo issue #37） |
| `cartisan.openapi.self.api-key/api-secret` | `aiplatform` / 领取 | 签名调用 identity 机机端点 |

### 3.4 我方**前端**（aiplatform-web）准备项

- 同源代理 `/api/*`、`/auth/*` 到后端（demo 用 Next `rewrites`，`BACKEND_URL` 环境变量）——业务 cookie SameSite=Lax 的前提。
- 页面三件：登录入口 `<a href="/auth/login">`、登出 form `POST /auth/logout`、启动时 `fetch('/api/me')` 判登录态；错误态处理 `?error=state_mismatch|exchange_failed`。
- **不持有**任何 OAuth 凭据/token——BFF 形态下前端零密钥。

## 四、联调等待点与顺序

```
[#8 定前端域名/回调地址]──►[app-registry：PUT sso-clients 白名单 + POST credentials 发 client 凭据
                                     + POST api-keys 发 aiplatform apiSecret]
        ──►[我方后端：BFF 三端点(/auth/login /auth/callback /auth/logout) + /api/me
              + sso.* / cartisan.openapi.* 配置 + JWKS 验签 + 会话存储(Redis)]
        ──►[我方前端：登录入口/回调落地/me 消费 + 同源代理]
        ──►[联调：浏览器闭环 authorize→登录→code→token→me→logout；
              签名链路单独冒烟：OpenApiClient GET /api/account/find]
```

- **identity/app-registry 侧等我方**：① `redirect_uri` / `post_logout_redirect_uri`（依赖 #8 定域名——这是当前最大前置）；② scopes / grants 确认；③ 应用展示名（如尚未登记完整）。
- **我方等 identity 侧**：① client_id/client_secret 发放；② apiKey=aiplatform 的 apiSecret 发放；③ 各环境 issuer 地址确认（local 已知）；④ （若 A2 需要管理端点）确认 aiplatform 是否入 `@RequireManagementCaller` 白名单。
- 可并行项：后端 BFF 骨架与签名客户端不依赖凭据即可先行开发（demo 即完整参考实现）；`/jwks`、`/.well-known/openid-configuration`、`/api/sso/client-info` 均为公开只读端点，无凭据即可联。

## 五、信息缺口清单

1. **我方 appId 未知**：apiKey=aiplatform 已注册，但其在 app-registry 的 `appId`（配置 SsoClient / 发凭证都要路径参数）需持凭据者调 `GET /api/app-registry/apps` 查询或直接告知。本调研无 apiSecret，无法代查。
2. **app-registry 管理端点的鉴权主体不明**：swagger 看不出 Apps/SsoClients/ApiKeys CRUD 由谁调用（管理台？持特定 apiKey 的签名调用方？）。即「谁替我们 PUT 白名单、发凭据」待与 identity 侧确认（影响 #8 的交付对象）。
3. **access_token 形态未知**（JWT 可本地验签 or opaque 必须调 `/userinfo`）：swagger 与 demo 均未证实。BFF 形态下影响小，但若 A2 要拿 access_token 调其它服务则需实测。
4. **id_token 的 nonce 校验与 JWKS 验签是否已在 identity 侧完整落地**：demo 留了 nonce 只校验 state、id_token 不验签（注释归因 #17 待补）。我方应独立做 JWKS 验签，但「identity 是否强校验 nonce」无法从 swagger 断定。
5. **swagger 契约与实际行为的偏差（已实测，提醒 A2）**：`/token` 参数在 swagger 标 `in=query`，实测 **form-urlencoded body** 才被接受（query 方式 415，form 空 body 报 `Missing required parameter: grant_type`）——按 swagger 生成客户端会踩坑，以 demo 实现 + discovery 的 `client_secret_post` 为准。
6. **refresh 轮换语义细节**：仅一句「refresh 轮换」，轮换窗口/旧 token 宽限期未知。
7. **aiplatform 是否在 identity `@RequireManagementCaller` 白名单**（默认 admin-console）：决定管理类端点（搜索/封号/踢人）可用性。
8. **前端域名/端口未定**（依赖 #8）：所有 URL 类配置（3.1/3.3）的值都等它定稿。
9. app-registry local 端口为 8088（swagger servers 声明）；生产环境地址未在本次信息源中出现，需另确认。

---

## 附：demo 关键文件索引（参考实现地图）

| 文件 | 内容 |
|---|---|
| `demo/demo-backend/src/main/java/com/aieducenter/demobff/sso/AuthController.java` | `/auth/login` `/auth/callback` `/auth/logout` 三端点（state/nonce、cookie、错误兜底） |
| `.../sso/OidcClient.java` | authorizeUrl / exchangeCode / logoutUrl / decodeIdToken / randomToken |
| `.../sso/BffSessionStore.java`、`BffSession.java` | 服务端会话存储（内存版，我方换 Redis） |
| `.../sso/MeController.java` | `/api/me` 凭 cookie 返用户 |
| `.../config/SsoProperties.java` | `sso.*` 七项配置 |
| `demo/demo-backend/src/main/resources/application-local.yml` | local 配置全样（含 cookie-secure 坑注解） |
| `demo/demo-web/src/app/page.tsx`、`src/lib/api.ts`、`next.config.mjs` | 前端登录入口 / me 拉取 / 同源代理 |
