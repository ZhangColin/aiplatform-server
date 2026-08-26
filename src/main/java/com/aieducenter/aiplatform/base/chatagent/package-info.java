/**
 * ChatAgent Context（base.chatagent）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>对话智能体域设施（ADR-0002 双轨分野：对话智能体平台级，与 base.agentengine
 *       编码引擎分立起步、互不搅动）——内核为 AgentScope Java 2.0 HarnessAgent</li>
 *   <li>模型串解析（provider:modelId 白名单，暂仅 deepseek，#44）</li>
 *   <li>HarnessAgent 构建工厂与 RuntimeContext 组装（sessionId/userId 寻址状态槽位；
 *       工作区分型：本地 / 项目 dev 容器，#45）</li>
 *   <li>事件桥（#45）：AgentScope 类型化事件 → 平台 agent 流事件帧（映射表单点
 *       AgentscopeEventMapper），经既有 agent 流通道触达前端（runId 锚定 +
 *       关联字段注入，编码引擎零变化）</li>
 *   <li>工作区桥（#45）：workspaceId → 项目 dev 工作区解析（docker exec 文件面，
 *       写入即落源码包）</li>
 *   <li>对话级用量埋点（模型调用事件 → UsageEvent，run 结束上报恰一条，#44）</li>
 *   <li>等待点双向桥（#48）：智能体挂起（RequireUserConfirm，含 ask_user 向用户
 *       提问）→ wait-raised 帧 → 平台等待点（AGENT 等待点统一模型，REST 可查/SSE
 *       可见）；settle（答复/批准/拒绝）→ AgentscopeWaitResponder 重建
 *       ConfirmResult 续跑（一 run 多 approve 点）；deny cap / run 终态联动等
 *       平台守卫同口径生效（WaitResponderDirectory 寻址，不进编码引擎能力矩阵）</li>
 *   <li>会话恢复（#48）：AgentState 落 PostgreSQL（cat_agent_state，Postgres
 *       AgentStateStore）；会话行按表判定首见（跨重启不重发 session-created、
 *       settle 可续跑前置）——平台重启后同一会话标识恢复续跑，访谈上下文不丢</li>
 *   <li>访谈工具（#48 ask_user / #49 savePrd）：平台自有对话智能体工具——向用户
 *       提问挂起（等待点 QUESTION）与 PRD 落盘（写项目工作区 + 经 PrdArtifactPort
 *       回调业务效果，效果归 business.project；savePrd 仅项目 dev 工作区注册）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座对话智能体接入能力，不含业务角色概念（BA 等角色卡在 business 侧）。
 * 错误码前缀 {@code CHAT_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：ChatAgentClient 端口、对话命令/回复/工作区锚定模型、模型串解析、计量归属</li>
 *   <li>application - 应用层：ChatAgentAppService（流桥：帧注入关联经既有通道发射；
 *       #48 补等待点拦截——wait-raised 落库 + 终态联动，对齐编码引擎 streamSink 语义）</li>
 *   <li>infrastructure - 基础设施层：工作区句柄解析口 + 会话登记收敛缝 +
 *       AgentScope（HarnessAgent 工厂 + 事件映射 + docker exec 文件面 + 计量埋点 +
 *       PG 状态存储 + 等待点答复通道 + 续跑闸 + ask_user 工具）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "ChatAgent", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.chatagent;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
