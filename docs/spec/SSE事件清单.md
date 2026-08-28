# SSE 事件清单（正本）

> 平台 SSE 事件的名册与信封正本，[ADR-0001](../adr/0001-swagger-contract-and-sse-channels.md) 定稿。前端消费以本文 + swagger 端点描述为准。
> 事件只让 UI「活」，不承担正确性：断线丢失可接受，状态以 REST 查询为准。
>
> **治理**：新增顶层 type 必须先进本清单再上线（code review 检查）；代码侧只允许引用各 BC 的 `XxxEventTypes` 常量类，禁止字符串字面量散落。

## 信封（两通道统一）

```
event: event
id: {streamId}:{seq}          # 通知通道 streamId=projectId；agent 流通道 streamId=runId
data: {"type":"...","payload":{...},"ts":"2026-08-19T02:15:33.123Z"}
```

- SSE name 恒为 `event`，前端每通道一个 listener。
- `payload` 恒为对象，必带关联字段；**payload 内禁用 `type` 键名**。
- 心跳：每 15s 发注释行 `:ping`（不进 listener，仅保活）。
- 订阅：`GET /api/events?projectId=xxx` / `GET /api/agent-events?projectId=xxx&runId=xxx`；缺省 = 全量。过滤参数与 payload 关联字段同名，多参数 AND。

## 通道一：平台通知（`GET /api/events`）

平台状态变化的广播；编排层在副作用真实落定后发射；**永不补发**，重连后 REST 重查。

