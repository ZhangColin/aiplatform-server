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

## 通道二：agent 流（`GET /api/agent-events`）

一次智能体运行的增量过程流（LLM 交互过程流的细化）；payload 必带 `runId`，`sessionId` 会话建立后携带。`projectId` 由业务编排桥接（片5）注入；**片2a 底座任务端点（`POST /api/workspaces/{id}/agent/tasks`）直发的事件以 `workspaceId` 关联**（底座零业务概念，无 projectId）。订阅过滤：`?runId=`（任务进度页「看某个运行才挂」的常规姿势）/ `?workspaceId=`（片2a 底座直发）/ `?projectId=`（片5 起），可叠用（AND）。

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
| `task-finish` | 平台 | `projectId` `runId` `sessionId` `finish` | 运行结束 |
| `wait-raised` | 平台 | `projectId` `runId` `waitId` `kind` `summary` | 等待点出现（kind=QUESTION/PERMISSION；summary 为适配器提取的中性短文本）——[A1 票 #5](https://github.com/ZhangColin/aiplatform-server/issues/5) 新增 |
| `wait-settled` | 平台 | `projectId` `runId` `waitId` `outcome` | 等待点关闭（outcome=answered/approved/denied/deferred）——替换原 `questions-answered` / `permission-replied` |
| `text` | 引擎透传 | … + `data` | 最终文本 |
| `reasoning` | 引擎透传 | … + `data` | 思考增量 |
| `patch` | 引擎透传 | … + `data`（`path` `diff` `edits`） | 代码补丁 |
| `tool` | 引擎透传 | … + `data` | 工具调用 |
| `step-start` / `step-finish` | 引擎透传 | … + `data` | 步骤边界 |

> **对话智能体事件桥（#45，ADR-0002）**：AgentScope HarnessAgent 的事件经单点映射表（`AgentscopeEventMapper`）转本表帧型，走同一通道同一信封——`engine=agentscope`，帧序 `task-start → session-created（sessionId 首见）→ 过程帧 → task-finish/error`。与 opencode 的差异：opencode 同步 message 整批回，`text` 帧是最终文本；AgentScope 流式回，`text`/`reasoning` 帧为增量（`data.delta`，前端按序拼接）。`tool` 帧 `data` 为 `{toolCallId, toolName, phase: start|end}`，`step-*` 对应模型调用边界。HITL 挂起→等待点的映射归 #48。

> 字段表为初版，随片 2 / 片 5 spec 细化；信封与名册的任何变更即改本文。

## 前端通用模块（约定）

门户布局级挂通知通道实例（常开），任务进度页挂 agent 流实例（看运行才挂）；模块统一管连接建立、心跳透明、自动重连、重连后 REST 重查钩子、按 type 分发回调——页面只声明关心的 type，不重复写连接逻辑。
