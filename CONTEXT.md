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

**置备状态（Provisioning Status）**：
环境的生命周期状态：provisioning（置备中）→ ready（就绪）/ failed（失败）。环境的引用（对话）与置备完成解耦——对话即时可用、置备后台收敛；状态只用于管理环境可用性，不暴露给对话 UI，失败在工作台可见、需要环境的能力时阻塞。
_Avoid_: 状态机暴露给用户（那是无缝体验的破坏）；与运行（Run）的状态混用（那是执行过程，两码事）

**开发智能体（Coding Agent）**：
有代码能力的智能体（开发 / Review / 修 bug / 跑测试），经「开发智能体适配层」接入。

**开发智能体引擎（Coding Agent Engine）**：
一个具体的 coding agent 运行时（opencode / dsh），后端经 `CodingAgentAdapter` 路由。平台用哪个引擎由服务端统一配置（后台切换，票 #42）——引擎选择不在创建参数与用户界面；生效口径 = 新项目生效（创建时固化进项目记录）、存量不迁（存量项目固化其创建时引擎跑完）。

**开发智能体适配层（Coding Agent Adapter）**：
抹平各引擎差异的薄 adapter：runTask / pendingQuestions / replyQuestions / replyPermission / health。systemPrompt 与 modelId 是入参——适配层不含角色概念。

**等待点（Wait Point）**：
智能体运行中挂起等人反馈的底座实体：waitId 稳定标识，kind = 问答 / 权限；生命周期 pending → settled / expired / cancelled，落库跨重启存活。业务层以 waitId 引用（转任务、回填续跑）；中性寻址，不含项目概念。
_Avoid_: HITL 等待点（业务侧交互概念，等待点是其底座承载）、决策门

**运行（Run）**：
一次任务下发的智能体执行过程，runId 为其标识（任务端点生成、随响应返回）。一次运行产出连串 agent 流事件。
_Avoid_: 会话（那是跨运行的持久寻址）

**运行终止（Run Termination）**：
人（或平台守卫，如权限拒绝达上限）主动终止一次进行中/挂起中的运行：run 名下挂起等待点联动收口为已失效（expired），agent 流以 `cancelled` 终态收尾。引擎侧终止能力是会话粒度——终止该运行所属会话的当前执行，幂等空转（重复终止不炸）。
_Avoid_: 等待点 cancelled（那是复用会话清理专用态「已清理」，与运行终止两码事——运行终止下等待点归宿是 expired）；取消任务（那是任务 BC 的 CANCELLED）

**agent 会话（Agent Session）**：
跨运行的持久寻址：引擎侧会话标识（opencode `ses_*` / dsh 适配器自生成）落库 `agt_agent_sessions`，按 workspaceId 寻址、跨重启存活；复用 sessionId 续跑 = 给会话的新消息（任务完成回填的锚点）。与 runId（一次运行）并存不混淆。
_Avoid_: 运行（一次执行过程）、需求调研会话（业务侧对话沉淀，两回事）

**agent 流事件（Agent Stream Event）**：
智能体运行过程的增量事件（文本、思考、工具调用、代码补丁）——与 LLM 交互过程流的细化，一次运行一连串。通道是带近期帧缓冲的热流（`Flux.replay(N)` 语义）：零订阅时的帧也进有界缓冲，新订阅（无 Last-Event-ID）先重放命中过滤谓词的最近帧再进实时流；重连（带 Last-Event-ID）维持 REST 重查兜底不重放（#53）。
_Avoid_: 平台通知（那是状态变化广播，两类不混）

