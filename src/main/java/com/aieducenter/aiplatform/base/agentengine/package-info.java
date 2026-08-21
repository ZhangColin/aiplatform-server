/**
 * AgentEngine Context（base.agentengine）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>开发智能体适配层（CodingAgentAdapter 端口：runTask / pendingQuestions /
 *       replyQuestions / replyPermission / health；systemPrompt 与 modelId 是入参）</li>
 *   <li>引擎注册表（OpenCode / Dsh，显式注册 + 能力矩阵如实暴露）+ 模型默认值配置</li>
 *   <li>agent 会话落库（agt_agent_sessions，按 workspaceId 寻址、跨重启存活）</li>
 *   <li>agent 流通道（GET /api/agent-events，runId 关联的任务过程事件透传）</li>
 *   <li>run 级用量埋点（OpenCode step-finish 五档求和，run 结束上报恰一条 UsageEvent）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座智能体接入能力，抹平引擎差异、不含角色概念（角色卡在 business.project）。
 * 表前缀 {@code agt_}，错误码前缀 {@code AGT_}。等待点统一模型（agt_pending_waits）
 * 归片2b（票 #21）。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：适配器端口、任务命令/事件模型、会话聚合（agt_agent_sessions）</li>
 *   <li>application - 应用层：引擎注册表、任务编排（AgentTaskAppService）、会话查询、
 *       agent 流通道语义（AgentStreamAppService）</li>
 *   <li>infrastructure - 基础设施层：OpenCode（serve 引导 + HTTP 适配 + 用量求和）、
 *       Dsh（headless exec 适配）、模型配置与 Key 解析、工作区句柄取用</li>
 *   <li>endpoints - 北向接口层：任务/交互/引擎矩阵 REST、agent 流 SSE 通道</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "AgentEngine", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.agentengine;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
