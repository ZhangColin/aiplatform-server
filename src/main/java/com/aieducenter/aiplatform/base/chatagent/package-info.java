/**
 * ChatAgent Context（base.chatagent）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>对话智能体域设施（ADR-0002 双轨分野：对话智能体平台级，与 base.agentengine
 *       编码引擎分立起步、互不搅动）——内核为 AgentScope Java 2.0 HarnessAgent</li>
 *   <li>模型串解析（provider:modelId 白名单，暂仅 deepseek，#44）</li>
 *   <li>HarnessAgent 构建工厂与 RuntimeContext 组装（sessionId/userId 寻址状态槽位）</li>
 *   <li>对话级用量埋点（模型调用事件 → UsageEvent，run 结束上报恰一条，#44）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座对话智能体接入能力，不含业务角色概念（BA 等角色卡在 business 侧，接线归
 * 基座 B #45：事件 → SSE、HITL → 等待点、workspace = 项目工作区、会话恢复落
 * PostgreSQL）。错误码前缀 {@code CHAT_}。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：ChatAgentClient 端口、对话命令/回复/进度模型、模型串解析、计量归属</li>
 *   <li>infrastructure - 基础设施层：AgentScope（HarnessAgent 工厂 + 流式适配 + 计量埋点）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "ChatAgent", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.chatagent;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