**对话智能体（Conversational Agent）**：
BA 等对话型角色的运行形态：平台进程内的 AgentScope HarnessAgent（base.chatagent，ADR-0002），per-call 以 RuntimeContext（sessionId/userId）寻址状态槽位；模型串 `provider:model`（白名单暂仅 deepseek），对话级用量埋点（engine=agentscope）。与编码引擎双轨分野：`Project.engine` 只指编码引擎，对话角色不走 opencode/dsh。
**事件桥（Event Bridge）**：AgentScope 类型化事件 → 平台 agent 流事件帧的单点映射（文本/思考增量、工具调用、轮次边界、终态），runId 锚定、关联字段（projectId）随帧注入，经既有 agent 流通道触达（前端零新增协议，#45）。
**工作区桥（Workspace Bridge）**：对话智能体的 workspace 锚定两形态——本地兜底（配置路径）与项目 dev 工作区（workspaceId 解析为 dev 容器，文件操作经 docker exec 落容器 `/workspace`，与编码引擎同视图，写入即进源码包，#45）。
**等待点双向桥（Wait Bridge）**：对话智能体挂起（权限确认/向用户提问）与平台等待点的双向通道——挂起 → `wait-raised` 落既有等待点（settle 即续跑），settle 三型答复 → ConfirmResult 重建续跑；deny cap / run 终态联动等平台守卫同口径生效（#48）。
**会话恢复（Session Recovery）**：对话智能体的 AgentState 落 PostgreSQL（(userId, sessionId) 槽位）+ 会话行表判定——平台重启后按会话标识恢复续跑，访谈上下文不丢（#48）。
**BA 会话绑定（BA Session Binding）**：projectId → BA 会话的无表派生（sessionId=`ba-{projectId}`，userId=项目 owner）——建项目即访谈、答卡 settle 续跑、对话自由补充都续这一条会话，上下文不劈叉（#40）。自由补充遇在悬问答时输入即答复（settle 化解，锚回原 run 不开新轮）。
**驳回回流（Reject Reflow）**：门驳回后的自动回流闭环，两扇门两形态——G1（需求确认）驳回落留痕后门操作内自动起 BA 续轮（意见注入 prompt 续 BA 会话），BA 按意见澄清追问或修订 PRD（savePrd 再执行），用户再确认，往复至通过（#50）；G2（Demo 确认）驳回落留痕后自动起 DEMO 修正 run（意见注入 prompt 续 Demo 会话——工作区最近一次本项目引擎会话），修正完门重新就绪，往复至通过（#46）。G2 驳回带显式「涉及需求变更」标记（requirementChange，v1 不做语义自动判定）时意见同时回流 BA 修订 PRD（document-updated 可观测，修正以新 PRD 为准）；不带标记不惊动 BA。起跑失败不阻断驳回留痕（两路独立护栏）。
_Avoid_: 与开发智能体/编码引擎概念混用（两类运行时、两套适配，不共享会话与引擎配置）；对话智能体的「会话」与编码引擎会话（agt_agent_sessions）混用（会话恢复归 #48 另接线）

**中间件资源（Middleware Resource）**：
项目环境挂载的数据库 / Redis / 对象存储，随环境生命周期供给与隔离（attachResource）。

**产物（Artifact）**：
项目阶段产出的文档/代码等。原件在工作区 `/workspace`，知识副本在 pgvector。

**知识资产（Knowledge Asset）**：
阶段产物的沉淀物。两维模型——维度：业务 / 技术 / 行业；形态：纯知识（检索辅助）→ skill（直接赋能 agent）。

**沉淀助手**：
RAG 检索注入机制——agent 阶段开始时检索历史知识拼入上下文（当前实现 = 后端检索注入）。

**计量上下文（Metering Context）**：
用量采集与聚合查询的能力域（base.metering）：UsageEvent 协议（token 五档 input/output/cache_read/cache_write/reasoning）+ 按 subject 聚合查询 + 费用换算（见「平台成本」）。**零商业概念**：存储只记 token、换算只出平台成本、无加价/售价/账单。平台内起步，独立计量服务是演化方向（换上报 / 查询适配器）。
_Avoid_: 计费（那是商业层概念，加价/售价在业务层，v1 出局）

**单价表（Price Table）**：
平台成本换算用的单价数据（`met_price_entries`，base.metering 私有表）：provider × model × token 档位 × 币种，带生效区间；改价 = 关旧行开新行，历史成本按事件时点单价不漂移。
_Avoid_: 价格表/费率（暗示售价）、业务层配置下发（表在计量上下文内，不经端口暴露）

**平台成本（Platform Cost）**：
平台为模型调用付出的成本金额（token 用量 × 事件时点生效单价，按币种分桶不折算）——运营口径，非商业概念；不含加价，加价属业务层计费策略（v1 出局）。

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

**PRD（Product Requirement Document）**：
项目 dev 工作区的 `docs/PRD.md` 文件——BA 访谈收敛后写出的需求**事实源**：编码智能体/DEV/ARCH 直读（零平台注入搬运），写入即进源码包，删除项目与工作区同亡。平台侧只落「PRD 已产出」项目级状态位（G1 门谓词查位不查文件系统）；REST 读端点直读工作区（无文件 = 未产出 404）；写出/修订经通知通道广播 `document-updated`（前端失效重拉）。v1 无版本链，只最新版。
_Avoid_: PRD 库表/文档服务（事实源在工作区，不建文档表；版本链将来再加库投影）、需求池条目（那是收件记录，不是产物）

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
- **计量上下文平台内起步**：base.metering 承担采集 / 存储 / 聚合 / 换算 / 查询（UsageEvent 只记 token；单价表与平台成本换算同在 base.metering，A6）；独立计量服务是演化方向（四段整体迁，换上报 / 查询适配器）；加价/售价在业务层（v1 出局）；支付独立服务，业务层只对接。**计量 ≠ 路由**：模型选择/档位路由属 agentengine（A6 §6）。
- **MVP 不引入**：AgentScope、强隔离 microVM（成规模才上）、腾讯云向量库（>100 万向量才迁）。
