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

## base 区

**环境（Environment）**：
一个抽象的计算环境，kind = dev / test / prod。dev = agent 工作区（写码/打包/运行）；test/prod = 纯运行打包产物。六条能力面：createWorkspace / exec+文件 / exposePort / attachResource / snapshot+restore / isolate。

**开发智能体（Coding Agent）**：
有代码能力的智能体（开发 / Review / 修 bug / 跑测试），经「开发智能体适配层」接入。

**开发智能体引擎（Coding Agent Engine）**：
一个具体的 coding agent 运行时（opencode / dsh），按项目选择，后端经 `CodingAgentAdapter` 路由。

**开发智能体适配层（Coding Agent Adapter）**：
抹平各引擎差异的薄 adapter：runTask / pendingQuestions / replyQuestions / replyPermission / health。systemPrompt 与 modelId 是入参——适配层不含角色概念。

**中间件资源（Middleware Resource）**：
项目环境挂载的数据库 / Redis / 对象存储，随环境生命周期供给与隔离（attachResource）。

**产物（Artifact）**：
项目阶段产出的文档/代码等。原件在工作区 `/workspace`，知识副本在 pgvector。

**知识资产（Knowledge Asset）**：
阶段产物的沉淀物。两维模型——维度：业务 / 技术 / 行业；形态：纯知识（检索辅助）→ skill（直接赋能 agent）。

**沉淀助手**：
RAG 检索注入机制——agent 阶段开始时检索历史知识拼入上下文（当前实现 = 后端检索注入）。

**阶段（Stage）**：
阶段推进引擎（base.process）的步骤单元；序列由业务侧模板配置（阶段列表 + 每阶段角色 + 产物），引擎只管推进 / 驳回停留 / 门禁计数，不知业务内容。
_Avoid_: 期（那是 business 的 Iteration）

## business 区

**项目（Project）**：
用户的长期实体，从第一次提需求创建起持续存在，一期期推进。Phase A 中一个项目 = 一个 dev 环境。

**项目团队**：
人 + 智能体；**人做决策、智能体做工作**。

**期（Iteration）**：
项目的一轮完整接单-交付过程（需求 → 开发 → 测试 → 交付）。
_Avoid_: 阶段（那是 base 的 Stage）

**任务（Task）**：
需要人参与的工作单元，与项目过程正交。产生于 HITL 等待点（人选择外包时）或人的自发安排；执行方 = 自己 / 内部指派 / 外包 OPC。过程只等完成信号，不关心谁做、怎么做。

**HITL 等待点（HITL Point）**：
智能体执行中挂起、等人反馈的点（要人决策 / 测试 / 提供信息……）。人不反馈智能体就停在这里；可当场处理，也可转成任务外包，任务完成后智能体继续。

**决策门（Decision Gate）**：
业务流程主链上需要人拍板的关口（v1 四扇：需求确认 / Demo 确认 / 开发完成确认 / 验收；用户拍板 3 扇 + 开发平台 1 扇）。

**素材（Source Material）**：
用户在沟通中提供的上传物料；与产物相对（产物 = 阶段产出，素材 = 用户输入）。

**沟通纪要（Communication Notes）**：
需求调研等会话的过程记录与总结——项目知识的重要来源。

**四门户（端）**：
平台的四个工作门户——需求端（用户）、开发平台（公司内部）、任务平台（OPC 登录）、管理后台（独立，内容后续生长）。同一平台、同一后端；OPC 端不与开发后台合并。

**业务智能体（Business Agent）**：
BA / Demo / 交付 / 沉淀助手等业务角色；MVP 用 agent preset（角色卡：systemPrompt + modelId）实现。
_Avoid_: 开发智能体（那是走适配层的 coding agent）

## 关键边界

- **分区规则**：`base` 不得 import `business`（ArchUnit 守护）；业务层只经端口调底座。
- **门与 HITL 不统一建模**：决策门 = 流程层关口（business，映射 base.process）；HITL 等待点 = 智能体层挂起（base.agentengine 通道）。两层分离、UI 统一呈现为「待我处理」（Code-Canvas 双表实践佐证）。
- **框架托管策略**：代码类智能体 → coding agent（OpenCode/DSH 经适配层）；业务类智能体 → AgentScope 类框架（MVP 不引入，preset 先行）；两类经 MCP/A2A 互通。
- **计量独立服务**：token 计量是独立薄服务；底座只做埋点上报（UsageEvent），不记钱；支付亦独立服务，业务层只对接。
- **MVP 不引入**：AgentScope、强隔离 microVM（成规模才上）、腾讯云向量库（>100 万向量才迁）。