| type | payload 字段 | 示例 |
|---|---|---|
| `workspace-created` | `projectId` `projectName` `container` `projectType` `engine` | `{"projectId":"a1b2c3d4","projectName":"官网 demo","container":"aiplatform-dev-a1b2c3d4","projectType":"WEBSITE","engine":"opencode"}` |
| `stage-changed` | `projectId` `stage` `stageLabel` `approved?` `rejected?` `reason?` | `{"projectId":"a1b2c3d4","stage":"DEV","stageLabel":"开发","approved":true}`（`reason` 驳回时携带，[A3 票 #9](https://github.com/ZhangColin/aiplatform-server/issues/9)） |
| `preview-ready` | `projectId` `url` | `{"projectId":"a1b2c3d4","url":"http://localhost:30080"}` |
| `workspace-destroyed` | `projectId` | `{"projectId":"a1b2c3d4"}` |
| `task-updated` | `projectId` `taskId` `status` | `{"projectId":"a1b2c3d4","taskId":"t1","status":"SUBMITTED"}`（任务状态每次迁移；[A4 票 #10](https://github.com/ZhangColin/aiplatform-server/issues/10) 新增） |
| `document-updated` | `projectId` `documentType` | `{"projectId":"a1b2c3d4","documentType":"PRD"}`（工作区文档产物写出/修订落定后广播；v1 唯一写入方 = BA 的 savePrd（[#49](https://github.com/ZhangColin/aiplatform-server/issues/49)），每次执行必发；前端按**失效为主**模式消费——invalidate 文档域 + 对话区提示胶囊，内容经 `GET /api/projects/{id}/prd` 重拉。[#41](https://github.com/ZhangColin/aiplatform-server/issues/41) 新增） |
| `project-renamed` | `projectId` `projectName` | `{"projectId":"a1b2c3d4","projectName":"品牌官网"}`（异步取名落库成功顶替占位名后发射，取名线程 save 提交后即发——ADR-0001 时序同款；前端失效 projects 域重拉，停留中的页面上名字静默浮现。守卫不覆写（用户已改名/取名已完成）与取名失败保占位**均不发**——失败静默是既有设计，改名端点兜底。[#52](https://github.com/ZhangColin/aiplatform-server/issues/52) 新增） |

> **`workspace-created` 信号语义（[#61](https://github.com/ZhangColin/aiplatform-server/issues/61)）**：该信号表示「工作区记录已落库、容器后台置备中」，**不再是「容器就绪」**——创建即返回，docker 置备后台收敛到 ready/failed。前端以「记录存在」即可进对话；环境能力（跑代码/出 PRD）的就绪性以 REST 查询工作区 `status`（provisioning/ready/failed）为准，信号非权威（CONTEXT.md「平台通知」）。

## 通道二：agent 流（`GET /api/agent-events`）

一次智能体运行的增量过程流（LLM 交互过程流的细化）；payload 必带 `runId`，`sessionId` 会话建立后携带。`projectId` 由业务编排桥接（片5）注入；**片2a 底座任务端点（`POST /api/workspaces/{id}/agent/tasks`）直发的事件以 `workspaceId` 关联**（底座零业务概念，无 projectId）。订阅过滤：`?runId=`（任务进度页「看某个运行才挂」的常规姿势）/ `?workspaceId=`（片2a 底座直发）/ `?projectId=`（片5 起），可叠用（AND）。

通道是**带近期帧缓冲的热流**（[#56](https://github.com/ZhangColin/aiplatform-server/issues/56)，[#53](https://github.com/ZhangColin/aiplatform-server/issues/53)）：帧一经发射即进 per-channel 有界缓冲（零订阅时也进），默认最近 1000 帧、配置 `app.agent-stream.replay-depth`。**新连接**（无 `Last-Event-ID` 值——请求头缺席或空串）先收命中订阅过滤的最近缓冲帧（原事件 id，与实时帧同一 id 口径）、再无缝进实时流——起跑即死的 error 帧晚到订阅也可见，刷新后最近过程历史不消失；**断线重连**（浏览器自动携带非空 `Last-Event-ID`）不补发、不做 seq 续传，前端对齐维持 REST 重查兜底。缓冲为**单实例内存态**（重启即失），多实例化时需重估（B0 蓝图 §3 升级路径）。

下表「payload 字段」列的关联字段 = `runId`（必带）+ `projectId`（片5 业务桥接注入；片2a 底座直发为 `workspaceId`，事件流**结束时整批到达**——同步 message 的已知限制，逐 part 增量是升级路径）。

两类事件：

- **平台事件**（封闭集合，注册制）：字段扁平，下表为准；代码侧引用 `AgentEventTypes` 常量（base.agentengine）；
- **引擎透传事件**（开放集合）：`data` 字段内为引擎 part 原样（如 opencode `part.type` 直传），下表列已知名型。

| type | 类别 | payload 字段 | 说明 |
|---|---|---|---|
| `task-start` | 平台 | `projectId` `runId` `prompt` `model` `engine?` | 运行开始（runId 随任务响应同值返回） |
| `role-assigned` | 平台 | `projectId` `runId` `role` `roleLabel` `stage` `engine` | 角色卡分配 |
| `knowledge-retrieved` | 平台 | `projectId` `runId` `items` | 沉淀助手注入；items = `[{kind, projectName, title, snippet?}]`（kind/projectName = 来源项目，命中可见）——[A5 票 #11](https://github.com/ZhangColin/aiplatform-server/issues/11) 扩展 |
| `session-created` | 平台 | `projectId` `runId` `sessionId` `engine?` | 会话建立 |
| `error` | 平台 | `projectId` `runId` `message` | 运行失败 |
| `task-finish` | 平台 | `projectId` `runId` `sessionId` `finish` | 运行结束（finish = 引擎结煞语 end/error；`cancelled` = 平台终止权威帧——[票 #38](https://github.com/ZhangColin/aiplatform-server/issues/38) 新增值） |
| `wait-raised` | 平台 | `projectId` `runId` `waitId` `kind` `summary` | 等待点出现（kind=QUESTION/PERMISSION；summary 为适配器提取的中性短文本）——[A1 票 #5](https://github.com/ZhangColin/aiplatform-server/issues/5) 新增 |
| `wait-settled` | 平台 | `projectId` `runId` `waitId` `outcome` | 等待点关闭（outcome=answered/approved/denied/deferred/cancelled——`cancelled` 为运行终止联动收口，[票 #38](https://github.com/ZhangColin/aiplatform-server/issues/38) 新增值）——替换原 `questions-answered` / `permission-replied` |
| `text` | 引擎透传 | … + `data` | 最终文本 |
| `reasoning` | 引擎透传 | … + `data` | 思考增量 |
| `patch` | 引擎透传 | … + `data`（`path` `diff` `edits`） | 代码补丁 |
| `tool` | 引擎透传 | … + `data` | 工具调用 |
| `step-start` / `step-finish` | 引擎透传 | … + `data` | 步骤边界 |

> **运行终止帧序（[#38](https://github.com/ZhangColin/aiplatform-server/issues/38)，硬约束）**：`POST /api/projects/{projectId}/agent/runs/{runId}/cancel`（用户终止）与 deny cap 平台终止共用一条平台终止路径（abort 引擎会话当前执行 + run 名下 PENDING 等待点收口 EXPIRED），SSE 帧序恒为 `wait-settled(outcome=cancelled) × N → task-finish(finish=cancelled)`——**顺序不可反**：前端 wait-settled 一律把 run status 拉回 running，终态帧必须最后落地（cancelled 粘性终态与后帧覆盖容忍归前端消费侧）。平台是 `cancelled` 终态帧的权威发射方，引擎自然帧照透不抑制（opencode abort 后引擎自发的 error/end 帧仍会出现，前端以平台帧为准）；dsh abort 恒 no-op，接受 best-effort（平台帧照发，引擎进程不真停）。幂等：重复终止 200 空转（无 PENDING 即无 wait-settled 帧，task-finish 同值重发无害）。BA/对话轨道（engine=agentscope）终止 = 平台关闸，无引擎帧，平台帧是唯一终态收口；在飞续跑被 interrupt 透出的引擎 error 帧照透。

> **对话智能体事件桥（#45，ADR-0002）**：AgentScope HarnessAgent 的事件经单点映射表（`AgentscopeEventMapper`）转本表帧型，走同一通道同一信封——`engine=agentscope`，帧序 `task-start → session-created（sessionId 首见）→ 过程帧 → task-finish/error`。与 opencode 的差异：opencode 同步 message 整批回，`text` 帧是最终文本；AgentScope 流式回，`text`/`reasoning` 帧为增量（`data.delta`，前端按序拼接）。`tool` 帧 `data` 为 `{toolCallId, toolName, phase: start|end}`，`step-*` 对应模型调用边界。HITL 挂起→等待点（#48）：`RequireUserConfirmEvent` → `wait-raised`（同一帧型、同一落库口径——`kind` 按待确认工具判：`ask_user` 提问 = QUESTION，其余工具确认/敏感动作 = PERMISSION；QUESTION 的 `data.questions` 为 ask_user 入参的前端问答卡投影：`[{header, question, multiple(恒 false), custom(恒 true), options[{label}]}]`，与 opencode `pendingQuestions` 载荷形状对齐，`summary` 取问题文本）；挂起轮不发 `task-finish`（软终点，等 settle 续跑后收口），settle（答复/批准/拒绝）经 `AgentscopeWaitResponder` 重建 ConfirmResult 续跑（再挂起/终态同口径）；会话状态落 PostgreSQL（`cat_agent_state`），平台重启后按会话标识恢复续跑。BA 访谈（#40）：创建即开场、role-assigned 带 `engine=agentscope` 标双轨，自由补充遇在悬问答时输入即答复（settle 化解，帧续在原 run）。

> 字段表为初版，随片 2 / 片 5 spec 细化；信封与名册的任何变更即改本文。

## 前端通用模块（约定）

门户布局级挂通知通道实例（常开），任务进度页挂 agent 流实例（看运行才挂）；模块统一管连接建立、心跳透明、自动重连、重连后 REST 重查钩子、按 type 分发回调——页面只声明关心的 type，不重复写连接逻辑。
