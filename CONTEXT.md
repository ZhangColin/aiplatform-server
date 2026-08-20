# CONTEXT.md — AI 开发平台后端领域词汇表

> 平台语言（glossary）。写代码、写文档、讨论时都用这里的词；要改词先改这里。
> 迁入自 `deepseek-harness/CONTEXT.md`（B0 裁剪：剔除 Phase B/C 专属与出局项，按 base/business 分区重组）。

## 平台结构

**底座（base）**：
基础设施能力层（端口-适配器形态）的模块分区——环境、智能体适配、事件广播、知识、流程引擎。零业务概念，只经端口被业务层消费；同进程调用，将来拆服务换端口实现。
_Avoid_: 基础设施层（与 BC 内 infrastructure 层混淆）、网关

**业务层（business）**：
交付业务的模块分区——项目、任务、计量对接、资产、流程配置、账号。按 DDD 设计（限界上下文 / 聚合 / 领域事件 / 统一语言）。
_Avoid_: 包装（旧称）、应用层

**限界上下文（Bounded Context）**：
一个有自己领域模型与语言的完整业务/能力域。分区不是 BC——base/business 是架构标签，BC 是区内每个包（workspace / agentengine / eventhub / knowledge / process / project / …）。

**平台通知（Platform Notification）**：
平台状态变化（工作区创建、阶段推进、预览就绪、销毁）的对外广播；只作实时呈现的信号，状态以查询为准。
_Avoid_: agent 流事件、应用事件

**应用事件（Application Event）**：
应用层经发布者端口发出的跨限界上下文 / 跨应用协作事件（cartisan ApplicationEvent：进程内分发起步，跨服务换消息发布器；事务提交后送达）。
_Avoid_: 领域事件（聚合内事件，已废弃不用）、agent 流事件、平台通知

## base 区

**环境（Environment）**：
一个抽象的计算环境，kind = dev / test / prod。dev = agent 工作区（写码/打包/运行）；test/prod = 纯运行打包产物。六条能力面：createWorkspace / exec+文件 / exposePort / attachResource / snapshot+restore / isolate。

**开发智能体（Coding Agent）**：
有代码能力的智能体（开发 / Review / 修 bug / 跑测试），经「开发智能体适配层」接入。

**开发智能体引擎（Coding Agent Engine）**：
一个具体的 coding agent 运行时（opencode / dsh），按项目选择，后端经 `CodingAgentAdapter` 路由。

**开发智能体适配层（Coding Agent Adapter）**：
抹平各引擎差异的薄 adapter：runTask / pendingQuestions / replyQuestions / replyPermission / health。systemPrompt 与 modelId 是入参——适配层不含角色概念。

**等待点（Wait Point）**：
智能体运行中挂起等人反馈的底座实体：waitId 稳定标识，kind = 问答 / 权限；生命周期 pending → settled / expired / cancelled，落库跨重启存活。业务层以 waitId 引用（转任务、回填续跑）；中性寻址，不含项目概念。
_Avoid_: HITL 等待点（业务侧交互概念，等待点是其底座承载）、决策门

**运行（Run）**：
一次任务下发的智能体执行过程，runId 为其标识（任务端点生成、随响应返回）。一次运行产出连串 agent 流事件。
_Avoid_: 会话（那是跨运行的持久寻址）

**agent 流事件（Agent Stream Event）**：
智能体运行过程的增量事件（文本、思考、工具调用、代码补丁）——与 LLM 交互过程流的细化，一次运行一连串。
_Avoid_: 平台通知（那是状态变化广播，两类不混）

**中间件资源（Middleware Resource）**：
项目环境挂载的数据库 / Redis / 对象存储，随环境生命周期供给与隔离（attachResource）。

**产物（Artifact）**：
项目阶段产出的文档/代码等。原件在工作区 `/workspace`，知识副本在 pgvector。

**知识资产（Knowledge Asset）**：
阶段产物的沉淀物。两维模型——维度：业务 / 技术 / 行业；形态：纯知识（检索辅助）→ skill（直接赋能 agent）。

**沉淀助手**：
RAG 检索注入机制——agent 阶段开始时检索历史知识拼入上下文（当前实现 = 后端检索注入）。

**计量上下文（Metering Context）**：
用量采集与聚合查询的能力域（base.metering）：UsageEvent 协议（token 五档 input/output/cache_read/cache_write/reasoning，只记 token 不记钱）+ 按 subject 聚合查询。平台内起步，独立计量服务是演化方向（换上报 / 查询适配器）。

**阶段（Stage）**：
阶段推进引擎（base.process）的步骤单元；序列由业务侧传入（平台主链定义：阶段列表 + 每阶段可空默认角色 + 产物清单），引擎只管推进 / 驳回停留 / 门禁计数，不知业务内容。
_Avoid_: 期（那是 business 的 Iteration）、模板（主链只有一条，不存在按项目类型选择的过程配置；过程演化 = 业务代码演化）

## business 区

**项目（Project）**：
用户的长期实体，从第一次提需求创建起持续存在，一期期推进。开发工具（工作区/智能体任务）与任务/bug 系统挂项目常开（不锁期）；「开发中/已交付」是有无 OPEN 期的派生投影，归档是单向终点动作。Phase A 中一个项目 = 一个 dev 环境。

