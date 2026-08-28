# swagger 为唯一契约，SSE 双通道

> 状态：已接受（2026-08-19 · [wayfinder 票 #3](https://github.com/ZhangColin/aiplatform-server/issues/3)）

前后端对接只认 swagger（本机 `http://localhost:8888`，`/v3/api-docs` + `/swagger-ui/index`），不维护任何 REST 对接文档——swagger 由代码生成，系统更新契约即更新。为此全 BC 的 controller 遵守同一套书写约定（本 ADR）；SSE 事件名册另册长期维护（[docs/spec/SSE事件清单.md](../spec/SSE事件清单.md)）。

## 决策

### 契约观

- **swagger 即唯一契约，REST 零对接文档**。前端只记 swagger 地址；demo 时代「docs/contract.md 契约正本」的设想作废。
- **对称原则**：我们消费其他服务（identity、app-registry）也以对方 swagger 为准——不读源码、不猜行为；发现 swagger 与实际行为不符，反馈对方修 swagger，不绕着写。
- 本约定只约束 controller 对外面；base 内部端口归《限界上下文代码编写规范》管。

### REST 约定

- 路径 `/api/*`，资源风格（`/api/projects`、`/api/projects/{id}/agent/task`），**无版本段**——前后端同节奏演进，无独立客户端要兼容承诺；破坏性变更时前端同步改，`/api` 前缀留给将来挂 `v2`。
- 全端点统一 `ApiResponse<T>` / `PageResponse<T>`（cartisan-web）；**成功判定 = HTTP 2xx**，`code/message/errors` 只在错误时有意义，前端不做 `data` 之外的业务判断。**修订（片5c · [票 #24](https://github.com/ZhangColin/aiplatform-server/issues/24)，2026-08-22）**：唯一例外 = 二进制文件下载端点（首例 `GET /api/projects/{id}/source-package`）——JSON 信封装不下文件流，直接返回文件字节（`Content-Type` + `Content-Disposition attachment`），错误路径照常走信封。
- 错误码 `{CONTEXT}_{NNN}`（编写规范既定），前缀注册表：`WSP_`（base.workspace）/ `AGT_`（base.agentengine）/ `KNW_`（base.knowledge）/ `PRJ_`（business.project），预留 `TASK_` / `METER_` / `IDN_`；通用错误复用 `BaseCodeMessage`，不自造。新 BC 建立即注册前缀。
- 分页请求响应**统一 1 基**（配置 `OneIndexedParameters`），`sort=field,desc` 可多值，默认 `size=20`——消掉 Spring「请求 0 基 / 响应 1 基」的错位。

### 鉴权：不用 cartisan-security，BFF 照 identity demo

- **不引入 cartisan-security（Sa-Token 一并不要）**。认证以 identity 服务为正主：OIDC 授权码 + BFF 形态，照 `aieducenter-identity/demo/demo-backend` 实现——`/auth/login`、`/auth/callback`、`/auth/logout` + `/api/me`；自管内存会话（不透明 cookie，token 三件套只在服务端）；配置 `sso.*` 七项。
- cartisan-security 的 `SecurityFilter` 原负责把 userId/userName 写入 RequestContext——改为**自写同等小 filter**（BFF 会话 → RequestContext），业务代码读法不变；401/403 映射进自己的全局异常处理。角色/权限切面（Code-Canvas 授权矩阵）归 A2 票。
- 机机调用走 cartisan-openapi 签名（`X-Api-Key/…/X-Sign`），与用户会话无关，不受影响。
- identity 服务刚建，swagger 与行为不符处协作调整（例：`/token` swagger 误标 query、实测 form body）。
- swagger 调试：浏览器同源先 `/auth/login` 登录，cookie 由同源请求自动携带（行为未实测，见 PoC 清单）。

### SSE：双通道

| | `GET /api/events`（平台通知） | `GET /api/agent-events`（agent 流） |
|---|---|---|
| 内容 | 状态变化广播（`workspace-created` 等） | 智能体运行过程流（`task-start`…`task-finish`） |
| 消费者 | 门户布局级常开 | 任务进度页组件级，看某个运行才挂 |
| id | `{projectId}:{seq}` | `{runId}:{seq}` |
| 补发 | 永不（REST 重查兜底） | Phase A 不做，ID 格式从第一天留缝 |

- 两通道**只共用 SSE 技术，是两回事**：平台通知是状态变化的呈现信号（低频、永不补发）；agent 流是与 LLM 交互过程流的细化（一次运行一连串增量，AgentScope event 同构，高频，补发有将来）。拆通道同时消掉 demo 的三层嵌套信封。
- 统一信封：SSE name 恒为 `event`；`data = {type, payload, ts}`（ISO-8601）；**payload 恒为对象且必带关联字段**（通知：`projectId`；agent 流：`projectId` + `runId`，`sessionId` 有则带）；payload 内禁用 `type` 键名（demo 的项目类型字段更名 `projectType`）。
- 寻址：过滤参数与信封字段同名（`?projectId=`），缺省全量（开发平台视角）；Phase A 只实现 `projectId` 过滤，`userId` 可见性过滤 A2 后加——实现分期，契约从第一天寻址完备（参考 Replit：按项目按任务，不按人）。
- 心跳：每 15s 发 SSE 注释行 `:ping`（不进前端 listener，防 Next 代理掐空闲连接）。
- 重连：EventSource 自动重连，前端重连后 REST 重拉对齐；事件只让 UI「活」，不承担正确性。
- 事件名册正本：[docs/spec/SSE事件清单.md](../spec/SSE事件清单.md)（双通道两节）；代码侧每 BC 一个 `XxxEventTypes` 常量类，禁止字符串字面量散落；swagger 端点描述嵌精简表指向正本；新增顶层 type 必须先进清单（review 检查）。

### 事件产生机制与概念定位

- **编排层发射制**：平台通知由 application service 在**副作用真实落定后**（落库/容器就绪之后）调 EventHub 广播；base 区不发 SSE（base.workspace 不知道 `projectId`，「底座零业务概念」的自然推论）；agent 流由适配器回调透传。EventHub 是纯技术广播组件（fire-and-forget：发送失败只记日志，不影响业务事务）。
  - **修订（#61，置备异步化）**：`workspace-created` 的「副作用真实落定」从「容器就绪」收窄为「**工作区记录（PROVISIONING 态）落库**」——docker 置备转后台收敛，创建即发射、容器后台置备中。语义正本见 [SSE事件清单](../spec/SSE事件清单.md)（「信号非权威，状态以 REST 查询 `status` 为准」）。
- **SSE ≠ 应用事件**。cartisan-boot 的应用事件（BC 间/跨服务，进程内 → 将来消息中间件）与 SSE 呈现通道是两个机制，概念与实现都不混；将来框架层也不合并（桥接至多做成可选项）。
- 事实命名与将来应用事件对齐（`WorkspaceCreated` 等），但**应用事件管道 Phase A 不架**——今天没有任何后端订阅方（A6 计量走直调上报）。触发条件：环境闲置回收（base 定时回收要通知 business，第一个只能发事件的单向场景）/ 第一个后端订阅方出现 / 跨服务。
- **修订（A1 · [票 #5](https://github.com/ZhangColin/aiplatform-server/issues/5)，2026-08-20）**：上条细化为——① 业务内 `TaskCompleted`（task→project 回填编排，已有真实订阅方）与 ② base 生命周期事件**发布端**（WorkspaceCreated/Destroyed/PreviewReady，cartisan 应用事件 + Spring 发布器）随各自切片就位；outbox/事件存储/重放等管道设施仍不建；SSE 呈现通道归属不变（业务编排层发射，base 不发 SSE）。详见 [A1 规格](../spec/A1-底座四口子规格.md) §4。
- **修订（片2a · [票 #20](https://github.com/ZhangColin/aiplatform-server/issues/20)，2026-08-22）**：agent 流通道（`GET /api/agent-events`）落地。底座任务端点（`POST /api/workspaces/{id}/agent/tasks`）直发的事件关联字段为 `runId` + `workspaceId`（「payload 必带 projectId + runId」自片5 业务编排桥接接管发射起对业务直发事件成立）；订阅过滤 `?runId=` / `?workspaceId=` / `?projectId=`（同名规则，可叠用）。增量性为已知限制：opencode message 同步返回，parts 于 run 结束整批透传（demo 同构，逐 part 实时增量 = opencode 事件总线，PoC 升级路径）。
- **runId**：一次任务下发的运行标识，`POST …/agent/task` 生成并随响应返回，该运行全部流事件携带；与 `sessionId`（跨运行会话寻址）并存不混淆。词表见 CONTEXT.md「运行（Run）」。

### SpringDoc 与落码归属

- swagger 分组按 BC（`GroupedOpenApi.packagesToScan`）；tag/summary 中文，路径/字段/schema 名英文。
- 落码归属：**片 0**：工程基线不含 cartisan-security 依赖、SpringDoc 全局配置、1 基分页配置、401/403 全局映射；**片 1**：EventHub 传输内核（emitter 管理 / 心跳 / predicate 过滤订阅 / 信封与 id 分配）+ `GET /api/events` + 端点描述；**片 2**：`GET /api/agent-events` 通道、runId 生成返回、`EventTypes` 常量类；BFF 三端点落码随 A2。

## Considered Options

- **单通道混双类事件（demo 形态）**：否——重连语义冲突（补发只对 agent 流有意义，混通道 Last-Event-ID 无法自洽）、频率差三个数量级、消费者姿态不同。
- **生命周期事件即应用事件、先架 Spring ApplicationEvent 管道**：否——零后端订阅方的发布订阅 = 绕一跳的直接调用（正是 cartisan 砍领域事件的理由）；且发射方/关联字段对不上（base 只知 workspaceId，SSE 要 projectId）。
- **`/api/v1` 版本段**：否——无独立客户端，仪式感成本。
- **SSE 事件 schema 做进 swagger**：否——为文档生成造无运行时用途的类；名册正本 + 常量类双轨更轻。
- **WebSocket**：否——单向推送够用。

## Consequences

- 通知通道让前端可以「活」，但**正确性永远走 REST**：事件丢失或未连接，页面照常工作。
- SSE 传输内核（emitter/心跳/过滤/信封）零业务概念——**先用后提**：片 1/2/5 双通道遛熟（含 Next 代理保活实测）后，提取为 cartisan-boot 模块（拟名 `cartisan-sse`）；应用侧只留通道语义与名册。
- PoC 清单（片 1 验收前过一遍，各 ≤半天，预写备选）：① swagger UI 同源请求是否自动携带登录 cookie；② Next rewrites 对 SSE 的缓冲/超时行为与 `:ping` 心跳有效性。