**项目团队**：
人 + 智能体；**人做决策、智能体做工作**。

**期（Iteration）**：
项目的一轮开发过程（需求定稿 → 开发 → 验收），开发团队的过程组织单元；阶段状态机与确认挂在期上。v1 每项目 1 期、≥2 期才在 UI 显示——用户视角无期，只有提需求/提 bug 与确认。
_Avoid_: 阶段（那是 base 的 Stage）

**任务（Task）**：
需要人参与的工作单元，与项目过程正交。产生于 HITL 等待点（人选择外包时）或人的自发安排；执行方 = 自己 / 内部指派 / 外包 OPC。过程只等完成信号，不关心谁做、怎么做。

**HITL 等待点（HITL Point）**：
智能体执行中挂起、等人反馈的点（要人决策 / 测试 / 提供信息……）。人不反馈智能体就停在这里；可当场处理，也可转成任务外包，任务完成后智能体继续。

**决策门（Decision Gate）**：
主链上需要人拍板的关口（v1 四扇：需求确认 / Demo 确认 / 开发完成确认 / 验收；用户拍板 3 扇 + 开发平台 1 扇），挂在期上；approve 推进 / 驳回停留（必带理由）。拍板留痕见「确认记录」。

**确认记录（Confirmation Record）**：
决策门拍板的 append-only 留痕（谁 / 何时 / 通过或驳回 / 理由），挂期；「待确认」由条件推导（就绪才亮按钮），非落库实体。
_Avoid_: 通知、待办（那是工作台的计算式投影）

**需求池（Demand Pool）**：
项目级、随时可记的需求/bug 收件清单（内容 + 类型 + 来源 + 时间）；开新期时作为需求梳理输入，记录不等同开工。
_Avoid_: 待办、任务（那是已派发的工作单元）

**Bug**：
任务交付物同时是项目级的独立缺陷记录（标题/描述/复现步骤/严重级/附件）；三态：待修复 → 已修复 → 复测通过，**复测通过是唯一关闭态**（开发平台手工关闭是其带理由的别名动作）；「未关闭」= 状态≠复测通过。挂项目常开，与过程正交（期后修复照常闭环）。
_Avoid_: 需求池 BUG 类型条目（那是收件清单记录，不是缺陷实体）

**素材（Source Material）**：
用户在沟通中提供的上传物料；与产物相对（产物 = 阶段产出，素材 = 用户输入）。

**沟通纪要（Communication Notes）**：
需求调研等会话的过程记录与总结——项目知识的重要来源。v1 = 需求问答会话（问答对）+ 验收反馈（确认留痕），无中期回路。

**四门户（端）**：
平台的四个工作门户——需求端（用户）、开发平台（公司内部）、任务平台（OPC 登录）、管理后台（独立，内容后续生长）。同一平台、同一后端；OPC 端不与开发后台合并。

**业务智能体（Business Agent）**：
BA / Demo / 交付 / 沉淀助手等业务角色；MVP 用 agent preset（角色卡：systemPrompt + modelId）实现。
_Avoid_: 开发智能体（那是走适配层的 coding agent）

**账号（Account）**：
平台内的用户档案，首次登录按外部 ID 自动建档（外部 ID + 显示名，无角色概念）；v1 单账号可进全部工作台，后端不做角色过滤。
_Avoid_: 用户/Subject（那是 identity 侧的账号体系）、角色（v1 不建模）

**待办（Todo）**：
需要人处理的事项列表——各处已有状态的实时计算式投影（等待答复 / 门待拍板 / 任务待确认 / 可发复测 / 新任务 / 被驳回），非独立落库实体，无推送。
_Avoid_: 通知（信息性广播，v1 无此概念）、消息

**工作台聚合（Workbench）**：
门户读模型的查询侧聚合上下文（无表）：把散在各上下文的状态拼成待办列表等门户视图。
_Avoid_: 门户/端（那是面向用户的四个门户概念）、待办（那是它聚合出的一种视图）

## 关键边界

- **分区规则**：`base` 不得 import `business`（ArchUnit 守护）；业务层只经端口调底座。
- **门与 HITL 不统一建模**：决策门 = 流程层关口（business，映射 base.process）；HITL 等待点 = 智能体层挂起（base.agentengine 通道）。两层分离、UI 统一呈现为「待我处理」（Code-Canvas 双表实践佐证）。
- **工具与过程正交**：工作区/智能体任务/任务/bug 挂项目常开；期状态机与确认是过程覆盖层——期关闭不锁开发能力（期后修 bug 照常进行）。
- **框架托管策略**：代码类智能体 → coding agent（OpenCode/DSH 经适配层）；业务类智能体 → AgentScope 类框架（MVP 不引入，preset 先行）；两类经 MCP/A2A 互通。
- **计量上下文平台内起步**：base.metering 承担采集 / 存储 / 聚合 / 查询（UsageEvent，只记 token 不记钱）；独立计量服务是演化方向（换上报 / 查询适配器），单价 / 加价在业务层；支付独立服务，业务层只对接。
- **MVP 不引入**：AgentScope、强隔离 microVM（成规模才上）、腾讯云向量库（>100 万向量才迁）。
